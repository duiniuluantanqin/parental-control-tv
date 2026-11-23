package com.example.tvparental

/** Settles the previous screen and pause states before applying a transition. */
internal class UsageSampleTracker {
    var lastElapsed = 0L
        private set
    var interactive = false
        private set
    var overlayPaused = false
        private set

    var timingPaused = false
        private set
    var answering = false
        private set
    var parentUnlocked = false
        private set

    val trackedKind: String?
        get() = when {
            !interactive -> null
            answering -> UsageInterval.ANSWERING
            overlayPaused -> null
            timingPaused || parentUnlocked -> UsageInterval.PARENT_UNLOCKED
            else -> UsageInterval.REGULAR
        }

    fun start(now: Long, interactive: Boolean, overlayPaused: Boolean, timingPaused: Boolean = false,
              answering: Boolean = false, parentUnlocked: Boolean = false) {
        lastElapsed = now
        this.interactive = interactive
        this.overlayPaused = overlayPaused
        this.timingPaused = timingPaused
        this.answering = answering
        this.parentUnlocked = parentUnlocked
    }

    fun sample(
        now: Long,
        interactive: Boolean = this.interactive,
        overlayPaused: Boolean = this.overlayPaused,
        timingPaused: Boolean = this.timingPaused,
        answering: Boolean = this.answering,
        parentUnlocked: Boolean = this.parentUnlocked,
    ): Long {
        val elapsed = (now - lastElapsed).coerceAtLeast(0L)
        val counted = if (this.interactive && !this.overlayPaused && !this.timingPaused) elapsed else 0L
        start(now, interactive, overlayPaused, timingPaused, answering, parentUnlocked)
        return counted
    }
}
