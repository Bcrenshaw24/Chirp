package com.example.chirp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    private lateinit var audioCapture: AudioCapture
    private var audioJob: Job? = null
    private var audioBusy by mutableStateOf(false)
    private var audioStatus by mutableStateOf("Configurable chirp or recording-only baseline • no distance estimate")
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioStatus = if (granted) "Microphone allowed. Tap Start test to begin."
        else "Microphone denied. Simulation still works. Allow Microphone in App permissions to retry."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        haptics = DistanceHaptics(applicationContext)
        audioCapture = AudioCapture(applicationContext)
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

                ScanScreen(
                    scanning, distance, message,
                    onToggleScan = {
                        if (scanning) stopScanning() else {
                            stopAudioCapture()
                            scanning = true
                        }
                    },
                    audioPanel = {
                        AudioDiagnosticPanel(audioBusy, audioStatus,
                            onCapture = { config -> if (audioBusy) stopAudioCapture() else startAudioCapture(config) },
                            onPermissions = {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName")))
                            })
                    },
                )
            }
        }
    }

    private fun stopScanning() {
        scanning = false
        haptics.stop()
    }

    private fun startAudioCapture(config: AcousticConfig) {
        stopScanning()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioStatus = "Microphone access is needed only for the audio test. Grant it, then tap Start test again."
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        audioBusy = true
        audioStatus = if (config.baseline) "Recording baseline…" else "Capturing ${config.band.label}, ${config.durationMs} ms chirp…"
        audioJob = lifecycleScope.launch {
            try {
                val result = audioCapture.capture(config)
                val directory = audioCapture.export(result)
                val report = checkNotNull(result.report)
                val peak = report.peaks.firstOrNull()
                val metrics = String.format(Locale.getDefault(),
                    "Band RMS %.4g • pre-roll %.4g • later %.4g", report.bandRms, report.preRollBandRms, report.laterBandRms)
                val peakText = peak?.let { String.format(Locale.getDefault(),
                    "Max |NCC| %.4f (signed %.4f) at %.2f ms, sample %d", kotlin.math.abs(it.signedNcc), it.signedNcc, it.timeMs, it.lagSamples)
                } ?: "No nonzero NCC candidates"

                audioStatus = "Captured ${result.recordedPcm.size / 2} frames at ${result.sampleRateHz} Hz. " +
                    "Source: ${AudioCapture.sourceName(result.audioSource)}; raw support reported: ${result.unprocessedSupported}. " +
                    "\n${if (config.baseline) "Recording-only baseline" else "Active chirp"}: ${config.band.label}, ${config.durationMs} ms reference. " +
                    "\n$metrics\n$peakText\n${result.overlap}\n" +
                    "Saved files/audio-diagnostic/${directory.name}. Latest PCM exports also retained. " +
                    "Other peaks and signal statistics: metadata.txt / ChirpAudio logs. Candidates are not confirmed detections or distances."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                audioStatus = "Audio test failed: ${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                audioBusy = false
            }
        }
    }

    private fun stopAudioCapture() {
        if (audioJob?.isActive == true) {
            audioJob?.cancel()
            audioStatus = "Audio test stopped."
        }
    }

    override fun onPause() {
        stopAudioCapture()
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
    audioPanel: @Composable () -> Unit = {},
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
            Text("Demo values are random, not measured. Simulated scanning uses no microphone or speaker.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            audioPanel()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ScanPreview() {
    ChirpTheme { ScanScreen(false, null, null, onToggleScan = {}) }
}
