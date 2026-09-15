// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Flat, in-memory-only pending-suggestion store shaped exactly like the existing
 * `AppState.pendingConflicts: Map<String, PendingConflict>`. Session-scoped only — discarded
 * on app exit, no persistence — per ADR-012 (the worst-case restart failure mode is "re-run
 * the proposal," not data loss, since nothing is written to the graph until explicit accept).
 *
 * Plain class, no [kotlinx.coroutines.CoroutineScope] of its own needed: every method here is
 * a synchronous [MutableStateFlow] mutation, no async work inside.
 */
class LlmSuggestionInbox {
    private val _pending = MutableStateFlow<Map<String, PendingLlmSuggestion>>(emptyMap())
    val pending: StateFlow<Map<String, PendingLlmSuggestion>> = _pending.asStateFlow()

    /** Adds [suggestion] to the inbox. Proposing with a duplicate `id` overwrites — `id` is the dedup key. */
    fun propose(suggestion: PendingLlmSuggestion) {
        _pending.update { it + (suggestion.id to suggestion) }
    }

    fun remove(id: String) {
        _pending.update { it - id }
    }

    /** Pending suggestions scoped to a single open graph — used by the review screen and multi-graph guard. */
    fun pendingForGraph(graphId: String): List<PendingLlmSuggestion> =
        _pending.value.values.filter { it.graphId == graphId }
}
