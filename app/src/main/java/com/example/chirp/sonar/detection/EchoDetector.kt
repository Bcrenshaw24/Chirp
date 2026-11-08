package com.example.chirp.sonar.detection

import com.example.chirp.sonar.calibration.Baseline
import com.example.chirp.sonar.dsp.FractionalDelay
import com.example.chirp.sonar.dsp.Signal
import com.example.chirp.sonar.ranging.RangeCorrection
import com.example.chirp.sonar.SonarConfig
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

data class Echo(
    /** Delay after the direct arrival, fractional samples. */
    val lagSamples: Double,
    /** Corrected distance (see [RangeCorrection]); what tracking and alerts use. */
    val distanceM: Double,
    /** Envelope over the calibrated noise level at this range. */
    val snrDb: Double,
    /** Uncorrected distance from the delay alone; what accuracy validation measures. */
    val rawDistanceM: Double = distanceM,
)

class DetectionResult(
    /** Envelope of (profile - gain * template): what changed since calibration. */
    val residualEnvelope: DoubleArray,
    val threshold: DoubleArray,
    /** Accepted echoes, nearest first. */
    val echoes: List<Echo>,
    /** Leakage gain fitted this frame; drifting far from 1 means the setup changed (grip, volume). */
    val leakageGain: Double,
    /** Fractional delay applied to the template to follow the direct arrival's drift, samples. */
    val templateShift: Double,
) {
    val nearest: Echo? get() = echoes.firstOrNull()
}

/**
 * Finds reflections that were not there at calibration.
 *
 *  1. Track how far the leakage has drifted since calibration (fractional samples) and its
 *     gain, by least-squares fitting the template to this frame over the direct-arrival region
 *     only. At 17 kHz a 0.4-sample drift is ~50 degrees of phase, which would otherwise leave
 *     most of the leakage behind. The fit uses the leakage waveform itself, which sits 50+ dB
 *     above the noise, so it is far steadier than the envelope-peak position. (Using that
 *     peak position injected frame-to-frame jitter and phantom near-range echoes.)
 *  2. Subtract the shifted, scaled template and take the envelope of what is left.
 *  3. Accept local maxima inside [minRange, maxRange] that exceed K x the calibrated noise
 *     at that range (constant-false-alarm style: the bar is high near the leakage, low far out).
 *  4. Keep the strongest peak per compressed-pulse width, report nearest first.
 *
 * Static room echoes in the template are shifted by the same amount. That is right when the
 * drift comes from the speaker/clock (it moves every path equally), which is the common case.
 *
 * Handheld ([adaptive]): the subtracted background starts as the calibration template and then
 * follows the profile with a ~[ADAPT_TIME_CONSTANT_PERIODS]-period time constant. Whatever stays
 * put relative to the phone (hand, body, a new grip, the room while standing still) fades into
 * it; whatever moves relative to the phone (walking toward a wall, a person approaching) changes
 * faster than that and stays visible. Bins around the confirmed track ([detect]'s protectLag)
 * are not updated, so an object you stop in front of isn't learned away, unless the track is a
 * ghost: something that was in the background and has since gone, which must fade.
 */
class EchoDetector(private val config: SonarConfig, private val baseline: Baseline) {
    private val pre = SonarConfig.PROFILE_PRE
    private val threshold = DoubleArray(config.profileLength) { config.detectionThreshold * baseline.noiseLevel[it] }
    /** About one compressed pulse width (1/B) in samples. */
    private val pulseWidth = maxOf(2, (config.sampleRateHz / config.bandwidthHz).toInt())

    private val delayedTemplate = FractionalDelay(baseline.template)
    /** Leakage fit region: ~2 pulse widths either side of the direct arrival (about +-3 cm). */
    private val fitRange = maxOf(0, pre - 2 * pulseWidth)..minOf(config.profileLength - 1, pre + 2 * pulseWidth)
    /** Applied to every echo distance. Settable from another thread (e.g. after validation). */
    @Volatile var correction = RangeCorrection()

    /** Handheld mode: adapt the background (see class doc). Settable from another thread. */
    @Volatile var adaptive = false

    /** The adaptive background; equals the calibration template until [adaptive] updates it. */
    private val background = baseline.template.copyOf()

    /** Tracked template delay, samples. Carried between frames; starts at 0 at calibration. */
    private var shift = 0.0

