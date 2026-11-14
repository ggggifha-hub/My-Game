package com.kocti.crystaldefense.pet.glb

import android.opengl.GLES20
import android.opengl.Matrix
import com.kocti.crystaldefense.render.createProgram
import com.kocti.crystaldefense.render.floatBufferOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

private const val MAX_JOINTS = 50

/** Модель, готовая к показу: буферы вершин, текущая анимация и приведение к высоте 1. */
class SkinnedInstance(model: GlbModel, idleClip: String) {
    val animator = SkeletonAnimator(model)
    internal val parts = model.primitives.map(::GpuPrimitive)

    /** Ноги стоят на y = 0, центр модели на оси, высота равна единице. */
    val normalize = FloatArray(16)

    init {
        require(model.jointNodes.size <= MAX_JOINTS) { "Too many joints: ${model.jointNodes.size}" }
        animator.play(idleClip)
        animator.update(0f)
        val bounds = animator.computeBounds()
        val scale = 1f / (bounds[4] - bounds[1])
        Matrix.setIdentityM(normalize, 0)
        Matrix.scaleM(normalize, 0, scale, scale, scale)
        Matrix.translateM(normalize, 0, -(bounds[0] + bounds[3]) / 2f, -bounds[1], -(bounds[2] + bounds[5]) / 2f)
    }

    internal class GpuPrimitive(primitive: GlbPrimitive) {
        val positions: FloatBuffer = floatBufferOf(primitive.positions)
        val normals: FloatBuffer = floatBufferOf(primitive.normals)
        val joints: FloatBuffer = floatBufferOf(primitive.joints)
        val weights: FloatBuffer = floatBufferOf(primitive.weights)
        val color = primitive.color
        val indexCount = primitive.indices.size
        val indices: IntBuffer = ByteBuffer.allocateDirect(primitive.indices.size * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
            .apply {
                put(primitive.indices)
                position(0)
            }
    }
}

/** Рисует модели со скелетом. Кости применяются в вершинном шейдере, поэтому анимация почти не нагружает процессор. */
class SkinnedRenderer {
    private var program = 0
    private var aPosition = 0
    private var aNormal = 0
    private var aJoints = 0
    private var aWeights = 0
    private var uMvp = 0
    private var uModel = 0
    private var uJoints = 0
    private var uColor = 0
    private var uLight = 0
    private val mvp = FloatArray(16)

    fun createOnGlThread() {
        program = createProgram(
            """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            uniform mat4 uJoints[$MAX_JOINTS];
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec4 aJoints;
            attribute vec4 aWeights;
            varying vec3 vNormal;
            void main() {
                mat4 skin = aWeights.x * uJoints[int(aJoints.x)]
                    + aWeights.y * uJoints[int(aJoints.y)]
                    + aWeights.z * uJoints[int(aJoints.z)]
                    + aWeights.w * uJoints[int(aJoints.w)];
                vec4 skinned = skin * vec4(aPosition, 1.0);
                vNormal = (uModel * skin * vec4(aNormal, 0.0)).xyz;
                gl_Position = uMvp * skinned;
            }
            """.trimIndent(),
            """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uLight;
            varying vec3 vNormal;
            void main() {
                vec3 normal = normalize(vNormal);
                float diffuse = max(dot(normal, normalize(vec3(0.3, 1.0, 0.2))), 0.0);
                float fill = max(dot(normal, normalize(vec3(-0.4, 0.2, 0.8))), 0.0);
                float lighting = (0.62 + 0.55 * diffuse + 0.25 * fill) * uLight;
                gl_FragColor = vec4(uColor * lighting, 1.0);
            }
            """.trimIndent(),
        )
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aJoints = GLES20.glGetAttribLocation(program, "aJoints")
        aWeights = GLES20.glGetAttribLocation(program, "aWeights")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uJoints = GLES20.glGetUniformLocation(program, "uJoints")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
    }

    fun draw(instance: SkinnedInstance, model: FloatArray, viewProjection: FloatArray, light: Float) {
        GLES20.glUseProgram(program)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform1f(uLight, light)
        val joints = instance.animator.jointMatrices
        GLES20.glUniformMatrix4fv(uJoints, joints.size / 16, false, joints, 0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glEnableVertexAttribArray(aJoints)
        GLES20.glEnableVertexAttribArray(aWeights)
        for (part in instance.parts) {
            GLES20.glUniform3f(uColor, part.color[0], part.color[1], part.color[2])
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, part.positions)
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 0, part.normals)
            GLES20.glVertexAttribPointer(aJoints, 4, GLES20.GL_FLOAT, false, 0, part.joints)
            GLES20.glVertexAttribPointer(aWeights, 4, GLES20.GL_FLOAT, false, 0, part.weights)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, part.indexCount, GLES20.GL_UNSIGNED_INT, part.indices)
        }
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glDisableVertexAttribArray(aJoints)
        GLES20.glDisableVertexAttribArray(aWeights)
    }
}
