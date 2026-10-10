// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.performance.DebugBuildConfig
import dev.stapler.stelekit.performance.DebugMenuState
import dev.stapler.stelekit.platform.*
import dev.stapler.stelekit.ui.components.*
import dev.stapler.stelekit.vault.VaultManager
import dev.stapler.stelekit.voice.VoiceCaptureState
import kotlinx.coroutines.CoroutineScope
import dev.stapler.stelekit.merge.ActiveDbPageSource
import dev.stapler.stelekit.ui.screens.copy.ConflictReviewViewModel
import dev.stapler.stelekit.ui.screens.copy.CopyFlowHost
import dev.stapler.stelekit.ui.screens.copy.CopyGraphBinding
import dev.stapler.stelekit.ui.screens.copy.LocalCopyFlow
import dev.stapler.stelekit.ui.screens.copy.conflictPagePersister
import kotlinx.coroutines.launch

/**
 * Parameter object for [GraphContentActiveShell] — the stacks/results [GraphContent] already
 * built (see its doc for why they stay there). [llmRegistryRefreshTokenState] and
 * [debugMenuStateState] are shared [MutableState] refs, not copies: don't rewrap either in a
 * fresh `remember { }` here, or writes stop reaching [GraphContent].
 */
internal class GraphContentActiveShellInputs(
    val scope: CoroutineScope,
    val graphContentLogger: Logger,
    val llmCredentialStore: LlmCredentialStore,
    val llmRegistryRefreshTokenState: MutableState<Int>,
    val llmSettings: LlmSettings,
    val llmProviderRegistry: LlmProviderRegistry,
    val showTagSuggestionOnDeviceNotice: Boolean,
    val activeGraphInfo: dev.stapler.stelekit.model.GraphInfo?,
    val activeGraphPath: String,
    val effectiveFileSystem: FileSystem,
    val isParanoidMode: Boolean,
    val vaultState: VaultState,
    val vaultManager: VaultManager?,
    val graphIoStack: GraphContentGraphIoStack,
    val gitSyncStack: GraphContentGitSyncStack,
    val viewModelStack: GraphContentViewModelStack,
    val storageMoveController: GraphContentStorageMoveController,
    val vaultActions: GraphContentVaultActions,
    val googleAuthState: GraphContentGoogleAuthState,
    val debugMenuStateState: MutableState<DebugMenuState>,
    val perfTelemetry: GraphContentPerformanceTelemetry,
    val supportingViewModels: GraphContentSupportingViewModels,
    val tagVoiceStack: GraphContentTagVoiceStack,
)

/**
 * The active graph shell: [MainLayout], [GraphDialogLayer], and the New-graph/storage-move
 * dialogs — split out of [GraphContent] purely for length/nesting (see ADR-001). If split again,
 * update [GraphContentDemoFileSystemWiringTest]'s Bazel filegroup/file list.
 */
