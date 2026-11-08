package com.example.chirp.sonar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.chirp.sonar.alerts.AlertConfig
import com.example.chirp.sonar.dsp.Signal
import com.example.chirp.sonar.SonarConfig
import com.example.chirp.sonar.SonarEngine
import com.example.chirp.sonar.SonarFrame
import com.example.chirp.sonar.SonarState
import kotlin.math.abs
import kotlin.math.log10

/**
 * Test bench: every intermediate signal plus the accuracy check, so failures are visible, not guessed.
 * Shown inside the "Signal lab" section of [SonarScreen].
 */
@Composable
fun DebugPanels(vm: SonarViewModel) {
    val frame by vm.frame.collectAsState()
    val status by vm.status.collectAsState()
    val history by vm.history.collectAsState()
    val running by vm.running.collectAsState()
    val config by vm.config.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        frame?.let { f ->
            val track = f.track
            val nearest = f.detection?.nearest
            if (track != null || nearest != null) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Tracker", style = MaterialTheme.typography.titleSmall)
                    track?.let {
                        Text("%+.2f m/s".format(it.velocityMps) +
                            (it.timeToContactSec?.let { t -> "  •  contact in %.1f s".format(t) } ?: "") +
                            (if (it.missed > 0) "  •  coasting (${it.missed} missed)" else ""),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    nearest?.let {
                        Text("Nearest echo SNR %.1f dB  •  %d echo(es)".format(it.snrDb, f.detection?.echoes?.size ?: 0),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Plots(f, config, history, status)
            AccuracyCard(vm, canMeasure = running && status.state == SonarState.RUNNING)
        }
        if (frame == null) Text(
            "Start scanning to see live signals, echo detection and the accuracy check here.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Diagnostics(frame, status, config)
    }
}

@Composable
private fun Plots(f: SonarFrame, config: SonarConfig, history: List<HistoryPoint>, status: SonarEngine.Status) {
    val pre = SonarConfig.PROFILE_PRE
    val maxLag = config.maxLag.toDouble()
    val error = MaterialTheme.colorScheme.error
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    val echoMarkers = f.detection?.echoes.orEmpty().mapIndexed { i, e ->
        Marker(e.lagSamples / maxLag, if (i == 0) error else muted)
    }
    val minMarker = Marker(config.minLag / maxLag, muted, 1f)
    val rangeLabels = arrayOf("0 m", "%.1f m".format(config.maxRangeM / 2), "%.1f m".format(config.maxRangeM))

    val peak = f.rawWindow.maxOfOrNull { abs(it) } ?: 0.0
    PlotCard(
        "Raw microphone",
        "Unfiltered, ${"%.1f".format(f.rawWindow.size * 1000.0 / config.sampleRateHz)} ms from the direct arrival. " +
            "Peak ${"%.1f".format(20 * log10(peak.coerceAtLeast(1e-9)))} dBFS" +
            if (f.clippedSamples > 0) " • CLIPPING (${f.clippedSamples}): lower the volume" else "",
    ) {
        WaveformPlot(f.rawWindow, scale = peak.coerceAtLeast(1e-4),
            markers = listOf(Marker(pre.toDouble() / f.rawWindow.size, error, 1f)))
    }

    // Distance axis: profile index pre (direct arrival) .. end (maxRange).
    val envDb = f.profileEnvelope.copyOfRange(pre, f.profileEnvelope.size)
        .map { 20 * log10((it / f.directLevel).coerceAtLeast(1e-6)) }.toDoubleArray()
    PlotCard("Matched-filter output", "Envelope, dB relative to the direct (leakage) arrival. Everything static is here too.") {
        LinePlot(listOf(Series(envDb, primary)), -90.0, 0.0, echoMarkers + minMarker)
        AxisLabels(*rangeLabels)
    }

    val detection = f.detection
    val baseline = status.baseline
    if (detection != null && baseline != null) {
        val ref = Signal.median(baseline.noiseLevel)
        fun db(x: DoubleArray) = DoubleArray(x.size - pre) { 20 * log10((x[it + pre] / ref).coerceAtLeast(1e-3)) }
        PlotCard(
            "Echo detection",
            "What changed vs. the background (blue) and the detection threshold (grey), dB re noise. " +
                "Red line = nearest echo. Left grey line = blind zone edge.",
        ) {
            LinePlot(listOf(Series(db(detection.threshold), muted, 1.5f), Series(db(detection.residualEnvelope), primary)),
                -10.0, 50.0, echoMarkers + minMarker)
            AxisLabels(*rangeLabels)
        }
    }

    if (history.isNotEmpty()) {
        PlotCard("Distance history (15 s)", "Dots: nearest raw echo. Line: tracked distance (faint = not yet confirmed). 0 m at bottom.") {
            HistoryPlot(history, SonarViewModel.HISTORY_NS, config.maxRangeM)
            AxisLabels("-15 s", "now")
        }
    }

    baseline?.responseDb?.takeIf { it.size > 1 }?.let { points ->
        PlotCard("Speaker → mic response", "Measured at calibration across the chirp band, dB re strongest. Dips are weak frequencies.") {
            LinePlot(listOf(Series(points.map { it.db }.toDoubleArray(), primary)), -30.0, 0.0)
            AxisLabels("%.1f kHz".format(points.first().hz / 1000), "%.1f kHz".format(points.last().hz / 1000))
        }
    }
}

@Composable
private fun Diagnostics(f: SonarFrame?, status: SonarEngine.Status, config: SonarConfig) {
    val b = status.baseline
    val lines = buildList {
        add("State: ${status.state ?: "idle"}")
        add("Chirp: ${config.startHz.toInt()}–${config.endHz.toInt()} Hz, ${config.chirpMs} ms every ${config.periodMs.toInt()} ms, amp ${config.amplitude}")
        add("Range window: ${config.minRangeM}–${config.maxRangeM} m, resolution ~%.1f cm".format(100 * config.speedOfSound / (2 * config.bandwidthHz)))
        AlertConfig().let { a ->
            add("Alerts: warning < ${a.warningDistanceM} m or contact < ${a.warningTtcSec} s; caution < ${a.cautionDistanceM} m or < ${a.cautionTtcSec} s")
        }
        b?.let {
            add("Ambient (speaker off): %.1f dBFS in band".format(it.ambientDbfs))
            add("Leakage-to-noise: %.0f dB over ${it.frames} frames".format(it.dynamicRangeDb))
        }
        f?.let {
            add("Frame #${it.sequence}, processing %.1f ms, time from ${if (it.timeFromHardware) "hardware timestamp" else "sample count"}".format(it.processingMs))
            if (b != null) add("Direct path: %+.1f dB vs calibration".format(20 * log10(it.directLevel / b.directLevel)))
            it.misalignmentSamples?.let { m ->
                add("Direct peak drift (envelope): %+.2f samples".format(m))
            }
            it.detection?.let { d ->
                add("Leakage fit: shift %+.3f samples, gain %.3f".format(d.templateShift, d.leakageGain) +
                    if (abs(d.templateShift) > SonarConfig.DIRECT_SEARCH / 2.0) "  ← large: recalibrate" else "")
            }
        }
        addAll(status.setupNotes)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
    }
}
