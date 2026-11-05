package com.example.tvparental

import android.app.Activity
import android.app.Dialog
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
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
    private lateinit var permissionButton: Button
    private lateinit var testButton: Button
    private lateinit var syncButton: Button
    private var autoOpenedOverlaySettings = false

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
        refreshStatus()
        permissionButton.post { permissionButton.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        registerSyncReceiver()
        statusHandler.removeCallbacks(statusRefresh)
        statusRefresh.run()
        if (!canDrawOverlays() && !autoOpenedOverlaySettings) {
            autoOpenedOverlaySettings = true
            openOverlaySettings()
        }
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusRefresh)
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
            menuButton("Overlay access", android.R.drawable.ic_menu_manage) { openOverlaySettings() },
            menuButton("Set parent PIN", android.R.drawable.ic_lock_lock) { startPasswordSetup() },
            menuButton("Test question", android.R.drawable.ic_media_play) { showQuestionNow() },
            menuButton("Sync now", android.R.drawable.ic_popup_sync) { syncRemoteSettings() },
        )
        permissionButton = actions[0]
        testButton = actions[2]
        syncButton = actions[3]
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
        controls.addView(Switch(this).apply {
            text = "Always show time remaining"
            textSize = 16f
            setTextColor(Color.rgb(235, 241, 237))
            isChecked = ControlState.alwaysShowRemainingTime(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                ControlState.setAlwaysShowRemainingTime(this@MainActivity, enabled)
                refreshUsageDisplay()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(8)
        })
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
        permissionStatus.text = if (canDrawOverlays()) "Overlay permission · Enabled" else "Overlay permission · Needs access"
        permissionStatus.setTextColor(if (canDrawOverlays()) Color.rgb(138, 205, 174) else Color.rgb(239, 185, 104))
        passwordStatus.text = if (ControlState.hasUnlockPassword(this)) "Parent PIN · Set" else "Parent PIN · Not set"
        passwordStatus.setTextColor(if (ControlState.hasUnlockPassword(this)) Color.rgb(138, 205, 174) else Color.rgb(239, 185, 104))
        adminStatus.text = if (ControlState.isDeviceAdminActive(this)) {
            "Uninstall protection · On"
        } else {
            "Uninstall protection · Off"
        }
        adminStatus.setTextColor(if (ControlState.isDeviceAdminActive(this)) Color.rgb(138, 205, 174) else Color.rgb(160, 174, 174))
        testButton.isEnabled = canDrawOverlays()
        testButton.alpha = if (canDrawOverlays()) 1f else 0.45f
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

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Overlay settings are unavailable. Enable overlay access in system app settings.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showQuestionNow() {
        if (!canDrawOverlays()) {
            openOverlaySettings()
            return
        }
        val intent = Intent(this, QuestionOverlayService::class.java)
            .setAction(QuestionOverlayService.ACTION_SHOW_QUESTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun startUsageTracking() {
        val intent = Intent(this, UsageTrackingService::class.java)
            .setAction(UsageTrackingService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
