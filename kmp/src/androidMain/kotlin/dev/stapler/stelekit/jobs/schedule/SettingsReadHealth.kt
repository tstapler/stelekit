// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import android.content.SharedPreferences

/**
 * Plain (non-Keystore) record of the last time a worker could not read task settings, so the status
 * line can say "Can't read settings" instead of a false Late. Holds a timestamp only, never user data.
 */
class SettingsReadHealth(private val prefs: SharedPreferences) : SettingsReadHealthSource {
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    fun recordFailure(nowMs: Long) {
        prefs.edit().putLong(KEY_LAST_FAILURE_MS, nowMs).apply()
    }

    /** Epoch millis of the last unreadable-settings failure, or null if none since the last [clear]. */
    override fun lastFailureMs(): Long? = if (prefs.contains(KEY_LAST_FAILURE_MS)) prefs.getLong(KEY_LAST_FAILURE_MS, 0L) else null

    override fun clear() {
        if (prefs.contains(KEY_LAST_FAILURE_MS)) prefs.edit().remove(KEY_LAST_FAILURE_MS).apply()
    }

    private companion object {
        const val FILE = "stelekit_settings_read_health"
        const val KEY_LAST_FAILURE_MS = "last_failure_ms"
    }
}
