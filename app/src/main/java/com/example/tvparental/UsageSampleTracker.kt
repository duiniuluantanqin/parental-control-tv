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

    fun start(now: Long, interactive: Boolean, overlayPaused: Boolean, timingPaused: Boolean = false) {
        lastElapsed = now
        this.interactive = interactive
        this.overlayPaused = overlayPaused
        this.timingPaused = timingPaused
    }

    fun sample(
        now: Long,
        interactive: Boolean = this.interactive,
        overlayPaused: Boolean = this.overlayPaused,
        timingPaused: Boolean = this.timingPaused,
    ): Long {
        val elapsed = (now - lastElapsed).coerceAtLeast(0L)
        val counted = if (this.interactive && !this.overlayPaused && !this.timingPaused) elapsed else 0L
        start(now, interactive, overlayPaused, timingPaused)
        return counted
    }
}
