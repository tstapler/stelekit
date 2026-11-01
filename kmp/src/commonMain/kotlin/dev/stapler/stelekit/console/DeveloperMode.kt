// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The `developer_mode_enabled` flag. Off unless explicitly stored. [isEnabled] reads the store each
 * time, because the dispatcher gate must not trust a cached value; [enabled] is the observable copy
 * UI uses to show or hide entry points. A failing store reads as off.
 */
class DeveloperMode(private val settings: Settings) {
    private val state = MutableStateFlow(read())
    val enabled: StateFlow<Boolean> = state.asStateFlow()

    fun isEnabled(): Boolean = read()

    fun set(value: Boolean) {
        try {
            settings.putBoolean(KEY, value)
        } catch (_: Throwable) {
            // A store that cannot persist leaves the flag as read() reports it (off).
        }
        state.value = read()
    }

    private fun read(): Boolean = try {
        settings.getBoolean(KEY, false)
    } catch (_: Throwable) {
        false
    }

    companion object {
        const val KEY = "developer_mode_enabled"
        const val WARNING = "Lets you run SQL writes and shell commands. Your notes can be changed or lost."
    }
}
