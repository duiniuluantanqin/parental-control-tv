package com.example.tvparental

import android.animation.ObjectAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToInt

class QuestionOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var currentMode = MODE_NONE
    private var lockCountdown: TextView? = null
    private var remainingTimeLabel: TextView? = null
    private var answerSubmitted = false
    private var answerTransition: Runnable? = null
    private var wrongAnswerAnimator: ObjectAnimator? = null
    private var unlockPad: PasswordPad? = null
    private var unlockButton: TextView? = null
    private var pinHint: TextView? = null
    private var pinEntryActive = false
    private var rewardOfferMillis = 0L
    private var rewardOfferDismissed = false
    private var rewardChallengeOffered = false
    private var rewardChallengeBinding = ""
    private var currentQuestion: TvQuestion? = null
    private var isTestQuestion = false
    private var failedAttempts = 0
    private var pinCooldownUntil = 0L

    private val pinCooldown = Runnable {
        pinCooldownUntil = 0L
        unlockPad?.inputEnabled = true
        unlockPad?.reset()
        pinHint?.text = "Enter your 6-direction PIN."
    }

    private val lockTicker = object : Runnable {
        override fun run() {
            updateLockCountdown()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DAILY_LIMIT -> showDailyLock()
            ACTION_LOCKOUT -> showLockout()
            ACTION_USAGE_WINDOW -> showUsageWindowLock()
            ACTION_REFRESH_STATE -> refreshBlockingState()
            ACTION_HIDE -> hideOverlay()
            ACTION_SHOW_QUESTION -> showQuestion(
                intent.getBooleanExtra(EXTRA_TEST_QUESTION, false),
                trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: QuestionBank.TRIGGER_UNKNOWN,
            )
            else -> showQuestion()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(lockTicker)
        handler.removeCallbacks(pinCooldown)
        removeOverlay()
        setUsagePaused(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showQuestion(testQuestion: Boolean = false, force: Boolean = false, trigger: String = QuestionBank.TRIGGER_UNKNOWN) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        when {
            ControlState.isDailyLimitReached(this) -> return showDailyLock()
            ControlState.lockoutUntil(this) > System.currentTimeMillis() -> return showLockout()
            !ControlState.isUsageWindowAllowed(this) -> return showUsageWindowLock()
            !testQuestion && UsageTimingPause.isPaused(this) -> return dismissAutomaticQuestion()
            !force && currentMode == MODE_QUESTION && overlayView != null -> return
        }
        val challengeTrigger = if (ControlState.lockoutUntil(this) > 0L) QuestionBank.TRIGGER_LOCKOUT_RETRY else trigger
        handler.removeCallbacks(lockTicker)
        val existingRoot = (overlayView as? LinearLayout)?.takeIf { currentMode == MODE_QUESTION }
        if (existingRoot != null) {
            clearOverlayState()
            existingRoot.removeAllViews()
        } else {
            removeOverlay()
        }
        currentMode = MODE_QUESTION
        pinEntryActive = false
        answerSubmitted = false
        isTestQuestion = testQuestion

        val question = (if (testQuestion) QuestionBank.testQuestion(this) else QuestionBank.currentOrNext(this, challengeTrigger)) ?: run {
            if (!testQuestion) {
                RemoteSync.syncAsync(applicationContext) { result ->
                    if (result.settingsChanged) QuestionAlarmReceiver.scheduleForCurrentState(applicationContext)
                }
                QuestionAlarmReceiver.scheduleNext(this)
            }
            hideOverlay(preserveQuestionTimeout = testQuestion)
            return
        }
        if (!testQuestion) ControlState.clearWrongAnswerLock(this)
        currentQuestion = question
        if (!testQuestion) RemoteSync.syncAsync(applicationContext) { result ->
            if (result.settingsChanged) QuestionAlarmReceiver.scheduleForCurrentState(applicationContext)
        }
        if (!testQuestion && ControlState.ensureQuestionTimeout(this) <= System.currentTimeMillis()) {
            handleQuestionTimeout(question)
            return
        }
        val optionTexts = question.options.shuffled()
        val optionViews = mutableListOf<TextView>()
        var selectedIndex = 0
        lateinit var moveFocus: (Int, Int) -> Unit

        val root = (existingRoot ?: createRoot()).apply {
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(36), dp(18), dp(36), dp(18))
        }
        addRemainingTimeLabel(root, 28)
        val title = TextView(this).apply {
            text = if (testQuestion) {
                "Test question · ${QuestionBank.displayCategory(question.category)}"
            } else {
                "Answer to continue · Question ${QuestionBank.challengeAnsweredCount(this@QuestionOverlayService) + 1}/${QuestionBank.CHALLENGE_QUESTION_COUNT} · Correct ${QuestionBank.challengeCorrectCount(this@QuestionOverlayService)}/${QuestionBank.CHALLENGE_REQUIRED_CORRECT} · ${QuestionBank.displayCategory(question.category)}"
            }
            setTextColor(Color.rgb(193, 203, 194))
            textSize = 20f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        root.addView(title, matchParams())

        val prompt = TextView(this).apply {
            text = question.prompt
            setTextColor(Color.WHITE)
            textSize = 34f
            maxLines = 4
            setAutoSizeTextTypeUniformWithConfiguration(
                20,
                32,
                2,
                TypedValue.COMPLEX_UNIT_SP,
            )
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        root.addView(prompt, weightedParams(1f))

        val grid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
        }
        optionTexts.forEachIndexed { index, text ->
            val option = TextView(this).apply {
                this.text = text
                textSize = 18f
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(dp(14), dp(8), dp(14), dp(8))
                isFocusable = true
                isFocusableInTouchMode = true
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus && !answerSubmitted) {
                        selectedIndex = index
                        renderOptionSelection(optionViews, selectedIndex)
                    }
                }
                setOnKeyListener { _, keyCode, event ->
                    if (answerSubmitted) return@setOnKeyListener true
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
                                submitAnswer(question, optionTexts[selectedIndex], root, optionViews, testQuestion)
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
                    height = dp(60)
                    setMargins(dp(8), dp(5), dp(8), dp(5))
                },
            )
        }
        root.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(60), dp(2), dp(60), dp(6)) },
        )

        val status = TextView(this).apply {
            setTextColor(Color.rgb(205, 214, 206))
            textSize = 18f
            gravity = Gravity.CENTER
            maxLines = 2
            setAutoSizeTextTypeUniformWithConfiguration(14, 18, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        lockCountdown = status
        addUnlockButton(root, MODE_QUESTION)
        unlockButton?.apply {
            setOnFocusChangeListener { _, hasFocus ->
                renderPinButtonFocus(this, hasFocus)
                if (hasFocus) renderOptionSelection(optionViews, -1)
            }
            setOnKeyListener { _, keyCode, event ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            optionViews[if (selectedIndex >= 2) selectedIndex else 2].requestFocus()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> true
                    else -> false
                }
            }
        }

        moveFocus = { index, keyCode ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && index >= 2 && !answerSubmitted) {
                unlockButton?.requestFocus()
            } else {
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
        }
        renderOptionSelection(optionViews, selectedIndex)
        MediaPlaybackController.pauseActivePlayback(this)
        if (overlayView !== root) attachOverlay(root)
        updateLockCountdown()
        root.post {
            if (currentQuestion === question && !pinEntryActive) optionViews.first().requestFocus()
        }
    }

    private fun submitAnswer(question: TvQuestion, selected: String, root: View, options: List<TextView>, testQuestion: Boolean) {
        if (answerSubmitted) return
        if (!testQuestion && ControlState.questionTimeoutAt(this) <= System.currentTimeMillis()) {
            handleQuestionTimeout(question)
            return
        }
        answerSubmitted = true
        unlockButton?.isEnabled = false
        if (!testQuestion) ControlState.clearQuestionTimeout(this)
        handler.removeCallbacks(lockTicker)
        lockCountdown?.text = ""
        currentQuestion = null
        val correct = selected == question.answer
        if (!correct) showWrongAnswerFeedback(question, selected, options)
        if (testQuestion) {
            if (correct) lockCountdown?.apply {
                text = "Correct answer"
                setTextColor(Color.rgb(185, 222, 119))
            }
            val transition = Runnable {
                if (overlayView === root) hideOverlay(preserveQuestionTimeout = true)
            }
            answerTransition = transition
            handler.postDelayed(transition, if (correct) 1_200L else WRONG_ANSWER_FEEDBACK_MS)
            return
        }
        val progress = QuestionBank.recordAnswer(this, question, selected)
        if (progress.complete && !progress.passed) {
            val poem = QuestionBank.poemForWrongAnswerLock(this)
            val until = ControlState.startWrongAnswerLock(this, poem)
            QuestionAlarmReceiver.scheduleAfter(this, until - System.currentTimeMillis())
        }
        if (progress.complete) {
            QuestionBank.finishChallenge(this, QuestionBank.STATUS_ANSWERED)
        } else if (ControlState.isDailyLimitReached(this)) {
            QuestionBank.finishChallenge(this, QuestionBank.STATUS_DAILY_LIMIT)
        }
        RemoteSync.syncAsync(applicationContext) { result ->
            if (result.settingsChanged) QuestionAlarmReceiver.scheduleForCurrentState(applicationContext)
        }
        if (ControlState.isDailyLimitReached(this)) {
            showDailyLock()
            return
        }
        if (correct && progress.complete && !progress.passed) {
            showLockout()
            return
        }
        if (correct) lockCountdown?.apply {
            text = if (progress.passed) {
                "${progress.correctCount}/${QuestionBank.CHALLENGE_QUESTION_COUNT} correct - access unlocked"
            } else {
                "Correct answer - ${progress.correctCount}/${QuestionBank.CHALLENGE_REQUIRED_CORRECT} needed"
            }
            setTextColor(Color.rgb(185, 222, 119))
        }
        val transition = Runnable {
            if (overlayView !== root) return@Runnable
            answerTransition = null
            if (ControlState.isDailyLimitReached(this)) {
                showDailyLock()
            } else if (progress.complete && !progress.passed) {
                showLockout()
            } else if (progress.passed) {
                ControlState.clearWrongAnswerLock(this)
                QuestionAlarmReceiver.scheduleNext(this)
                hideOverlay()
            } else {
                showQuestion(force = true)
            }
        }
        answerTransition = transition
        handler.postDelayed(transition, if (correct) 450L else WRONG_ANSWER_FEEDBACK_MS)
    }

    private fun showWrongAnswerFeedback(question: TvQuestion, selected: String, options: List<TextView>) {
        lockCountdown?.apply {
            text = "Incorrect answer · Correct answer: ${question.answer}"
            setTextColor(Color.rgb(238, 154, 131))
        }
        options.filter { it.text.toString() == selected || it.text.toString() == question.answer }.forEach { option ->
            val correct = option.text.toString() == question.answer
            option.background = GradientDrawable().apply {
                setColor(if (correct) Color.rgb(185, 222, 119) else Color.rgb(238, 154, 131))
                setStroke(dp(3), if (correct) Color.rgb(210, 239, 111) else Color.rgb(255, 96, 80))
                cornerRadius = dp(6).toFloat()
            }
            option.setTextColor(Color.rgb(24, 31, 25))
        }
        val correctOption = options.first { it.text.toString() == question.answer }
        wrongAnswerAnimator = ObjectAnimator.ofFloat(correctOption, View.ALPHA, 1f, 0.82f, 1f).apply {
            duration = 1_000L
            repeatCount = 4
            start()
        }
    }

    private fun showDailyLock() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        if (!ControlState.isDailyLimitReached(this)) return refreshBlockingState()
        if (QuestionBank.isChallengeActive(this)) {
            QuestionBank.finishChallenge(this, QuestionBank.STATUS_DAILY_LIMIT, currentQuestion?.id.orEmpty())
            ControlState.clearQuestionTimeout(this)
            currentQuestion = null
            RemoteSync.syncAsync(applicationContext) { result ->
                if (result.settingsChanged) QuestionAlarmReceiver.scheduleForCurrentState(applicationContext)
            }
        }
        if (currentMode == MODE_REWARD_CHALLENGE && canChallengeForRewards() &&
            DeviceBinding.bindingId(this) == rewardChallengeBinding) return
        renderLock(MODE_DAILY_LOCK)
    }

    private fun showLockout() {
        if (ControlState.isDailyLimitReached(this)) return showDailyLock()
        if (currentMode == MODE_QUESTION && overlayView != null && answerTransition != null) return
        if (ControlState.lockoutUntil(this) <= System.currentTimeMillis()) {
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

    private fun renderLock(mode: Int, force: Boolean = false) {
        if (!force && currentMode == mode && overlayView != null) {
            updateLockCountdown()
            return
        }
        handler.removeCallbacks(lockTicker)
        handler.removeCallbacks(pinCooldown)
        removeOverlay()
        if (currentMode != mode) {
            failedAttempts = 0
            rewardOfferDismissed = false
        }
        rewardOfferMillis = if (mode == MODE_DAILY_LOCK) ControlState.rewardOfferMillis(this) else 0L
        rewardChallengeOffered = mode == MODE_DAILY_LOCK && canChallengeForRewards()
        currentMode = mode
        isTestQuestion = false
        answerSubmitted = true
        pinEntryActive = false
        if (mode == MODE_DAILY_LOCK || mode == MODE_USAGE_WINDOW) {
            ControlState.clearQuestionTimeout(this)
            currentQuestion = null
        }

        val root = createRoot().apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            if (mode == MODE_WRONG_LOCK) setPadding(dp(24), dp(16), dp(24), dp(16))
            else setPadding(dp(48), dp(32), dp(48), dp(32))
            setOnKeyListener { _, _, _ -> true }
        }
        var rewardFocus: View? = null
        if (mode == MODE_WRONG_LOCK) {
            val status = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addLockCountdown(status, heightDp = 28, textSizeSp = 16f)
            lockCountdown?.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, dp(28), 1f)
            }
            addRemainingTimeLabel(status, heightDp = 28)
            remainingTimeLabel?.apply {
                textSize = 14f
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28))
            }
            root.addView(status, matchParams().apply { bottomMargin = dp(6) })
        } else {
            addRemainingTimeLabel(root)
            val title = TextView(this).apply {
                text = if (mode == MODE_DAILY_LOCK) "Today's screen-time limit has been reached"
                else "Outside allowed hours"
                setTextColor(Color.rgb(220, 235, 222))
                textSize = 28f
                gravity = Gravity.CENTER
            }
            root.addView(title, matchParams())
        }

        if (mode == MODE_WRONG_LOCK) {
            addLockPoem(root)
        } else {
            val message = TextView(this).apply {
                text = when {
                    mode == MODE_USAGE_WINDOW -> "Try again during an allowed time window."
                    rewardOfferMillis > 0L && !rewardOfferDismissed ->
                        "Use reward time?\n${ControlState.rewardTimeLabel(rewardOfferMillis)} available today."
                    rewardOfferMillis > 0L -> "TV is paused. You can still use reward time today."
                    rewardChallengeOffered -> "Answer a hard question to earn reward time."
                    else -> "Today's allowance is used. Access returns tomorrow."
                }
                setTextColor(Color.WHITE)
                textSize = 32f
                gravity = Gravity.CENTER
                setPadding(0, dp(35), 0, dp(20))
            }
            root.addView(message, weightedParams(1f))
        }

        if (mode == MODE_USAGE_WINDOW) addLockCountdown(root)
        if (mode == MODE_USAGE_WINDOW) {
            root.addView(TextView(this).apply {
                text = "TV access resumes at the next allowed time."
                setTextColor(Color.rgb(150, 160, 152))
                textSize = 18f
                gravity = Gravity.CENTER
            }, matchParams())
        } else if (mode == MODE_WRONG_LOCK) {
            addUnlockButton(root, mode)
        } else if (rewardOfferMillis > 0L || rewardChallengeOffered) {
            rewardFocus = addRewardChoices(root)
        }
        attachOverlay(root)
        updateLockCountdown()
        val focus = rewardFocus ?: unlockButton ?: root
        root.post { focus.requestFocus() }
    }

    private fun addRewardChoices(root: LinearLayout): View {
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun action(label: String, clicked: () -> Unit) = TextView(this).apply {
            id = View.generateViewId()
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(22), 0, dp(22), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            fun showFocus(focused: Boolean) {
                setTextColor(if (focused) Color.rgb(20, 24, 25) else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (focused) Color.rgb(210, 239, 111) else Color.rgb(45, 58, 60))
                }
            }
            showFocus(false)
            setOnFocusChangeListener { _, focused -> showFocus(focused) }
            setOnClickListener { clicked() }
            // Back on a child must not dismiss the quota lock.
            setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        }
        val buttons = mutableListOf<View>()
        if (rewardOfferMillis > 0L) {
            buttons += action("Use reward time") { useRewardTime() }
        }
        if (rewardChallengeOffered) {
            buttons += action("Challenge a hard question") { openRewardChallenge() }
        }
        if (rewardOfferMillis > 0L && !rewardOfferDismissed) {
            buttons += action("Not now") {
                rewardOfferDismissed = true
                renderLock(MODE_DAILY_LOCK, force = true)
            }
        }
        buttons.forEachIndexed { index, button ->
            actions.addView(button, LinearLayout.LayoutParams(-2, dp(52)).apply {
                if (index > 0) marginStart = dp(16)
            })
            button.nextFocusLeftId = buttons.getOrElse(index - 1) { button }.id
            button.nextFocusRightId = buttons.getOrElse(index + 1) { button }.id
        }
        root.addView(actions, matchParams())
        return if (rewardOfferMillis > 0L && !rewardOfferDismissed) buttons.last() else buttons.first()
    }

    private fun canChallengeForRewards(): Boolean =
        DeviceBinding.bindingId(this).isNotEmpty() && ControlState.canOfferRewards(this)

    private fun useRewardTime() {
        if (ControlState.activateRewardTime(this)) refreshBlockingState()
        else renderLock(MODE_DAILY_LOCK, force = true)
    }

    private fun openRewardChallenge() {
        if (!canChallengeForRewards()) return refreshBlockingState()
        if (currentMode != MODE_REWARD_CHALLENGE) {
            rewardChallengeBinding = DeviceBinding.bindingId(this)
        }
        requestRewardChallenge(pendingMessage = "Loading a hard question…", retry = ::openRewardChallenge) { response ->
            val question = response.optJSONObject("question")
            if (question == null) {
                showRewardPanel("No hard questions are available right now.")
            } else {
                val options = question.getJSONArray("options")
                val minutes = question.getInt("rewardMinutes")
                val questionId = question.getString("id")
                val choices = (0 until options.length()).map { index ->
                    "${'A' + index}.  ${options.getString(index)}" to { submitRewardAnswer(questionId, index) }
                }
                showRewardPanel(question.getString("prompt"), "Win ${minuteLabel(minutes)}", choices)
            }
        }
    }

    private fun submitRewardAnswer(questionId: String, selectedIndex: Int) {
        if (!canChallengeForRewards() || DeviceBinding.bindingId(this) != rewardChallengeBinding) {
            return refreshBlockingState()
        }
        val answer = JSONObject().put("questionId", questionId).put("selectedIndex", selectedIndex)
        requestRewardChallenge(answer, "Checking your answer…", { submitRewardAnswer(questionId, selectedIndex) }) { response ->
            val minutes = response.getInt("earnedMinutes")
            val available = ControlState.rewardOfferMillis(this)
            val message = when {
                !response.getBoolean("correct") -> "Incorrect. No reward time was earned."
                available > 0L -> "Correct! ${minuteLabel(minutes)} added.\n${ControlState.rewardTimeLabel(available)} available today."
                else -> "Correct! ${minuteLabel(minutes)} saved in your reward balance."
            }
            val actions = if (available > 0L) listOf("Use reward time" to ::useRewardTime) else emptyList()
            showRewardPanel(message, actions = actions)
        }
    }

    private fun requestRewardChallenge(
        answer: JSONObject? = null,
        pendingMessage: String,
        retry: () -> Unit,
        onSuccess: (JSONObject) -> Unit,
    ) {
        val pendingView = showRewardPanel(pendingMessage)
        val context = applicationContext
        val binding = rewardChallengeBinding
        Thread {
            val result = runCatching { RemoteSync.rewardChallenge(context, binding, answer) }
            handler.post {
                if (overlayView !== pendingView || DeviceBinding.bindingId(this) != binding) return@post
                result.fold(onSuccess = { response ->
                    when {
                        response.optBoolean("unavailable") -> showRewardPanel("This reward challenge is no longer available.")
                        !response.optBoolean("eligible", true) -> showRewardPanel("Today's usage could not be confirmed. Sync and try again.")
                        else -> onSuccess(response)
                    }
                }, onFailure = {
                    showRewardPanel("Could not connect. Check the connection and try again.", actions = listOf("Retry" to retry))
                })
            }
        }.start()
    }

    private fun minuteLabel(minutes: Int): String = if (minutes == 1) "1 minute" else "$minutes minutes"

    private fun returnToUsageLock() {
        if (ControlState.isDailyLimitReached(this)) renderLock(MODE_DAILY_LOCK, force = true)
        else refreshBlockingState()
    }

    private fun showRewardPanel(
        message: String,
        prize: String? = null,
        actions: List<Pair<String, () -> Unit>> = emptyList(),
    ): View {
        handler.removeCallbacks(lockTicker)
        removeOverlay()
        currentMode = MODE_REWARD_CHALLENGE
        val gold = Color.rgb(255, 213, 127)
        val lavender = Color.rgb(238, 228, 255)
        val root = createRoot().apply {
            setPadding(dp(36), dp(18), dp(36), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.rgb(40, 22, 65))
                setStroke(dp(2), Color.rgb(164, 119, 215))
                cornerRadius = dp(24).toFloat()
            }
        }
        fun handleBack(key: Int, event: KeyEvent): Boolean {
            if (key != KeyEvent.KEYCODE_BACK) return false
            if (event.action == KeyEvent.ACTION_UP) returnToUsageLock()
            return true
        }
        root.setOnKeyListener { _, key, event -> handleBack(key, event) }
        fun label(copy: String, size: Float, color: Int) = TextView(this).apply {
            text = copy
            textSize = size
            setTextColor(color)
        }
        root.addView(label("Reward challenge", 30f, gold).apply { setTypeface(null, Typeface.BOLD) }, matchParams())
        if (prize != null) root.addView(label(prize, 19f, lavender), matchParams())
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFocusable = false
            addView(body)
        }
        root.addView(scroll, weightedParams(1f))
        body.addView(label(message, 24f, Color.WHITE).apply {
            setPadding(0, dp(10), 0, dp(10))
        }, matchParams())
        fun button(copy: String, onClick: () -> Unit) = label(copy, 20f, lavender).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isFocusable = true
            isClickable = true
            fun paint(focused: Boolean) {
                setTextColor(if (focused) Color.rgb(40, 22, 65) else lavender)
                background = GradientDrawable().apply {
                    setColor(if (focused) gold else Color.rgb(66, 43, 94))
                    cornerRadius = dp(10).toFloat()
                }
            }
            paint(false)
            setOnFocusChangeListener { _, focused -> paint(focused) }
            setOnKeyListener { _, key, event -> handleBack(key, event) }
            setOnClickListener { onClick() }
        }
        val actionButtons = actions.map { (copy, onClick) -> button(copy, onClick) }
        val back = button("Back to daily limit", ::returnToUsageLock)
        root.addView(back, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        if (prize != null && actionButtons.size == 4) {
            actionButtons.chunked(2).forEachIndexed { rowIndex, pair ->
                val row = LinearLayout(this)
                pair.forEachIndexed { column, option ->
                    row.addView(option, LinearLayout.LayoutParams(0, -1, 1f).apply {
                        if (column == 1) marginStart = dp(10)
                    })
                    option.nextFocusLeftId = pair[0].id
                    option.nextFocusRightId = pair[1].id
                    option.nextFocusUpId = actionButtons[column].id
                    option.nextFocusDownId = if (rowIndex == 0) actionButtons[2 + column].id else back.id
                }
                body.addView(row, matchParams().apply { if (rowIndex == 0) bottomMargin = dp(6) })
            }
            back.nextFocusUpId = actionButtons[2].id
        } else {
            val controls = actionButtons + back
            actionButtons.forEach { body.addView(it, matchParams().apply { bottomMargin = dp(6) }) }
            controls.forEachIndexed { index, view ->
                view.nextFocusUpId = controls.getOrElse(index - 1) { view }.id
                view.nextFocusDownId = controls.getOrElse(index + 1) { view }.id
            }
        }
        attachOverlay(root)
        root.post {
            (actionButtons.firstOrNull() ?: back).requestFocus()
            scroll.scrollTo(0, 0)
        }
        handler.postDelayed(lockTicker, 1_000L)
        return root
    }

    private fun addLockPoem(root: LinearLayout) {
        val poem = ControlState.wrongAnswerLockPoem(this)
        val title = TextView(this).apply {
            text = poem.title
            textSize = 28f
            setTextColor(Color.rgb(220, 235, 222))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val author = TextView(this).apply {
            text = poem.author
            textSize = 18f
            setTextColor(Color.rgb(193, 203, 194))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(14))
        }
        val body = TextView(this).apply {
            text = poem.content
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setLineSpacing(dp(8).toFloat(), 1f)
        }
        val titlePaint = TextPaint(title.paint)
        val authorPaint = TextPaint(author.paint)
        val bodyPaint = TextPaint(body.paint)
        val content = object : LinearLayout(this) {
            private var lastWidth = 0
            private var lastHeight = 0
            private var compact = false

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                val height = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
                if (width > 0 && height > 0 && (width != lastWidth || height != lastHeight)) {
                    lastWidth = width
                    lastHeight = height
                    fun textHeight(text: String, paint: TextPaint, spacing: Float = 0f): Int =
                        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                            .setIncludePad(true)
                            .setBreakStrategy(body.breakStrategy)
                            .setHyphenationFrequency(body.hyphenationFrequency)
                            .setLineSpacing(spacing, 1f)
                            .build().height
                    val normalHeight = textHeight(poem.title, titlePaint) +
                        textHeight(poem.author, authorPaint) + author.paddingTop + author.paddingBottom +
                        textHeight(poem.content, bodyPaint, dp(8).toFloat())
                    val needsCompact = normalHeight > height
                    if (needsCompact != compact) {
                        compact = needsCompact
                        author.visibility = if (compact) View.GONE else View.VISIBLE
                        title.setTypeface(null, if (compact) Typeface.NORMAL else Typeface.BOLD)
                        title.text = if (compact) SpannableString("${poem.title}  ${poem.author}").apply {
                            setSpan(StyleSpan(Typeface.BOLD), 0, poem.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            val start = poem.title.length + 2
                            setSpan(RelativeSizeSpan(0.5f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            setSpan(ForegroundColorSpan(Color.rgb(193, 203, 194)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        } else poem.title
                        body.text = if (compact) poem.content.lineSequence().filter { it.isNotBlank() }.joinToString("\n")
                        else poem.content
                        if (compact) {
                            body.setLineSpacing(0f, 1f)
                            val scaledDensity = resources.displayMetrics.scaledDensity
                            body.setAutoSizeTextTypeUniformWithConfiguration(
                                scaledDensity.roundToInt().coerceAtLeast(1),
                                (26f * scaledDensity).roundToInt(),
                                1,
                                TypedValue.COMPLEX_UNIT_PX,
                            )
                        } else {
                            body.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
                            body.textSize = 26f
                            body.setLineSpacing(dp(8).toFloat(), 1f)
                        }
                    }
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(25, 30, 28))
                setStroke(dp(2), Color.rgb(70, 84, 76))
                cornerRadius = dp(12).toFloat()
            }
            addView(title, matchParams())
            addView(author, matchParams())
            addView(body, weightedParams(1f))
        }
        root.addView(content, weightedParams(1f))
    }

    private fun addLockCountdown(root: LinearLayout, heightDp: Int = 56, textSizeSp: Float = 28f) {
        lockCountdown = TextView(this).apply {
            setTextColor(Color.rgb(210, 239, 111))
            textSize = textSizeSp
            gravity = Gravity.CENTER
        }
        root.addView(lockCountdown, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)))
    }

    private fun addRemainingTimeLabel(root: LinearLayout, heightDp: Int = 34) {
        remainingTimeLabel = TextView(this).apply {
            setTextColor(Color.rgb(193, 203, 194))
            textSize = 16f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        root.addView(remainingTimeLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(heightDp),
        ))
        updateRemainingTimeLabel()
    }

    private fun updateRemainingTimeLabel() {
        val minutes = (ControlState.remainingDailyMillis(this) + 59_999L) / 60_000L
        remainingTimeLabel?.text = "Time remaining today · ${minutes} min"
    }

    private fun handleQuestionTimeout(question: TvQuestion) {
        if (answerSubmitted || currentMode != MODE_QUESTION) return
        answerSubmitted = true
        QuestionBank.recordUnanswered(this, question)
        QuestionBank.finishChallenge(this, QuestionBank.STATUS_TIMEOUT, question.id)
        ControlState.clearQuestionTimeout(this)
        currentQuestion = null
        RemoteSync.syncAsync(applicationContext) { result ->
            if (result.settingsChanged) QuestionAlarmReceiver.scheduleForCurrentState(applicationContext)
        }
        ControlState.clearWrongAnswerLock(this)
        QuestionAlarmReceiver.scheduleNext(this)
        hideOverlay()
    }

    private fun setUsagePaused(paused: Boolean) {
        if (ControlState.isQuestionOverlayActive(this) == paused) return
        ControlState.setQuestionOverlayActive(this, paused)
        val intent = Intent(this, UsageTrackingService::class.java)
            .setAction(UsageTrackingService.ACTION_SET_OVERLAY_PAUSED)
            .putExtra(UsageTrackingService.EXTRA_OVERLAY_PAUSED, paused)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun addUnlockButton(root: LinearLayout, mode: Int) {
        if (mode != MODE_QUESTION && !ControlState.hasUnlockPassword(this)) {
            root.addView(TextView(this).apply {
                text = "Set a parent PIN on the home screen."
                setTextColor(Color.rgb(150, 160, 152))
                textSize = if (mode == MODE_WRONG_LOCK) 14f else 18f
                gravity = Gravity.CENTER
            }, if (mode == MODE_WRONG_LOCK) LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ) else matchParams())
            return
        }
        val button = TextView(this).apply {
            text = if (mode == MODE_QUESTION) "SKIP" else "Unlock with PIN"
            setTextColor(Color.rgb(155, 165, 156))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setOnFocusChangeListener { _, hasFocus ->
                renderPinButtonFocus(this, hasFocus)
            }
            background = null
            setOnClickListener {
                if (mode == MODE_QUESTION && answerSubmitted) return@setOnClickListener
                if (ControlState.hasUnlockPassword(this@QuestionOverlayService)) {
                    showPasswordEntry(mode)
                } else {
                    Toast.makeText(this@QuestionOverlayService, "Set a parent PIN on the home screen first.", Toast.LENGTH_LONG).show()
                }
            }
        }
        unlockButton = button
        root.addView(button, LinearLayout.LayoutParams(
            if (mode == MODE_QUESTION) dp(96) else LinearLayout.LayoutParams.WRAP_CONTENT,
            if (mode == MODE_QUESTION) dp(40) else LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(if (mode == MODE_QUESTION || mode == MODE_WRONG_LOCK) 0 else 12)
        })
    }

    private fun renderPinButtonFocus(button: TextView, hasFocus: Boolean) {
        button.setTextColor(if (hasFocus) Color.rgb(210, 239, 111) else Color.rgb(155, 165, 156))
        button.setTypeface(null, if (hasFocus) Typeface.BOLD else Typeface.NORMAL)
        button.paintFlags = if (hasFocus) button.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        else button.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
    }

    private fun showPasswordEntry(mode: Int) {
        if (currentMode != mode || pinEntryActive) return
        pinEntryActive = true
        handler.removeCallbacks(pinCooldown)
        removeOverlay()
        val root = createRoot().apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        addRemainingTimeLabel(root)
        root.addView(TextView(this).apply {
            text = if (mode == MODE_QUESTION) "Skip with parent PIN" else "Unlock with PIN"
            setTextColor(Color.rgb(220, 235, 222))
            textSize = 28f
            gravity = Gravity.CENTER
        }, matchParams())
        addLockCountdown(root)
        val pad = PasswordPad(this, true, onBack = {
            pinEntryActive = false
            if (mode == MODE_QUESTION) showQuestion(isTestQuestion, force = true)
            else renderLock(mode, force = true)
        }, onComplete = { entered ->
            if (ControlState.verifyUnlockPassword(this, entered)) {
                failedAttempts = 0
                pinCooldownUntil = 0L
                when (mode) {
                    MODE_QUESTION -> skipChallenge()
                    else -> unlockWrongLock()
                }
            } else {
                failedAttempts++
                unlockPad?.reset()
                if (failedAttempts >= MAX_PASSWORD_ATTEMPTS) {
                    failedAttempts = 0
                    unlockPad?.inputEnabled = false
                    pinCooldownUntil = System.currentTimeMillis() + PASSWORD_COOLDOWN_MS
                    pinHint?.text = "Too many attempts. Try again in 30 seconds."
                    handler.postDelayed(pinCooldown, PASSWORD_COOLDOWN_MS)
                } else {
                    pinHint?.text = "Incorrect PIN. ${MAX_PASSWORD_ATTEMPTS - failedAttempts} attempts left."
                }
            }
        }).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        unlockPad = pad
        root.addView(pad, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(20)
        })
        pinHint = TextView(this).apply {
            text = "Enter your 6-direction PIN."
            setTextColor(Color.rgb(193, 203, 194))
            textSize = 16f
            gravity = Gravity.CENTER
        }
        root.addView(pinHint, matchParams())
        root.addView(TextView(this).apply {
            text = if (mode == MODE_QUESTION) "Press Back to return to the question."
            else "Press Back to return to the lock screen."
            setTextColor(Color.rgb(193, 203, 194))
            textSize = 16f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        if (pinCooldownUntil > System.currentTimeMillis()) {
            pad.inputEnabled = false
            pinHint?.text = "Too many attempts. Try again in ${ceil((pinCooldownUntil - System.currentTimeMillis()) / 1_000.0).toLong()} seconds."
            handler.postDelayed(pinCooldown, pinCooldownUntil - System.currentTimeMillis())
        }
        attachOverlay(root)
        updateLockCountdown()
        root.post { pad.requestFocus() }
    }

    private fun skipChallenge() {
        if (currentMode != MODE_QUESTION || !pinEntryActive || answerSubmitted) return
        if (isTestQuestion) {
            hideOverlay(preserveQuestionTimeout = true)
            return
        }
        when {
            ControlState.isDailyLimitReached(this) -> return showDailyLock()
            ControlState.lockoutUntil(this) > System.currentTimeMillis() -> return showLockout()
            !ControlState.isUsageWindowAllowed(this) -> return showUsageWindowLock()
        }
        answerSubmitted = true
        // Use the existing parent-override completion path without recording any answers.
        QuestionBank.finishChallenge(this, QuestionBank.STATUS_PARENT_SKIP, currentQuestion?.id.orEmpty())
        ControlState.clearQuestionTimeout(this)
        ControlState.clearWrongAnswerLock(this)
        QuestionAlarmReceiver.scheduleNext(this)
        hideOverlay()
        RemoteSync.syncAsync(applicationContext) { result ->
            if (result.settingsChanged) QuestionAlarmReceiver.scheduleForCurrentState(applicationContext)
        }
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
        updateRemainingTimeLabel()
        if (currentMode == MODE_QUESTION) {
            if (isTestQuestion) {
                lockCountdown?.text = ""
                return
            }
            val remaining = ControlState.questionTimeoutAt(this) - System.currentTimeMillis()
            if (remaining <= 0L) {
                currentQuestion?.let(::handleQuestionTimeout)
                return
            }
            lockCountdown?.text = "Answer at least ${QuestionBank.CHALLENGE_REQUIRED_CORRECT} of ${QuestionBank.CHALLENGE_QUESTION_COUNT} questions correctly to continue · ${formatRemaining(remaining)}"
            handler.removeCallbacks(lockTicker)
            handler.postDelayed(lockTicker, 1_000L)
            return
        }
        if (currentMode == MODE_REWARD_CHALLENGE) {
            if (!canChallengeForRewards() || DeviceBinding.bindingId(this) != rewardChallengeBinding) return returnToUsageLock()
            handler.removeCallbacks(lockTicker)
            handler.postDelayed(lockTicker, 1_000L)
            return
        }
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
        if (currentMode == MODE_DAILY_LOCK) {
            if (!ControlState.isDailyLimitReached(this)) return refreshBlockingState()
            if (rewardOfferMillis != ControlState.rewardOfferMillis(this) || rewardChallengeOffered != canChallengeForRewards()) {
                return renderLock(MODE_DAILY_LOCK, force = true)
            }
        }
        if (currentMode == MODE_WRONG_LOCK) {
            val remaining = ControlState.lockoutUntil(this) - System.currentTimeMillis()
            if (remaining <= 0L) {
                refreshBlockingState()
                return
            }
            lockCountdown?.text = if (pinEntryActive) "Unlocks in ${formatRemaining(remaining)}"
            else "Locked · Unlocks in ${formatRemaining(remaining)}"
        } else {
            lockCountdown?.text = ""
        }
        handler.removeCallbacks(lockTicker)
        handler.postDelayed(lockTicker, 1_000L)
    }

    private fun refreshBlockingState() {
        when {
            ControlState.isDailyLimitReached(this) -> showDailyLock()
            ControlState.lockoutUntil(this) > System.currentTimeMillis() -> showLockout()
            !ControlState.isUsageWindowAllowed(this) -> showUsageWindowLock()
            UsageTimingPause.isPaused(this) -> dismissAutomaticQuestion()
            currentMode == MODE_QUESTION && overlayView != null -> Unit
            ControlState.lockoutUntil(this) > 0L -> showQuestion(trigger = QuestionBank.TRIGGER_LOCKOUT_RETRY)
            currentMode == MODE_DAILY_LOCK || currentMode == MODE_WRONG_LOCK || currentMode == MODE_USAGE_WINDOW ||
                currentMode == MODE_REWARD_CHALLENGE -> {
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

    private fun dismissAutomaticQuestion() {
        if (currentMode == MODE_QUESTION && isTestQuestion) return
        // Keep the unfinished challenge, but do not let a hidden question time out.
        hideOverlay()
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
        setUsagePaused(true)
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

    private fun clearOverlayState() {
        answerTransition?.let { handler.removeCallbacks(it) }
        answerTransition = null
        wrongAnswerAnimator?.cancel()
        wrongAnswerAnimator = null
        lockCountdown = null
        remainingTimeLabel = null
        unlockPad = null
        unlockButton = null
        pinHint = null
    }

    private fun removeOverlay() {
        clearOverlayState()
        overlayView?.let { view -> runCatching { windowManager?.removeViewImmediate(view) } }
        overlayView = null
    }

    private fun hideOverlay(preserveQuestionTimeout: Boolean = false) {
        handler.removeCallbacks(lockTicker)
        handler.removeCallbacks(pinCooldown)
        removeOverlay()
        pinEntryActive = false
        currentMode = MODE_NONE
        currentQuestion = null
        if (!preserveQuestionTimeout && !isTestQuestion) ControlState.clearQuestionTimeout(this)
        isTestQuestion = false
        setUsagePaused(false)
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
        var isRunning = false
            private set

        const val ACTION_SHOW_QUESTION = "com.example.tvparental.action.SHOW_QUESTION"
        const val ACTION_DAILY_LIMIT = "com.example.tvparental.action.DAILY_LIMIT"
        const val ACTION_LOCKOUT = "com.example.tvparental.action.LOCKOUT"
        const val ACTION_USAGE_WINDOW = "com.example.tvparental.action.USAGE_WINDOW"
        const val ACTION_REFRESH_STATE = "com.example.tvparental.action.REFRESH_STATE"
        const val ACTION_HIDE = "com.example.tvparental.action.HIDE"
        const val EXTRA_TEST_QUESTION = "com.example.tvparental.extra.TEST_QUESTION"
        const val EXTRA_TRIGGER = "com.example.tvparental.extra.CHALLENGE_TRIGGER"

        private const val CHANNEL_ID = "question_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val MODE_NONE = 0
        private const val MODE_QUESTION = 1
        private const val MODE_DAILY_LOCK = 2
        private const val MODE_WRONG_LOCK = 3
        private const val MODE_USAGE_WINDOW = 4
        private const val MODE_REWARD_CHALLENGE = 5
        private const val MAX_PASSWORD_ATTEMPTS = 5
        private const val PASSWORD_COOLDOWN_MS = 30_000L
        private const val WRONG_ANSWER_FEEDBACK_MS = 5_000L
    }
}
