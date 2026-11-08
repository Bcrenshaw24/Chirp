package com.example.chirp.sonar.dsp

import kotlin.math.hypot
import kotlin.math.sqrt

/** Stateless signal-processing helpers. */
object Signal {

    /**
     * Matched filter: r[lag] = sum_m x[lag + m] * h[m], for lag = 0 .. N - M ("valid" form).
     *
     * If x contains a * h delayed by d, r peaks at lag d with value a * energy(h). A chirp's
     * wide bandwidth B compresses that peak to about 1/B seconds, which sets range resolution
     * (c / 2B: 3.4 cm for a 5 kHz sweep).
     *
     * Time-domain O(N*M) is deliberate: per chirp period the window is about 1,100 samples
     * against a 240-sample chirp (~0.3 M multiply-adds), which costs less than FFT setup.
     */
    fun crossCorrelate(x: DoubleArray, h: DoubleArray): DoubleArray {
        require(h.isNotEmpty() && x.size >= h.size) { "need non-empty reference no longer than signal" }
        val out = DoubleArray(x.size - h.size + 1)
        for (lag in out.indices) {
            var acc = 0.0
            for (m in h.indices) acc += x[lag + m] * h[m]
            out[lag] = acc
        }
        return out
    }

    /**
     * Envelope |x + j*Hilbert(x)| via the analytic signal (zero-padded FFT).
     *
     * A chirp's correlation peak oscillates at the carrier, giving several near-equal lobes
     * ~1/f apart. The envelope turns each arrival into one smooth peak, so peak picking does
     * not depend on which lobe happened to be largest.
     */
    fun envelope(x: DoubleArray): DoubleArray {
        if (x.isEmpty()) return DoubleArray(0)
        val n = Fft.nextPowerOfTwo(maxOf(2, x.size))
        val re = x.copyOf(n)
        val im = DoubleArray(n)
        Fft.transform(re, im)
        for (k in 1 until n / 2) { re[k] *= 2.0; im[k] *= 2.0 }
        for (k in n / 2 + 1 until n) { re[k] = 0.0; im[k] = 0.0 }
        Fft.transform(re, im, inverse = true)
        return DoubleArray(x.size) { hypot(re[it], im[it]) }
    }

    /** Sub-sample vertex offset in [-0.5, 0.5] of the parabola through y[i-1], y[i], y[i+1]. */
    fun parabolicOffset(y: DoubleArray, i: Int): Double {
        if (i <= 0 || i >= y.lastIndex) return 0.0
        val denom = y[i - 1] - 2.0 * y[i] + y[i + 1]
        return if (denom == 0.0) 0.0 else (0.5 * (y[i - 1] - y[i + 1]) / denom).coerceIn(-0.5, 0.5)
    }

    /** Centred moving average; the window shrinks at the edges. */
    fun movingAverage(x: DoubleArray, width: Int): DoubleArray {
        if (width <= 1 || x.isEmpty()) return x.copyOf()
        val prefix = DoubleArray(x.size + 1)
        for (i in x.indices) prefix[i + 1] = prefix[i] + x[i]
        val half = width / 2
        return DoubleArray(x.size) { i ->
            val a = maxOf(0, i - half)
            val b = minOf(x.size, i + half + 1)
            (prefix[b] - prefix[a]) / (b - a)
        }
    }

    fun rms(x: DoubleArray, from: Int = 0, to: Int = x.size): Double {
        if (to <= from) return 0.0
        var e = 0.0
        for (i in from until to) e += x[i] * x[i]
        return sqrt(e / (to - from))
    }

    fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sortedArray()
        return s[s.size / 2]
    }
}
