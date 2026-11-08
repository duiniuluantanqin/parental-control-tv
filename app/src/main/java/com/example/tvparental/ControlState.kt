package com.example.tvparental

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
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
    private const val QUESTION_STARTED_AT_KEY = "question_started_at"
    private const val QUESTION_TIMEOUT_AT_KEY = "question_timeout_at"
    private const val QUESTION_OVERLAY_ACTIVE_KEY = "question_overlay_active"
    private const val REMOTE_QUESTIONS_KEY = "remote_questions"
    private const val WEEKLY_LIMITS_KEY = "weekly_daily_limit_minutes"
    private const val USAGE_WINDOWS_KEY = "usage_time_windows"
    private const val DISABLED_WINDOWS_KEY = "disabled_time_windows"
    private const val QUESTION_INTERVAL_KEY = "question_interval_minutes"
    private const val WRONG_LOCK_KEY = "wrong_lock_minutes"
    private const val SYNC_INTERVAL_KEY = "sync_interval_minutes"
    private const val ALWAYS_SHOW_REMAINING_TIME_KEY = "always_show_remaining_time"
    private const val SHOW_QUESTION_ON_STARTUP_KEY = "show_question_on_startup"
    const val BLOCK_RECENT_WATCH_KEY = "block_recent_watch"
    const val BLOCK_LIVE_TV_KEY = "block_live_tv"
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val TEMP_EXTENSION_KEY = "temp_extension_millis"
    const val TEN_MINUTE_REMINDER_MINUTES = 10
    const val ONE_MINUTE_REMINDER_MINUTES = 1
    const val PASSWORD_EXTENSION_MINUTES = 15

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
            .putString(USAGE_RESET_ID_KEY, commandId)
            .putLong(USAGE_RESET_AT_KEY, System.currentTimeMillis())
            .commit()) { "Could not reset today's usage. It will retry later." }
        return true
    }

    @Synchronized
    fun addScreenTime(context: Context, elapsedMillis: Long) {
        if (elapsedMillis <= 0L) return
        resetForNewDay(context)
        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val start = maxOf(now - elapsedMillis, todayStart, prefs(context).getLong(USAGE_RESET_AT_KEY, 0L))
        val countedMillis = allowedUsageMillis(settings(context), start, now)
        if (countedMillis == 0L) return
        val current = prefs(context).getLong(USED_MILLIS_KEY, 0L).coerceAtLeast(0L)
        val limit = todayDailyLimitMinutes(context) * MILLIS_PER_MINUTE + tempExtension(context)
        prefs(context).edit().putLong(USED_MILLIS_KEY, (current + countedMillis).coerceAtMost(limit)).apply()
    }

    fun isDailyLimitReached(context: Context): Boolean {
        return remainingDailyMillis(context) <= 0L
    }

    fun remainingDailyMillis(context: Context): Long =
        (todayDailyLimitMinutes(context) * MILLIS_PER_MINUTE + tempExtension(context) - usedMillis(context))
            .coerceAtLeast(0L)

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

    @Synchronized
    fun grantPasswordExtension(context: Context) {
        resetForNewDay(context)
        prefs(context).edit()
            .putLong(TEMP_EXTENSION_KEY, PASSWORD_EXTENSION_MINUTES * MILLIS_PER_MINUTE)
            .apply()
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
    fun startWrongAnswerLock(context: Context): Long {
        val startedAt = System.currentTimeMillis()
        val until = startedAt + settings(context).wrongLockMinutes * MILLIS_PER_MINUTE
        prefs(context).edit()
            .putLong(LOCKOUT_STARTED_AT_KEY, startedAt)
            .putLong(LOCKOUT_UNTIL_KEY, until)
            .apply()
        return until
    }

    @Synchronized
    fun clearWrongAnswerLock(context: Context) {
        prefs(context).edit()
            .remove(LOCKOUT_UNTIL_KEY)
            .remove(LOCKOUT_STARTED_AT_KEY)
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
        prefs(context).getString(PASSWORD_HASH_KEY, null) != null

    fun setUnlockPassword(context: Context, sequence: String): Boolean {
        if (sequence.length != PASSWORD_LENGTH || sequence.any { it !in "UDLR" }) return false
        val salt = java.util.UUID.randomUUID().toString()
        return prefs(context).edit()
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

    fun verifyUnlockPassword(context: Context, sequence: String): Boolean {
        val preferences = prefs(context)
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
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())
        val preferences = prefs(context)
        if (preferences.getString(DAY_KEY, null) != today) {
            preferences.edit()
                .putString(DAY_KEY, today)
                .putLong(USED_MILLIS_KEY, 0L)
                .putLong(TEMP_EXTENSION_KEY, 0L)
                .remove(USAGE_RESET_ID_KEY)
                .remove(USAGE_RESET_AT_KEY)
                .apply()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
