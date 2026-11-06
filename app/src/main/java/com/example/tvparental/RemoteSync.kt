package com.example.tvparental

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SyncResult(
    val success: Boolean,
    val message: String,
    val questionCount: Int = 0,
    val settingsChanged: Boolean = false,
)

object RemoteSync {
    @Synchronized
    fun sync(context: Context): SyncResult {
        var connection: HttpURLConnection? = null
        return try {
            val acknowledged = mutableSetOf<String>()
            val acknowledgedAttempts = mutableSetOf<String>()
            for (attempt in QuestionBank.pendingAnswerAttempts(context)) {
                val report = URL("${ControlState.BACKEND_URL}/api/v1/device/answers").openConnection() as HttpURLConnection
                try {
                    report.requestMethod = "POST"
                    report.connectTimeout = 5_000
                    report.readTimeout = 5_000
                    report.doOutput = true
                    report.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val body = JSONObject()
                        .put("attemptId", attempt.attemptId)
                        .put("deviceId", deviceId(context))
                        .put("date", attempt.date)
                        .put("answeredAt", attempt.answeredAt)
                        .put("questionId", attempt.question.id)
                        .put("selectedAnswer", attempt.selectedAnswer)
                        .put("answered", attempt.answered)
                        .toString().toByteArray(Charsets.UTF_8)
                    report.setFixedLengthStreamingMode(body.size)
                    report.outputStream.use { it.write(body) }
                    val code = report.responseCode
                    if (code !in 200..299) return SyncResult(false, "Answer report failed (HTTP $code). It will retry later.")
                    val result = JSONObject(report.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                    if (result.getBoolean("accepted")) {
                        acknowledgedAttempts.add(attempt.attemptId)
                        if (result.optBoolean("correct")) acknowledged.add(attempt.question.id)
                    }
                } finally {
                    report.disconnect()
                }
            }
            for (question in QuestionBank.pendingCorrectAnswers(context).filterNot { it.id in acknowledged }) {
                val report = URL("${ControlState.BACKEND_URL}/api/v1/device/answers").openConnection() as HttpURLConnection
                try {
                    report.requestMethod = "POST"
                    report.connectTimeout = 5_000
                    report.readTimeout = 5_000
                    report.doOutput = true
                    report.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val body = JSONObject().put("questionId", question.id).put("answer", question.answer)
                        .toString().toByteArray(Charsets.UTF_8)
                    report.setFixedLengthStreamingMode(body.size)
                    report.outputStream.use { it.write(body) }
                    val code = report.responseCode
                    if (code !in 200..299) return SyncResult(false, "Answer report failed (HTTP $code). It will retry later.")
                    JSONObject(report.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).getBoolean("removed")
                    acknowledged.add(question.id)
                } finally {
                    report.disconnect()
                }
            }
            connection = URL("${ControlState.BACKEND_URL}/api/v1/device/state").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false
            val code = connection.responseCode
            if (code !in 200..299) return SyncResult(false, "Server returned HTTP $code.")
            val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val state = JSONObject(payload)
            val remoteSettings = state.getJSONObject("settings")
            val weeklyLimitsJson = remoteSettings.getJSONObject("weeklyDailyLimitMinutes")
            val weeklyLimits = (1..7).map { day ->
                weeklyLimitsJson.getInt(day.toString()).coerceIn(0, 1440)
            }
            val windowsJson = remoteSettings.getJSONObject("usageTimeWindows")
            fun parseWindows(windowsByDay: JSONObject) = (1..7).map { day ->
                val dayWindows = windowsByDay.optJSONArray(day.toString()) ?: JSONArray()
                (0 until dayWindows.length()).map { index ->
                    val window = dayWindows.getJSONObject(index)
                    UsageWindow(
                        minuteOfDay(window.getString("start")),
                        minuteOfDay(window.getString("end")),
                    )
                }
            }
            val disabledWindowsJson = remoteSettings.optJSONObject("disabledTimeWindows") ?: JSONObject()
            val settings = ParentSettings(
                weeklyDailyLimitMinutes = weeklyLimits,
                usageTimeWindows = parseWindows(windowsJson),
                disabledTimeWindows = parseWindows(disabledWindowsJson),
                questionIntervalMinutes = remoteSettings.getInt("questionIntervalMinutes").coerceIn(1, 240),
                wrongLockMinutes = remoteSettings.getInt("wrongLockMinutes").coerceIn(1, 1440),
                syncIntervalMinutes = remoteSettings.optInt("syncIntervalMinutes", 5).coerceIn(1, 60),
            )
            val questions = state.getJSONArray("questions")
            val parsed = QuestionBank.parseQuestions(questions.toString())
            val settingsChanged = ControlState.settings(context) != settings
            ControlState.updateSettings(context, settings)
            check(ControlState.saveRemoteQuestions(context, JSONArray(parsed.map { QuestionBank.toJsonObject(it) }))) {
                "Could not save the question bank. It will retry later."
            }
            state.optJSONObject("passwordReset")?.optString("id")
                ?.takeIf { it.isNotBlank() }
                ?.let { commandId ->
                    val applied = ControlState.applyRemotePasswordReset(context, commandId)
                    if (applied) {
                        context.sendBroadcast(Intent(ACTION_PASSWORD_RESET).setPackage(context.packageName))
                    }
                }
            // Keep the local exclusion until the replacement bank has been saved successfully.
            QuestionBank.acknowledgeAnswerAttempts(context, acknowledgedAttempts)
            QuestionBank.acknowledgeCorrectAnswers(context, acknowledged)
            val message = if (parsed.isEmpty()) "Question bank is empty. Waiting for the server to refill it." else "Synced ${parsed.size} questions."
            SyncResult(true, message, parsed.size, settingsChanged)
        } catch (error: Exception) {
            SyncResult(false, error.message ?: "Could not connect to the server.")
        } finally {
            connection?.disconnect()
            context.sendBroadcast(Intent(ACTION_SYNC_COMPLETED).setPackage(context.packageName))
        }
    }

    fun syncAsync(context: Context, onComplete: (SyncResult) -> Unit) {
        val applicationContext = context.applicationContext
        Thread {
            val result = sync(applicationContext)
            Handler(applicationContext.mainLooper).post { onComplete(result) }
        }.start()
    }

    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: error("Could not read the device ID.")

    fun reportUsage(context: Context): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("${ControlState.BACKEND_URL}/api/v1/device/usage").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val now = System.currentTimeMillis()
            val body = JSONObject()
                .put("deviceId", deviceId(context))
                .put("deviceName", deviceName())
                .put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now)))
                .put("usageMillis", ControlState.usedMillis(context))
                .toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun minuteOfDay(value: String): Int {
        val parts = value.split(":")
        require(parts.size == 2 && parts.all { it.length == 2 }) { "Invalid usage time window." }
        val hour = parts[0].toIntOrNull() ?: error("Invalid usage time window.")
        val minute = parts[1].toIntOrNull() ?: error("Invalid usage time window.")
        require(hour in 0..23 && minute in 0..59) { "Invalid usage time window." }
        return hour * 60 + minute
    }

    private fun deviceName(): String {
        val avdName = runCatching {
            Runtime.getRuntime()
                .exec(arrayOf("/system/bin/getprop", "ro.kernel.qemu.avd_name"))
                .inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrDefault("")
        if (avdName.isNotBlank()) return avdName

        val manufacturer = Build.MANUFACTURER.trim().replaceFirstChar { it.uppercase(Locale.ROOT) }
        val model = Build.MODEL.trim()
        return when {
            model.isBlank() -> manufacturer.ifBlank { "Android TV" }
            manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }

    const val ACTION_SYNC_COMPLETED = "com.example.tvparental.action.SYNC_COMPLETED"
    const val ACTION_PASSWORD_RESET = "com.example.tvparental.action.PASSWORD_RESET"
}
