package com.voicegrowth.app.transcription

/** Whisper's ~30-second audio context; 1 second of overlap protects boundary words. */
internal object TranscriptSegmentPlan {
    const val WINDOW_MILLIS = 28_000L
    const val OVERLAP_MILLIS = 1_000L
    const val STEP_MILLIS = WINDOW_MILLIS - OVERLAP_MILLIS
    const val MAX_RECORDING_SECONDS = 7_200L

    data class Window(val index: Int, val startMs: Long, val endMs: Long)

    fun count(durationMs: Long): Int {
        require(durationMs in 1..MAX_RECORDING_SECONDS * 1_000L) { "Unsupported recording duration" }
        return ((durationMs - 1) / STEP_MILLIS + 1).toInt()
    }

    fun window(durationMs: Long, index: Int): Window {
        val total = count(durationMs)
        require(index in 0 until total) { "Window index out of range" }
        val start = index * STEP_MILLIS
        return Window(index, start, minOf(durationMs, start + WINDOW_MILLIS))
    }
}
