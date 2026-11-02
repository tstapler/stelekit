// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One graph's [SyncStalenessStore] entry plus an observable copy, so the live service can drive the chip and banner. */
class StalenessTracker(val store: SyncStalenessStore?, private val graphId: String, private val clock: () -> Long) {
    private val _state = MutableStateFlow(store?.read(graphId) ?: SyncStaleness())
    val state: StateFlow<SyncStaleness> = _state.asStateFlow()

    /** The app is open, so a leftover "app closed" fetch-only marker from the worker no longer applies. */
    fun onServiceStarted() = write {
        it.markSeen(graphId, clock())
        if (it.read(graphId).fetchOnlyReason == FetchOnlyReason.AppClosed) it.setFetchOnlyReason(graphId, null)
    }

    companion object {
        fun create(settings: Settings?, graphId: String, clock: () -> Long) = StalenessTracker(settings?.let { SyncStalenessStore(it) }, graphId, clock)
    }

    fun merged(atMs: Long) = write { it.recordMerged(graphId, atMs) }

    fun fetched(behindCount: Int) = write { it.recordFetched(graphId, clock(), behindCount) }

    fun failed(error: Any) = write { it.recordError(graphId, clock(), error::class.simpleName ?: "GitError") }

    private fun write(change: (SyncStalenessStore) -> Unit) {
        val s = store ?: return
        change(s)
        _state.value = s.read(graphId)
    }
}
