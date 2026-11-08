package com.example.chirp.spatial

import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import com.google.ar.core.*
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

internal data class SavedLocation(val id: Int, val name: String, val guidance: String)
internal data class SpatialSnapshot(
    val status: String = "Starting camera…",
    val objects: List<SavedLocation> = emptyList(),
    val naming: Boolean = false,
)

/** Session update, hit tests, and anchor mutations all run on the GL thread. */
internal class SpatialRenderer(
    private val session: () -> Session?,
    private val rotation: () -> Int,
    private val publish: (SpatialSnapshot) -> Unit,
) : GLSurfaceView.Renderer {
    private val background = CameraBackground()
    private val commands = ConcurrentLinkedQueue<(Frame) -> Unit>()
    private val anchors = linkedMapOf<Int, Pair<String, Anchor>>()
    private var pending: Anchor? = null
    private var nextId = 0
    private var width = 1
    private var height = 1
    private var lastPublish = 0L
    private var notice: String? = null
    fun tap(x: Float?, y: Float?) { commands.add { frame ->
        if (pending == null && frame.camera.trackingState == TrackingState.TRACKING) {
            val hit = frame.hitTest(x ?: width / 2f, y ?: height / 2f).firstOrNull {
                val plane = it.trackable as? Plane
                plane != null && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    plane.trackingState == TrackingState.TRACKING && plane.isPoseInPolygon(it.hitPose)
            }
            if (hit != null) { pending = hit.createAnchor(); notice = null }
            else notice = "No horizontal surface here. Move slowly around a textured table and try again."
        } else if (pending == null) notice = "Tracking unavailable. Move slowly in good light."
        lastPublish = 0
    } }
    fun save(name: String) { commands.add {
        pending?.let { anchors[++nextId] = name to it }; pending = null; lastPublish = 0
    } }
    fun cancel() { commands.add { pending?.detach(); pending = null; lastPublish = 0 } }
    fun delete(id: Int) { commands.add { anchors.remove(id)?.second?.detach(); lastPublish = 0 } }
    // Called only after GLSurfaceView.onPause has joined the rendering thread.
    fun release() {
        commands.clear(); pending?.detach(); pending = null
        anchors.values.forEach { it.second.detach() }; anchors.clear()
    }
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) { background.create() }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width; this.height = height; glViewport(0, 0, width, height)
    }
    override fun onDrawFrame(gl: GL10?) {
        glClear(GL_COLOR_BUFFER_BIT)
        val current = session() ?: return
        try {
            current.setDisplayGeometry(rotation(), width, height)
            current.setCameraTextureName(background.texture)
            val frame = current.update()
            background.draw(frame)
            while (true) (commands.poll() ?: break).invoke(frame)
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastPublish < 350) return
            lastPublish = now
            val tracking = frame.camera.trackingState == TrackingState.TRACKING
            val surface = current.getAllTrackables(Plane::class.java).any {
                it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING
            }
            val status = when {
                !tracking -> "Tracking unavailable: ${frame.camera.trackingFailureReason}. Move slowly; use good light and a textured surface."
                notice != null -> notice!!
                surface -> "Horizontal surface detected. Tap an object location or aim the center and save."
                else -> "Tracking. Slowly scan a textured table or counter to detect a horizontal surface."
            }
            val objects = anchors.map { (id, entry) ->
                val (name, anchor) = entry
                val guidance = if (!tracking || anchor.trackingState != TrackingState.TRACKING)
                    "Location tracking unavailable. Return toward the saved area."
                else {
                    val relative = frame.camera.displayOrientedPose.inverse().compose(anchor.pose).translation
                    spatialDirection(relative[0], relative[1], relative[2])
                }
                SavedLocation(id, name, guidance)
            }
            publish(SpatialSnapshot(status, objects, pending != null))
        } catch (failure: Exception) {
            publish(SpatialSnapshot("AR error: ${failure.javaClass.simpleName}. Leave and reopen Spatial Memory to retry."))
        }
    }
}
