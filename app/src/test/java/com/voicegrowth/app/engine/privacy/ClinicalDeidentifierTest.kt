package com.voicegrowth.app.engine.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClinicalDeidentifierTest {
    @Test
    fun redactsCommonClinicalIdentifiers() {
        val input = "Patient name is Ravi Kumar, UHID: NIMS-123456, phone 9876543210, email ravi@example.com and ID 1234 5678 9012."
        val result = ClinicalDeidentifier.process(input, enabled = true)

        assertFalse(result.scrubbedText.contains("Ravi Kumar", ignoreCase = true))
        assertFalse(result.scrubbedText.contains("9876543210"))
        assertFalse(result.scrubbedText.contains("ravi@example.com"))
        assertFalse(result.scrubbedText.contains("1234 5678 9012"))
        assertTrue(result.identifiersDetectedCount >= 4)
        assertTrue(result.requiresManualReview)
    }

    @Test
    fun privacyOffDoesNotClaimSafety() {
        val result = ClinicalDeidentifier.process("Patient name is Ravi", enabled = false)
        assertTrue(result.requiresManualReview)
        assertTrue(result.scrubbedText.contains("Ravi"))
    }
}
