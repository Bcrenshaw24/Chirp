package com.example.chirp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Pure-JVM tests for [DspEngine] using synthetic data only (no device, mic or speaker).
 * The "recording" is built by placing the transmitted chirp at a known offset in an
 * array of zeros, so the expected lag and distance are known exactly.
 */
class DspEngineTest {

    private val fs = 48000
    private val f0 = 15000.0
    private val f1 = 20000.0
    private val duration = 0.01 // 480 samples

    private fun chirpDoubles(): DoubleArray =
        DspEngine.pcm16ToDoubles(DspEngine.generateChirp(fs, f0, f1, duration))

    /** Returns a recording of [length] zeros with [signal] * [gain] added starting at [offset]. */
    private fun place(recording: DoubleArray, signal: DoubleArray, offset: Int, gain: Double) {
        for (i in signal.indices) recording[offset + i] += gain * signal[i]
    }

    private fun signChanges(x: DoubleArray, from: Int, to: Int): Int {
        var count = 0
        for (i in from + 1 until to) if ((x[i - 1] < 0) != (x[i] < 0)) count++
        return count
    }

    // ---------- Step 1: chirp generation ----------

    @Test
    fun chirp_hasExpectedByteLength() {
        val bytes = DspEngine.generateChirp(fs, f0, f1, duration)
        assertEquals(2 * 480, bytes.size)
    }

    @Test
    fun chirp_respectsAmplitudeAndFadesAtEdges() {
        val x = DspEngine.pcm16ToDoubles(DspEngine.generateChirp(fs, f0, f1, duration, amplitude = 0.5))
        assertTrue("peak must not exceed amplitude", x.maxOf { abs(it) } <= 0.5 + 1e-3)
        assertTrue("peak should reach near amplitude", x.maxOf { abs(it) } > 0.45)
        assertTrue("first sample should be ~0", abs(x.first()) < 1e-3)
        assertTrue("last sample should be ~0", abs(x.last()) < 1e-3)
    }

    @Test
    fun chirp_frequencyIncreasesLinearly() {
        // 2 kHz -> 10 kHz over 100 ms, no fade. Zero-crossings = 2 * f * time.
        val x = DspEngine.pcm16ToDoubles(
            DspEngine.generateChirp(fs, 2000.0, 10000.0, 0.1, amplitude = 1.0, fadeSec = 0.0)
        )
        val window = 480 // 10 ms
        // Start window: f 2000 -> 2800 Hz, avg 2400 -> ~48 crossings.
        val startCrossings = signChanges(x, 0, window)
        // End window: f 9200 -> 10000 Hz, avg 9600 -> ~192 crossings.
        val endCrossings = signChanges(x, x.size - window, x.size)
        assertTrue("start crossings $startCrossings", startCrossings in 42..54)
        assertTrue("end crossings $endCrossings", endCrossings in 180..204)
    }

    @Test
    fun chirp_totalCyclesMatchMeanFrequency() {
        // Cycles = T * (f0 + f1) / 2 => crossings ~ 2 * that. 100 ms, 2k->10k => 600 cycles.
        val x = DspEngine.pcm16ToDoubles(
            DspEngine.generateChirp(fs, 2000.0, 10000.0, 0.1, amplitude = 1.0, fadeSec = 0.0)
        )
        val crossings = signChanges(x, 0, x.size)
        assertTrue("crossings $crossings", abs(crossings - 1200) <= 6)
    }

    @Test
    fun chirp_rejectsInvalidArguments() {
        expectIllegalArgument { DspEngine.generateChirp(fs, f0, 24000.0, duration) } // = Nyquist
        expectIllegalArgument { DspEngine.generateChirp(fs, f0, f1, 0.0) }
        expectIllegalArgument { DspEngine.generateChirp(fs, f0, f1, duration, amplitude = 1.5) }
        expectIllegalArgument { DspEngine.generateChirp(fs, f0, f1, duration, fadeSec = 0.01) }
    }

