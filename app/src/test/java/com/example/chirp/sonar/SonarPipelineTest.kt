package com.example.chirp.sonar

import com.example.chirp.sonar.audio.ChirpGenerator
import com.example.chirp.sonar.tracking.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.roundToInt

/**
 * End-to-end on synthetic audio: a looping chirp train passes through a fake speaker/mic
 * response, arrives as strong leakage + a static wall + an optional target, plus noise.
 * Nothing Android is involved; this pins down the DSP, alignment and tracking logic.
 */
class SonarPipelineTest {
    private val config = SonarConfig(calibrationFrames = 10)
    private val chirp = ChirpGenerator.linearChirp(
        config.sampleRateHz, config.startHz, config.endHz, config.chirpMs / 1000, config.amplitude, config.fadeMs / 1000,
    )
    private val period = config.periodFrames

    /** Speaker + mic colouring, applied to every path alike (as in reality). */
    private val system = mapOf(0 to 1.0, 3 to -0.3)
    private val leakageDelay = 30
    /** Output latency: the first chirp starts well after the speaker is told to play. */
    private val txStart = config.ambientFrames + 2_345

    private fun tx(n: Int): Double {
        if (n < txStart) return 0.0
        val k = (n - txStart) % period
        return if (k < chirp.size) chirp[k] else 0.0
    }

    /** [target] maps a period number to (distance m, amplitude) or null. */
    private fun simulate(periods: Int, noise: Double = 1e-3, target: (Int) -> Pair<Double, Double>?): ShortArray {
        val rng = Random(7)
        val total = txStart + periods * period
        val wallLag = config.lagForDistance(2.4).roundToInt()
        return ShortArray(total) { n ->
            val q = Math.floorDiv(n - txStart, period)
            var paths = listOf(leakageDelay to 1.0, leakageDelay + wallLag to 0.02)
            target(q)?.let { (d, a) -> paths = paths + ((leakageDelay + config.lagForDistance(d).roundToInt()) to a) }
            var y = noise * rng.nextGaussian()
            for ((delay, amp) in paths) for ((tap, g) in system) y += amp * g * tx(n - delay - tap)
            ChirpGenerator.toPcm16(y)
        }
    }

    private fun run(audio: ShortArray, adaptive: Boolean = false): List<SonarFrame> {
        val frames = mutableListOf<SonarFrame>()
        val p = SonarProcessor(config).apply { adaptiveBackground = adaptive }
        p.onFrame = { frames += it }
        var i = 0
        while (i < audio.size) {
            val n = minOf(480, audio.size - i)
            p.push(audio.copyOfRange(i, i + n), n)
            i += n
        }
        return frames
    }

    @Test fun clearSceneProducesNoDetections() {
        val frames = run(simulate(40) { null })
        val running = frames.filter { it.state == SonarState.RUNNING }
        assertTrue("should reach RUNNING, got ${frames.map { it.state }.distinct()}", running.size >= 20)
        val falseAlarms = running.count { it.detection!!.echoes.isNotEmpty() }
        assertEquals("static wall and leakage must be subtracted out", 0, falseAlarms)
    }

    @Test fun targetAppearingAfterCalibrationIsRangedAccurately() {
        // Clear while acquiring + calibrating, then an object at 0.8 m, -40 dB re leakage.
        // (At -50 dB with this noise the matched-filter SNR is ~19 dB, only ~5 dB over the
        // 14 dB threshold: detected ~40% of the time. That is the real SNR budget at 5 ms.)
        val frames = run(simulate(45) { q -> if (q >= 18) 0.8 to 0.01 else null })
        val withTarget = frames.filter { it.state == SonarState.RUNNING && it.sequence >= 20 }
        assertTrue(withTarget.size >= 15)
        val hits = withTarget.mapNotNull { it.detection!!.nearest }
        assertTrue("detected in ${hits.size}/${withTarget.size}", hits.size >= withTarget.size * 0.9)
        hits.forEach { assertEquals(0.8, it.distanceM, 0.01) }
    }

    @Test fun approachingTargetIsTrackedAsApproaching() {
        // 1.5 m closing at 0.5 m/s (5 cm per 100 ms period).
        // Ends at 0.45 m after 40 periods.
        val frames = run(simulate(40) { q -> if (q >= 18) (1.5 - 0.05 * (q - 18)) to 0.005 else null })
        val last = frames.last { it.track != null }.track!!
        assertTrue("confirmed", last.confirmed)
        assertEquals(Trend.APPROACHING, last.trend)
        assertEquals(-0.5, last.velocityMps, 0.1)
        assertTrue("time to contact reported", last.timeToContactSec != null)
    }

    // --- Handheld mode (adaptive background) ---

    /** An object present during calibration that is then removed: leaves a "ghost" in the background. */
    private val removedObject: (Int) -> Pair<Double, Double>? = { q -> if (q < 18) 0.8 to 0.01 else null }
    /** A real object that appears after calibration and then stays put. */
    private val newStaticObject: (Int) -> Pair<Double, Double>? = { q -> if (q >= 18) 0.8 to 0.01 else null }

    private fun running(frames: List<SonarFrame>) = frames.filter { it.state == SonarState.RUNNING }

    @Test fun fixedBaselineKeepsReportingAGhost() {
        val last = running(run(simulate(80, target = removedObject))).takeLast(10)
        assertTrue(last.all { f -> f.detection!!.echoes.any { kotlin.math.abs(it.distanceM - 0.8) < 0.05 } })
    }

    @Test fun handheldLetsAGhostFade() {
        val frames = running(run(simulate(80, target = removedObject), adaptive = true))
        assertTrue("ghost seen right after removal", frames.first { it.sequence == 19L }.detection!!.nearest != null)
        val last = frames.takeLast(10)
        assertTrue("ghost gone ~6 s later: ${last.map { it.detection!!.echoes }}", last.all { it.detection!!.echoes.isEmpty() })
    }

    @Test fun handheldKeepsARealObjectYouStopInFrontOf() {
        // 6 s standing still in front of it: without protection it would have faded after ~4 s.
        val last = running(run(simulate(80, target = newStaticObject), adaptive = true)).takeLast(10)
        last.forEach { f ->
            assertEquals(0.8, f.detection!!.nearest!!.distanceM, 0.02)
            assertTrue(f.track!!.confirmed)
        }
    }

    @Test fun handheldStillTracksApproachingObject() {
        val frames = run(simulate(40) { q -> if (q >= 18) (1.5 - 0.05 * (q - 18)) to 0.005 else null }, adaptive = true)
        val last = frames.last { it.track != null }.track!!
        assertEquals(Trend.APPROACHING, last.trend)
        assertEquals(-0.5, last.velocityMps, 0.1)
    }

    @Test fun silenceNeverLocksOn() {
        val p = SonarProcessor(config)
        val states = mutableListOf<SonarState>()
        p.onStatus = { s, _ -> states += s }
        val noise = Random(1)
        repeat(200) { p.push(ShortArray(480) { (noise.nextGaussian() * 30).toInt().toShort() }) }
        assertEquals(SonarState.ACQUIRING, p.state)
        assertTrue("user is told why", states.isNotEmpty())
    }
}
