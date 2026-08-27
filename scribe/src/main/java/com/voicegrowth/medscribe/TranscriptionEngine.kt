package com.voicegrowth.medscribe

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

class ModelRequiredException(message: String) : IllegalStateException(message)

data class TranscriptResult(
    val markdown: String,
    val language: String,
    val speakerCount: Int,
    val diarizationUsed: Boolean
)

internal data class SpeechTurn(val start: Double, val end: Double, val speaker: Int)
private data class TextTurn(val start: Double, val end: Double, val speaker: Int?, val text: String)

class TranscriptionEngine {
    suspend fun transcribe(context: Context, item: ScribeItem, settings: ScribeSettings): TranscriptResult =
        withContext(Dispatchers.Default) {
            if (!ModelManager.isWhisperInstalled(context, settings.whisperModel)) {
                throw ModelRequiredException("${settings.whisperModel.replaceFirstChar(Char::uppercase)} Whisper model is not installed")
            }
            val audioFile = File(item.audioPath)
            require(audioFile.isFile && audioFile.length() > 0L) { "Recording file is unavailable" }

            val pcm = AudioUtils.decodeToMonoPcm16(context, audioFile)
            try {
                val recognizer = createRecognizer(context, settings)
                try {
                    val wantDiarization = settings.diarizationEnabled && ModelManager.isDiarizationInstalled(context)
                    val safeForDiarization = pcm.durationSeconds in 1..MAX_DIARIZATION_SECONDS
                    val turns = if (wantDiarization && safeForDiarization) {
                        runCatching { diarize(context, pcm) }.getOrElse { emptyList() }
                    } else emptyList()

                    val identities = if (
                        turns.isNotEmpty() && settings.voiceRecognitionEnabled && VoiceProfileStore.all(context).isNotEmpty()
                    ) {
                        runCatching { SpeakerIdentityEngine.identify(context, pcm, turns) }.getOrDefault(emptyMap())
                    } else emptyMap()

                    val textTurns = if (turns.isNotEmpty()) {
                        transcribeDiarized(recognizer, pcm, turns)
                    } else {
                        transcribeSequential(recognizer, pcm)
                    }
                    val cleanedTurns = textTurns.map { it.copy(text = MedicalText.clean(it.text)) }
                        .filter { it.text.isNotBlank() }
                    require(cleanedTurns.isNotEmpty()) { "Whisper returned an empty transcript" }

                    val speakerCount = cleanedTurns.mapNotNull { it.speaker }.distinct().size
                    val body = cleanedTurns.joinToString("\n") { it.text }
                    val topics = MedicalText.detectedTopics(body)
                    TranscriptResult(
                        markdown = buildMarkdown(
                            item = item,
                            model = settings.whisperModel,
                            language = settings.language,
                            turns = cleanedTurns,
                            topics = topics,
                            identities = identities,
                            diarizationRequested = wantDiarization,
                            diarizationUsed = speakerCount > 0,
                            skippedForLength = wantDiarization && !safeForDiarization
                        ),
                        language = languageLabel(settings.language),
                        speakerCount = speakerCount,
                        diarizationUsed = speakerCount > 0
                    )
                } finally {
                    recognizer.release()
                }
            } finally {
                pcm.file.delete()
            }
        }

