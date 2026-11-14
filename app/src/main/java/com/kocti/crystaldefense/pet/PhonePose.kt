package com.kocti.crystaldefense.pet

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Наклон и поворот телефона по датчикам. У телефонов без гироскопа работает один акселерометр:
 * он видит наклон, но не поворот вокруг себя, поэтому существо не уходит из кадра при развороте.
 */
class PhonePose(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotation = UPRIGHT_PHONE.copyOf()
    private val next = FloatArray(9)
    private val gravity = FloatArray(3)
    private var hasGravity = false

    fun start() {
        hasGravity = false
        if (sensor != null) manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    /** Вызывается из потока рендера. */
    fun read(out: FloatArray) {
        synchronized(rotation) { rotation.copyInto(out) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val ready = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            rotationFromGravity(event.values)
        } else {
            SensorManager.getRotationMatrixFromVector(next, event.values)
            true
        }
        if (ready) synchronized(rotation) { next.copyInto(rotation) }
    }

    private fun rotationFromGravity(values: FloatArray): Boolean {
        for (i in 0..2) gravity[i] = if (hasGravity) gravity[i] + (values[i] - gravity[i]) * GRAVITY_SMOOTHING else values[i]
        hasGravity = true
        return SensorManager.getRotationMatrix(next, null, gravity, FIXED_NORTH)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private companion object {
        const val GRAVITY_SMOOTHING = 0.15f

        /** Подставляется вместо компаса: это направление не совпадает с вертикалью при обычном положении в руке. */
        val FIXED_NORTH = floatArrayOf(0f, 1f, -1f)
    }
}
