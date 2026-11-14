package com.kocti.crystaldefense.pet.glb

import kotlin.math.sqrt

private const val FADE_SECONDS = 0.25f

/** Вычисляет матрицы костей для шейдера. Не выделяет память в кадре, поэтому анимация не вызывает пауз сборщика мусора. */
class SkeletonAnimator(val model: GlbModel) {
    private val nodeCount = model.nodes.size
    private val translations = FloatArray(nodeCount * 3)
    private val rotations = FloatArray(nodeCount * 4)
    private val scales = FloatArray(nodeCount * 3)
    private val fromTranslations = FloatArray(nodeCount * 3)
    private val fromRotations = FloatArray(nodeCount * 4)
    private val fromScales = FloatArray(nodeCount * 3)
    private val globals = FloatArray(nodeCount * 16)
    private val local = FloatArray(16)

    /** Готовые матрицы: по 16 чисел на кость, в порядке суставов модели. */
    val jointMatrices = FloatArray(model.jointNodes.size * 16)

    var clip: GlbClip? = null
        private set
    private var time = 0f
    private var fadeLeft = 0f
    private var looping = true

    /** Разовая анимация дошла до конца и держит последнюю позу. */
    val finished: Boolean get() = !looping && time >= (clip?.duration ?: 0f)

    /** [restart] запускает уже идущую анимацию заново: так существо бьёт второй раз подряд. */
    fun play(name: String, loop: Boolean = true, restart: Boolean = false) {
        val next = model.clips[name] ?: error("Unknown clip $name")
        looping = loop
        if (next === clip && !restart) return
        // Следующая анимация плавно перетекает из текущей позы, а не прыгает в начало.
        if (clip != null) {
            translations.copyInto(fromTranslations)
            rotations.copyInto(fromRotations)
            scales.copyInto(fromScales)
            fadeLeft = FADE_SECONDS
        }
        clip = next
        time = 0f
    }

    fun update(deltaSeconds: Float, speed: Float = 1f) {
        val current = clip ?: return
        time += deltaSeconds * speed
        if (!looping) {
            time = time.coerceAtMost(current.duration)
        } else if (current.duration > 0f) {
            time %= current.duration
        }
        resetToRest()
        for (channel in current.channels) applyChannel(channel, time)
        if (fadeLeft > 0f) {
            fadeLeft = (fadeLeft - deltaSeconds).coerceAtLeast(0f)
            blendFromPrevious(fadeLeft / FADE_SECONDS)
        }
        computeMatrices()
    }

    /** Границы модели в текущей позе: minX, minY, minZ, maxX, maxY, maxZ. */
    fun computeBounds(): FloatArray {
        val bounds = floatArrayOf(
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
            -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
        )
        for (primitive in model.primitives) {
            for (v in 0 until primitive.positions.size / 3) {
                val x = primitive.positions[v * 3]
                val y = primitive.positions[v * 3 + 1]
                val z = primitive.positions[v * 3 + 2]
                var sx = 0f
                var sy = 0f
                var sz = 0f
                for (k in 0..3) {
                    val weight = primitive.weights[v * 4 + k]
                    if (weight == 0f) continue
                    val m = primitive.joints[v * 4 + k].toInt() * 16
                    sx += weight * (jointMatrices[m] * x + jointMatrices[m + 4] * y + jointMatrices[m + 8] * z + jointMatrices[m + 12])
                    sy += weight * (jointMatrices[m + 1] * x + jointMatrices[m + 5] * y + jointMatrices[m + 9] * z + jointMatrices[m + 13])
                    sz += weight * (jointMatrices[m + 2] * x + jointMatrices[m + 6] * y + jointMatrices[m + 10] * z + jointMatrices[m + 14])
                }
                bounds[0] = minOf(bounds[0], sx)
                bounds[1] = minOf(bounds[1], sy)
                bounds[2] = minOf(bounds[2], sz)
                bounds[3] = maxOf(bounds[3], sx)
                bounds[4] = maxOf(bounds[4], sy)
                bounds[5] = maxOf(bounds[5], sz)
            }
        }
        return bounds
    }

    private fun resetToRest() {
        model.nodes.forEachIndexed { i, node ->
            node.translation.copyInto(translations, i * 3)
            node.rotation.copyInto(rotations, i * 4)
            node.scale.copyInto(scales, i * 3)
        }
    }

