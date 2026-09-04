package com.voicegrowth.app.localai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads only open-source model weights into app-private storage.
 * No paid API, cloud inference, account or token is required.
 */
class LocalModelManager(private val context: Context) {
    data class Paths(
        val whisperEncoder: File,
        val whisperDecoder: File,
        val whisperTokens: File,
        val diarizationSegmentation: File,
        val diarizationEmbedding: File,
    )

    private val root = File(context.filesDir, "voicegrowth_models").apply { mkdirs() }

    fun isInstalled(): Boolean {
        val paths = expectedPaths()
        return paths.whisperEncoder.length() >= ENCODER_MIN_BYTES &&
            paths.whisperDecoder.length() >= DECODER_MIN_BYTES &&
            paths.whisperTokens.length() >= TOKENS_MIN_BYTES &&
            paths.diarizationSegmentation.length() >= SEGMENTATION_MIN_BYTES &&
            paths.diarizationEmbedding.length() >= EMBEDDING_MIN_BYTES
    }

    suspend fun ensureInstalled(): Paths = withContext(Dispatchers.IO) {
        val paths = expectedPaths()
        downloadIfNeeded(
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-encoder.int8.onnx?download=true",
            paths.whisperEncoder,
            ENCODER_MIN_BYTES,
        )
        downloadIfNeeded(
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-decoder.int8.onnx?download=true",
            paths.whisperDecoder,
            DECODER_MIN_BYTES,
        )
        downloadIfNeeded(
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-tokens.txt?download=true",
            paths.whisperTokens,
            TOKENS_MIN_BYTES,
        )
        downloadIfNeeded(
            "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.int8.onnx?download=true",
            paths.diarizationSegmentation,
            SEGMENTATION_MIN_BYTES,
        )
        downloadIfNeeded(
            "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/main/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx?download=true",
            paths.diarizationEmbedding,
            EMBEDDING_MIN_BYTES,
        )
        paths
    }

    private fun expectedPaths(): Paths {
        val whisperDir = File(root, "whisper-base-int8").apply { mkdirs() }
        val diarizationDir = File(root, "speaker-diarization").apply { mkdirs() }
        return Paths(
            whisperEncoder = File(whisperDir, "base-encoder.int8.onnx"),
            whisperDecoder = File(whisperDir, "base-decoder.int8.onnx"),
            whisperTokens = File(whisperDir, "base-tokens.txt"),
            diarizationSegmentation = File(diarizationDir, "pyannote-segmentation-3.0-int8.onnx"),
            diarizationEmbedding = File(diarizationDir, "3dspeaker-eres2net-base.onnx"),
        )
    }

    private fun downloadIfNeeded(url: String, destination: File, minimumBytes: Long) {
        if (destination.exists() && destination.length() >= minimumBytes) return
        val partial = File(destination.parentFile, destination.name + ".part")
        if (partial.exists()) partial.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "VoiceGrowth/2.1")
        }
        try {
            require(connection.responseCode in 200..299) {
                "Model download failed (${connection.responseCode}) for ${destination.name}"
            }
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output -> input.copyTo(output, 256 * 1024) }
            }
            require(partial.length() >= minimumBytes) {
                "Downloaded model ${destination.name} is unexpectedly small (${partial.length()} bytes)"
            }
            if (destination.exists()) destination.delete()
            require(partial.renameTo(destination)) { "Could not finalize ${destination.name}" }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ENCODER_MIN_BYTES = 20L * 1024 * 1024
        private const val DECODER_MIN_BYTES = 100L * 1024 * 1024
        private const val TOKENS_MIN_BYTES = 500L * 1024
        private const val SEGMENTATION_MIN_BYTES = 1L * 1024 * 1024
        private const val EMBEDDING_MIN_BYTES = 30L * 1024 * 1024
    }
}
