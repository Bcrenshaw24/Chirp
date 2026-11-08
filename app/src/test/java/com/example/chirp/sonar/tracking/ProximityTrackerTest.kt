package com.example.chirp.sonar.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityTrackerTest {
    private val stepNs = 100_000_000L

    @Test fun steadyObjectIsConfirmedAndSteady() {
        val t = ProximityTracker()
        var track: Track? = null
        repeat(10) { track = t.update(it * stepNs, listOf(1.0 + if (it % 2 == 0) 0.004 else -0.004)) }
        assertTrue(track!!.confirmed)
        assertEquals(Trend.STEADY, track!!.trend)
        assertEquals(1.0, track!!.distanceM, 0.01)
    }

    @Test fun singleOutlierDoesNotMoveTheTrack() {
        val t = ProximityTracker()
        repeat(6) { t.update(it * stepNs, listOf(1.0)) }
        val track = t.update(6 * stepNs, listOf(2.5))!!
        assertEquals(1.0, track.distanceM, 0.01)
        assertEquals(1, track.missed)
    }

    @Test fun nearestMeasurementToPredictionIsUsed() {
        val t = ProximityTracker()
        repeat(6) { t.update(it * stepNs, listOf(1.0, 2.0)) }
        assertEquals(1.0, t.update(6 * stepNs, listOf(2.0, 1.01))!!.distanceM, 0.01)
    }

    @Test fun recedingObjectIsReceding() {
        val t = ProximityTracker()
        var track: Track? = null
        repeat(20) { track = t.update(it * stepNs, listOf(0.5 + 0.03 * it)) }
        assertEquals(Trend.RECEDING, track!!.trend)
        assertNull(track!!.timeToContactSec)
    }

    @Test fun trackIsDroppedAfterRepeatedMisses() {
        val t = ProximityTracker(maxMisses = 3)
        repeat(5) { t.update(it * stepNs, listOf(1.0)) }
        repeat(3) { assertFalse(t.update((5 + it) * stepNs, emptyList()) == null) }
        assertNull(t.update(8 * stepNs, emptyList()))
    }
}
