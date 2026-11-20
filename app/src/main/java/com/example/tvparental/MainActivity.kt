package com.example.tvparental

import android.app.Activity
import android.app.Dialog
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            refreshStatus()
            statusHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS)
        }
    }
    private val syncCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatus()
        }
    }
    private var syncReceiverRegistered = false
    private lateinit var usageStatus: TextView
    private lateinit var settingsStatus: TextView
    private lateinit var syncStatus: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var passwordStatus: TextView
    private lateinit var adminStatus: TextView
    private lateinit var usageProgress: ProgressBar
    private lateinit var syncButton: Button
    private var startupQuestionPending = false
    private var remoteButtonStatus: TextView? = null
    private var bindingDialog: Dialog? = null
    private var bindingText: TextView? = null
    private var bindingHint: TextView? = null
    private var bindingCodeExpiresAt = 0L
    private var bindingShownBound = false
    private var bindingRequestInProgress = false
    private val bindingPoll = object : Runnable {
        override fun run() {
            if (bindingDialog?.isShowing != true || isFinishing || isDestroyed) return
            loadBindingCode(requestCode = !bindingShownBound && bindingCodeExpiresAt <= System.currentTimeMillis())
            statusHandler.postDelayed(this, 3_000L)
        }
    }

    companion object {
        private const val REQUEST_CODE_ENABLE_ADMIN = 1001
        private const val STATUS_REFRESH_INTERVAL_MS = 30_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        setContentView(buildContent())
        registerSyncReceiver()
        startUsageTracking()
        QuestionAlarmReceiver.ensureScheduled(this)
        startupQuestionPending = ControlState.showQuestionOnStartup(this)
        refreshStatus()
        syncButton.post { syncButton.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        refreshRemoteButtonStatus()
        registerSyncReceiver()
        statusHandler.removeCallbacks(statusRefresh)
        statusRefresh.run()
        if (bindingDialog?.isShowing == true) bindingPoll.run()
        if ((startupQuestionPending || QuestionBank.hasRebootChallenge(this)) && canDrawOverlays()) {
            startupQuestionPending = false
            if (ControlState.claimStartupQuestion(this) || QuestionBank.hasRebootChallenge(this)) showQuestionNow()
        }
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusRefresh)
        statusHandler.removeCallbacks(bindingPoll)
        unregisterSyncReceiver()
        super.onPause()
    }

    private fun registerSyncReceiver() {
        if (syncReceiverRegistered) return
        val filter = IntentFilter(RemoteSync.ACTION_SYNC_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncCompletedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(syncCompletedReceiver, filter)
        }
        syncReceiverRegistered = true
    }

    private fun unregisterSyncReceiver() {
        if (!syncReceiverRegistered) return
        unregisterReceiver(syncCompletedReceiver)
        syncReceiverRegistered = false
    }

    private fun buildContent(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(15, 22, 24)) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(36), dp(64), dp(36))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "PARENTAL TV"
            setTextColor(Color.rgb(205, 235, 220))
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(32), 1f))
        header.addView(TextView(this).apply {
            text = "APP v${packageManager.getPackageInfo(packageName, 0).versionName}"
            setTextColor(Color.rgb(160, 174, 174))
            textSize = 14f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        })
        content.addView(header)
        content.addView(View(this).apply {
            setBackgroundColor(Color.rgb(51, 65, 66))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(18)
            bottomMargin = dp(30)
        })

        val dashboard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val overview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(42), 0)
        }
        overview.addView(labelText("TODAY'S USAGE"), matchStartParams())
        usageStatus = statusText(34f).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setPadding(0, dp(14), 0, dp(10))
        }
        overview.addView(usageStatus, matchStartParams())
        usageProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Color.rgb(138, 205, 174))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(45, 58, 60))
        }
        overview.addView(usageProgress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)))
        settingsStatus = statusText(17f).apply {
            gravity = Gravity.START
            setTextColor(Color.rgb(191, 203, 200))
            setPadding(0, dp(24), 0, 0)
        }
        overview.addView(settingsStatus, matchStartParams())
        dashboard.addView(overview, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        dashboard.addView(View(this).apply {
            setBackgroundColor(Color.rgb(51, 65, 66))
        }, LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(42), 0, 0, 0)
        }
        controls.addView(labelText("DEVICE CONTROLS"), matchStartParams())
        permissionStatus = statusText(15f).apply { gravity = Gravity.START }
        passwordStatus = statusText(15f).apply { gravity = Gravity.START }
        adminStatus = statusText(15f).apply { gravity = Gravity.START }
        controls.addView(permissionStatus, matchStartParams())
        controls.addView(passwordStatus, matchStartParams())
        controls.addView(adminStatus, matchStartParams())

        val actionGrid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
            useDefaultMargins = false
            setPadding(0, dp(22), 0, 0)
        }
        val actions = listOf(
            menuButton("Set parent PIN", android.R.drawable.ic_lock_lock) { startPasswordSetup() },
            menuButton("Sync now", android.R.drawable.ic_popup_sync) { syncRemoteSettings() },
            menuButton("Advanced settings", android.R.drawable.ic_menu_preferences) { openAdvancedSettings() },
            menuButton("绑定账号", android.R.drawable.ic_menu_myplaces) { openAccountBinding() },
        )
        syncButton = actions[1]
        actions.forEachIndexed { index, button ->
            actionGrid.addView(button, GridLayout.LayoutParams(
                GridLayout.spec(index / 2, 1f),
                GridLayout.spec(index % 2, 1f),
            ).apply {
                width = 0
                height = dp(58)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            })
        }
        controls.addView(actionGrid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        syncStatus = statusText(15f).apply {
            gravity = Gravity.START
            setTextColor(Color.rgb(160, 174, 174))
            setPadding(dp(5), dp(10), 0, 0)
            text = "Questions and rules sync automatically."
        }
        controls.addView(syncStatus, matchStartParams())
        dashboard.addView(controls, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        content.addView(dashboard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        scroll.addView(content)
        return scroll
    }

    private fun labelText(label: String) = TextView(this).apply {
        text = label
        setTextColor(Color.rgb(138, 205, 174))
        textSize = 13f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(12))
    }

    private fun menuButton(label: String, iconResource: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        isFocusable = true
        stateListAnimator = null
        minHeight = dp(58)
        minWidth = 0
        compoundDrawablePadding = dp(10)
        setPadding(dp(12), 0, dp(8), 0)
        applyFocusStyle(this, false)
        val icon = getDrawable(iconResource)?.mutate()?.apply { setTint(Color.rgb(188, 207, 198)) }
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
        setOnFocusChangeListener { view, focused -> applyFocusStyle(view as Button, focused) }
        setOnClickListener { onClick() }
    }

    private fun applyFocusStyle(button: Button, focused: Boolean) {
        val foreground = if (focused) Color.rgb(15, 34, 30) else Color.rgb(235, 241, 237)
        button.background = GradientDrawable().apply {
            setColor(if (focused) Color.rgb(138, 205, 174) else Color.rgb(27, 37, 39))
            setStroke(dp(if (focused) 3 else 1), if (focused) Color.rgb(239, 185, 104) else Color.rgb(67, 84, 84))
            cornerRadius = dp(5).toFloat()
        }
        button.setTextColor(foreground)
        button.compoundDrawablesRelative.firstOrNull()?.setTint(foreground)
    }

    private fun statusText(size: Float) = TextView(this).apply {
        setTextColor(Color.rgb(194, 207, 202))
        textSize = size
        gravity = Gravity.START
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun centeredParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun matchStartParams() = centeredParams().apply { gravity = Gravity.START }

    private fun refreshStatus() {
        val settings = ControlState.settings(this)
        val dailyLimit = ControlState.todayDailyLimitMinutes(this)
        usageStatus.text = "${ControlState.todayUsageLabel(this)} / $dailyLimit min"
        settingsStatus.text = "Question every ${settings.questionIntervalMinutes} min\nIncorrect-answer lock: ${settings.wrongLockMinutes} min\nSync every ${settings.syncIntervalMinutes} min · ${QuestionBank.all(this).size} questions"
        val dailyLimitMillis = dailyLimit * 60_000L + ControlState.tempExtension(this)
        usageProgress.progress = if (dailyLimitMillis > 0L) {
            ((ControlState.usedMillis(this) * 1000L) / dailyLimitMillis).toInt().coerceIn(0, 1000)
        } else {
            1000
        }
        val hasMediaAccess = MediaPlaybackController.hasAccess(this)
        permissionStatus.text = "Overlay · ${if (canDrawOverlays()) "Enabled" else "Needs access"}\nPause media · ${if (hasMediaAccess) "Enabled" else "Needs access"}"
        permissionStatus.setTextColor(if (canDrawOverlays() && hasMediaAccess) Color.rgb(138, 205, 174) else Color.rgb(239, 185, 104))
        passwordStatus.text = if (ControlState.hasUnlockPassword(this)) "Parent PIN · Set" else "Parent PIN · Not set"
        passwordStatus.setTextColor(if (ControlState.hasUnlockPassword(this)) Color.rgb(138, 205, 174) else Color.rgb(239, 185, 104))
        adminStatus.text = if (ControlState.isDeviceAdminActive(this)) {
            "Uninstall protection · On"
        } else {
            "Uninstall protection · Off"
        }
        adminStatus.setTextColor(if (ControlState.isDeviceAdminActive(this)) Color.rgb(138, 205, 174) else Color.rgb(160, 174, 174))
        adminStatus.append("\nAccount · ${DeviceBinding.username(this).ifBlank { "Not linked" }}")
    }

    private fun syncRemoteSettings() {
        syncButton.isEnabled = false
        syncStatus.text = "Connecting to server…"
        RemoteSync.syncAsync(this) { result ->
            syncButton.isEnabled = true
            syncStatus.text = if (result.success) {
                if (result.settingsChanged) QuestionAlarmReceiver.scheduleForCurrentState(this)
                "${result.message.removeSuffix(".")} · Rules updated."
            } else {
                result.message
            }
            refreshStatus()
        }
    }

    private fun openAccountBinding() {
        if (bindingDialog?.isShowing == true) return
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(28), dp(36), dp(28))
            setBackgroundColor(Color.rgb(22, 34, 36))
        }
        box.addView(statusText(26f).apply {
            text = "绑定账号"
            gravity = Gravity.CENTER
        }, centeredParams())
        bindingText = statusText(42f).apply {
            text = "正在获取绑定码…"
            gravity = Gravity.CENTER
        }
        box.addView(bindingText, centeredParams())
        bindingHint = statusText(17f).apply { gravity = Gravity.CENTER }
        box.addView(bindingHint, centeredParams())
        val close = menuButton("关闭", android.R.drawable.ic_menu_close_clear_cancel) { dialog.dismiss() }
        box.addView(close, centeredParams())
        dialog.setContentView(box)
        dialog.setOnDismissListener {
            statusHandler.removeCallbacks(bindingPoll)
            bindingDialog = null
            bindingText = null
            bindingHint = null
        }
        bindingDialog = dialog
        bindingCodeExpiresAt = 0L
        bindingShownBound = false
        dialog.show()
        dialog.window?.setLayout(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT)
        close.requestFocus()
        loadBindingCode(requestCode = true)
        statusHandler.postDelayed(bindingPoll, 3_000L)
    }

    private fun loadBindingCode(requestCode: Boolean = false) {
        if (bindingRequestInProgress) return
        val dialog = bindingDialog ?: return
        bindingRequestInProgress = true
        val appContext = applicationContext
        Thread {
            val result = runCatching { RemoteSync.refreshBinding(appContext, requestCode) }
            statusHandler.post {
                bindingRequestInProgress = false
                if (isFinishing || isDestroyed || bindingDialog !== dialog || !dialog.isShowing) return@post
                result.onSuccess { state ->
                    if (state.getBoolean("bound")) {
                        val newlyLinked = !bindingShownBound
                        bindingShownBound = true
                        bindingText?.text = "已绑定 ${state.getString("username")}"
                        bindingHint?.text = "更换账号请先在后台解绑此电视。"
                        if (newlyLinked) syncRemoteSettings()
                    } else {
                        val code = state.optString("code").takeUnless { state.isNull("code") || it.isBlank() }
                        bindingShownBound = false
                        bindingCodeExpiresAt = state.optLong("expiresAt")
                        val seconds = ((bindingCodeExpiresAt - System.currentTimeMillis()) / 1_000).coerceAtLeast(0)
                        bindingText?.text = code ?: "正在更新绑定码…"
                        bindingHint?.text = "登录 tvparental.mediainsight360.com\n在「设备与重置」中输入绑定码" +
                            if (code != null) "\n有效期 ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}，过期自动更新" else ""
                    }
                    refreshStatus()
                }.onFailure {
                    if (it.message == DeviceBinding.CREDENTIALS_CHANGED_MESSAGE) {
                        bindingText?.text = "需要解绑旧设备"
                        bindingHint?.text = "请在原账号后台解绑此电视，随后会自动重试。"
                    } else {
                        bindingText?.text = "暂时无法连接"
                        bindingHint?.text = if (it is IllegalStateException) it.message else "请检查网络，稍后将自动重试。"
                    }
                }
            }
        }.start()
    }

    override fun onDestroy() {
        bindingDialog?.dismiss()
        statusHandler.removeCallbacks(bindingPoll)
        super.onDestroy()
    }

    private fun refreshUsageDisplay() {
        val intent = Intent(this, UsageTrackingService::class.java)
            .setAction(UsageTrackingService.ACTION_REFRESH_DISPLAY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun startPasswordSetup() {
        if (!ControlState.hasUnlockPassword(this)) {
            enterNewPassword()
            return
        }
        askPassword("Verify current PIN", "Enter your 6-direction parent PIN.", true) { entered, pad, hint, dialog ->
            if (ControlState.verifyUnlockPassword(this, entered)) {
                dialog.dismiss()
                enterNewPassword()
            } else {
                hint.text = "Incorrect PIN. Try again."
                pad.reset()
            }
        }
    }

    private fun openAdvancedSettings() {
        if (!ControlState.hasUnlockPassword(this)) {
            Toast.makeText(this, "Set a parent PIN before opening advanced settings.", Toast.LENGTH_LONG).show()
            return
        }
        askPassword("Advanced settings", "Enter your 6-direction parent PIN.", true) { entered, pad, hint, passwordDialog ->
            if (ControlState.verifyUnlockPassword(this, entered)) {
                passwordDialog.dismiss()
                showAdvancedSettings()
            } else {
                hint.text = "Incorrect PIN. Try again."
                pad.reset()
            }
        }
    }

    private fun showAdvancedSettings() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(22))
            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 34, 36))
                setStroke(dp(1), Color.rgb(67, 84, 84))
                cornerRadius = dp(12).toFloat()
            }
        }
        box.addView(TextView(this).apply {
            text = "Advanced settings"
            setTextColor(Color.WHITE)
            textSize = 26f
            setPadding(0, 0, 0, dp(4))
        }, centeredParams())
        box.addView(statusText(14f).apply {
            text = "↑ ↓ Select   ·   OK Toggle   ·   Back Close"
            setPadding(0, 0, 0, dp(14))
        }, centeredParams())
        val options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val startupQuestion = settingsToggle(
            "Questions on first startup of the day", "Ask once on the first startup each day.",
            ControlState.showQuestionOnStartup(this),
        ) { enabled -> ControlState.setShowQuestionOnStartup(this, enabled) }
        val alwaysShowRemainingTime = settingsToggle(
            "Always show time remaining", "Keep the remaining-time display visible while watching.",
            ControlState.alwaysShowRemainingTime(this),
        ) { enabled ->
            ControlState.setAlwaysShowRemainingTime(this, enabled)
            refreshUsageDisplay()
        }
        val blockRecentWatch = settingsToggle(
            "Block Recent Watch button", "Prevent the remote shortcut from opening Recent Watch.",
            ControlState.blockRecentWatch(this),
        ) { enabled ->
            ControlState.setBlockRecentWatch(this, enabled)
            refreshRemoteButtonStatus()
        }
        val blockLiveTv = settingsToggle(
            "Block Live TV button", "Prevent the remote shortcut from opening Live TV.",
            ControlState.blockLiveTv(this),
        ) { enabled ->
            ControlState.setBlockLiveTv(this, enabled)
            refreshRemoteButtonStatus()
        }
        val toggles = listOf(startupQuestion, alwaysShowRemainingTime, blockRecentWatch, blockLiveTv)
        toggles.forEach { row ->
            options.addView(row, centeredParams().apply { bottomMargin = dp(10) })
        }
        remoteButtonStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(185, 199, 191))
            setPadding(dp(4), dp(6), dp(4), dp(12))
        }
        options.addView(remoteButtonStatus, centeredParams())
        refreshRemoteButtonStatus()
        box.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(options)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val done = menuButton("Done", android.R.drawable.ic_menu_close_clear_cancel) { dialog.dismiss() }
        box.addView(done, LinearLayout.LayoutParams(dp(220), dp(54)).apply {
            gravity = Gravity.END
            topMargin = dp(14)
        })
        val focusOrder = toggles + done
        focusOrder.forEach { it.id = View.generateViewId() }
        focusOrder.forEachIndexed { index, view ->
            view.nextFocusLeftId = view.id
            view.nextFocusRightId = view.id
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    val next = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) index - 1 else index + 1
                    focusOrder[next.coerceIn(0, focusOrder.lastIndex)].requestFocus()
                    true
                } else false
            }
        }
        dialog.setOnDismissListener { remoteButtonStatus = null }
        dialog.setContentView(box)
        dialog.setOnShowListener { startupQuestion.requestFocus() }
        dialog.show()
        dialog.window?.setLayout(
            minOf(dp(860), (resources.displayMetrics.widthPixels * 0.9f).toInt()),
            (resources.displayMetrics.heightPixels * 0.9f).toInt(),
        )
    }

    private fun settingsToggle(label: String, description: String, initial: Boolean, onChanged: (Boolean) -> Unit): View {
        var enabled = initial
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isFocusable = true
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = statusText(19f).apply {
            text = label
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(4))
        }
        labels.addView(title, centeredParams())
        labels.addView(statusText(14f).apply {
            text = description
            setPadding(0, 0, dp(16), 0)
        }, centeredParams())
        row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val state = TextView(this).apply {
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        row.addView(state, LinearLayout.LayoutParams(dp(68), dp(34)))
        fun render() {
            row.background = GradientDrawable().apply {
                setColor(if (row.hasFocus()) Color.rgb(37, 63, 56) else Color.rgb(27, 37, 39))
                setStroke(dp(if (row.hasFocus()) 3 else 1),
                    if (row.hasFocus()) Color.rgb(138, 205, 174) else Color.rgb(67, 84, 84))
                cornerRadius = dp(8).toFloat()
            }
            state.text = if (enabled) "ON" else "OFF"
            state.setTextColor(if (enabled) Color.rgb(15, 34, 30) else Color.rgb(194, 207, 202))
            state.background = GradientDrawable().apply {
                setColor(if (enabled) Color.rgb(138, 205, 174) else Color.rgb(49, 59, 62))
                cornerRadius = dp(17).toFloat()
            }
            row.contentDescription = "$label. $description ${if (enabled) "On" else "Off"}. Press OK to toggle."
        }
        row.setOnFocusChangeListener { _, _ -> render() }
        row.setOnClickListener {
            enabled = !enabled
            onChanged(enabled)
            render()
        }
        render()
        return row
    }

    private fun refreshRemoteButtonStatus() {
        remoteButtonStatus?.text = when {
            !ControlState.blockRecentWatch(this) && !ControlState.blockLiveTv(this) -> "Remote button blocking is off."
            !RecentWatchGuardService.hasAccess(this) -> "Not active: enable Parental TV remote button control in system accessibility settings."
            else -> "Remote button control is active. The selected shortcuts are blocked."
        }
    }

    private fun enterNewPassword() {
        askPassword("Set parent PIN", "Enter a 6-direction PIN using the remote.", true) { first, _, _, dialog ->
            dialog.dismiss()
            askPassword("Confirm parent PIN", "Enter the same sequence again.", true) { second, pad, hint, confirmDialog ->
                if (second == first) {
                    ControlState.setUnlockPassword(this, first)
                    confirmDialog.dismiss()
                    activateDeviceAdmin()
                } else {
                    hint.text = "The sequences do not match. Try again."
                    pad.reset()
                }
            }
        }
    }

    private fun activateDeviceAdmin() {
        if (!ControlState.isDeviceAdminActive(this)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ControlState.getDeviceAdminComponent(this@MainActivity))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "A parent PIN is required to turn off uninstall protection before removing this app.")
            }
            try {
                startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN)
            } catch (e: Exception) {
                refreshStatus()
                Toast.makeText(this, "Parent PIN saved.", Toast.LENGTH_LONG).show()
            }
        } else {
            refreshStatus()
            Toast.makeText(this, "Parent PIN saved.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_ENABLE_ADMIN) {
            refreshStatus()
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Parent PIN and uninstall protection are enabled.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Parent PIN saved. Uninstall protection is off.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun askPassword(
        title: String,
        message: String,
        masked: Boolean,
        onEntered: (entered: String, pad: PasswordPad, hint: TextView, dialog: Dialog) -> Unit,
    ) {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(36), dp(48), dp(36))
            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 34, 36))
                setStroke(dp(2), Color.rgb(210, 239, 111))
                cornerRadius = dp(16).toFloat()
            }
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
        }, centeredParams())
        val hintView = TextView(this).apply {
            text = message
            setTextColor(Color.rgb(193, 203, 194))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
        }
        box.addView(hintView, centeredParams())
        lateinit var pad: PasswordPad
        pad = PasswordPad(this, masked) { entered -> onEntered(entered, pad, hintView, dialog) }
        box.addView(pad, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        box.addView(TextView(this).apply {
            text = "Use the D-pad · OK clears · Back cancels"
            setTextColor(Color.rgb(150, 160, 152))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }, centeredParams())
        dialog.setContentView(box)
        dialog.setOnShowListener { pad.requestFocus() }
        dialog.show()
        dialog.window?.setLayout(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun showQuestionNow() {
        if (!canDrawOverlays()) return
        val intent = Intent(this, QuestionOverlayService::class.java)
            .setAction(QuestionOverlayService.ACTION_SHOW_QUESTION)
            .putExtra(QuestionOverlayService.EXTRA_TRIGGER, QuestionBank.TRIGGER_STARTUP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun startUsageTracking() {
        val intent = Intent(this, UsageTrackingService::class.java)
            .setAction(UsageTrackingService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
