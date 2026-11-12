package com.example.tvparental

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED && action != Intent.ACTION_BOOT_COMPLETED) return

        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            QuestionAlarmReceiver.scheduleAfter(context, 10 * 60_000L)
            Log.i(TAG, "Received $action; fallback question alarm scheduled")
            return
        }

        QuestionBank.finishForReboot(context)
        QuestionAlarmReceiver.scheduleNext(context)
        val trackingIntent = Intent(context, UsageTrackingService::class.java)
            .setAction(UsageTrackingService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(trackingIntent)
        } else {
            context.startService(trackingIntent)
        }
        Log.i(TAG, "Received $action; question alarm and usage tracking started")

        if (Settings.canDrawOverlays(context) && ControlState.claimStartupQuestion(context)) {
            val questionIntent = Intent(context, QuestionOverlayService::class.java)
                .setAction(QuestionOverlayService.ACTION_SHOW_QUESTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(questionIntent)
            } else {
                context.startService(questionIntent)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(context)
        ) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    companion object {
        private const val TAG = "TvParentalBoot"
    }
}
