package com.example.chirp.sonar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val TARGETS = listOf(0.3, 0.5, 1.0, 1.5)
/** Spread above this (vs. ~1-2 mm when stable) means readings alternate between reflectors. */
private const val UNSTABLE_STD_M = 0.01

/** Tape-measure check: per-distance bias, repeatability and detection rate, then a fitted correction. */
@Composable
fun AccuracyCard(vm: SonarViewModel, canMeasure: Boolean) {
    val measuring by vm.measuring.collectAsState()
    val points by vm.points.collectAsState()
    val correction by vm.correction.collectAsState()
    val fitMessage by vm.fitMessage.collectAsState()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Accuracy check", style = MaterialTheme.typography.titleSmall)
            Text(
                "After calibrating with the area clear, place a large flat board (40 cm+) squarely at a tape-measured " +
                    "distance from the bottom edge, move well off to the side, and tap that distance. Small objects at 1 m+ " +
                    "can be out-echoed by whatever is behind them. Each point collects 20 readings.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TARGETS.forEach { d ->
                    FilterChip(
                        selected = measuring?.first == d || points.any { it.trueM == d },
                        enabled = canMeasure && measuring == null,
                        onClick = { vm.measureAt(d) },
                        label = { Text("%.1f m".format(d)) },
                    )
                }
            }
            measuring?.let { (d, progress) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Measuring at %.1f m…".format(d), Modifier.weight(1f))
                    TextButton(onClick = vm::cancelMeasurement) { Text("Cancel") }
                }
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }

            if (points.isNotEmpty()) {
                points.forEach { p ->
                    val text = if (p.readings.isEmpty()) "%.2f m: no echo within ±0.3 m (%d periods)".format(p.trueM, p.periods)
                    else "%.2f m: median %.3f m, bias %+.0f mm, hits %.0f%%, SNR %.0f dB\n".format(
                        p.trueM, p.medianM, 1000 * p.biasM, 100 * p.detectionRate, p.medianSnrDb ?: 0.0,
                    ) + "  range %.3f–%.3f m, std %.1f mm, corrected %+.0f mm".format(
                        p.minM, p.maxM, 1000 * p.stdM, 1000 * (correction.apply(p.medianM) - p.trueM),
                    ) + if (p.stdM > UNSTABLE_STD_M) "\n  ⚠ readings jump: likely two reflectors" else ""
                    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                if (correction.isIdentity) "Correction: none"
                else "Correction: true = %.4f × raw %+.1f mm".format(correction.scale, 1000 * correction.offsetM),
                style = MaterialTheme.typography.bodySmall,
            )
            fitMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::fitCorrection, enabled = points.isNotEmpty() && measuring == null) { Text("Fit & apply") }
                if (!correction.isIdentity) TextButton(onClick = vm::resetCorrection) { Text("Reset correction") }
                if (points.isNotEmpty()) TextButton(onClick = vm::clearPoints) { Text("Clear") }
            }
        }
    }
}
