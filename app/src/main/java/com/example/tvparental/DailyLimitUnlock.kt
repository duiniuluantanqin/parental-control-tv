package com.example.tvparental

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

/** A parent unlock survives app recovery, but ends at standby or the next boot. */
internal object DailyLimitUnlock {
    const val UNLOCKED_BOOT = "daily_limit_unlocked_boot"

    fun isActive(context: Context): Boolean = isActive(preferences(context), bootCount(context))

    fun setActive(context: Context, active: Boolean): Boolean =
        setActive(preferences(context), bootCount(context), active)

    internal fun isActive(preferences: SharedPreferences, boot: Int): Boolean =
        boot >= 0 && preferences.getInt(UNLOCKED_BOOT, -1) == boot

    internal fun setActive(preferences: SharedPreferences, boot: Int, active: Boolean): Boolean {
        if (active && boot < 0) return false
        val editor = preferences.edit()
        if (active) editor.putInt(UNLOCKED_BOOT, boot) else editor.remove(UNLOCKED_BOOT)
        return editor.commit()
    }

    private fun preferences(context: Context) = context.getSharedPreferences("parental_control", Context.MODE_PRIVATE)

    private fun bootCount(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
}
