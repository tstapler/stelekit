// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Last successful sync time for [graphId], kept in [settings] when given so it survives restarts. */
internal class PersistedLastSync(private val settings: Settings?, graphId: String) {
    private val key = "git_last_synced_at_$graphId"
    private val _at = MutableStateFlow(settings?.getString(key, "")?.toLongOrNull())
    val at: StateFlow<Long?> = _at.asStateFlow()

    fun record(epochMillis: Long) {
        _at.value = epochMillis
        settings?.putString(key, epochMillis.toString())
    }
}
