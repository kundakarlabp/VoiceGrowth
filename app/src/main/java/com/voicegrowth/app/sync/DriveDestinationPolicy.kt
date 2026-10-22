package com.voicegrowth.app.sync

/**
 * Google Drive's SAF picker may grant VoiceGrowth, Audio, or their parent.
 * Always write to exactly one VoiceGrowth/Audio tree, never a duplicate subtree.
 */
internal object DriveDestinationPolicy {
    fun relativeSegments(rootName: String, segments: List<String>): List<String> {
        if (segments.isEmpty()) return segments
        return when {
            rootName.equals("Audio", ignoreCase = true) &&
                segments.size >= 2 &&
                segments[0].equals("VoiceGrowth", ignoreCase = true) &&
                segments[1].equals("Audio", ignoreCase = true) -> segments.drop(2)
            rootName.equals(segments[0], ignoreCase = true) -> segments.drop(1)
            else -> segments
        }
    }
}
