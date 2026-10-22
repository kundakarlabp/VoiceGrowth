package com.voicegrowth.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArchiveFilePolicyTest {
    @Test fun retriesKeepTheSameDestinationName() {
        val one = ArchiveFilePolicy.stableName(21, 1_760_000_000_000L, "CALL_RECORDING", "call.m4a")
        val two = ArchiveFilePolicy.stableName(21, 1_760_000_000_000L, "CALL_RECORDING", "call.m4a")
        assertEquals(one, two)
    }

    @Test fun separateRecordingsNeverShareTheSameDestinationName() {
        val first = ArchiveFilePolicy.stableName(21, 1_760_000_000_000L, "CALL_RECORDING", "call.m4a")
        val second = ArchiveFilePolicy.stableName(22, 1_760_000_000_000L, "CALL_RECORDING", "call.m4a")
        assertNotEquals(first, second)
    }

    @Test fun filenameDoesNotCopyUntrustedPathCharacters() {
        val name = ArchiveFilePolicy.stableName(5, 1_760_000_000_000L, "IMPORTED_AUDIO", "../../patient.wav")
        assertEquals(true, name.startsWith("VG_"))
        assertEquals(false, name.contains('/'))
        assertEquals(true, name.endsWith(".wav"))
    }
}
