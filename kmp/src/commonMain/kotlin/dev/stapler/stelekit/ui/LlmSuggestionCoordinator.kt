// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.llm.LlmSuggestionInbox
import dev.stapler.stelekit.llm.LlmSuggestionWriter
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the LLM approval-gated edit workflow (Epic 7): the pending-suggestion review screen's
 * visibility and the AI-provider settings surface. Almost a pure facade — the actual suggestion
 * storage and write logic already live in the injected [llmSuggestionInbox]/[llmSuggestionWriter]
 * collaborators; this class only orchestrates between them and [AppState].
 *
 * Extracted from [StelekitViewModel] (Phase 2 of the decomposition plan in
 * `project_plans/stelekit-viewmodel-decomposition/plan.md`), following the same
 * parameter-object-driven collaborator pattern [SectionManagementCoordinator] established in
 * Phase 1.
 *
 * Shares the ViewModel's [AppState] directly via [uiState] rather than owning a separate
 * `MutableStateFlow` of its own (contrast [LlmSuggestionInbox] or
 * [dev.stapler.stelekit.ui.annotate.DepthEstimationCoordinator], which introduce new state not
 * previously part of `AppState`): the two fields this coordinator owns
 * (`llmSuggestionReviewVisible`/`llmProviderSettingsVisible`) are pre-existing `AppState` fields
 * read directly by Compose call sites across the app (`GraphDialogLayer`) via
 * `uiState.value.xxx`. Splitting them into a second `StateFlow` would require reworking every one
 * of those read sites to combine two flows — out of scope for a mechanical, behavior-preserving
 * extraction — so this coordinator mutates the same backing `MutableStateFlow<AppState>` the
 * ViewModel exposes as `uiState`, exactly as the original methods did before the move.
 *
 * Reuses the ViewModel's own [scope] rather than creating a new one: that scope already carries
 * the ViewModel's `CoroutineExceptionHandler` guard and is cancelled in [StelekitViewModel.close],
 * so this coordinator's launched work is cancelled for free at the same point. Safe because the
 * coordinator is held as a `private val` field with the same lifetime as the ViewModel itself —
 * never a `rememberCoroutineScope()`-derived scope.
 */
class LlmSuggestionCoordinator(
    private val llmSuggestionInbox: LlmSuggestionInbox,
    private val llmSuggestionWriter: LlmSuggestionWriter,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<AppState>,
    private val activeGraphIdProvider: () -> String?,
    private val sendSnackbar: (String) -> Unit,
) {
    private val logger = Logger("LlmSuggestionCoordinator")

    /** Live pending-suggestion map — exposed for the review screen. */
    val llmSuggestions: StateFlow<Map<String, PendingLlmSuggestion>> = llmSuggestionInbox.pending

    /**
     * Observes [llmSuggestionInbox], flipping [AppState.llmSuggestionReviewVisible] to `true`
     * when the currently active graph gains at least one pending suggestion. Structurally
     * parallel to `StelekitViewModel.observeSyncState`'s `syncState.collect` — does NOT
     * auto-dismiss when the inbox becomes empty via accept/reject (those explicitly set
     * visibility, same "do NOT auto-dismiss" rule as journal-merge review).
     */
    internal fun observeLlmSuggestions() {
        scope.launch {
            llmSuggestionInbox.pending.collect { pending ->
                val currentGraphId = activeGraphIdProvider() ?: uiState.value.currentGraphId
                val hasPendingForCurrentGraph = currentGraphId != null &&
                    pending.values.any { it.graphId == currentGraphId }
                if (hasPendingForCurrentGraph) {
                    uiState.update { it.copy(llmSuggestionReviewVisible = true) }
                }
            }
        }
    }

    /** Routes a suggestion from TagSuggestionViewModel's scan into the inbox. */
    fun proposeLlmSuggestion(suggestion: PendingLlmSuggestion) {
        llmSuggestionInbox.propose(suggestion)
    }

    /** Dismisses the LLM suggestion review screen without accepting or rejecting anything. */
    fun dismissLlmSuggestionReview() {
        uiState.update { it.copy(llmSuggestionReviewVisible = false) }
    }

    /**
     * Rejects a pending LLM suggestion. Pure in-memory removal, cannot fail — no confirmation
     * dialog required at the call site (features research §3's "reject should be a single tap,
     * no are-you-sure" recommendation).
     */
    fun rejectLlmSuggestion(id: String) {
        llmSuggestionInbox.remove(id)
    }

    /**
     * Accepts a pending LLM suggestion: re-validates it is still present and still scoped to
     * the currently active graph, optimistically removes it from the inbox, then materializes
     * and writes it via [llmSuggestionWriter] (Story 7.4's staleness re-check + `GraphWriterPort`
     * call). Errors are surfaced via [sendSnackbar] — never silently swallowed.
     */
    fun acceptLlmSuggestion(id: String) {
        // Re-validate: already resolved/expired — matches abortJournalMerge's "state may have
        // advanced" guard shape.
        val suggestion = llmSuggestionInbox.pending.value[id] ?: return

        val currentGraphId = activeGraphIdProvider() ?: uiState.value.currentGraphId
        if (suggestion.graphId != currentGraphId) {
            // Do not apply, and do not remove from the inbox — it's still there if the user
            // switches back to the graph this suggestion targets.
            sendSnackbar("Switch back to the graph this suggestion targets to review it")
            return
        }

        val graphPath = uiState.value.currentGraphPath ?: return

        llmSuggestionInbox.remove(id)

        scope.launch {
            val result = llmSuggestionWriter.materializeAndWrite(suggestion, graphPath)
            result.onLeft { error ->
                logger.error("acceptLlmSuggestion failed for id=$id: ${error.message}")
                sendSnackbar(error.message)
            }
        }
    }

    /** Opens the LLM provider settings surface ("Settings → AI Providers"). */
    fun openLlmProviderSettings() {
        uiState.update { it.copy(llmProviderSettingsVisible = true) }
    }

    /** Dismisses the LLM provider settings surface. */
    fun dismissLlmProviderSettings() {
        uiState.update { it.copy(llmProviderSettingsVisible = false) }
    }
}
