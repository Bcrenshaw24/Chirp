package com.example.chirp.sonar.calibration

import com.example.chirp.sonar.dsp.Fft
import com.example.chirp.sonar.dsp.Signal
import com.example.chirp.sonar.SonarConfig
import kotlin.math.log10
import kotlin.math.sqrt

data class ResponsePoint(val hz: Double, val db: Double)

/**
 * What "nothing in front of the phone" looks like. Profiles are raw matched-filter outputs
 * indexed by lag relative to the expected direct arrival (index [SonarConfig.PROFILE_PRE]).
 */
class Baseline(
    /** Mean profile: direct speaker-to-mic leakage + static room reflections. Subtracted later. */
    val template: DoubleArray,
    /** Per-lag RMS of the residual envelope across calibration frames (smoothed): the noise to beat. */
    val noiseLevel: DoubleArray,
    /** Envelope of the direct arrival in the template. */
    val directLevel: Double,
    /** Mean sub-sample position of the direct arrival relative to its expected sample. */
    val directOffset: Double,
    /** In-band ambient noise with the speaker silent, dB re full scale. */
    val ambientDbfs: Double,
    /** Direct-path magnitude response across the chirp band, dB re its maximum. */
    val responseDb: List<ResponsePoint>,
    val frames: Int,
) {
    /** Direct-path level vs. the median noise level: how far below leakage an echo can still be seen. */
    val dynamicRangeDb: Double get() = 20 * log10(directLevel / Signal.median(noiseLevel).coerceAtLeast(1e-300))
}

/**
 * Builds a [Baseline] from a run of chirp periods recorded with the area in front clear.
 *
 * Subtracting the averaged template removes everything static, including the direct
 * leakage (30-40 dB louder than a wall echo), so what remains is only what CHANGED:
 * an object that appeared or moved. Noise is measured per range bin because leakage
 * jitter makes the residual much noisier near the direct arrival than further out.
 */
class Calibrator(
    private val config: SonarConfig,
    private val chirp: DoubleArray,
    private val ambientDbfs: Double,
) {
    private val profiles = mutableListOf<DoubleArray>()
    private val offsets = mutableListOf<Double>()
    private var directWindowSum: DoubleArray? = null

    val count get() = profiles.size
    val isComplete get() = count >= config.calibrationFrames

    /** [directWindow]: band-passed samples from [DIRECT_WINDOW_PRE] before the direct arrival. */
    fun add(profile: DoubleArray, directOffset: Double, directWindow: DoubleArray) {
        require(profile.size == config.profileLength)
        profiles += profile
        offsets += directOffset
        val sum = directWindowSum ?: DoubleArray(directWindow.size).also { directWindowSum = it }
        for (i in sum.indices) sum[i] += directWindow[i]
    }

    fun finish(): Baseline {
        check(count >= 2) { "not enough calibration frames" }
        val n = profiles.size
        val len = config.profileLength
        val template = DoubleArray(len)
        for (p in profiles) for (i in 0 until len) template[i] += p[i] / n

        val sumSq = DoubleArray(len)
        for (p in profiles) {
            val env = Signal.envelope(DoubleArray(len) { p[it] - template[it] })
            for (i in 0 until len) sumSq[i] += env[i] * env[i]
        }
        // n/(n-1): each frame contributed to the mean it is compared against.
        val rms = DoubleArray(len) { sqrt(sumSq[it] / n * n / (n - 1)) }
        // Smooth over ~4 compressed-pulse widths so a few lucky quiet bins don't set a hair trigger.
        val width = maxOf(3, (4.0 * config.sampleRateHz / config.bandwidthHz).toInt())
        val directLevel = Signal.envelope(template)[SonarConfig.PROFILE_PRE]
        val floor = directLevel * 1e-4 // -80 dB guard against an all-digital-zero input
        val noise = Signal.movingAverage(rms, width).map { maxOf(it, floor) }.toDoubleArray()

        return Baseline(
            template = template,
            noiseLevel = noise,
            directLevel = directLevel,
            directOffset = offsets.average(),
            ambientDbfs = ambientDbfs,
            responseDb = directWindowSum?.let { response(it, n) } ?: emptyList(),
            frames = n,
        )
    }

    /**
     * Speaker -> mic magnitude response across the band: |FFT(received direct arrival)| /
     * |FFT(transmitted chirp)|. Includes speaker, mic, the S23+'s internal processing and
     * the first few cm of reflections. Tells you where in the band the hardware is weak.
     */
    private fun response(sum: DoubleArray, frames: Int, bands: Int = 16): List<ResponsePoint> {
        val size = Fft.nextPowerOfTwo(2 * maxOf(sum.size, chirp.size))
        val yr = DoubleArray(size).also { for (i in sum.indices) it[i] = sum[i] / frames }
        val yi = DoubleArray(size)
        val xr = chirp.copyOf(size)
        val xi = DoubleArray(size)
        Fft.transform(yr, yi); Fft.transform(xr, xi)
        val lo = minOf(config.startHz, config.endHz)
        val hi = maxOf(config.startHz, config.endHz)
        val step = (hi - lo) / bands
        val points = (0 until bands).mapNotNull { b ->
            val f0 = lo + b * step
            var py = 0.0; var px = 0.0
            for (k in 0..size / 2) {
                val hz = k.toDouble() * config.sampleRateHz / size
                if (hz >= f0 && hz < f0 + step) {
                    py += yr[k] * yr[k] + yi[k] * yi[k]
                    px += xr[k] * xr[k] + xi[k] * xi[k]
                }
            }
            if (px <= 0 || py <= 0) null else ResponsePoint(f0 + step / 2, 10 * log10(py / px))
        }
        val max = points.maxOfOrNull { it.db } ?: return points
        return points.map { it.copy(db = it.db - max) }
    }

    companion object {
        /** Samples kept before the direct arrival in the response window. */
        const val DIRECT_WINDOW_PRE = 16
        /** Samples kept after the chirp ends in the response window (~17 cm of path). */
        const val DIRECT_WINDOW_POST = 48
    }
}
