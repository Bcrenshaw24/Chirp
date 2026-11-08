package com.example.chirp.spatial

import android.opengl.GLES20.*
import android.opengl.Matrix
import com.google.ar.core.Camera
import com.google.ar.core.Pose
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small solid diamonds in anchor-local meters. The lower tip is exactly at the saved pose. */
internal class AnchorMarkerRenderer {
    private val vertices = run {
        val bottom = floatArrayOf(0f, 0f, 0f)
        val top = floatArrayOf(0f, 0.12f, 0f)
        val ring = arrayOf(
            floatArrayOf(-0.04f, 0.06f, 0f), floatArrayOf(0f, 0.06f, 0.04f),
            floatArrayOf(0.04f, 0.06f, 0f), floatArrayOf(0f, 0.06f, -0.04f),
        )
        val values = mutableListOf<Float>()
        for (i in ring.indices) {
            // Facet brightness makes the marker readable without lighting or a texture.
            for (tip in arrayOf(top, bottom)) {
                val brightness = if (tip === top) 1f - i * 0.10f else 0.55f + i * 0.08f
                for (point in arrayOf(tip, ring[i], ring[(i + 1) % ring.size])) {
                    values.addAll(point.toList()); values.add(brightness)
                }
            }
        }
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values.toFloatArray()); position(0) }
    }
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)
    private var program = 0
    private var position = 0
    private var brightness = 0
    private var transform = 0
    private var color = 0

    fun create() {
        fun shader(type: Int, source: String) = glCreateShader(type).also {
            glShaderSource(it, source); glCompileShader(it)
            val ok = IntArray(1); glGetShaderiv(it, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { glGetShaderInfoLog(it) }
        }
        val vertex = shader(GL_VERTEX_SHADER, """
            uniform mat4 mvp;
            attribute vec3 position;
            attribute float brightness;
            varying mediump float shade;
            void main() { gl_Position = mvp * vec4(position, 1.0); shade = brightness; }
        """.trimIndent())
        val fragment = shader(GL_FRAGMENT_SHADER, """
            precision mediump float;
            uniform vec3 color;
            varying mediump float shade;
            void main() { gl_FragColor = vec4(color * shade, 1.0); }
        """.trimIndent())
        program = glCreateProgram()
        glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program)
        val ok = IntArray(1); glGetProgramiv(program, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { glGetProgramInfoLog(program) }
        glDeleteShader(vertex); glDeleteShader(fragment)
        position = glGetAttribLocation(program, "position")
        brightness = glGetAttribLocation(program, "brightness")
        transform = glGetUniformLocation(program, "mvp")
        color = glGetUniformLocation(program, "color")
    }

    fun begin(camera: Camera) {
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, 0.02f, 100f)
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glDepthMask(true)
        glDisable(GL_BLEND)
        glDisable(GL_CULL_FACE)
        glUseProgram(program)
        glEnableVertexAttribArray(position); glEnableVertexAttribArray(brightness)
        vertices.position(0)
        glVertexAttribPointer(position, 3, GL_FLOAT, false, 16, vertices)
        vertices.position(3)
        glVertexAttribPointer(brightness, 1, GL_FLOAT, false, 16, vertices)
    }

    fun draw(pose: Pose, selected: Boolean) {
        pose.toMatrix(model, 0)
        if (selected) Matrix.scaleM(model, 0, 1.4f, 1.4f, 1.4f)
        // ARCore's view/projection already include display rotation and viewport geometry.
        Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        glUniformMatrix4fv(transform, 1, false, mvp, 0)
        if (selected) glUniform3f(color, 1f, 0.85f, 0.1f)
        else glUniform3f(color, 0.15f, 0.9f, 1f)
        glDrawArrays(GL_TRIANGLES, 0, 24)
    }

    /** Uses the exact MVP uploaded by the preceding draw; coordinates are GL-view-local pixels. */
    fun projectedOrigin(width: Int, height: Int): String {
        // Multiplying MVP by (0,0,0,1) selects its fourth column (column-major).
        val w = mvp[15]
        if (!w.isFinite() || w <= 0f) return "behind-camera"
        val x = mvp[12] / w
        val y = mvp[13] / w
        val z = mvp[14] / w
        return "px=(${(x + 1f) * width / 2f},${(1f - y) * height / 2f}) ndcZ=$z " +
            "inView=${x in -1f..1f && y in -1f..1f && z in -1f..1f}"
    }

    fun end() {
        glDisableVertexAttribArray(position); glDisableVertexAttribArray(brightness)
        glDisable(GL_DEPTH_TEST)
    }
}
