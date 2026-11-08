package com.example.chirp.sonar

import com.example.chirp.sonar.ranging.Ranging
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Everything that defines the transmitted signal and the processing window.
 * Immutable: changing it means restarting the engine (and recalibrating).
 */
data class SonarConfig(
    val sampleRateHz: Int = 48_000,
    val startHz: Double = 15_000.0,
    val endHz: Double = 20_000.0,
    val chirpMs: Double = 5.0,
    val fadeMs: Double = 0.5,
    /** Digital peak level, 0..1. Actual loudness also depends on the system media volume. */
    val amplitude: Double = 0.3,
    /** Chirp repetition period: sets the update rate (10 Hz) and max unambiguous range c*T/2 (17 m). */
    val periodMs: Double = 100.0,
    /** Echoes closer than this are inside the direct-path blind zone and are not searched. */
    val minRangeM: Double = 0.15,
    val maxRangeM: Double = 3.0,
    val temperatureC: Double = 20.0,
    /** Speaker-to-mic acoustic path. Both openings are on the S23+'s bottom edge. Rough guess. */
    val speakerMicSpacingM: Double = 0.02,
    /** Speaker stays silent for this long at start so ambient noise can be measured. */
    val ambientMs: Double = 300.0,
    /** Chirp periods averaged into the baseline (3 s at 10 Hz). Keep the area in front clear. */
    val calibrationFrames: Int = 30,
    /** Echo must exceed this many times the calibrated per-range noise level (5 = 14 dB). */
    val detectionThreshold: Double = 5.0,
) {
    val chirpFrames get() = (sampleRateHz * chirpMs / 1000).roundToInt()
    val periodFrames get() = (sampleRateHz * periodMs / 1000).roundToInt()
    val ambientFrames get() = (sampleRateHz * ambientMs / 1000).roundToInt()
    val speedOfSound get() = Ranging.speedOfSound(temperatureC)
    val highPassCutoffHz get() = 0.75 * minOf(startHz, endHz)
    val bandwidthHz get() = kotlin.math.abs(endHz - startHz)

    /** Lag (samples after the direct arrival) of an echo from [distanceM]. */
    fun lagForDistance(distanceM: Double): Double =
        Ranging.delayForDistance(distanceM, speedOfSound, speakerMicSpacingM) * sampleRateHz

    fun distanceForLag(lagSamples: Double): Double =
        Ranging.distanceFromDelay(lagSamples / sampleRateHz, speedOfSound, speakerMicSpacingM)

    val minLag get() = maxOf(1, ceil(lagForDistance(minRangeM)).toInt())
    val maxLag get() = ceil(lagForDistance(maxRangeM)).toInt()

    /** A profile holds lags -[PROFILE_PRE] .. +[maxLag] relative to the direct arrival. */
    val profileLength get() = PROFILE_PRE + maxLag + 1

    init {
        require(maxOf(startHz, endHz) < sampleRateHz / 2.0) { "band must be below Nyquist" }
        require(bandwidthHz >= 1000) { "need at least 1 kHz of sweep for usable range resolution" }
        require(minRangeM in 0.0..<maxRangeM) { "invalid range window" }
        require(calibrationFrames >= 5) { "need at least 5 calibration frames" }
        // Each period must hold the whole echo window plus the chirp, or echoes alias into the next one.
        require(PROFILE_PRE + 2 * DIRECT_SEARCH + maxLag + chirpFrames < periodFrames) {
            "period too short for maxRangeM; lengthen periodMs or shorten the range"
        }
    }

    companion object {
        /** Lags kept before the direct arrival (for display and the leakage gain fit). */
        const val PROFILE_PRE = 32
        /** How far (samples) the direct arrival may move between periods before re-acquiring. */
        const val DIRECT_SEARCH = 8

        val INAUDIBLE = SonarConfig()
        /** Fallback if the speaker's high band turns out too weak. Clearly audible. */
        val AUDIBLE = SonarConfig(startHz = 8_000.0, endHz = 14_000.0, amplitude = 0.15)
    }
}