@Composable
internal fun GraphContentActiveShell(
    deps: GraphContentDeps,
    viewModel: StelekitViewModel,
    inputs: GraphContentActiveShellInputs,
) {
    val repos = deps.repos
    val fileSystem = deps.fileSystem
    val platformSettings = deps.platformSettings
    val graphManager = deps.graphManager
    val notificationManager = deps.notificationManager
    val encryptionManager = deps.coreServices.encryptionManager
    val pluginHost = deps.coreServices.pluginHost
    val voicePipeline = deps.coreServices.voicePipeline
    val voiceSettings = deps.voiceConfig.voiceSettings
    val onRebuildVoicePipeline = deps.voiceConfig.onRebuildVoicePipeline
    val deviceSttAvailable = deps.voiceConfig.deviceSttAvailable
    val deviceLlmAvailable = deps.voiceConfig.deviceLlmAvailable
    val gitRepository = deps.platformIntegrations.gitRepository
    val gitCloneWorkerLauncher = deps.platformIntegrations.gitCloneWorkerLauncher
    val inFlightCloneTracker = remember { dev.stapler.stelekit.git.InFlightCloneTracker() }
    val hotkeyComboLabel = deps.hotkeyComboLabel
    val googleAuthManager = deps.platformIntegrations.googleAuthManager
    val storageLocationResolver = deps.platformIntegrations.storageLocationResolver
    val onReconnectHostDirectory = deps.webSyncDeps.onReconnectHostDirectory
    val onConnectHostDirectory = deps.webSyncDeps.onConnectHostDirectory
    val onUnlinkHostDirectory = deps.webSyncDeps.onUnlinkHostDirectory
    val copyFlow = deps.copyFlow

    val hostAccessStateFlow = deps.webSyncDeps.hostAccessStateFlow
    val hostWritePendingCountFlow = deps.webSyncDeps.hostWritePendingCountFlow
    val hostWriteStuckFlow = deps.webSyncDeps.hostWriteStuckFlow
    val hostAccessState = hostAccessStateFlow?.collectAsState()?.value ?: HostAccessState.NotApplicable
    val hostWritePendingCount = hostWritePendingCountFlow?.collectAsState()?.value ?: 0
    val hostWriteStuck = hostWriteStuckFlow?.collectAsState()?.value ?: false

    val scope = inputs.scope
    val graphContentLogger = inputs.graphContentLogger
    val llmCredentialStore = inputs.llmCredentialStore
    val llmProviderRegistry = inputs.llmProviderRegistry
    val llmSettings = inputs.llmSettings
    val llmRegistryRefreshTokenState = inputs.llmRegistryRefreshTokenState
    val showTagSuggestionOnDeviceNotice = inputs.showTagSuggestionOnDeviceNotice
    val activeGraphInfo = inputs.activeGraphInfo
    val activeGraphPath = inputs.activeGraphPath
    val effectiveFileSystem = inputs.effectiveFileSystem
    val isParanoidMode = inputs.isParanoidMode
    val vaultState = inputs.vaultState
    val vaultManager = inputs.vaultManager
    val graphIoStack = inputs.graphIoStack
    val gitConfigRepository = inputs.gitSyncStack.gitConfigRepository
    val gitSyncService = inputs.gitSyncStack.gitSyncService
    val viewModelStack = inputs.viewModelStack
    val blockStateManager = viewModelStack.blockStateManager
    val exportService = viewModelStack.exportService
    val shareProvider = viewModelStack.shareProvider
    val storageMoveController = inputs.storageMoveController
    var storageMoveState by storageMoveController.stateState
    val storageMoveGraphName by storageMoveController.graphNameState
    val onStorageLocationChoose = storageMoveController.onStorageLocationChoose
    val onCreateVault = inputs.vaultActions.onCreateVault
    val onAddKeyslot = inputs.vaultActions.onAddKeyslot
    val onRemoveKeyslot = inputs.vaultActions.onRemoveKeyslot
    val onLockVault = inputs.vaultActions.onLockVault
    val onListActiveSlots = inputs.vaultActions.onListActiveSlots
    val isGoogleAuthenticated = inputs.googleAuthState.isAuthenticated
    val googleConnectedEmail = inputs.googleAuthState.connectedEmail
    val isGoogleConnecting = inputs.googleAuthState.isConnecting
    val googleAuthError = inputs.googleAuthState.authError
    val onConnectGoogle = inputs.googleAuthState.onConnect
    val onDisconnectGoogle = inputs.googleAuthState.onDisconnect
    val debugMenuStateState = inputs.debugMenuStateState
    val debugMenuState = debugMenuStateState.value
    val perfTelemetry = inputs.perfTelemetry
    val frameMetricState = perfTelemetry.frameMetricState
    val supportingViewModels = inputs.supportingViewModels
    val activeSectionIds = supportingViewModels.activeSectionIds
    val journalsViewModel = supportingViewModels.journalsViewModel
    val searchViewModel = supportingViewModels.searchViewModel
    val tagVoiceStack = inputs.tagVoiceStack
    val tagSettings = tagVoiceStack.tagSettings
    val hasTagSuggestionLlmProvider = tagVoiceStack.hasTagSuggestionLlmProvider
    val voiceCaptureViewModel = tagVoiceStack.voiceCaptureViewModel

    val appState by viewModel.uiState.collectAsState()
    val voiceCaptureState by voiceCaptureViewModel.state.collectAsState()
    val graphRegistry by graphManager.graphRegistry.collectAsState()
    val captureTargetSettings = remember(platformSettings) {
        dev.stapler.stelekit.capture.CaptureTargetSettings(platformSettings)
    }
    val activeGraphId = graphRegistry.activeGraphId
    val syncState by viewModel.syncState.collectAsState()
    val gitLastSyncAt by viewModel.gitLastSyncAt.collectAsState()

    val focusManager = LocalFocusManager.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
            .platformNavigationInput(
                onBack = { viewModel.goBack() },
                onForward = { viewModel.goForward() }
            )
            .onKeyEvent { keyEvent ->
                onGraphKeyEvent(
                    keyEvent = keyEvent,
                    handlers = GraphKeyEventHandlers(
                        onCommandPalette = { viewModel.setCommandPaletteVisible(true) },
                        onSearch = { viewModel.setSearchDialogVisible(true) },
                        onToggleSidebar = { viewModel.toggleSidebar() },
                        onToggleRightSidebar = { viewModel.toggleRightSidebar() },
                        onSettings = { viewModel.setSettingsVisible(true) },
                        onUndo = { journalsViewModel.undo() },
                        onRedo = { journalsViewModel.redo() },
                        onBack = { viewModel.goBack() },
                        onForward = { viewModel.goForward() },
                        onDebugMenu = { viewModel.showDebugMenu() },
                    ),
                )
            }
    ) {
        val windowSizeClass = windowSizeClassFor(maxWidth)
        val isMobile = windowSizeClass.isMobile
        val snackbarHostState = remember { SnackbarHostState() }
        val demoBannerDismissedState = remember { mutableStateOf(false) }
        // See GraphContentNewGraphFlow.kt for the "New graph…" flow's state/dialogs.
        val newGraphFlowController = rememberGraphContentNewGraphFlowController(deps)
        LaunchedEffect(Unit) {
            viewModel.snackbarEvents.collect { msg ->
                try {
                    snackbarHostState.showSnackbar(msg)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    graphContentLogger.warn("showSnackbar failed: $e")
                }
            }
        }
        // Epic 8 Story 8.2: one-time notice for existing installs whose tag
        // suggestion LLM tier was explicitly disabled by the migration guard above
        // (see showTagSuggestionOnDeviceNotice) so they know on-device support now
        // exists and how to turn it on.
        LaunchedEffect(showTagSuggestionOnDeviceNotice) {
            if (showTagSuggestionOnDeviceNotice) {
                try {
                    snackbarHostState.showSnackbar("On-device tag suggestions are now available — enable in Settings")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    graphContentLogger.warn("showSnackbar failed: $e")
                }
            }
        }

        CompositionLocalProvider(
            LocalWindowSizeClass provides windowSizeClass,
            LocalOpenSearchWithText provides { text -> viewModel.setSearchDialogVisible(true, text) },
            LocalFileSystem provides effectiveFileSystem,
            LocalCopyFlow provides copyFlow,
        ) {

        // Auto-manage sidebar based on layout: open on desktop, closed on mobile.
        // Fires once per isMobile change — handles fold/unfold transitions too.
        LaunchedEffect(isMobile) {
            if (isMobile && appState.sidebarExpanded) viewModel.toggleSidebar()
            else if (!isMobile && !appState.sidebarExpanded) viewModel.toggleSidebar()
        }

        fun closeSidebarIfMobile() {
            if (isMobile && appState.sidebarExpanded) viewModel.toggleSidebar()
        }

        // Android back gesture — priority order: last registered = highest priority.
        // goBack fires only when nothing else intercepts the event.
        PlatformBackHandler(enabled = appState.canGoBack) { viewModel.goBack() }
        // Dismiss dialogs before navigating back.
        PlatformBackHandler(enabled = appState.commandPaletteVisible) { viewModel.setCommandPaletteVisible(false) }
        PlatformBackHandler(enabled = appState.searchDialogVisible) { viewModel.setSearchDialogVisible(false) }
        PlatformBackHandler(enabled = appState.settingsVisible) { viewModel.setSettingsVisible(false) }
        // Cancel an in-progress voice capture before any navigation back.
        PlatformBackHandler(
            enabled = voiceCaptureState is VoiceCaptureState.Recording ||
                voiceCaptureState is VoiceCaptureState.Transcribing ||
                voiceCaptureState is VoiceCaptureState.Formatting,
        ) { voiceCaptureViewModel.cancel() }
        // Highest priority: close sidebar on mobile before anything else.
        PlatformBackHandler(enabled = isMobile && appState.sidebarExpanded) { viewModel.toggleSidebar() }

        // Collect linked references for the current page
        val linkedReferences by produceState(
            initialValue = emptyList<Block>(),
            key1 = appState.currentPage?.name,
            key2 = repos
        ) {
            val pageName = appState.currentPage?.name
            if (pageName == null || repos == null) {
                value = emptyList()
            } else {
                repos.blockRepository.getLinkedReferences(pageName)
                    .collect { result -> value = result.getOrNull() ?: emptyList() }
            }
        }

        MainLayout(
            sidebarExpanded = appState.sidebarExpanded,
            onSidebarDismiss = { viewModel.toggleSidebar() },
            topBar = {
                TopBar(
                    appState = appState,
                    platformSettings = platformSettings,
                    onSettingsClick = { viewModel.setSettingsVisible(true) },
                    onNewPageClick = { viewModel.setSearchDialogVisible(true) },
                    onNavigate = { viewModel.navigateTo(it) },
                    onThemeChange = { viewModel.setThemeMode(it) },
                    onLanguageChange = { viewModel.setLanguage(it) },
                    onResetOnboarding = { viewModel.setOnboardingCompleted(false) },
                    onToggleDebug = { viewModel.toggleDebugMode() },
                    onGoBack = { viewModel.goBack() },
                    onGoForward = { viewModel.goForward() },
                    onMenuToggle = { viewModel.toggleSidebar() },
                    onShareClick = { viewModel.showShareDialog() },
                    onShowDebugMenu = if (DebugBuildConfig.isDebugBuild) {{ viewModel.showDebugMenu() }} else null,
                )
            },
            leftSidebar = {
                GraphContentLeftSidebar(
                    deps,
                    viewModel,
                    GraphContentLeftSidebarInputs(
                        appState = appState,
                        isMobile = isMobile,
                        demoBannerDismissedState = demoBannerDismissedState,
                        hostAccessState = hostAccessState,
                        hostWritePendingCount = hostWritePendingCount,
                        hostWriteStuck = hostWriteStuck,
                        onReconnectHostDirectory = onReconnectHostDirectory,
                        onCopyPages = copyFlow?.let { flow -> { flow.open() } },
                        activeGraphInfo = activeGraphInfo,
                        graphRegistry = graphRegistry,
                        activeGraphId = activeGraphId,
                        activeGraphPath = activeGraphPath,
                        activeSectionIds = activeSectionIds,
                        vaultManager = vaultManager,
                        syncState = syncState,
                        gitLastSyncAt = gitLastSyncAt,
                        storageLocationResolver = storageLocationResolver,
                        gitRepository = gitRepository,
                        onStorageLocationChoose = onStorageLocationChoose,
                        onStartNewGraphFlow = newGraphFlowController.onStartNewGraphFlow,
                        onShowNewGraphLocationPicker = { appOwnedPath ->
                            newGraphFlowController.pendingNewGraphAppOwnedPathState.value = appOwnedPath
                            newGraphFlowController.showNewGraphLocationPickerState.value = true
                        },
                        scope = scope,
                        closeSidebarIfMobile = ::closeSidebarIfMobile,
                    ),
                )
            },
            rightSidebar = {
                RightSidebar(
                    expanded = appState.rightSidebarExpanded,
                    onClose = { viewModel.toggleRightSidebar() },
                    currentPageName = appState.currentPage?.name,
                    linkedReferences = linkedReferences,
                    onNavigateToPage = { pageUuid -> viewModel.navigateToPageByUuid(pageUuid) }
                )
            },
            content = {
                // See GraphContentMainArea.kt / GraphContentCameraCapture.kt: connectivity
                // banners, ScreenRouter wiring, and the camera-capture dialogs.
                GraphContentMainArea(
                    deps,
                    viewModel,
                    GraphContentMainAreaInputs(
                        appState = appState,
                        activeGraphId = activeGraphId,
                        graphRegistry = graphRegistry,
                        hostAccessState = hostAccessState,
                        hostWriteStuck = hostWriteStuck,
                        hostWritePendingCount = hostWritePendingCount,
                        gitConfigRepository = gitConfigRepository,
                        graphIoStack = graphIoStack,
                        viewModelStack = viewModelStack,
                        supportingViewModels = supportingViewModels,
                        tagVoiceStack = tagVoiceStack,
                        perfTelemetry = perfTelemetry,
                        scope = scope,
                        graphContentLogger = graphContentLogger,
                    ),
                )
            },
            statusBar = {
                // See GraphContentStatusAndBottomBar.kt.
                if (!isMobile) {
                    GraphContentDesktopStatusRow(
                        viewModel,
                        GraphContentStatusRowInputs(
                            appState = appState,
                            encryptionManager = encryptionManager,
                            activeGraphInfo = activeGraphInfo,
                            pluginHost = pluginHost,
                            activeVaultManager = vaultManager.takeIf { isParanoidMode },
                            graphIoStack = graphIoStack,
                        ),
                    )
                }
                SnackbarHost(hostState = snackbarHostState)
            },
            bottomBar = {
                GraphContentBottomBar(
                    appState,
                    GraphContentBottomBarInputs(viewModel, voiceCaptureViewModel, voiceCaptureState, voicePipeline),
                    ::closeSidebarIfMobile,
                )
            }
        )

        GraphDialogLayer(
            appState = appState,
            searchViewModel = searchViewModel,
            viewModel = viewModel,
            notificationManager = notificationManager,
            fileSystem = fileSystem,
            frameMetric = frameMetricState,
            deps = GraphDialogLayerDeps(
                settings = SettingsDialogDeps(
                    voiceSettings = voiceSettings,
                    llmCredentialStore = llmCredentialStore,
                    llmProviderRegistry = llmProviderRegistry,
                    llmSettings = llmSettings,
                    onLlmCredentialsChange = { llmRegistryRefreshTokenState.value++ },
                    onRebuildVoicePipeline = onRebuildVoicePipeline,
                    deviceSttAvailable = deviceSttAvailable,
                    deviceLlmAvailable = deviceLlmAvailable,
                    isParanoidMode = isParanoidMode,
                    isVaultUnlocked = vaultState is VaultState.Unlocked,
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
                    hasLlmKey = hasTagSuggestionLlmProvider,
                    hostAccessState = hostAccessState,
                    onConnectHostDirectory = onConnectHostDirectory,
                    onMoveStorageLocation = storageLocationResolver?.let { resolver ->
                        { resolver.resolveOrBackfill(activeGraphId?.value ?: "") }
                    },
                    storageMoveGraphName = activeGraphInfo?.displayName ?: "this graph",
                    onStorageLocationChoose = onStorageLocationChoose,
                    // Story 3.3.3 (AppOwned→HostFolder direction): a name-only preview
                    // pick (see FileSystem.pickHostFolderNamePreview's doc for why it
                    // doesn't reuse pickDirectoryAsync/relinkHostDirectoryAsync) wrapped
                    // as the StorageLocation FolderSyncSettings's UnifiedLocationPicker
                    // needs to name the destination — the real connect (its own native
                    // picker call) happens later, when the user confirms Link.
                    onBrowseRequestForMove = {
                        fileSystem.pickHostFolderNamePreview()?.let { name ->
                            StorageLocation.HostFolder(activeGraphId?.value ?: "", name)
                        }
                    },
                    onBrowseClickForMove = {
                        // Must run synchronously here, not inside the suspend lambda
                        // above — same transient-user-activation constraint as every
                        // other showDirectoryPicker()-backed click in this file.
                        fileSystem.requestDirectoryPickerNow()
                    },
                    onUnlinkHostDirectory = onUnlinkHostDirectory,
                    hotkeyComboLabel = hotkeyComboLabel,
                    captureTargetSettings = captureTargetSettings,
                    captureGraphs = graphRegistry.graphs,
                ),
                gitSync = GitSyncDeps(
                    gitSyncService = gitSyncService,
                    gitRepository = gitRepository,
                    gitConfigRepository = gitConfigRepository,
                    activeGraphId = activeGraphId?.value,
                    onCloneAndAdd = if (gitRepository != null) {
                        { url, localPath, auth, location, displayName, description, onProgress, onStateChange ->
                            // git-sync-resilience Story 3.1.3: Android routes the clone through
                            // GitCloneWorker (dataSync foreground survival) and Desktop (Story 4.1.4)
                            // through JvmGitCloneWorkerLauncher, so Cancel has something to cancel.
                            // A platform with no launcher wired (e.g. iOS) stays on the direct call.
                            if (gitCloneWorkerLauncher != null) {
                                val graphId = graphManager.graphIdFromPath(fileSystem.expandTilde(localPath)).value
                                inFlightCloneTracker.track(graphId) {
                                    gitCloneWorkerLauncher.launchClone(
                                        graphId, url, localPath, auth, onProgress, onStateChange, displayName,
                                    )
                                }.map { graphManager.addGraph(localPath, location, displayName, description).value }
                            } else {
                                graphManager.cloneAndAdd(
                                    gitRepository, url, localPath, auth, onProgress, location, displayName, description, onStateChange,
                                ).map { it.value }
                            }
                        }
                    } else null,
                    onCancelClone = { inFlightCloneTracker.cancel(gitCloneWorkerLauncher) },
                    graphPath = activeGraphPath,
                    detectedRepoRoot = graphRegistry.graphs.firstOrNull { it.id == activeGraphId }?.detectedRepoRoot,
                    detectedWikiSubdir = graphRegistry.graphs.firstOrNull { it.id == activeGraphId }?.detectedWikiSubdir,
                    onCloneComplete = { newGraphId ->
                        scope.launch { graphManager.switchGraph(GraphId(newGraphId)) }
                    },
                    onAuthError = { viewModel.openGitSetupForCredentials() },
                ),
                share = ShareDialogDeps(
                    shareProvider = shareProvider,
                    exportService = exportService,
                    driveClient = null, // DriveApiClient injected from platform entry point in a future phase
                    shareGoogleAuthManager = googleAuthManager,
                    currentPage = appState.currentPage,
                    currentBlocks = appState.currentPage?.let {
                        blockStateManager.blocksForPage(it.uuid.value)
                    } ?: emptyList(),
                    selectedBlockUuids = blockStateManager.selectedBlockUuids.collectAsState().value,
                ),
                debugState = debugMenuState,
                extraCommands = remember(copyFlow) {
                    listOfNotNull(copyFlow?.let { flow -> Command("copy-pages", "Copy pages to...") { flow.open() } })
                },
                loadPageBlocks = { pageUuidStr -> repos.blockRepository.getBlocksForPage(dev.stapler.stelekit.model.PageUuid(pageUuidStr)) },
                onDebugStateChange = { newState ->
                    debugMenuStateState.value = newState
                    viewModel.onDebugMenuStateChange(newState)
                },
            ),
        )

        if (copyFlow != null && activeGraphId != null) {
            val copyBinding = remember(activeGraphId, repos) {
                CopyGraphBinding(
                    graphId = activeGraphId,
                    source = ActiveDbPageSource(repos.pageRepository, repos.blockRepository, repos.searchRepository),
                    pagesByNames = { names -> repos.pageRepository.getPagesByNames(names) },
                    onAddGraph = newGraphFlowController.onStartNewGraphFlow,
                )
            }
            CopyFlowHost(
                controller = copyFlow,
                binding = copyBinding,
                conflictReviewFactory = {
                    repos.writeActor?.let { actor ->
                        ConflictReviewViewModel(
                            repos.propertyRepository,
                            repos.blockRepository,
                            actor,
                            conflictPagePersister(repos.pageRepository, repos.blockRepository, graphIoStack.graphWriter) { activeGraphPath },
                        )
                    }
                },
                onOpenPage = { uuid -> viewModel.navigateToPageByUuid(uuid.value) },
                onNotice = { viewModel.sendSnackbar(it) },
            )
        }

        // See GraphContentNewGraphFlow.kt for NewGraphDialog/UnifiedLocationPicker/
        // PlainGraphAppOwnedWarningDialog — the three dialogs that step through
        // "New graph…".
        GraphContentNewGraphDialogs(deps, viewModel, scope, newGraphFlowController)

        // Epic 3.4/Story 3.1.5: renders the coordinator's live Flow<StorageMoveUiState>,
        // started by onStorageLocationChoose above. Cancel just cancels the collecting
        // job and clears state — the coordinator's own NonCancellable cleanup (reopen,
        // quiesce release) still runs even though no terminal state reaches this dialog
        // on that path (see GraphRelocationCoordinator.relocate's doc).
        storageMoveState?.let { state ->
            StorageMoveProgressDialog(
                graphName = storageMoveGraphName,
                state = state,
                onCancel = storageMoveController.onCancel,
                onRetry = storageMoveController.onRetry,
                onSummaryAcknowledge = storageMoveController.onAcknowledge,
                onReopenFailedAcknowledge = storageMoveController.onAcknowledge,
            )
        }

        } // CompositionLocalProvider(LocalWindowSizeClass)
    }
}
