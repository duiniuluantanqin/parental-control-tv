package com.example.tvparental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockPasswordTest {
    @Test
    fun freshInstallInitializesDefaultPinAndPersistsOnlySaltedHash() {
        val store = TestPreferenceStore()
        val preferences = store.open()

        assertTrue(ControlState.hasUnlockPassword(preferences))
        assertTrue(ControlState.verifyUnlockPassword(preferences, "UDLRUU"))
        assertFalse(ControlState.verifyUnlockPassword(preferences, "UUUUUU"))
        assertNotEquals("UDLRUU", preferences.getString("unlock_password_hash", null))
        assertFalse(preferences.getString("unlock_password_salt", null).isNullOrBlank())
        assertEquals(setOf("unlock_password_hash", "unlock_password_salt"), preferences.all.keys)
        assertTrue(ControlState.verifyUnlockPassword(store.restart().open(), "UDLRUU"))
    }

    @Test
    fun verificationWorksBeforeLauncherChecksPin() {
        assertTrue(ControlState.verifyUnlockPassword(TestPreferenceStore().open(), "UDLRUU"))
    }

    @Test
    fun existingCustomPinIsNeverReplacedByDefault() {
        val store = TestPreferenceStore()
        val preferences = store.open()
        assertTrue(ControlState.setUnlockPassword(preferences, "LRLRUD"))
        val savedHash = preferences.getString("unlock_password_hash", null)

        assertTrue(ControlState.hasUnlockPassword(preferences))
        assertTrue(ControlState.verifyUnlockPassword(preferences, "LRLRUD"))
        assertFalse(ControlState.verifyUnlockPassword(preferences, "UDLRUU"))
        assertEquals(savedHash, preferences.getString("unlock_password_hash", null))
        assertTrue(ControlState.verifyUnlockPassword(store.restart().open(), "LRLRUD"))
    }
}
