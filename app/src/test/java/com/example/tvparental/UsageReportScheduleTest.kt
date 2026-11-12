package com.example.tvparental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageReportScheduleTest {
    @Test
    fun aFreshServiceReportsImmediatelyEvenAtBootTimeZero() {
        assertTrue(UsageReportSchedule().begin(0L))
    }

    @Test
    fun wakingTheScreenBypassesTheNormalMinuteInterval() {
        val schedule = UsageReportSchedule()
        assertTrue(schedule.begin(0L))
        schedule.complete(100L, true)
        assertFalse(schedule.begin(500L))
        assertTrue(schedule.begin(500L, force = true))
    }

    @Test
    fun ordinaryReportsWaitOneMinuteAfterSuccess() {
        val schedule = UsageReportSchedule()
        schedule.begin(0L)
        schedule.complete(100L, true)
        assertFalse(schedule.begin(60_099L))
        assertTrue(schedule.begin(60_100L))
    }

    @Test
    fun aFailedBootReportRetriesInFiveSecondsInsteadOfOneMinute() {
        val schedule = UsageReportSchedule()
        schedule.begin(0L, force = true)
        schedule.complete(1_000L, false)
        assertTrue(schedule.retryPending)
        assertEquals(5_000L, schedule.followUpDelay(1_000L))
        assertFalse(schedule.begin(5_999L))
        assertTrue(schedule.begin(6_000L))
    }

    @Test
    fun restoringConnectivityCanRetryImmediately() {
        val schedule = UsageReportSchedule()
        schedule.begin(0L)
        schedule.complete(1_000L, false)
        assertTrue(schedule.begin(1_100L, force = schedule.retryPending))
    }

    @Test
    fun aWakeDuringAnExistingReportQueuesOneFreshReportWithoutConcurrentRequests() {
        val schedule = UsageReportSchedule()
        schedule.begin(0L)
        assertFalse(schedule.begin(50L, force = true))
        assertFalse(schedule.begin(60L, force = true))
        assertNull(schedule.followUpDelay(60L))
        schedule.complete(100L, true)
        assertEquals(0L, schedule.followUpDelay(100L))
        assertTrue(schedule.begin(100L))
        schedule.complete(200L, true)
        assertNull(schedule.followUpDelay(200L))
        assertFalse(schedule.begin(200L))
    }

    @Test
    fun repeatedFailuresKeepRetryingAndSuccessReturnsToTheMinuteInterval() {
        val schedule = UsageReportSchedule()
        schedule.begin(0L)
        schedule.complete(100L, false)
        assertTrue(schedule.begin(5_100L))
        schedule.complete(5_200L, false)
        assertEquals(5_000L, schedule.followUpDelay(5_200L))
        assertTrue(schedule.begin(10_200L))
        schedule.complete(10_300L, true)
        assertFalse(schedule.retryPending)
        assertNull(schedule.followUpDelay(10_300L))
        assertFalse(schedule.begin(10_301L))
        assertTrue(schedule.begin(70_300L))
    }
}
