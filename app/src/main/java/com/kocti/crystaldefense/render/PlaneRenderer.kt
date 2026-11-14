package com.kocti.crystaldefense.render

import android.opengl.GLES20
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState

/** Подсвечивает найденные поверхности, чтобы игрок видел, куда можно поставить кристалл. */
class PlaneRenderer {
    private var program = 0
    private var aPosition = 0
    private var uMvp = 0
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    fun createOnGlThread() {
        program = createProgram(
            """
            uniform mat4 uMvp;
            attribute vec2 aPosition;
            void main() {
                gl_Position = uMvp * vec4(aPosition.x, 0.0, aPosition.y, 1.0);
            }
            """.trimIndent(),
            """
            precision mediump float;
            void main() {
                gl_FragColor = vec4(0.11, 0.24, 0.27, 0.25);
            }
            """.trimIndent(),
        )
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
    }

    fun draw(planes: Collection<Plane>, viewProjection: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glDepthMask(false)
        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            val polygon = plane.polygon
            polygon.rewind()
            plane.centerPose.toMatrix(model, 0)
            android.opengl.Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, polygon)
            // ARCore гарантирует выпуклый многоугольник, поэтому хватает веера треугольников.
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, polygon.limit() / 2)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisableVertexAttribArray(aPosition)
    }
}
