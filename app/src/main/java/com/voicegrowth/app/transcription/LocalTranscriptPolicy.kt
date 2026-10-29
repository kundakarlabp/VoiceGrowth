package com.voicegrowth.app.transcription

import com.voicegrowth.app.sync.ArchiveFilePolicy

/** Stable transcript name; never identify clinical files using a patient name/CR. */
internal object LocalTranscriptPolicy {
    fun name(id: Long, recordedAt: Long, source: String, originalName: String): String =
        ArchiveFilePolicy.stableName(id, recordedAt, source, originalName).substringBeforeLast('.') + " - Transcript.md"
    fun archiveHierarchy(audioHierarchy: String): String {
        val normalized = audioHierarchy.trim('/').ifBlank { "VoiceGrowth/Audio" }
        return when {
            normalized.equals("VoiceGrowth", true) -> "VoiceGrowth/Transcripts"
            normalized.endsWith("/Audio", true) -> normalized.dropLast(6) + "/Transcripts"
            else -> "VoiceGrowth/Transcripts"
        }
    }
}
