package com.example.chirp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.*

/** Diagnostic settings only; gain stays fixed regardless of the selected band or duration. */
enum class ChirpBand(val label: String, val startHz: Double, val endHz: Double) {
    AUDIBLE("8–14 kHz", 8000.0, 14000.0),
    HIGH("15–20 kHz", 15000.0, 20000.0),
}

data class AcousticConfig(
    val band: ChirpBand = ChirpBand.AUDIBLE,
    val durationMs: Int = 10,
    val baseline: Boolean = false,
) {
    init { require(durationMs in listOf(10, 30, 50)) }
}

data class CorrelationPeak(val lagSamples: Int, val signedNcc: Double, val timeMs: Double)

data class AcousticReport(
    val bandRms: Double,
    val preRollBandRms: Double,
    val laterBandRms: Double,
    val peakWindowBandRms: Double?,
    val peaks: List<CorrelationPeak>,
) {
    fun text(): String = buildString {
        appendLine("bandRms=$bandRms preRollBandRms=$preRollBandRms laterBandRms=$laterBandRms peakWindowBandRms=$peakWindowBandRms")
        appendLine("band RMS: rectangular-window FFT bin energy in selected band, full-scale units; spectral leakage is possible")
        appendLine("NCC: raw, unfiltered, uncentered dot product / sqrt(reference energy * sliding recording energy)")
        appendLine("Top 3 absolute NCC candidates, separated by at least half a reference duration; integer lags, milliseconds from first recorded sample")
        peaks.forEachIndexed { i, peak -> appendLine("peak${i + 1}=$peak") }
        if (peaks.isEmpty()) appendLine("No nonzero correlation candidates")
        append("Candidate peaks are not confirmed detections, echoes, or distances. Compare repeated active and baseline runs.")
    }
}

/** Extra diagnostics around Dev 1's unchanged correlation, never a ranging algorithm. */
internal object AcousticDiagnostics {
    suspend fun analyze(recorded: ByteArray, referencePcm: ByteArray, rate: Int, config: AcousticConfig): AcousticReport {
        val received = DspEngine.pcm16ToDoubles(recorded)
        val reference = DspEngine.pcm16ToDoubles(referencePcm)
        val normalized = normalizedCorrelation(received, reference)
        val selected = mutableListOf<CorrelationPeak>()
        val suppressed = BooleanArray(normalized.size)
        val separation = maxOf(1, reference.size / 2)
        repeat(3) {
            val lag = normalized.indices.filter { !suppressed[it] }
                .maxByOrNull { abs(normalized[it]) } ?: return@repeat
            if (abs(normalized[lag]) <= 1e-12) return@repeat
            selected += CorrelationPeak(lag, normalized[lag], lag * 1000.0 / rate)
            for (i in maxOf(0, lag - separation + 1)..minOf(normalized.lastIndex, lag + separation - 1)) {
                suppressed[i] = true
            }
        }
        currentCoroutineContext().ensureActive()
        val split = minOf(rate / 10, received.size)
        fun rms(samples: DoubleArray) = bandRms(samples, rate, config.band.startHz, config.band.endHz)
        return AcousticReport(
            rms(received), rms(received.copyOfRange(0, split)), rms(received.copyOfRange(split, received.size)),
            selected.firstOrNull()?.let { rms(received.copyOfRange(it.lagSamples, it.lagSamples + reference.size)) },
            selected,
        )
    }

    /** Chunk calls to the existing DSP function so cancellation is checked between bounded work units. */
    suspend fun normalizedCorrelation(received: DoubleArray, reference: DoubleArray): DoubleArray {
        require(reference.isNotEmpty() && received.size >= reference.size)
        val referenceEnergy = reference.sumOf { it * it }
        val result = DoubleArray(received.size - reference.size + 1)
        if (referenceEnergy <= 1e-24) return result
        val prefixEnergy = DoubleArray(received.size + 1)
        received.forEachIndexed { i, x -> prefixEnergy[i + 1] = prefixEnergy[i] + x * x }
        for (start in result.indices step 256) {
            currentCoroutineContext().ensureActive()
            val end = minOf(start + 256, result.size)
            val correlation = DspEngine.crossCorrelate(received.copyOfRange(start, end + reference.size - 1), reference)
            for (i in correlation.indices) {
                val lag = start + i
                val energy = maxOf(0.0, prefixEnergy[lag + reference.size] - prefixEnergy[lag])
                result[lag] = if (energy <= 1e-24) 0.0
                    else (correlation[i] / sqrt(referenceEnergy * energy)).coerceIn(-1.0, 1.0)
            }
        }
        return result
    }

    /** Zero-padded radix-2 DFT; Parseval scaling uses the original sample count, not padding. */
    fun bandRms(samples: DoubleArray, rate: Int, lowHz: Double, highHz: Double): Double {
        require(rate > 0 && lowHz >= 0 && highHz >= lowHz && highHz <= rate / 2.0)
        if (samples.isEmpty()) return 0.0
        var size = 1
        while (size < samples.size) size *= 2
        val real = samples.copyOf(size)
        val imaginary = DoubleArray(size)
        var reverse = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (reverse and bit != 0) { reverse = reverse xor bit; bit = bit shr 1 }
            reverse = reverse xor bit
            if (i < reverse) {
                val temp = real[i]; real[i] = real[reverse]; real[reverse] = temp
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            for (start in 0 until size step length) {
                var wr = 1.0
                var wi = 0.0
                for (offset in 0 until length / 2) {
                    val a = start + offset
                    val b = a + length / 2
                    val tr = wr * real[b] - wi * imaginary[b]
                    val ti = wr * imaginary[b] + wi * real[b]
                    real[b] = real[a] - tr; imaginary[b] = imaginary[a] - ti
                    real[a] += tr; imaginary[a] += ti
                    val next = wr * stepReal - wi * stepImaginary
                    wi = wr * stepImaginary + wi * stepReal
                    wr = next
                }
            }
            length *= 2
        }
        var energy = 0.0
        for (bin in 0..size / 2) {
            val hz = bin * rate.toDouble() / size
            if (hz in lowHz..highHz) {
                val weight = if (bin == 0 || bin == size / 2) 1 else 2
                energy += weight * (real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
            }
        }
        return sqrt(energy / size / samples.size)
    }
}

/** Hardware timestamp mapping estimates rendered chirp position on the input sample timeline.
 * It verifies API-reported overlap, not acoustic detection or a calibrated propagation delay.
 */
internal object PlaybackOverlap {
    fun describe(input: AudioClockSample?, output: AudioClockSample?, rate: Int,
                 recordedFrames: Int, chirpOffsetFrames: Int, chirpFrames: Int): String {
        if (input == null || output == null) return "INCONCLUSIVE: hardware timestamps unavailable; acoustic overlap not verified"
        val start = input.framePosition + (output.nanoTime - input.nanoTime) * rate / 1e9 +
            chirpOffsetFrames - output.framePosition
        val end = start + chirpFrames
        val status = if (start >= 0 && end <= recordedFrames) "TIMESTAMP_OVERLAP" else "OUTSIDE_CAPTURE"
        return "$status: estimated chirp inputFrames=[$start,$end) inputMs=[${start * 1000 / rate},${end * 1000 / rate}); hardware clock estimate, not acoustic detection"
    }
}
