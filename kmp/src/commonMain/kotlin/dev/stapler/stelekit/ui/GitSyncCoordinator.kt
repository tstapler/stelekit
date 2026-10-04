// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the git sync status machinery and the git setup/conflict/journal-merge dialog surfaces:
 * the derived [syncState]/[gitLastSyncAt] `StateFlow`s built from the active [GitSyncService],
 * the git setup wizard (`gitConfig`/`gitSetupVisible`/`gitSetupInitialStep`/`gitSetupOpenForClone`),
 * and the conflict-resolution/journal-merge-review dialogs
 * (`conflictResolutionVisible`/`diskConflictViewFullVisible`/`journalMergeReviewVisible`).
 *
 * Extracted from [StelekitViewModel] (Phase 3 of the decomposition plan in
 * `project_plans/stelekit-viewmodel-decomposition/plan.md`), following the same
 * parameter-object-driven collaborator pattern [SectionManagementCoordinator] (Phase 1) and
 * [LlmSuggestionCoordinator] (Phase 2) established.
 *
 * Shares the ViewModel's [AppState] directly via [uiState] rather than owning a separate
 * `MutableStateFlow` of its own, for the same reason documented on
 * [SectionManagementCoordinator]: the fields this coordinator owns are pre-existing `AppState`
 * fields read directly by Compose call sites across the app (`GraphContentLeftSidebar`,
 * `GraphContentMainArea`, `GraphDialogLayer`) via `uiState.value.xxx`. Splitting them into a
 * second `StateFlow` would require reworking every one of those read sites — out of scope for a
 * mechanical, behavior-preserving extraction.
 *
 * [syncState] and [gitLastSyncAt] are, unlike [AppState] fields, public properties exposed
 * directly on this coordinator and re-exported as forwarding properties on [StelekitViewModel] —
 * both are read directly by `GraphContentActiveShell`/`GraphDialogLayer` via
 * `viewModel.syncState`/`viewModel.gitLastSyncAt` and by dedicated test coverage
 * (`StelekitViewModelSyncStateTest`/`StelekitViewModelSyncStateIntegrationTest`), so the public
 * shape must be preserved byte-for-byte. `gitLocalStatusCountFlow` stays private — it is only
 * ever consumed internally to build [syncState] and has no external readers.
 *
 * Reuses the ViewModel's own [scope] rather than creating a new one, for the same lifecycle
 * reasons documented on [SectionManagementCoordinator]: it is cancelled in
 * [StelekitViewModel.close], and this coordinator is held as a `private val` field with the same
 * lifetime as the ViewModel itself.
 */
