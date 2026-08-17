package com.voicegrowth.app.engine.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptChunkerTest {
    @Test
    fun shortTranscriptRemainsSingleChunk() {
        assertEquals(listOf("short transcript"), TranscriptChunker.chunk("short transcript", 500))
    }

    @Test
    fun longTranscriptIsBoundedWithoutDroppingContent() {
        val text = (1..30).joinToString("\n\n") { index ->
            "Paragraph $index. " + "clinical discussion ".repeat(8)
        }
        val chunks = TranscriptChunker.chunk(text, 500)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 500 })
        val reconstructed = chunks.joinToString("\n\n")
        assertTrue(reconstructed.contains("Paragraph 1."))
        assertTrue(reconstructed.contains("Paragraph 30."))
    }
}
