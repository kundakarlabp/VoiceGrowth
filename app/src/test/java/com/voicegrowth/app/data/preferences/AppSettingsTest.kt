package com.voicegrowth.app.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun captureOnlyDefaultsPreserveAndUploadOriginalAudio() {
        val settings = AppSettings()

        assertTrue(settings.uploadAudio)
        assertFalse(settings.uploadTranscript)
        assertFalse(settings.onlyProcessOver30Sec)
        assertEquals("VoiceGrowth/Audio", settings.driveFolderHierarchy)
        assertFalse(settings.deleteSourceAudioEnabled)
        assertFalse(settings.aiEnabled)
        assertFalse(settings.dailyDigestEnabled)
    }
}
