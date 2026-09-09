// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.performance.DebugBuildConfig
import dev.stapler.stelekit.performance.FrameMetric
import dev.stapler.stelekit.performance.DebugMenuState
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.ui.screens.git.ConflictResolutionScreen
import dev.stapler.stelekit.git.GitHubDeviceFlowClient
import dev.stapler.stelekit.ui.screens.git.GitSetupScreen
import dev.stapler.stelekit.ui.screens.git.JournalMergeReviewScreen
import kotlinx.coroutines.flow.StateFlow
import dev.stapler.stelekit.ui.components.CommandPalette
import dev.stapler.stelekit.ui.components.DebugMenuOverlay
import dev.stapler.stelekit.ui.components.DiskConflictDialog
import dev.stapler.stelekit.ui.screens.DiskConflictFullScreen
import dev.stapler.stelekit.ui.components.NotificationOverlay
import dev.stapler.stelekit.ui.components.PlatformFrameTimeOverlay
import dev.stapler.stelekit.ui.components.RenamePageDialog
import dev.stapler.stelekit.ui.components.SearchDialog
import dev.stapler.stelekit.ui.components.ShareDialog
import dev.stapler.stelekit.db.isLibsqlDriverSupported
import dev.stapler.stelekit.sections.SectionState
import dev.stapler.stelekit.ui.components.SectionPickerDialog
import dev.stapler.stelekit.ui.components.SectionQuickTogglePanel
import dev.stapler.stelekit.ui.components.settings.SettingsCategory
import dev.stapler.stelekit.ui.components.settings.SettingsDialog
import dev.stapler.stelekit.ui.onboarding.DeviceSetupWizard
import dev.stapler.stelekit.ui.screens.SearchViewModel

/**
 * All overlay dialogs for the graph content area, composed as a single layer.
 * Extracted from GraphContent so dialog additions don't modify the layout tree.
 * See ADR-001.
 *
 * [appState], [viewModel], [searchViewModel], [notificationManager], [fileSystem], and
 * [frameMetric] are read by most of the dialogs below, so they stay direct parameters.
 * Everything else — one feature's worth of settings/callbacks per dialog — is grouped in [deps]
 * (Parameter Object pattern; see [GraphDialogLayerDeps] for the grouping rationale).
 */
@Composable
internal fun GraphDialogLayer(
    appState: AppState,
    searchViewModel: SearchViewModel,
    viewModel: StelekitViewModel,
    notificationManager: NotificationManager,
    fileSystem: FileSystem,
    frameMetric: StateFlow<FrameMetric>,
    deps: GraphDialogLayerDeps = GraphDialogLayerDeps(),
) {
    val scope = rememberCoroutineScope()

    CommandPaletteHost(appState, viewModel)
    SearchDialogHost(appState, searchViewModel, viewModel, deps.loadPageBlocks)
    SettingsDialogHost(appState, viewModel, fileSystem, deps.settings)
    GitSetupDialogHost(appState, viewModel, fileSystem, deps.gitSync)
    ConflictResolutionDialogHost(appState, viewModel, deps.gitSync)
    JournalMergeReviewHost(appState, viewModel)
    LlmSuggestionReviewHost(appState, viewModel)
    DiskConflictHost(appState, viewModel)
    RenamePageDialogHost(appState, viewModel)
    ShareDialogHost(appState, viewModel, deps.share)
    DeviceSetupWizardHost(appState, viewModel)
    SectionDialogsHost(appState, viewModel)

    NotificationOverlay(
        notificationManager = notificationManager,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
    )

    // Frame-time debug overlay — shown in top corner when enabled, regardless of dialog state.
    PlatformFrameTimeOverlay(isEnabled = deps.debugState.isFrameOverlayEnabled, frameMetric = frameMetric)

    DebugMenuHost(appState, viewModel, notificationManager, fileSystem, deps.debugState, deps.onDebugStateChange, scope)
}

@Composable
private fun CommandPaletteHost(appState: AppState, viewModel: StelekitViewModel) {
    CommandPalette(
        visible = appState.commandPaletteVisible,
        commands = appState.commands,
        onDismiss = { viewModel.setCommandPaletteVisible(false) }
    )
}

