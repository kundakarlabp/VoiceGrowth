package com.voicegrowth.medscribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicalTextTest {
    @Test
    fun conservativeMedicalCleanupDoesNotRewriteMeaning() {
        val input = "Started mero penem and ampho tericin after source control"
        val output = MedicalText.clean(input)
        assertEquals("Started meropenem and amphotericin after source control", output)
    }

    @Test
    fun detectsRelevantClinicalTopics() {
        val topics = MedicalText.detectedTopics("Kidney transplant patient with CMV and antimicrobial de-escalation")
        assertTrue(topics.contains("Transplant / immunocompromised host"))
        assertTrue(topics.contains("Antimicrobial stewardship"))
    }
}
