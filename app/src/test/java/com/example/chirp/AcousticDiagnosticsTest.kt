package com.example.chirp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AcousticDiagnosticsTest {
    @Test fun allSixWaveformsRetainDspPcmContract() {
        for (band in ChirpBand.entries) for (duration in listOf(10, 30, 50)) {
            val config = AcousticConfig(band, duration)
            val bytes = DspEngine.generateChirp(48000, band.startHz, band.endHz, config.durationMs / 1000.0, amplitude = 0.15)
            assertEquals(duration * 48 * 2, bytes.size)
            assertArrayEquals(bytes, PcmAudio.encode(PcmAudio.decode(bytes)))
            assertTrue(DspEngine.pcm16ToDoubles(bytes).maxOf { abs(it) } <= 0.151)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDurationIsRejected() { AcousticConfig(durationMs = 500) }

    @Test fun normalizedCorrelationFindsInvertedGainScaledChirpAcrossChunkBoundary() = runBlocking {
        val reference = DspEngine.pcm16ToDoubles(DspEngine.generateChirp(48000, 8000.0, 14000.0, 0.03))
        val received = DoubleArray(5000)
        reference.indices.forEach { received[778 + it] = -0.023 * reference[it] }
        val ncc = AcousticDiagnostics.normalizedCorrelation(received, reference)
        assertEquals(778, ncc.indices.maxByOrNull { abs(ncc[it]) })
        assertEquals(-1.0, ncc[778], 1e-10)
        assertTrue(ncc.all { it.isFinite() && abs(it) <= 1.0 })
    }

    @Test fun normalizationMatchesDirectWindowEnergy() = runBlocking {
        val received = DoubleArray(700) { sin(it * 0.6) + cos(it * 0.12) }
        val reference = doubleArrayOf(0.2, -0.4, 0.7, 0.1)
        val ncc = AcousticDiagnostics.normalizedCorrelation(received, reference)
        for (lag in ncc.indices) {
            val window = received.copyOfRange(lag, lag + reference.size)
            val expected = window.indices.sumOf { window[it] * reference[it] } /
                sqrt(window.sumOf { it * it } * reference.sumOf { it * it })
            assertEquals(expected, ncc[lag], 1e-10)
        }
    }

    @Test fun silenceProducesFiniteZeroMetricsAndNoPeaks() = runBlocking {
        val chirp = DspEngine.generateChirp(48000, 8000.0, 14000.0, 0.01)
        val report = AcousticDiagnostics.analyze(ByteArray(10000), chirp, 48000, AcousticConfig(baseline = true))
        assertEquals(0.0, report.bandRms, 0.0)
        assertEquals(0.0, report.preRollBandRms, 0.0)
        assertTrue(report.peaks.isEmpty())
        assertNull(report.peakWindowBandRms)
        assertTrue(AcousticDiagnostics.normalizedCorrelation(DoubleArray(20), DoubleArray(5)).all { it == 0.0 })
    }

    @Test fun selectedBandRejectsOtherToneAndPreservesRmsScaling() {
        val rate = 48000
        val signal = DoubleArray(4096) { 0.2 * sin(2 * PI * 9000 * it / rate) + 0.4 * sin(2 * PI * 18000 * it / rate) }
        assertEquals(0.2 / sqrt(2.0), AcousticDiagnostics.bandRms(signal, rate, 8000.0, 14000.0), 1e-10)
        assertEquals(0.4 / sqrt(2.0), AcousticDiagnostics.bandRms(signal, rate, 15000.0, 20000.0), 1e-10)
        assertEquals(sqrt(0.1), AcousticDiagnostics.bandRms(signal, rate, 0.0, 24000.0), 1e-10)
    }

    @Test fun zeroPaddingUsesOriginalSampleCountAndHandlesDcNyquist() {
        val signal = DoubleArray(137) { 0.3 + if (it % 2 == 0) 0.2 else -0.2 }
        assertEquals(sqrt(signal.sumOf { it * it } / signal.size),
            AcousticDiagnostics.bandRms(signal, 48000, 0.0, 24000.0), 1e-10)
        assertEquals(0.3, AcousticDiagnostics.bandRms(DoubleArray(128) { 0.3 }, 48000, 0.0, 0.0), 1e-10)
        assertEquals(0.2, AcousticDiagnostics.bandRms(DoubleArray(128) { if (it % 2 == 0) 0.2 else -0.2 },
            48000, 24000.0, 24000.0), 1e-10)
    }

    @Test fun reportPeakTimeIsRelativeToFirstInputSample() = runBlocking {
        val chirp = DspEngine.generateChirp(48000, 8000.0, 14000.0, 0.01)
        val input = ByteArray(20000)
        chirp.copyInto(input, 6000 * 2)
        val report = AcousticDiagnostics.analyze(input, chirp, 48000, AcousticConfig())
        assertEquals(6000, report.peaks.first().lagSamples)
        assertEquals(125.0, report.peaks.first().timeMs, 0.0)
        assertTrue(report.bandRms > 0)
    }

    @Test fun timestampMappingAccountsForBothFramePositionsAndChirpLead() {
        val input = AudioClockSample(24000, 1_500_000_000)
        val output = AudioClockSample(9600, 1_400_000_000)
        val overlap = PlaybackOverlap.describe(input, output, 48000, 33600, 4800, 2400)
        assertTrue(overlap, overlap.startsWith("TIMESTAMP_OVERLAP"))
        assertTrue(overlap, overlap.contains("inputFrames=[14400.0,16800.0)"))
        assertTrue(PlaybackOverlap.describe(input, AudioClockSample(0, 2_000_000_000),
            48000, 33600, 4800, 2400).startsWith("OUTSIDE_CAPTURE"))
        assertTrue(PlaybackOverlap.describe(input, null, 48000, 33600, 4800, 2400).startsWith("INCONCLUSIVE"))
    }
}
