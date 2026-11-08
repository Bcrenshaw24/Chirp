package com.example.chirp.spatial

import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.*
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

// Submission switch: restore marker rendering by setting this to true; anchors stay active.
internal const val SHOW_SPATIAL_MARKERS = false

internal data class SavedLocation(val id: Int, val name: String, val guidance: String, val distanceMeters: Float? = null)
internal data class SpatialSnapshot(
    val status: String = "Starting camera…",
    val objects: List<SavedLocation> = emptyList(),
    val naming: Boolean = false,
    val tracking: Boolean = false,
)

/** Session update, hit tests, and anchor mutations all run on the GL thread. */
internal class SpatialRenderer(
    private val session: () -> Session?,
    private val rotation: () -> Int,
    private val publish: (SpatialSnapshot) -> Unit,
) : GLSurfaceView.Renderer {
    private val background = CameraBackground()
    private val markers = AnchorMarkerRenderer()
    private var selectedId: Int? = null
    private var textureSession: Session? = null
    private var lastDiagnostic = 0L
    private var currentRotation = 0
    private var tagAttempt = 0
    private val commands = ConcurrentLinkedQueue<(Frame) -> Unit>()
    private val anchors = linkedMapOf<Int, Pair<String, Anchor>>()
    private var pending: Anchor? = null
    private var nextId = 0
    private var width = 1
    private var height = 1
    private var lastPublish = 0L
    private var lastCameraTracking = false
    private var lastSelectedAnchorTracking = false
    private var notice: String? = null
    fun tap(x: Float?, y: Float?) {
        val requestedAt = android.os.SystemClock.elapsedRealtime()
        commands.add { frame ->
            val attempt = ++tagAttempt
            val hitX = x ?: width / 2f
            val hitY = y ?: height / 2f
            val diagnose = Log.isLoggable("ChirpSpatial", Log.DEBUG)
            if (diagnose) Log.d("ChirpSpatial", "tag=$attempt source=${if (x == null) "reticle" else "touch"} " +
                "viewPx=($hitX,$hitY) viewport=${width}x$height rotation=$currentRotation " +
                "queueAgeMs=${android.os.SystemClock.elapsedRealtime() - requestedAt} " +
                "frame=${frame.timestamp} cameraState=${frame.camera.trackingState} " +
                "cameraWorld=${frame.camera.pose.translation.contentToString()}")
            if (pending == null && frame.camera.trackingState == TrackingState.TRACKING) {
                val hits = frame.hitTest(hitX, hitY)
                // Original unrestricted surface eligibility from b2eb765.
                val hit = hits.firstOrNull {
                    val plane = it.trackable as? Plane
                    plane != null && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                        plane.trackingState == TrackingState.TRACKING && plane.isPoseInPolygon(it.hitPose)
                }
                if (diagnose) {
                    val uv = FloatArray(2)
                    frame.transformCoordinates2d(Coordinates2d.VIEW, floatArrayOf(hitX, hitY),
                        Coordinates2d.TEXTURE_NORMALIZED, uv)
                    Log.d("ChirpSpatial", "tag=$attempt candidates=${hits.size} cameraTextureUv=${uv.contentToString()}")
                    hits.forEachIndexed { index, candidate ->
                        val plane = candidate.trackable as? Plane
                        Log.d("ChirpSpatial", "tag=$attempt hit=$index selected=${candidate === hit} " +
                            "trackable=${candidate.trackable.javaClass.simpleName} planeType=${plane?.type} " +
                            "state=${candidate.trackable.trackingState} insidePolygon=${plane?.isPoseInPolygon(candidate.hitPose)} " +
                            "hitDistance=${candidate.distance} world=${candidate.hitPose.translation.contentToString()} " +
                            "cameraMinusHitY=${frame.camera.pose.ty() - candidate.hitPose.ty()} " +
                            "planeId=${plane?.let { System.identityHashCode(it) }} planeY=${plane?.centerPose?.ty()} " +
                            "extentXZ=(${plane?.extentX},${plane?.extentZ})")
                    }
                    val planes = session()?.getAllTrackables(Plane::class.java).orEmpty()
                        .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING }
                    Log.d("ChirpSpatial", "tag=$attempt detectedHorizontalPlanes=" + planes.joinToString { plane ->
                        "id=${System.identityHashCode(plane)} y=${plane.centerPose.ty()} extent=(${plane.extentX},${plane.extentZ})"
                    })
                }
                if (hit != null) {
                    pending = hit.createAnchor(); notice = null
                    if (diagnose) {
                        logAnchorComparison("tag=$attempt created", frame, pending!!)
                        // Independent projection check: a hit on this ray must map back to the requested VIEW pixel.
                        val view = FloatArray(16); val projection = FloatArray(16)
                        val worldToClip = FloatArray(16); val clip = FloatArray(4)
                        frame.camera.getViewMatrix(view, 0)
                        frame.camera.getProjectionMatrix(projection, 0, 0.02f, 100f)
                        Matrix.multiplyMM(worldToClip, 0, projection, 0, view, 0)
                        Matrix.multiplyMV(clip, 0, worldToClip, 0,
                            floatArrayOf(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz(), 1f), 0)
                        if (clip[3] > 0f) Log.d("ChirpSpatial", "tag=$attempt projectedHitViewPx=" +
                            "(${(clip[0] / clip[3] + 1f) * width / 2f},${(1f - clip[1] / clip[3]) * height / 2f}) " +
                            "requestedViewPx=($hitX,$hitY)")
                    }
                } else notice = "No horizontal surface here. Move slowly around a textured table and try again."
            } else if (pending == null) notice = "Tracking unavailable. Move slowly in good light."
            lastPublish = 0
        }
    }
    private fun logAnchorComparison(stage: String, frame: Frame, anchor: Anchor) {
        val camera = frame.camera.pose
        val pose = anchor.pose
        val dx = camera.tx() - pose.tx(); val dy = camera.ty() - pose.ty(); val dz = camera.tz() - pose.tz()
        Log.d("ChirpSpatial", "$stage anchorState=${anchor.trackingState} " +
            "cameraWorld=${camera.translation.contentToString()} anchorWorld=${pose.translation.contentToString()} " +
            "cameraMinusAnchorY=$dy horizontalMeters=${kotlin.math.sqrt(dx * dx + dz * dz)} " +
            "euclideanMeters=${kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)}")
    }
    fun save(name: String) { commands.add { frame ->
        pending?.let {
            anchors[++nextId] = name to it
            if (Log.isLoggable("ChirpSpatial", Log.DEBUG)) logAnchorComparison("saved anchor=$nextId", frame, it)
        }
        pending = null; lastPublish = 0
    } }
    fun select(id: Int?) { commands.add { selectedId = id } }
    fun cancel() { commands.add { pending?.detach(); pending = null; lastPublish = 0 } }
    fun delete(id: Int) { commands.add { anchors.remove(id)?.second?.detach(); lastPublish = 0 } }
    // Called only after GLSurfaceView.onPause has joined the rendering thread.
    fun release() {
        commands.clear(); pending?.detach(); pending = null
        anchors.values.forEach { it.second.detach() }; anchors.clear()
    }
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        background.create()
        // Texture names belong to the new GL context, even if an integer ID is reused.
        textureSession = null
        if (SHOW_SPATIAL_MARKERS) markers.create()
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width; this.height = height; glViewport(0, 0, width, height)
    }
    override fun onDrawFrame(gl: GL10?) {
        glDepthMask(true)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        val current = session() ?: return
        try {
            val displayRotation = rotation()
            currentRotation = displayRotation
            current.setDisplayGeometry(displayRotation, width, height)
            if (textureSession !== current) {
                // Texture contents are not guaranteed after re-registration. Avoid doing it
                // before update() can return a repeated camera timestamp; keep the binding stable.
                current.setCameraTextureName(background.texture)
                textureSession = current
            }
            val frame = current.update()
            glDisable(GL_DEPTH_TEST)
            glDepthMask(false)
            background.draw(frame)
            glDepthMask(true)
            while (true) (commands.poll() ?: break).invoke(frame)
            val diagnosticTime = android.os.SystemClock.elapsedRealtime()
            val diagnose = Log.isLoggable("ChirpSpatial", Log.DEBUG) && diagnosticTime - lastDiagnostic >= 1000
            if (diagnose) {
                lastDiagnostic = diagnosticTime
                Log.d("ChirpSpatial", "frame=${frame.timestamp} viewport=${width}x$height rotation=$displayRotation " +
                    "cameraState=${frame.camera.trackingState}/${frame.camera.trackingFailureReason} " +
                    "cameraWorld=${frame.camera.pose.translation.contentToString()} " +
                    "cameraQuaternion=${frame.camera.pose.rotationQuaternion.contentToString()}")
            }
            // Draw every camera frame, before the throttled Compose snapshot update.
            if (SHOW_SPATIAL_MARKERS && frame.camera.trackingState == TrackingState.TRACKING) {
                markers.begin(frame.camera)
                for ((id, entry) in anchors) {
                    if (entry.second.trackingState == TrackingState.TRACKING) {
                        markers.draw(entry.second.pose, id == selectedId)
                        if (diagnose) Log.d("ChirpSpatial", "anchor=$id selected=${id == selectedId} " +
                            "state=${entry.second.trackingState} world=${entry.second.pose.translation.contentToString()} " +
                            "quaternion=${entry.second.pose.rotationQuaternion.contentToString()} " +
                            "projectedOrigin=${markers.projectedOrigin(width, height)}")
                    }
                }
                markers.end()
            }
            if (diagnose) {
                for ((id, entry) in anchors) {
                    if (id == selectedId) logAnchorComparison("selected anchor=$id", frame, entry.second)
                    if (!SHOW_SPATIAL_MARKERS || frame.camera.trackingState != TrackingState.TRACKING || entry.second.trackingState != TrackingState.TRACKING)
                        Log.d("ChirpSpatial", "anchor=$id state=${entry.second.trackingState} " +
                            "world=${entry.second.pose.translation.contentToString()} projectedOrigin=hidden markersEnabled=$SHOW_SPATIAL_MARKERS")
                }
                val error = glGetError()
                if (error != GL_NO_ERROR) Log.w("ChirpSpatial", "GL error=0x${error.toString(16)}")
            }
            val now = android.os.SystemClock.elapsedRealtime()
            val tracking = frame.camera.trackingState == TrackingState.TRACKING
            val selectedTracking = anchors[selectedId]?.second?.trackingState == TrackingState.TRACKING
            // Feedback cancellation must not wait for the normal UI snapshot interval.
            if (now - lastPublish < 350 && tracking == lastCameraTracking &&
                selectedTracking == lastSelectedAnchorTracking) return
            lastPublish = now
            lastCameraTracking = tracking
            lastSelectedAnchorTracking = selectedTracking
            val surface = current.getAllTrackables(Plane::class.java).any {
                it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING
            }
            val status = when {
                !tracking -> "Tracking unavailable: ${frame.camera.trackingFailureReason}. Move slowly; use good light and a textured surface."
                notice != null -> notice!!
                surface -> "Surface ready. Tap a location or aim the ring and use Tag Object."
                else -> "Tracking. Slowly scan a textured table or counter."
            }
            val objects = anchors.map { (id, entry) ->
                val (name, anchor) = entry
                var distanceMeters: Float? = null
                val guidance = if (!tracking || anchor.trackingState != TrackingState.TRACKING)
                    "Location tracking unavailable. Return toward the saved area."
                else {
                    val relative = frame.camera.displayOrientedPose.inverse().compose(anchor.pose).translation
                    distanceMeters = kotlin.math.sqrt(relative[0] * relative[0] + relative[1] * relative[1] + relative[2] * relative[2])
                    spatialDirection(relative[0], relative[1], relative[2])
                }
                SavedLocation(id, name, guidance, distanceMeters)
            }
            publish(SpatialSnapshot(status, objects, pending != null, tracking))
        } catch (failure: Exception) {
            publish(SpatialSnapshot("AR error: ${failure.javaClass.simpleName}. Leave and reopen Spatial Memory to retry."))
        }
    }
}
