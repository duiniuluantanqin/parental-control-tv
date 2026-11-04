package com.example.tvparental

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView

/** 6 格方向键密码输入控件：上下左右各算一位，输满 6 位后回调 [onComplete]。 */
class PasswordPad(
    context: Context,
    private val masked: Boolean,
    private val onComplete: (String) -> Unit,
) : LinearLayout(context) {
    private val input = StringBuilder()
    private val slots = mutableListOf<TextView>()
    private val syncHandler = Handler(Looper.getMainLooper())
    private var resetReceiverRegistered = false
    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reset()
        }
    }
    private val syncWhileFocused = object : Runnable {
        override fun run() {
            if (!hasFocus()) return
            RemoteSync.syncAsync(context) { }
            syncHandler.postDelayed(this, PASSWORD_RESET_POLL_INTERVAL_MS)
        }
    }
    var inputEnabled = true

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        repeat(ControlState.PASSWORD_LENGTH) {
            val slot = TextView(context).apply {
                textSize = 30f
                gravity = Gravity.CENTER
            }
            slots += slot
            addView(
                slot,
                LinearLayout.LayoutParams(dp(60), dp(68)).apply { setMargins(dp(7), 0, dp(7), 0) },
            )
        }
        setOnFocusChangeListener { _, focused ->
            render()
            if (focused) startRemoteSync() else stopRemoteSync()
        }
        render()
    }

    private fun startRemoteSync() {
        if (!resetReceiverRegistered) {
            val filter = IntentFilter(RemoteSync.ACTION_PASSWORD_RESET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(resetReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(resetReceiver, filter)
            }
            resetReceiverRegistered = true
        }
        syncHandler.removeCallbacks(syncWhileFocused)
        syncWhileFocused.run()
    }

    private fun stopRemoteSync() {
        syncHandler.removeCallbacks(syncWhileFocused)
        if (resetReceiverRegistered) {
            context.unregisterReceiver(resetReceiver)
            resetReceiverRegistered = false
        }
    }

    override fun onDetachedFromWindow() {
        stopRemoteSync()
        super.onDetachedFromWindow()
    }

    fun reset() {
        input.setLength(0)
        render()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val symbol = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 'U'
            KeyEvent.KEYCODE_DPAD_DOWN -> 'D'
            KeyEvent.KEYCODE_DPAD_LEFT -> 'L'
            KeyEvent.KEYCODE_DPAD_RIGHT -> 'R'
            else -> null
        }
        if (symbol != null) {
            if (event.repeatCount == 0 && inputEnabled && input.length < ControlState.PASSWORD_LENGTH) {
                input.append(symbol)
                render()
                if (input.length == ControlState.PASSWORD_LENGTH) onComplete(input.toString())
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (inputEnabled) reset()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun render() {
        slots.forEachIndexed { index, slot ->
            val filled = index < input.length
            val active = hasFocus() && index == input.length
            slot.text = when {
                !filled -> ""
                masked -> "●"
                else -> arrow(input[index])
            }
            slot.setTextColor(Color.rgb(24, 31, 25))
            slot.background = GradientDrawable().apply {
                setColor(if (filled) Color.rgb(210, 239, 111) else Color.rgb(244, 246, 240))
                setStroke(dp(if (active) 4 else 1), if (active) Color.rgb(90, 160, 40) else Color.rgb(174, 184, 174))
                cornerRadius = dp(8).toFloat()
            }
        }
    }

    private fun arrow(symbol: Char): String = when (symbol) {
        'U' -> "↑"
        'D' -> "↓"
        'L' -> "←"
        else -> "→"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PASSWORD_RESET_POLL_INTERVAL_MS = 30_000L
    }
}
