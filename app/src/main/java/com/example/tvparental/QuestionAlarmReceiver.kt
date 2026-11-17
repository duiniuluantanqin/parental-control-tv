package com.example.tvparental

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

class QuestionAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        alarmPreferences(context)
            .edit()
            .remove(ALARM_AT_KEY)
            .apply()
        val now = System.currentTimeMillis()
        when {
            ControlState.isDailyLimitReached(context) -> {
                startOverlay(context, QuestionOverlayService.ACTION_DAILY_LIMIT)
                scheduleAfter(context, ControlState.millisUntilNextDay())
            }

            ControlState.lockoutUntil(context) > now -> {
                startOverlay(context, QuestionOverlayService.ACTION_LOCKOUT)
                scheduleAfter(context, ControlState.lockoutUntil(context) - now)
            }

            !ControlState.isUsageWindowAllowed(context) -> {
                startOverlay(context, QuestionOverlayService.ACTION_USAGE_WINDOW)
                scheduleAfter(context, ControlState.millisUntilNextAllowedUsageWindow(context))
            }

            else -> startOverlay(context, QuestionOverlayService.ACTION_SHOW_QUESTION)
        }
    }

    companion object {
        const val REQUEST_CODE = 4107
        private const val ALARM_PREFERENCES = "question_alarm"
        private const val ALARM_AT_KEY = "alarm_at"

        fun scheduleNext(context: Context) {
            val interval = ControlState.settings(context).questionIntervalMinutes * MILLIS_PER_MINUTE
            scheduleAfter(context, interval)
        }

        fun ensureScheduled(context: Context) {
            val scheduledAt = alarmPreferences(context)
                .getLong(ALARM_AT_KEY, 0L)
            when {
                scheduledAt <= 0L -> scheduleNext(context)
                scheduledAt <= System.currentTimeMillis() -> scheduleAfter(context, 1_000L)
            }
        }

        fun restoreScheduled(context: Context) {
            val scheduledAt = alarmPreferences(context).getLong(ALARM_AT_KEY, 0L)
            if (scheduledAt > 0L) {
                scheduleAfter(context, scheduledAt - System.currentTimeMillis())
            } else {
                scheduleForCurrentState(context)
            }
        }

        fun scheduleForCurrentState(context: Context) {
            val now = System.currentTimeMillis()
            when {
                ControlState.isDailyLimitReached(context) -> scheduleAfter(context, ControlState.millisUntilNextDay())
                ControlState.lockoutUntil(context) > now -> scheduleAfter(context, ControlState.lockoutUntil(context) - now)
                !ControlState.isUsageWindowAllowed(context) -> scheduleAfter(context, ControlState.millisUntilNextAllowedUsageWindow(context))
                else -> scheduleNext(context)
            }
        }

        fun scheduleAfter(context: Context, delayMillis: Long) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val delay = delayMillis.coerceAtLeast(1_000L)
            val alarmIntent = Intent(context, QuestionAlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
            )
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay,
                pendingIntent,
            )
            alarmPreferences(context)
                .edit()
                .putLong(ALARM_AT_KEY, System.currentTimeMillis() + delay)
                .apply()
        }

        private fun alarmPreferences(context: Context) =
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(ALARM_PREFERENCES, Context.MODE_PRIVATE)

        private fun startOverlay(context: Context, action: String) {
            val serviceIntent = Intent(context, QuestionOverlayService::class.java).setAction(action)
                .putExtra(QuestionOverlayService.EXTRA_TRIGGER, QuestionBank.TRIGGER_INTERVAL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        private fun immutableFlag(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
