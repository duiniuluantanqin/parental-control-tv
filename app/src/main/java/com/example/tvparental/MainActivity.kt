package com.example.tvparental

import android.app.Activity
import android.app.Dialog
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var usageStatus: TextView
    private lateinit var settingsStatus: TextView
    private lateinit var syncStatus: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var passwordStatus: TextView
    private lateinit var adminStatus: TextView
    private lateinit var permissionButton: Button
    private lateinit var testButton: Button
    private lateinit var syncButton: Button
    private var autoOpenedOverlaySettings = false

    companion object {
        private const val REQUEST_CODE_ENABLE_ADMIN = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        setContentView(buildContent())
        startUsageTracking()
        QuestionAlarmReceiver.ensureScheduled(this)
        refreshStatus()
        permissionButton.post { permissionButton.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (!canDrawOverlays() && !autoOpenedOverlaySettings) {
            autoOpenedOverlaySettings = true
            openOverlaySettings()
        }
    }

    private fun buildContent(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(18, 24, 25)) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(72), dp(40), dp(72), dp(40))
        }
        usageStatus = statusText(32f)
        usageStatus.setPadding(0, dp(16), 0, dp(24))
        content.addView(usageStatus, centeredParams())

        val versionText = TextView(this).apply {
            text = "版本 ${packageManager.getPackageInfo(packageName, 0).versionName}"
            setTextColor(Color.rgb(150, 160, 152))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(versionText, centeredParams())

        settingsStatus = statusText(18f)
        content.addView(settingsStatus, centeredParams())

        syncStatus = statusText(16f)
        content.addView(syncStatus, centeredParams())

        permissionStatus = statusText(18f)
        permissionStatus.setPadding(0, dp(16), 0, dp(4))
        content.addView(permissionStatus, centeredParams())

        passwordStatus = statusText(18f)
        passwordStatus.setPadding(0, 0, 0, dp(4))
        content.addView(passwordStatus, centeredParams())

        adminStatus = statusText(18f)
        adminStatus.setPadding(0, 0, 0, dp(16))
        content.addView(adminStatus, centeredParams())

        permissionButton = menuButton("打开悬浮窗权限设置", 18f) { openOverlaySettings() }
        content.addView(permissionButton, buttonParams())

        content.addView(menuButton("设置解锁密码（6 位方向键）", 18f) { startPasswordSetup() }, buttonParams())

        testButton = menuButton("立即答题测试", 20f) { showQuestionNow() }
        content.addView(testButton, buttonParams())

        val divider = TextView(this).apply {
            text = "远程家长后台"
            setTextColor(Color.rgb(210, 239, 111))
            textSize = 21f
            setPadding(0, dp(24), 0, dp(10))
        }
        content.addView(divider, matchStartParams())

        content.addView(statusText(16f).apply {
            text = "题目和规则自动同步，无需配置地址"
        }, centeredParams())

        syncButton = menuButton("立即同步题目和规则", 18f) { syncRemoteSettings() }
        content.addView(syncButton, buttonParams())

        scroll.addView(content)
        return scroll
    }

    private fun menuButton(label: String, size: Float, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = size
        isAllCaps = false
        isFocusable = true
        stateListAnimator = null
        minHeight = dp(64)
        applyFocusStyle(this, false)
        setOnFocusChangeListener { view, focused -> applyFocusStyle(view as Button, focused) }
        setOnClickListener { onClick() }
    }

    private fun applyFocusStyle(button: Button, focused: Boolean) {
        button.background = GradientDrawable().apply {
            setColor(if (focused) Color.rgb(210, 239, 111) else Color.rgb(48, 56, 58))
            setStroke(dp(if (focused) 4 else 1), if (focused) Color.WHITE else Color.rgb(92, 102, 104))
            cornerRadius = dp(10).toFloat()
        }
        button.setTextColor(if (focused) Color.rgb(24, 31, 25) else Color.WHITE)
    }

    private fun statusText(size: Float) = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = size
        gravity = Gravity.CENTER
    }

    private fun centeredParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun matchStartParams() = centeredParams().apply { gravity = Gravity.START }

    private fun buttonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(0, dp(8), 0, dp(8)) }

    private fun refreshStatus() {
        val settings = ControlState.settings(this)
        usageStatus.text = "今日已使用 ${ControlState.todayUsageLabel(this)} / ${settings.dailyLimitMinutes} 分钟"
        settingsStatus.text = "每 ${settings.questionIntervalMinutes} 分钟答题 · 答错锁定 ${settings.wrongLockMinutes} 分钟 · 每 ${settings.syncIntervalMinutes} 分钟同步 · 题库 ${QuestionBank.all(this).size} 道"
        permissionStatus.text = if (canDrawOverlays()) "悬浮答题权限：已开启" else "悬浮答题权限：未开启（请在系统页面中允许）"
        passwordStatus.text = if (ControlState.hasUnlockPassword(this)) "解锁密码：已设置" else "解锁密码：未设置（锁定后将无法用密码解锁）"
        adminStatus.text = if (ControlState.isDeviceAdminActive(this)) {
            "卸载保护：已启用（卸载前需输入密码）"
        } else {
            "卸载保护：未启用（设置密码后自动启用）"
        }
        testButton.isEnabled = canDrawOverlays()
        testButton.alpha = if (canDrawOverlays()) 1f else 0.45f
    }

    private fun syncRemoteSettings() {
        syncButton.isEnabled = false
        syncStatus.text = "正在连接后台…"
        RemoteSync.syncAsync(this) { result ->
            syncButton.isEnabled = true
            syncStatus.text = if (result.success) {
                if (result.settingsChanged) QuestionAlarmReceiver.scheduleForCurrentState(this)
                "${result.message}；规则已更新"
            } else {
                result.message
            }
            refreshStatus()
        }
    }

    private fun startPasswordSetup() {
        if (!ControlState.hasUnlockPassword(this)) {
            enterNewPassword()
            return
        }
        askPassword("验证当前密码", "请先输入当前的 6 位方向键密码", true) { entered, pad, hint, dialog ->
            if (ControlState.verifyUnlockPassword(this, entered)) {
                dialog.dismiss()
                enterNewPassword()
            } else {
                hint.text = "密码错误，请重新输入"
                pad.reset()
            }
        }
    }

    private fun enterNewPassword() {
        askPassword("设置解锁密码", "用方向键输入 6 位密码，例如 上 下 上 下 左 右", false) { first, _, _, dialog ->
            dialog.dismiss()
            askPassword("再次输入密码确认", "请按同样的顺序再输入一遍", false) { second, pad, hint, confirmDialog ->
                if (second == first) {
                    ControlState.setUnlockPassword(this, first)
                    confirmDialog.dismiss()
                    activateDeviceAdmin()
                } else {
                    hint.text = "两次输入不一致，请重新输入"
                    pad.reset()
                }
            }
        }
    }

    private fun activateDeviceAdmin() {
        if (!ControlState.isDeviceAdminActive(this)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ControlState.getDeviceAdminComponent(this@MainActivity))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "启用设备管理器后，卸载应用前需要先输入家长密码停用管理器")
            }
            try {
                startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN)
            } catch (e: Exception) {
                refreshStatus()
                Toast.makeText(this, "解锁密码已保存", Toast.LENGTH_LONG).show()
            }
        } else {
            refreshStatus()
            Toast.makeText(this, "解锁密码已保存", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_ENABLE_ADMIN) {
            refreshStatus()
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "解锁密码和卸载保护已启用", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "解锁密码已保存，但卸载保护未启用", Toast.LENGTH_LONG).show()
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
            text = "方向键输入 · OK 清空重输 · 返回键取消"
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
            Toast.makeText(this, "此设备没有悬浮窗权限设置页，请在系统设置的应用权限里手动开启", Toast.LENGTH_LONG).show()
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
