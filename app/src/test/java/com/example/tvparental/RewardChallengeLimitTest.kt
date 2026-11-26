package com.example.tvparental

import org.junit.Assert.assertEquals
import org.junit.Test

class RewardChallengeLimitTest {
    @Test
    fun exhaustedAttemptsSurviveRestartAndAStaleSyncCannotRestoreThem() {
        val store = TestPreferenceStore()
        val today = "2026-11-26"
        ControlState.applyRemoteRewardChallenge(store.open(), today, 1, today)
        ControlState.applyRemoteRewardChallenge(store.open(), today, 0, today)
        val preferences = store.restart().open()
        ControlState.applyRemoteRewardChallenge(preferences, today, 2, today)
        assertEquals(0, ControlState.rewardChallengesRemaining(preferences, today))
    }

    @Test
    fun aNewDayRestoresTwoAttemptsAndIgnoresAnEarlierDaysResponse() {
        val preferences = TestPreferenceStore().open()
        ControlState.applyRemoteRewardChallenge(preferences, "2026-11-26", 0, "2026-11-26")
        assertEquals(2, ControlState.rewardChallengesRemaining(preferences, "2026-11-27"))
        ControlState.applyRemoteRewardChallenge(preferences, "2026-11-27", 1, "2026-11-27")
        ControlState.applyRemoteRewardChallenge(preferences, "2026-11-26", 0, "2026-11-27")
        assertEquals(1, ControlState.rewardChallengesRemaining(preferences, "2026-11-27"))
    }
}
