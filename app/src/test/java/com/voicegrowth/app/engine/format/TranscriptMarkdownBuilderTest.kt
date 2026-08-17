package com.voicegrowth.app.engine.format

import com.voicegrowth.app.data.model.RecordingSource
import com.voicegrowth.app.engine.privacy.DeidentificationResult
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptMarkdownBuilderTest {
    @Test
    fun markdownContainsSafetyAndProvenanceMetadata() {
        val md = TranscriptMarkdownBuilder.buildMarkdown(
            recordedAtMillis = 1_700_000_000_000,
            durationSeconds = 90,
            source = RecordingSource.MANUAL_DISCUSSION,
            language = "en-IN",
            engineName = "test-engine",
            rawTranscript = "De-identified text",
            deidResult = DeidentificationResult("De-identified text", 1, listOf("Phone Number"), true),
            detectedThemes = listOf("Antimicrobial stewardship")
        )
        assertTrue(md.contains("MANUAL REVIEW RECOMMENDED"))
        assertTrue(md.contains("test-engine"))
        assertTrue(md.contains("Antimicrobial stewardship"))
        assertTrue(md.contains("does not guarantee"))
    }

    @Test
    fun filenamesAreUniqueForDifferentRecordingsAtSameSecond() {
        val recordedAt = 1_700_000_000_000
        val first = TranscriptMarkdownBuilder.generateFileName(
            recordedAtMillis = recordedAt,
            source = RecordingSource.CALL_RECORDING,
            recordingId = 41
        )
        val second = TranscriptMarkdownBuilder.generateFileName(
            recordedAtMillis = recordedAt,
            source = RecordingSource.CALL_RECORDING,
            recordingId = 42
        )

        assertNotEquals(first, second)
        assertTrue(first.endsWith("_r41.md"))
        assertTrue(second.endsWith("_r42.md"))
    }
}
