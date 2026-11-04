package com.example.tvparental

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class VerifyPasswordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(18, 24, 25))
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }

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
            text = "Verify the parent PIN to uninstall"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
        }, matchParams())

        val hintView = TextView(this).apply {
            text = "Enter the 6-direction PIN using the remote"
            setTextColor(Color.rgb(193, 203, 194))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
        }
        box.addView(hintView, matchParams())

        lateinit var pad: PasswordPad
        pad = PasswordPad(this, true) { entered ->
            if (ControlState.verifyUnlockPassword(this, entered)) {
                ParentalDeviceAdminReceiver.removeDeviceAdmin(this)
                Toast.makeText(this, "Verified. Uninstall protection is off.", Toast.LENGTH_LONG).show()
                finish()
            } else {
                hintView.text = "Incorrect PIN. Try again."
                pad.reset()
            }
        }
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
        }, matchParams())

        root.addView(box, LinearLayout.LayoutParams(dp(760), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        pad.post { pad.requestFocus() }
        return root
    }

    private fun matchParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
