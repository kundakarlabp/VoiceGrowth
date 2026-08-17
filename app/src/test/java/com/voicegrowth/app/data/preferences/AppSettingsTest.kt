package com.voicegrowth.app.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun destructiveSourceAudioDeletionIsOffByDefault() {
        val settings = AppSettings()

        assertFalse(settings.deleteSourceAudioEnabled)
        assertTrue(settings.clinicalPrivacyMode)
        assertFalse(settings.uploadAudio)
    }
}
