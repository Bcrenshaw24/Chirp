package com.example.chirp.sonar.tracking

import kotlin.math.abs

enum class Trend { UNKNOWN, APPROACHING, STEADY, RECEDING }

data class Track(
    val distanceM: Double,
    /** Negative = closing. */
    val velocityMps: Double,
    val trend: Trend,
    /** True once [ProximityTracker.confirmHits] measurements agreed. Alerts should require this. */
    val confirmed: Boolean,
    /** Consecutive periods without a matching echo (the track is coasting on its prediction). */
    val missed: Int,
) {
    val timeToContactSec: Double?
        get() = if (trend == Trend.APPROACHING && velocityMps < 0) distanceM / -velocityMps else null
}

/**
 * Follows one object (the nearest) across chirp periods with an alpha-beta filter.
 *
 * Noise rejection lives here as much as in detection: a measurement must land inside a gate
 * around the prediction to update the track, a track needs several hits before it is
 * "confirmed", and trend changes use hysteresis so jitter doesn't flip approaching/receding.
 * Plain enough to reason about; swap for a Kalman filter only if this proves insufficient.
 */
class ProximityTracker(
    private val alpha: Double = 0.5,
    private val beta: Double = 0.1,
    private val gateM: Double = 0.25,
    private val confirmHits: Int = 3,
    private val maxMisses: Int = 5,
    /** Speed (m/s) that counts as moving; STEADY needs half of it (hysteresis). */
    private val trendSpeedMps: Double = 0.08,
) {
    private var active = false
    private var distance = 0.0
    private var velocity = 0.0
    private var lastNs = 0L
    private var hits = 0
    private var misses = 0
    private var trend = Trend.UNKNOWN

    fun reset() { active = false }

    /** [measurementsM]: all echo distances this period (any order). Returns null when nothing is tracked. */
    fun update(timeNs: Long, measurementsM: List<Double>): Track? {
        if (!active) {
            val first = measurementsM.minOrNull() ?: return null
            active = true; distance = first; velocity = 0.0; lastNs = timeNs
            hits = 1; misses = 0; trend = Trend.UNKNOWN
            return snapshot()
        }
        val dt = ((timeNs - lastNs) / 1e9).coerceIn(1e-3, 2.0)
        lastNs = timeNs
        val predicted = distance + velocity * dt
        val gate = gateM + abs(velocity) * dt
        val match = measurementsM.minByOrNull { abs(it - predicted) }?.takeIf { abs(it - predicted) <= gate }

        if (match != null) {
            val r = match - predicted
            distance = predicted + alpha * r
            velocity += beta / dt * r
            hits++; misses = 0
        } else {
            distance = predicted
            if (++misses > maxMisses) { active = false; return null }
        }
        distance = distance.coerceAtLeast(0.0)

        val confirmed = hits >= confirmHits
        trend = when {
            !confirmed -> Trend.UNKNOWN
            velocity < -trendSpeedMps -> Trend.APPROACHING
            velocity > trendSpeedMps -> Trend.RECEDING
            abs(velocity) < trendSpeedMps / 2 || trend == Trend.UNKNOWN -> Trend.STEADY
            else -> trend
        }
        return snapshot()
    }

    private fun snapshot() = Track(distance, velocity, trend, hits >= confirmHits, misses)
}
