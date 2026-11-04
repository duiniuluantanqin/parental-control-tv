package com.example.tvparental

import android.content.Context
import android.os.Handler
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
            for (question in QuestionBank.pendingCorrectAnswers(context)) {
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
                    if (code !in 200..299) return SyncResult(false, "答题结果上报失败（HTTP $code），稍后自动重试")
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
            if (code !in 200..299) return SyncResult(false, "后台返回 HTTP $code")
            val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val state = JSONObject(payload)
            val remoteSettings = state.getJSONObject("settings")
            val weeklyLimitsJson = remoteSettings.getJSONObject("weeklyDailyLimitMinutes")
            val weeklyLimits = (1..7).map { day ->
                weeklyLimitsJson.getInt(day.toString()).coerceIn(0, 1440)
            }
            val windowsJson = remoteSettings.getJSONObject("usageTimeWindows")
            val usageWindows = (1..7).map { day ->
                val dayWindows = windowsJson.getJSONArray(day.toString())
                (0 until dayWindows.length()).map { index ->
                    val window = dayWindows.getJSONObject(index)
                    UsageWindow(
                        minuteOfDay(window.getString("start")),
                        minuteOfDay(window.getString("end")),
                    )
                }
            }
            val settings = ParentSettings(
                weeklyDailyLimitMinutes = weeklyLimits,
                usageTimeWindows = usageWindows,
                questionIntervalMinutes = remoteSettings.getInt("questionIntervalMinutes").coerceIn(1, 240),
                wrongLockMinutes = remoteSettings.getInt("wrongLockMinutes").coerceIn(1, 1440),
                syncIntervalMinutes = remoteSettings.optInt("syncIntervalMinutes", 5).coerceIn(1, 60),
            )
            val questions = state.getJSONArray("questions")
            val parsed = QuestionBank.parseQuestions(questions.toString())
            val settingsChanged = ControlState.settings(context) != settings
            ControlState.updateSettings(context, settings)
            check(ControlState.saveRemoteQuestions(context, JSONArray(parsed.map { QuestionBank.toJsonObject(it) }))) {
                "保存题库失败，稍后自动重试"
            }
            // Keep the local exclusion until the replacement bank has been saved successfully.
            QuestionBank.acknowledgeCorrectAnswers(context, acknowledged)
            val message = if (parsed.isEmpty()) "题库已空，等待后台自动补题" else "已同步 ${parsed.size} 道题目"
            SyncResult(true, message, parsed.size, settingsChanged)
        } catch (error: Exception) {
            SyncResult(false, error.message ?: "连接后台失败")
        } finally {
            connection?.disconnect()
        }
    }

    fun syncAsync(context: Context, onComplete: (SyncResult) -> Unit) {
        val applicationContext = context.applicationContext
        Thread {
            val result = sync(applicationContext)
            Handler(applicationContext.mainLooper).post { onComplete(result) }
        }.start()
    }

    private fun minuteOfDay(value: String): Int {
        val parts = value.split(":")
        require(parts.size == 2 && parts.all { it.length == 2 }) { "无效的使用时段" }
        val hour = parts[0].toIntOrNull() ?: error("无效的使用时段")
        val minute = parts[1].toIntOrNull() ?: error("无效的使用时段")
        require(hour in 0..23 && minute in 0..59) { "无效的使用时段" }
        return hour * 60 + minute
    }
}
