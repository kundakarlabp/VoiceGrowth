package com.voicegrowth.app.engine.transcription

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.min

class OfflineAsrModelRequiredException(message: String) : IllegalStateException(message)

/**
 * True prerecorded-file ASR. Unlike Android SpeechRecognizer, sherpa-onnx consumes the decoded
 * waveform directly and never falls back to the device microphone.
 */
class OfflineWhisperTranscriptionEngine : TranscriptionEngine {
    override val engineName: String = "Sherpa-ONNX Whisper tiny multilingual INT8 (offline)"

    override suspend fun transcribe(
        context: Context,
        audioFile: File,
        language: String
    ): Result<TranscriptionResult> = withContext(Dispatchers.Default) {
        try {
            val status = OfflineWhisperModelManager.status(context)
            if (!status.installed) {
                return@withContext Result.failure(
                    OfflineAsrModelRequiredException(
                        "Reliable offline file transcription is not installed. Open Settings → Speech transcription → Install offline Whisper, then retry."
                    )
                )
            }

            val pcmFile = File(context.cacheDir, "whisper_${audioFile.nameWithoutExtension}_${System.nanoTime()}.pcm")
            try {
                val pcm = AudioPcmDecoder.decodeToMonoPcm16(audioFile, pcmFile)
                require(pcm.durationSeconds > 0L) { "Decoded recording contains no usable audio" }

                val files = OfflineWhisperModelManager.files(context)
                val recognizer = OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(
                                encoder = files.encoder.absolutePath,
                                decoder = files.decoder.absolutePath,
                                language = whisperLanguage(language),
                                task = "transcribe",
                                tailPaddings = 1000
                            ),
                            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, MAX_THREADS),
                            provider = "cpu",
                            modelType = "whisper",
                            tokens = files.tokens.absolutePath
                        ),
                        decodingMethod = "greedy_search"
                    )
                )

                try {
                    val texts = decodePcmInChunks(recognizer, pcm)
                    val transcript = TranscriptChunkMerger.merge(texts)
                    require(transcript.isNotBlank()) { "Offline Whisper returned an empty transcript" }

                    Result.success(
                        TranscriptionResult(
                            transcriptText = transcript,
                            detectedLanguage = languageLabel(language),
                            engineName = engineName,
                            durationSeconds = pcm.durationSeconds,
                            detectedThemes = MedicalThemeDetector.detect(transcript)
                        )
                    )
                } finally {
                    recognizer.release()
                }
            } finally {
                pcmFile.delete()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun decodePcmInChunks(
        recognizer: OfflineRecognizer,
        pcm: DecodedPcmAudio
    ): List<String> = withContext(Dispatchers.IO) {
        val sampleRate = pcm.sampleRate.coerceAtLeast(8_000)
        val bytesPerSample = 2
        val chunkSamples = sampleRate * CHUNK_SECONDS
        val overlapSamples = sampleRate * OVERLAP_SECONDS
        val totalSamples = pcm.file.length() / bytesPerSample
        val result = mutableListOf<String>()

        RandomAccessFile(pcm.file, "r").use { input ->
            var startSample = 0L
            while (startSample < totalSamples) {
                coroutineContext.ensureActive()
                val samplesThisChunk = min(chunkSamples.toLong(), totalSamples - startSample).toInt()
                if (samplesThisChunk <= 0) break

                val byteCount = samplesThisChunk * bytesPerSample
                val raw = ByteArray(byteCount)
                input.seek(startSample * bytesPerSample)
                input.readFully(raw)
                val floats = pcm16ToFloat(raw)

                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(floats, sampleRate)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text.trim()
                        .takeIf(String::isNotBlank)
                        ?.let(result::add)
                } finally {
                    stream.release()
                }

                if (startSample + samplesThisChunk >= totalSamples) break
                startSample += (samplesThisChunk - overlapSamples).coerceAtLeast(sampleRate * 5).toLong()
            }
        }
        result
    }

    private fun pcm16ToFloat(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(buffer.remaining())
        var i = 0
        while (buffer.hasRemaining()) {
            samples[i++] = buffer.get().toFloat() / 32768.0f
        }
        return samples
    }

    private fun whisperLanguage(language: String): String = when (language.lowercase()) {
        "english", "en", "en-in" -> "en"
        "telugu", "te", "te-in" -> "te"
        "hindi", "hi", "hi-in" -> "hi"
        else -> ""
    }

    private fun languageLabel(language: String): String = when (language.lowercase()) {
        "english", "en", "en-in" -> "English"
        "telugu", "te", "te-in" -> "Telugu"
        "hindi", "hi", "hi-in" -> "Hindi"
        else -> "Auto / multilingual"
    }

    companion object {
        private const val CHUNK_SECONDS = 26
        private const val OVERLAP_SECONDS = 2
        private const val MAX_THREADS = 6
    }
}

