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

    @Test
    fun aParentResetRestoresAttemptsAndRejectsOldResponsesAcrossRestart() {
        val store = TestPreferenceStore()
        val today = "2026-11-26"
        ControlState.applyRemoteRewardChallenge(store.open(), today, 0, today)
        ControlState.applyRemoteRewardChallenge(store.open(), today, 2, today, resetVersion = 1L)
        assertEquals(2, ControlState.rewardChallengesRemaining(store.open(), today))
        ControlState.applyRemoteRewardChallenge(store.open(), today, 1, today, resetVersion = 1L)

        val preferences = store.restart().open()
        ControlState.applyRemoteRewardChallenge(preferences, today, 0, today)
        ControlState.applyRemoteRewardChallenge(preferences, today, 2, today, resetVersion = 1L)
        assertEquals(1, ControlState.rewardChallengesRemaining(preferences, today))
        ControlState.applyRemoteRewardChallenge(preferences, today, 0, today, resetVersion = 1L)
        ControlState.applyRemoteRewardChallenge(preferences, today, 2, today, resetVersion = 1L)
        assertEquals(0, ControlState.rewardChallengesRemaining(preferences, today))
        ControlState.applyRemoteRewardChallenge(preferences, today, 2, today, resetVersion = 2L)
        ControlState.applyRemoteRewardChallenge(preferences, today, 0, today, resetVersion = 1L)
        assertEquals(2, ControlState.rewardChallengesRemaining(preferences, today))
    }

    @Test
    fun aPreviousDaysResetDoesNotAffectTheNextDaysCountOrTheNextAccount() {
        val preferences = TestPreferenceStore().open()
        ControlState.applyRemoteRewardChallenge(preferences, "2026-11-26", 0, "2026-11-26", resetVersion = 3L)
        ControlState.applyRemoteRewardChallenge(preferences, "2026-11-27", 1, "2026-11-27")
        ControlState.applyRemoteRewardChallenge(preferences, "2026-11-26", 2, "2026-11-27", resetVersion = 4L)
        assertEquals(1, ControlState.rewardChallengesRemaining(preferences, "2026-11-27"))

        ControlState.applyRemoteRewardChallenge(preferences, "2026-11-27", 0, "2026-11-27", resetVersion = 4L)
        ControlState.clearAccountData(preferences)
        ControlState.applyRemoteRewardChallenge(preferences, "2026-11-27", 1, "2026-11-27")
        assertEquals(1, ControlState.rewardChallengesRemaining(preferences, "2026-11-27"))
    }
}
