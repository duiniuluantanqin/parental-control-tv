package com.example.tvparental

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.io.FileDescriptor
import java.io.PrintWriter

/** Only filters TCL's Recent Watch key and its confirmed destination; never reads window content. */
class RecentWatchGuardService : AccessibilityService() {
    private var blockedPresses = 0
    private var closedPages = 0
    private var lastKeyCode = -1
    private var lastScanCode = -1
    private var lastCloseAt = Long.MIN_VALUE / 2
    private val settingChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ControlState.BLOCK_RECENT_WATCH_KEY) updateKeyFilter()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ControlState.observeRemoteButtonSetting(this, settingChanged)
        updateKeyFilter()
    }

    private fun updateKeyFilter() {
        val info = serviceInfo ?: return
        val filter = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.flags = if (ControlState.blockRecentWatch(this)) info.flags or filter else info.flags and filter.inv()
        serviceInfo = info
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!ControlState.blockRecentWatch(this)) return false
        lastKeyCode = event.keyCode
        lastScanCode = event.scanCode
        val recentWatch = event.scanCode == TCL_RECENT_WATCH_SCAN_CODE ||
            KeyEvent.keyCodeToString(event.keyCode) == "KEYCODE_TCL_RECENT_WATCH"
        if (!recentWatch) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) blockedPresses++
        return true // Swallow both down and up; pass Home, navigation, volume and app-switch through.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!ControlState.blockRecentWatch(this) ||
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.packageName?.toString() != TCL_LAUNCHER ||
            event.className?.toString() != RECENT_WATCH_PAGE
        ) return
        // Some TCL firmware launches vendor pages before accessibility sees the key.
        // Close only the observed Recent Watch window, without changing or disabling the launcher.
        val now = SystemClock.uptimeMillis()
        if (now - lastCloseAt < 500L) return
        lastCloseAt = now
        if (performGlobalAction(GLOBAL_ACTION_BACK)) closedPages++
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        ControlState.stopObservingRemoteButtonSetting(this, settingChanged)
        super.onDestroy()
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        writer.println("Recent Watch guard: enabled=${ControlState.blockRecentWatch(this)}")
        writer.println("blockedPresses=$blockedPresses closedPages=$closedPages lastKeyCode=$lastKeyCode lastScanCode=$lastScanCode")
    }

    companion object {
        private const val TCL_RECENT_WATCH_SCAN_CODE = 613
        private const val TCL_LAUNCHER = "com.tcl.cyberui"
        private const val RECENT_WATCH_PAGE = "com.tcl.cyberui.recents.PluginMainActivity"

        fun hasAccess(context: Context): Boolean {
            val me = ComponentName(context, RecentWatchGuardService::class.java)
            return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
