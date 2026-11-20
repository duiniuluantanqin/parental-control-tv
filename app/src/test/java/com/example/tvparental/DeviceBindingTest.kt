package com.example.tvparental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeviceBindingTest {
    @Test
    fun onlyExplicitOwnershipConflictAsksToUnbind() {
        assertEquals(DeviceBinding.CREDENTIALS_CHANGED_MESSAGE,
            DeviceBinding.responseErrorMessage(409, "device_already_bound"))
        assertNotEquals(DeviceBinding.CREDENTIALS_CHANGED_MESSAGE,
            DeviceBinding.responseErrorMessage(401, ""))
        assertNotEquals(DeviceBinding.CREDENTIALS_CHANGED_MESSAGE,
            DeviceBinding.responseErrorMessage(409, ""))
    }
}
