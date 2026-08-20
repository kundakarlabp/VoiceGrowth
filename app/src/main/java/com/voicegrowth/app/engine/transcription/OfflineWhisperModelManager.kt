package com.voicegrowth.app.engine.transcription

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

/** Small multilingual Whisper-tiny INT8 model used only for local prerecorded-file ASR. */
object OfflineWhisperModelManager {
    data class Status(
        val installed: Boolean,
        val sizeBytes: Long,
        val message: String
    )

    data class Progress(
        val downloadedBytes: Long,
        val expectedBytes: Long,
        val currentFile: String
    ) {
        val percent: Int
            get() = if (expectedBytes <= 0L) 0 else
                ((downloadedBytes * 100L) / expectedBytes).toInt().coerceIn(0, 100)
    }

    data class ModelFiles(
        val encoder: File,
        val decoder: File,
        val tokens: File
    )

    fun files(context: Context): ModelFiles {
        val dir = File(context.filesDir, MODEL_DIR)
        return ModelFiles(
            encoder = File(dir, ENCODER_NAME),
            decoder = File(dir, DECODER_NAME),
            tokens = File(dir, TOKENS_NAME)
        )
    }

    fun status(context: Context): Status {
        val model = files(context)
        val valid = model.encoder.isFile && model.encoder.length() == ENCODER_BYTES &&
            model.decoder.isFile && model.decoder.length() == DECODER_BYTES &&
            model.tokens.isFile && model.tokens.length() >= MIN_TOKENS_BYTES
        val size = listOf(model.encoder, model.decoder, model.tokens)
            .filter(File::exists)
            .sumOf(File::length)
        return if (valid) {
            Status(true, size, "Reliable offline Whisper ASR is installed and ready")
        } else {
            Status(false, size, "Reliable offline file transcription model is not installed")
        }
    }

    suspend fun install(context: Context, onProgress: (Progress) -> Unit): ModelFiles =
        withContext(Dispatchers.IO) {
            val target = files(context)
            val dir = target.encoder.parentFile ?: error("ASR model directory unavailable")
            dir.mkdirs()
            require(dir.isDirectory) { "Unable to create ASR model directory" }

            val requiredFree = EXPECTED_TOTAL_BYTES + 64L * 1024L * 1024L
            require(context.filesDir.usableSpace > requiredFree) {
                "Not enough private storage. Keep at least ${requiredFree / (1024L * 1024L)} MB free and retry."
            }

            val specs = listOf(
                Spec(ENCODER_NAME, ENCODER_URL, ENCODER_BYTES, ENCODER_SHA256),
                Spec(DECODER_NAME, DECODER_URL, DECODER_BYTES, DECODER_SHA256),
                Spec(TOKENS_NAME, TOKENS_URL, null, null)
            )

            var completed = 0L
            try {
                for (spec in specs) {
                    coroutineContext.ensureActive()
                    val destination = File(dir, spec.name)
                    if (isExistingValid(destination, spec)) {
                        completed += destination.length()
                        onProgress(Progress(completed, EXPECTED_TOTAL_BYTES, spec.name))
                        continue
                    }
                    val staged = File(dir, ".${spec.name}.part")
                    staged.delete()
                    download(spec, staged) { fileBytes ->
                        onProgress(Progress(completed + fileBytes, EXPECTED_TOTAL_BYTES, spec.name))
                    }
                    validate(staged, spec)
                    if (destination.exists() && !destination.delete()) {
                        error("Unable to replace ${spec.name}")
                    }
                    if (!staged.renameTo(destination)) {
                        staged.copyTo(destination, overwrite = true)
                        staged.delete()
                    }
                    completed += destination.length()
                    onProgress(Progress(completed, EXPECTED_TOTAL_BYTES, spec.name))
                }
                val ready = status(context)
                require(ready.installed) { "Offline Whisper model installation did not validate" }
                target
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach(File::delete)
                throw error
            }
        }

    suspend fun remove(context: Context) = withContext(Dispatchers.IO) {
        val dir = files(context).encoder.parentFile ?: return@withContext
        dir.deleteRecursively()
    }

    private suspend fun download(spec: Spec, target: File, onBytes: (Long) -> Unit) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 120_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "VoiceGrowth/1.3.3 Android")
                setRequestProperty("Accept", "application/octet-stream,*/*")
            }
            val code = connection.responseCode
            require(code in 200..299) { "Model download failed for ${spec.name}: HTTP $code" }
            var copied = 0L
            connection.inputStream.buffered(128 * 1024).use { input ->
                target.outputStream().buffered(128 * 1024).use { output ->
                    val buffer = ByteArray(128 * 1024)
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
            require(target.length() > 0L) { "Downloaded ${spec.name} is empty" }
        } finally {
            connection?.disconnect()
        }
    }

    private fun isExistingValid(file: File, spec: Spec): Boolean = runCatching {
        validate(file, spec)
        true
    }.getOrDefault(false)

    private fun validate(file: File, spec: Spec) {
        require(file.isFile && file.length() > 0L) { "${spec.name} is missing" }
        spec.expectedBytes?.let { expected ->
            require(file.length() == expected) {
                "${spec.name} has the wrong size (${file.length()} bytes; expected $expected)"
            }
        }
        if (spec.expectedSha256 != null) {
            val actual = sha256(file)
            require(actual.equals(spec.expectedSha256, ignoreCase = true)) {
                "${spec.name} checksum verification failed"
            }
        } else {
            require(file.length() >= MIN_TOKENS_BYTES) { "${spec.name} is incomplete" }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Spec(
        val name: String,
        val url: String,
        val expectedBytes: Long?,
        val expectedSha256: String?
    )

    const val MODEL_LABEL = "Whisper tiny multilingual INT8"
    private const val MODEL_DIR = "offline_asr/whisper-tiny-int8"
    private const val ENCODER_NAME = "tiny-encoder.int8.onnx"
    private const val DECODER_NAME = "tiny-decoder.int8.onnx"
    private const val TOKENS_NAME = "tiny-tokens.txt"

    private const val ENCODER_BYTES = 12_937_772L
    private const val DECODER_BYTES = 89_855_401L
    private const val MIN_TOKENS_BYTES = 500_000L
    private const val EXPECTED_TOTAL_BYTES = 103_700_000L

    private const val ENCODER_SHA256 = "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"
    private const val DECODER_SHA256 = "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"

    private const val MODEL_REVISION = "65176e2deb88badc814a94058666cadccc29b61c"
    private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/$MODEL_REVISION"
    private const val ENCODER_URL = "$BASE/$ENCODER_NAME?download=true"
    private const val DECODER_URL = "$BASE/$DECODER_NAME?download=true"
    private const val TOKENS_URL = "$BASE/$TOKENS_NAME?download=true"
}
