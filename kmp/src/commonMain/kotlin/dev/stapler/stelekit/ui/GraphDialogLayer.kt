// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.export.ExportService
import dev.stapler.stelekit.export.ShareProvider
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.SectionId
import dev.stapler.stelekit.performance.DebugBuildConfig
import dev.stapler.stelekit.performance.FrameMetric
import dev.stapler.stelekit.performance.DebugMenuState
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.google.DriveUploader
import dev.stapler.stelekit.platform.google.GoogleAuthManager
import dev.stapler.stelekit.ui.screens.git.ConflictResolutionScreen
import dev.stapler.stelekit.git.GitHubDeviceFlowClient
import dev.stapler.stelekit.ui.screens.git.GitSetupScreen
import dev.stapler.stelekit.ui.screens.git.JournalMergeReviewScreen
import dev.stapler.stelekit.vault.VaultError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import dev.stapler.stelekit.ui.components.CommandPalette
import dev.stapler.stelekit.ui.components.DebugMenuOverlay
import dev.stapler.stelekit.ui.components.DiskConflictDialog
import dev.stapler.stelekit.ui.components.NotificationOverlay
import dev.stapler.stelekit.ui.components.PlatformFrameTimeOverlay
import dev.stapler.stelekit.ui.components.RenamePageDialog
import dev.stapler.stelekit.ui.components.SearchDialog
import dev.stapler.stelekit.ui.components.ShareDialog
import dev.stapler.stelekit.db.isLibsqlDriverSupported
import dev.stapler.stelekit.tags.TagSettings
import dev.stapler.stelekit.sections.SectionState
import dev.stapler.stelekit.ui.components.SectionPickerDialog
import dev.stapler.stelekit.ui.components.SectionQuickTogglePanel
import dev.stapler.stelekit.ui.components.settings.SettingsCategory
import dev.stapler.stelekit.ui.components.settings.SettingsDialog
import dev.stapler.stelekit.ui.onboarding.DeviceSetupWizard
import dev.stapler.stelekit.ui.screens.SearchViewModel
import dev.stapler.stelekit.voice.VoiceSettings

/**
 * All overlay dialogs for the graph content area, composed as a single layer.
 * Extracted from GraphContent so dialog additions don't modify the layout tree.
 * See ADR-001.
 */
