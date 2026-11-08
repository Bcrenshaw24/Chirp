package com.example.chirp.sonar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chirp.sonar.alerts.AlertConfig
import com.example.chirp.sonar.alerts.AlertLevel
import com.example.chirp.sonar.dsp.Signal
import com.example.chirp.sonar.ranging.Ranging
import com.example.chirp.sonar.SonarConfig
import com.example.chirp.sonar.SonarEngine
import com.example.chirp.sonar.SonarFrame
import com.example.chirp.sonar.SonarState
import com.example.chirp.sonar.tracking.Trend
import kotlin.math.abs
import kotlin.math.log10

/** Test bench: live distance plus every intermediate signal, so failures are visible, not guessed. */
@Composable
fun DebugScreen(vm: SonarViewModel, onStart: () -> Unit) {
    val frame by vm.frame.collectAsState()
    val status by vm.status.collectAsState()
    val history by vm.history.collectAsState()
    val running by vm.running.collectAsState()
    val error by vm.error.collectAsState()
    val config by vm.config.collectAsState()
    val vibrate by vm.vibrate.collectAsState()
    val handheld by vm.handheld.collectAsState()

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Chirp sonar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            AlertBanner(frame)
            Readout(frame, running)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (running) vm.stop() else onStart() }) { Text(if (running) "Stop" else "Start") }
                OutlinedButton(onClick = vm::recalibrate, enabled = running && status.state == SonarState.RUNNING) {
                    Text("Recalibrate")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("15–20 kHz" to SonarConfig.INAUDIBLE, "8–14 kHz (audible)" to SonarConfig.AUDIBLE).forEach { (label, c) ->
                    FilterChip(selected = config == c, enabled = !running, onClick = { vm.selectConfig(c) }, label = { Text(label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = handheld, onCheckedChange = vm::setHandheld)
                Text(
                    if (handheld) "Handheld: background adapts (~1.5 s)" else "Fixed baseline (phone on a table)",
                    Modifier.padding(start = 8.dp),
                )
            }
            if (vm.vibrationAvailable) Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vibrate, onCheckedChange = vm::setVibrate)
                Text("Vibrate on alert", Modifier.padding(start = 8.dp))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (running) Text(status.message, style = MaterialTheme.typography.bodyMedium)
            if (frame?.state == SonarState.CALIBRATING) LinearProgressIndicator(
                progress = { frame?.calibrationProgress ?: 0f }, modifier = Modifier.fillMaxWidth(),
            )
            if (!running && frame == null) Text(
                "Put the phone on a table, bottom edge (speaker + mic) facing open space. Set media volume " +
                    "to about half. Start, keep the area clear for ~3 s while it calibrates, then bring an " +
                    "object (a book, your hand, a box) in front of the bottom edge.",
                style = MaterialTheme.typography.bodyMedium,
            )

            frame?.let { f -> Plots(f, config, history, status) }
            if (frame != null) AccuracyCard(vm, canMeasure = running && status.state == SonarState.RUNNING)
            Diagnostics(frame, status, config)
        }
    }
}

@Composable
private fun AlertBanner(frame: SonarFrame?) {
    val alert = frame?.alert ?: return
    if (alert.level == AlertLevel.NONE) return
    val warning = alert.level == AlertLevel.WARNING
    Surface(
        color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(if (warning) "WARNING" else "CAUTION", fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(alert.reason)
        }
    }
}

@Composable
private fun Readout(frame: SonarFrame?, running: Boolean) {
    val track = frame?.track
    val nearest = frame?.detection?.nearest
    val shown = track?.takeIf { it.confirmed }?.distanceM ?: nearest?.distanceM
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                shown?.let { "%.2f m".format(it) } ?: "— m",
                fontSize = 48.sp, fontWeight = FontWeight.Bold,
            )
            Text(shown?.let { "%.1f ft".format(it * Ranging.FEET_PER_METER) } ?: if (running) "No object detected" else "Stopped")
            if (track != null) {
                val trend = when (track.trend) {
                    Trend.APPROACHING -> "APPROACHING"
                    Trend.RECEDING -> "moving away"
                    Trend.STEADY -> "steady"
                    Trend.UNKNOWN -> "confirming…"
                }
                Text("$trend  •  %+.2f m/s".format(track.velocityMps) +
                    (track.timeToContactSec?.let { "  •  contact in %.1f s".format(it) } ?: "") +
                    (if (track.missed > 0) "  •  coasting (${track.missed} missed)" else ""))
            }
            nearest?.let { Text("Nearest echo SNR %.1f dB  •  %d echo(es)".format(it.snrDb, frame.detection?.echoes?.size ?: 0)) }
        }
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
