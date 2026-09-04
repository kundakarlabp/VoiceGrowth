package com.voicegrowth.app.localai

import android.content.ContentResolver
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Decodes common Android audio containers to mono 16 kHz float PCM for local ASR. */
object AudioPcmDecoder {
    data class Pcm(val samples: FloatArray, val sampleRate: Int = 16_000)

    fun decode(resolver: ContentResolver, uri: Uri): Pcm {
        val extractor = MediaExtractor()
        val afd = resolver.openAssetFileDescriptor(uri, "r")
            ?: error("Unable to open audio URI")
        try {
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    inputFormat = format
                    break
                }
            }
            require(audioTrack >= 0 && inputFormat != null) { "No decodable audio track found" }
            extractor.selectTrack(audioTrack)
            val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val output = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outputFormat = inputFormat

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: error("Decoder input buffer unavailable")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            output.write(bytes)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            codec.stop()
            codec.release()

            val sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else AudioFormat.ENCODING_PCM_16BIT

            val mono = when (pcmEncoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> decodeFloat(output.toByteArray(), channels)
                else -> decodePcm16(output.toByteArray(), channels)
            }
            return Pcm(resample(mono, sampleRate, 16_000), 16_000)
        } finally {
            runCatching { extractor.release() }
            afd.close()
        }
    }

    private fun decodePcm16(bytes: ByteArray, channels: Int): FloatArray {
        val shorts = bytes.size / 2
        val frames = shorts / max(1, channels)
        val result = FloatArray(frames)
        var offset = 0
        for (frame in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val lo = bytes[offset++].toInt() and 0xff
                val hi = bytes[offset++].toInt()
                val value = ((hi shl 8) or lo).toShort()
                sum += value / 32768f
            }
            result[frame] = sum / channels
        }
        return result
    }

    private fun decodeFloat(bytes: ByteArray, channels: Int): FloatArray {
        val bb = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val floats = bytes.size / 4
        val frames = floats / max(1, channels)
        val result = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += bb.float
            result[frame] = (sum / channels).coerceIn(-1f, 1f)
        }
        return result
    }

    private fun resample(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        if (input.isEmpty() || sourceRate == targetRate) return input
        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        val size = max(1, floor(input.size / ratio).toInt())
        val output = FloatArray(size)
        for (i in output.indices) {
            val source = i * ratio
            val left = min(input.lastIndex, floor(source).toInt())
            val right = min(input.lastIndex, left + 1)
            val fraction = (source - left).toFloat()
            output[i] = input[left] * (1f - fraction) + input[right] * fraction
        }
        return output
    }
}