@Composable
private fun SearchDialogHost(
    appState: AppState,
    searchViewModel: SearchViewModel,
    viewModel: StelekitViewModel,
    loadPageBlocks: (String) -> kotlinx.coroutines.flow.Flow<Either<DomainError, List<dev.stapler.stelekit.model.Block>>>,
) {
    val indexingProgress by viewModel.indexingProgress.collectAsState()
    SearchDialog(
        visible = appState.searchDialogVisible,
        viewModel = searchViewModel,
        onDismiss = { viewModel.setSearchDialogVisible(false) },
        onNavigateToPage = { viewModel.navigateToPageByUuid(it) },
        onNavigateToBlock = { viewModel.navigateToBlock(it) },
        onCreatePage = { viewModel.navigateToPageByName(it) },
        initialQuery = appState.searchDialogInitialQuery,
        isIndexing = indexingProgress is IndexingState.InProgress,
        loadPageBlocks = loadPageBlocks
    )
}

@Composable
private fun SettingsDialogHost(
    appState: AppState,
    viewModel: StelekitViewModel,
    fileSystem: FileSystem,
    settings: SettingsDialogDeps,
) {
    SettingsDialog(
        visible = appState.settingsVisible || appState.llmProviderSettingsVisible,
        onDismiss = {
            viewModel.setSettingsVisible(false)
            viewModel.dismissLlmProviderSettings()
        },
        currentTheme = appState.themeMode,
        onThemeChange = { viewModel.setThemeMode(it) },
        currentLanguage = appState.language,
        onLanguageChange = { viewModel.setLanguage(it) },
        onReindex = {
            viewModel.triggerReindex()
            viewModel.setSettingsVisible(false)
        },
        isLeftHanded = appState.isLeftHanded,
        onLeftHandedChange = { viewModel.setLeftHanded(it) },
        voiceSettings = settings.voiceSettings,
        llmCredentialStore = settings.llmCredentialStore,
        llmProviderRegistry = settings.llmProviderRegistry,
        llmSettings = settings.llmSettings,
        initialCategory = if (appState.llmProviderSettingsVisible) SettingsCategory.LLM_PROVIDERS else SettingsCategory.GENERAL,
        onLlmCredentialsChange = settings.onLlmCredentialsChange,
        onRebuildVoicePipeline = settings.onRebuildVoicePipeline,
        deviceSttAvailable = settings.deviceSttAvailable,
        deviceLlmAvailable = settings.deviceLlmAvailable,
        isParanoidMode = settings.isParanoidMode,
        isVaultUnlocked = settings.isVaultUnlocked,
        onCreateVault = settings.onCreateVault,
        onAddKeyslot = settings.onAddKeyslot,
        onRemoveKeyslot = settings.onRemoveKeyslot,
        onLockVault = settings.onLockVault,
        onListActiveSlots = settings.onListActiveSlots,
        isGoogleAuthenticated = settings.isGoogleAuthenticated,
        googleConnectedEmail = settings.googleConnectedEmail,
        isGoogleConnecting = settings.isGoogleConnecting,
        googleAuthError = settings.googleAuthError,
        onConnectGoogle = settings.onConnectGoogle,
        onDisconnectGoogle = settings.onDisconnectGoogle,
        tagSettings = settings.tagSettings,
        hasLlmKey = settings.hasLlmKey,
        isLibsqlDriverEnabled = appState.isLibsqlDriverEnabled,
        onLibsqlDriverToggle = if (isLibsqlDriverSupported) { { viewModel.setLibsqlDriverEnabled(it) } } else null,
        sectionManifest = appState.currentManifest,
        sectionStates = appState.currentSectionStates,
        onCreateSection = { id, name, color, pagePfx, journalPfx ->
            viewModel.createSection(id, name, color, pagePfx, journalPfx)
        },
        onRenameSection = { id, newName -> viewModel.renameSection(id, newName) },
        onDeleteSection = { id -> viewModel.deleteSection(id) },
        onToggleSectionState = { id, state -> viewModel.setSectionState(id, state) },
        hostAccessState = settings.hostAccessState,
        supportsNativeDirectoryPicker = fileSystem.supportsNativeDirectoryPicker,
        onConnectHostDirectory = settings.onConnectHostDirectory,
    )
}

