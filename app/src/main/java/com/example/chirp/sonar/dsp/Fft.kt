package com.example.chirp.sonar.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The project's single FFT: in-place iterative radix-2. */
object Fft {
    fun nextPowerOfTwo(n: Int): Int {
        var size = 1
        while (size < n) size = size shl 1
        return size
    }

    /** Transforms [re]/[im] in place. Size must be a power of two. The inverse is scaled by 1/n. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size && n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = 2.0 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(angle)
            val wi = sin(angle)
            for (start in 0 until n step len) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = start + k
                    val b = a + len / 2
                    val tr = cr * re[b] - ci * im[b]
                    val ti = cr * im[b] + ci * re[b]
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
            }
            len *= 2
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}
