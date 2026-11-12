package com.example.tvparental

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun local(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(value)!!.time
}