    private fun createRecognizer(context: Context, settings: ScribeSettings): OfflineRecognizer {
        val files = ModelManager.whisperFiles(context, settings.whisperModel)
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = files.encoder.absolutePath,
                        decoder = files.decoder.absolutePath,
                        language = languageCode(settings.language),
                        task = "transcribe",
                        tailPaddings = 1000
                    ),
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
                    provider = "cpu",
                    modelType = "whisper",
                    tokens = files.tokens.absolutePath
                ),
                decodingMethod = "greedy_search"
            )
        )
    }

    private suspend fun diarize(context: Context, pcm: DecodedPcmFile): List<SpeechTurn> {
        coroutineContext.ensureActive()
        val files = ModelManager.diarizationFiles(context)
        val samples = PcmAccess.readResampled16k(pcm, MAX_DIARIZATION_SECONDS)
        if (samples.isEmpty()) return emptyList()
        val diarizer = OfflineSpeakerDiarization(
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                        model = files.segmentation.absolutePath
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = files.embedding.absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                clustering = FastClusteringConfig(numClusters = -1, threshold = 0.55f),
                minDurationOn = 0.2f,
                minDurationOff = 0.5f
            )
        )
        return try {
            diarizer.process(samples)
                .map { SpeechTurn(it.start.toDouble(), it.end.toDouble(), it.speaker) }
                .filter { it.end - it.start >= MIN_TURN_SECONDS }
                .sortedBy { it.start }
                .let(::mergeTurns)
        } finally {
            diarizer.release()
        }
    }

    private fun mergeTurns(input: List<SpeechTurn>): List<SpeechTurn> {
        if (input.isEmpty()) return input
        val out = mutableListOf<SpeechTurn>()
        for (turn in input) {
            val last = out.lastOrNull()
            if (
                last != null && last.speaker == turn.speaker &&
                turn.start - last.end <= MAX_MERGE_GAP &&
                turn.end - last.start <= MAX_ASR_CHUNK_SECONDS
            ) {
                out[out.lastIndex] = last.copy(end = max(last.end, turn.end))
            } else {
                out += turn
            }
        }
        return out.flatMap(::splitTurn)
    }

    private fun splitTurn(turn: SpeechTurn): List<SpeechTurn> {
        if (turn.end - turn.start <= MAX_ASR_CHUNK_SECONDS) return listOf(turn)
        val result = mutableListOf<SpeechTurn>()
        var start = turn.start
        while (start < turn.end) {
            val end = min(turn.end, start + MAX_ASR_CHUNK_SECONDS)
            result += turn.copy(start = start, end = end)
            start = end
        }
        return result
    }

    private suspend fun transcribeDiarized(
        recognizer: OfflineRecognizer,
        pcm: DecodedPcmFile,
        turns: List<SpeechTurn>
    ): List<TextTurn> {
        val result = mutableListOf<TextTurn>()
        for (turn in turns) {
            coroutineContext.ensureActive()
            val start = (turn.start - TURN_PADDING_SECONDS).coerceAtLeast(0.0)
            val end = (turn.end + TURN_PADDING_SECONDS).coerceAtMost(pcm.durationSeconds.toDouble())
            val samples = PcmAccess.readSegment(pcm, start, end)
            val text = recognize(recognizer, samples, pcm.sampleRate)
            if (text.isNotBlank()) result += TextTurn(turn.start, turn.end, turn.speaker, text)
        }
        return result
    }

    private suspend fun transcribeSequential(recognizer: OfflineRecognizer, pcm: DecodedPcmFile): List<TextTurn> {
        val result = mutableListOf<TextTurn>()
        var start = 0.0
        while (start < pcm.durationSeconds.coerceAtLeast(1L).toDouble()) {
            coroutineContext.ensureActive()
            val end = min(pcm.durationSeconds.toDouble(), start + MAX_ASR_CHUNK_SECONDS)
            val samples = PcmAccess.readSegment(pcm, start, end)
            val text = recognize(recognizer, samples, pcm.sampleRate)
            if (text.isNotBlank()) result += TextTurn(start, end, null, text)
            if (end >= pcm.durationSeconds.toDouble()) break
            start = (end - SEQUENTIAL_OVERLAP_SECONDS).coerceAtLeast(start + 5.0)
        }
        return mergeSequentialText(result)
    }

    private fun recognize(recognizer: OfflineRecognizer, samples: FloatArray, sampleRate: Int): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun mergeSequentialText(turns: List<TextTurn>): List<TextTurn> {
        if (turns.size < 2) return turns
        val out = mutableListOf(turns.first())
        for (next in turns.drop(1)) {
            val previous = out.last()
            val prevWords = previous.text.split(Regex("\\s+")).takeLast(MAX_OVERLAP_WORDS)
            val nextWords = next.text.split(Regex("\\s+"))
            var overlap = 0
            val maxOverlap = min(prevWords.size, nextWords.size)
            for (n in maxOverlap downTo MIN_OVERLAP_WORDS) {
                if (prevWords.takeLast(n).joinToString(" ").equals(nextWords.take(n).joinToString(" "), true)) {
                    overlap = n
                    break
                }
            }
            out += next.copy(text = nextWords.drop(overlap).joinToString(" "))
        }
        return out
    }

    private fun buildMarkdown(
        item: ScribeItem,
        model: String,
        language: String,
        turns: List<TextTurn>,
        topics: List<String>,
        identities: Map<Int, String>,
        diarizationRequested: Boolean,
        diarizationUsed: Boolean,
        skippedForLength: Boolean
    ): String = buildString {
        appendLine("# ${item.title.sanitizeMarkdownTitle()}")
        appendLine()
        appendLine("- Recorded: ${java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.recordedAt))}")
        appendLine("- Duration: ${formatDuration(item.durationSeconds)}")
        appendLine("- Transcription: offline Whisper ${model.replaceFirstChar(Char::uppercase)} INT8")
        appendLine("- Language mode: ${languageLabel(language)}")
        appendLine("- Speaker separation: ${if (diarizationUsed) "offline diarization" else "not applied"}")
        if (identities.isNotEmpty()) appendLine("- Enrolled voice matches: ${identities.values.distinct().joinToString()}")
        if (skippedForLength) appendLine("- Note: speaker diarization was skipped beyond ${MAX_DIARIZATION_SECONDS / 60} minutes to protect phone memory; the full recording was still transcribed.")
        else if (diarizationRequested && !diarizationUsed) appendLine("- Note: diarization could not produce reliable turns; sequential transcript used.")
        appendLine()
        appendLine("## Transcript")
        appendLine()
        turns.forEach { turn ->
            if (turn.speaker != null) {
                val name = identities[turn.speaker] ?: "Speaker ${turn.speaker + 1}"
                append("**$name** ")
            }
            append("[${timestamp(turn.start)}] ")
            appendLine(turn.text.trim())
            appendLine()
        }
        appendLine("## Medical learning index")
        appendLine()
        if (topics.isEmpty()) appendLine("No predefined infectious-diseases topic tag detected.")
        else topics.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## Verification note")
        appendLine()
        appendLine("This transcript was generated locally from retained audio. Review drug names, doses, laboratory values, patient identifiers and clinical decisions against the audio before clinical, academic or research use. Speaker identification is probabilistic; confirm names before relying on them.")
    }

    private fun languageCode(value: String): String = when (value.lowercase()) {
        "english", "en", "en-in" -> "en"
        "telugu", "te", "te-in" -> "te"
        "hindi", "hi", "hi-in" -> "hi"
        else -> ""
    }

    private fun languageLabel(value: String): String = when (value.lowercase()) {
        "english", "en", "en-in" -> "English"
        "telugu", "te", "te-in" -> "Telugu"
        "hindi", "hi", "hi-in" -> "Hindi"
        else -> "Auto / multilingual"
    }

    private fun String.sanitizeMarkdownTitle(): String = replace(Regex("[\\r\\n#]+"), " ").trim().ifBlank { "Recording" }

    private fun timestamp(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%dh %02dm %02ds".format(h, m, s) else "%dm %02ds".format(m, s)
    }

    companion object {
        private const val MAX_ASR_CHUNK_SECONDS = 28.0
        private const val SEQUENTIAL_OVERLAP_SECONDS = 2.0
        private const val TURN_PADDING_SECONDS = 0.12
        private const val MIN_TURN_SECONDS = 0.25
        private const val MAX_MERGE_GAP = 0.65
        private const val MAX_DIARIZATION_SECONDS = 45L * 60L
        private const val MIN_OVERLAP_WORDS = 2
        private const val MAX_OVERLAP_WORDS = 20
    }
}
