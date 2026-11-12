package com.example.tvparental

/** Samples the previous screen state before applying a power or overlay transition. */
internal class UsageSampleTracker {
    var lastElapsed = 0L
        private set
    var interactive = false
        private set
    var overlayPaused = false
        private set

    fun start(now: Long, interactive: Boolean, overlayPaused: Boolean) {
        lastElapsed = now
        this.interactive = interactive
        this.overlayPaused = overlayPaused
    }

    fun sample(now: Long, interactive: Boolean = this.interactive, overlayPaused: Boolean = this.overlayPaused): Long {
        val elapsed = (now - lastElapsed).coerceAtLeast(0L)
        val counted = if (this.interactive && !this.overlayPaused) elapsed else 0L
        start(now, interactive, overlayPaused)
        return counted
    }
}
