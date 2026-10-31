package com.voicegrowth.app.transcription

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/** Best-effort duration recovery for legacy recordings saved without metadata. */
internal object AudioDurationProbe {
    fun seconds(context: Context, source: Uri): Long {
        val extractor = MediaExtractor()
        try {
            when (source.scheme) {
                "file" -> extractor.setDataSource(requireNotNull(source.path))
                "content" -> extractor.setDataSource(context, source, null)
                else -> error("Unsupported recording URI")
            }
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                if (!format.containsKey(MediaFormat.KEY_DURATION)) continue
                val us = format.getLong(MediaFormat.KEY_DURATION)
                require(us > 0L) { "Unknown audio duration" }
                val seconds = (us + 999_999L) / 1_000_000L
                require(seconds in 1..TranscriptSegmentPlan.MAX_RECORDING_SECONDS) {
                    "Recording exceeds the two-hour offline ASR safety limit"
                }
                return seconds
            }
            error("Cannot determine audio duration from recording metadata")
        } finally {
            extractor.release()
        }
    }
}
