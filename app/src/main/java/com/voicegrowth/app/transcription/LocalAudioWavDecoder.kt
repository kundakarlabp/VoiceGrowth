package com.voicegrowth.app.transcription

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile

/**
 * Offline AAC/M4A -> 16-bit mono WAV conversion. Uses Android platform codecs;
 * no cloud service, permissions or FFmpeg executable are required.
 * Output remains app-private and must be deleted after inference.
 */
internal object LocalAudioWavDecoder {
    fun convert(source: File, wav: File, maxDurationSeconds: Long = 180) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track")
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            require(durationUs <= 0L || durationUs <= maxDurationSeconds * 1_000_000L) {
                "Recording exceeds the short-audio pilot limit"
            }
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val decoder = codec
            val info = MediaCodec.BufferInfo()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var totalBytes = 0L
            var inputEnd = false
            var outputEnd = false
            RandomAccessFile(wav, "rw").use { out ->
                out.setLength(0)
                repeat(44) { out.writeByte(0) } // reserve header
                while (!outputEnd) {
                    if (!inputEnd) {
                        val slot = decoder.dequeueInputBuffer(10_000L)
                        if (slot >= 0) {
                            val input = requireNotNull(decoder.getInputBuffer(slot))
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(slot, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnd = true
                            } else {
                                decoder.queueInputBuffer(slot, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val slot = decoder.dequeueOutputBuffer(info, 10_000L)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = decoder.outputFormat
                            sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else AudioFormat.ENCODING_PCM_16BIT
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT) {
                                "Unsupported decoder output encoding: $encoding"
                            }
                        }
                        in 0..Int.MAX_VALUE -> {
                            val buffer = requireNotNull(decoder.getOutputBuffer(slot))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val pcm = ByteArray(info.size)
                            buffer.get(pcm)
                            require(channels in 1..8) { "Unsupported channel count" }
                            require(pcm.size % (channels * 2) == 0) { "Unaligned PCM output" }
                            if (channels == 1) {
                                out.write(pcm)
                                totalBytes += pcm.size
                            } else {
                                for (frame in pcm.indices step channels * 2) {
                                    var sum = 0
                                    repeat(channels) { channel ->
                                        val offset = frame + channel * 2
                                        val value = ((pcm[offset + 1].toInt() shl 8) or (pcm[offset].toInt() and 0xff)).toShort()
                                        sum += value.toInt()
                                    }
                                    val mono = (sum / channels).toShort().toInt()
                                    out.writeByte(mono and 0xff)
                                    out.writeByte((mono shr 8) and 0xff)
                                    totalBytes += 2
                                }
                            }
                            outputEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            decoder.releaseOutputBuffer(slot, false)
                        }
                    }
                    // Bound unknown-duration recordings without consuming unbounded storage.
                    require(totalBytes <= maxDurationSeconds * sampleRate * 2L) {
                        "Recording exceeds the short-audio pilot limit"
                    }
                }
                require(totalBytes > 0 && totalBytes <= Int.MAX_VALUE - 44) { "Invalid WAV length" }
                out.seek(0)
                fun word(value: Int) {
                    out.writeByte(value and 0xff)
                    out.writeByte((value shr 8) and 0xff)
                }
                fun dword(value: Int) {
                    word(value and 0xffff)
                    word((value ushr 16) and 0xffff)
                }
                out.writeBytes("RIFF")
                dword((36 + totalBytes).toInt())
                out.writeBytes("WAVEfmt ")
                dword(16)
                word(1)
                word(1)
                dword(sampleRate)
                dword(sampleRate * 2)
                word(2)
                word(16)
                out.writeBytes("data")
                dword(totalBytes.toInt())
            }
        } catch (e: Exception) {
            wav.delete()
            throw e
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
