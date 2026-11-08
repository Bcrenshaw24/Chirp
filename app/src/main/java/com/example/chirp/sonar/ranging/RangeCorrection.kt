package com.example.chirp.sonar.ranging

/**
 * Linear correction from raw to true distance: true = scale * raw + offsetM.
 *
 * Offset absorbs the speaker-mic spacing guess (an error of e in the spacing shifts every
 * distance by e/2) and any fixed processing bias. Scale absorbs speed-of-sound error:
 * about 0.18% per degree C away from the assumed 20 C.
 */
data class RangeCorrection(val scale: Double = 1.0, val offsetM: Double = 0.0) {
    fun apply(rawM: Double): Double = scale * rawM + offsetM
    val isIdentity get() = scale == 1.0 && offsetM == 0.0

    companion object {
        /** Below this spread of raw distances the slope is poorly determined, so only an offset is fitted. */
        const val MIN_SPREAD_FOR_SCALE_M = 0.4
        /** Loose sanity bounds: only reject fits that are clearly garbage (e.g. one point on the wrong reflector). */
        val PLAUSIBLE_SCALE = 0.5..1.5
        const val MAX_OFFSET_M = 0.5

        /**
         * Least squares through (raw, true) pairs. Returns null when the result is physically
         * implausible, which usually means some points measured the wrong reflector.
         */
        fun fit(points: List<Pair<Double, Double>>): RangeCorrection? {
            if (points.isEmpty()) return null
            val mx = points.map { it.first }.average()
            val my = points.map { it.second }.average()
            val spread = points.maxOf { it.first } - points.minOf { it.first }
            val result = if (points.size < 2 || spread < MIN_SPREAD_FOR_SCALE_M) {
                RangeCorrection(1.0, my - mx)
            } else {
                val sxx = points.sumOf { (it.first - mx) * (it.first - mx) }
                val scale = points.sumOf { (it.first - mx) * (it.second - my) } / sxx
                RangeCorrection(scale, my - scale * mx)
            }
            return result.takeIf { it.scale in PLAUSIBLE_SCALE && kotlin.math.abs(it.offsetM) <= MAX_OFFSET_M }
        }
    }
}
