package com.example.chirp.sonar.dsp

import com.example.chirp.sonar.audio.ChirpGenerator
import com.example.chirp.sonar.ranging.Ranging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class DspTest {
    private val fs = 48_000

    private fun signChanges(x: DoubleArray) = (1 until x.size).count { (x[it - 1] < 0) != (x[it] < 0) }

    @Test fun chirpLengthAmplitudeAndFades() {
        val x = ChirpGenerator.linearChirp(fs, 15_000.0, 20_000.0, 0.005, 0.5, 0.0005)
        assertEquals(240, x.size)
        assertTrue(x.maxOf { abs(it) } <= 0.5 && x.maxOf { abs(it) } > 0.45)
        assertTrue(abs(x.first()) < 1e-3 && abs(x.last()) < 1e-3)
    }

    @Test fun chirpCycleCountMatchesMeanFrequency() {
        // 100 ms of 2 -> 10 kHz = 600 cycles = ~1200 zero crossings.
        val x = ChirpGenerator.linearChirp(fs, 2_000.0, 10_000.0, 0.1, 1.0, 0.0)
        assertEquals(1200.0, signChanges(x).toDouble(), 6.0)
    }

    @Test fun correlationPeaksAtInsertedDelay() {
        val h = ChirpGenerator.linearChirp(fs, 15_000.0, 20_000.0, 0.005, 1.0, 0.0005)
        val x = DoubleArray(2_000).also { for (i in h.indices) it[737 + i] += 0.1 * h[i] }
        val env = Signal.envelope(Signal.crossCorrelate(x, h))
        assertEquals(737, env.indices.maxBy { env[it] })
    }

    @Test fun envelopeOfSteadySineIsFlat() {
        val x = DoubleArray(1000) { 0.7 * sin(2 * PI * 3000 * it / fs) }
        val env = Signal.envelope(x)
        for (i in 100 until 900) assertEquals(0.7, env[i], 0.02)
    }

    @Test fun parabolicOffsetRecoversSubSamplePeak() {
        val y = DoubleArray(9) { 1.0 - (it - 4.3) * (it - 4.3) / 50 }
        assertEquals(0.3, Signal.parabolicOffset(y, 4), 1e-9)
    }

    @Test fun highPassKeepsBandAndRejectsSpeech() {
        fun gainDb(hz: Double): Double {
            val f = HighPassFilter(fs, 11_250.0)
            val out = DoubleArray(9_600) { f.process(sin(2 * PI * hz * it / fs)) }
            return 20 * log10(Signal.rms(out, 4_800) / Signal.rms(DoubleArray(4_800) { sin(2 * PI * hz * it / fs) }))
        }
        assertTrue("band should pass", gainDb(17_500.0) > -1.0)
        assertTrue("1 kHz should be cut", gainDb(1_000.0) < -60.0)
    }

    @Test fun rangingRoundTrips() {
        val c = Ranging.speedOfSound(20.0)
        assertEquals(343.42, c, 0.01)
        val tau = Ranging.delayForDistance(1.25, c, 0.02)
        assertEquals(1.25, Ranging.distanceFromDelay(tau, c, 0.02), 1e-12)
    }
}
