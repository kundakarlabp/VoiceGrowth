package com.voicegrowth.app.engine.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptChunkMergerTest {
    @Test
    fun `removes exact word overlap between adjacent chunks`() {
        val merged = TranscriptChunkMerger.merge(
            listOf(
                "The patient has fever and rising creatinine",
                "rising creatinine and tacrolimus was discussed"
            )
        )

        assertEquals(
            "The patient has fever and rising creatinine and tacrolimus was discussed",
            merged
        )
    }

    @Test
    fun `keeps non-overlapping chunks in order`() {
        val merged = TranscriptChunkMerger.merge(listOf("First discussion point.", "Second action item."))
        assertEquals("First discussion point. Second action item.", merged)
    }

    @Test
    fun `ignores blank chunks`() {
        assertEquals("Useful text", TranscriptChunkMerger.merge(listOf("", "   ", "Useful text")))
    }
}
