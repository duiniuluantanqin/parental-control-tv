package com.example.tvparental

import android.content.SharedPreferences
import java.lang.reflect.Proxy

/** Separate volatile and committed data so a simulated process restart discards pending writes. */
internal class TestPreferenceStore(initial: Map<String, Any?> = emptyMap()) {
    val values = initial.toMutableMap()
    private val committed = initial.toMutableMap()

    fun restart() = TestPreferenceStore(committed)

    fun open(): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getBoolean", "getString", "getLong", "getInt" -> if (values.containsKey(args!![0])) values[args[0]] else args[1]
            "getAll" -> values.toMap()
            "contains" -> values.containsKey(args!![0])
            "edit" -> editor()
            else -> error("Unexpected preferences method: ${method.name}")
        }
    } as SharedPreferences

    private fun editor(): SharedPreferences.Editor {
        val updates = mutableMapOf<String, Any?>()
        val removed = mutableSetOf<String>()
        return Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putString", "putLong", "putInt", "putBoolean" -> {
                    updates[args!![0] as String] = args[1]
                    proxy
                }
                "remove" -> {
                    removed.add(args!![0] as String)
                    proxy
                }
                "commit", "apply" -> {
                    removed.forEach { values.remove(it) }
                    values.putAll(updates)
                    if (method.name == "commit") {
                        committed.clear()
                        committed.putAll(values)
                        true
                    } else null
                }
                else -> error("Unexpected editor method: ${method.name}")
            }
        } as SharedPreferences.Editor
    }
}
