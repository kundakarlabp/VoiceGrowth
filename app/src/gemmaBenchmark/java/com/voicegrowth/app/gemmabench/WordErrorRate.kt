package com.voicegrowth.app.gemmabench

import java.util.Locale
import kotlin.math.min

/** WER is descriptive, not a clinical accuracy/safety score. */
internal object WordErrorRate {
    fun calculate(reference: String, hypothesis: String): Double? {
        val truth = tokenize(reference)
        if (truth.isEmpty()) return null
        val predicted = tokenize(hypothesis)
        var prev = IntArray(predicted.size + 1) { it }
        var next = IntArray(predicted.size + 1)
        for (i in truth.indices) {
            next[0] = i + 1
            for (j in predicted.indices) {
                val edit = if (truth[i] == predicted[j]) 0 else 1
                next[j + 1] = min(min(prev[j + 1] + 1, next[j] + 1), prev[j] + edit)
            }
            val tmp = prev
            prev = next
            next = tmp
        }
        return prev[predicted.size].toDouble() / truth.size
    }

    private fun tokenize(input: String): List<String> = input.lowercase(Locale.ROOT)
        .replace(Regex("[\\p{P}\\p{S}]+"), " ")
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
}
