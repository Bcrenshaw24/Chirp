package com.example.chirp.spatial

import org.junit.Assert.*
import org.junit.Test

class SpatialFeedbackPolicyTest {
    @Test fun invalidDistancesDisablePulses() {
        for (distance in listOf(null, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
            assertNull(proximityPattern(distance))
    }
    @Test fun bandsHaveDistinctRhythmsAndExactBoundaries() {
        assertEquals(ProximityPattern(3, 25, 75, 650, 75), proximityPattern(0f))
        assertEquals(proximityPattern(0f), proximityPattern(0.5f))
        assertEquals(ProximityPattern(2, 30, 110, 1200, 65), proximityPattern(0.5001f))
        assertEquals(proximityPattern(1f), proximityPattern(2f))
        assertEquals(ProximityPattern(1, 30, 0, 2200, 55), proximityPattern(2.0001f))
        assertEquals(proximityPattern(3f), proximityPattern(Float.MAX_VALUE))
    }
    @Test fun nearerBandsAreFasterWithMorePulses() {
        val close = proximityPattern(0.2f)!!
        val near = proximityPattern(1.5f)!!
        val far = proximityPattern(3f)!!
        assertTrue(close.intervalMs < near.intervalMs && near.intervalMs < far.intervalMs)
        assertTrue(close.pulseCount > near.pulseCount && near.pulseCount > far.pulseCount)
    }
    @Test fun burstsAreBriefGentleAndNeverOverlap() {
        for (distance in listOf(0f, 0.5f, 1f, 2f, 3f, 100f)) {
            val pattern = proximityPattern(distance)!!
            val onTime = pattern.pulseCount * pattern.pulseMs
            val burstTime = onTime + (pattern.pulseCount - 1) * pattern.gapMs
            assertTrue(pattern.amplitude in 1..75)
            assertTrue(burstTime < pattern.intervalMs)
            assertTrue(onTime.toDouble() / pattern.intervalMs < 0.15)
        }
    }
    @Test fun initialSuccessfulTrackingIsSilent() {
        val policy = TrackingAnnouncements()
        assertNull(policy.update(true, 0))
        assertNull(policy.update(true, 1200))
        assertNull(policy.update(true, 20000))
    }
    @Test fun stableLossAndRecoveryAnnounceOnceEach() {
        val policy = TrackingAnnouncements()
        policy.update(true, 0); policy.update(true, 1200)
        assertNull(policy.update(false, 2000))
        assertNull(policy.update(false, 3199))
        assertTrue(policy.update(false, 3200)!!.contains("unavailable"))
        assertNull(policy.update(false, 9000))
        assertNull(policy.update(true, 10000))
        assertTrue(policy.update(true, 11200)!!.contains("restored"))
        assertNull(policy.update(true, 20000))
    }
    @Test fun briefTrackingFlickerDoesNotAnnounce() {
        val policy = TrackingAnnouncements()
        policy.update(true, 0); policy.update(true, 1200)
        assertNull(policy.update(false, 2000))
        assertNull(policy.update(true, 2500))
        assertNull(policy.update(true, 5000))
    }
    @Test fun cooldownDefersRecoveryInsteadOfRepeatingSpeech() {
        val policy = TrackingAnnouncements()
        policy.update(false, 0)
        assertNotNull(policy.update(false, 1200))
        assertNull(policy.update(true, 1500))
        assertNull(policy.update(true, 2700))
        assertNull(policy.update(true, 5199))
        assertTrue(policy.update(true, 5200)!!.contains("restored"))
        assertNull(policy.update(true, 9000))
    }
    @Test fun startupLossIsAnnouncedOnlyAfterDebounce() {
        val policy = TrackingAnnouncements()
        assertNull(policy.update(false, 10000))
        assertNull(policy.update(false, 11199))
        assertNotNull(policy.update(false, 11200))
        assertNull(policy.update(false, 20000))
    }
    @Test fun newSessionDoesNotReplayOldTransitions() {
        val previous = TrackingAnnouncements()
        previous.update(false, 0); previous.update(false, 1200)
        val resumed = TrackingAnnouncements()
        assertNull(resumed.update(true, 10000))
        assertNull(resumed.update(true, 11200))
    }
}