@Composable
private fun GitSetupDialogHost(
    appState: AppState,
    viewModel: StelekitViewModel,
    fileSystem: FileSystem,
    gitSync: GitSyncDeps,
) {
    // key(gitSetupVisible) resets composition — and the remember inside — each time the dialog
    // opens, giving GitSetupScreen a fresh HttpClient. GitSetupScreen.DisposableEffect closes
    // the client on dismiss, so we never hand a closed client back in on second open.
    key(appState.gitSetupVisible) {
        val gitSyncService = gitSync.gitSyncService
        val gitRepository = gitSync.gitRepository
        val gitConfigRepository = gitSync.gitConfigRepository
        val canShowGitSetup = appState.gitSetupVisible &&
            gitSyncService != null && gitRepository != null && gitConfigRepository != null
        if (!canShowGitSetup) return@key

        val deviceFlowClient = remember { GitHubDeviceFlowClient.withDefaultClient() }
        // Previously always null, discarding a graph's saved GitConfig every time the wizard
        // reopened — re-editing sync settings silently reset auth type, branch, and poll
        // interval to their defaults. Loaded once per open (keyed on activeGraphId, inside the
        // already gitSetupVisible-keyed composition) rather than reactively, matching this
        // dialog's existing "fresh state per open" pattern (see the class doc above).
        val (existingConfigLoaded, existingConfig) = rememberExistingGitConfig(gitSync.activeGraphId, gitConfigRepository)
        if (!existingConfigLoaded) return@key

        GitSetupScreen(
            graphId = gitSync.activeGraphId ?: "",
            gitRepository = gitRepository,
            gitConfigRepository = gitConfigRepository,
            gitSyncService = gitSyncService,
            fileSystem = fileSystem,
            onDismiss = { viewModel.dismissGitSetup() },
            onSave = {
                viewModel.sendSnackbar("Git sync configured")
                viewModel.dismissGitSetup()
            },
            onCloneAndAdd = gitSync.onCloneAndAdd,
            graphPath = gitSync.graphPath,
            onCloneComplete = gitSync.onCloneComplete,
            initialStep = appState.gitSetupInitialStep,
            initialUseExistingClone = !appState.gitSetupOpenForClone,
            existingConfig = existingConfig,
            detectedRepoRoot = gitSync.detectedRepoRoot,
            detectedWikiSubdir = gitSync.detectedWikiSubdir,
            deviceFlowClient = deviceFlowClient,
        )
    }
}

/**
 * Loads [activeGraphId]'s saved [dev.stapler.stelekit.git.model.GitConfig], if any, keyed so a
 * different graph (or the same graph reopened) starts a fresh load. Returns `loaded = false`
 * until that load completes — distinct from "loaded, and there was no config" (`null`) — so
 * [GitSetupDialogHost] doesn't render the wizard with defaults for one frame before the real
 * config arrives.
 */
@Composable
private fun rememberExistingGitConfig(
    activeGraphId: String?,
    gitConfigRepository: GitConfigRepository,
): Pair<Boolean, dev.stapler.stelekit.git.model.GitConfig?> {
    var existingConfig by remember(activeGraphId) {
        mutableStateOf<dev.stapler.stelekit.git.model.GitConfig?>(null)
    }
    var loaded by remember(activeGraphId) { mutableStateOf(false) }
    LaunchedEffect(activeGraphId) {
        existingConfig = activeGraphId?.let { id -> gitConfigRepository.getConfig(id).getOrNull() }
        loaded = true
    }
    return loaded to existingConfig
}

