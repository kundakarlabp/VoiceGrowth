package com.voicegrowth.app.gemmabench

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Convert the Android decoder's temporary mono 16-bit PCM WAV into 16 kHz mono.
 * This bounded helper handles <=25 s only. Linear interpolation is sufficient
 * for a hardware compatibility smoke test, not a production clinical ASR path.
 */
internal object BenchmarkAudio {
    fun convertTo16k(source: File, destination: File) {
        val all = source.readBytes()
        require(all.size >= 44 && all.size <= 10_000_044) { "Unsupported WAV size" }
        require(String(all, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(all, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not RIFF WAV" }
        val view = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
        val format = view.getShort(20).toInt()
        val channels = view.getShort(22).toInt()
        val rate = view.getInt(24)
        val bits = view.getShort(34).toInt()
        require(format == 1 && channels == 1 && bits == 16) { "Expected mono PCM16 WAV" }
        require(rate in 8000..192000) { "Unreasonable audio sample rate" }
        require(String(all, 36, 4, Charsets.US_ASCII) == "data") { "Expected simple PCM WAV" }
        val length = view.getInt(40)
        require(length in 1..(all.size - 44) && length % 2 == 0) { "Invalid PCM length" }
        val frames = length / 2
        require(frames.toLong() <= 25L * rate) { "Only <=25 seconds supported" }
        val outFrames = (frames * 16_000.0 / rate).roundToInt()
        val bytes = ByteBuffer.allocate(44 + outFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII))
        bytes.putInt(36 + outFrames * 2)
        bytes.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        bytes.putInt(16)
        bytes.putShort(1)
        bytes.putShort(1)
        bytes.putInt(16_000)
        bytes.putInt(32_000)
        bytes.putShort(2)
        bytes.putShort(16)
        bytes.put("data".toByteArray(Charsets.US_ASCII))
        bytes.putInt(outFrames * 2)
        for (i in 0 until outFrames) {
            val sourcePosition = (i.toDouble() * rate / 16_000.0).coerceIn(0.0, (frames - 1).toDouble())
            val first = sourcePosition.toInt()
            val second = (first + 1).coerceAtMost(frames - 1)
            val fraction = sourcePosition - first
            val a = view.getShort(44 + first * 2).toDouble()
            val b = view.getShort(44 + second * 2).toDouble()
            val sample = (a + (b - a) * fraction).roundToInt().coerceIn(-32768, 32767)
            bytes.putShort(sample.toShort())
        }
        destination.outputStream().use { it.write(bytes.array()) }
    }
}
