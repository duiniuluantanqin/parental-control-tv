package com.example.tvparental

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

/** A pause may survive app recovery, but can only be used in the boot that created it. */
internal object UsageTimingPause {
    private const val PAUSED_BOOT = "paused_boot"

    fun isPaused(context: Context): Boolean = isPaused(preferences(context), bootCount(context))

    fun setPaused(context: Context, paused: Boolean): Boolean =
        setPaused(preferences(context), bootCount(context), paused)

    internal fun isPaused(preferences: SharedPreferences, boot: Int): Boolean =
        boot >= 0 && preferences.getInt(PAUSED_BOOT, -1) == boot

    internal fun setPaused(preferences: SharedPreferences, boot: Int, paused: Boolean): Boolean {
        if (paused && boot < 0) return false
        val editor = preferences.edit()
        if (paused) editor.putInt(PAUSED_BOOT, boot) else editor.remove(PAUSED_BOOT)
        return editor.commit()
    }

    private fun preferences(context: Context) = context.getSharedPreferences("usage_timing", Context.MODE_PRIVATE)

    private fun bootCount(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
}
