package com.example.chirp.sonar.detection

import com.example.chirp.sonar.audio.ChirpGenerator
import com.example.chirp.sonar.calibration.Baseline
import com.example.chirp.sonar.dsp.FractionalDelay
import com.example.chirp.sonar.dsp.Signal
import com.example.chirp.sonar.SonarConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class DriftCompensationTest {
    private val config = SonarConfig()
    private val pre = SonarConfig.PROFILE_PRE
    private val chirp = ChirpGenerator.linearChirp(config.sampleRateHz, config.startHz, config.endHz,
        config.chirpMs / 1000, config.amplitude, config.fadeMs / 1000)
    private val len = config.profileLength
    /** The leakage's matched-filter output, direct arrival at index pre. */
    private val template = Signal.crossCorrelate(DoubleArray(len + chirp.size).also { chirp.copyInto(it, pre) }, chirp).copyOf(len)
    private val direct = Signal.envelope(template)[pre]

    /** Noise level matches the jitter test's added noise: white noise's envelope RMS is sqrt(2) sigma. */
    private fun detector() =
        EchoDetector(config, Baseline(template, DoubleArray(len) { direct * NOISE * 1.414 }, direct, 0.0, -90.0, emptyList(), 30))

    private companion object { const val NOISE = 3e-4 }

    private fun leftover(r: DetectionResult) = r.residualEnvelope.maxOrNull()!! / direct

    @Test fun fractionalDelayMatchesAnalyticSine() {
        val w = 2 * PI * 17_000 / 48_000
        val x = DoubleArray(1024) { sin(w * it) }
        val y = FractionalDelay(x).shift(0.37)
        // Away from the edges (where the zero padding cuts the sine off) it is exact.
        for (n in 200 until 800) assertEquals(sin(w * (n - 0.37)), y[n], 2e-3)
    }

    /** The S23+ showed -0.39 samples of drift after two minutes. */
    @Test fun driftedLeakageIsTrackedAndCancelled() {
        val profile = FractionalDelay(template).shift(-0.39)
        val naive = Signal.envelope(DoubleArray(len) { profile[it] - template[it] }).max() / direct
        assertTrue("without compensation most leakage is left: $naive", naive > 0.3)

        val d = detector()
        var r = d.detect(profile, 0.0)
        repeat(4) { r = d.detect(profile, 0.0) }
        assertEquals(-0.39, r.templateShift, 0.005)
        assertTrue("compensated cancels to < -50 dB: ${leftover(r)}", leftover(r) < 3e-3)
    }

    /**
     * Regression: the first version took the shift from the envelope-peak position, whose
     * frame-to-frame jitter re-misaligned the template and caused phantom near-range echoes.
     * The shift must come from the leakage waveform, independent of that jittery estimate.
     */
    @Test fun envelopePeakJitterDoesNotLeakThrough() {
        val rng = Random(3)
        val drift = 0.12
        val clean = FractionalDelay(template).shift(drift)
        val d = detector()
        repeat(30) {
            val noisy = DoubleArray(len) { clean[it] + direct * NOISE * rng.nextGaussian() }
            val r = d.detect(noisy, directOffset = drift + 0.05 * rng.nextGaussian())
            if (it >= 5) {
                assertEquals(drift, r.templateShift, 0.01)
                assertTrue("no phantom echoes, got ${r.echoes}", r.echoes.isEmpty())
            }
        }
    }
}
