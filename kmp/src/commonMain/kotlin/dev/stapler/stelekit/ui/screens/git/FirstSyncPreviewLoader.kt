// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** `Sync now` needs the remote numbers, so it waits for [FirstSyncPreviewState.Ready]. */
internal val FirstSyncPreviewState.allowsSyncNow: Boolean get() = this is FirstSyncPreviewState.Ready

/** `Sync without preview` is offered only after a failure, timeout or cancel; never while loading or once loaded. */
internal val FirstSyncPreviewState.allowsSyncWithoutPreview: Boolean get() = this is FirstSyncPreviewState.Failed

/**
 * Runs the first-sync preview load as a cancellable job and exposes it as [state]. Owns its scope (never a
 * composition scope) so the load survives recomposition; call [close] when the review leaves the screen.
 */
internal class FirstSyncPreviewLoader(
    private val load: suspend () -> FirstSyncPreviewState,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow<FirstSyncPreviewState>(FirstSyncPreviewState.Loading)
    val state: StateFlow<FirstSyncPreviewState> = _state.asStateFlow()
    private var job: Job? = null

    /** Starts (or restarts) the load; the state reads [FirstSyncPreviewState.Loading] until it finishes. */
    fun start() {
        job?.cancel()
        _state.value = FirstSyncPreviewState.Loading
        job = scope.launch {
            val result = load()
            // Awaiting an already-completed result returns even from a cancelled job, so a plain assignment
            // here could overwrite the state [cancel] just set. Publish only over the still-loading state.
            ensureActive()
            _state.compareAndSet(FirstSyncPreviewState.Loading, result)
        }
    }

    /** Suspends until the current load job has finished or been cancelled. */
    internal suspend fun awaitIdle() {
        job?.join()
    }

    /** Stops a running check and keeps the local part; a finished load is left alone. */
    fun cancel() {
        if (_state.value !is FirstSyncPreviewState.Loading) return
        job?.cancel()
        _state.value = FirstSyncPreviewState.Failed(CANCELLED_MESSAGE)
    }

    fun close() = scope.cancel()

    companion object {
        const val CANCELLED_MESSAGE = "the check was cancelled"
    }
}
