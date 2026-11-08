package com.example.chirp.sonar.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Streaming 4th-order Butterworth high-pass (two RBJ biquads, transposed direct form II).
 *
 * The matched filter already rejects most out-of-band energy, but speech, HVAC and handling
 * noise sit far below the chirp band and are much louder. Removing them first keeps noise
 * statistics, the raw-signal clip/level metrics and correlation sidelobes honest. The phase
 * shift it adds is identical for the direct path and every echo, so it cancels in ranging.
 */
class HighPassFilter(sampleRateHz: Int, cutoffHz: Double) {
    private class Section(q: Double, w0: Double) {
        private val b0: Double; private val b1: Double; private val b2: Double
        private val a1: Double; private val a2: Double
        private var z1 = 0.0; private var z2 = 0.0

        init {
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            b0 = (1 + cw) / 2 / a0; b1 = -(1 + cw) / a0; b2 = (1 + cw) / 2 / a0
            a1 = -2 * cw / a0; a2 = (1 - alpha) / a0
        }

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    private val sections: List<Section>

    init {
        require(cutoffHz > 0 && cutoffHz < sampleRateHz / 2.0) { "cutoff must be in (0, Nyquist)" }
        val w0 = 2 * PI * cutoffHz / sampleRateHz
        // Pole-pair Qs of a 4th-order Butterworth.
        sections = listOf(Section(0.5411961, w0), Section(1.3065630, w0))
    }

    fun process(x: Double): Double {
        var y = x
        for (s in sections) y = s.process(y)
        return y
    }
}
