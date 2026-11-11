// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Observable view of the `homeSketch` flag so navigation recomposes when it flips. [ProjectBridgeSettings]
 * stays the one persistence path; [refresh] re-syncs after an out-of-band write to the same storage.
 */
class HomeSketchFlag(private val settings: ProjectBridgeSettings) {
    private val _enabled = MutableStateFlow(settings.homeSketchEnabled)
    val enabled: StateFlow<Boolean> get() = _enabled

    fun set(on: Boolean) {
        settings.homeSketchEnabled = on
        _enabled.value = on
    }

    /** Persisted value, bypassing the cached state. */
    fun readLive(): Boolean = settings.homeSketchEnabled.also { _enabled.value = it }
}
