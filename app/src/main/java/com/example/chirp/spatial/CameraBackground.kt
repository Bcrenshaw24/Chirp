package com.example.chirp.spatial

import android.opengl.GLES11Ext
import android.opengl.GLES20.*
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only draws the ARCore camera texture. No scene framework or separate camera owner. */
internal class CameraBackground {
    private fun buffer(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    private val vertices = buffer(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
    private val uv = buffer(FloatArray(8))
    var texture = 0
        private set
    private var program = 0
    fun create() {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        texture = ids[0]
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        fun shader(type: Int, source: String): Int = glCreateShader(type).also {
            glShaderSource(it, source); glCompileShader(it)
            val ok = IntArray(1); glGetShaderiv(it, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { glGetShaderInfoLog(it) }
        }
        val vs = shader(GL_VERTEX_SHADER, "attribute vec2 p; attribute vec2 t; varying vec2 uv; void main(){ gl_Position=vec4(p,0.,1.); uv=t; }")
        val fs = shader(GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES camera; varying vec2 uv; void main(){ gl_FragColor=texture2D(camera,uv); }")
        program = glCreateProgram()
        glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program)
        val ok = IntArray(1); glGetProgramiv(program, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { glGetProgramInfoLog(program) }
        glDeleteShader(vs); glDeleteShader(fs)
    }
    fun draw(frame: Frame) {
        vertices.position(0); uv.position(0)
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, vertices,
            Coordinates2d.TEXTURE_NORMALIZED, uv)
        if (frame.timestamp == 0L) return
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        glUniform1i(glGetUniformLocation(program, "camera"), 0)
        val p = glGetAttribLocation(program, "p"); val t = glGetAttribLocation(program, "t")
        glEnableVertexAttribArray(p); glEnableVertexAttribArray(t)
        vertices.position(0); uv.position(0)
        glVertexAttribPointer(p, 2, GL_FLOAT, false, 0, vertices)
        glVertexAttribPointer(t, 2, GL_FLOAT, false, 0, uv)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(p); glDisableVertexAttribArray(t)
    }
}
