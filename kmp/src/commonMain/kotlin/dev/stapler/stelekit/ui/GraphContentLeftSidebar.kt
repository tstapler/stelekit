// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import dev.stapler.stelekit.platform.HostAccessState
import dev.stapler.stelekit.ui.components.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Everything [GraphContentLeftSidebar] needs beyond [GraphContentDeps] (Parameter Object
 * pattern) — mostly already-computed values from other GraphContent setup stacks, plus the small
 * slice of the "New graph…" flow this sidebar's actions can kick off (see that flow's dialogs at
 * [GraphContent]'s call site).
 */
internal class GraphContentLeftSidebarInputs(
    val appState: AppState,
    val isMobile: Boolean,
    val demoBannerDismissedState: MutableState<Boolean>,
    val hostAccessState: HostAccessState,
    val hostWritePendingCount: Int,
    val hostWriteStuck: Boolean,
    val onReconnectHostDirectory: (() -> Unit)?,
    val onCopyPages: (() -> Unit)?,
    val copyDirection: dev.stapler.stelekit.merge.CopyDirection = dev.stapler.stelekit.merge.CopyDirection.Push,
    val onCopyPagesFromGraph: ((dev.stapler.stelekit.model.GraphInfo) -> Unit)? = null,
    val interceptGraphSwitch: (GraphId, () -> Unit) -> Unit = { _, proceed -> proceed() },
    val activeGraphInfo: dev.stapler.stelekit.model.GraphInfo?,
    val graphRegistry: GraphRegistry,
    val activeGraphId: GraphId?,
    val activeGraphPath: String,
    val activeSectionIds: List<String>?,
    val vaultManager: dev.stapler.stelekit.vault.VaultManager?,
    val syncState: dev.stapler.stelekit.git.model.SyncState,
    val gitLastSyncAt: Long?,
    val firstSyncReviewPending: Boolean = false,
    val storageLocationResolver: dev.stapler.stelekit.db.StorageLocationResolver?,
    val gitRepository: dev.stapler.stelekit.git.GitRepository?,
    val onStorageLocationChoose: (StorageMoveOperation) -> Unit,
    val onStartNewGraphFlow: () -> Unit,
    val onShowNewGraphLocationPicker: (appOwnedPath: String) -> Unit,
    val scope: CoroutineScope,
    val closeSidebarIfMobile: () -> Unit,
)

/**
 * Renders [LeftSidebar] and wires all of its callbacks. Split out from [GraphContent] purely for
 * length/nesting — see ADR-001-style decomposition rationale at [GraphContent]'s own doc.
 */
