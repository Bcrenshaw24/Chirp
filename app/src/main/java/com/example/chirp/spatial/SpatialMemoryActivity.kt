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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
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
        enableEdgeToEdge()
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
        SpatialMemoryScreen(
            surface = surface,
            snapshot = snapshot,
            ready = ready,
            speechReady = speechReady,
            onStart = {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.CAMERA)
                else { installRequested = false; startAr() }
            },
            onSettings = {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            onTag = { renderer.tap(null, null) },
            onSave = renderer::save,
            onCancel = renderer::cancel,
            onSelect = renderer::select,
            onDelete = renderer::delete,
            onSpeak = { item ->
                tts?.speak("${item.name}. ${item.guidance}", TextToSpeech.QUEUE_FLUSH, null, "spatial-location")
            },
            onLeave = { finish() },
        )
    }
}
