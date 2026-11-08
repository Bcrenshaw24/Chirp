package com.example.chirp.spatial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.opengl.GLSurfaceView
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.chirp.ui.theme.ChirpTheme
import com.google.ar.core.*
import java.util.Locale

class SpatialMemoryActivity : ComponentActivity() {
    @Volatile private var session: Session? = null
    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: SpatialRenderer
    private var snapshot by mutableStateOf(SpatialSnapshot())
    private var ready by mutableStateOf(false)
    private var running = false
    private var resumed = false
    private var installRequested = false
    private var tts: TextToSpeech? = null
    private var speechReady by mutableStateOf(false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && resumed) startAr()
        else snapshot = SpatialSnapshot("Camera permission denied. Allow Camera in App settings, then retry.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val language = tts?.setLanguage(Locale.getDefault())
                speechReady = language != null && language >= 0
            }
        }
        renderer = SpatialRenderer({ session }, { @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation }) {
            runOnUiThread { if (!isDestroyed) snapshot = it }
        }
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    renderer.tap(event.x, event.y); view.performClick()
                }
                true
            }
        }
        setContent { ChirpTheme { SpatialContent() } }
    }
    override fun onResume() { super.onResume(); resumed = true; startAr() }
    private fun startAr() {
        if (running) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            snapshot = SpatialSnapshot("Camera access is required for Spatial Memory.")
            return
        }
        try {
            if (session == null) {
                val availability = ArCoreApk.getInstance().checkAvailability(this)
                if (availability.isTransient) {
                    snapshot = SpatialSnapshot("Checking ARCore support. Tap Start / retry shortly."); return
                }
                if (!availability.isSupported) {
                    snapshot = SpatialSnapshot("ARCore is unavailable on this device. Scanner and audio modes remain available."); return
                }
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    snapshot = SpatialSnapshot("Complete Google Play Services for AR installation, then retry."); return
                }
                val created = Session(this)
                try {
                    created.configure(Config(created).apply {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                        focusMode = Config.FocusMode.AUTO
                    })
                    session = created
                } catch (failure: Exception) { created.close(); throw failure }
            }
            session!!.resume()
            running = true; ready = true
            surface.onResume()
        } catch (failure: Exception) {
            snapshot = SpatialSnapshot("Cannot start AR: ${failure.javaClass.simpleName}. Check AR services and camera access, then retry.")
        }
    }
    override fun onPause() {
        resumed = false
        if (running) {
            surface.onPause()
            session?.pause()
            running = false
        }
        ready = false
        snapshot = snapshot.copy(status = "AR paused. Resume and re-establish tracking.", objects = snapshot.objects.map {
            it.copy(guidance = "Location tracking unavailable while paused.")
        })
        tts?.stop()
        super.onPause()
    }
    override fun onDestroy() {
        surface.onPause()
        renderer.release()
        session?.close(); session = null
        tts?.shutdown(); tts = null
        super.onDestroy()
    }
    @Composable private fun SpatialContent() {
        var name by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf<Int?>(null) }
        val chosen = snapshot.objects.firstOrNull { it.id == selected }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Spatial Memory", style = MaterialTheme.typography.headlineMedium)
                    Text("One room • this session only. Leaving this mode or restarting clears saved locations. Positions can drift; this is not obstacle avoidance.")
                    Text(snapshot.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (!ready) {
                        Button(onClick = {
                            if (ContextCompat.checkSelfPermission(this@SpatialMemoryActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                                permission.launch(Manifest.permission.CAMERA)
                            else { installRequested = false; startAr() }
                        }) { Text("Start / retry camera and AR") }
                        TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) { Text("App permission settings") }
                    }
                    Button(onClick = { renderer.tap(null, null) }, enabled = ready && !snapshot.naming) {
                        Text("Save location at camera center")
                    }
                    Text("Aim the center of the camera at the object's supporting surface. You can also tap its position in the camera preview.")
                    Text("Saved objects (${snapshot.objects.size})", style = MaterialTheme.typography.titleLarge)
                    snapshot.objects.forEach { item ->
                        Button(onClick = { selected = item.id }, modifier = Modifier.fillMaxWidth()) { Text("Find ${item.name}") }
                        TextButton(onClick = { renderer.delete(item.id); if (selected == item.id) selected = null }) { Text("Delete ${item.name}") }
                    }
                    chosen?.let { item ->
                        Text("${item.name}: ${item.guidance}", style = MaterialTheme.typography.titleLarge)
                        Button(enabled = speechReady && ready, onClick = {
                            tts?.speak("${item.name}. ${item.guidance}", TextToSpeech.QUEUE_FLUSH, null, "spatial-location")
                        }) { Text("Speak location") }
                    }
                    if (!speechReady) Text("Spoken feedback unavailable or initializing. Text guidance is available.")
                    TextButton(onClick = { finish() }) { Text("Leave Spatial Memory and clear objects") }
                }
                AndroidView(factory = { surface }, modifier = Modifier.fillMaxWidth().weight(1f)
                    .semantics { contentDescription = "Live AR camera preview. Use Save location at camera center to tag with TalkBack." })
            }
        }
        if (snapshot.naming) AlertDialog(
            onDismissRequest = { renderer.cancel(); name = "" },
            title = { Text("Name this location") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("Object name") }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { renderer.save(name.trim()); name = "" }) { Text("Save object") } },
            dismissButton = { TextButton(onClick = { renderer.cancel(); name = "" }) { Text("Cancel") } },
        )
    }
}
