// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import android.service.notification.NotificationListenerService

/**
 * Required declaration for MediaSessionManager.getActiveSessions() — the API requires a
 * NotificationListenerService ComponentName to authorize cross-app MediaSession access.
 * The service itself does nothing; all media session reading is done by MediaSessionObserver.
 */
class SteleKitNotificationListenerService : NotificationListenerService()
