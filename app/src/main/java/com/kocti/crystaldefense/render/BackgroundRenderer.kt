package com.kocti.crystaldefense.render

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.FloatBuffer

/** Рисует изображение с камеры на весь экран. */
class BackgroundRenderer {
    var textureId = 0
        private set

    private var program = 0
    private var positionAttribute = 0
    private var uvAttribute = 0
    private val quad: FloatBuffer = floatBufferOf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uvs: FloatBuffer = floatBufferOf(FloatArray(8))

    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        program = createProgram(
            """
            attribute vec2 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vUv;
            void main() {
                gl_FragColor = texture2D(uTexture, vUv);
            }
            """.trimIndent(),
        )
        positionAttribute = GLES20.glGetAttribLocation(program, "aPosition")
        uvAttribute = GLES20.glGetAttribLocation(program, "aUv")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quad.position(0)
            uvs.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quad,
                Coordinates2d.TEXTURE_NORMALIZED,
                uvs,
            )
            quad.position(0)
            uvs.position(0)
        }
        // Пока камера не выдала кадр, текстура пуста.
        if (frame.timestamp == 0L) return
        drawQuad()
    }

    /** Кадр обычной камеры без ARCore: [transform] пришёл от SurfaceTexture, а масштаб обрезает лишнее по краям. */
    fun drawCamera(transform: FloatArray, scaleX: Float, scaleY: Float) {
        val point = FloatArray(4)
        val result = FloatArray(4)
        for (corner in 0..3) {
            point[0] = 0.5f + ((corner and 1) - 0.5f) * scaleX
            point[1] = 0.5f + ((corner shr 1) - 0.5f) * scaleY
            point[2] = 0f
            point[3] = 1f
            Matrix.multiplyMV(result, 0, transform, 0, point, 0)
            uvs.put(corner * 2, result[0])
            uvs.put(corner * 2 + 1, result[1])
        }
        drawQuad()
    }

    private fun drawQuad() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glEnableVertexAttribArray(uvAttribute)
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(uvAttribute, 2, GLES20.GL_FLOAT, false, 0, uvs)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(uvAttribute)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
}
