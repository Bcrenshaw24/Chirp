package com.example.chirp.sonar.validation

import com.example.chirp.sonar.ranging.RangeCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccuracyTest {
    @Test fun measurementPicksEchoNearestTheTapeDistance() {
        val m = AccuracyMeasurement(0.5, wanted = 3)
        m.add(listOf(0.2 to 30.0, 0.52 to 20.0, 1.4 to 25.0)) // table at 0.2, wall at 1.4, target at 0.52
        m.add(emptyList())                                    // a miss
        m.add(listOf(0.51 to 22.0))
        m.add(listOf(0.95 to 40.0))                           // outside the window: a miss
        m.add(listOf(0.53 to 18.0))
        val p = m.result()
        assertEquals(listOf(0.52, 0.51, 0.53), p.readings)
        assertEquals(0.52, p.meanM, 1e-9)
        assertEquals(0.52, p.medianM, 1e-9)
        assertEquals(0.02, p.biasM, 1e-9)
        assertEquals(0.01, p.stdM, 1e-9)
        assertEquals(0.6, p.detectionRate, 1e-9)
        assertEquals(20.0, p.medianSnrDb!!, 1e-9)
        assertTrue(m.isDone)
    }

    @Test fun measurementGivesUpAfterMaxPeriods() {
        val m = AccuracyMeasurement(1.0, maxPeriods = 5)
        repeat(5) { m.add(emptyList()) }
        assertTrue(m.isDone)
        assertEquals(0.0, m.result().detectionRate, 0.0)
    }

    @Test fun fitRecoversSpacingOffsetAndTemperatureScale() {
        // Raw reads 1.5% short (warm room) and 12 mm short (spacing guess).
        val truth = listOf(0.3, 0.5, 1.0, 1.5)
        val c = RangeCorrection.fit(truth.map { ((it - 0.012) / 1.015) to it })!!
        assertEquals(1.015, c.scale, 1e-9)
        assertEquals(0.012, c.offsetM, 1e-9)
        truth.forEach { assertEquals(it, c.apply((it - 0.012) / 1.015), 1e-9) }
    }

    @Test fun narrowSpreadFitsOffsetOnly() {
        val c = RangeCorrection.fit(listOf(0.488 to 0.5, 0.688 to 0.7))!!
        assertEquals(1.0, c.scale, 0.0)
        assertEquals(0.012, c.offsetM, 1e-9)
    }

    @Test fun implausibleFitIsRejected() {
        // One point measured the wrong reflector: slope far from 1.
        assertNull(RangeCorrection.fit(listOf(0.3 to 0.3, 1.4 to 0.5, 1.0 to 1.0)))
        assertNull(RangeCorrection.fit(listOf(0.3 to 0.9)))
    }

    @Test fun onDeviceRunIsAccepted() {
        // Real S23+ run: these used to be rejected (scale 0.889).
        val c = RangeCorrection.fit(listOf(0.269 to 0.3, 0.518 to 0.5, 1.080 to 1.0, 1.623 to 1.5))!!
        listOf(0.269 to 0.3, 0.518 to 0.5, 1.080 to 1.0, 1.623 to 1.5).forEach { (raw, t) -> assertEquals(t, c.apply(raw), 0.015) }
    }
}
