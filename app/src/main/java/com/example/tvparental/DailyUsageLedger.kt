package com.example.tvparental

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Locale

internal data class UsageInterval(val start: Long, val end: Long, val usageMillis: Long = end - start,
                                  val kind: String = REGULAR) {
    companion object {
        const val REGULAR = "regular"
        const val REWARD = "reward"
        const val ANSWERING = "answering"
        const val PARENT_UNLOCKED = "parent_unlocked"
    }
}

/** Keeps recent daily totals when the TV clock changes during boot or network time sync. */
internal object DailyUsageLedger {
    private const val DAY = "usage_day"
    private const val USED = "used_millis"
    private const val EXTENSION = "temp_extension_millis"
    private const val RESET_ID = "usage_reset_id"
    private const val RESET_AT = "usage_reset_at"
    private const val HISTORY = "usage_history_days"
    const val INTERVALS = "usage_intervals"
    private val dailyKeys = listOf(USED, EXTENSION, RESET_ID, RESET_AT, RewardTimeLedger.USED_TODAY, INTERVALS)
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
            val rewardUsed = preferences.getLong(RewardTimeLedger.USED_TODAY, 0L)
            if (rewardUsed > 0L) editor.putLong("${RewardTimeLedger.USED_TODAY}.$previous", rewardUsed)
            val intervals = preferences.getString(INTERVALS, "").orEmpty()
            if (intervals.isNotEmpty()) editor.putString("$INTERVALS.$previous", intervals)
        }
        val restore = today in history
        editor.putString(DAY, today)
            .putLong(USED, if (restore) preferences.getLong("$USED.$today", 0L) else 0L)
            .putLong(EXTENSION, if (restore) preferences.getLong("$EXTENSION.$today", 0L) else 0L)
            .putString(RESET_ID, if (restore) preferences.getString("$RESET_ID.$today", "") else "")
            .putLong(RESET_AT, if (restore) preferences.getLong("$RESET_AT.$today", 0L) else 0L)
            .putString(INTERVALS, if (restore) preferences.getString("$INTERVALS.$today", "") else "")
            .putLong(RewardTimeLedger.USED_TODAY,
                if (restore) preferences.getLong("${RewardTimeLedger.USED_TODAY}.$today", 0L) else 0L)
            .remove(RewardTimeLedger.ACTIVATED)

        val savedDays = (listOfNotNull(previous) + history).distinct().filter { it != today }
        editor.putString(HISTORY, savedDays.take(HISTORY_SIZE).joinToString(","))
        for (day in savedDays.drop(HISTORY_SIZE) + today) {
            dailyKeys.forEach { editor.remove("$it.$day") }
        }
        check(editor.commit()) { "Could not save daily usage." }
    }

    /** Returns true when the sample exhausts the accepted reward time. */
    fun addUsage(preferences: SharedPreferences, elapsedMillis: Long, limitMillis: Long, rewardLimitMillis: Long = 0L,
                 usageIntervals: List<UsageInterval> = emptyList()): Boolean {
        if (elapsedMillis <= 0L) return false
        val current = preferences.getLong(USED, 0L).coerceAtLeast(0L)
        val regular = minOf(elapsedMillis, (limitMillis - current).coerceAtLeast(0L))
        val rewardRemaining = if (preferences.getBoolean(RewardTimeLedger.ACTIVATED, false)) {
            RewardTimeLedger.availableToday(preferences, rewardLimitMillis)
        } else 0L
        val reward = minOf(elapsedMillis - regular, rewardRemaining)
        if (regular == 0L && reward == 0L) return false
        val editor = preferences.edit().putLong(USED, current + regular)
        if (usageIntervals.isNotEmpty()) {
            val saved = intervals(preferences).toMutableList()
            var regularRemaining = regular
            var rewardToRecord = reward
            for (interval in usageIntervals) {
                val regularPart = minOf(regularRemaining, interval.end - interval.start)
                if (regularPart > 0L) {
                    appendInterval(saved, UsageInterval(interval.start, interval.start + regularPart))
                    regularRemaining -= regularPart
                }
                val rewardPart = minOf(rewardToRecord, interval.end - interval.start - regularPart)
                if (rewardPart > 0L) {
                    appendInterval(saved, UsageInterval(interval.start + regularPart, interval.start + regularPart + rewardPart,
                        kind = UsageInterval.REWARD))
                    rewardToRecord -= rewardPart
                }
                if (regularRemaining == 0L && rewardToRecord == 0L) break
            }
            editor.putString(INTERVALS, encodeIntervals(saved))
        }
        val rewardExhausted = reward > 0L && reward == rewardRemaining
        if (reward > 0L) {
            editor.putLong(RewardTimeLedger.SPENT, preferences.getLong(RewardTimeLedger.SPENT, 0L) + reward)
                .putLong(RewardTimeLedger.USED_TODAY, preferences.getLong(RewardTimeLedger.USED_TODAY, 0L) + reward)
            if (rewardExhausted) editor.remove(RewardTimeLedger.ACTIVATED)
        }
        check(editor.commit()) { "Could not save screen time." }
        return rewardExhausted
    }

    fun addActivity(preferences: SharedPreferences, periods: List<UsageInterval>, kind: String) {
        if (periods.isEmpty()) return
        val saved = intervals(preferences).toMutableList()
        periods.forEach { appendInterval(saved, it.copy(kind = kind)) }
        check(preferences.edit().putString(INTERVALS, encodeIntervals(saved)).commit()) { "Could not save activity time." }
    }

    private fun appendInterval(saved: MutableList<UsageInterval>, interval: UsageInterval) {
        val last = saved.lastOrNull()
        if (last != null && last.kind == interval.kind && interval.start >= last.end - 1_000L && interval.start <= last.end + 120_000L) {
            val total = last.usageMillis + interval.usageMillis
            saved[saved.lastIndex] = last.copy(end = maxOf(last.end, interval.end, last.start + total), usageMillis = total)
        } else {
            saved += interval
        }
    }

    fun encodeIntervals(intervals: List<UsageInterval>): String =
        intervals.joinToString(",") { "${it.start}:${it.end}:${it.usageMillis}:${it.kind}" }

    fun intervals(preferences: SharedPreferences): List<UsageInterval> =
        preferences.getString(INTERVALS, "").orEmpty().split(',').filter { it.isNotEmpty() }.map {
            val parts = it.split(':')
            UsageInterval(parts[0].toLong(), parts[1].toLong(), parts[2].toLong(), parts.getOrElse(3) { UsageInterval.REGULAR })
        }
}
