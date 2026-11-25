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
import android.net.ConnectivityManager
import android.net.Network
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
import android.widget.Toast
import java.io.FileDescriptor
import java.io.PrintWriter

class UsageTrackingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val usageSamples = UsageSampleTracker()
    private var usageBindingId = ""
    private var lastRemoteSync = 0L
    private val usageReports = UsageReportSchedule()
    private var remoteSyncInProgress = false
    private var serviceStopped = false
    private var networkCallbackRegistered = false
    private val wasInteractive get() = usageSamples.interactive
    private val overlayPaused get() = usageSamples.overlayPaused
    private val timingPaused get() = usageSamples.timingPaused
    private var receiverRegistered = false
    private var lastEnforcementAction: String? = null
    private var reminderView: LinearLayout? = null
    private var reminderText: TextView? = null
    private var reminderWindowManager: WindowManager? = null
    private val usageReportRetry = Runnable { reportUsageIfDue() }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (!serviceStopped) reportUsageIfDue(force = usageReports.retryPending)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RemoteSync.ACTION_USAGE_RESET) {
                accountElapsedTime(interactive = powerManager().isInteractive)
                refreshTrackedState()
                reportUsageIfDue(force = true)
                return
            }
            if (intent?.action == RemoteSync.ACTION_SYNC_COMPLETED) {
                accountElapsedTime(interactive = powerManager().isInteractive)
                refreshTrackedState()
                return
            }
            val interactive = when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> true
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SHUTDOWN -> false
                else -> powerManager().isInteractive
            }
            if (!interactive && timingPaused) {
                // A TV power-off can be standby without a new Android boot.
                setTimingPause(paused = false, interactive = false)
            } else {
                accountElapsedTime(interactive = interactive)
            }
            if (!interactive) {
                DailyLimitUnlock.setActive(this@UsageTrackingService, false)
                ControlState.clearRewardTimeActivation(this@UsageTrackingService)
            }
            if (intent?.action == Intent.ACTION_SHUTDOWN) {
                QuestionBank.finishForReboot(this@UsageTrackingService, shuttingDown = true)
                return
            }
            updateEnforcement()
            maybeShowUsageReminder()
            reportUsageIfDue(force = intent?.action == Intent.ACTION_SCREEN_ON)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            accountElapsedTime(interactive = powerManager().isInteractive)
            updateEnforcement()
            maybeShowUsageReminder()
            updateNotification()
            syncRemoteIfDue()
            reportUsageIfDue()
            handler.postDelayed(this, nextSampleDelay())
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true
        usageBindingId = DeviceBinding.bindingId(this)
        val interactive = powerManager().isInteractive
        if (!interactive) DailyLimitUnlock.setActive(this, false)
        val storedPause = UsageTimingPause.isPaused(this)
        if (storedPause && !interactive && UsageTimingPause.setPaused(this, false)) {
            QuestionAlarmReceiver.scheduleForCurrentState(this)
        }
        usageSamples.start(
            SystemClock.elapsedRealtime(),
            interactive,
            QuestionOverlayService.isRunning && ControlState.isQuestionOverlayActive(this),
            timingPaused = interactive && storedPause,
            answering = QuestionOverlayService.isRunning && ControlState.isAnsweringActive(this),
            parentUnlocked = DailyLimitUnlock.isActive(this),
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(RemoteSync.ACTION_SYNC_COMPLETED)
            addAction(RemoteSync.ACTION_USAGE_RESET)
        }
        registerReceiver(screenReceiver, filter)
        receiverRegistered = true
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        networkCallbackRegistered = true
        updateEnforcement()
        updateNotification()
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_OVERLAY_PAUSED) {
            accountElapsedTime(
                interactive = powerManager().isInteractive,
                paused = intent.getBooleanExtra(EXTRA_OVERLAY_PAUSED, false),
                answering = intent.getBooleanExtra(EXTRA_ANSWERING, false),
            )
            refreshTrackedState()
        } else if (intent?.action == ACTION_SET_TIMING_PAUSED) {
            val interactive = powerManager().isInteractive
            val paused = interactive && intent.getBooleanExtra(EXTRA_TIMING_PAUSED, false)
            if (!setTimingPause(paused, interactive)) {
                Toast.makeText(this, "Could not change the timer state.", Toast.LENGTH_SHORT).show()
            }
            refreshTrackedState()
        } else if (intent?.action == ACTION_REFRESH_DISPLAY) {
            accountElapsedTime(interactive = powerManager().isInteractive)
            refreshTrackedState()
        } else {
            accountElapsedTime(interactive = powerManager().isInteractive)
            refreshTrackedState()
            reportUsageIfDue(force = true)
            syncRemoteIfDue(force = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceStopped = true
        accountElapsedTime(interactive = false)
        isRunning = false
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(usageReportRetry)
        hideUsageReminder()
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        if (networkCallbackRegistered) {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        writer.println("Usage tracking: running=$isRunning lastSampleElapsed=${usageSamples.lastElapsed}")
        writer.println("interactive=$wasInteractive overlayPaused=$overlayPaused timingPaused=$timingPaused")
        writer.println("usageDay=${ControlState.usageReportSnapshot(this).getString("date")} usedMillis=${ControlState.usedMillis(this)} clockDay=${DailyUsageLedger.localDay(System.currentTimeMillis())}")
    }

    private fun setTimingPause(paused: Boolean, interactive: Boolean): Boolean {
        val wasPaused = UsageTimingPause.isPaused(this)
        val saved = UsageTimingPause.setPaused(this, paused)
        val isPaused = UsageTimingPause.isPaused(this)
        accountElapsedTime(interactive = interactive, timingPaused = isPaused)
        if (wasPaused != isPaused) {
            QuestionAlarmReceiver.scheduleForCurrentState(this)
            if (isPaused && QuestionOverlayService.isRunning) {
                startQuestionService(QuestionOverlayService.ACTION_REFRESH_STATE)
            }
        }
        sendBroadcast(Intent(ACTION_TIMING_PAUSE_CHANGED).setPackage(packageName))
        return saved
    }

    private fun accountElapsedTime(
        interactive: Boolean = wasInteractive,
        paused: Boolean = overlayPaused,
        timingPaused: Boolean = this.timingPaused,
        answering: Boolean = usageSamples.answering,
    ) {
        val now = SystemClock.elapsedRealtime()
        val bindingId = DeviceBinding.bindingId(this)
        if (bindingId != usageBindingId) {
            usageBindingId = bindingId
            usageSamples.start(now, interactive, paused, timingPaused, answering, DailyLimitUnlock.isActive(this))
            return
        }
        val kind = usageSamples.trackedKind
        val duration = (now - usageSamples.lastElapsed).coerceAtLeast(0L)
        val elapsed = usageSamples.sample(now, interactive, paused, timingPaused, answering, DailyLimitUnlock.isActive(this))
        if (kind == UsageInterval.REGULAR) {
            if (elapsed > 0L && ControlState.addScreenTime(this, elapsed)) reportUsageIfDue(force = true)
        } else if (kind != null) {
            ControlState.addActivityTime(this, duration, kind)
        }
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
            if (action != QuestionOverlayService.ACTION_LOCKOUT) hideUsageReminder()
            startQuestionService(action)
        } else if (lastEnforcementAction != null) {
            startQuestionService(QuestionOverlayService.ACTION_REFRESH_STATE)
        }
        lastEnforcementAction = action
    }

    private fun updateNotification() {
        val used = "Used today: ${ControlState.todayUsageLabel(this)} / ${ControlState.todayDailyLimitMinutes(this)} min"
        val notification = buildNotification(if (timingPaused) "Timer paused · $used" else used)
        // Keep notification updates tied to foreground status, including after recovery.
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun nextSampleDelay(): Long {
        if (!wasInteractive || ControlState.lockoutUntil(this) > System.currentTimeMillis()) {
            return SAMPLE_INTERVAL_MS
        }
        val remaining = ControlState.remainingDailyMillis(this)
        val quotaDelay = if (remaining > 0L) remaining.coerceAtLeast(100L) else SAMPLE_INTERVAL_MS
        val windowDelay = ControlState.millisUntilNextUsageWindowBoundary(this) ?: SAMPLE_INTERVAL_MS
        val reminderDelay = if (canShowUsageReminder() && !DailyLimitUnlock.isActive(this)) {
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
        if (!canShowUsageReminder() ||
            (DailyLimitUnlock.isActive(this) && !timingPaused && !ControlState.alwaysShowRemainingTime(this)) ||
            (!timingPaused && !ControlState.alwaysShowRemainingTime(this) &&
                remaining > TEN_MINUTE_THRESHOLD_MS && !overlayPaused)
        ) {
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
        val text = when {
            timingPaused -> "Timer paused"
            DailyLimitUnlock.isActive(this) -> "Daily limit unlocked"
            remaining <= ONE_MINUTE_THRESHOLD_MS -> {
                val seconds = (remaining + ONE_SECOND_MS - 1L) / ONE_SECOND_MS
                "${seconds}s"
            }
            else -> {
                val minutes = (remaining + MILLIS_PER_MINUTE - 1L) / MILLIS_PER_MINUTE
                "${minutes}m"
            }
        }
        reminderText?.text = text
    }

    private fun canShowUsageReminder(): Boolean =
        wasInteractive && (overlayPaused ||
            (!ControlState.isDailyLimitReached(this) && ControlState.isUsageWindowAllowed(this))) &&
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

    private fun refreshTrackedState() {
        updateEnforcement()
        maybeShowUsageReminder()
        updateNotification()
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

    private fun reportUsageIfDue(force: Boolean = false) {
        if (serviceStopped) return
        val now = SystemClock.elapsedRealtime()
        if (!usageReports.begin(now, force)) return
        handler.removeCallbacks(usageReportRetry)
        Thread {
            val success = RemoteSync.reportUsage(applicationContext)
            handler.post {
                if (serviceStopped) return@post
                val completedAt = SystemClock.elapsedRealtime()
                usageReports.complete(completedAt, success)
                usageReports.followUpDelay(completedAt)?.let { delay ->
                    handler.postDelayed(usageReportRetry, delay)
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
        var isRunning = false
            private set

        const val ACTION_START = "com.example.tvparental.action.START_USAGE_TRACKING"
        const val ACTION_REFRESH_DISPLAY = "com.example.tvparental.action.REFRESH_USAGE_DISPLAY"
        const val ACTION_SET_OVERLAY_PAUSED = "com.example.tvparental.action.SET_OVERLAY_PAUSED"
        const val EXTRA_OVERLAY_PAUSED = "overlay_paused"
        const val EXTRA_ANSWERING = "answering"
        const val ACTION_SET_TIMING_PAUSED = "com.example.tvparental.action.SET_TIMING_PAUSED"
        const val ACTION_TIMING_PAUSE_CHANGED = "com.example.tvparental.action.TIMING_PAUSE_CHANGED"
        const val EXTRA_TIMING_PAUSED = "timing_paused"
        private const val CHANNEL_ID = "usage_tracking"
        private const val NOTIFICATION_ID = 1002
        private const val SAMPLE_INTERVAL_MS = 10_000L
        private const val TEN_MINUTE_THRESHOLD_MS = ControlState.TEN_MINUTE_REMINDER_MINUTES * 60_000L
        private const val ONE_MINUTE_THRESHOLD_MS = ControlState.ONE_MINUTE_REMINDER_MINUTES * 60_000L
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val ONE_SECOND_MS = 1_000L
        private const val REMINDER_ALPHA = 0.68f
    }
}