@Composable
internal fun GraphContentLeftSidebar(
    deps: GraphContentDeps,
    viewModel: StelekitViewModel,
    inputs: GraphContentLeftSidebarInputs,
) {
    val fileSystem = deps.fileSystem
    val graphManager = deps.graphManager
    val appState = inputs.appState
    var demoBannerDismissed by inputs.demoBannerDismissedState

    LeftSidebar(
        expanded = appState.sidebarExpanded,
        isLoading = appState.isLoading,
        favoritePages = appState.favoritePages,
        recentPages = appState.recentPages,
        currentScreen = appState.currentScreen,
        currentGraphName = inputs.activeGraphInfo?.displayName ?: "",
        availableGraphs = inputs.graphRegistry.graphs,
        activeGraphId = inputs.activeGraphId?.value,
        pendingConflictFilePaths = appState.pendingConflictFilePaths,
        isDemoActive = inputs.activeGraphInfo?.isDemo == true,
        demoBannerDismissed = demoBannerDismissed,
        onDismissDemoBanner = { demoBannerDismissed = true },
        hostAccessState = inputs.hostAccessState,
        hostPendingWriteCount = inputs.hostWritePendingCount,
        hostWriteStuck = inputs.hostWriteStuck,
        onReconnectHostDirectory = inputs.onReconnectHostDirectory ?: {},
        onCopyPages = inputs.onCopyPages?.let { open -> { open(); inputs.closeSidebarIfMobile() } },
        copyDirection = inputs.copyDirection,
        onCopyPagesFromGraph = inputs.onCopyPagesFromGraph?.let { open -> { graph -> open(graph); inputs.closeSidebarIfMobile() } },
        onPageClick = { page ->
            viewModel.navigateTo(Screen.PageView(page))
            inputs.closeSidebarIfMobile()
        },
        onNavigate = { route ->
            viewModel.navigateTo(route)
            inputs.closeSidebarIfMobile()
        },
        onToggleFavorite = { viewModel.toggleFavorite(it) },
        onGraphSelected = { id ->
            inputs.interceptGraphSwitch(GraphId(id)) { inputs.scope.launch { graphManager.switchGraph(GraphId(id)) } }
            inputs.closeSidebarIfMobile()
        },
        onAddGraph = {
            handleAddGraph(deps, viewModel, inputs)
            inputs.closeSidebarIfMobile()
        },
        onNewGraph = {
            inputs.onStartNewGraphFlow()
            inputs.closeSidebarIfMobile()
        },
        onRemoveGraph = { id ->
            inputs.scope.launch {
                if (!graphManager.removeGraph(GraphId(id))) {
                    viewModel.sendSnackbar("Switch to another graph before removing the active one")
                }
            }
        },
        onUpdateGraphPath = { id, newPath -> updateGraphPath(inputs, deps, viewModel, id, newPath) },
        onUpdateGraphDescription = { id, description ->
            if (!graphManager.updateGraphDescription(GraphId(id), description)) {
                viewModel.sendSnackbar("Failed to update graph description")
            }
        },
        onRenameGraph = { id, newName ->
            if (!graphManager.renameGraph(GraphId(id), newName)) {
                viewModel.sendSnackbar("Failed to rename graph")
            }
        },
        onUpdateWikiSubdir = { id, newSubdir ->
            inputs.scope.launch {
                graphManager.updateWikiSubdir(GraphId(id), newSubdir)
            }
        },
        onRelinkHostDirectory = { id -> relinkHostDirectory(inputs, deps, viewModel, id) },
        supportsHostDirectoryLink = fileSystem.supportsHostDirectoryLink,
        storageLocationResolver = inputs.storageLocationResolver,
        // Same StorageLocation.SafFolder(graphId, treeUri) construction as the new-graph
        // UnifiedLocationPicker's onBrowseRequest — the established idiom for "wrap whatever
        // fileSystem.pickDirectoryAsync() returned as a storage location", not a new one here.
        onBrowseRequestForMove = { graphId ->
            fileSystem.pickDirectoryAsync()?.let { path ->
                val expanded = fileSystem.expandTilde(path)
                val treeUri = expanded.removePrefix("saf://").substringBefore("/")
                StorageLocation.SafFolder(graphId, treeUri)
            }
        },
        onBrowseClickForMove = {
            // Must run synchronously here, not inside the suspend lambda above — same
            // transient-user-activation constraint as every other showDirectoryPicker()-backed
            // click in this file (see FolderSyncSettings's onBrowseClickForMove wiring below).
            fileSystem.requestDirectoryPickerNow()
        },
        moveStorageLocationPlatformCapabilities = fileSystem.supportsNativeDirectoryPicker,
        // Epic 4.2 (Story 4.2.1, ADR-003): Link is a real continuous mirror only for git-cloned
        // graphs (the existing shadow-worktree write-back mechanism, Android-only). No
        // gitRepository on this platform (e.g. Web) falls back to `true` — unaffected by this
        // gate, matching ADR-003's Web-ships-Link-for-both-graph-types decision (Epic 4.1, wired
        // separately in FolderSyncSettings.kt).
        isGraphGitCloned = { path -> inputs.gitRepository?.isGitRepo(path) ?: true },
        onStorageLocationChoose = inputs.onStorageLocationChoose,
        onCollapse = { viewModel.toggleSidebar() },
        syncState = inputs.syncState,
        gitLastSyncAt = inputs.gitLastSyncAt,
        firstSyncReviewPending = inputs.firstSyncReviewPending,
        onBranchRepair = { viewModel.openBranchRepair() },
        onReviewFirstSync = { viewModel.openFirstSyncReview() },
        onSyncClick = {
            if (inputs.syncState is dev.stapler.stelekit.git.model.SyncState.CredentialVaultLocked) {
                // Vault is locked — lock() re-shows the unlock screen
                inputs.vaultManager?.lock()
            } else {
                viewModel.triggerSync()
            }
        },
        onGitSetup = { viewModel.openGitSetup() },
        isGitConfigured = appState.gitConfig != null,
        onAuthError = { viewModel.openGitSetupForCredentials() },
        onCloneGraph = { viewModel.openGitSetupForClone() },
        gitSyncedGraphId = if (appState.gitConfig != null) inputs.activeGraphId?.value else null,
        onNewSectionJournalEntry = if (inputs.activeSectionIds?.size == 1) {
            { viewModel.newSectionJournalForToday(inputs.activeSectionIds[0]) }
        } else null,
        sectionManifest = appState.currentManifest,
        defaultSection = appState.defaultSection.toDbString(),
        onSectionIndicatorClick = { viewModel.setSectionQuickToggleVisible(true) },
    )
}

