package com.example.chirp.sonar.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object ChirpGenerator {
    /**
     * Linear FM chirp in [-amplitude, amplitude] with raised-cosine edge fades.
     *
     *   f(t)   = f0 + k t,  k = (f1 - f0) / T
     *   phi(t) = 2 pi (f0 t + k t^2 / 2)      (phase is the integral of frequency)
     *   s[n]   = A w[n] sin(phi(n / fs))
     *
     * The fades suppress the click (broadband splatter, often audible) that an abrupt start
     * or stop would cause on the speaker.
     */
    fun linearChirp(
        sampleRateHz: Int,
        startHz: Double,
        endHz: Double,
        durationSec: Double,
        amplitude: Double,
        fadeSec: Double,
    ): DoubleArray {
        require(sampleRateHz > 0 && durationSec > 0) { "rate and duration must be positive" }
        require(startHz > 0 && endHz > 0 && maxOf(startHz, endHz) < sampleRateHz / 2.0) {
            "frequencies must be in (0, Nyquist)"
        }
        require(amplitude in 0.0..1.0) { "amplitude must be in [0, 1]" }
        require(fadeSec >= 0 && 2 * fadeSec <= durationSec) { "fade too long for duration" }

        val n = (sampleRateHz * durationSec).roundToInt()
        val rate = (endHz - startHz) / durationSec
        val fade = (sampleRateHz * fadeSec).roundToInt()
        return DoubleArray(n) { i ->
            val t = i.toDouble() / sampleRateHz
            val edge = minOf(i, n - 1 - i)
            val w = if (fade > 0 && edge < fade) 0.5 * (1 - cos(PI * edge / fade)) else 1.0
            amplitude * w * sin(2 * PI * (startHz * t + 0.5 * rate * t * t))
        }
    }

    /** One loop period for the speaker: the chirp at frame 0 followed by silence. */
    fun chirpTrainPeriod(chirp: DoubleArray, periodFrames: Int): ShortArray {
        require(chirp.size <= periodFrames) { "chirp longer than period" }
        return ShortArray(periodFrames) { i -> if (i < chirp.size) toPcm16(chirp[i]) else 0 }
    }

    fun toPcm16(x: Double): Short =
        (x * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}