class GitSyncCoordinator(
    private val activeGitSyncService: StateFlow<GitSyncService?>,
    private val localChangesCountFlow: StateFlow<Int>?,
    private val activeGraphIdProvider: () -> String?,
    private val onDismissGitDetection: (suspend (graphId: String) -> Unit)?,
    private val onDismissContentMismatchBanner: (suspend (graphId: String) -> Unit)?,
    private val onDismissBrowserOnlySyncBanner: (suspend (graphId: String) -> Unit)?,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<AppState>,
) {
    /**
     * Dirty-file count derived from [GitSyncService.localStatus] — the platform-agnostic source,
     * refreshed on graph open, each periodic poll tick, and after every sync/commit (see
     * [GitSyncService.refreshLocalStatus]). Works identically on every platform, unlike
     * [localChangesCountFlow] below.
     */
    private val gitLocalStatusCountFlow: Flow<Int> = activeGitSyncService
        .flatMapLatest { service -> service?.localStatus ?: flowOf(null) }
        .map { status -> (status?.untrackedFiles?.size ?: 0) + (status?.modifiedFiles?.size ?: 0) }

    /**
     * Emits the current [SyncState] from the active [GitSyncService], upgraded to
     * [SyncState.LocalChangesPending] when either dirty-count source reports a nonzero count while
     * the raw state is otherwise [SyncState.Idle] (Epic 4.3, Story 4.3.2): [gitLocalStatusCountFlow]
     * (all platforms) or [localChangesCountFlow] (web-only file-watcher signal, kept as a second,
     * lower-latency source — a local git status read can lag a just-written file by one poll tick).
     * Falls back to [SyncState.Idle] when no git sync service is configured.
     *
     * The upgrade only ever overrides [SyncState.Idle] — it never interrupts an in-progress state
     * (`Fetching`/`Merging`/`Pushing`/`Committing`/etc.).
     */
    val syncState: StateFlow<SyncState> = activeGitSyncService
        .flatMapLatest { service -> service?.syncState ?: flowOf(SyncState.Idle) }
        .combine(
            gitLocalStatusCountFlow.combine(localChangesCountFlow ?: flowOf(0)) { a, b -> maxOf(a, b) }
        ) { rawState, count ->
            if (rawState is SyncState.Idle && count > 0) SyncState.LocalChangesPending(count) else rawState
        }
        .stateIn(scope, SharingStarted.Eagerly, SyncState.Idle)

    /** Epoch-millis of the last successful sync for the active graph, persisted across app
     * restarts (see [GitSyncService.lastSyncAt]) — null when never synced or no service is active. */
    val gitLastSyncAt: StateFlow<Long?> = activeGitSyncService
        .flatMapLatest { service -> service?.lastSyncAt ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    internal fun observeSyncState() {
        scope.launch {
            syncState.collect { state ->
                when (state) {
                    is SyncState.ConflictPending -> uiState.update { it.copy(conflictResolutionVisible = true) }
                    is SyncState.JournalMergeReady -> uiState.update { it.copy(journalMergeReviewVisible = true) }
                    // Do NOT auto-dismiss journalMergeReviewVisible here — dismissal is handled
                    // explicitly by abortJournalMerge() and acceptJournalMerge(). Auto-dismissal
                    // races with fetchOnly background calls that emit Fetching/Pushing states.
                    else -> Unit
                }
            }
        }
    }

    /** Triggers a full sync (commit → fetch → merge → push) on the active graph. */
    fun triggerSync() {
        val graphId = activeGraphIdProvider() ?: uiState.value.currentGraphId ?: return
        scope.launch {
            activeGitSyncService.value?.sync(graphId)
        }
    }

    /** Triggers a fetch-only check for remote changes on the active graph. */
    fun triggerFetchOnly() {
        val graphId = activeGraphIdProvider() ?: uiState.value.currentGraphId ?: return
        scope.launch {
            activeGitSyncService.value?.fetchOnly(graphId)
        }
    }

    /** Reflects the active graph's real [dev.stapler.stelekit.git.GitConfigRepository] state (or `null`) into [AppState.gitConfig] — this field previously had no writer at all. */
    fun setGitConfig(config: GitConfig?) {
        uiState.update { it.copy(gitConfig = config) }
    }

    /** Opens the git setup wizard. */
    fun openGitSetup() {
        uiState.update { it.copy(gitSetupVisible = true) }
    }

    /** Dismisses the git setup wizard. */
    fun dismissGitSetup() {
        uiState.update { it.copy(gitSetupVisible = false, gitSetupInitialStep = 1, gitSetupOpenForClone = false) }
    }

    /** Opens the git setup wizard pre-navigated to Step 3 (credentials). */
    fun openGitSetupForCredentials() {
        uiState.update { it.copy(gitSetupVisible = true, gitSetupInitialStep = 3) }
    }

    /** Opens the git setup wizard in clone-from-URL mode (pre-selects clone, starts at step 2). */
    fun openGitSetupForClone() {
        uiState.update { it.copy(gitSetupVisible = true, gitSetupInitialStep = 2, gitSetupOpenForClone = true) }
    }

    /** Dismisses the conflict resolution screen. */
    fun dismissConflictResolution() {
        uiState.update { it.copy(conflictResolutionVisible = false) }
    }

    /** Opens the full-screen line-diff view for the current disk conflict. */
    fun showDiskConflictFullView() {
        uiState.update { it.copy(diskConflictViewFullVisible = true) }
    }

    /** Closes the full-screen line-diff view, returning to the still-open DiskConflictDialog. */
    fun hideDiskConflictFullView() {
        uiState.update { it.copy(diskConflictViewFullVisible = false) }
    }

    /** Dismisses the journal merge review screen without applying the merge. */
    fun dismissJournalMergeReview() {
        uiState.update { it.copy(journalMergeReviewVisible = false) }
    }

    /**
     * Aborts the in-progress git merge and dismisses the review screen.
     * Called when the user dismisses or falls back to manual resolution.
     */
    fun abortJournalMerge() {
        val state = syncState.value as? SyncState.JournalMergeReady ?: run {
            uiState.update { it.copy(journalMergeReviewVisible = false) }
            return
        }
        uiState.update { it.copy(journalMergeReviewVisible = false) }
        scope.launch {
            // Re-validate: syncState may have advanced (e.g. auto-completed) between capture and execution
            if (syncState.value !is SyncState.JournalMergeReady) return@launch
            activeGitSyncService.value?.abortActiveMerge(state.graphId)
        }
    }

    /**
     * Applies the user-approved merged content for a journal conflict: writes to disk,
     * marks resolved, commits, reloads, and pushes.
     */
    fun acceptJournalMerge(mergedContent: String) {
        val state = syncState.value as? SyncState.JournalMergeReady ?: return
        uiState.update { it.copy(journalMergeReviewVisible = false) }
        scope.launch {
            // Re-validate: syncState may have advanced between capture and execution
            if (syncState.value !is SyncState.JournalMergeReady) return@launch
            activeGitSyncService.value?.applyJournalMerge(
                graphId = state.graphId,
                filePath = state.proposal.filePath,
                mergedContent = mergedContent,
            )
        }
    }

    /** Dismisses the git auto-detection banner for the given graph. */
    fun dismissGitDetection(graphId: String) {
        scope.launch {
            onDismissGitDetection?.invoke(graphId)
        }
    }

    /** Dismisses the content mismatch banner for the given graph. */
    fun dismissContentMismatchBanner(graphId: String) {
        scope.launch {
            onDismissContentMismatchBanner?.invoke(graphId)
        }
    }

    /** Dismisses the "not synced to disk" browser-only-storage banner for the given graph. */
    fun dismissBrowserOnlySyncBanner(graphId: String) {
        scope.launch {
            onDismissBrowserOnlySyncBanner?.invoke(graphId)
        }
    }

    /** Opens the wiki subdirectory fix dialog. */
    fun openWikiSubdirFixDialog() {
        uiState.update { it.copy(wikiSubdirFixDialogVisible = true) }
    }

    /** Dismisses the wiki subdirectory fix dialog. */
    fun dismissWikiSubdirFixDialog() {
        uiState.update { it.copy(wikiSubdirFixDialogVisible = false) }
    }
}
