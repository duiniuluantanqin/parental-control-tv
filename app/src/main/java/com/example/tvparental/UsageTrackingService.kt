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
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class UsageTrackingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastSample = 0L
    private var lastRemoteSync = 0L
    private var remoteSyncInProgress = false
    private var wasInteractive = false
    private var receiverRegistered = false
    private var lastEnforcementAction: String? = null
    private var reminderView: LinearLayout? = null
    private var reminderText: TextView? = null
    private var reminderWindowManager: WindowManager? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            accountElapsedTime()
            wasInteractive = intent?.action == Intent.ACTION_SCREEN_ON
            updateEnforcement()
            maybeShowUsageReminder()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            accountElapsedTime()
            wasInteractive = powerManager().isInteractive
            updateEnforcement()
            maybeShowUsageReminder()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        syncRemoteIfDue(force = true)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        hideUsageReminder()
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
            !ControlState.isUsageWindowAllowed(this) -> QuestionOverlayService.ACTION_USAGE_WINDOW
            else -> null
        }
        if (action == lastEnforcementAction) return
        if (action != null) {
            hideUsageReminder()
            startQuestionService(action)
        } else if (lastEnforcementAction != null) {
            startQuestionService(QuestionOverlayService.ACTION_REFRESH_STATE)
        }
        lastEnforcementAction = action
    }

    private fun updateNotification() {
        val notification = buildNotification(
            "Used today: ${ControlState.todayUsageLabel(this)} / ${ControlState.todayDailyLimitMinutes(this)} min",
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun nextSampleDelay(): Long {
        if (!wasInteractive || ControlState.lockoutUntil(this) > System.currentTimeMillis()) {
            return SAMPLE_INTERVAL_MS
        }
        val remaining = ControlState.remainingDailyMillis(this)
        val quotaDelay = if (remaining > 0L) remaining.coerceAtLeast(100L) else SAMPLE_INTERVAL_MS
        val windowDelay = ControlState.millisUntilNextUsageWindowBoundary(this) ?: SAMPLE_INTERVAL_MS
        val reminderDelay = if (canShowUsageReminder()) {
            when {
                remaining > TEN_MINUTE_THRESHOLD_MS -> remaining - TEN_MINUTE_THRESHOLD_MS
                remaining <= ONE_MINUTE_THRESHOLD_MS -> ONE_SECOND_MS
                else -> {
                    val displayedMinutes = (remaining + MILLIS_PER_MINUTE - 1L) / MILLIS_PER_MINUTE
                    remaining - (displayedMinutes - 1L) * MILLIS_PER_MINUTE
                }
            }.coerceAtLeast(100L)
        } else {
            SAMPLE_INTERVAL_MS
        }
        return minOf(SAMPLE_INTERVAL_MS, quotaDelay, windowDelay.coerceAtLeast(100L), reminderDelay)
    }

    private fun maybeShowUsageReminder() {
        val remaining = ControlState.remainingDailyMillis(this)
        if (!canShowUsageReminder() || remaining > TEN_MINUTE_THRESHOLD_MS) {
            hideUsageReminder()
            return
        }

        val view = reminderView ?: createUsageReminder().also { reminder ->
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(28)
                y = dp(28)
            }
            runCatching { manager.addView(reminder, params) }.onSuccess {
                reminderView = reminder
                reminderWindowManager = manager
            }
            reminder
        }
        if (reminderView !== view) return
        val text = if (remaining <= ONE_MINUTE_THRESHOLD_MS) {
            val seconds = (remaining + ONE_SECOND_MS - 1L) / ONE_SECOND_MS
            "${seconds}s"
        } else {
            val minutes = (remaining + MILLIS_PER_MINUTE - 1L) / MILLIS_PER_MINUTE
            "${minutes}m"
        }
        reminderText?.text = text
    }

    private fun canShowUsageReminder(): Boolean =
        wasInteractive && !ControlState.isDailyLimitReached(this) &&
            ControlState.lockoutUntil(this) <= System.currentTimeMillis() &&
            ControlState.isUsageWindowAllowed(this) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))

    private fun createUsageReminder() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val icon = ImageView(this@UsageTrackingService).apply {
            setImageResource(R.drawable.ic_timer)
            setColorFilter(Color.WHITE)
            alpha = REMINDER_ALPHA
        }
        addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)))
        val label = TextView(this@UsageTrackingService).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            alpha = REMINDER_ALPHA
            setPadding(dp(5), 0, 0, 0)
        }
        addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        reminderText = label
    }

    private fun hideUsageReminder() {
        reminderView?.let { view -> runCatching { reminderWindowManager?.removeViewImmediate(view) } }
        reminderView = null
        reminderText = null
        reminderWindowManager = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun syncRemoteIfDue(force: Boolean = false) {
        if (remoteSyncInProgress) return
        val now = SystemClock.elapsedRealtime()
        val intervalMs = ControlState.settings(this).syncIntervalMinutes * 60_000L
        if (!force && now - lastRemoteSync < intervalMs) return
        lastRemoteSync = now
        remoteSyncInProgress = true
        Thread {
            val result = RemoteSync.sync(applicationContext)
            handler.post {
                remoteSyncInProgress = false
                if (result.success) {
                    if (result.settingsChanged) QuestionAlarmReceiver.scheduleForCurrentState(applicationContext)
                    updateEnforcement()
                    updateNotification()
                }
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
                "Screen time controls",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String = "Tracking today's screen time"): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen time controls")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Screen time controls")
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
        private const val TEN_MINUTE_THRESHOLD_MS = ControlState.TEN_MINUTE_REMINDER_MINUTES * 60_000L
        private const val ONE_MINUTE_THRESHOLD_MS = ControlState.ONE_MINUTE_REMINDER_MINUTES * 60_000L
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val ONE_SECOND_MS = 1_000L
        private const val REMINDER_ALPHA = 0.68f
    }
}
