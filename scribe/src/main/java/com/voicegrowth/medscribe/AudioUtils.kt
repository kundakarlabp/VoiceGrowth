package com.voicegrowth.medscribe

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.floor

data class DecodedPcmFile(
    val file: File,
    val sampleRate: Int,
    val sampleCount: Long,
    val durationSeconds: Long
)

object AudioUtils {
    suspend fun importAudio(context: Context, uri: Uri, titleHint: String? = null): ScribeItem =
        withContext(Dispatchers.IO) {
            val repo = ScribeRepository.get(context)
            val id = UUID.randomUUID().toString()
            val mime = context.contentResolver.getType(uri).orEmpty()
            val ext = when {
                mime.contains("wav") -> "wav"
                mime.contains("mpeg") -> "mp3"
                mime.contains("ogg") -> "ogg"
                mime.contains("flac") -> "flac"
                else -> "m4a"
            }
            val target = File(repo.audioDir, "$id.$ext")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open selected audio" }
                target.outputStream().buffered(256 * 1024).use { output -> input.copyTo(output, 256 * 1024) }
            }
            require(target.length() > 0L) { "Selected audio is empty" }
            val duration = mediaDurationSeconds(target)
            ScribeItem(
                id = id,
                title = titleHint?.takeIf { it.isNotBlank() } ?: "Imported audio",
                audioPath = target.absolutePath,
                recordedAt = System.currentTimeMillis(),
                durationSeconds = duration,
                status = ItemStatus.RECORDED
            )
        }

    fun mediaDurationSeconds(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            (ms / 1000L).coerceAtLeast(0L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun decodeToMonoPcm16(context: Context, audioFile: File): DecodedPcmFile = withContext(Dispatchers.IO) {
        require(audioFile.exists() && audioFile.length() > 0L) { "Audio file is missing or empty" }
        val tempDir = File(ScribeRepository.get(context).baseDir, "tmp").apply { mkdirs() }
        val outputFile = File(tempDir, "${audioFile.nameWithoutExtension}_${System.nanoTime()}.pcm")
        decodeInternal(audioFile, outputFile)
    }

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (input.isEmpty() || fromRate <= 0 || toRate <= 0 || fromRate == toRate) return input
        val outSize = ((input.size.toDouble() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outSize)
        val ratio = fromRate.toDouble() / toRate
        for (i in out.indices) {
            val src = i * ratio
            val left = floor(src).toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val frac = (src - left).toFloat()
            out[i] = input[left] * (1f - frac) + input[right] * frac
        }
        return out
    }

    fun pcm16BytesToFloat(raw: ByteArray): FloatArray {
        val shorts = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(shorts.remaining())
        var i = 0
        while (shorts.hasRemaining()) samples[i++] = shorts.get().toFloat() / 32768f
        return samples
    }

    private fun decodeInternal(audioFile: File, outputFile: File): DecodedPcmFile {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(audioFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No decodable audio track found")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME type missing")
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            var framesWritten = 0L

            BufferedOutputStream(FileOutputStream(outputFile), 256 * 1024).use { output ->
                while (!outputEnded) {
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(10_000L)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex) ?: error("Decoder input unavailable")
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else AudioFormat.ENCODING_PCM_16BIT
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(outputIndex) ?: error("Decoder output unavailable")
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                framesWritten += writeMono(
                                    buffer.slice().order(ByteOrder.LITTLE_ENDIAN),
                                    channelCount.coerceAtLeast(1),
                                    pcmEncoding,
                                    output
                                )
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            require(outputFile.length() > 0L) { "Decoded audio contains no samples" }
            val duration = if (sampleRate > 0) framesWritten / sampleRate else 0L
            return DecodedPcmFile(outputFile, sampleRate, framesWritten, duration)
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun writeMono(source: ByteBuffer, channels: Int, encoding: Int, output: BufferedOutputStream): Long {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = source.asFloatBuffer()
                val frames = floats.remaining() / channels
                val out = ByteArray(frames * 2)
                var j = 0
                repeat(frames) {
                    var sum = 0f
                    repeat(channels) { sum += floats.get() }
                    val v = ((sum / channels).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                    out[j++] = (v and 0xff).toByte()
                    out[j++] = ((v shr 8) and 0xff).toByte()
                }
                output.write(out)
                frames.toLong()
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val frames = source.remaining() / channels
                val out = ByteArray(frames * 2)
                var j = 0
                repeat(frames) {
                    var sum = 0
                    repeat(channels) { sum += (source.get().toInt() and 0xff) - 128 }
                    val v = ((sum / channels) shl 8).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out[j++] = (v and 0xff).toByte()
                    out[j++] = ((v shr 8) and 0xff).toByte()
                }
                output.write(out)
                frames.toLong()
            }
            else -> {
                val shorts = source.asShortBuffer()
                val frames = shorts.remaining() / channels
                val out = ByteArray(frames * 2)
                var j = 0
                repeat(frames) {
                    var sum = 0L
                    repeat(channels) { sum += shorts.get().toLong() }
                    val v = (sum / channels).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
                    out[j++] = (v and 0xff).toByte()
                    out[j++] = ((v shr 8) and 0xff).toByte()
                }
                output.write(out)
                frames.toLong()
            }
        }
    }
}
