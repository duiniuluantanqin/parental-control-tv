package com.example.tvparental

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class ParentSettings(
    val dailyLimitMinutes: Int = 60,
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
    private const val DAILY_LIMIT_KEY = "daily_limit_minutes"
    private const val QUESTION_INTERVAL_KEY = "question_interval_minutes"
    private const val WRONG_LOCK_KEY = "wrong_lock_minutes"
    private const val SYNC_INTERVAL_KEY = "sync_interval_minutes"
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val TEMP_EXTENSION_KEY = "temp_extension_millis"
    const val PASSWORD_EXTENSION_MINUTES = 15

    fun settings(context: Context): ParentSettings {
        val preferences = prefs(context)
        return ParentSettings(
            dailyLimitMinutes = preferences.getInt(DAILY_LIMIT_KEY, 60).coerceIn(1, 1440),
            questionIntervalMinutes = preferences.getInt(QUESTION_INTERVAL_KEY, 10).coerceIn(1, 240),
            wrongLockMinutes = preferences.getInt(WRONG_LOCK_KEY, 10).coerceIn(1, 1440),
            syncIntervalMinutes = preferences.getInt(SYNC_INTERVAL_KEY, 5).coerceIn(1, 60),
        )
    }

    fun updateSettings(context: Context, settings: ParentSettings) {
        prefs(context).edit()
            .putInt(DAILY_LIMIT_KEY, settings.dailyLimitMinutes.coerceIn(1, 1440))
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
        val countedMillis = (now - maxOf(now - elapsedMillis, todayStart)).coerceAtLeast(0L)
        if (countedMillis == 0L) return
        val current = prefs(context).getLong(USED_MILLIS_KEY, 0L).coerceAtLeast(0L)
        val limit = settings(context).dailyLimitMinutes * MILLIS_PER_MINUTE + tempExtension(context)
        prefs(context).edit().putLong(USED_MILLIS_KEY, (current + countedMillis).coerceAtMost(limit)).apply()
    }

    fun isDailyLimitReached(context: Context): Boolean {
        val used = usedMillis(context)
        val limit = settings(context).dailyLimitMinutes * MILLIS_PER_MINUTE
        val extension = tempExtension(context)
        return used >= limit + extension
    }

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
