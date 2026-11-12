package com.example.tvparental

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StartupQuestionTest {
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun useTvTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun rebootAndLauncherOpeningsShareOnePersistedClaimPerDay() {
        val store = PreferenceStore()
        val morning = utc("2026-11-12 01:00")
        assertTrue(ControlState.claimStartupQuestion(store.open(), morning))
        // New preference instances represent the launcher and a restarted process.
        assertFalse(ControlState.claimStartupQuestion(store.open(), morning))
        assertFalse(ControlState.claimStartupQuestion(store.open(), utc("2026-11-12 13:00")))
        assertTrue(ControlState.claimStartupQuestion(store.open(), utc("2026-11-13 01:00")))
        assertFalse(ControlState.claimStartupQuestion(store.open(), utc("2026-11-13 02:00")))
    }

    @Test
    fun localMidnightRearmsTheClaimWithoutWaitingTwentyFourHours() {
        val store = PreferenceStore()
        assertTrue(ControlState.claimStartupQuestion(store.open(), utc("2026-11-12 15:59")))
        assertTrue(ControlState.claimStartupQuestion(store.open(), utc("2026-11-12 16:00")))
        assertFalse(ControlState.claimStartupQuestion(store.open(), utc("2026-11-13 00:00")))
    }

    @Test
    fun disablingDoesNotConsumeTheDayAndTogglingCannotRepeatAClaim() {
        val store = PreferenceStore()
        val now = utc("2026-11-12 01:00")
        store.values["show_question_on_startup"] = false
        assertFalse(ControlState.claimStartupQuestion(store.open(), now))
        store.values["show_question_on_startup"] = true
        assertTrue(ControlState.claimStartupQuestion(store.open(), now))
        store.values["show_question_on_startup"] = false
        assertFalse(ControlState.claimStartupQuestion(store.open(), now))
        store.values["show_question_on_startup"] = true
        assertFalse(ControlState.claimStartupQuestion(store.open(), now))
    }

    private fun utc(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse(value)!!.time

    // Android interfaces can be exercised on the JVM without an emulator or new dependencies.
    private class PreferenceStore {
        val values = mutableMapOf<String, Any?>()

        fun open(): SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getBoolean", "getString" -> if (values.containsKey(args!![0])) values[args[0]] else args[1]
                "edit" -> editor()
                else -> error("Unexpected preferences method: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val updates = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "putString" -> {
                        updates[args!![0] as String] = args[1]
                        proxy
                    }
                    "commit" -> {
                        values.putAll(updates)
                        true
                    }
                    else -> error("Unexpected editor method: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
