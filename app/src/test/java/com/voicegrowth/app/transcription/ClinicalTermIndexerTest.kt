package com.voicegrowth.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClinicalTermIndexerTest {
    @Test fun findsOnlyActualSpokenTermsWithoutDiagnosing() {
        val hits = ClinicalTermIndexer.find("Mero and BioFire blood cultures; CRO unclear", 1200L)
        assertTrue(hits.any { it.raw == "Mero" && it.tentativeTerm == "meropenem_possible" })
        assertTrue(hits.any { it.raw == "CRO" && it.tentativeTerm == "context_required" })
        assertTrue(hits.all { it.startMs == 1200L })
    }
    @Test fun doesNotInventAbsentTerms() {
        assertEquals(0, ClinicalTermIndexer.find("No test or medicine was named", 0L).size)
    }
}
