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
    fun sync(context: Context): SyncResult {
        val baseUrl = ControlState.backendUrl(context)
        if (baseUrl.isBlank()) return SyncResult(false, "请先设置后台地址")
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return SyncResult(false, "后台地址必须以 http:// 或 https:// 开头")
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = URL("${baseUrl.trimEnd('/')}/api/v1/device/state").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false
            val code = connection.responseCode
            if (code !in 200..299) return SyncResult(false, "后台返回 HTTP $code")
            val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val state = JSONObject(payload)
            val remoteSettings = state.getJSONObject("settings")
            val settings = ParentSettings(
                dailyLimitMinutes = remoteSettings.getInt("dailyLimitMinutes").coerceIn(1, 1440),
                questionIntervalMinutes = remoteSettings.getInt("questionIntervalMinutes").coerceIn(1, 240),
                wrongLockMinutes = remoteSettings.getInt("wrongLockMinutes").coerceIn(1, 1440),
            )
            val questions = state.getJSONArray("questions")
            val parsed = QuestionBank.parseQuestions(questions.toString())
            require(parsed.isNotEmpty()) { "远程题库为空" }
            val settingsChanged = ControlState.settings(context) != settings
            ControlState.updateSettings(context, settings)
            ControlState.saveRemoteQuestions(context, JSONArray(parsed.map { QuestionBank.toJsonObject(it) }))
            SyncResult(true, "已同步 ${parsed.size} 道题目", parsed.size, settingsChanged)
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
}