@Composable
internal fun GraphDialogLayer(
    appState: AppState,
    searchViewModel: SearchViewModel,
    viewModel: StelekitViewModel,
    notificationManager: NotificationManager,
    fileSystem: FileSystem,
    frameMetric: StateFlow<FrameMetric>,
    voiceSettings: VoiceSettings? = null,
    llmCredentialStore: LlmCredentialStore? = null,
    llmProviderRegistry: LlmProviderRegistry? = null,
    llmSettings: LlmSettings? = null,
    onLlmCredentialsChange: () -> Unit = {},
    onRebuildVoicePipeline: (() -> Unit)? = null,
    deviceSttAvailable: Boolean = false,
    deviceLlmAvailable: Boolean = false,
    debugState: DebugMenuState = DebugMenuState(),
    loadPageBlocks: (String) -> Flow<Either<DomainError, List<Block>>> = { flowOf(Either.Right(emptyList())) },
    onDebugStateChange: (DebugMenuState) -> Unit = {},
    isParanoidMode: Boolean = false,
    isVaultUnlocked: Boolean = false,
    onCreateVault: (suspend (CharArray) -> Either<VaultError, Unit>)? = null,
    onAddKeyslot: (suspend (CharArray) -> Either<VaultError, Unit>)? = null,
    onRemoveKeyslot: (suspend (Int) -> Either<VaultError, Unit>)? = null,
    onLockVault: (() -> Unit)? = null,
    onListActiveSlots: (suspend () -> List<Int>)? = null,
    isGoogleAuthenticated: Boolean = false,
    googleConnectedEmail: String? = null,
    isGoogleConnecting: Boolean = false,
    googleAuthError: String? = null,
    onConnectGoogle: (() -> Unit)? = null,
    onDisconnectGoogle: (() -> Unit)? = null,
    gitSyncService: GitSyncService? = null,
    gitRepository: GitRepository? = null,
    gitConfigRepository: GitConfigRepository? = null,
    activeGraphId: String? = null,
    onCloneAndAdd: (suspend (url: String, localPath: String, auth: dev.stapler.stelekit.git.GitAuth, onProgress: (String) -> Unit) -> Either<DomainError.GitError, String>)? = null,
    graphPath: String = "",
    onCloneComplete: ((String) -> Unit)? = null,
    onAuthError: (() -> Unit)? = null,
    shareProvider: ShareProvider? = null,
    exportService: ExportService? = null,
    driveClient: DriveUploader? = null,
    shareGoogleAuthManager: GoogleAuthManager? = null,
    currentPage: Page? = null,
    currentBlocks: List<Block> = emptyList(),
    selectedBlockUuids: Set<String> = emptySet(),
    tagSettings: TagSettings? = null,
    hasLlmKey: Boolean = false,
) {
    val scope = rememberCoroutineScope()

    CommandPalette(
        visible = appState.commandPaletteVisible,
        commands = appState.commands,
        onDismiss = { viewModel.setCommandPaletteVisible(false) }
    )

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
        voiceSettings = voiceSettings,
        llmCredentialStore = llmCredentialStore,
        llmProviderRegistry = llmProviderRegistry,
        llmSettings = llmSettings,
        initialCategory = if (appState.llmProviderSettingsVisible) SettingsCategory.LLM_PROVIDERS else SettingsCategory.GENERAL,
        onLlmCredentialsChange = onLlmCredentialsChange,
        onRebuildVoicePipeline = onRebuildVoicePipeline,
        deviceSttAvailable = deviceSttAvailable,
        deviceLlmAvailable = deviceLlmAvailable,
        isParanoidMode = isParanoidMode,
        isVaultUnlocked = isVaultUnlocked,
        onCreateVault = onCreateVault,
        onAddKeyslot = onAddKeyslot,
        onRemoveKeyslot = onRemoveKeyslot,
        onLockVault = onLockVault,
        onListActiveSlots = onListActiveSlots,
        isGoogleAuthenticated = isGoogleAuthenticated,
        googleConnectedEmail = googleConnectedEmail,
        isGoogleConnecting = isGoogleConnecting,
        googleAuthError = googleAuthError,
        onConnectGoogle = onConnectGoogle,
        onDisconnectGoogle = onDisconnectGoogle,
        tagSettings = tagSettings,
        hasLlmKey = hasLlmKey,
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
    )

    // key(gitSetupVisible) resets composition — and the remember inside — each time the dialog
    // opens, giving GitSetupScreen a fresh HttpClient. GitSetupScreen.DisposableEffect closes
    // the client on dismiss, so we never hand a closed client back in on second open.
    key(appState.gitSetupVisible) {
        val canShowGitSetup = appState.gitSetupVisible &&
            gitSyncService != null && gitRepository != null && gitConfigRepository != null
        if (canShowGitSetup) {
            val deviceFlowClient = remember { GitHubDeviceFlowClient.withDefaultClient() }
            GitSetupScreen(
                graphId = activeGraphId ?: "",
                gitRepository = gitRepository,
                gitConfigRepository = gitConfigRepository,
                gitSyncService = gitSyncService,
                fileSystem = fileSystem,
                onDismiss = { viewModel.dismissGitSetup() },
                onSave = { viewModel.dismissGitSetup() },
                onCloneAndAdd = onCloneAndAdd,
                graphPath = graphPath,
                onCloneComplete = onCloneComplete,
                initialStep = appState.gitSetupInitialStep,
                initialUseExistingClone = !appState.gitSetupOpenForClone,
                existingConfig = null,
                deviceFlowClient = deviceFlowClient,
            )
        }
    }

    if (appState.conflictResolutionVisible) {
        val liveSyncState by viewModel.syncState.collectAsState()
        val conflictFiles = if (liveSyncState is SyncState.ConflictPending)
            (liveSyncState as SyncState.ConflictPending).conflicts
        else emptyList()

        ConflictResolutionScreen(
            conflicts = conflictFiles,
            onResolve = { fileResolutions ->
                val id = activeGraphId ?: return@ConflictResolutionScreen arrow.core.Either.Left(
                    dev.stapler.stelekit.error.DomainError.GitError.CommitFailed("No active graph")
                )
                gitSyncService?.resolveConflictBySide(id, fileResolutions)
                    ?: arrow.core.Either.Left(
                        dev.stapler.stelekit.error.DomainError.GitError.CommitFailed("Git sync not available")
                    )
            },
            onAbortMerge = if (gitSyncService != null && activeGraphId != null) {
                {
                    val id = activeGraphId
                    gitSyncService.abortActiveMerge(id)
                    viewModel.dismissConflictResolution()
                }
            } else null,
            onDismiss = { viewModel.dismissConflictResolution() },
        )
    }

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

    appState.diskConflict?.let { conflict ->
        DiskConflictDialog(
            conflict = conflict,
            onKeepLocal = { viewModel.keepLocalChanges() },
            onUseDisk = { viewModel.acceptDiskVersion() },
            onSaveAsNew = { viewModel.saveAsNewBlock() },
            onManualResolve = { viewModel.manualResolve() }
        )
    }

    appState.renameDialogPage?.let { page ->
        RenamePageDialog(
            page = page,
            busy = appState.renameDialogBusy,
            error = appState.renameDialogError,
            onConfirm = { newName -> viewModel.renamePage(page, newName) },
            onDismiss = { viewModel.dismissRenameDialog() }
        )
    }

    // ShareDialog — shown when appState.shareDialogVisible and a ShareProvider is available.
    if (shareProvider != null) {
        ShareDialog(
            appState = appState,
            viewModel = viewModel,
            page = currentPage,
            blocks = currentBlocks,
            selectedBlockUuids = selectedBlockUuids,
            shareProvider = shareProvider,
            driveClient = driveClient,
            googleAuthManager = shareGoogleAuthManager,
            onDismiss = { viewModel.hideShareDialog() },
        )
    }

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

    // Section picker dialog — opened when the SectionBadge is tapped on a non-journal page
    val manifest = appState.currentManifest
    if (appState.sectionPickerVisible && manifest != null) {
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
    if (appState.sectionQuickToggleVisible && manifest != null) {
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

    NotificationOverlay(
        notificationManager = notificationManager,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
    )

    // Frame-time debug overlay — shown in top corner when enabled, regardless of dialog state.
    PlatformFrameTimeOverlay(isEnabled = debugState.isFrameOverlayEnabled, frameMetric = frameMetric)

    if (appState.isDebugMenuVisible && DebugBuildConfig.isDebugBuild) {
        DebugMenuOverlay(
            state = debugState,
            onStateChange = { newState -> onDebugStateChange(newState) },
            onExportBugReport = {
                val json = viewModel.exportBugReport()
                if (json == null) {
                    notificationManager.show("Bug report unavailable — OTel not initialized")
                } else {
                    scope.launch {
                        val path = fileSystem.pickSaveFileAsync("stelekit-bug-report.json", "application/json")
                        when {
                            path == null -> { /* user cancelled */ }
                            fileSystem.writeFile(path, json) ->
                                notificationManager.show("Bug report saved to ${fileSystem.displayNameForPath(path)}")
                            else ->
                                notificationManager.show("Failed to save bug report. Check storage permissions.")
                        }
                    }
                }
            },
            onDismiss = { viewModel.dismissDebugMenu() }
        )
    }

}
