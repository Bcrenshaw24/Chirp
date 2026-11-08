package com.example.chirp.sonar.alerts

import com.example.chirp.sonar.tracking.Track
import com.example.chirp.sonar.tracking.Trend
import org.junit.Assert.assertEquals
import org.junit.Test

class ProximityAlertTest {
    private val step = 100_000_000L // one 100 ms period

    private fun track(d: Double, v: Double = 0.0, confirmed: Boolean = true) = Track(
        d, v, when { v < -0.08 -> Trend.APPROACHING; v > 0.08 -> Trend.RECEDING; else -> Trend.STEADY }, confirmed, 0,
    )

    @Test fun unconfirmedTrackNeverAlerts() {
        val a = ProximityAlert()
        assertEquals(AlertLevel.NONE, a.update(0, track(0.2, confirmed = false)).level)
        assertEquals(AlertLevel.NONE, a.update(step, null).level)
    }

    @Test fun levelsByDistanceRaiseImmediately() {
        val a = ProximityAlert()
        assertEquals(AlertLevel.NONE, a.update(0, track(1.5)).level)
        assertEquals(AlertLevel.CAUTION, a.update(step, track(0.9)).level)
        assertEquals(AlertLevel.WARNING, a.update(2 * step, track(0.4)).level)
    }

    @Test fun fastApproachWarnsBeforeDistanceThreshold() {
        // 1.6 m away closing at 2 m/s: 0.8 s to contact.
        val alert = ProximityAlert().update(0, track(1.6, v = -2.0))
        assertEquals(AlertLevel.WARNING, alert.level)
        assertEquals("Closing: contact in 0.8 s", alert.reason)
    }

    @Test fun doesNotFlickerAtThreshold() {
        val a = ProximityAlert()
        a.update(0, track(0.49))
        // Jitter just above the 0.5 m threshold but inside the 0.1 m release margin: stays WARNING.
        repeat(20) { assertEquals(AlertLevel.WARNING, a.update((it + 1) * step, track(if (it % 2 == 0) 0.55 else 0.49)).level) }
    }

    @Test fun lowersOnlyAfterHoldTime() {
        val a = ProximityAlert()
        a.update(0, track(0.4))
        // Clearly beyond the caution zone from here on; 600 ms hold before dropping.
        for (k in 1..6) assertEquals(AlertLevel.WARNING, a.update(k * step, track(1.5)).level)
        assertEquals(AlertLevel.NONE, a.update(7 * step, track(1.5)).level)
    }

    @Test fun lostTrackClearsAfterHold() {
        val a = ProximityAlert()
        a.update(0, track(0.8))
        assertEquals(AlertLevel.CAUTION, a.update(step, null).level)
        assertEquals(AlertLevel.NONE, a.update(8 * step, null).level)
    }
}
