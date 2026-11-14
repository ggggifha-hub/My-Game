package com.kocti.crystaldefense.pet

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.kocti.crystaldefense.PetActivity
import com.kocti.crystaldefense.R
import java.time.ZonedDateTime

/** Ближайшие 21:00 по времени телефона. */
fun nextBedtime(now: ZonedDateTime): ZonedDateTime {
    val today = now.withHour(BEDTIME_HOUR).withMinute(0).withSecond(0).withNano(0)
    return if (today.isAfter(now)) today else today.plusDays(1)
}

/** Вечернее напоминание уложить существо спать и кнопка «Уложить спать» в нём. */
class BedtimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PUT_TO_SLEEP -> {
                StepStore(context).putToSleep()
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }
            ACTION_BEDTIME -> {
                schedule(context)
                val store = StepStore(context)
                if (!store.load().asleep) notify(context, store.selectedSpecies())
            }
            // После перезагрузки телефона будильники пропадают.
            Intent.ACTION_BOOT_COMPLETED -> schedule(context)
        }
    }

    companion object {
        private const val ACTION_BEDTIME = "com.kocti.crystaldefense.BEDTIME"
        private const val ACTION_PUT_TO_SLEEP = "com.kocti.crystaldefense.PUT_TO_SLEEP"
        private const val CHANNEL_ID = "bedtime"
        private const val NOTIFICATION_ID = 21
        private const val ALARM_WINDOW_MILLIS = 10 * 60 * 1000L

        private fun broadcast(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, BedtimeReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Повторный вызов заменяет прежний будильник. Точное время требует отдельного разрешения от игрока, поэтому система может сдвинуть сигнал на минуты. */
        fun schedule(context: Context) {
            context.getSystemService(AlarmManager::class.java).setWindow(
                AlarmManager.RTC_WAKEUP,
                nextBedtime(ZonedDateTime.now()).toInstant().toEpochMilli(),
                ALARM_WINDOW_MILLIS,
                broadcast(context, ACTION_BEDTIME),
            )
        }

        fun notify(context: Context, species: Species) {
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            if (needsPermission && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.bedtime_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, PetActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val sleepAction = android.app.Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_sleep),
                context.getString(R.string.pet_put_to_sleep),
                broadcast(context, ACTION_PUT_TO_SLEEP),
            ).build()
            manager.notify(
                NOTIFICATION_ID,
                android.app.Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_sleep)
                    .setContentTitle(context.getString(R.string.bedtime_title, species.title))
                    .setContentText(context.getString(R.string.bedtime_text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .addAction(sleepAction)
                    .build(),
            )
        }
    }
}
