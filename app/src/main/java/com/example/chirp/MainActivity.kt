package com.example.chirp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chirp.ui.theme.ChirpTheme
import kotlinx.coroutines.CancellationException
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val distanceProvider: DistanceProvider = SimulatedDistanceProvider()
    private lateinit var haptics: DistanceHaptics
    private var scanning by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        haptics = DistanceHaptics(applicationContext)
        enableEdgeToEdge()
        setContent {
            ChirpTheme {
                var distance by remember { mutableStateOf<Double?>(null) }
                var message by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(scanning) {
                    if (!scanning) return@LaunchedEffect
                    distance = null
                    message = null
                    try {
                        while (scanning) {
                            val meters = distanceProvider.scanMeters()
                            distance = meters
                            if (!haptics.showDistance(meters)) {
                                message = "Vibration unavailable. Follow the distance display."
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        message = "Scan failed. Tap Scan to try again."
                        scanning = false
                    } finally {
                        haptics.stop()
                    }
                }

                ScanScreen(scanning, distance, message) {
                    if (scanning) stopScanning() else scanning = true
                }
            }
        }
    }

    private fun stopScanning() {
        scanning = false
        haptics.stop()
    }

    override fun onPause() {
        stopScanning()
        super.onPause()
    }
}

@Composable
private fun ScanScreen(
    scanning: Boolean,
    distance: Double?,
    message: String?,
    onToggleScan: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text("Chirp", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Feel the space around you", style = MaterialTheme.typography.bodyLarge)
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text("SIMULATED DEMO", Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge)
            }
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Simulated distance", style = MaterialTheme.typography.titleMedium)
                    Text(
                        distance?.let { String.format(Locale.getDefault(), "%.2f m", it) } ?: "— m",
                        fontSize = 56.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        when {
                            scanning && distance == null -> "Starting simulated scan…"
                            scanning -> "Scanning • simulated values"
                            distance != null -> "Stopped • last simulated value"
                            else -> "Ready to scan"
                        },
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        textAlign = TextAlign.Center,
                    )
                    if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            Button(
                onClick = onToggleScan,
                modifier = Modifier.size(184.dp),
                shape = CircleShape,
            ) {
                Text(if (scanning) "Stop" else "Scan", style = MaterialTheme.typography.headlineMedium)
            }
            Text("Closer values give stronger, faster pulses.\nRange: 0.50–3.00 meters.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Text("Demo values are random, not measured. No microphone or speaker is used.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ScanPreview() {
    ChirpTheme { ScanScreen(false, null, null) {} }
}
