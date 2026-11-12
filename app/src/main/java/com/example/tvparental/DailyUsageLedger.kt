package com.example.tvparental

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Locale

/** Keeps recent daily totals when the TV clock changes during boot or network time sync. */
internal object DailyUsageLedger {
    private const val DAY = "usage_day"
    private const val USED = "used_millis"
    private const val EXTENSION = "temp_extension_millis"
    private const val RESET_ID = "usage_reset_id"
    private const val RESET_AT = "usage_reset_at"
    private const val HISTORY = "usage_history_days"
    private val dailyKeys = listOf(USED, EXTENSION, RESET_ID, RESET_AT)
    private const val HISTORY_SIZE = 32

    fun localDay(now: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)

    fun ensureDay(preferences: SharedPreferences, now: Long) {
        val today = localDay(now)
        val previous = preferences.getString(DAY, null)
        if (previous == today) return
        val history = preferences.getString(HISTORY, "").orEmpty().split(',').filter { it.isNotBlank() }
        // An older, unseen date during boot is not a new allowance day.
        if (previous != null && today < previous && today !in history) return

        val editor = preferences.edit()
        if (previous != null) {
            editor.putLong("$USED.$previous", preferences.getLong(USED, 0L))
                .putLong("$EXTENSION.$previous", preferences.getLong(EXTENSION, 0L))
                .putString("$RESET_ID.$previous", preferences.getString(RESET_ID, ""))
                .putLong("$RESET_AT.$previous", preferences.getLong(RESET_AT, 0L))
        }
        val restore = today in history
        editor.putString(DAY, today)
            .putLong(USED, if (restore) preferences.getLong("$USED.$today", 0L) else 0L)
            .putLong(EXTENSION, if (restore) preferences.getLong("$EXTENSION.$today", 0L) else 0L)
            .putString(RESET_ID, if (restore) preferences.getString("$RESET_ID.$today", "") else "")
            .putLong(RESET_AT, if (restore) preferences.getLong("$RESET_AT.$today", 0L) else 0L)

        val savedDays = (listOfNotNull(previous) + history).distinct().filter { it != today }
        editor.putString(HISTORY, savedDays.take(HISTORY_SIZE).joinToString(","))
        for (day in savedDays.drop(HISTORY_SIZE) + today) {
            dailyKeys.forEach { editor.remove("$it.$day") }
        }
        check(editor.commit()) { "Could not save daily usage." }
    }

    fun addUsage(preferences: SharedPreferences, elapsedMillis: Long, limitMillis: Long) {
        if (elapsedMillis <= 0L) return
        val current = preferences.getLong(USED, 0L).coerceAtLeast(0L)
        val added = minOf(elapsedMillis, (limitMillis - current).coerceAtLeast(0L))
        if (added == 0L) return
        check(preferences.edit().putLong(USED, current + added).commit()) { "Could not save screen time." }
    }
}
