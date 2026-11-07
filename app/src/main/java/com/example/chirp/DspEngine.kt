package com.example.chirp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Echolocation DSP engine.
 *
 * Step 1: FMCW (linear sine sweep) chirp generation only.
 */
object DspEngine {

    /**
     * Generates a linear frequency-modulated chirp as 16-bit signed PCM,
     * little-endian, mono (2 bytes per sample).
     *
     * @param sampleRateHz   Sample rate fs in Hz (e.g. 44100 or 48000).
     * @param startFreqHz    Sweep start frequency f0 in Hz.
     * @param endFreqHz      Sweep end frequency f1 in Hz (must be < fs/2, Nyquist).
     * @param durationSec    Sweep duration T in seconds.
     * @param amplitude      Peak amplitude in [0.0, 1.0] relative to full scale.
     * @param fadeSec        Raised-cosine fade-in/out length at each end (0 = none).
     *                       Suppresses the click / spectral splatter from abrupt
     *                       start and stop.
     * @return PCM16 little-endian bytes, length = 2 * round(fs * T).
     *
     * Math:
     *   Instantaneous frequency is linear in time:
     *       f(t) = f0 + k t,        k = (f1 - f0) / T      (sweep rate, Hz/s)
     *
     *   Phase is the integral of 2*pi*f(t) dt:
     *       phi(t) = 2*pi * integral_0^t (f0 + k tau) dtau
     *              = 2*pi * (f0 t + (k/2) t^2)
     *
     *   Sample n occurs at t = n / fs, so:
     *       s[n] = A * w[n] * sin(phi(n / fs))
     *
     *   Note: sin(2*pi*f(t)*t) would be WRONG; frequency is the derivative of
     *   phase, so phase must be the integral of frequency (hence the k/2 t^2).
     */
    fun generateChirp(
        sampleRateHz: Int,
        startFreqHz: Double,
        endFreqHz: Double,
        durationSec: Double,
        amplitude: Double = 0.8,
        fadeSec: Double = 0.002,
    ): ByteArray {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
        require(durationSec > 0.0) { "durationSec must be > 0" }
        require(startFreqHz > 0.0 && endFreqHz > 0.0) { "frequencies must be > 0" }
        require(maxOf(startFreqHz, endFreqHz) < sampleRateHz / 2.0) {
            "frequencies must be below Nyquist (fs/2)"
        }
        require(amplitude in 0.0..1.0) { "amplitude must be in [0, 1]" }
        require(fadeSec >= 0.0 && fadeSec * 2 <= durationSec) { "fade too long for duration" }

        val numSamples = (sampleRateHz * durationSec).roundToInt()
        val sweepRate = (endFreqHz - startFreqHz) / durationSec // k, Hz/s
        val fadeSamples = (sampleRateHz * fadeSec).roundToInt()
        val out = ByteArray(numSamples * 2)

        for (n in 0 until numSamples) {
            val t = n.toDouble() / sampleRateHz
            val phase = 2.0 * PI * (startFreqHz * t + 0.5 * sweepRate * t * t)

            // Raised-cosine edge taper: 0 -> 1 over fadeSamples, mirrored at the end.
            val edge = minOf(n, numSamples - 1 - n)
            val window = if (fadeSamples > 0 && edge < fadeSamples) {
                0.5 * (1.0 - cos(PI * edge / fadeSamples))
            } else {
                1.0
            }

            val sample = amplitude * window * sin(phase)
            val pcm = (sample * Short.MAX_VALUE).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

            out[2 * n] = (pcm and 0xFF).toByte()            // low byte
            out[2 * n + 1] = ((pcm shr 8) and 0xFF).toByte() // high byte
        }
        return out
    }

    /**
     * Decodes 16-bit signed little-endian mono PCM bytes into doubles in [-1.0, 1.0).
     * Inverse of the quantization in [generateChirp].
     */
    fun pcm16ToDoubles(pcm: ByteArray): DoubleArray {
        require(pcm.size % 2 == 0) { "PCM16 byte array must have even length" }
        return DoubleArray(pcm.size / 2) { i ->
            val lo = pcm[2 * i].toInt() and 0xFF
            val hi = pcm[2 * i + 1].toInt() // sign-extends
            ((hi shl 8) or lo).toShort() / 32768.0
        }
    }