@Composable
private fun ConflictResolutionDialogHost(appState: AppState, viewModel: StelekitViewModel, gitSync: GitSyncDeps) {
    if (appState.conflictResolutionVisible) {
        val liveSyncState by viewModel.syncState.collectAsState()
        val conflictFiles = if (liveSyncState is SyncState.ConflictPending)
            (liveSyncState as SyncState.ConflictPending).conflicts
        else emptyList()

        ConflictResolutionScreen(
            conflicts = conflictFiles,
            onResolve = { sideResolutions, hunkResolutions ->
                val id = gitSync.activeGraphId ?: return@ConflictResolutionScreen arrow.core.Either.Left(
                    dev.stapler.stelekit.error.DomainError.GitError.CommitFailed("No active graph")
                )
                gitSync.gitSyncService?.resolveConflicts(id, conflictFiles, sideResolutions, hunkResolutions)
                    ?: arrow.core.Either.Left(
                        dev.stapler.stelekit.error.DomainError.GitError.CommitFailed("Git sync not available")
                    )
            },
            onAbortMerge = if (gitSync.gitSyncService != null && gitSync.activeGraphId != null) {
                {
                    val id = gitSync.activeGraphId
                    gitSync.gitSyncService.abortActiveMerge(id)
                    viewModel.dismissConflictResolution()
                }
            } else null,
            onDismiss = { viewModel.dismissConflictResolution() },
        )
    }
}

@Composable
private fun JournalMergeReviewHost(appState: AppState, viewModel: StelekitViewModel) {
    if (appState.journalMergeReviewVisible) {
        val liveSyncState by viewModel.syncState.collectAsState()
        val proposal = (liveSyncState as? SyncState.JournalMergeReady)?.proposal
        if (proposal != null) {
            JournalMergeReviewScreen(
                proposal = proposal,
                onAccept = { mergedContent -> viewModel.acceptJournalMerge(mergedContent) },
                onFallbackToManual = { viewModel.abortJournalMerge() },
                onDismiss = { viewModel.abortJournalMerge() },
            )
        }
    }
}

@Composable
private fun LlmSuggestionReviewHost(appState: AppState, viewModel: StelekitViewModel) {
    if (appState.llmSuggestionReviewVisible) {
        val liveSuggestions by viewModel.llmSuggestions.collectAsState()
        val currentGraphId = appState.currentGraphId
        val pendingForGraph = if (currentGraphId != null) {
            liveSuggestions.values.filter { it.graphId == currentGraphId }
        } else {
            emptyList()
        }
        dev.stapler.stelekit.ui.screens.llm.LlmSuggestionReviewScreen(
            pending = pendingForGraph,
            onAccept = { id -> viewModel.acceptLlmSuggestion(id) },
            onReject = { id -> viewModel.rejectLlmSuggestion(id) },
            onAcceptAll = { pendingForGraph.forEach { viewModel.acceptLlmSuggestion(it.id) } },
            onRejectAll = { pendingForGraph.forEach { viewModel.rejectLlmSuggestion(it.id) } },
            onDismiss = { viewModel.dismissLlmSuggestionReview() },
        )
    }
}

@Composable
private fun DiskConflictHost(appState: AppState, viewModel: StelekitViewModel) {
    if (!appState.diskConflictViewFullVisible) {
        appState.diskConflict?.let { conflict ->
            DiskConflictDialog(
                conflict = conflict,
                onKeepLocal = { viewModel.keepLocalChanges() },
                onUseDisk = { viewModel.acceptDiskVersion() },
                onSaveAsNew = { viewModel.saveAsNewBlock() },
                onManualResolve = { viewModel.manualResolve() },
                onViewFull = { viewModel.showDiskConflictFullView() },
            )
        }
    }

    if (appState.diskConflictViewFullVisible) {
        appState.diskConflict?.let { conflict ->
            DiskConflictFullScreen(
                localContent = conflict.localContent,
                diskContent = conflict.diskBlockContent ?: conflict.diskContent,
                onDismiss = { viewModel.hideDiskConflictFullView() },
            )
        }
    }
}

@Composable
private fun RenamePageDialogHost(appState: AppState, viewModel: StelekitViewModel) {
    appState.renameDialogPage?.let { page ->
        RenamePageDialog(
            page = page,
            busy = appState.renameDialogBusy,
            error = appState.renameDialogError,
            onConfirm = { newName -> viewModel.renamePage(page, newName) },
            onDismiss = { viewModel.dismissRenameDialog() }
        )
    }
}