/**
 * Story 2.2.1: show UnifiedLocationPicker instead of jumping straight to the SAF folder picker,
 * so "App storage" is choosable with zero SAF grant, when the platform supports app-owned
 * storage. On a platform with a native directory picker, calls it synchronously (must run before
 * `scope.launch`, so the browser's `showDirectoryPicker()` runs within this click's transient
 * user activation — see `requestDirectoryPickerNow`'s doc). Otherwise (no "existing folder"
 * concept — e.g. OPFS-only web) collapses to the same "create a named graph" flow as "New
 * graph…" (previously its own name-only AddGraphDialog duplicating this minus the description
 * field).
 */
private fun handleAddGraph(deps: GraphContentDeps, viewModel: StelekitViewModel, inputs: GraphContentLeftSidebarInputs) {
    val fileSystem = deps.fileSystem
    when (addGraphFlowMode(fileSystem)) {
        AddGraphFlowMode.ShowLocationPicker -> inputs.onShowNewGraphLocationPicker(fileSystem.newAppOwnedGraphPath())
        AddGraphFlowMode.ImmediateNativePicker -> {
            fileSystem.requestDirectoryPickerNow()
            inputs.scope.launch { pickAndAddGraph(deps, viewModel) }
        }
        AddGraphFlowMode.ShowNameDialog -> inputs.onStartNewGraphFlow()
    }
}

private suspend fun pickAndAddGraph(deps: GraphContentDeps, viewModel: StelekitViewModel) {
    val fileSystem = deps.fileSystem
    val selectedPath = fileSystem.pickDirectoryAsync()
    if (selectedPath != null) {
        deps.graphManager.switchGraph(deps.graphManager.addGraph(selectedPath))
    } else {
        fileSystem.consumeLastPickerError()?.let { viewModel.sendSnackbar("Couldn't open folder picker: $it") }
    }
}

private fun updateGraphPath(
    inputs: GraphContentLeftSidebarInputs,
    deps: GraphContentDeps,
    viewModel: StelekitViewModel,
    id: String,
    newPath: String,
) {
    inputs.scope.launch {
        val message = when (deps.graphManager.updateGraphPath(GraphId(id), newPath)) {
            is dev.stapler.stelekit.db.UpdateGraphPathResult.Success -> "Graph moved to $newPath"
            dev.stapler.stelekit.db.UpdateGraphPathResult.GraphNotFound -> "Graph not found"
            dev.stapler.stelekit.db.UpdateGraphPathResult.DemoGraphImmutable -> "The demo graph's path cannot be changed"
            dev.stapler.stelekit.db.UpdateGraphPathResult.PathNotFound -> "Folder \"$newPath\" does not exist"
            dev.stapler.stelekit.db.UpdateGraphPathResult.PathUnchanged -> null
            dev.stapler.stelekit.db.UpdateGraphPathResult.AlreadyTracked -> "That folder is already tracked as another graph"
            dev.stapler.stelekit.db.UpdateGraphPathResult.DatabaseMoveFailed -> "Failed to move the graph's database — check file permissions"
        }
        message?.let { viewModel.sendSnackbar(it) }
    }
}

private fun relinkHostDirectory(inputs: GraphContentLeftSidebarInputs, deps: GraphContentDeps, viewModel: StelekitViewModel, id: String) {
    // Must call synchronously here, before scope.launch — same transient-user-activation
    // constraint as onAddGraph above.
    deps.fileSystem.requestDirectoryPickerNow()
    inputs.scope.launch {
        val graph = deps.graphManager.graphRegistry.value.graphs.find { it.id.value == id } ?: return@launch
        val dirName = deps.fileSystem.relinkHostDirectoryAsync(graph.path)
        if (dirName != null) {
            deps.graphManager.updateHostDirName(GraphId(id), dirName)
            viewModel.sendSnackbar("Linked to folder \"$dirName\"")
        } else {
            deps.fileSystem.consumeLastPickerError()?.let { viewModel.sendSnackbar("Couldn't open folder picker: $it") }
        }
    }
}
