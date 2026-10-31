package com.voicegrowth.app.transcription

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile

/**
 * Decodes only a bounded time window (up to 28 seconds) into PCM16 mono WAV.
 * Whisper AAR handles the sample-rate conversion internally. No full-size
 * decoded WAV, no internet, no third-party transcoding binary.
 */
internal object SegmentedAudioWavDecoder {
    fun convertWindow(context: Context, source: Uri, wavFile: File,
                      window: TranscriptSegmentPlan.Window): Int {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            when (source.scheme) {
                "file" -> extractor.setDataSource(requireNotNull(source.path))
                "content" -> extractor.setDataSource(context, source, null)
                else -> error("Recording source URI is not readable")
            }
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Recording contains no audio track")
            val track = extractor.getTrackFormat(index)
            val mime = requireNotNull(track.getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(index)
            val startUs = window.startMs * 1000
            val endUs = window.endMs * 1000
            extractor.seekTo((startUs - 250_000L).coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // Reject non-seekable remote audio providers instead of repeatedly decoding
            // hours of preceding audio for every new window.
            if (startUs > 5_000_000L) {
                require(extractor.sampleTime >= startUs - 5_000_000L) {
                    "Audio source cannot seek near the requested segment"
                }
            }
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(track, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var rate = track.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = track.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var inDone = false
            var outDone = false
            var bytesWritten = 0L
            var idle = 0
            RandomAccessFile(wavFile, "rw").use { out ->
                out.setLength(0)
                repeat(44) { out.writeByte(0) }
                while (!outDone) {
                    if (!inDone) {
                        val slot = decoder.dequeueInputBuffer(10_000L)
                        if (slot >= 0) {
                            val buffer = requireNotNull(decoder.getInputBuffer(slot))
                            buffer.clear()
                            val time = extractor.sampleTime
                            val count = if (time < 0 || time > endUs + 250_000L) -1
                                else extractor.readSampleData(buffer, 0)
                            if (count < 0) {
                                decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                decoder.queueInputBuffer(slot, 0, count, time, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val slot = decoder.dequeueOutputBuffer(info, 10_000L)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = decoder.outputFormat
                            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else AudioFormat.ENCODING_PCM_16BIT
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT) { "Unsupported PCM encoding" }
                        }
                        in 0..Int.MAX_VALUE -> {
                            idle = 0
                            require(channels in 1..8 && rate in 8_000..192_000) { "Unsupported audio format" }
                            require(info.size % (channels * 2) == 0) { "Unaligned PCM frames" }
                            val decoded = requireNotNull(decoder.getOutputBuffer(slot))
                            val frames = info.size / (channels * 2)
                            for (frame in 0 until frames) {
                                val time = info.presentationTimeUs + frame * 1_000_000L / rate
                                if (time < startUs || time >= endUs) continue
                                val offset = info.offset + frame * channels * 2
                                var mixed = 0
                                for (channel in 0 until channels) {
                                    val pos = offset + channel * 2
                                    val lo = decoded.get(pos).toInt() and 0xff
                                    val hi = decoded.get(pos + 1).toInt()
                                    mixed += ((hi shl 8) or lo).toShort().toInt()
                                }
                                val mono = mixed / channels
                                out.writeByte(mono and 0xff)
                                out.writeByte((mono shr 8) and 0xff)
                                bytesWritten += 2
                            }
                            outDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 ||
                                info.presentationTimeUs > endUs + 250_000L
                            decoder.releaseOutputBuffer(slot, false)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            idle++
                            require(idle < 1000) { "Decoder stalled" }
                        }
                    }
                    require(bytesWritten <= 28L * rate * 2L + 2L * rate) {
                        "Audio segment exceeded bounded size"
                    }
                }
                require(bytesWritten > 0 && bytesWritten < Int.MAX_VALUE - 44) {
                    "No valid audio samples for segment"
                }
                out.seek(0)
                fun short(value: Int) {
                    out.writeByte(value and 0xff)
                    out.writeByte((value shr 8) and 0xff)
                }
                fun int32(value: Int) {
                    short(value and 0xffff)
                    short((value ushr 16) and 0xffff)
                }
                out.writeBytes("RIFF")
                int32(36 + bytesWritten.toInt())
                out.writeBytes("WAVEfmt ")
                int32(16)
                short(1)
                short(1)
                int32(rate)
                int32(rate * 2)
                short(2)
                short(16)
                out.writeBytes("data")
                int32(bytesWritten.toInt())
            }
            return (bytesWritten / 2).toInt()
        } catch (error: Exception) {
            wavFile.delete()
            throw error
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
