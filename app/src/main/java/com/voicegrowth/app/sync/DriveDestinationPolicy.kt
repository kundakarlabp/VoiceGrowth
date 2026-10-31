package com.voicegrowth.app.sync

/**
 * The Android picker must point at the existing private VoiceGrowth/Audio folder
 * (preferred), or its immediate VoiceGrowth parent. Never silently create a
 * second VoiceGrowth tree inside My Drive, an arbitrary parent, or Transcripts.
 *
 * SAF does not expose the Google Drive REST ID: the user must select the
 * intended existing folder; this policy prevents accidental nested roots.
 */
internal object DriveDestinationPolicy {
    fun isSupportedRoot(name: String): Boolean =
        name.equals("Audio", ignoreCase = true) ||
            name.equals("VoiceGrowth", ignoreCase = true)

    fun relativeSegments(rootName: String, segments: List<String>): List<String> {
        require(isSupportedRoot(rootName)) {
            "Select the existing VoiceGrowth/Audio folder in Google Drive (or its VoiceGrowth parent)."
        }
        require(segments.take(2).map(String::lowercase) == listOf("voicegrowth", "audio")) {
            "Only the fixed VoiceGrowth/Audio destination is supported"
        }
        return if (rootName.equals("Audio", ignoreCase = true)) {
            emptyList()
        } else {
            listOf("Audio")
        }
    }
}
