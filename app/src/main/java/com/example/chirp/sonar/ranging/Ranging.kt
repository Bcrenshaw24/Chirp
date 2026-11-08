package com.example.chirp.sonar.ranging

/**
 * Delay <-> distance conversion. All delays are measured from the DIRECT speaker-to-mic
 * arrival, never from play()/record() call times: fixed output/input latency (tens of ms
 * on Android, and variable between runs) cancels exactly that way.
 *
 * Geometry: speaker and mic are [spacingM] apart on the same edge. An object at distance D
 * gives an echo path of about 2D; the direct path is spacingM. So the measured delay is
 *     tau = (2D - spacingM) / c      =>      D = (c * tau + spacingM) / 2
 */
object Ranging {
    /** Dry air, m/s; ~0.1% accurate over -20..40 C. 343.4 m/s at 20 C. */
    fun speedOfSound(temperatureC: Double): Double = 331.3 + 0.606 * temperatureC

    fun distanceFromDelay(delaySec: Double, speedOfSound: Double, spacingM: Double): Double =
        (speedOfSound * delaySec + spacingM) / 2

    fun delayForDistance(distanceM: Double, speedOfSound: Double, spacingM: Double): Double =
        (2 * distanceM - spacingM) / speedOfSound

    const val FEET_PER_METER = 3.280839895
}