@Composable
private fun ShareDialogHost(appState: AppState, viewModel: StelekitViewModel, share: ShareDialogDeps) {
    // ShareDialog — shown when appState.shareDialogVisible and a ShareProvider is available.
    if (share.shareProvider != null) {
        ShareDialog(
            appState = appState,
            viewModel = viewModel,
            page = share.currentPage,
            blocks = share.currentBlocks,
            selectedBlockUuids = share.selectedBlockUuids,
            shareProvider = share.shareProvider,
            driveClient = share.driveClient,
            googleAuthManager = share.shareGoogleAuthManager,
            onDismiss = { viewModel.hideShareDialog() },
        )
    }
}

@Composable
private fun DeviceSetupWizardHost(appState: AppState, viewModel: StelekitViewModel) {
    // Device setup wizard — shown once when a graph with sections is first opened
    if (appState.deviceSetupWizardVisible && appState.currentManifest != null) {
        DeviceSetupWizard(
            manifest = appState.currentManifest,
            onComplete = { defaultSection, sectionStates ->
                viewModel.completeDeviceSetup(defaultSection, sectionStates)
            },
            onDismiss = { viewModel.completeDeviceSetup("", emptyMap()) },
        )
    }
}

@Composable
private fun SectionDialogsHost(appState: AppState, viewModel: StelekitViewModel) {
    val manifest = appState.currentManifest ?: return

    // Section picker dialog — opened when the SectionBadge is tapped on a non-journal page
    if (appState.sectionPickerVisible) {
        val pickerPage = appState.sectionPickerPage
        // Only ACTIVE sections are offered as move targets — hidden/removed sections are not
        // reachable on this device so moving a page there would make it disappear immediately.
        val activeSections = remember(manifest, appState.currentSectionStates) {
            manifest.sections.filter { section ->
                (appState.currentSectionStates[section.id] ?: SectionState.ACTIVE) == SectionState.ACTIVE
            }
        }
        SectionPickerDialog(
            sections = activeSections,
            currentSectionId = pickerPage?.sectionId?.toDbString() ?: "",
            onSelect = { sectionId ->
                pickerPage?.let { viewModel.movePageToSection(it, sectionId) }
            },
            onDismiss = { viewModel.dismissSectionPicker() },
        )
    }

    // Section quick-toggle panel
    if (appState.sectionQuickToggleVisible) {
        SectionQuickTogglePanel(
            manifest = manifest,
            sectionStates = appState.currentSectionStates,
            onToggleSection = { id, state -> viewModel.setSectionState(id, state) },
            onManageSections = {
                viewModel.setSectionQuickToggleVisible(false)
                viewModel.setSettingsVisible(true)
            },
            onDismiss = { viewModel.setSectionQuickToggleVisible(false) },
        )
    }
}

@Composable
private fun DebugMenuHost(
    appState: AppState,
    viewModel: StelekitViewModel,
    notificationManager: NotificationManager,
    fileSystem: FileSystem,
    debugState: DebugMenuState,
    onDebugStateChange: (DebugMenuState) -> Unit,
    scope: CoroutineScope,
) {
    if (!appState.isDebugMenuVisible || !DebugBuildConfig.isDebugBuild) return

    DebugMenuOverlay(
        state = debugState,
        onStateChange = { newState -> onDebugStateChange(newState) },
        onExportBugReport = {
            scope.launch { exportAndSaveBugReport(viewModel, notificationManager, fileSystem) }
        },
        onDismiss = { viewModel.dismissDebugMenu() }
    )
}

private suspend fun exportAndSaveBugReport(
    viewModel: StelekitViewModel,
    notificationManager: NotificationManager,
    fileSystem: FileSystem,
) {
    val json = viewModel.exportBugReport()
    if (json == null) {
        notificationManager.show("Bug report unavailable — OTel not initialized")
        return
    }
    val path = fileSystem.pickSaveFileAsync("stelekit-bug-report.json", "application/json")
    when {
        path == null -> { /* user cancelled */ }
        fileSystem.writeFile(path, json) ->
            notificationManager.show("Bug report saved to ${fileSystem.displayNameForPath(path)}")
        else ->
            notificationManager.show("Failed to save bug report. Check storage permissions.")
    }
}
