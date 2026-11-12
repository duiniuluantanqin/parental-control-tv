package com.example.tvparental

/** Serializes reports, retaining immediate requests and retrying failures before the next minute. */
internal class UsageReportSchedule {
    private var lastSuccess: Long? = null
    private var retryAt: Long? = null
    private var immediatePending = false
    private var inProgress = false

    val retryPending get() = retryAt != null

    fun begin(now: Long, force: Boolean = false): Boolean {
        if (force) immediatePending = true
        if (inProgress) return false
        if (!force && retryAt?.let { now < it } == true) return false
        if (!immediatePending && lastSuccess?.let { now - it < INTERVAL_MS } == true) return false
        immediatePending = false
        retryAt = null
        inProgress = true
        return true
    }

    fun complete(now: Long, success: Boolean) {
        inProgress = false
        if (success) {
            lastSuccess = now
            retryAt = null
        } else {
            immediatePending = true
            retryAt = now + RETRY_MS
        }
    }

    fun followUpDelay(now: Long): Long? {
        if (inProgress) return null
        retryAt?.let { return (it - now).coerceAtLeast(0L) }
        return if (immediatePending) 0L else null
    }

    private companion object {
        const val INTERVAL_MS = 60_000L
        const val RETRY_MS = 5_000L
    }
}
