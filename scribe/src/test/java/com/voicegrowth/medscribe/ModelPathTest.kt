package com.voicegrowth.medscribe

import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPathTest {
    @Test
    fun modelChoicesExposeExpectedMultilingualVariants() {
        val ids = ModelManager.whisperChoices.map { it.first }
        assertTrue(ids.containsAll(listOf("tiny", "base", "small")))
    }
}