    private fun applyChannel(channel: GlbChannel, time: Float) {
        val times = channel.times
        var next = 0
        while (next < times.size && times[next] < time) next++
        val after = next.coerceAtMost(times.size - 1)
        val before = (next - 1).coerceAtLeast(0)
        val span = times[after] - times[before]
        val t = if (span > 0f) ((time - times[before]) / span).coerceIn(0f, 1f) else 0f
        when (channel.path) {
            ChannelPath.TRANSLATION -> lerp3(channel.values, before, after, t, translations, channel.node)
            ChannelPath.SCALE -> lerp3(channel.values, before, after, t, scales, channel.node)
            ChannelPath.ROTATION -> nlerp(channel.values, before * 4, channel.values, after * 4, t, rotations, channel.node * 4)
        }
    }

    private fun lerp3(values: FloatArray, a: Int, b: Int, t: Float, target: FloatArray, node: Int) {
        for (i in 0..2) {
            val from = values[a * 3 + i]
            target[node * 3 + i] = from + (values[b * 3 + i] - from) * t
        }
    }

    /** Поворот по кратчайшему пути. Шаг между ключевыми кадрами мал, поэтому точная сферическая формула не нужна. */
    private fun nlerp(a: FloatArray, aAt: Int, b: FloatArray, bAt: Int, t: Float, target: FloatArray, at: Int) {
        var dot = 0f
        for (i in 0..3) dot += a[aAt + i] * b[bAt + i]
        val sign = if (dot < 0f) -1f else 1f
        var lengthSquared = 0f
        for (i in 0..3) {
            val value = a[aAt + i] + (b[bAt + i] * sign - a[aAt + i]) * t
            target[at + i] = value
            lengthSquared += value * value
        }
        val inverse = if (lengthSquared > 0f) 1f / sqrt(lengthSquared) else 1f
        for (i in 0..3) target[at + i] *= inverse
    }

    private fun blendFromPrevious(fromWeight: Float) {
        val toWeight = 1f - fromWeight
        for (n in 0 until nodeCount) {
            for (i in 0..2) {
                translations[n * 3 + i] = fromTranslations[n * 3 + i] * fromWeight + translations[n * 3 + i] * toWeight
                scales[n * 3 + i] = fromScales[n * 3 + i] * fromWeight + scales[n * 3 + i] * toWeight
            }
            nlerp(fromRotations, n * 4, rotations, n * 4, toWeight, rotations, n * 4)
        }
    }

    private fun computeMatrices() {
        for (node in model.nodeOrder) {
            composeLocal(node)
            val parent = model.nodes[node].parent
            if (parent >= 0) {
                multiply(globals, parent * 16, local, 0, globals, node * 16)
            } else {
                local.copyInto(globals, node * 16)
            }
        }
        model.jointNodes.forEachIndexed { joint, node ->
            multiply(globals, node * 16, model.inverseBind, joint * 16, jointMatrices, joint * 16)
        }
    }

    private fun composeLocal(node: Int) {
        val x = rotations[node * 4]
        val y = rotations[node * 4 + 1]
        val z = rotations[node * 4 + 2]
        val w = rotations[node * 4 + 3]
        val sx = scales[node * 3]
        val sy = scales[node * 3 + 1]
        val sz = scales[node * 3 + 2]
        local[0] = (1f - 2f * (y * y + z * z)) * sx
        local[1] = 2f * (x * y + z * w) * sx
        local[2] = 2f * (x * z - y * w) * sx
        local[3] = 0f
        local[4] = 2f * (x * y - z * w) * sy
        local[5] = (1f - 2f * (x * x + z * z)) * sy
        local[6] = 2f * (y * z + x * w) * sy
        local[7] = 0f
        local[8] = 2f * (x * z + y * w) * sz
        local[9] = 2f * (y * z - x * w) * sz
        local[10] = (1f - 2f * (x * x + y * y)) * sz
        local[11] = 0f
        local[12] = translations[node * 3]
        local[13] = translations[node * 3 + 1]
        local[14] = translations[node * 3 + 2]
        local[15] = 1f
    }

    /** result = a * b для матриц по столбцам. Результат не должен пересекаться с аргументами. */
    private fun multiply(a: FloatArray, aAt: Int, b: FloatArray, bAt: Int, result: FloatArray, at: Int) {
        for (column in 0..3) {
            val b0 = b[bAt + column * 4]
            val b1 = b[bAt + column * 4 + 1]
            val b2 = b[bAt + column * 4 + 2]
            val b3 = b[bAt + column * 4 + 3]
            for (row in 0..3) {
                result[at + column * 4 + row] =
                    a[aAt + row] * b0 + a[aAt + 4 + row] * b1 + a[aAt + 8 + row] * b2 + a[aAt + 12 + row] * b3
            }
        }
    }
}
