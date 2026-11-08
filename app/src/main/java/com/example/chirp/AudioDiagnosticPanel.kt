package com.example.chirp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun AudioDiagnosticPanel(busy: Boolean, status: String, onCapture: (AcousticConfig) -> Unit, onPermissions: () -> Unit) {
    var config by remember { mutableStateOf(AcousticConfig()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Acoustic diagnostic", style = MaterialTheme.typography.titleLarge)
            Text("Uses the built-in microphone and speaker. Simulated scanning pauses during this test.")
            Text("Volume warning: use low, nonzero media volume and keep the speaker away from ears. 8–14 kHz may be audible. Do not raise volume just to hear a high-frequency chirp.",
                color = MaterialTheme.colorScheme.error)
            Text("Reference band", style = MaterialTheme.typography.labelLarge)
            // A column accommodates large accessibility font sizes without clipping choices.
            ChirpBand.entries.forEach { band ->
                FilterChip(selected = config.band == band, enabled = !busy,
                    onClick = { config = config.copy(band = band) }, label = { Text(band.label) })
            }
            Text("Chirp duration", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 30, 50).forEach { duration ->
                    FilterChip(selected = config.durationMs == duration, enabled = !busy,
                        onClick = { config = config.copy(durationMs = duration) }, label = { Text("$duration ms") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = config.baseline, enabled = !busy,
                    onCheckedChange = { config = config.copy(baseline = it) })
                Text("Recording-only baseline", Modifier.padding(start = 8.dp))
            }
            Text(if (config.baseline) "No playback. Compare background against the selected reference."
                else "One chirp only • fixed low gain • 0.7 s capture • no automatic detection verdict")
            Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Button(onClick = { onCapture(config) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Stop audio test" else "Start test")
            }
            TextButton(onClick = onPermissions) { Text("App permissions") }
        }
    }
}
