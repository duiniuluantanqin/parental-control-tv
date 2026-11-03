package com.example.tvparental

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var usageStatus: TextView
    private lateinit var settingsStatus: TextView
    private lateinit var syncStatus: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var testButton: Button
    private lateinit var syncButton: Button
    private var autoOpenedOverlaySettings = false

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
        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 36f
            gravity = Gravity.CENTER
        }, centeredParams())

        usageStatus = statusText(26f)
        usageStatus.setPadding(0, dp(24), 0, dp(8))
        content.addView(usageStatus, centeredParams())

        settingsStatus = statusText(18f)
        content.addView(settingsStatus, centeredParams())

        syncStatus = statusText(16f)
        content.addView(syncStatus, centeredParams())

        permissionStatus = statusText(18f)
        permissionStatus.setPadding(0, dp(16), 0, dp(16))
        content.addView(permissionStatus, centeredParams())

        val permissionButton = Button(this).apply {
            text = "打开全屏显示权限"
            textSize = 18f
            isFocusable = true
            setOnClickListener { openOverlaySettings() }
        }
        content.addView(permissionButton, buttonParams())

        testButton = Button(this).apply {
            text = "立即答题测试"
            textSize = 20f
            isFocusable = true
            setOnClickListener { showQuestionNow() }
        }
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

        syncButton = Button(this).apply {
            text = "立即同步题目和规则"
            textSize = 18f
            isFocusable = true
            setOnClickListener { syncRemoteSettings() }
        }
        content.addView(syncButton, buttonParams())

        scroll.addView(content)
        return scroll
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
    ).apply { setMargins(0, dp(6), 0, dp(6)) }

    private fun refreshStatus() {
        val settings = ControlState.settings(this)
        usageStatus.text = "今日已使用 ${ControlState.todayUsageLabel(this)} / ${settings.dailyLimitMinutes} 分钟"
        settingsStatus.text = "每 ${settings.questionIntervalMinutes} 分钟答题 · 答错锁定 ${settings.wrongLockMinutes} 分钟 · 每 ${settings.syncIntervalMinutes} 分钟同步 · 题库 ${QuestionBank.all(this).size} 道"
        permissionStatus.text = if (canDrawOverlays()) "全屏答题权限：已开启" else "全屏答题权限：未开启"
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

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
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
