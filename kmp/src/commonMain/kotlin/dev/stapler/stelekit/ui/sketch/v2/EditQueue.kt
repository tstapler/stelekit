// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one gate every edit that must not overlap goes through (solves, label edits, analysis, and undo/redo queued behind them),
 * with a count of the work queued or running so a caller can refuse, queue behind it or wait for it ([settle]).
 */
internal class EditQueue(private val scope: CoroutineScope) {
    private val gate = Mutex()
    private val mutablePending = MutableStateFlow(0)

    /** Edits queued or running; 0 when the document is quiet. */
    val pending: StateFlow<Int> get() = mutablePending

    /**
     * Runs [work] under the gate and counts it until it ends, even when [scope] is already closed (ATOMIC starts the body, whose
     * `finally` then balances the count).
     */
    fun enqueue(work: suspend () -> Unit) {
        mutablePending.update { it + 1 }
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                gate.withLock { work() }
            } finally {
                mutablePending.update { it - 1 }
            }
        }
    }

    /** Runs [work] under the gate from a suspending caller, counted like [enqueue]. */
    suspend fun <T> exclusive(work: suspend () -> T): T {
        mutablePending.update { it + 1 }
        try {
            return gate.withLock { work() }
        } finally {
            mutablePending.update { it - 1 }
        }
    }

    /** Runs [action] now, or behind the work queued before it so its effect lands after theirs. */
    fun afterQueued(action: () -> Unit) {
        if (mutablePending.value == 0) action() else enqueue { action() }
    }

    /** Waits until nothing is queued or running; false when [timeoutMs] passed first. */
    suspend fun settle(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { mutablePending.first { it == 0 } } != null
}
