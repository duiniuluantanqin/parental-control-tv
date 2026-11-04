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
    val questionIntervalMinutes: Int = 10,
    val wrongLockMinutes: Int = 10,
    val syncIntervalMinutes: Int = 5,
)

object ControlState {
    const val BACKEND_URL = "https://tvparental.mediainsight360.com"
    private const val PREFERENCES = "parental_control"
    private const val DAY_KEY = "usage_day"
    private const val USED_MILLIS_KEY = "used_millis"
    private const val LOCKOUT_UNTIL_KEY = "lockout_until"
    private const val REMOTE_QUESTIONS_KEY = "remote_questions"
    private const val WEEKLY_LIMITS_KEY = "weekly_daily_limit_minutes"
    private const val USAGE_WINDOWS_KEY = "usage_time_windows"
    private const val QUESTION_INTERVAL_KEY = "question_interval_minutes"
    private const val WRONG_LOCK_KEY = "wrong_lock_minutes"
    private const val SYNC_INTERVAL_KEY = "sync_interval_minutes"
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val TEMP_EXTENSION_KEY = "temp_extension_millis"
    const val PASSWORD_EXTENSION_MINUTES = 15

    fun settings(context: Context): ParentSettings {
        val preferences = prefs(context)
        val weeklyLimits = runCatching {
            JSONObject(preferences.getString(WEEKLY_LIMITS_KEY, null) ?: "{}")
        }.getOrDefault(JSONObject())
        val storedWindows = runCatching {
            JSONObject(preferences.getString(USAGE_WINDOWS_KEY, null) ?: "{}")
        }.getOrDefault(JSONObject())
        val dailyLimits = (1..7).map { day -> weeklyLimits.optInt(day.toString(), 60).coerceIn(0, 1440) }
        val usageWindows = (1..7).map { day ->
            val windows = storedWindows.optJSONArray(day.toString()) ?: JSONArray()
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
            usageTimeWindows = usageWindows,
            questionIntervalMinutes = preferences.getInt(QUESTION_INTERVAL_KEY, 10).coerceIn(1, 240),
            wrongLockMinutes = preferences.getInt(WRONG_LOCK_KEY, 10).coerceIn(1, 1440),
            syncIntervalMinutes = preferences.getInt(SYNC_INTERVAL_KEY, 5).coerceIn(1, 60),
        )
    }

    fun updateSettings(context: Context, settings: ParentSettings) {
        val weeklyLimits = JSONObject().apply {
            settings.weeklyDailyLimitMinutes.take(7).forEachIndexed { index, minutes ->
                put((index + 1).toString(), minutes.coerceIn(0, 1440))
            }
        }
        val usageWindows = JSONObject().apply {
            settings.usageTimeWindows.take(7).forEachIndexed { index, windows ->
                put((index + 1).toString(), JSONArray().apply {
                    windows.take(8).forEach { window ->
                        put(JSONObject().put("start", window.startMinute).put("end", window.endMinute))
                    }
                })
            }
        }
        prefs(context).edit()
            .putString(WEEKLY_LIMITS_KEY, weeklyLimits.toString())
            .putString(USAGE_WINDOWS_KEY, usageWindows.toString())
            .putInt(QUESTION_INTERVAL_KEY, settings.questionIntervalMinutes.coerceIn(1, 240))
            .putInt(WRONG_LOCK_KEY, settings.wrongLockMinutes.coerceIn(1, 1440))
            .putInt(SYNC_INTERVAL_KEY, settings.syncIntervalMinutes.coerceIn(1, 60))
            .apply()
    }

    fun usedMillis(context: Context): Long {
        resetForNewDay(context)
        return prefs(context).getLong(USED_MILLIS_KEY, 0L).coerceAtLeast(0L)
    }

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
        val start = maxOf(now - elapsedMillis, todayStart)
        val countedMillis = allowedUsageMillis(settings(context), start, now)
        if (countedMillis == 0L) return
        val current = prefs(context).getLong(USED_MILLIS_KEY, 0L).coerceAtLeast(0L)
        val limit = todayDailyLimitMinutes(context) * MILLIS_PER_MINUTE + tempExtension(context)
        prefs(context).edit().putLong(USED_MILLIS_KEY, (current + countedMillis).coerceAtMost(limit)).apply()
    }

    fun isDailyLimitReached(context: Context): Boolean {
        val used = usedMillis(context)
        val limit = todayDailyLimitMinutes(context) * MILLIS_PER_MINUTE
        val extension = tempExtension(context)
        return used >= limit + extension
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

    fun tempExtension(context: Context): Long {
        resetForNewDay(context)
        return prefs(context).getLong(TEMP_EXTENSION_KEY, 0L).coerceAtLeast(0L)
    }

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
        val todayWindows = settings.usageTimeWindows[dayIndex]
        if (todayWindows.isEmpty()) return true
        if (todayWindows.any { window ->
                if (window.startMinute < window.endMinute) {
                    minute >= window.startMinute && minute < window.endMinute
                } else {
                    minute >= window.startMinute
                }
            }) return true

        val previousWindows = settings.usageTimeWindows[(dayIndex + 6) % 7]
        return previousWindows.any { window ->
            window.startMinute > window.endMinute && minute < window.endMinute
        }
    }

    private fun nextUsageWindowTransition(settings: ParentSettings, now: Long): Long? {
        if (settings.usageTimeWindows.all { it.isEmpty() }) return null
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
            for (window in settings.usageTimeWindows[weekdayIndex(day)]) {
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

    fun startWrongAnswerLock(context: Context): Long {
        val until = System.currentTimeMillis() + settings(context).wrongLockMinutes * MILLIS_PER_MINUTE
        prefs(context).edit().putLong(LOCKOUT_UNTIL_KEY, until).apply()
        return until
    }

    fun clearWrongAnswerLock(context: Context) {
        prefs(context).edit().remove(LOCKOUT_UNTIL_KEY).apply()
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
            String.format(Locale.getDefault(), "%d小时%02d分", hours, minutes)
        } else {
            String.format(Locale.getDefault(), "%d分", minutes)
        }
    }

    const val PASSWORD_LENGTH = 6
    private const val PASSWORD_HASH_KEY = "unlock_password_hash"
    private const val PASSWORD_SALT_KEY = "unlock_password_salt"

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

    private fun resetForNewDay(context: Context) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())
        val preferences = prefs(context)
        if (preferences.getString(DAY_KEY, null) != today) {
            preferences.edit()
                .putString(DAY_KEY, today)
                .putLong(USED_MILLIS_KEY, 0L)
                .putLong(TEMP_EXTENSION_KEY, 0L)
                .apply()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
