package com.voicegrowth.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTranscriptPolicyTest {
    @Test fun `transcript names are deterministic and independent of patient information`() {
        val a = LocalTranscriptPolicy.name(15L, 0L, "MANUAL_DISCUSSION", "patient-A.m4a")
        val b = LocalTranscriptPolicy.name(15L, 0L, "MANUAL_DISCUSSION", "patient-B.m4a")
        assertEquals(a, b)
        assertEquals("VG_19700101_000000_15_manual_discussion - Transcript.md", a)
    }

    @Test fun `transcripts use a sibling to Audio`() {
        assertEquals("VoiceGrowth/Transcripts",
            LocalTranscriptPolicy.archiveHierarchy("VoiceGrowth/Audio"))
        assertEquals("VoiceGrowth/Transcripts",
            LocalTranscriptPolicy.archiveHierarchy("VoiceGrowth"))
        assertEquals("VoiceGrowth/Transcripts",
            LocalTranscriptPolicy.archiveHierarchy(""))
    }
}
