package com.kocti.crystaldefense.render

import android.opengl.GLES20
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Треугольники с позициями и нормалями. Все модели строятся в коде, поэтому игре не нужны файлы ресурсов. */
class Mesh(positions: FloatArray, normals: FloatArray) {
    val vertexCount = positions.size / 3
    val positionBuffer: FloatBuffer = floatBufferOf(positions)
    val normalBuffer: FloatBuffer = floatBufferOf(normals)

    companion object {
        /** Единичная сфера с гладкими нормалями на основе икосаэдра. */
        fun sphere(subdivisions: Int): Mesh {
            val t = (1f + sqrt(5f)) / 2f
            val v = arrayOf(
                floatArrayOf(-1f, t, 0f), floatArrayOf(1f, t, 0f), floatArrayOf(-1f, -t, 0f), floatArrayOf(1f, -t, 0f),
                floatArrayOf(0f, -1f, t), floatArrayOf(0f, 1f, t), floatArrayOf(0f, -1f, -t), floatArrayOf(0f, 1f, -t),
                floatArrayOf(t, 0f, -1f), floatArrayOf(t, 0f, 1f), floatArrayOf(-t, 0f, -1f), floatArrayOf(-t, 0f, 1f),
            ).map(::unit)
            val faces = intArrayOf(
                0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11,
                1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1, 8,
                3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9,
                4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1,
            )
            var triangles = faces.toList().chunked(3).map { (a, b, c) -> listOf(v[a], v[b], v[c]) }
            repeat(subdivisions) {
                triangles = triangles.flatMap { (a, b, c) ->
                    val ab = unit(mid(a, b))
                    val bc = unit(mid(b, c))
                    val ca = unit(mid(c, a))
                    listOf(listOf(a, ab, ca), listOf(b, bc, ab), listOf(c, ca, bc), listOf(ab, bc, ca))
                }
            }
            val flat = triangles.flatten().flatMap { it.asList() }.toFloatArray()
            return Mesh(flat, flat)
        }

        /** Кристалл: вытянутый граненый бипирамидальный многогранник с основанием на y = 0. */
        fun crystal(height: Float, radius: Float, sides: Int = 6): Mesh {
            val waist = height * 0.3f
            val top = floatArrayOf(0f, height, 0f)
            val bottom = floatArrayOf(0f, 0f, 0f)
            val positions = mutableListOf<Float>()
            val normals = mutableListOf<Float>()
            for (i in 0 until sides) {
                val a = ring(i, sides, radius, waist)
                val b = ring(i + 1, sides, radius, waist)
                addFlatTriangle(positions, normals, top, b, a)
                addFlatTriangle(positions, normals, bottom, a, b)
            }
            return Mesh(positions.toFloatArray(), normals.toFloatArray())
        }

        /** Плоский диск радиуса 1 в плоскости XZ: тени под объектами. */
        fun disc(segments: Int = 24): Mesh {
            val positions = mutableListOf<Float>()
            val normals = mutableListOf<Float>()
            val center = floatArrayOf(0f, 0f, 0f)
            for (i in 0 until segments) {
                addFlatTriangle(positions, normals, center, ring(i + 1, segments, 1f, 0f), ring(i, segments, 1f, 0f))
            }
            return Mesh(positions.toFloatArray(), normals.toFloatArray())
        }

        private fun ring(index: Int, count: Int, radius: Float, y: Float): FloatArray {
            val angle = 2.0 * PI * index / count
            return floatArrayOf((cos(angle) * radius).toFloat(), y, (sin(angle) * radius).toFloat())
        }

        private fun addFlatTriangle(
            positions: MutableList<Float>,
            normals: MutableList<Float>,
            a: FloatArray,
            b: FloatArray,
            c: FloatArray,
        ) {
            val u = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
            val w = floatArrayOf(c[0] - a[0], c[1] - a[1], c[2] - a[2])
            val normal = unit(
                floatArrayOf(u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0]),
            )
            for (vertex in listOf(a, b, c)) {
                positions += vertex.asList()
                normals += normal.asList()
            }
        }

        private fun mid(a: FloatArray, b: FloatArray) =
            floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)

        private fun unit(a: FloatArray): FloatArray {
            val length = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            return floatArrayOf(a[0] / length, a[1] / length, a[2] / length)
        }
    }
}

/** Единственный шейдер для объектов: диффузный свет, освещённость от ARCore и самосвечение. */
class MeshRenderer {
    private var program = 0
    private var aPosition = 0
    private var aNormal = 0
    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0
    private var uEmissive = 0
    private var uLight = 0
    private val mvp = FloatArray(16)

    fun createOnGlThread() {
        program = createProgram(
            """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying vec3 vNormal;
            void main() {
                vNormal = (uModel * vec4(aNormal, 0.0)).xyz;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
            """.trimIndent(),
            """
            precision mediump float;
            uniform vec4 uColor;
            uniform float uEmissive;
            uniform float uLight;
            varying vec3 vNormal;
            void main() {
                vec3 normal = normalize(vNormal);
                float diffuse = max(dot(normal, normalize(vec3(0.3, 1.0, 0.2))), 0.0);
                float lighting = mix((0.35 + 0.65 * diffuse) * uLight, 1.0, uEmissive);
                gl_FragColor = vec4(uColor.rgb * lighting * uColor.a, uColor.a);
            }
            """.trimIndent(),
        )
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uEmissive = GLES20.glGetUniformLocation(program, "uEmissive")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
    }

    fun begin(light: Float) {
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uLight, light)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aNormal)
    }

    fun end() {
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aNormal)
    }

    fun draw(
        mesh: Mesh,
        model: FloatArray,
        viewProjection: FloatArray,
        r: Float,
        g: Float,
        b: Float,
        alpha: Float = 1f,
        emissive: Float = 0f,
    ) {
        android.opengl.Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform4f(uColor, r, g, b, alpha)
        GLES20.glUniform1f(uEmissive, emissive)
        mesh.positionBuffer.position(0)
        mesh.normalBuffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, mesh.positionBuffer)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 0, mesh.normalBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
    }
}