    /**
     * Cross-correlates a received signal x with a reference (the transmitted chirp) h,
     * using the direct time-domain "valid" form (no zero padding).
     *
     * @param received  x[n], length N (the recording).
     * @param reference h[m], length M (the clean chirp), with M <= N.
     * @return r[lag] for lag = 0 .. N - M, i.e. N - M + 1 values.
     *
     * Math:
     *       r[lag] = sum_{m=0}^{M-1} x[lag + m] * h[m]
     *
     *   This slides h along x and takes the dot product at each offset. It is
     *   correlation, not convolution: h is NOT time-reversed. (Convolution with the
     *   reversed h, the "matched filter", gives the identical output.)
     *
     *   If x contains a delayed, scaled copy of h, x[n] = a * h[n - d] + noise,
     *   then r[lag] peaks at lag = d, because there x[lag + m] aligns with h[m] and
     *   the sum becomes a * sum(h[m]^2) = a * E_h (the chirp energy). At other lags
     *   the chirp's wide bandwidth makes h nearly orthogonal to its own shifts, so
     *   r stays small. The peak width is about 1/B seconds (B = sweep bandwidth),
     *   which sets the range resolution later.
     *
     *   Lag is in samples; lag / fs gives seconds. Converting to distance and
     *   finding the peak are NOT done here (step 3).
     *
     *   Cost is O(N * M). Fine for verification and short recordings; an FFT-based
     *   version (zero-pad, IFFT(X * conj(H))) is the O(N log N) alternative.
     */
    fun crossCorrelate(received: DoubleArray, reference: DoubleArray): DoubleArray {
        require(reference.isNotEmpty()) { "reference must not be empty" }
        require(received.size >= reference.size) {
            "received must be at least as long as reference"
        }

        val numLags = received.size - reference.size + 1
        val out = DoubleArray(numLags)
        for (lag in 0 until numLags) {
            var acc = 0.0
            for (m in reference.indices) {
                acc += received[lag + m] * reference[m]
            }
            out[lag] = acc
        }
        return out
    }

    /** Result of [findPeak]. [lag] is fractional samples after parabolic interpolation. */
    data class Peak(val lag: Double, val magnitude: Double)

    /**
     * Finds the strongest peak of |r| at or after [minLag], with sub-sample refinement.
     *
     * @param correlation Output of [crossCorrelate].
     * @param minLag      First lag (samples) to search. Use this to skip the direct
     *                    speaker-to-mic arrival and near-field ringing.
     * @return The peak, or null if the search range is empty or all zero.
     *
     * Math:
     *   The raw correlation oscillates at the carrier frequency, with both positive
     *   and negative lobes, so the peak is taken on |r[lag]|.
     *
     *   Parabolic interpolation: with y-1, y0, y+1 = |r| at lag-1, lag, lag+1,
     *   fit a parabola through the three points; its vertex is at
     *       delta = 0.5 * (y-1 - y+1) / (y-1 - 2*y0 + y+1),   delta in [-0.5, 0.5]
     *   and the refined lag is (lag + delta). This gives resolution finer than
     *   1 sample (one sample at 48 kHz is ~3.6 mm of range).
     *
     *   Caveat: |r| of a carrier-modulated peak has several near-equal lobes
     *   (spaced ~1/f_center apart), so noise can pick a neighbouring lobe. An
     *   envelope (Hilbert magnitude) would be more robust; not done here.
     */
    fun findPeak(correlation: DoubleArray, minLag: Int = 0): Peak? {
        require(minLag >= 0) { "minLag must be >= 0" }
        if (minLag >= correlation.size) return null

        var best = minLag
        var bestVal = Math.abs(correlation[minLag])
        for (i in minLag + 1 until correlation.size) {
            val v = Math.abs(correlation[i])
            if (v > bestVal) {
                bestVal = v
                best = i
            }
        }
        if (bestVal == 0.0) return null

        var refined = best.toDouble()
        if (best > 0 && best < correlation.size - 1) {
            val y1 = Math.abs(correlation[best - 1])
            val y2 = bestVal
            val y3 = Math.abs(correlation[best + 1])
            val denom = y1 - 2.0 * y2 + y3
            if (denom != 0.0) refined += 0.5 * (y1 - y3) / denom
        }
        return Peak(refined, bestVal)
    }

    /**
     * Speed of sound in dry air, m/s, from temperature in degrees Celsius.
     *     c ~= 331.3 + 0.606 * T
     * (about 343.2 m/s at 20 C). Linear approximation, good to ~0.1% over -20..40 C.
     */
    fun speedOfSound(temperatureC: Double = 20.0): Double = 331.3 + 0.606 * temperatureC

    /**
     * Converts an echo lag to one-way distance to the reflector.
     *
     * @param echoLag          Echo peak lag in samples (from [findPeak]).
     * @param sampleRateHz     Sample rate fs.
     * @param referenceLagSamples Lag at which the direct (speaker-to-mic) arrival
     *                         peaks, i.e. the zero-distance point. Subtracting it
     *                         removes fixed system latency. Pass 0 only if the
     *                         recording starts exactly at transmit time.
     * @param speedOfSoundMps  Speed of sound c in m/s.
     * @return Distance in metres (negative if the echo precedes the reference).
     *
     * Math:
     *   Round-trip delay:  tau = (echoLag - referenceLag) / fs        [s]
     *   The sound travels out and back, so path length = c * tau and
     *       distance = c * tau / 2
     *
     *   Range resolution of the chirp is c / (2B); sampling resolution is
     *   c / (2 fs). The coarser of the two limits accuracy.
     */
    fun lagToDistanceMeters(
        echoLag: Double,
        sampleRateHz: Int,
        referenceLagSamples: Double = 0.0,
        speedOfSoundMps: Double = speedOfSound(),
    ): Double {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
        val roundTripSec = (echoLag - referenceLagSamples) / sampleRateHz
        return speedOfSoundMps * roundTripSec / 2.0
    }
}
