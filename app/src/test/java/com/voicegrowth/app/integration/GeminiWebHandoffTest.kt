package com.voicegrowth.app.integration

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiWebHandoffTest {
    @Test fun opensOrdinaryGeminiWebsiteNotAnApi() {
        assertEquals("https://gemini.google.com/app", GeminiWebHandoff.GEMINI_URL)
    }
    @Test fun instructionsRequireEvidenceAndClinicianReview() {
        val prompt = GeminiWebHandoff.PROMPT
        assertTrue(prompt.contains("DRAFT"))
        assertTrue(prompt.contains("clinician"))
        assertTrue(prompt.contains("de-identified"))
        assertTrue(prompt.contains("null"))
        assertTrue(!prompt.contains("sk-proj-"))
    }
}
