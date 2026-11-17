package com.example.isle

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty. Granting "Notification access" to this component is what lets
 * MediaSessionManager.getActiveSessions() return the currently playing media session,
 * so the island can show the track title and control playback. Isle never reads notifications.
 */
class MediaListener : NotificationListenerService()
