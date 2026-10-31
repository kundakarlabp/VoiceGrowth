package com.voicegrowth.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TranscriptSegmentPlanTest {
    @Test fun plansTwentyEightSecondWindowsWithOneSecondOverlap() {
        assertEquals(3, TranscriptSegmentPlan.count(60_000))
        val first = TranscriptSegmentPlan.window(60_000, 0)
        val second = TranscriptSegmentPlan.window(60_000, 1)
        val third = TranscriptSegmentPlan.window(60_000, 2)
        assertEquals(0L, first.startMs)
        assertEquals(28_000L, first.endMs)
        assertEquals(27_000L, second.startMs)
        assertEquals(55_000L, second.endMs)
        assertEquals(54_000L, third.startMs)
        assertEquals(60_000L, third.endMs)
    }
    @Test fun handlesVeryShortAndBoundaryAudio() {
        assertEquals(1, TranscriptSegmentPlan.count(1_000))
        assertEquals(2, TranscriptSegmentPlan.count(28_001))
        assertEquals(1, TranscriptSegmentPlan.count(27_000))
    }
    @Test fun enforcesTwoHourLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptSegmentPlan.count(7_200_001)
        }
    }
}
