package com.example.tvparental

import android.content.SharedPreferences

internal object RewardTimeLedger {
    const val GRANTED = "reward_granted_millis"
    const val SPENT = "reward_spent_millis"
    const val USED_TODAY = "reward_used_millis"
    const val ACTIVATED = "reward_activated"
    const val DEFAULT_DAILY_LIMIT_MINUTES = 30

    fun balance(preferences: SharedPreferences): Long =
        (preferences.getLong(GRANTED, 0L) - preferences.getLong(SPENT, 0L)).coerceAtLeast(0L)

    fun availableToday(preferences: SharedPreferences, dailyLimitMillis: Long): Long =
        minOf(balance(preferences), (dailyLimitMillis - preferences.getLong(USED_TODAY, 0L)).coerceAtLeast(0L))

    fun clearActivation(preferences: SharedPreferences) {
        if (!preferences.contains(ACTIVATED)) return
        check(preferences.edit().remove(ACTIVATED).commit()) { "Could not clear reward time activation." }
    }

    fun reconcile(preferences: SharedPreferences, sameDay: Boolean, granted: Long, spent: Long, usedToday: Long): Boolean {
        val localGranted = preferences.getLong(GRANTED, 0L)
        val localSpent = preferences.getLong(SPENT, 0L)
        val localToday = preferences.getLong(USED_TODAY, 0L)
        val newGranted = maxOf(localGranted, granted)
        val newSpent = maxOf(localSpent, spent)
        val newToday = if (sameDay) maxOf(localToday, usedToday) else localToday
        if (newGranted == localGranted && newSpent == localSpent && newToday == localToday) return false
        check(preferences.edit().putLong(GRANTED, newGranted).putLong(SPENT, newSpent)
            .putLong(USED_TODAY, newToday).commit()) { "Could not save reward time." }
        return true
    }
}
