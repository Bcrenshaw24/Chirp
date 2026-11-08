package com.example.chirp

import org.junit.Assert.*
import org.junit.Test

class PcmAudioTest {
    @Test fun signedExtremesUseLittleEndianAndDspNormalization() {
        val samples = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
        val bytes = PcmAudio.encode(samples)
        assertArrayEquals(byteArrayOf(0, -128, -1, -1, 0, 0, 1, 0, -1, 127), bytes)
        assertArrayEquals(samples, PcmAudio.decode(bytes))
        val normalized = DspEngine.pcm16ToDoubles(bytes)
        samples.indices.forEach { assertEquals(samples[it] / 32768.0, normalized[it], 0.0) }
    }

    @Test fun chirpSurvivesAndroidShortConversionWithoutChangingReference() {
        val chirp = DspEngine.generateChirp(48000, 15000.0, 20000.0, 0.01, amplitude = 0.15)
        assertArrayEquals(chirp, PcmAudio.encode(PcmAudio.decode(chirp)))
    }

    @Test fun statisticsDetectSilenceAndClipping() {
        assertEquals(PcmAudio.Statistics(0.0, 0.0, 0), PcmAudio.statistics(ShortArray(100)))
        val stats = PcmAudio.statistics(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE))
        assertEquals(1.0, stats.peak, 0.0)
        assertEquals(2, stats.clippedSamples)
        assertEquals(kotlin.math.sqrt((1.0 + (32767.0 / 32768) * (32767.0 / 32768)) / 3), stats.rms, 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompletePcmFrames() { PcmAudio.decode(byteArrayOf(1)) }
}
