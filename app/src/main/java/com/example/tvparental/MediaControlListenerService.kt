package com.example.tvparental

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.util.Log
import java.io.FileDescriptor
import java.io.PrintWriter

/** System rebinds this approved listener after a process death on the target TCL firmware. */
class MediaControlListenerService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var retry = 0
    private var watchingUnlock = false
    private var lastRecovery = "not attempted"
    private val recover = object : Runnable {
        override fun run() {
            if (getSystemService(UserManager::class.java).isUserUnlocked) {
                runCatching { restoreControls() }
                    .onFailure { Log.w(TAG, "Could not restore parental controls", it) }
            }
            if (retry < RETRY_DELAYS.size) handler.postDelayed(this, RETRY_DELAYS[retry++])
        }
    }
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = startRecovery()
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        watchingUnlock = true
        startRecovery()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected; recovering parental controls")
        startRecovery()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebind(ComponentName(this, MediaControlListenerService::class.java))
    }

    private fun startRecovery() {
        handler.removeCallbacks(recover)
        retry = 0
        handler.post(recover)
    }

    private fun restoreControls() {
        // A force-stop removes the system alarm, but its original deadline remains on disk.
        // Re-arm that deadline instead of granting another whole question interval.
        QuestionAlarmReceiver.restoreScheduled(this)
        if (!UsageTrackingService.isRunning) {
            startForegroundService(
                Intent(this, UsageTrackingService::class.java)
                    .setAction(UsageTrackingService.ACTION_START),
            )
            Log.i(TAG, "Requested usage tracking recovery")
        }
        if (!UsageTimingPause.isPaused(this) && !QuestionOverlayService.isRunning && QuestionBank.isChallengeActive(this)) {
            startForegroundService(
                Intent(this, QuestionOverlayService::class.java)
                    .setAction(QuestionOverlayService.ACTION_SHOW_QUESTION),
            )
            Log.i(TAG, "Requested unfinished challenge recovery")
        }
        lastRecovery = "controls checked at elapsed=${android.os.SystemClock.elapsedRealtime()}"
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        writer.println("Parental recovery: $lastRecovery retry=$retry")
        writer.println("usageRunning=${UsageTrackingService.isRunning} overlayRunning=${QuestionOverlayService.isRunning}")
    }

    override fun onDestroy() {
        handler.removeCallbacks(recover)
        if (watchingUnlock) unregisterReceiver(unlocked)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "TvParentalRecovery"
        val RETRY_DELAYS = longArrayOf(5_000, 10_000, 15_000, 30_000, 60_000)
    }
}
