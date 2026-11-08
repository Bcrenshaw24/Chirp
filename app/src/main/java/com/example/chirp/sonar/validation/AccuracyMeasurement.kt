package com.example.chirp.sonar.validation

import kotlin.math.abs
import kotlin.math.sqrt

/** Statistics of raw readings taken with an object at a tape-measured distance. */
data class AccuracyPoint(
    val trueM: Double,
    val readings: List<Double>,
    /** Chirp periods it took to collect them. */
    val periods: Int,
    /** Echo SNR (dB over calibrated noise) of each reading. Low values near the detection threshold mean a weak target. */
    val snrsDb: List<Double> = emptyList(),
) {
    val medianSnrDb: Double? get() = snrsDb.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    val meanM: Double get() = readings.average()
    /** Robust centre: unmoved by occasional readings from a different reflector. Used for fitting. */
    val medianM: Double get() = readings.sorted().let { s ->
        if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
    val minM: Double get() = readings.min()
    val maxM: Double get() = readings.max()
    /** Repeatability: sample standard deviation. */
    val stdM: Double get() = if (readings.size < 2) 0.0 else
        sqrt(readings.sumOf { (it - meanM) * (it - meanM) } / (readings.size - 1))
    /** Bias of the raw (uncorrected) readings, from the median. */
    val biasM: Double get() = medianM - trueM
    val detectionRate: Double get() = if (periods == 0) 0.0 else readings.size.toDouble() / periods
}

/**
 * Collects readings for one known distance. Each period, the echo nearest the expected
 * distance (within [windowM]) counts, so the table, a wall or the hand holding the tape
 * measure isn't mistaken for the target. Periods with no echo in the window count as misses.
 */
class AccuracyMeasurement(
    val trueM: Double,
    private val windowM: Double = 0.3,
    private val wanted: Int = 20,
    private val maxPeriods: Int = 60,
) {
    private val readings = mutableListOf<Double>()
    private val snrs = mutableListOf<Double>()
    private var periods = 0

    val isDone get() = readings.size >= wanted || periods >= maxPeriods
    val progress get() = maxOf(readings.size.toFloat() / wanted, periods.toFloat() / maxPeriods).coerceAtMost(1f)

    /** [echoes]: every echo in this period as (raw distance m, SNR dB). */
    fun add(echoes: List<Pair<Double, Double>>) {
        if (isDone) return
        periods++
        echoes.filter { abs(it.first - trueM) <= windowM }.minByOrNull { abs(it.first - trueM) }?.let {
            readings += it.first
            snrs += it.second
        }
    }

    fun result() = AccuracyPoint(trueM, readings.toList(), periods, snrs.toList())
}
