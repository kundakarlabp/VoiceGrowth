package com.voicegrowth.app.engine.transcription

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class DecodedPcmAudio(
    val file: File,
    val sampleRate: Int,
    val channelCount: Int,
    val durationSeconds: Long
)

internal object AudioPcmDecoder {
    private const val TIMEOUT_US = 10_000L

    suspend fun decodeToMonoPcm16(audioFile: File, outputFile: File): DecodedPcmAudio =
        withContext(Dispatchers.IO) {
            require(audioFile.exists() && audioFile.length() > 0) { "Audio file is empty or unavailable" }
            if (outputFile.exists()) outputFile.delete()

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
                var samplesWritten = 0L

                BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                    while (!outputEnded) {
                        if (!inputEnded) {
                            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                            if (inputIndex >= 0) {
                                val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Decoder input buffer unavailable")
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(
                                        inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputEnded = true
                                } else {
                                    codec.queueInputBuffer(
                                        inputIndex, 0, sampleSize, extractor.sampleTime, 0
                                    )
                                    extractor.advance()
                                }
                            }
                        }

                        when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
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
                                    val buffer = codec.getOutputBuffer(outputIndex)
                                        ?: error("Decoder output buffer unavailable")
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    val slice = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                                    samplesWritten += writeMonoPcm16(
                                        source = slice,
                                        channelCount = channelCount.coerceAtLeast(1),
                                        pcmEncoding = pcmEncoding,
                                        output = output
                                    )
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }
                }

                require(outputFile.length() > 0) { "Decoded audio contained no PCM samples" }
                val duration = if (sampleRate > 0) samplesWritten / sampleRate else 0L
                DecodedPcmAudio(outputFile, sampleRate, 1, duration)
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
            }
        }

    private fun writeMonoPcm16(
        source: ByteBuffer,
        channelCount: Int,
        pcmEncoding: Int,
        output: BufferedOutputStream
    ): Long {
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> writeFloat(source, channelCount, output)
            AudioFormat.ENCODING_PCM_8BIT -> writePcm8(source, channelCount, output)
            else -> writePcm16(source, channelCount, output)
        }
    }

    private fun writePcm16(source: ByteBuffer, channels: Int, output: BufferedOutputStream): Long {
        val shorts = source.asShortBuffer()
        val frames = shorts.remaining() / channels
        val out = ByteArray(frames * 2)
        var outIndex = 0
        repeat(frames) {
            var sum = 0L
            repeat(channels) { sum += shorts.get().toLong() }
            val mono = (sum / channels).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
            out[outIndex++] = (mono and 0xFF).toByte()
            out[outIndex++] = ((mono shr 8) and 0xFF).toByte()
        }
        output.write(out)
        return frames.toLong()
    }

    private fun writeFloat(source: ByteBuffer, channels: Int, output: BufferedOutputStream): Long {
        val floats = source.asFloatBuffer()
        val frames = floats.remaining() / channels
        val out = ByteArray(frames * 2)
        var outIndex = 0
        repeat(frames) {
            var sum = 0f
            repeat(channels) { sum += floats.get() }
            val normalized = (sum / channels).coerceIn(-1f, 1f)
            val mono = (normalized * Short.MAX_VALUE).toInt()
            out[outIndex++] = (mono and 0xFF).toByte()
            out[outIndex++] = ((mono shr 8) and 0xFF).toByte()
        }
        output.write(out)
        return frames.toLong()
    }

    private fun writePcm8(source: ByteBuffer, channels: Int, output: BufferedOutputStream): Long {
        val frames = source.remaining() / channels
        val out = ByteArray(frames * 2)
        var outIndex = 0
        repeat(frames) {
            var sum = 0
            repeat(channels) { sum += (source.get().toInt() and 0xFF) - 128 }
            val mono = ((sum / channels) shl 8).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[outIndex++] = (mono and 0xFF).toByte()
            out[outIndex++] = ((mono shr 8) and 0xFF).toByte()
        }
        output.write(out)
        return frames.toLong()
    }
}
