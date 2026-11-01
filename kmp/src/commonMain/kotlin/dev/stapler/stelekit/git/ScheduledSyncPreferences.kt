// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.platform.SCHEDULED_SYNC_ENABLED_KEY
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The `scheduled_sync_enabled` switch. Absent or unreadable settings mean off, so a settings
 * failure can never start background network activity.
 */
class ScheduledSyncPreferences(private val settings: Settings) {
    private val _enabled = MutableStateFlow(read())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        try {
            settings.putBoolean(SCHEDULED_SYNC_ENABLED_KEY, value)
        } catch (_: Exception) {
            // Persisting failed; keep the in-memory choice for this session only.
        }
        _enabled.value = value
    }

    private fun read(): Boolean = try {
        settings.getBoolean(SCHEDULED_SYNC_ENABLED_KEY, false)
    } catch (_: Exception) {
        false
    }
}
