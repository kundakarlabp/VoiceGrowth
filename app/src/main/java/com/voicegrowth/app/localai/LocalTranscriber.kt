package com.voicegrowth.app.localai

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.max
import kotlin.math.min

class LocalTranscriber(private val models: LocalModelManager.Paths) {
    data class Segment(val start: Float, val end: Float, val speaker: Int, val text: String)
    data class Result(val segments: List<Segment>, val diarizationAvailable: Boolean, val language: String?)

    fun transcribe(samples: FloatArray, sampleRate: Int = 16_000): Result {
        require(sampleRate == 16_000) { "Local transcription expects 16 kHz PCM" }
        val speakerRegions = runCatching { diarize(samples) }.getOrNull()
        val diarized = !speakerRegions.isNullOrEmpty()
        val regions = if (diarized) {
            normalizeSpeakerRegions(speakerRegions!!, samples.size / 16_000f)
        } else {
            fixedChunks(samples.size / 16_000f)
        }

        val recognizer = createRecognizer()
        try {
            val output = mutableListOf<Segment>()
            var detectedLanguage: String? = null
            for (region in regions) {
                val startSample = (region.start * 16_000).toInt().coerceIn(0, samples.size)
                val endSample = (region.end * 16_000).toInt().coerceIn(startSample, samples.size)
                if (endSample - startSample < 4_000) continue
                val clip = samples.copyOfRange(startSample, endSample)
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(clip, sampleRate = 16_000)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    val text = result.text.trim()
                    if (detectedLanguage.isNullOrBlank() && result.lang.isNotBlank()) detectedLanguage = result.lang
                    if (text.isNotBlank()) output += Segment(region.start, region.end, region.speaker, text)
                } finally {
                    stream.release()
                }
            }
            return Result(output, diarized, detectedLanguage)
        } finally {
            recognizer.release()
        }
    }

    private fun createRecognizer(): OfflineRecognizer {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = models.whisperEncoder.absolutePath,
                decoder = models.whisperDecoder.absolutePath,
                language = "",
                task = "transcribe",
                enableTokenTimestamps = true,
                enableSegmentTimestamps = true,
            ),
            numThreads = threads,
            debug = false,
            provider = "cpu",
            modelType = "whisper",
            tokens = models.whisperTokens.absolutePath,
        )
        return OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = modelConfig))
    }

    private fun diarize(samples: FloatArray): List<OfflineSpeakerDiarizationSegment> {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = models.diarizationSegmentation.absolutePath,
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = models.diarizationEmbedding.absolutePath,
                numThreads = threads,
                debug = false,
                provider = "cpu",
            ),
            clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
            minDurationOn = 0.25f,
            minDurationOff = 0.35f,
        )
        val diarizer = OfflineSpeakerDiarization(config = config)
        return try {
            diarizer.process(samples).toList().sortedBy { it.start }
        } finally {
            diarizer.release()
        }
    }

    private data class Region(val start: Float, val end: Float, val speaker: Int)

    /** Merge short adjacent turns by the same speaker, but keep ASR clips under Whisper's comfortable window. */
    private fun normalizeSpeakerRegions(
        input: List<OfflineSpeakerDiarizationSegment>,
        duration: Float,
    ): List<Region> {
        val merged = mutableListOf<Region>()
        for (segment in input) {
            val start = segment.start.coerceIn(0f, duration)
            val end = segment.end.coerceIn(start, duration)
            if (end - start < 0.25f) continue
            val last = merged.lastOrNull()
            if (last != null && last.speaker == segment.speaker && start - last.end <= 0.8f && end - last.start <= 26f) {
                merged[merged.lastIndex] = last.copy(end = end)
            } else {
                merged += Region(start, end, segment.speaker)
            }
        }
        return merged.flatMap(::splitRegion)
    }

    private fun splitRegion(region: Region): List<Region> {
        if (region.end - region.start <= 26f) return listOf(region)
        val out = mutableListOf<Region>()
        var cursor = region.start
        while (cursor < region.end) {
            val end = min(region.end, cursor + 25f)
            out += Region(cursor, end, region.speaker)
            cursor = max(end - 0.4f, cursor + 1f)
        }
        return out
    }

    private fun fixedChunks(duration: Float): List<Region> {
        val out = mutableListOf<Region>()
        var cursor = 0f
        while (cursor < duration) {
            val end = min(duration, cursor + 25f)
            out += Region(cursor, end, 0)
            cursor = max(end - 0.4f, cursor + 1f)
        }
        return out
    }

    companion object {
        fun formatTimestamp(seconds: Float): String {
            val total = max(0, seconds.toInt())
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
    }
}
