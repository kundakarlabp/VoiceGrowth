package com.voicegrowth.app.engine.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeSearchIndexTest {
    @Test
    fun searchRanksMatchingThemesAndContent() {
        val entries = listOf(
            KnowledgeEntry(1, "round.m4a", "CRAB|Antimicrobial stewardship", 100, "MANUAL_DISCUSSION", "We discussed high dose sulbactam for CRAB."),
            KnowledgeEntry(2, "reflection.m4a", "Transplant", 200, "VOICE_REFLECTION", "CMV prophylaxis follow-up."),
            KnowledgeEntry(3, "call.m4a", "General", 300, "CALL_RECORDING", "A brief unrelated call.")
        )
        val matches = KnowledgeSearchIndex.search(entries, "CRAB sulbactam")
        assertEquals(1L, matches.first().entry.recordingId)
        assertTrue(matches.first().excerpt.contains("sulbactam", ignoreCase = true))
    }

    @Test
    fun evidenceForAiIncludesRecordingIdsAndRespectsBudget() {
        val entry = KnowledgeEntry(42, "discussion.m4a", "AMR", 100, "MANUAL_DISCUSSION", "evidence ".repeat(1_000))
        val evidence = KnowledgeSearchIndex.evidenceForAi(listOf(KnowledgeMatch(entry, 10, "evidence")), maxChars = 1_000)
        assertTrue(evidence.contains("RECORDING 42"))
        assertTrue(evidence.length <= 1_000)
    }

    @Test
    fun sourceExtractionDoesNotFeedPriorAiSynthesisBackIntoKnowledgeSearch() {
        val markdown = """
            ## On-device AI synthesis
            fabricated-ai-only-token

            ## De-identified ASR transcript

            source evidence says cefiderocol was discussed

            ## Automatic metadata
            Possible themes:
            - AMR
        """.trimIndent()

        val source = KnowledgeSearchIndex.extractSourceTranscript(markdown)
        assertTrue(source.contains("cefiderocol"))
        assertFalse(source.contains("fabricated-ai-only-token"))
        assertFalse(source.contains("Automatic metadata"))
    }
}
