package com.voicegrowth.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveDestinationPolicyTest {
    private val requested = listOf("VoiceGrowth", "Audio")

    @Test fun choosingVoiceGrowthRootOnlyCreatesAudioChild() {
        assertEquals(listOf("Audio"), DriveDestinationPolicy.relativeSegments("VoiceGrowth", requested))
    }

    @Test fun choosingExistingAudioFolderCreatesNoDuplicateHierarchy() {
        assertEquals(emptyList<String>(), DriveDestinationPolicy.relativeSegments("Audio", requested))
    }

    @Test fun caseInsensitiveFolderNamesAreSupported() {
        assertEquals(emptyList<String>(), DriveDestinationPolicy.relativeSegments("aUdIo", requested))
    }

    @Test fun cannotAccidentallyCreateNestedVoiceGrowthFromDriveRoot() {
        assertFalse(DriveDestinationPolicy.isSupportedRoot("My Drive"))
        try {
            DriveDestinationPolicy.relativeSegments("My Drive", requested)
            throw AssertionError("Arbitrary root should be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("existing VoiceGrowth/Audio"))
        }
    }

    @Test fun cannotSaveAudioInsideTranscripts() {
        assertFalse(DriveDestinationPolicy.isSupportedRoot("Transcripts"))
    }

    @Test fun hierarchyCannotBeRedirectedToAnotherSubfolder() {
        try {
            DriveDestinationPolicy.relativeSegments("Audio", listOf("VoiceGrowth", "Transcripts"))
            throw AssertionError("Unexpected hierarchy should be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("fixed VoiceGrowth/Audio"))
        }
    }
}
