package com.voicegrowth.medscribe

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class WhisperFiles(val encoder: File, val decoder: File, val tokens: File)
data class DiarizationFiles(val segmentation: File, val embedding: File)

object ModelManager {
    private data class WhisperSpec(
        val id: String,
        val label: String,
        val expectedBytes: Long,
        val encoderMin: Long,
        val decoderMin: Long,
        val encoderSha256: String,
        val decoderSha256: String
    )

    val whisperChoices: List<Pair<String, String>> = listOf(
        "tiny" to "Fast · Whisper tiny INT8 (~104 MB)",
        "base" to "Balanced · Whisper base INT8 (~161 MB)",
        "small" to "Accuracy · Whisper small INT8 (~375 MB)"
    )

    private val specs = mapOf(
        "tiny" to WhisperSpec(
            "tiny", "Whisper tiny", 104_000_000L, 12_000_000L, 89_000_000L,
            "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
            "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"
        ),
        "base" to WhisperSpec(
            "base", "Whisper base", 162_000_000L, 28_000_000L, 130_000_000L,
            "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
            "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"
        ),
        "small" to WhisperSpec(
            "small", "Whisper small", 376_000_000L, 110_000_000L, 260_000_000L,
            "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
            "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee"
        )
    )

    fun whisperFiles(context: Context, id: String): WhisperFiles {
        val safe = specs[id]?.id ?: "base"
        val dir = File(ScribeRepository.get(context).modelDir, "whisper-$safe").apply { mkdirs() }
        return WhisperFiles(
            File(dir, "$safe-encoder.int8.onnx"),
            File(dir, "$safe-decoder.int8.onnx"),
            File(dir, "$safe-tokens.txt")
        )
    }

    fun isWhisperInstalled(context: Context, id: String): Boolean {
        val spec = specs[id] ?: return false
        val f = whisperFiles(context, id)
        return f.encoder.isFile && f.encoder.length() >= spec.encoderMin &&
            f.decoder.isFile && f.decoder.length() >= spec.decoderMin &&
            f.tokens.isFile && f.tokens.length() >= MIN_TOKENS_BYTES
    }

    suspend fun installWhisper(context: Context, id: String, onProgress: (ModelProgress) -> Unit) =
        withContext(Dispatchers.IO) {
            val spec = specs[id] ?: error("Unknown Whisper model: $id")
            val files = whisperFiles(context, id)
            val targets = listOf(
                DownloadSpec(files.encoder, whisperUrl(id, "${id}-encoder.int8.onnx"), spec.encoderMin, spec.encoderSha256),
                DownloadSpec(files.decoder, whisperUrl(id, "${id}-decoder.int8.onnx"), spec.decoderMin, spec.decoderSha256),
                DownloadSpec(files.tokens, whisperUrl(id, "${id}-tokens.txt"), MIN_TOKENS_BYTES, null)
            )
            require(context.filesDir.usableSpace > spec.expectedBytes + 128L * 1024L * 1024L) {
                "Not enough free storage for ${spec.label}. Keep at least ${(spec.expectedBytes + 128L * 1024L * 1024L) / (1024L * 1024L)} MB free."
            }
            downloadAll(spec.label, targets, spec.expectedBytes, onProgress)
            require(isWhisperInstalled(context, id)) { "${spec.label} download did not validate" }
        }

    fun diarizationFiles(context: Context): DiarizationFiles {
        val dir = File(ScribeRepository.get(context).modelDir, "diarization").apply { mkdirs() }
        return DiarizationFiles(
            segmentation = File(dir, "pyannote-segmentation-3.0.onnx"),
            embedding = File(dir, "3dspeaker-eres2net-base.onnx")
        )
    }

    fun isDiarizationInstalled(context: Context): Boolean {
        val f = diarizationFiles(context)
        return f.segmentation.isFile && f.segmentation.length() >= 5_900_000L &&
            f.embedding.isFile && f.embedding.length() >= 39_000_000L
    }

    suspend fun installDiarization(context: Context, onProgress: (ModelProgress) -> Unit) =
        withContext(Dispatchers.IO) {
            val files = diarizationFiles(context)
            val expected = 46_000_000L
            require(context.filesDir.usableSpace > expected + 64L * 1024L * 1024L) {
                "Not enough free storage for speaker diarization models"
            }
            downloadAll(
                "Speaker models",
                listOf(
                    DownloadSpec(
                        files.segmentation,
                        "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx?download=true",
                        5_900_000L,
                        "fed22097bca974bad329a930b60865703766ff89f05fa09060bf6fd44e92e319"
                    ),
                    DownloadSpec(
                        files.embedding,
                        "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/main/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx?download=true",
                        39_000_000L,
                        "1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b"
                    )
                ),
                expected,
                onProgress
            )
            require(isDiarizationInstalled(context)) { "Speaker model download did not validate" }
        }

    private data class DownloadSpec(
        val file: File,
        val url: String,
        val minBytes: Long,
        val sha256: String?
    )

    private suspend fun downloadAll(
        label: String,
        targets: List<DownloadSpec>,
        expectedTotal: Long,
        onProgress: (ModelProgress) -> Unit
    ) {
        var completed = 0L
        for (target in targets) {
            coroutineContext.ensureActive()
            if (isValid(target.file, target)) {
                completed += target.file.length()
                onProgress(ModelProgress(label, target.file.name, completed, expectedTotal))
                continue
            }
            val part = File(target.file.parentFile, ".${target.file.name}.part")
            part.delete()
            try {
                download(target.url, part) { bytes ->
                    onProgress(ModelProgress(label, target.file.name, completed + bytes, expectedTotal))
                }
                require(isValid(part, target)) { "${target.file.name} failed integrity validation" }
                if (target.file.exists()) target.file.delete()
                if (!part.renameTo(target.file)) {
                    part.copyTo(target.file, overwrite = true)
                    part.delete()
                }
                completed += target.file.length()
            } catch (error: CancellationException) {
                part.delete()
                throw error
            } catch (error: Exception) {
                part.delete()
                throw error
            }
        }
    }

    private fun isValid(file: File, spec: DownloadSpec): Boolean {
        if (!file.isFile || file.length() < spec.minBytes) return false
        val expected = spec.sha256 ?: return true
        return runCatching { sha256(file).equals(expected, ignoreCase = true) }.getOrDefault(false)
    }

    private suspend fun download(url: String, target: File, onBytes: (Long) -> Unit) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 180_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "MedScribeLocal/1.0 Android")
                setRequestProperty("Accept", "application/octet-stream,*/*")
            }
            val code = connection.responseCode
            require(code in 200..299) { "Model download failed: HTTP $code" }
            var copied = 0L
            connection.inputStream.buffered(256 * 1024).use { input ->
                target.outputStream().buffered(256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        copied += read
                        onBytes(copied)
                    }
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun whisperUrl(id: String, filename: String): String =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$id/resolve/main/$filename?download=true"

    private const val MIN_TOKENS_BYTES = 500_000L
}
