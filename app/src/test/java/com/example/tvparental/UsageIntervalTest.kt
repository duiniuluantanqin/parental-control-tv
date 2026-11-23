package com.example.tvparental

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageIntervalTest {
    private val minute = 60_000L

    @Test
    fun offlineIntervalsSurviveRecoveryAndDoNotCountGapsOrRewardTime() {
        val store = TestPreferenceStore(mapOf(
            "used_millis" to 58 * minute,
            RewardTimeLedger.GRANTED to 10 * minute,
            RewardTimeLedger.ACTIVATED to true,
        ))
        val start = 1_000_000L
        DailyUsageLedger.addUsage(store.open(), minute, 60 * minute, 30 * minute,
            usageIntervals = listOf(UsageInterval(start, start + minute)))
        val restored = store.restart().open()
        DailyUsageLedger.addUsage(restored, 2 * minute, 60 * minute, 30 * minute,
            usageIntervals = listOf(UsageInterval(start + 2 * minute, start + 4 * minute)))

        assertEquals(listOf(UsageInterval(start, start + 3 * minute, 2 * minute),
            UsageInterval(start + 3 * minute, start + 4 * minute, kind = UsageInterval.REWARD)), DailyUsageLedger.intervals(restored))
        assertEquals(60 * minute, restored.getLong("used_millis", 0L))
        assertEquals(minute, restored.getLong(RewardTimeLedger.USED_TODAY, 0L))
    }

    @Test
    fun dateChangesRestoreEachDaysIntervalsAlongWithItsTotal() {
        val preferences = TestPreferenceStore().open()
        val day = local("2026-11-22 13:00")
        DailyUsageLedger.ensureDay(preferences, day)
        DailyUsageLedger.addUsage(preferences, minute, 60 * minute,
            usageIntervals = listOf(UsageInterval(day, day + minute)))
        DailyUsageLedger.ensureDay(preferences, local("2026-11-23 13:00"))
        assertEquals(emptyList<UsageInterval>(), DailyUsageLedger.intervals(preferences))
        DailyUsageLedger.ensureDay(preferences, day)
        assertEquals(listOf(UsageInterval(day, day + minute)), DailyUsageLedger.intervals(preferences))
        assertEquals(minute, preferences.getLong("used_millis", 0L))
    }

    @Test
    fun disabledWindowsAreExcludedBeforeRegularQuotaIsApplied() {
        val rules = ParentSettings(
            usageTimeWindows = List(7) { listOf(UsageWindow(13 * 60, 14 * 60)) },
            disabledTimeWindows = List(7) { listOf(UsageWindow(13 * 60 + 1, 13 * 60 + 2)) },
        )
        val start = local("2026-11-22 13:00")
        val intervals = ControlState.allowedUsageIntervals(rules, start, start + 3 * minute)
        assertEquals(listOf(UsageInterval(start, start + minute), UsageInterval(start + 2 * minute, start + 3 * minute)), intervals)
        val preferences = TestPreferenceStore().open()
        DailyUsageLedger.addUsage(preferences, 2 * minute, minute, usageIntervals = intervals)
        assertEquals(listOf(UsageInterval(start, start + minute)), DailyUsageLedger.intervals(preferences))
    }

    @Test
    fun answeringAndParentViewingArePersistedSeparatelyWithoutDebitingQuotaOrRewards() {
        val store = TestPreferenceStore(mapOf("used_millis" to 60 * minute, RewardTimeLedger.GRANTED to 20 * minute))
        val preferences = store.open()
        val start = local("2026-11-22 13:00")
        DailyUsageLedger.ensureDay(preferences, start)
        preferences.edit().putLong("used_millis", 60 * minute).commit()
        DailyUsageLedger.addActivity(preferences, listOf(UsageInterval(start, start + 2 * minute)), UsageInterval.ANSWERING)
        DailyUsageLedger.addActivity(preferences, listOf(UsageInterval(start + 2 * minute, start + 12 * minute)), UsageInterval.PARENT_UNLOCKED)
        val restored = store.restart().open()
        assertEquals(60 * minute, restored.getLong("used_millis", 0L))
        assertEquals(20 * minute, RewardTimeLedger.balance(restored))
        assertEquals(listOf(UsageInterval(start, start + 2 * minute, kind = UsageInterval.ANSWERING),
            UsageInterval(start + 2 * minute, start + 12 * minute, kind = UsageInterval.PARENT_UNLOCKED)), DailyUsageLedger.intervals(restored))
        DailyUsageLedger.ensureDay(restored, start + 24 * 60 * minute)
        assertEquals(emptyList<UsageInterval>(), DailyUsageLedger.intervals(restored))
        DailyUsageLedger.ensureDay(restored, start)
        assertEquals(12 * minute, DailyUsageLedger.intervals(restored).sumOf { it.usageMillis })
    }

    @Test
    fun activityTransitionsCountThePreviousStateAndExcludeStandbyLocksAndPinEntry() {
        val samples = UsageSampleTracker()
        samples.start(0L, interactive = true, overlayPaused = false)
        assertEquals(UsageInterval.REGULAR, samples.trackedKind)
        assertEquals(5_000L, samples.sample(5_000L, overlayPaused = true, answering = true))
        assertEquals(UsageInterval.ANSWERING, samples.trackedKind)
        samples.sample(15_000L, answering = false)
        assertEquals(null, samples.trackedKind)
        samples.sample(20_000L, overlayPaused = false, parentUnlocked = true)
        assertEquals(UsageInterval.PARENT_UNLOCKED, samples.trackedKind)
        samples.sample(30_000L, interactive = false, parentUnlocked = false)
        assertEquals(null, samples.trackedKind)
        samples.sample(60_000L, interactive = true, timingPaused = true)
        assertEquals(UsageInterval.PARENT_UNLOCKED, samples.trackedKind)
        samples.sample(70_000L, timingPaused = false)
        assertEquals(UsageInterval.REGULAR, samples.trackedKind)
    }

    private fun local(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(value)!!.time
}
