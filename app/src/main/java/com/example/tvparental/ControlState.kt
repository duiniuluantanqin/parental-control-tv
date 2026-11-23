package com.example.tvparental

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class UsageWindow(
    val startMinute: Int,
    val endMinute: Int,
)

data class ParentSettings(
    val weeklyDailyLimitMinutes: List<Int> = List(7) { 60 },
    val usageTimeWindows: List<List<UsageWindow>> = List(7) { emptyList() },
    val disabledTimeWindows: List<List<UsageWindow>> = List(7) { emptyList() },
    val questionIntervalMinutes: Int = 10,
    val wrongLockMinutes: Int = 10,
    val syncIntervalMinutes: Int = 5,
    val dailyRewardLimitMinutes: Int = RewardTimeLedger.DEFAULT_DAILY_LIMIT_MINUTES,
)

object ControlState {
    const val BACKEND_URL = "https://tvparental.mediainsight360.com"
    private const val PREFERENCES = "parental_control"
    private const val DAY_KEY = "usage_day"
    private const val USED_MILLIS_KEY = "used_millis"
    private const val USAGE_RESET_ID_KEY = "usage_reset_id"
    private const val USAGE_RESET_AT_KEY = "usage_reset_at"
    private const val LOCKOUT_UNTIL_KEY = "lockout_until"
    private const val LOCKOUT_STARTED_AT_KEY = "lockout_started_at"
    private const val LOCKOUT_POEM_KEY = "lockout_poem"
    private const val QUESTION_STARTED_AT_KEY = "question_started_at"
    private const val QUESTION_TIMEOUT_AT_KEY = "question_timeout_at"
    private const val QUESTION_OVERLAY_ACTIVE_KEY = "question_overlay_active"
    private const val REMOTE_QUESTIONS_KEY = "remote_questions"
    private const val WEEKLY_LIMITS_KEY = "weekly_daily_limit_minutes"
    private const val USAGE_WINDOWS_KEY = "usage_time_windows"
    private const val DISABLED_WINDOWS_KEY = "disabled_time_windows"
    private const val QUESTION_INTERVAL_KEY = "question_interval_minutes"
    private const val WRONG_LOCK_KEY = "wrong_lock_minutes"
    private const val DAILY_REWARD_LIMIT_KEY = "daily_reward_limit_minutes"
    private const val SYNC_INTERVAL_KEY = "sync_interval_minutes"
    private const val ALWAYS_SHOW_REMAINING_TIME_KEY = "always_show_remaining_time"
    private const val SHOW_QUESTION_ON_STARTUP_KEY = "show_question_on_startup"
    private const val STARTUP_QUESTION_DAY_KEY = "startup_question_day"
    private var lastIgnoredClockDay: String? = null
    const val BLOCK_RECENT_WATCH_KEY = "block_recent_watch"
    const val BLOCK_LIVE_TV_KEY = "block_live_tv"
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val TEMP_EXTENSION_KEY = "temp_extension_millis"
    const val TEN_MINUTE_REMINDER_MINUTES = 10
    const val ONE_MINUTE_REMINDER_MINUTES = 1

    fun settings(context: Context): ParentSettings {
        val preferences = prefs(context)
        val weeklyLimits = runCatching {
            JSONObject(preferences.getString(WEEKLY_LIMITS_KEY, null) ?: "{}")
        }.getOrDefault(JSONObject())
        val storedWindows = runCatching {
            JSONObject(preferences.getString(USAGE_WINDOWS_KEY, null) ?: "{}")
        }.getOrDefault(JSONObject())
        val storedDisabledWindows = runCatching {
            JSONObject(preferences.getString(DISABLED_WINDOWS_KEY, null) ?: "{}")
        }.getOrDefault(JSONObject())
        val dailyLimits = (1..7).map { day -> weeklyLimits.optInt(day.toString(), 60).coerceIn(0, 1440) }
        fun readWindows(source: JSONObject) = (1..7).map { day ->
            val windows = source.optJSONArray(day.toString()) ?: JSONArray()
            (0 until windows.length()).mapNotNull { index ->
                windows.optJSONObject(index)?.let { window ->
                    UsageWindow(
                        window.optInt("start").coerceIn(0, 1439),
                        window.optInt("end").coerceIn(0, 1439),
                    )
                }
            }
        }
        return ParentSettings(
            weeklyDailyLimitMinutes = dailyLimits,
            usageTimeWindows = readWindows(storedWindows),
            disabledTimeWindows = readWindows(storedDisabledWindows),
            questionIntervalMinutes = preferences.getInt(QUESTION_INTERVAL_KEY, 10).coerceIn(1, 240),
            wrongLockMinutes = preferences.getInt(WRONG_LOCK_KEY, 10).coerceIn(1, 1440),
            syncIntervalMinutes = preferences.getInt(SYNC_INTERVAL_KEY, 5).coerceIn(1, 60),
            dailyRewardLimitMinutes = preferences.getInt(DAILY_REWARD_LIMIT_KEY, RewardTimeLedger.DEFAULT_DAILY_LIMIT_MINUTES).coerceIn(0, 1440),
        )
    }

