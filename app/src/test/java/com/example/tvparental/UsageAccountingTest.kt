package com.example.tvparental

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UsageAccountingTest {
    private lateinit var originalTimeZone: TimeZone
    private val minute = 60_000L

    @Before
    fun useTvTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun restoreTimeZone() = TimeZone.setDefault(originalTimeZone)

    @Test
    fun repeatedProcessRestartsPreserveAccumulatedUsage() {
        var store = TestPreferenceStore(mapOf("usage_day" to "2026-11-12", "used_millis" to 20 * minute))
        repeat(10) {
            DailyUsageLedger.ensureDay(store.open(), local("2026-11-12 19:00"))
            DailyUsageLedger.addUsage(store.open(), 10_000L, 60 * minute)
            store = store.restart()
        }
        assertEquals(20 * minute + 100_000L, store.values["used_millis"])
    }

    @Test
    fun anOlderBootClockCannotResetTodaysUsageOrReplayItsResetCommand() {
        val store = TestPreferenceStore(mapOf(
            "usage_day" to "2026-11-12", "used_millis" to 25 * minute,
            "usage_reset_id" to "already-applied", "usage_reset_at" to 123L,
            "temp_extension_millis" to 15 * minute,
        ))
        repeat(3) {
            DailyUsageLedger.ensureDay(store.open(), local("2020-01-01 08:00"))
            DailyUsageLedger.ensureDay(store.open(), local("2026-11-12 19:00"))
        }
        assertEquals("2026-11-12", store.values["usage_day"])
        assertEquals(25 * minute, store.values["used_millis"])
        assertEquals("already-applied", store.values["usage_reset_id"])
        assertEquals(123L, store.values["usage_reset_at"])
        assertEquals(15 * minute, store.values["temp_extension_millis"])
    }

    @Test
    fun forwardClockJumpAndCorrectionRestoreEachDaysOwnTotalAndResetIdentity() {
        var store = TestPreferenceStore(mapOf(
            "usage_day" to "2026-11-12", "used_millis" to 25 * minute,
            "usage_reset_id" to "already-applied", "usage_reset_at" to 123L,
            "temp_extension_millis" to 15 * minute,
        ))
        DailyUsageLedger.ensureDay(store.open(), local("2026-11-13 01:00"))
        assertEquals(0L, store.values["used_millis"])
        DailyUsageLedger.addUsage(store.open(), minute, 60 * minute)
        store = store.restart()
        DailyUsageLedger.ensureDay(store.open(), local("2026-11-12 19:00"))
        assertEquals(25 * minute, store.values["used_millis"])
        assertEquals("already-applied", store.values["usage_reset_id"])
        assertEquals(123L, store.values["usage_reset_at"])
        assertEquals(15 * minute, store.values["temp_extension_millis"])
        DailyUsageLedger.ensureDay(store.open(), local("2026-11-13 01:00"))
        assertEquals(minute, store.values["used_millis"])
        assertEquals("", store.values["usage_reset_id"])
        assertEquals(0L, store.values["temp_extension_millis"])
    }

    @Test
    fun realLocalMidnightCreatesANewDayOnlyOnceAcrossRestarts() {
        var store = TestPreferenceStore(mapOf("usage_day" to "2026-11-12", "used_millis" to 60 * minute))
        DailyUsageLedger.ensureDay(store.open(), local("2026-11-13 00:00"))
        DailyUsageLedger.addUsage(store.open(), 30_000L, 60 * minute)
        store = store.restart()
        DailyUsageLedger.ensureDay(store.open(), local("2026-11-13 00:01"))
        assertEquals("2026-11-13", store.values["usage_day"])
        assertEquals(30_000L, store.values["used_millis"])
    }

    @Test
    fun aReducedQuotaNeverReducesThePersistedUsedTotal() {
        val store = TestPreferenceStore(mapOf("used_millis" to 45 * minute))
        DailyUsageLedger.addUsage(store.open(), 30_000L, 30 * minute)
        assertEquals(45 * minute, store.restart().values["used_millis"])
        DailyUsageLedger.addUsage(store.open(), 30_000L, 60 * minute)
        assertEquals(45 * minute + 30_000L, store.restart().values["used_millis"])
    }

    @Test
    fun recentHistoryIsBoundedAndDoesNotDiscardTheCurrentTotal() {
        var store = TestPreferenceStore()
        val day = Calendar.getInstance().apply { timeInMillis = local("2026-01-01 12:00") }
        repeat(50) {
            DailyUsageLedger.ensureDay(store.open(), day.timeInMillis)
            DailyUsageLedger.addUsage(store.open(), minute, 60 * minute)
            day.add(Calendar.DAY_OF_MONTH, 1)
            store = store.restart()
        }
        assertEquals(minute, store.values["used_millis"])
        assertEquals(32, (store.values["usage_history_days"] as String).split(',').size)
        assertTrue(store.values.size < 140)
    }

    @Test
    fun repeatedStandbyCyclesCountViewingAndExcludeAllStandbyTime() {
        val samples = UsageSampleTracker()
        samples.start(0L, interactive = true, overlayPaused = false)
        var elapsed = 0L
        var counted = 0L
        repeat(10) {
            elapsed += 3_500L
            counted += samples.sample(elapsed, interactive = false)
            elapsed += 10 * minute
            counted += samples.sample(elapsed, interactive = true)
        }
        assertEquals(35_000L, counted)
    }

    @Test
    fun overlaysDoNotCountAndClosingThemResumesFromTheTransition() {
        val samples = UsageSampleTracker()
        samples.start(0L, interactive = true, overlayPaused = false)
        assertEquals(5_000L, samples.sample(5_000L, overlayPaused = true))
        assertEquals(0L, samples.sample(605_000L, overlayPaused = false))
        assertEquals(5_000L, samples.sample(610_000L))
    }

    @Test
    fun serviceRestartDoesNotCountPoweredOffTimeAndShutdownFlushesTheTail() {
        val beforeShutdown = UsageSampleTracker()
        beforeShutdown.start(0L, interactive = true, overlayPaused = false)
        assertEquals(7_500L, beforeShutdown.sample(7_500L, interactive = false))
        val afterReboot = UsageSampleTracker()
        afterReboot.start(2_000L, interactive = true, overlayPaused = false)
        assertEquals(3_000L, afterReboot.sample(5_000L))
    }

    @Test
    fun refreshesSampleBeforeChangingThePreviousScreenState() {
        val samples = UsageSampleTracker()
        samples.start(0L, interactive = true, overlayPaused = false)
        assertEquals(4_000L, samples.sample(4_000L, interactive = false))
        assertEquals(0L, samples.sample(8_000L, interactive = true))
        assertEquals(2_000L, samples.sample(10_000L, interactive = true))
    }

    @Test
    fun manualPauseSettlesTimeAtBothTransitionsWithoutOverridingAnOverlay() {
        val samples = UsageSampleTracker()
        samples.start(0L, interactive = true, overlayPaused = false)
        assertEquals(4_000L, samples.sample(4_000L, timingPaused = true))
        assertEquals(0L, samples.sample(30_000L, overlayPaused = true))
        assertEquals(0L, samples.sample(60_000L, timingPaused = false))
        assertEquals(0L, samples.sample(61_000L, overlayPaused = false))
        assertEquals(2_000L, samples.sample(63_000L))
    }

    @Test
    fun manualPauseSurvivesServiceRecoveryButExpiresAfterPowerOffOrReboot() {
        val store = TestPreferenceStore()
        assertFalse(UsageTimingPause.setPaused(store.open(), boot = -1, paused = true))
        assertTrue(UsageTimingPause.setPaused(store.open(), boot = 12, paused = true))
        val restored = store.restart().open()
        assertTrue(UsageTimingPause.isPaused(restored, boot = 12))
        assertFalse(UsageTimingPause.isPaused(restored, boot = 13))

        // A standby event explicitly clears the pause when the boot has not changed.
        val samples = UsageSampleTracker()
        samples.start(0L, interactive = true, overlayPaused = false, timingPaused = true)
        assertTrue(UsageTimingPause.setPaused(restored, boot = 12, paused = false))
        assertEquals(0L, samples.sample(5_000L, interactive = false, timingPaused = false))
        assertFalse(UsageTimingPause.isPaused(restored, boot = 12))
        assertEquals(0L, samples.sample(60_000L, interactive = true))
        assertEquals(3_000L, samples.sample(63_000L))
    }

    @Test
    fun rewardNeedsConsentAndOnlyTheExcessOverRegularTimeIsCharged() {
        val store = TestPreferenceStore(mapOf(RewardTimeLedger.GRANTED to 50 * minute, "used_millis" to 59 * minute))
        val preferences = store.open()
        assertFalse(DailyUsageLedger.addUsage(preferences, 2 * minute, 60 * minute, 30 * minute))
        assertEquals(50 * minute, RewardTimeLedger.balance(preferences))
        preferences.edit().putBoolean(RewardTimeLedger.ACTIVATED, true).commit()
        assertFalse(DailyUsageLedger.addUsage(preferences, 2 * minute, 61 * minute, 30 * minute))
        assertEquals(61 * minute, preferences.getLong("used_millis", 0L))
        assertEquals(49 * minute, RewardTimeLedger.balance(preferences))
        assertTrue(DailyUsageLedger.addUsage(preferences, 35 * minute, 61 * minute, 30 * minute))
        assertEquals(30 * minute, preferences.getLong(RewardTimeLedger.USED_TODAY, 0L))
        assertEquals(20 * minute, RewardTimeLedger.balance(store.restart().open()))
        assertFalse(preferences.getBoolean(RewardTimeLedger.ACTIVATED, false))
    }

    @Test
    fun rewardBalanceSurvivesMidnightAndClockReversalRestoresTheEarlierCap() {
        val preferences = TestPreferenceStore(mapOf(RewardTimeLedger.GRANTED to 50 * minute)).open()
        val firstDay = local("2026-11-21 23:59")
        DailyUsageLedger.ensureDay(preferences, firstDay)
        preferences.edit().putBoolean(RewardTimeLedger.ACTIVATED, true).commit()
        DailyUsageLedger.addUsage(preferences, 70 * minute, 60 * minute, 30 * minute)
        DailyUsageLedger.ensureDay(preferences, local("2026-11-22 00:01"))
        assertEquals(40 * minute, RewardTimeLedger.balance(preferences))
        assertEquals(30 * minute, RewardTimeLedger.availableToday(preferences, 30 * minute))
        assertFalse(preferences.getBoolean(RewardTimeLedger.ACTIVATED, false))
        DailyUsageLedger.ensureDay(preferences, firstDay)
        assertEquals(20 * minute, RewardTimeLedger.availableToday(preferences, 30 * minute))
    }

    @Test
    fun aStaleSyncCannotRestoreLocallySpentRewardAndNewGrantsAreMerged() {
        val preferences = TestPreferenceStore(mapOf(
            RewardTimeLedger.GRANTED to 50 * minute,
            RewardTimeLedger.SPENT to 25 * minute,
            RewardTimeLedger.USED_TODAY to 10 * minute,
        )).open()
        assertFalse(RewardTimeLedger.reconcile(preferences, true, 50 * minute, 20 * minute, 5 * minute))
        assertTrue(RewardTimeLedger.reconcile(preferences, false, 90 * minute, 25 * minute, 20 * minute))
        assertEquals(65 * minute, RewardTimeLedger.balance(preferences))
        assertEquals(20 * minute, RewardTimeLedger.availableToday(preferences, 30 * minute))
        assertEquals(0L, RewardTimeLedger.availableToday(preferences, 0L))
    }

    private fun local(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(value)!!.time
}
