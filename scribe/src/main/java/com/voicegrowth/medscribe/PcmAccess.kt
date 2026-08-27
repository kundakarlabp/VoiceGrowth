package com.voicegrowth.medscribe

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

object PcmAccess {
    fun readSegment(pcm: DecodedPcmFile, startSeconds: Double, endSeconds: Double): FloatArray {
        val start = (startSeconds.coerceAtLeast(0.0) * pcm.sampleRate).toLong().coerceAtMost(pcm.sampleCount)
        val end = (endSeconds.coerceAtLeast(startSeconds) * pcm.sampleRate).toLong().coerceAtMost(pcm.sampleCount)
        val count = (end - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (count <= 0) return FloatArray(0)
        val raw = ByteArray(count * 2)
        RandomAccessFile(pcm.file, "r").use { input ->
            input.seek(start * 2L)
            input.readFully(raw)
        }
        return AudioUtils.pcm16BytesToFloat(raw)
    }

    fun readResampled16k(pcm: DecodedPcmFile, maxSeconds: Long): FloatArray {
        val sourceCount = min(pcm.sampleCount, pcm.sampleRate.toLong() * maxSeconds)
        if (sourceCount <= 0) return FloatArray(0)
        if (pcm.sampleRate == TARGET_RATE) {
            return readSegment(pcm, 0.0, sourceCount.toDouble() / pcm.sampleRate)
        }

        val outputCount = ceil(sourceCount.toDouble() * TARGET_RATE / pcm.sampleRate).toInt()
        val output = FloatArray(outputCount)
        val step = pcm.sampleRate.toDouble() / TARGET_RATE
        var outIndex = 0
        var nextSrc = 0.0
        var base = 0L
        val blockSamples = 16_384

        RandomAccessFile(pcm.file, "r").use { input ->
            while (base < sourceCount && outIndex < output.size) {
                val count = min(blockSamples.toLong(), sourceCount - base).toInt()
                val includeNext = if (base + count < sourceCount) 1 else 0
                val raw = ByteArray((count + includeNext) * 2)
                input.seek(base * 2L)
                input.readFully(raw)
                val shorts = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val block = FloatArray(shorts.remaining())
                var i = 0
                while (shorts.hasRemaining()) block[i++] = shorts.get().toFloat() / 32768f

                val blockEndExclusive = base + count
                while (outIndex < output.size && nextSrc < blockEndExclusive) {
                    val local = nextSrc - base
                    val left = floor(local).toInt().coerceIn(0, block.lastIndex)
                    val right = (left + 1).coerceAtMost(block.lastIndex)
                    val frac = (local - left).toFloat()
                    output[outIndex++] = block[left] * (1f - frac) + block[right] * frac
                    nextSrc += step
                }
                base += count
            }
        }
        return if (outIndex == output.size) output else output.copyOf(outIndex)
    }

    const val TARGET_RATE = 16_000
}
