package com.example.tvparental

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

class UsageTrackingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastSample = 0L
    private var lastRemoteSync = 0L
    private var wasInteractive = false
    private var receiverRegistered = false
    private var lastEnforcementAction: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            accountElapsedTime()
            wasInteractive = intent?.action == Intent.ACTION_SCREEN_ON
            updateEnforcement()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            accountElapsedTime()
            wasInteractive = powerManager().isInteractive
            updateEnforcement()
            updateNotification()
            syncRemoteIfDue()
            handler.postDelayed(this, nextSampleDelay())
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        wasInteractive = powerManager().isInteractive
        lastSample = SystemClock.elapsedRealtime()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
        receiverRegistered = true
        updateEnforcement()
        updateNotification()
        handler.post(ticker)
        syncRemoteIfDue(force = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun accountElapsedTime() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - lastSample).coerceAtLeast(0L)
        if (wasInteractive && ControlState.lockoutUntil(this) <= System.currentTimeMillis()) {
            ControlState.addScreenTime(this, elapsed)
        }
        lastSample = now
    }

    private fun updateEnforcement() {
        val action = when {
            ControlState.isDailyLimitReached(this) -> QuestionOverlayService.ACTION_DAILY_LIMIT
            ControlState.lockoutUntil(this) > System.currentTimeMillis() -> QuestionOverlayService.ACTION_LOCKOUT
            else -> null
        }
        if (action == lastEnforcementAction) return
        if (action != null) {
            startQuestionService(action)
        } else if (lastEnforcementAction != null) {
            startQuestionService(QuestionOverlayService.ACTION_REFRESH_STATE)
        }
        lastEnforcementAction = action
    }

    private fun updateNotification() {
        val settings = ControlState.settings(this)
        val notification = buildNotification(
            "今日已用 ${ControlState.todayUsageLabel(this)} / ${settings.dailyLimitMinutes} 分钟",
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun nextSampleDelay(): Long {
        if (!wasInteractive || ControlState.lockoutUntil(this) > System.currentTimeMillis()) {
            return SAMPLE_INTERVAL_MS
        }
        val limitMillis = ControlState.settings(this).dailyLimitMinutes * 60_000L
        val remaining = limitMillis - ControlState.usedMillis(this)
        return if (remaining > 0L) minOf(SAMPLE_INTERVAL_MS, remaining).coerceAtLeast(100L) else SAMPLE_INTERVAL_MS
    }

    private fun syncRemoteIfDue(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val intervalMs = ControlState.settings(this).syncIntervalMinutes * 60_000L
        if (!force && now - lastRemoteSync < intervalMs) return
        lastRemoteSync = now
        Thread {
            val result = RemoteSync.sync(applicationContext)
            if (result.success) handler.post {
                if (result.settingsChanged) QuestionAlarmReceiver.scheduleForCurrentState(applicationContext)
                updateEnforcement()
                updateNotification()
            }
        }.start()
    }

    private fun startQuestionService(action: String) {
        val intent = Intent(this, QuestionOverlayService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun powerManager() = getSystemService(POWER_SERVICE) as PowerManager

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "电视时长看护",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String = "正在记录今日电视使用时长"): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("电视时长看护")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("电视时长看护")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        const val ACTION_START = "com.example.tvparental.action.START_USAGE_TRACKING"
        private const val CHANNEL_ID = "usage_tracking"
        private const val NOTIFICATION_ID = 1002
        private const val SAMPLE_INTERVAL_MS = 30_000L
    }
}