    @Test
    fun pcmDecode_roundTripsKnownBytes() {
        // 0x0000 = 0, 0x7FFF = max, 0x8000 = min, 0xFFFF = -1 (little-endian)
        val bytes = byteArrayOf(0, 0, 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val x = DspEngine.pcm16ToDoubles(bytes)
        assertEquals(0.0, x[0], 0.0)
        assertEquals(32767 / 32768.0, x[1], 1e-12)
        assertEquals(-1.0, x[2], 1e-12)
        assertEquals(-1 / 32768.0, x[3], 1e-12)
    }

    // ---------- Step 2: cross-correlation ----------

    @Test
    fun crossCorrelate_matchesHandComputedExample() {
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val h = doubleArrayOf(1.0, -1.0)
        // r[lag] = x[lag]*1 + x[lag+1]*(-1)
        val r = DspEngine.crossCorrelate(x, h)
        assertEquals(3, r.size)
        assertEquals(-1.0, r[0], 1e-12)
        assertEquals(-1.0, r[1], 1e-12)
        assertEquals(-1.0, r[2], 1e-12)
    }

    @Test
    fun crossCorrelate_peakAtInsertedOffsetWithEnergyTimesGain() {
        val h = chirpDoubles()
        val energy = h.sumOf { it * it }
        val x = DoubleArray(4800)
        place(x, h, offset = 1200, gain = 0.3)

        val r = DspEngine.crossCorrelate(x, h)
        assertEquals(x.size - h.size + 1, r.size)
        val argMax = r.indices.maxByOrNull { abs(r[it]) }!!
        assertEquals(1200, argMax)
        assertEquals(0.3 * energy, r[1200], 1e-9)
    }

    @Test
    fun crossCorrelate_rejectsBadSizes() {
        expectIllegalArgument { DspEngine.crossCorrelate(DoubleArray(5), DoubleArray(0)) }
        expectIllegalArgument { DspEngine.crossCorrelate(DoubleArray(3), DoubleArray(5)) }
    }

    // ---------- Step 3: peak detection and distance ----------

    @Test
    fun findPeak_exactLagForCleanSignal() {
        val h = chirpDoubles()
        val x = DoubleArray(4800)
        place(x, h, 1200, 0.3)
        val peak = DspEngine.findPeak(DspEngine.crossCorrelate(x, h))
        assertNotNull(peak)
        assertEquals(1200.0, peak!!.lag, 1e-6) // autocorrelation is symmetric => delta ~ 0
    }

    @Test
    fun findPeak_survivesNoise() {
        val h = chirpDoubles()
        val x = DoubleArray(4800)
        place(x, h, 2000, 0.2)
        val rng = Random(42) // fixed seed => deterministic
        for (i in x.indices) x[i] += 0.05 * (rng.nextDouble() * 2 - 1)
        val peak = DspEngine.findPeak(DspEngine.crossCorrelate(x, h))
        assertNotNull(peak)
        assertEquals(2000.0, peak!!.lag, 1.0)
    }

    @Test
    fun findPeak_minLagSkipsDirectPath() {
        val h = chirpDoubles()
        val x = DoubleArray(4800)
        place(x, h, 0, 1.0)    // direct speaker->mic, strongest
        place(x, h, 600, 0.3)  // echo
        val r = DspEngine.crossCorrelate(x, h)
        assertEquals(0.0, DspEngine.findPeak(r)!!.lag, 1e-6)
        assertEquals(600.0, DspEngine.findPeak(r, minLag = 100)!!.lag, 1e-6)
    }

    @Test
    fun findPeak_returnsNullWhenEmptyOrSilent() {
        assertNull(DspEngine.findPeak(DoubleArray(10), minLag = 10))
        assertNull(DspEngine.findPeak(DoubleArray(10))) // all zero
    }

    @Test
    fun findPeak_parabolicInterpolationRefinesSubSample() {
        // |r| samples of a parabola with vertex at 4.25: y = 10 - (i - 4.25)^2
        val r = DoubleArray(9) { 10.0 - (it - 4.25) * (it - 4.25) }
        assertEquals(4.25, DspEngine.findPeak(r)!!.lag, 1e-9)
    }

    @Test
    fun speedOfSound_at20C() {
        assertEquals(343.42, DspEngine.speedOfSound(20.0), 0.01)
        assertEquals(331.3, DspEngine.speedOfSound(0.0), 1e-9)
    }

    @Test
    fun lagToDistance_roundTripMath() {
        val c = 343.0
        // 280 samples at 48 kHz = 5.8333 ms round trip => 343 * 0.0058333 / 2 = 1.000417 m
        assertEquals(1.000417,DspEngine.lagToDistanceMeters(280.0, fs, 0.0, c), 1e-5)
        // Reference lag subtracts fixed latency.
        assertEquals(
            DspEngine.lagToDistanceMeters(280.0, fs, 0.0, c),
            DspEngine.lagToDistanceMeters(330.0, fs, 50.0, c),
            1e-12
        )
        // Echo before reference => negative distance.
        assertTrue(DspEngine.lagToDistanceMeters(10.0, fs, 50.0, c) < 0)
    }

    @Test
    fun endToEnd_knownDistanceIsRecovered() {
        val c = DspEngine.speedOfSound(20.0)
        // Far enough that the echo (>= 480 samples after direct) doesn't overlap the direct chirp.
        val trueDistance = 3.0 // metres
        val directLag = 100    // simulated fixed pipeline latency, samples
        val echoLag = directLag + (2 * trueDistance / c * fs).toInt()

        val h = chirpDoubles()
        val x = DoubleArray(9600)
        place(x, h, directLag, 1.0)
        place(x, h, echoLag, 0.25)

        val r = DspEngine.crossCorrelate(x, h)
        val direct = DspEngine.findPeak(r)!!
        val echo = DspEngine.findPeak(r, minLag = directLag + h.size)!!
        val d = DspEngine.lagToDistanceMeters(echo.lag, fs, direct.lag, c)

        // Error bound: one sample of range, c / (2 fs) ~ 3.6 mm (plus int truncation above).
        assertEquals(trueDistance, d, 0.005)
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