/** Prefer true file-ASR; use Android's recognizer only as a compatibility fallback. */
class HybridTranscriptionEngine : TranscriptionEngine {
    private val whisper = OfflineWhisperTranscriptionEngine()
    private val android = LocalMedicalSpeechEngine()

    override val engineName: String = "VoiceGrowth hybrid offline ASR"

    override suspend fun transcribe(
        context: Context,
        audioFile: File,
        language: String
    ): Result<TranscriptionResult> {
        if (OfflineWhisperModelManager.status(context).installed) {
            val reliable = whisper.transcribe(context, audioFile, language)
            if (reliable.isSuccess) return reliable
            val failure = reliable.exceptionOrNull()
            if (failure is CancellationException) throw failure
        }

        val androidAttempt = android.transcribe(context, audioFile, language)
        if (androidAttempt.isSuccess) return androidAttempt

        val error = androidAttempt.exceptionOrNull()
        if (error is CancellationException) throw error
        return Result.failure(
            OfflineAsrModelRequiredException(
                "This phone's Android speech recognizer could not transcribe the recorded audio (${error?.message ?: "unknown ASR error"}). " +
                    "Install the reliable offline Whisper model in Settings → Speech transcription; the recording will stay pending and can be retried."
            )
        )
    }
}

internal object TranscriptChunkMerger {
    fun merge(chunks: List<String>): String {
        val cleaned = chunks.map { it.trim().replace(Regex("\\s+"), " ") }.filter(String::isNotBlank)
        if (cleaned.isEmpty()) return ""
        val merged = StringBuilder(cleaned.first())
        for (next in cleaned.drop(1)) {
            val existingWords = merged.toString().trim().split(Regex("\\s+")).takeLast(MAX_OVERLAP_WORDS)
            val nextWords = next.split(Regex("\\s+"))
            var overlap = 0
            val max = min(existingWords.size, nextWords.size)
            for (size in max downTo MIN_OVERLAP_WORDS) {
                val tail = existingWords.takeLast(size).joinToString(" ").lowercase()
                val head = nextWords.take(size).joinToString(" ").lowercase()
                if (tail == head) {
                    overlap = size
                    break
                }
            }
            if (merged.isNotEmpty() && !merged.endsWith(" ")) merged.append(' ')
            merged.append(nextWords.drop(overlap).joinToString(" "))
        }
        return merged.toString().trim()
    }

    private const val MIN_OVERLAP_WORDS = 2
    private const val MAX_OVERLAP_WORDS = 24
}

internal object MedicalThemeDetector {
    fun detect(text: String): List<String> {
        val lower = text.lowercase()
        return TERMS.mapNotNull { (label, terms) -> label.takeIf { terms.any(lower::contains) } }.take(10)
    }

    private val TERMS = linkedMapOf(
        "Antimicrobial resistance" to listOf("resistant", "resistance", "mdr", "xdr", "crab", "cre", "dtr"),
        "Antimicrobial stewardship" to listOf("de-escal", "antibiotic", "antimicrobial", "duration", "extended infusion"),
        "Transplant infection" to listOf("transplant", "cmv", "bk virus", "immunosuppression"),
        "Invasive fungal infection" to listOf("asperg", "mucor", "candida", "amphotericin", "voriconazole"),
        "Tuberculosis" to listOf("tuberculosis", "tb", "rifampicin", "isoniazid"),
        "HIV" to listOf("hiv", "antiretroviral", "cd4", "viral load"),
        "Source control" to listOf("source control", "drain", "catheter removal", "debrid"),
        "PK/PD optimization" to listOf("mic", "auc", "loading dose", "creatinine clearance", "pk/pd")
    )
}
