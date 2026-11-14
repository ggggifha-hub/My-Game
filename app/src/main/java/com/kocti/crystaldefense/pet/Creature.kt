package com.kocti.crystaldefense.pet

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

private const val BASE_HEIGHT = 0.16f
private const val GROWTH_PER_LEVEL = 0.2f
private const val MAX_HEIGHT = 0.8f
private const val MAX_SPEED = 1.2f
private const val FOLLOW_GAIN = 2.2f
private const val STOP_DISTANCE = 0.05f
private const val SNAP_DISTANCE = 4f
private const val MAX_STEP = 0.1f
private const val TURN_DEGREES_PER_SECOND = 300f

/** Высота существа в метрах. Предел нужен, чтобы оно не перекрыло весь экран после долгих месяцев ходьбы. */
fun creatureHeightMeters(level: Int): Float = min(BASE_HEIGHT * (1f + GROWTH_PER_LEVEL * level), MAX_HEIGHT)

/** Движение существа по полу. Оно плавно догоняет точку рядом с хозяином. */
class Creature {
    var x = 0f
        private set
    var z = 0f
        private set
    var yawDegrees = 0f
        private set
    var speed = 0f
        private set
    var placed = false
        private set

    fun update(deltaSeconds: Float, targetX: Float, targetZ: Float) {
        val dt = min(deltaSeconds, MAX_STEP)
        val dx = targetX - x
        val dz = targetZ - z
        val distance = hypot(dx, dz)
        if (!placed || distance > SNAP_DISTANCE) {
            // Хозяин ушёл далеко, пока камера не следила: существо просто появляется рядом.
            x = targetX
            z = targetZ
            placed = true
            speed = 0f
            return
        }
        if (distance <= STOP_DISTANCE) {
            speed = 0f
            return
        }
        speed = min(MAX_SPEED, distance * FOLLOW_GAIN)
        val step = min(distance, speed * dt)
        x += dx / distance * step
        z += dz / distance * step
        turnTowards(Math.toDegrees(atan2(dx, dz).toDouble()).toFloat(), dt)
    }

    private fun turnTowards(targetDegrees: Float, dt: Float) {
        var difference = (targetDegrees - yawDegrees) % 360f
        if (difference > 180f) difference -= 360f
        if (difference < -180f) difference += 360f
        val maxTurn = TURN_DEGREES_PER_SECOND * dt
        yawDegrees += difference.coerceIn(-maxTurn, maxTurn)
    }
}
