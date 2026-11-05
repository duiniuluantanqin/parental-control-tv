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
    private var unlockPad: PasswordPad? = null
    private var failedAttempts = 0

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
            ACTION_USAGE_WINDOW -> showUsageWindowLock()
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
            !ControlState.isUsageWindowAllowed(this) -> return showUsageWindowLock()
            currentMode == MODE_QUESTION && overlayView != null && !answerSubmitted -> return
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
        QuestionBank.beginChallenge(this)
        val optionTexts = question.options.shuffled()
        val optionViews = mutableListOf<TextView>()
        var selectedIndex = 0
        lateinit var moveFocus: (Int, Int) -> Unit

        val root = createRoot()
        val title = TextView(this).apply {
            text = "Answer to continue · Question ${QuestionBank.challengeAnsweredCount(this@QuestionOverlayService) + 1}/${QuestionBank.CHALLENGE_QUESTION_COUNT} · Correct ${QuestionBank.challengeCorrectCount(this@QuestionOverlayService)}/${QuestionBank.CHALLENGE_REQUIRED_CORRECT} · ${QuestionBank.displayCategory(question.category)}"
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
                textSize = 22f
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
                    height = dp(84)
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                },
            )
        }
        root.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(60), dp(4), dp(60), dp(12)) },
        )

        val status = TextView(this).apply {
            text = "Answer at least ${QuestionBank.CHALLENGE_REQUIRED_CORRECT} of ${QuestionBank.CHALLENGE_QUESTION_COUNT} questions correctly to continue."
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
        val progress = QuestionBank.recordAnswer(this, question, selected)
        RemoteSync.syncAsync(applicationContext) { result ->
            if (result.settingsChanged) QuestionAlarmReceiver.scheduleForCurrentState(applicationContext)
        }
        if (ControlState.isDailyLimitReached(this)) {
            showDailyLock()
            return
        }
        if (progress.complete && !progress.passed) {
            val until = ControlState.startWrongAnswerLock(this)
            QuestionBank.restartChallenge(this)
            QuestionAlarmReceiver.scheduleAfter(this, until - System.currentTimeMillis())
            showLockout()
            return
        }
        if (progress.passed) QuestionBank.finishChallenge(this)
        (root as? LinearLayout)?.let { layout ->
            (layout.getChildAt(layout.childCount - 1) as? TextView)?.apply {
                text = when {
                    progress.passed -> "${progress.correctCount}/${QuestionBank.CHALLENGE_QUESTION_COUNT} correct - access unlocked"
                    selected == question.answer -> "Correct answer - ${progress.correctCount}/${QuestionBank.CHALLENGE_REQUIRED_CORRECT} needed"
                    else -> "Incorrect answer - ${progress.correctCount}/${QuestionBank.CHALLENGE_REQUIRED_CORRECT} needed"
                }
                setTextColor(if (selected == question.answer) Color.rgb(185, 222, 119) else Color.rgb(238, 154, 131))
            }
        }
        root.postDelayed({
            if (ControlState.isDailyLimitReached(this)) {
                showDailyLock()
            } else if (progress.passed) {
                ControlState.clearWrongAnswerLock(this)
                QuestionAlarmReceiver.scheduleNext(this)
                hideOverlay()
            } else {
                showQuestion()
            }
        }, 450)
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

    private fun showUsageWindowLock() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        if (ControlState.isDailyLimitReached(this)) return showDailyLock()
        if (ControlState.lockoutUntil(this) > System.currentTimeMillis()) return showLockout()
        if (ControlState.isUsageWindowAllowed(this)) return refreshBlockingState()
        QuestionAlarmReceiver.scheduleForCurrentState(this)
        renderLock(MODE_USAGE_WINDOW)
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
            setPadding(dp(48), dp(32), dp(48), dp(32))
            setOnKeyListener { _, _, _ -> true }
        }
        val title = TextView(this).apply {
            text = when (mode) {
                MODE_DAILY_LOCK -> "Today's screen-time limit has been reached"
                MODE_USAGE_WINDOW -> "Outside allowed hours"
                else -> "Incorrect answer"
            }
            setTextColor(Color.rgb(220, 235, 222))
            textSize = 28f
            gravity = Gravity.CENTER
        }
        root.addView(title, matchParams())

        val message = TextView(this).apply {
            text = if (mode == MODE_DAILY_LOCK) {
                "Today's allowance is used. Access returns tomorrow."
            } else if (mode == MODE_USAGE_WINDOW) {
                "Try again during an allowed time window."
            } else {
                "The 5-question challenge restarts after the lockout."
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
        root.addView(lockCountdown, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        if (mode == MODE_USAGE_WINDOW) {
            root.addView(TextView(this).apply {
                text = "TV access resumes at the next allowed time."
                setTextColor(Color.rgb(150, 160, 152))
                textSize = 18f
                gravity = Gravity.CENTER
            }, matchParams())
        } else {
            addUnlockPad(root, mode)
        }
        attachOverlay(root)
        updateLockCountdown()
        unlockPad?.let { pad -> root.post { pad.requestFocus() } }
    }

    private fun addUnlockPad(root: LinearLayout, mode: Int) {
        failedAttempts = 0
        if (!ControlState.hasUnlockPassword(this)) {
            root.addView(TextView(this).apply {
                text = "Set a parent PIN on the home screen."
                setTextColor(Color.rgb(150, 160, 152))
                textSize = 18f
                gravity = Gravity.CENTER
            }, matchParams())
            return
        }
        val idleHint = if (mode == MODE_DAILY_LOCK) {
            "Parent unlock · Enter your PIN to add 15 minutes"
        } else {
            "Parent unlock · Enter your 6-direction PIN"
        }
        val hint = TextView(this).apply {
            text = idleHint
            setTextColor(Color.rgb(193, 203, 194))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(hint, matchParams())
        val pad = PasswordPad(this, true) { entered ->
            if (ControlState.verifyUnlockPassword(this, entered)) {
                failedAttempts = 0
                if (mode == MODE_DAILY_LOCK) {
                    unlockDailyLimit()
                } else {
                    unlockWrongLock()
                }
            } else {
                failedAttempts++
                unlockPad?.reset()
                if (failedAttempts >= MAX_PASSWORD_ATTEMPTS) {
                    failedAttempts = 0
                    unlockPad?.inputEnabled = false
                    hint.text = "Too many attempts. Try again in 30 seconds."
                    handler.postDelayed({
                        unlockPad?.inputEnabled = true
                        unlockPad?.reset()
                        hint.text = idleHint
                    }, PASSWORD_COOLDOWN_MS)
                } else {
                    hint.text = "Incorrect PIN. ${MAX_PASSWORD_ATTEMPTS - failedAttempts} attempts left."
                }
            }
        }
        unlockPad = pad
        root.addView(pad, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    private fun unlockDailyLimit() {
        QuestionBank.finishChallenge(this)
        ControlState.grantPasswordExtension(this)
        QuestionAlarmReceiver.scheduleNext(this)
        hideOverlay()
    }

    private fun unlockWrongLock() {
        QuestionBank.finishChallenge(this)
        ControlState.clearWrongAnswerLock(this)
        if (ControlState.isDailyLimitReached(this)) {
            renderLock(MODE_DAILY_LOCK)
            return
        }
        if (!ControlState.isUsageWindowAllowed(this)) {
            renderLock(MODE_USAGE_WINDOW)
            return
        }
        QuestionAlarmReceiver.scheduleNext(this)
        hideOverlay()
    }

    private fun updateLockCountdown() {
        if (currentMode != MODE_DAILY_LOCK && currentMode != MODE_WRONG_LOCK && currentMode != MODE_USAGE_WINDOW) return
        if (currentMode == MODE_USAGE_WINDOW) {
            if (ControlState.isUsageWindowAllowed(this)) {
                return refreshBlockingState()
            }
            lockCountdown?.text = "Next allowed window · ${formatRemaining(ControlState.millisUntilNextAllowedUsageWindow(this))} remaining"
            handler.removeCallbacks(lockTicker)
            handler.postDelayed(lockTicker, 1_000L)
            return
        }
        if (currentMode == MODE_DAILY_LOCK && !ControlState.isDailyLimitReached(this)) {
            if (ControlState.lockoutUntil(this) > System.currentTimeMillis()) {
                renderLock(MODE_WRONG_LOCK)
            } else if (QuestionBank.isChallengeActive(this)) {
                showQuestion()
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
                } else if (!ControlState.isUsageWindowAllowed(this)) {
                    renderLock(MODE_USAGE_WINDOW)
                } else if (QuestionBank.isChallengeActive(this)) {
                    showQuestion()
                } else {
                    QuestionAlarmReceiver.scheduleAfter(this, 1_000L)
                    hideOverlay()
                }
                return
            }
            lockCountdown?.text = "Unlocks in ${formatRemaining(remaining)}"
        } else {
            lockCountdown?.text = "Resumes tomorrow · ${formatRemaining(ControlState.millisUntilNextDay())} remaining"
        }
        handler.removeCallbacks(lockTicker)
        handler.postDelayed(lockTicker, 1_000L)
    }

    private fun refreshBlockingState() {
        when {
            ControlState.isDailyLimitReached(this) -> showDailyLock()
            ControlState.lockoutUntil(this) > System.currentTimeMillis() -> showLockout()
            !ControlState.isUsageWindowAllowed(this) -> showUsageWindowLock()
            currentMode == MODE_DAILY_LOCK || currentMode == MODE_WRONG_LOCK || currentMode == MODE_USAGE_WINDOW -> {
                if (QuestionBank.isChallengeActive(this)) {
                    showQuestion()
                } else {
                    QuestionAlarmReceiver.scheduleNext(this)
                    hideOverlay()
                }
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
        setPadding(dp(48), dp(28), dp(48), dp(28))
        background = GradientDrawable().apply {
            setColor(Color.rgb(20, 24, 25))
            setStroke(dp(2), Color.rgb(70, 84, 76))
            cornerRadius = dp(24).toFloat()
        }
        isFocusable = true
        isFocusableInTouchMode = true
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
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            (resources.displayMetrics.heightPixels * 0.82f).toInt(),
            type,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.65f
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
        unlockPad = null
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
            val channel = NotificationChannel(CHANNEL_ID, "Question lock", NotificationManager.IMPORTANCE_LOW)
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
                .setContentTitle("Question lock is active")
                .setContentText("TV access follows screen-time limits and quiz rules.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Question lock is active")
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
        const val ACTION_USAGE_WINDOW = "com.example.tvparental.action.USAGE_WINDOW"
        const val ACTION_REFRESH_STATE = "com.example.tvparental.action.REFRESH_STATE"
        const val ACTION_HIDE = "com.example.tvparental.action.HIDE"

        private const val CHANNEL_ID = "question_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val MODE_NONE = 0
        private const val MODE_QUESTION = 1
        private const val MODE_DAILY_LOCK = 2
        private const val MODE_WRONG_LOCK = 3
        private const val MODE_USAGE_WINDOW = 4
        private const val MAX_PASSWORD_ATTEMPTS = 5
        private const val PASSWORD_COOLDOWN_MS = 30_000L
    }
}
