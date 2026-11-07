package com.example.tvparental

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings

object MediaPlaybackController {
    fun hasAccess(context: Context): Boolean {
        val component = ComponentName(context, MediaControlListenerService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    fun pauseActivePlayback(context: Context) {
        if (!hasAccess(context)) return
        val component = ComponentName(context, MediaControlListenerService::class.java)
        try {
            val sessions = context.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(component)
            sessions.forEach(::pauseIfPlaying)
        } catch (_: SecurityException) {
            // Notification access can be revoked between the settings check and session lookup.
        }
    }

    private fun pauseIfPlaying(controller: MediaController) {
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        }
    }
}
