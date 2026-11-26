package com.example.tvparental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountDataTest {
    @Test
    fun switchingAccountsClearsPersistedUsageAndRemoteDataButKeepsDevicePinAndSwitches() {
        val store = TestPreferenceStore(mapOf(
            "usage_day" to "2026-11-19",
            "used_millis" to 180_000L,
            "used_millis.2026-11-18" to 600_000L,
            "usage_history_days" to "2026-11-18",
            RewardTimeLedger.GRANTED to 3_000_000L,
            RewardTimeLedger.SPENT to 1_800_000L,
            RewardTimeLedger.USED_TODAY to 180_000L,
            "${RewardTimeLedger.USED_TODAY}.2026-11-18" to 1_620_000L,
            RewardTimeLedger.ACTIVATED to true,
            "daily_reward_limit_minutes" to 45,
            "reward_challenge_day" to "2026-11-19",
            "reward_challenges_remaining" to 0,
            "reward_challenge_reset_version" to 3L,
            "temp_extension_millis" to 900_000L,
            DailyUsageLedger.INTERVALS to "100:200:100",
            "${DailyUsageLedger.INTERVALS}.2026-11-18" to "10:20:10",
            DailyLimitUnlock.UNLOCKED_BOOT to 12,
            "usage_reset_id.2026-11-18" to "previous-command",
            "remote_questions" to "previous account questions",
            "weekly_daily_limit_minutes" to "previous account rules",
            "lockout_poem" to "previous account poem",
            "lockout_until" to 1000L,
            "answering_active" to true,
            "question_timeout_at" to 1000L,
            "applied_password_reset_id" to "old-pin-reset",
            "unlock_password_hash" to "device-pin-hash",
            "unlock_password_salt" to "device-pin-salt",
            "block_recent_watch" to false,
            "show_question_on_startup" to true,
        ))

        ControlState.clearAccountData(store.open())

        val persisted = store.restart().open()
        assertEquals(setOf("unlock_password_hash", "unlock_password_salt", "block_recent_watch", "show_question_on_startup"), persisted.all.keys)
        assertEquals("device-pin-hash", persisted.getString("unlock_password_hash", null))
        assertFalse(persisted.getBoolean("block_recent_watch", true))
    }
}
