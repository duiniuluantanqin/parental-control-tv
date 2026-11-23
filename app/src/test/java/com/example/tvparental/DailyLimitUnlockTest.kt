package com.example.tvparental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyLimitUnlockTest {
    private val minute = 60_000L

    @Test
    fun anUnlockSurvivesProcessRecoveryButNotANewBoot() {
        val store = TestPreferenceStore()
        assertFalse(DailyLimitUnlock.isActive(store.open(), boot = 12))
        assertTrue(DailyLimitUnlock.setActive(store.open(), boot = 12, active = true))

        val restored = store.restart().open()
        assertTrue(DailyLimitUnlock.isActive(restored, boot = 12))
        assertFalse(DailyLimitUnlock.isActive(restored, boot = 13))
        assertFalse(DailyLimitUnlock.isActive(restored, boot = -1))
    }

    @Test
    fun standbyRestoresTheQuotaAndPreservesUsageAndRewards() {
        val store = TestPreferenceStore(mapOf(
            "used_millis" to 60 * minute,
            RewardTimeLedger.GRANTED to 20 * minute,
        ))
        val preferences = store.open()
        DailyLimitUnlock.setActive(preferences, boot = 12, active = true)
        DailyUsageLedger.addActivity(preferences, listOf(UsageInterval(minute, 11 * minute)), UsageInterval.PARENT_UNLOCKED)
        assertEquals(60 * minute, preferences.getLong("used_millis", 0L))
        assertEquals(10 * minute, DailyUsageLedger.intervals(preferences).sumOf { it.usageMillis })
        assertEquals(20 * minute, RewardTimeLedger.balance(preferences))
        assertEquals(0L, preferences.getLong(RewardTimeLedger.USED_TODAY, 0L))

        assertTrue(DailyLimitUnlock.setActive(preferences, boot = 12, active = false))
        val restored = store.restart().open()
        assertFalse(DailyLimitUnlock.isActive(restored, boot = 12))
        DailyUsageLedger.addUsage(restored, minute, 60 * minute, 30 * minute)
        assertEquals(60 * minute, restored.getLong("used_millis", 0L))
        assertEquals(20 * minute, RewardTimeLedger.balance(restored))
    }

    @Test
    fun unlockedViewingDoesNotSpendPreviouslyAcceptedRewards() {
        val preferences = TestPreferenceStore(mapOf(
            "used_millis" to 60 * minute,
            RewardTimeLedger.GRANTED to 20 * minute,
            RewardTimeLedger.SPENT to 5 * minute,
            RewardTimeLedger.USED_TODAY to 5 * minute,
            RewardTimeLedger.ACTIVATED to true,
        )).open()
        DailyUsageLedger.addActivity(preferences, listOf(UsageInterval(minute, 11 * minute)), UsageInterval.PARENT_UNLOCKED)
        assertEquals(60 * minute, preferences.getLong("used_millis", 0L))
        assertEquals(15 * minute, RewardTimeLedger.balance(preferences))
        assertEquals(5 * minute, preferences.getLong(RewardTimeLedger.USED_TODAY, 0L))
    }

    @Test
    fun anUnknownBootCannotGrantAnUnlock() {
        val preferences = TestPreferenceStore().open()
        assertFalse(DailyLimitUnlock.setActive(preferences, boot = -1, active = true))
        assertFalse(DailyLimitUnlock.isActive(preferences, boot = -1))
        assertFalse(preferences.contains(DailyLimitUnlock.UNLOCKED_BOOT))
    }
}
