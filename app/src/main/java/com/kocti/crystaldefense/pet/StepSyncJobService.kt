package com.kocti.crystaldefense.pet

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit

/**
 * Периодически сохраняет показание датчика, пока приложение закрыто.
 * Без этого все шаги с прошлого запуска попали бы в один день, и рост считался бы неверно.
 */
class StepSyncJobService : JobService() {
    private val handler = Handler(Looper.getMainLooper())
    private var listener: SensorEventListener? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val manager = getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return false
        val finish = Runnable {
            stopListening()
            jobFinished(params, false)
        }
        val newListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                StepStore(this@StepSyncJobService).recordCounter(event.values[0].toLong())
                handler.removeCallbacks(finish)
                finish.run()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        if (!manager.registerListener(newListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) return false
        listener = newListener
        // Если шагов не было, датчик может промолчать: задача не должна висеть.
        handler.postDelayed(finish, READ_TIMEOUT_MILLIS)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        handler.removeCallbacksAndMessages(null)
        stopListening()
        return true
    }

    private fun stopListening() {
        listener?.let { getSystemService(SensorManager::class.java).unregisterListener(it) }
        listener = null
    }

    companion object {
        private const val JOB_ID = 1
        private const val READ_TIMEOUT_MILLIS = 10_000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, StepSyncJobService::class.java))
                    .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}