    /** Lag (samples after the direct arrival) at which an echo would give corrected distance [distanceM]. */
    fun lagForDistance(distanceM: Double): Double =
        config.lagForDistance((distanceM - correction.offsetM) / correction.scale)

    /**
     * Stateful: call once per period, in order.
     * [protectLag]: lag of the confirmed track, whose bins the adaptive background must not absorb.
     */
    fun detect(profile: DoubleArray, directOffset: Double, protectLag: Double? = null): DetectionResult {
        require(profile.size == config.profileLength)
        val adapting = adaptive
        val source = if (adapting) FractionalDelay(background) else delayedTemplate

        // Linearise around the current shift: profile ~= a * T(s) + b * dT/ds, so gain = a, step = b / a.
        val t0 = source.shift(shift)
        val tPlus = source.shift(shift + H)
        val tMinus = source.shift(shift - H)
        var stt = 0.0; var std = 0.0; var sdd = 0.0; var spt = 0.0; var spd = 0.0
        for (i in fitRange) {
            val d = (tPlus[i] - tMinus[i]) / (2 * H)
            stt += t0[i] * t0[i]; std += t0[i] * d; sdd += d * d
            spt += profile[i] * t0[i]; spd += profile[i] * d
        }
        val det = stt * sdd - std * std
        if (det > 0) {
            val a = (spt * sdd - spd * std) / det
            val b = (stt * spd - std * spt) / det
            if (a > 0) shift = (shift + (b / a).coerceIn(-MAX_STEP, MAX_STEP))
                .coerceIn(-SonarConfig.DIRECT_SEARCH.toDouble(), SonarConfig.DIRECT_SEARCH.toDouble())
        }
        val t = source.shift(shift)

        var num = 0.0; var den = 0.0
        for (i in fitRange) { num += profile[i] * t[i]; den += t[i] * t[i] }
        val gain = if (den > 0) (num / den).coerceIn(0.5, 2.0) else 1.0

        val env = Signal.envelope(DoubleArray(profile.size) { profile[it] - gain * t[it] })

        val lo = pre + config.minLag
        val hi = minOf(pre + config.maxLag, env.lastIndex - 1)
        val peaks = (maxOf(lo, 1)..hi).filter { i ->
            env[i] > threshold[i] && env[i] >= env[i - 1] && env[i] >= env[i + 1]
        }.sortedByDescending { env[it] }

        val kept = mutableListOf<Int>()
        for (i in peaks) if (kept.none { abs(it - i) < 2 * pulseWidth }) kept += i

        val echoes = kept.take(MAX_ECHOES).map { i ->
            val lag = (i - pre) + Signal.parabolicOffset(env, i) - directOffset
            val raw = config.distanceForLag(lag)
            Echo(lag, correction.apply(raw), 20 * log10(env[i] / baseline.noiseLevel[i]), raw)
        }.sortedBy { it.lagSamples }

        if (adapting) {
            // Protect the tracked object only if it ADDED energy there. A "ghost" (something in the
            // background that has since gone) is missing energy: it must fade, not be locked in.
            val center = protectLag?.let { pre + it + directOffset }?.takeIf { c ->
                val k = c.roundToInt().coerceIn(0, profile.lastIndex)
                Signal.envelope(profile)[k] > Signal.envelope(DoubleArray(t.size) { gain * t[it] })[k]
            }
            val halfWidth = PROTECT_PULSE_WIDTHS * pulseWidth
            for (i in background.indices) {
                if (center == null || abs(i - center) > halfWidth) {
                    background[i] += (profile[i] - background[i]) / ADAPT_TIME_CONSTANT_PERIODS
                }
            }
        }

        return DetectionResult(env, threshold, echoes, gain, shift)
    }

    companion object {
        const val MAX_ECHOES = 5
        /**
         * Background time constant in chirp periods: 15 = 1.5 s at 10 Hz. A strong new static reflector
         * fades in ~4 s; anything moving faster than ~2 cm/s crosses a range bin before it is absorbed.
         */
        const val ADAPT_TIME_CONSTANT_PERIODS = 15.0
        /** Bins within this many pulse widths (~3.4 cm each) of the tracked object are not adapted. */
        private const val PROTECT_PULSE_WIDTHS = 4
        /** Finite-difference step (samples) for the template's derivative with respect to delay. */
        private const val H = 0.25
        /** Largest shift change accepted per period; real drift is ~0.003 samples/s. */
        private const val MAX_STEP = 0.5
    }
}
