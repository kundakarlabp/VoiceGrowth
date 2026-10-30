package com.voicegrowth.app.gemmabench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordErrorRateTest {
    @Test fun exactText() {
        assertEquals(0.0, WordErrorRate.calculate("Mero Blood Culture", "mero blood culture")!!, 0.000001)
    }
    @Test fun insertionAndTeluguTokens() {
        assertEquals(1.0 / 3.0, WordErrorRate.calculate("మందు mero BioFire",
            "మందు mero and BioFire")!!, 0.000001)
    }
    @Test fun emptyReferenceIsNotScored() {
        assertNull(WordErrorRate.calculate("  ", "hallucinated words"))
    }
}
