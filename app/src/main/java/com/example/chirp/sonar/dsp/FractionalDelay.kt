package com.example.chirp.sonar.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Delays a fixed, band-limited signal by any fractional number of samples:
 * y[n] = x[n - delay], computed exactly as a linear phase ramp in the frequency domain.
 *
 * The signal's spectrum is computed once; each [shift] is one multiply and one inverse FFT.
 * Zero padding to at least twice the length keeps shifts of a few samples from wrapping around.
 */
class FractionalDelay(private val x: DoubleArray) {
    private val n = Fft.nextPowerOfTwo(2 * maxOf(1, x.size))
    private val re = x.copyOf(n)
    private val im = DoubleArray(n)

    init { Fft.transform(re, im) }

    fun shift(delay: Double): DoubleArray {
        if (delay == 0.0) return x.copyOf()
        val yr = DoubleArray(n)
        val yi = DoubleArray(n)
        for (k in 1 until n / 2) {
            val phase = -2 * PI * k * delay / n
            val c = cos(phase); val s = sin(phase)
            yr[k] = re[k] * c - im[k] * s
            yi[k] = re[k] * s + im[k] * c
            yr[n - k] = yr[k]; yi[n - k] = -yi[k] // keep the output real
        }
        yr[0] = re[0]
        // Nyquist bin left at zero: its phase is ambiguous for a fractional shift, and our signals have no energy there.
        Fft.transform(yr, yi, inverse = true)
        return yr.copyOf(x.size)
    }
}
