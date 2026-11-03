package com.example.tvparental

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.roundToInt

class QuestionOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var currentMode = MODE_NONE
    private var lockCountdown: TextView? = null
    private var answerSubmitted = false

    private val lockTicker = object : Runnable {
        override fun run() {
            updateLockCountdown()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DAILY_LIMIT -> showDailyLock()
            ACTION_LOCKOUT -> showLockout()
            ACTION_REFRESH_STATE -> refreshBlockingState()
            ACTION_HIDE -> hideOverlay()
            else -> showQuestion()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(lockTicker)
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showQuestion() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        when {
            ControlState.isDailyLimitReached(this) -> return showDailyLock()
            ControlState.lockoutUntil(this) > System.currentTimeMillis() -> return showLockout()
            currentMode == MODE_QUESTION && overlayView != null -> return
        }
        ControlState.clearWrongAnswerLock(this)
        handler.removeCallbacks(lockTicker)
        removeOverlay()
        currentMode = MODE_QUESTION
        answerSubmitted = false

        val question = QuestionBank.currentOrNext(this) ?: run {
            RemoteSync.syncAsync(applicationContext) { result ->
                if (result.settingsChanged) QuestionAlarmReceiver.scheduleForCurrentState(applicationContext)
            }
            QuestionAlarmReceiver.scheduleNext(this)
            hideOverlay()
            return
        }
        val optionTexts = question.options.shuffled()
        val optionViews = mutableListOf<TextView>()
        var selectedIndex = 0
        lateinit var moveFocus: (Int, Int) -> Unit

        val root = createRoot()
        val title = TextView(this).apply {
            text = "看电视前答题  ·  ${question.category}"
            setTextColor(Color.rgb(193, 203, 194))
            textSize = 20f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        root.addView(title, matchParams())

        val prompt = TextView(this).apply {
            text = question.prompt
            setTextColor(Color.WHITE)
            textSize = 34f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }
        root.addView(prompt, weightedParams(1f))

        val grid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
        }
        optionTexts.forEachIndexed { index, text ->
            val option = TextView(this).apply {
                this.text = text
                textSize = 24f
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(dp(18), dp(12), dp(18), dp(12))
                isFocusable = true
                isFocusableInTouchMode = true
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        selectedIndex = index
                        renderOptionSelection(optionViews, selectedIndex)
                    }
                }
                setOnKeyListener { _, keyCode, event ->
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (event.action == KeyEvent.ACTION_DOWN) moveFocus(index, keyCode)
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_BUTTON_A -> {
                            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                submitAnswer(question, optionTexts[selectedIndex], root)
                            }
                            true
                        }

                        KeyEvent.KEYCODE_BACK -> true
                        else -> false
                    }
                }
            }
            optionViews += option
            grid.addView(
                option,
                GridLayout.LayoutParams(
                    GridLayout.spec(index / 2, 1f),
                    GridLayout.spec(index % 2, 1f),
                ).apply {
                    width = 0
                    height = 0
                    setMargins(dp(10), dp(10), dp(10), dp(10))
                },
            )
        }
        root.addView(grid, weightedParams(2.2f))

        val status = TextView(this).apply {
            text = "答错后将锁定电视一段时间"
            setTextColor(Color.rgb(205, 214, 206))
            textSize = 18f
            gravity = Gravity.CENTER
        }
        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)))

        moveFocus = { index, keyCode ->
            val nextIndex = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (index % 2 == 1) index - 1 else index
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (index % 2 == 0) index + 1 else index
                KeyEvent.KEYCODE_DPAD_UP -> if (index >= 2) index - 2 else index
                KeyEvent.KEYCODE_DPAD_DOWN -> if (index < 2) index + 2 else index
                else -> index
            }
            selectedIndex = nextIndex
            optionViews[nextIndex].requestFocus()
            renderOptionSelection(optionViews, selectedIndex)
        }
        renderOptionSelection(optionViews, selectedIndex)
        attachOverlay(root)
        root.post { optionViews.first().requestFocus() }
    }

    private fun submitAnswer(question: TvQuestion, selected: String, root: View) {
        if (answerSubmitted) return
        answerSubmitted = true
        if (selected == question.answer) {
            QuestionBank.markCorrect(this, question)
            RemoteSync.syncAsync(applicationContext) { result ->
                if (result.settingsChanged) QuestionAlarmReceiver.scheduleForCurrentState(applicationContext)
            }
            if (ControlState.isDailyLimitReached(this)) {
                showDailyLock()
                return
            }
            (root as? LinearLayout)?.let { layout ->
                (layout.getChildAt(layout.childCount - 1) as? TextView)?.apply {
                    text = "回答正确"
                    setTextColor(Color.rgb(185, 222, 119))
                }
            }
            root.postDelayed({
                if (ControlState.isDailyLimitReached(this)) {
                    showDailyLock()
                } else {
                    ControlState.clearWrongAnswerLock(this)
                    QuestionAlarmReceiver.scheduleNext(this)
                    hideOverlay()
                }
            }, 450)
        } else {
            val until = ControlState.startWrongAnswerLock(this)
            QuestionBank.finishCurrent(this)
            QuestionAlarmReceiver.scheduleAfter(this, until - System.currentTimeMillis())
            showLockout()
        }
    }

    private fun showDailyLock() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        if (!ControlState.isDailyLimitReached(this)) return refreshBlockingState()
        renderLock(MODE_DAILY_LOCK)
    }

    private fun showLockout() {
        if (ControlState.isDailyLimitReached(this)) return showDailyLock()
        if (ControlState.lockoutUntil(this) <= System.currentTimeMillis()) {
            ControlState.clearWrongAnswerLock(this)
            return refreshBlockingState()
        }
        renderLock(MODE_WRONG_LOCK)
    }

    private fun renderLock(mode: Int) {
        if (currentMode == mode && overlayView != null) {
            updateLockCountdown()
            return
        }
        handler.removeCallbacks(lockTicker)
        removeOverlay()
        currentMode = mode
        answerSubmitted = true

        val root = createRoot().apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setPadding(dp(100), dp(70), dp(100), dp(70))
            setOnKeyListener { _, _, _ -> true }
        }
        val title = TextView(this).apply {
            text = if (mode == MODE_DAILY_LOCK) "今日可用看电视时间已用完" else "本次答题错误"
            setTextColor(Color.rgb(220, 235, 222))
            textSize = 28f
            gravity = Gravity.CENTER
        }
        root.addView(title, matchParams())

        val message = TextView(this).apply {
            text = if (mode == MODE_DAILY_LOCK) {
                "今日额度已用完，明日恢复使用"
            } else {
                "本题只有一次作答机会，请等待解锁"
            }
            setTextColor(Color.WHITE)
            textSize = 38f
            gravity = Gravity.CENTER
            setPadding(0, dp(35), 0, dp(20))
        }
        root.addView(message, weightedParams(1f))

        lockCountdown = TextView(this).apply {
            setTextColor(Color.rgb(210, 239, 111))
            textSize = 28f
            gravity = Gravity.CENTER
        }
        root.addView(lockCountdown, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(90)))
        attachOverlay(root)
        updateLockCountdown()
    }

    private fun updateLockCountdown() {
        if (currentMode != MODE_DAILY_LOCK && currentMode != MODE_WRONG_LOCK) return
        if (currentMode == MODE_DAILY_LOCK && !ControlState.isDailyLimitReached(this)) {
            if (ControlState.lockoutUntil(this) > System.currentTimeMillis()) {
                renderLock(MODE_WRONG_LOCK)
            } else {
                QuestionAlarmReceiver.scheduleNext(this)
                hideOverlay()
            }
            return
        }
        if (currentMode == MODE_WRONG_LOCK) {
            val remaining = ControlState.lockoutUntil(this) - System.currentTimeMillis()
            if (remaining <= 0L) {
                ControlState.clearWrongAnswerLock(this)
                if (ControlState.isDailyLimitReached(this)) {
                    renderLock(MODE_DAILY_LOCK)
                } else {
                    QuestionAlarmReceiver.scheduleAfter(this, 1_000L)
                    hideOverlay()
                }
                return
            }
            lockCountdown?.text = "${formatRemaining(remaining)} 后解锁"
        } else {
            lockCountdown?.text = "明日解锁 · 还剩 ${formatRemaining(ControlState.millisUntilNextDay())}"
        }
        handler.removeCallbacks(lockTicker)
        handler.postDelayed(lockTicker, 1_000L)
    }

    private fun refreshBlockingState() {
        when {
            ControlState.isDailyLimitReached(this) -> showDailyLock()
            ControlState.lockoutUntil(this) > System.currentTimeMillis() -> showLockout()
            currentMode == MODE_DAILY_LOCK || currentMode == MODE_WRONG_LOCK -> {
                QuestionAlarmReceiver.scheduleNext(this)
                hideOverlay()
            }
            else -> {
                currentMode = MODE_NONE
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun formatRemaining(milliseconds: Long): String {
        val totalSeconds = ceil(milliseconds.coerceAtLeast(0L) / 1_000.0).toLong()
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun createRoot() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(64), dp(28), dp(64), dp(28))
        setBackgroundColor(Color.rgb(20, 24, 25))
        isFocusable = true
        isFocusableInTouchMode = true
        systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
    }

    private fun attachOverlay(root: View) {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        windowManager?.addView(root, params)
        overlayView = root
    }

    private fun renderOptionSelection(options: List<TextView>, selectedIndex: Int) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            option.background = GradientDrawable().apply {
                setColor(if (selected) Color.rgb(210, 239, 111) else Color.rgb(244, 246, 240))
                setStroke(dp(if (selected) 3 else 1), if (selected) Color.rgb(210, 239, 111) else Color.rgb(174, 184, 174))
                cornerRadius = dp(6).toFloat()
            }
            option.setTextColor(if (selected) Color.rgb(24, 31, 25) else Color.rgb(27, 34, 30))
        }
    }

    private fun removeOverlay() {
        overlayView?.let { view -> runCatching { windowManager?.removeViewImmediate(view) } }
        overlayView = null
        lockCountdown = null
    }

    private fun hideOverlay() {
        handler.removeCallbacks(lockTicker)
        removeOverlay()
        currentMode = MODE_NONE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun matchParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun weightedParams(weight: Float) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        0,
        weight,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "答题看护", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("答题看护正在运行")
                .setContentText("电视播放受每日时长与答题规则管理")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("答题看护正在运行")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        const val ACTION_SHOW_QUESTION = "com.example.tvparental.action.SHOW_QUESTION"
        const val ACTION_DAILY_LIMIT = "com.example.tvparental.action.DAILY_LIMIT"
        const val ACTION_LOCKOUT = "com.example.tvparental.action.LOCKOUT"
        const val ACTION_REFRESH_STATE = "com.example.tvparental.action.REFRESH_STATE"
        const val ACTION_HIDE = "com.example.tvparental.action.HIDE"

        private const val CHANNEL_ID = "question_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val MODE_NONE = 0
        private const val MODE_QUESTION = 1
        private const val MODE_DAILY_LOCK = 2
        private const val MODE_WRONG_LOCK = 3
    }
}