    fun alwaysShowRemainingTime(context: Context): Boolean =
        prefs(context).getBoolean(ALWAYS_SHOW_REMAINING_TIME_KEY, true)

    fun setAlwaysShowRemainingTime(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ALWAYS_SHOW_REMAINING_TIME_KEY, enabled).apply()
    }

    fun showQuestionOnStartup(context: Context): Boolean =
        prefs(context).getBoolean(SHOW_QUESTION_ON_STARTUP_KEY, true)

    fun setShowQuestionOnStartup(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(SHOW_QUESTION_ON_STARTUP_KEY, enabled).apply()
    }

    fun claimStartupQuestion(context: Context): Boolean =
        claimStartupQuestion(prefs(context), System.currentTimeMillis())

    @Synchronized
    internal fun claimStartupQuestion(preferences: SharedPreferences, now: Long): Boolean {
        if (!preferences.getBoolean(SHOW_QUESTION_ON_STARTUP_KEY, true)) return false
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        if (preferences.getString(STARTUP_QUESTION_DAY_KEY, null) == today) return false
        // Persist before dispatch so the boot receiver and launcher cannot both trigger it.
        return preferences.edit().putString(STARTUP_QUESTION_DAY_KEY, today).commit()
    }

    fun blockRecentWatch(context: Context): Boolean =
        prefs(context).getBoolean(BLOCK_RECENT_WATCH_KEY, true)

    fun setBlockRecentWatch(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(BLOCK_RECENT_WATCH_KEY, enabled).apply()
    }

    fun blockLiveTv(context: Context): Boolean =
        prefs(context).getBoolean(BLOCK_LIVE_TV_KEY, true)

    fun setBlockLiveTv(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(BLOCK_LIVE_TV_KEY, enabled).apply()
    }

    fun observeRemoteButtonSetting(context: Context, listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun stopObservingRemoteButtonSetting(context: Context, listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    @Synchronized
    fun updateSettings(context: Context, settings: ParentSettings) {
        val preferences = prefs(context)
        val now = System.currentTimeMillis()
        val previousLockMinutes = preferences.getInt(WRONG_LOCK_KEY, 10).coerceIn(1, 1440)
        val lockUntil = preferences.getLong(LOCKOUT_UNTIL_KEY, 0L)
        val storedLockStart = preferences.getLong(LOCKOUT_STARTED_AT_KEY, 0L)
        val questionStartedAt = preferences.getLong(QUESTION_STARTED_AT_KEY, 0L)
        val questionTimeoutAt = preferences.getLong(QUESTION_TIMEOUT_AT_KEY, 0L)
        val weeklyLimits = JSONObject().apply {
            settings.weeklyDailyLimitMinutes.take(7).forEachIndexed { index, minutes ->
                put((index + 1).toString(), minutes.coerceIn(0, 1440))
            }
        }
        fun writeWindows(dailyWindows: List<List<UsageWindow>>) = JSONObject().apply {
            dailyWindows.take(7).forEachIndexed { index, windows ->
                put((index + 1).toString(), JSONArray().apply {
                    windows.take(8).forEach { window ->
                        put(JSONObject().put("start", window.startMinute).put("end", window.endMinute))
                    }
                })
            }
        }
        val usageWindows = writeWindows(settings.usageTimeWindows)
        val disabledWindows = writeWindows(settings.disabledTimeWindows)
        val newLockMinutes = settings.wrongLockMinutes.coerceIn(1, 1440)
        val editor = preferences.edit()
            .putString(WEEKLY_LIMITS_KEY, weeklyLimits.toString())
            .putString(USAGE_WINDOWS_KEY, usageWindows.toString())
            .putString(DISABLED_WINDOWS_KEY, disabledWindows.toString())
            .putInt(QUESTION_INTERVAL_KEY, settings.questionIntervalMinutes.coerceIn(1, 240))
            .putInt(WRONG_LOCK_KEY, newLockMinutes)
            .putInt(SYNC_INTERVAL_KEY, settings.syncIntervalMinutes.coerceIn(1, 60))
            .putInt(DAILY_REWARD_LIMIT_KEY, settings.dailyRewardLimitMinutes.coerceIn(0, 1440))
        if (settings.weeklyDailyLimitMinutes[weekdayIndex(Calendar.getInstance())] == 0 ||
            RewardTimeLedger.availableToday(preferences, settings.dailyRewardLimitMinutes * MILLIS_PER_MINUTE) == 0L
        ) {
            editor.remove(RewardTimeLedger.ACTIVATED)
        }
        if (lockUntil > now) {
            val lockStartedAt = if (storedLockStart in 1..lockUntil) {
                storedLockStart
            } else {
                (lockUntil - previousLockMinutes * MILLIS_PER_MINUTE).coerceAtLeast(0L)
            }
            val adjustedLockUntil = lockStartedAt + newLockMinutes * MILLIS_PER_MINUTE
            editor.putLong(LOCKOUT_STARTED_AT_KEY, lockStartedAt)
            if (adjustedLockUntil <= now) {
                editor.remove(LOCKOUT_UNTIL_KEY)
                editor.remove(LOCKOUT_POEM_KEY)
            } else {
                editor.putLong(LOCKOUT_UNTIL_KEY, adjustedLockUntil)
            }
        }
        if (questionTimeoutAt > now && questionStartedAt > 0L) {
            val adjustedTimeoutAt = questionStartedAt + newLockMinutes * MILLIS_PER_MINUTE
            editor.putLong(QUESTION_TIMEOUT_AT_KEY, adjustedTimeoutAt)
            if (adjustedTimeoutAt <= now) editor.putLong(QUESTION_TIMEOUT_AT_KEY, now)
        }
        editor.apply()
    }

    @Synchronized
    fun usedMillis(context: Context): Long {
        resetForNewDay(context)
        return prefs(context).getLong(USED_MILLIS_KEY, 0L).coerceAtLeast(0L)
    }

    @Synchronized
    fun usageReportSnapshot(context: Context): JSONObject {
        resetForNewDay(context)
        val preferences = prefs(context)
        return JSONObject()
            .put("date", preferences.getString(DAY_KEY, ""))
            .put("utcOffsetMinutes", java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000)
            .put("usageMillis", preferences.getLong(USED_MILLIS_KEY, 0L).coerceAtLeast(0L))
            .put("usageResetId", preferences.getString(USAGE_RESET_ID_KEY, ""))
            .put("rewardTime", JSONObject()
                .put("spentMillis", preferences.getLong(RewardTimeLedger.SPENT, 0L))
                .put("usedTodayMillis", preferences.getLong(RewardTimeLedger.USED_TODAY, 0L)))
    }

    @Synchronized
    fun applyRemoteUsageReset(context: Context, commandId: String, date: String): Boolean {
        resetForNewDay(context)
        val preferences = prefs(context)
        if (commandId.isBlank() || commandId.length > 64 || date != preferences.getString(DAY_KEY, null)) return false
        if (preferences.getString(USAGE_RESET_ID_KEY, "") == commandId) return false
        check(preferences.edit()
            .putLong(USED_MILLIS_KEY, 0L)
            .putLong(TEMP_EXTENSION_KEY, 0L)
            .remove(RewardTimeLedger.ACTIVATED)
            .putString(USAGE_RESET_ID_KEY, commandId)
            .putLong(USAGE_RESET_AT_KEY, System.currentTimeMillis())
            .commit()) { "Could not reset today's usage. It will retry later." }
        return true
    }

    @Synchronized
    fun addScreenTime(context: Context, elapsedMillis: Long): Boolean {
        if (elapsedMillis <= 0L) return false
        resetForNewDay(context)
        val now = System.currentTimeMillis()
        val preferences = prefs(context)
        if (preferences.getString(DAY_KEY, null) != DailyUsageLedger.localDay(now)) return false
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val start = maxOf(now - elapsedMillis, todayStart, preferences.getLong(USAGE_RESET_AT_KEY, 0L), lockoutUntil(context))
        val rules = settings(context)
        val countedMillis = allowedUsageMillis(rules, start, now)
        val dailyLimit = todayDailyLimitMinutes(context)
        val regularLimit = dailyLimit * MILLIS_PER_MINUTE + tempExtension(context)
        val rewardLimit = if (dailyLimit > 0) rules.dailyRewardLimitMinutes * MILLIS_PER_MINUTE else 0L
        return DailyUsageLedger.addUsage(preferences, countedMillis, regularLimit, rewardLimit,
            dailyLimitUnlocked = DailyLimitUnlock.isActive(context))
    }

    fun isDailyLimitReached(context: Context): Boolean {
        return !DailyLimitUnlock.isActive(context) && remainingDailyMillis(context) <= 0L
    }

    @Synchronized
    fun remainingDailyMillis(context: Context): Long {
        val regular = remainingRegularMillis(context)
        if (regular > 0L || todayDailyLimitMinutes(context) == 0) return regular
        if (!prefs(context).getBoolean(RewardTimeLedger.ACTIVATED, false)) return 0L
        return rewardAvailableToday(context)
    }

    private fun remainingRegularMillis(context: Context): Long =
        (todayDailyLimitMinutes(context) * MILLIS_PER_MINUTE + tempExtension(context) - usedMillis(context))
            .coerceAtLeast(0L)

    @Synchronized
    fun rewardBalanceMillis(context: Context): Long = RewardTimeLedger.balance(prefs(context))

    @Synchronized
    fun rewardAvailableToday(context: Context): Long {
        resetForNewDay(context)
        if (todayDailyLimitMinutes(context) == 0) return 0L
        return RewardTimeLedger.availableToday(prefs(context), settings(context).dailyRewardLimitMinutes * MILLIS_PER_MINUTE)
    }

    @Synchronized
    fun canOfferRewards(context: Context): Boolean =
        todayDailyLimitMinutes(context) > 0 && isDailyLimitReached(context) &&
            lockoutUntil(context) <= System.currentTimeMillis() && isUsageWindowAllowed(context)

    @Synchronized
    fun rewardOfferMillis(context: Context): Long =
        if (canOfferRewards(context)) rewardAvailableToday(context) else 0L

    @Synchronized
    fun activateRewardTime(context: Context): Boolean {
        if (rewardOfferMillis(context) <= 0L) return false
        return prefs(context).edit().putBoolean(RewardTimeLedger.ACTIVATED, true).commit()
    }

    @Synchronized
    fun applyRemoteRewardTime(context: Context, reward: JSONObject): Boolean {
        resetForNewDay(context)
        val preferences = prefs(context)
        return RewardTimeLedger.reconcile(preferences,
            sameDay = reward.optString("date") == preferences.getString(DAY_KEY, null),
            granted = reward.getLong("grantedMillis"),
            spent = reward.getLong("spentMillis"),
            usedToday = reward.getLong("usedTodayMillis"),
        )
    }

    fun todayDailyLimitMinutes(context: Context): Int =
        settings(context).weeklyDailyLimitMinutes[weekdayIndex(Calendar.getInstance())]

    fun isUsageWindowAllowed(context: Context): Boolean =
        isUsageWindowAllowed(settings(context), System.currentTimeMillis())

    fun millisUntilNextAllowedUsageWindow(context: Context): Long {
        if (isUsageWindowAllowed(context)) return 0L
        return nextUsageWindowTransition(settings(context), System.currentTimeMillis())
            ?: millisUntilNextDay()
    }

    fun millisUntilNextUsageWindowBoundary(context: Context): Long? =
        nextUsageWindowTransition(settings(context), System.currentTimeMillis())

    @Synchronized
    fun tempExtension(context: Context): Long {
        resetForNewDay(context)
        return prefs(context).getLong(TEMP_EXTENSION_KEY, 0L).coerceAtLeast(0L)
    }

    private fun allowedUsageMillis(settings: ParentSettings, startMillis: Long, endMillis: Long): Long {
        var cursor = startMillis
        var total = 0L
        while (cursor < endMillis) {
            val nextMinute = Calendar.getInstance().apply {
                timeInMillis = cursor
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, 1)
            }.timeInMillis.coerceAtLeast(cursor + 1L)
            val segmentEnd = minOf(endMillis, nextMinute)
            if (isUsageWindowAllowed(settings, cursor)) total += segmentEnd - cursor
            cursor = segmentEnd
        }
        return total
    }

    private fun isUsageWindowAllowed(settings: ParentSettings, timeMillis: Long): Boolean {
        val calendar = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val dayIndex = weekdayIndex(calendar)
        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        fun contains(windows: List<List<UsageWindow>>): Boolean {
            val todayWindows = windows[dayIndex]
            if (todayWindows.any { window ->
                    if (window.startMinute < window.endMinute) {
                        minute >= window.startMinute && minute < window.endMinute
                    } else {
                        minute >= window.startMinute
                    }
                }) return true
            val previousWindows = windows[(dayIndex + 6) % 7]
            return previousWindows.any { window ->
                window.startMinute > window.endMinute && minute < window.endMinute
            }
        }
        if (contains(settings.disabledTimeWindows)) return false
        if (settings.usageTimeWindows[dayIndex].isEmpty()) return true
        return contains(settings.usageTimeWindows)
    }

    private fun nextUsageWindowTransition(settings: ParentSettings, now: Long): Long? {
        if (settings.usageTimeWindows.all { it.isEmpty() } && settings.disabledTimeWindows.all { it.isEmpty() }) return null
        val midnight = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val candidates = sortedSetOf<Long>()
        for (dayOffset in -1..8) {
            val day = (midnight.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, dayOffset) }
            if (dayOffset > 0) candidates.add(day.timeInMillis)
            for (window in settings.usageTimeWindows[weekdayIndex(day)] + settings.disabledTimeWindows[weekdayIndex(day)]) {
                val start = (day.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, window.startMinute / 60)
                    set(Calendar.MINUTE, window.startMinute % 60)
                }
                val end = (day.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, window.endMinute / 60)
                    set(Calendar.MINUTE, window.endMinute % 60)
                    if (window.endMinute <= window.startMinute) add(Calendar.DAY_OF_MONTH, 1)
                }
                candidates.add(start.timeInMillis)
                candidates.add(end.timeInMillis)
            }
        }
        for (candidate in candidates) {
            if (candidate <= now) continue
            val allowedBefore = isUsageWindowAllowed(settings, candidate - 1L)
            val allowedAfter = isUsageWindowAllowed(settings, candidate)
            if (allowedBefore != allowedAfter) return candidate - now
        }
        return null
    }

    private fun weekdayIndex(calendar: Calendar): Int = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7

    fun lockoutUntil(context: Context): Long = prefs(context).getLong(LOCKOUT_UNTIL_KEY, 0L)

    @Synchronized
    fun ensureQuestionTimeout(context: Context): Long {
        val preferences = prefs(context)
        val storedUntil = preferences.getLong(QUESTION_TIMEOUT_AT_KEY, 0L)
        if (storedUntil > 0L) return storedUntil
        val startedAt = System.currentTimeMillis()
        val timeoutAt = startedAt + settings(context).wrongLockMinutes * MILLIS_PER_MINUTE
        preferences.edit()
            .putLong(QUESTION_STARTED_AT_KEY, startedAt)
            .putLong(QUESTION_TIMEOUT_AT_KEY, timeoutAt)
            .apply()
        return timeoutAt
    }

    fun questionTimeoutAt(context: Context): Long = prefs(context).getLong(QUESTION_TIMEOUT_AT_KEY, 0L)

    @Synchronized
    fun clearQuestionTimeout(context: Context) {
        prefs(context).edit()
            .remove(QUESTION_STARTED_AT_KEY)
            .remove(QUESTION_TIMEOUT_AT_KEY)
            .apply()
    }

    fun setQuestionOverlayActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(QUESTION_OVERLAY_ACTIVE_KEY, active).apply()
    }

    fun isQuestionOverlayActive(context: Context): Boolean =
        prefs(context).getBoolean(QUESTION_OVERLAY_ACTIVE_KEY, false)

    @Synchronized
    fun startWrongAnswerLock(context: Context, poem: Poem): Long {
        val startedAt = System.currentTimeMillis()
        val until = startedAt + settings(context).wrongLockMinutes * MILLIS_PER_MINUTE
        prefs(context).edit()
            .putLong(LOCKOUT_STARTED_AT_KEY, startedAt)
            .putLong(LOCKOUT_UNTIL_KEY, until)
            .putString(LOCKOUT_POEM_KEY, QuestionBank.poemToJsonObject(poem).toString())
            .apply()
        return until
    }

    @Synchronized
    fun wrongAnswerLockPoem(context: Context): Poem {
        val preferences = prefs(context)
        preferences.getString(LOCKOUT_POEM_KEY, null)?.let { json ->
            runCatching { QuestionBank.parsePoem(JSONObject(json)) }.getOrNull()?.let { return it }
        }
        val poem = QuestionBank.poemForWrongAnswerLock(context)
        preferences.edit().putString(LOCKOUT_POEM_KEY, QuestionBank.poemToJsonObject(poem).toString()).apply()
        return poem
    }

    @Synchronized
    fun clearWrongAnswerLock(context: Context) {
        prefs(context).edit()
            .remove(LOCKOUT_UNTIL_KEY)
            .remove(LOCKOUT_STARTED_AT_KEY)
            .remove(LOCKOUT_POEM_KEY)
            .apply()
    }

    fun millisUntilNextDay(): Long {
        val nextDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return (nextDay.timeInMillis - System.currentTimeMillis()).coerceAtLeast(1_000L)
    }

    fun saveRemoteQuestions(context: Context, questions: JSONArray): Boolean =
        prefs(context).edit().putString(REMOTE_QUESTIONS_KEY, questions.toString()).commit()

    fun remoteQuestions(context: Context): String? = prefs(context).getString(REMOTE_QUESTIONS_KEY, null)

    @Synchronized
    fun clearAccountData(context: Context) = clearAccountData(prefs(context))

    internal fun clearAccountData(preferences: SharedPreferences) {
        val accountKeys = setOf(
            DAY_KEY, USED_MILLIS_KEY, TEMP_EXTENSION_KEY, USAGE_RESET_ID_KEY, USAGE_RESET_AT_KEY,
            DailyLimitUnlock.UNLOCKED_BOOT,
            "usage_history_days", LOCKOUT_UNTIL_KEY, LOCKOUT_STARTED_AT_KEY, LOCKOUT_POEM_KEY,
            QUESTION_STARTED_AT_KEY, QUESTION_TIMEOUT_AT_KEY, QUESTION_OVERLAY_ACTIVE_KEY,
            REMOTE_QUESTIONS_KEY, WEEKLY_LIMITS_KEY, USAGE_WINDOWS_KEY, DISABLED_WINDOWS_KEY,
            QUESTION_INTERVAL_KEY, WRONG_LOCK_KEY, SYNC_INTERVAL_KEY, APPLIED_PASSWORD_RESET_ID_KEY,
            DAILY_REWARD_LIMIT_KEY, RewardTimeLedger.GRANTED, RewardTimeLedger.SPENT,
            RewardTimeLedger.USED_TODAY, RewardTimeLedger.ACTIVATED,
        )
        val editor = preferences.edit()
        preferences.all.keys.filter { key -> accountKeys.any { key == it || key.startsWith("$it.") } }
            .forEach { editor.remove(it) }
        check(editor.commit()) { "Could not clear the previous account's data." }
    }

    fun rewardTimeLabel(millis: Long): String {
        val seconds = (millis + 999L) / 1_000L
        return when {
            seconds < 60L -> "${seconds} sec"
            seconds % 60L == 0L -> "${seconds / 60L} min"
            else -> "${seconds / 60L} min ${seconds % 60L} sec"
        }
    }

    fun todayUsageLabel(context: Context): String {
        val totalSeconds = usedMillis(context) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%d hr %02d min", hours, minutes)
        } else {
            String.format(Locale.getDefault(), "%d min", minutes)
        }
    }

    const val PASSWORD_LENGTH = 6
    const val DEFAULT_REMOTE_PASSWORD = "UDLRUU"
    private const val PASSWORD_HASH_KEY = "unlock_password_hash"
    private const val PASSWORD_SALT_KEY = "unlock_password_salt"
    private const val APPLIED_PASSWORD_RESET_ID_KEY = "applied_password_reset_id"

    fun hasUnlockPassword(context: Context): Boolean =
        hasUnlockPassword(prefs(context))

    @Synchronized
    internal fun hasUnlockPassword(preferences: SharedPreferences): Boolean =
        preferences.getString(PASSWORD_HASH_KEY, null) != null ||
            setUnlockPassword(preferences, DEFAULT_REMOTE_PASSWORD)

    fun setUnlockPassword(context: Context, sequence: String): Boolean =
        setUnlockPassword(prefs(context), sequence)

    @Synchronized
    internal fun setUnlockPassword(preferences: SharedPreferences, sequence: String): Boolean {
        if (sequence.length != PASSWORD_LENGTH || sequence.any { it !in "UDLR" }) return false
        val salt = java.util.UUID.randomUUID().toString()
        return preferences.edit()
            .putString(PASSWORD_SALT_KEY, salt)
            .putString(PASSWORD_HASH_KEY, hashPassword(salt, sequence))
            .commit()
    }

    @Synchronized
    fun applyRemotePasswordReset(context: Context, commandId: String): Boolean {
        if (commandId.isBlank() || commandId.length > 64) return false
        val preferences = prefs(context)
        if (preferences.getString(APPLIED_PASSWORD_RESET_ID_KEY, null) == commandId) return false
        val salt = java.util.UUID.randomUUID().toString()
        return preferences.edit()
            .putString(PASSWORD_SALT_KEY, salt)
            .putString(PASSWORD_HASH_KEY, hashPassword(salt, DEFAULT_REMOTE_PASSWORD))
            .putString(APPLIED_PASSWORD_RESET_ID_KEY, commandId)
            .commit()
    }

    fun verifyUnlockPassword(context: Context, sequence: String): Boolean =
        verifyUnlockPassword(prefs(context), sequence)

    @Synchronized
    internal fun verifyUnlockPassword(preferences: SharedPreferences, sequence: String): Boolean {
        if (!hasUnlockPassword(preferences)) return false
        val salt = preferences.getString(PASSWORD_SALT_KEY, null) ?: return false
        val hash = preferences.getString(PASSWORD_HASH_KEY, null) ?: return false
        return MessageDigest.isEqual(hash.toByteArray(), hashPassword(salt, sequence).toByteArray())
    }

    private fun hashPassword(salt: String, sequence: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$sequence".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, ParentalDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    fun getDeviceAdminComponent(context: Context): ComponentName =
        ComponentName(context, ParentalDeviceAdminReceiver::class.java)

    @Synchronized
    private fun resetForNewDay(context: Context) {
        val preferences = prefs(context)
        val previous = preferences.getString(DAY_KEY, null)
        val now = System.currentTimeMillis()
        DailyUsageLedger.ensureDay(preferences, now)
        val day = preferences.getString(DAY_KEY, null)
        if (day != previous) {
            Log.i("TvParentalUsage", "Usage day $previous -> $day; usedMillis=${preferences.getLong(USED_MILLIS_KEY, 0L)}")
        }
        val clockDay = DailyUsageLedger.localDay(now)
        if (day != clockDay && lastIgnoredClockDay != clockDay) {
            Log.w("TvParentalUsage", "Ignoring older clock day $clockDay; preserving usage day $day")
        }
        lastIgnoredClockDay = clockDay.takeIf { it != day }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
