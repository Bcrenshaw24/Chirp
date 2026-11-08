package com.example.chirp.spatial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.opengl.GLSurfaceView
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.chirp.DistanceHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private lateinit var haptics: DistanceHaptics
    private var hapticJob: Job? = null
    private var selectedObject: Int? = null
    private var pendingFind: Int? = null
    private var lastSnapshotAt = 0L
    private var trackingAnnouncements = TrackingAnnouncements()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && resumed) startAr()
        else snapshot = SpatialSnapshot("Camera permission denied. Allow Camera in App settings, then retry.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        haptics = DistanceHaptics(applicationContext)
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val language = tts?.setLanguage(Locale.getDefault())
                speechReady = language != null && language >= 0
                announceFindIfReady()
            }
        }
        renderer = SpatialRenderer({ session }, { @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation }) {
            runOnUiThread {
                if (!isDestroyed && !isFinishing && resumed && running) {
                    snapshot = it
                    lastSnapshotAt = SystemClock.elapsedRealtime()
                    updateFeedback()
                }
            }
        }
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    logTagRequest("touch", event.x, event.y)
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
        stopFeedback()
        if (running) {
            surface.onPause()
            session?.pause()
            running = false
        }
        ready = false
        snapshot = snapshot.copy(status = "AR paused. Resume and re-establish tracking.", tracking = false, objects = snapshot.objects.map {
            it.copy(guidance = "Location tracking unavailable while paused.", distanceMeters = null)
        })
        tts?.stop()
        super.onPause()
    }
    override fun onDestroy() {
        stopFeedback()
        surface.onPause()
        renderer.release()
        session?.close(); session = null
        tts?.shutdown(); tts = null
        super.onDestroy()
    }
    private fun speak(message: String) {
        if (resumed && running && !isFinishing && speechReady)
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "spatial-feedback")
    }

    private fun announceFindIfReady() {
        val id = pendingFind ?: return
        if (!speechReady || !resumed || !running || isFinishing) return
        val item = snapshot.objects.firstOrNull { it.id == id } ?: return
        pendingFind = null
        speak("${item.name}. ${item.guidance}")
    }

    private fun updateFeedback() {
        updateHaptics()
        val announcement = if (speechReady)
            trackingAnnouncements.update(snapshot.tracking, SystemClock.elapsedRealtime()) else null
        if (announcement != null) speak(announcement)
        else announceFindIfReady()
    }

    private fun selectedDistance(): Float? =
        if (!resumed || !running || isFinishing || !snapshot.tracking ||
            SystemClock.elapsedRealtime() - lastSnapshotAt > 1500) null
        else snapshot.objects.firstOrNull { it.id == selectedObject }?.distanceMeters

    private fun updateHaptics() {
        if (proximityPattern(selectedDistance()) == null) { stopHaptics(); return }
        if (hapticJob?.isActive == true) return
        hapticJob = lifecycleScope.launch {
            try {
                while (true) {
                    val pattern = proximityPattern(selectedDistance()) ?: break
                    if (!haptics.pulse(pattern.pulseCount, pattern.pulseMs, pattern.gapMs, pattern.amplitude)) break
                    delay(pattern.intervalMs)
                }
            } finally { haptics.stop() }
        }
    }

    private fun stopHaptics() {
        hapticJob?.cancel()
        hapticJob = null
        haptics.stop()
    }

    private fun stopFeedback() {
        stopHaptics()
        pendingFind = null
        trackingAnnouncements = TrackingAnnouncements()
        tts?.stop()
    }

    private fun logTagRequest(source: String, x: Float, y: Float) {
        if (!Log.isLoggable("ChirpSpatial", Log.DEBUG)) return
        val location = IntArray(2)
        surface.getLocationInWindow(location)
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(surface)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
        Log.d("ChirpSpatial", "tagRequest source=$source localPx=($x,$y) " +
            "viewSize=${surface.width}x${surface.height} viewOriginWindow=${location.contentToString()} " +
            "surfaceBuffer=${surface.holder.surfaceFrame} rotation=${surface.display?.rotation} systemInsets=$insets")
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
            onTag = {
                logTagRequest("reticle", surface.width / 2f, surface.height / 2f)
                renderer.tap(null, null)
            },
            onSave = renderer::save,
            onCancel = renderer::cancel,
            onSelect = { id ->
                stopHaptics()
                tts?.stop()
                selectedObject = id
                pendingFind = id
                renderer.select(id)
                announceFindIfReady()
                updateHaptics()
            },
            onDelete = { id ->
                if (selectedObject == id) {
                    selectedObject = null
                    pendingFind = null
                    stopHaptics()
                    tts?.stop()
                }
                renderer.delete(id)
            },
            onSpeak = { item ->
                pendingFind = null
                speak("${item.name}. ${item.guidance}")
            },
            onLeave = { stopFeedback(); finish() },
        )
    }
}
