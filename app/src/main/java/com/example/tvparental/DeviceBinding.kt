package com.example.tvparental

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.FutureTask

object DeviceBinding {
    internal const val CREDENTIALS_CHANGED_MESSAGE = "设备凭证已更换，请先在原账号后台解绑此电视。"

    internal fun responseErrorMessage(status: Int, errorCode: String): String = when {
        status == 409 && errorCode == "device_already_bound" -> CREDENTIALS_CHANGED_MESSAGE
        status == 401 -> "设备验证失败；若刚重装，请确认后台已更新后重试。"
        status == 404 -> "绑定服务不可用，请先更新后台。"
        else -> "无法检查账号绑定（HTTP $status），请稍后重试。"
    }

    private fun prefs(context: Context) = context.getSharedPreferences("device_binding", Context.MODE_PRIVATE)

    fun bindingId(context: Context): String = prefs(context).getString("binding_id", "").orEmpty()

    fun username(context: Context): String = prefs(context).getString("username", "").orEmpty()

    private fun token(context: Context): String {
        val preferences = prefs(context)
        preferences.getString("token", null)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        check(preferences.edit().putString("token", token).commit()) { "无法保存设备凭证。" }
        return token
    }

    fun authorize(context: Context, connection: HttpURLConnection) {
        connection.setRequestProperty("Authorization", "Bearer ${token(context)}")
        connection.setRequestProperty("X-Device-Id", RemoteSync.deviceId(context))
        connection.setRequestProperty("X-Binding-Id", bindingId(context))
    }

    // Called through RemoteSync's monitor so reports and binding changes cannot overlap.
    fun refresh(context: Context, requestCode: Boolean = false): JSONObject {
        val path = if (requestCode) "pairing" else "binding"
        val connection = URL("${ControlState.BACKEND_URL}/api/v1/device/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false
            authorize(context, connection)
            if (requestCode) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val body = JSONObject().put("deviceName", RemoteSync.deviceName()).toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code == 401 && !requestCode) return refresh(context, requestCode = true)
            check(code in 200..299) {
                val errorCode = runCatching {
                    JSONObject(connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
                        .optString("errorCode")
                }.getOrDefault("")
                responseErrorMessage(code, errorCode)
            }
            val state = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val nextId = if (state.getBoolean("bound")) state.getString("bindingId") else ""
            val previous = bindingId(context)
            if (nextId != previous) {
                val update = FutureTask<Unit> {
                    // Keep existing local records only for the TV's first binding to the original admin.
                    val migrateAdmin = !prefs(context).getBoolean("ever_bound", false) && state.optString("accountId") == "1"
                    if (!migrateAdmin) {
                        context.stopService(Intent(context, QuestionOverlayService::class.java))
                        ControlState.clearAccountData(context)
                        check(context.getSharedPreferences("question_state", Context.MODE_PRIVATE).edit().clear().commit()) {
                            "无法清除原账号的题目。"
                        }
                    }
                    check(prefs(context).edit().putString("binding_id", nextId)
                        .putString("username", state.optString("username"))
                        .putBoolean("ever_bound", nextId.isNotEmpty() || prefs(context).getBoolean("ever_bound", false))
                        .commit()) { "无法保存账号绑定。" }
                    QuestionAlarmReceiver.scheduleForCurrentState(context)
                    context.sendBroadcast(Intent(RemoteSync.ACTION_SYNC_COMPLETED).setPackage(context.packageName))
                }
                if (Looper.myLooper() == Looper.getMainLooper()) update.run()
                else Handler(Looper.getMainLooper()).post(update)
                update.get()
            }
            return state
        } finally {
            connection.disconnect()
        }
    }
}
