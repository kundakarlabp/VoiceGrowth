package com.voicegrowth.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveDestinationPolicyTest {
    private val requested = listOf("VoiceGrowth", "Audio")

    @Test fun choosingVoiceGrowthRootOnlyCreatesAudioChild() {
        assertEquals(listOf("Audio"), DriveDestinationPolicy.relativeSegments("VoiceGrowth", requested))
    }

    @Test fun choosingExistingAudioFolderCreatesNoDuplicateHierarchy() {
        assertEquals(emptyList<String>(), DriveDestinationPolicy.relativeSegments("Audio", requested))
    }

    @Test fun choosingParentCreatesCanonicalHierarchy() {
        assertEquals(requested, DriveDestinationPolicy.relativeSegments("My Drive", requested))
    }

    @Test fun caseInsensitiveFolderNamesAreSupported() {
        assertEquals(emptyList<String>(), DriveDestinationPolicy.relativeSegments("aUdIo", requested))
    }
}
