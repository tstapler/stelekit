// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import dev.stapler.stelekit.capture.HotkeyRegistrationFailure
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.migration.registerAllMigrations
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.DEMO_GRAPH_ID
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.performance.DebugMenuState
import dev.stapler.stelekit.performance.LocalSpanRecorder
import dev.stapler.stelekit.platform.*
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.repository.*
import dev.stapler.stelekit.ui.components.*
import dev.stapler.stelekit.ui.components.settings.SettingsCategory
import dev.stapler.stelekit.ui.components.settings.SettingsDialog
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.i18n.Language
import dev.stapler.stelekit.ui.i18n.LocalI18n
import dev.stapler.stelekit.ui.i18n.t
import dev.stapler.stelekit.ui.screens.EmptyGraphStateScreen
import dev.stapler.stelekit.ui.screens.LibrarySetupScreen
import dev.stapler.stelekit.ui.screens.PermissionRecoveryScreen
import dev.stapler.stelekit.ui.screens.VaultUnlockScreen
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.plus

/** Runs [importOperation] bounded by [timeoutMs], returning `null` on timeout instead of hanging. */
internal suspend fun <T> withImportTimeout(
    timeoutMs: Long = 20_000L,
    importOperation: suspend () -> T,
): T? = withTimeoutOrNull(timeoutMs) { importOperation() }


/** Shown when the user has explicitly removed their only graph — see [StelekitApp]'s call site. */
@Composable
private fun EmptyGraphScreen(
    onCreateGraph: (() -> Unit)?,
    errorMessage: String?,
    onTryDemo: () -> Unit,
) {
    StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
        EmptyGraphStateScreen(onCreateGraph = onCreateGraph, onTryDemo = onTryDemo, errorMessage = errorMessage)
    }
}

/**
 * Root Composable for the Logseq application.
 * Updated to use multi-graph support with GraphManager.
 */
@Composable
fun StelekitApp(
    fileSystem: FileSystem,
    graphPath: String,
    deps: StelekitAppDeps = remember { StelekitAppDeps() },
) {
    val platformSettings = remember { PlatformSettings() }
    val scope = rememberCoroutineScope()

    // Register all content migrations once before any graph is opened
    remember { registerAllMigrations() }

    val graphManagerState = rememberGraphManagerState(
        injectedGraphManager = deps.graphManager,
        platformSettings = platformSettings,
        fileSystem = fileSystem,
        onGraphManagerReady = deps.lifecycleHooks.onGraphManagerReady,
    )
    val graphManager = graphManagerState.graphManager

    if (permissionGateAndGraphInit(fileSystem, graphPath, graphManager, scope)) return

    val notificationManager = remember { NotificationManager() }
    LaunchedEffect(notificationManager) { deps.lifecycleHooks.onNotificationManagerReady?.invoke(notificationManager) }

    if (emptyGraphGate(graphManager, fileSystem, scope, graphManagerState.activeGraphId)) return

    MainGraphContentHost(fileSystem, deps, platformSettings, graphManagerState, notificationManager)
}

/**
 * Renders the loading state or the active graph's [GraphContent] once past both [StelekitApp]
 * gates (SAF permission and empty-graph). Split out from [StelekitApp] purely for length — see
 * ADR-001-style decomposition rationale at [GraphContent]'s own doc.
 */
@Composable
private fun MainGraphContentHost(
    fileSystem: FileSystem,
    deps: StelekitAppDeps,
    platformSettings: Settings,
    graphManagerState: GraphManagerState,
    notificationManager: NotificationManager,
) {
    val graphManager = graphManagerState.graphManager
    val activeGraphId = graphManagerState.activeGraphId
    val repos = graphManagerState.activeRepoSet

    // Created here, above key(activeGraphId), so a page snapshot survives the graph switch
    // it's meant to be pasted into — see GraphMergeService's class doc.
    val graphMergeService = remember { dev.stapler.stelekit.transfer.GraphMergeService() }

    if (repos == null || !graphManagerState.migrationReady) {
        // Show loading state while repositories are being initialized or migration is running.
        // Includes an escape hatch to Settings — see InitializingScreenThemed's doc for why a
        // stuck load would otherwise leave the user with no way back to the toggle that caused
        // it (e.g. db.libsql.enabled), and why Performance/Logs are deliberately not exposed here.
        InitializingScreenThemed(platformSettings)
        return
    }

    // Use key(graphId) to recreate ViewModels when graph changes
    Box(modifier = Modifier.fillMaxSize()) {
        key(activeGraphId) {
            GraphContent(GraphContentDeps(
                repos = repos,
                fileSystem = fileSystem,
                platformSettings = platformSettings,
                graphManager = graphManager,
                notificationManager = notificationManager,
                onMemoryPressure = deps.lifecycleHooks.onMemoryPressure,
                coreServices = deps.coreServices,
                voiceConfig = deps.voiceConfig,
                platformIntegrations = deps.platformIntegrations,
                webSyncDeps = deps.webSyncDeps,
                graphMergeService = graphMergeService,
                hotkeyComboLabel = deps.captureDeps.hotkeyComboLabel,
            ))
        }
        CaptureNoticesOverlay(platformSettings, deps.captureDeps)
    }
}

/** Bundles [GraphManager] plus the derived state [StelekitApp] needs from it (Parameter Object pattern). */
private data class GraphManagerState(
    val graphManager: GraphManager,
    val activeRepoSet: RepositorySet?,
    val activeGraphId: GraphId?,
    val migrationReady: Boolean,
)

/**
 * Creates (or reuses an injected) [GraphManager] and tracks the derived state [StelekitApp] reads
 * from it: the active repository set, the active graph id, and whether the one-shot UUID migration
 * has completed for the active graph. Migration-readiness resets to false whenever the active graph
 * changes so the gate re-applies; try/finally ensures it always returns to true even if the effect
 * is cancelled mid-run (e.g. activeGraphId changes twice in quick succession), preventing the
 * CircularProgressIndicator from spinning forever.
 */
@Composable
private fun rememberGraphManagerState(
    injectedGraphManager: GraphManager?,
    platformSettings: Settings,
    fileSystem: FileSystem,
    onGraphManagerReady: ((GraphManager) -> Unit)?,
): GraphManagerState {
    val graphManager = injectedGraphManager ?: remember(platformSettings, fileSystem) {
        GraphManager(platformSettings, DriverFactory(), fileSystem)
    }
    LaunchedEffect(graphManager) { onGraphManagerReady?.invoke(graphManager) }

    val activeRepoSet by graphManager.activeRepositorySet.collectAsState()
    val graphRegistry by graphManager.graphRegistry.collectAsState()
    val activeGraphId = graphRegistry.activeGraphId

    var migrationReady by remember { mutableStateOf(false) }
    LaunchedEffect(activeGraphId) {
        migrationReady = false
        try {
            graphManager.awaitPendingMigration()
        } finally {
            migrationReady = true
        }
    }

    return GraphManagerState(graphManager, activeRepoSet, activeGraphId, migrationReady)
}

/**
 * Prefers the GraphManager's persisted active graph over the filesystem-default [graphPath] so we
 * don't force a graph switch (and an unnecessary migration cycle) on every launch when the user
 * has already chosen a different graph.
 */
internal fun initialGraphPath(graphManager: GraphManager, graphPath: String): String {
    val persistedPath = graphManager.getActiveGraphInfo()?.path
    return if (!persistedPath.isNullOrEmpty()) persistedPath else graphPath
}


/** Whether storage permission was granted the last time the app was paused. */
private enum class PriorPermissionState { WAS_GRANTED, WAS_NOT_GRANTED }

/** Returns the updated [PriorPermissionState] for [ObservePermissionRevocationOnResume]. */
private fun nextPriorPermissionState(
    event: Lifecycle.Event,
    priorPermissionState: PriorPermissionState,
    permissionGranted: () -> Boolean,
    fileSystem: FileSystem,
    onPermissionGrantedChange: (Boolean) -> Unit,
): PriorPermissionState = when (event) {
    Lifecycle.Event.ON_PAUSE ->
        if (permissionGranted()) PriorPermissionState.WAS_GRANTED else PriorPermissionState.WAS_NOT_GRANTED
    Lifecycle.Event.ON_RESUME -> {
        if (priorPermissionState == PriorPermissionState.WAS_GRANTED) {
            onPermissionGrantedChange(fileSystem.hasStoragePermission())
        }
        priorPermissionState
    }
    else -> priorPermissionState
}

/** Builds [EmptyGraphScreen]'s `onCreateGraph` handler — null when the platform has no native picker. */
internal fun buildCreateGraphHandler(
    fileSystem: FileSystem,
    graphManager: GraphManager,
    scope: kotlinx.coroutines.CoroutineScope,
    onError: (String?) -> Unit,
): (() -> Unit)? {
    if (!fileSystem.supportsNativeDirectoryPicker) return null
    return { scope.launch { createGraphViaPicker(fileSystem, graphManager, onError) } }
}

/** Body of [StelekitApp]'s `onCreateGraph` handler for [EmptyGraphScreen]. */
private suspend fun createGraphViaPicker(
    fileSystem: FileSystem,
    graphManager: GraphManager,
    onError: (String?) -> Unit,
) {
    val path = fileSystem.pickDirectoryAsync()
    if (path != null) {
        onError(null)
        val graphId = graphManager.addGraph(path)
        graphManager.switchGraph(graphId)
    } else {
        onError(fileSystem.consumeLastPickerError())
    }
}

/** Body of [StelekitApp]'s `onTryDemo` handler for [EmptyGraphScreen]. */
internal suspend fun tryLoadDemoGraph(graphManager: GraphManager, onError: (String) -> Unit) {
    try {
        val graphId = graphManager.addDemoGraph()
        graphManager.switchGraph(graphId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError("Could not load the demo graph: ${e.message}")
    }
}

/** Bundles [pickFolderAndUpdateState]'s three result callbacks (Parameter Object pattern). */
internal class FolderPickCallbacks(
    val onPathPicked: (String) -> Unit,
    val onPermissionGrantedChange: (Boolean) -> Unit,
    val onError: (String?) -> Unit,
)

/** Runs [pickFolderAndUpdateState] on [scope] — kept as its own function so call sites stay a single statement. */
internal fun launchFolderPick(
    scope: kotlinx.coroutines.CoroutineScope,
    fileSystem: FileSystem,
    appLogger: Logger,
    callbacks: FolderPickCallbacks,
) {
    scope.launch { pickFolderAndUpdateState(fileSystem, appLogger, callbacks) }
}

/** Body of [StelekitApp]'s `onFolderPicked` — pure suspend logic, no Compose state captured directly. */
private suspend fun pickFolderAndUpdateState(
    fileSystem: FileSystem,
    appLogger: Logger,
    callbacks: FolderPickCallbacks,
) {
    callbacks.onError(null)
    appLogger.info("onFolderPicked: launching folder picker")
    val newPath = fileSystem.pickDirectoryAsync()
    appLogger.info("onFolderPicked: pickDirectoryAsync returned '$newPath'")
    if (newPath != null) {
        callbacks.onPathPicked(newPath)
        val granted = fileSystem.hasStoragePermission()
        appLogger.info("onFolderPicked: hasStoragePermission=$granted after picking '$newPath'")
        callbacks.onPermissionGrantedChange(granted)
        if (!granted) {
            callbacks.onError("Folder selected but permission not granted. Try choosing the folder again.")
        }
    } else {
        val pickerError = fileSystem.consumeLastPickerError()
        appLogger.info("onFolderPicked: picker returned null (cancelled, failed, or folder type not supported): $pickerError")
        callbacks.onError(
            pickerError
                ?: "No folder was selected. Please choose a local folder on your device (not Google Drive or cloud storage)."
        )
    }
}

/**
 * Stories 1.4.2/1.4.3's two one-time notices, layered as an overlay over the main window content
 * (never blocking or stealing focus — Nielsen #1/#9, `design/ux.md` Surfaces 2/3). A registration
 * failure supersedes the first-run notice for the whole launch: [firstRunNoticeShown]'s persisted
 * flag is only written when the notice is actually shown and dismissed, so it still shows
 * normally on a later, successful-registration launch.
 */
@Composable
private fun CaptureNoticesOverlay(platformSettings: Settings, captureDeps: StelekitAppCaptureDeps) {
    val failureFlow = remember(captureDeps.hotkeyRegistrationFailure) {
        captureDeps.hotkeyRegistrationFailure
            ?: MutableStateFlow<HotkeyRegistrationFailure?>(null)
    }
    val hotkeyFailure by failureFlow.collectAsState()
    var conflictNoticeDismissedThisSession by remember { mutableStateOf(false) }
    var firstRunNoticeShown by remember {
        mutableStateOf(platformSettings.getBoolean(CAPTURE_FIRST_RUN_NOTICE_KEY, false))
    }

    when {
        hotkeyFailure != null && !conflictNoticeDismissedThisSession -> HotkeyConflictNotice(
            failure = hotkeyFailure!!,
            hotkeyCombo = captureDeps.hotkeyComboLabel,
            onDismiss = { conflictNoticeDismissedThisSession = true },
        )
        !firstRunNoticeShown && hotkeyFailure == null -> FirstRunHotkeyNotice(
            hotkeyCombo = captureDeps.hotkeyComboLabel,
            onDismiss = {
                platformSettings.putBoolean(CAPTURE_FIRST_RUN_NOTICE_KEY, true)
                firstRunNoticeShown = true
            },
        )
    }
}

private const val CAPTURE_FIRST_RUN_NOTICE_KEY = "capture.firstRunNoticeShown"

/**
 * Composition root for a single active graph.
 * Owns ViewModel creation and lifecycle wiring only — all UI is delegated
 * to focused child composables. Recreated via key(graphId) on graph switch.
 *
 * See ADR-001 for decomposition rationale.
 */
@Composable
private fun GraphContent(deps: GraphContentDeps) {
    val repos = deps.repos
    val fileSystem = deps.fileSystem
    val platformSettings = deps.platformSettings
    val graphManager = deps.graphManager
    val notificationManager = deps.notificationManager
    val onMemoryPressure = deps.onMemoryPressure
    val pluginHost = deps.coreServices.pluginHost
    val encryptionManager = deps.coreServices.encryptionManager
    val voicePipeline = deps.coreServices.voicePipeline
    val spanRecorder = deps.coreServices.spanRecorder
    val voiceSettings = deps.voiceConfig.voiceSettings
    val onRebuildVoicePipeline = deps.voiceConfig.onRebuildVoicePipeline
    val deviceSttAvailable = deps.voiceConfig.deviceSttAvailable
    val deviceLlmAvailable = deps.voiceConfig.deviceLlmAvailable
    val gitRepository = deps.platformIntegrations.gitRepository
    val cryptoEngine = deps.platformIntegrations.cryptoEngine
    val attachmentService = deps.platformIntegrations.attachmentService
    val hotkeyComboLabel = deps.hotkeyComboLabel
    val googleAuthManager = deps.platformIntegrations.googleAuthManager
    val storageLocationResolver = deps.platformIntegrations.storageLocationResolver
    val localChangesCountFlow = deps.webSyncDeps.localChangesCountFlow

    CompositionLocalProvider(
        LocalSpanRecorder provides spanRecorder,
        LocalFileSystem provides fileSystem,
    ) {
    val scope = rememberCoroutineScope()
    val graphContentLogger = remember { Logger("GraphContent") }
    val composeClipboard = LocalClipboardManager.current
    val clipboardProvider = rememberClipboardProvider(composeClipboard)

    // LLM/voice provider credential store (ADR-011) — not vault-integrated; a plain
    // CredentialStore() is correct here (unlike git credentials, LLM keys have no
    // paranoid-mode requirement in this epic).
    val llmCredentialStore = remember {
        dev.stapler.stelekit.llm.LlmCredentialStore(dev.stapler.stelekit.platform.security.CredentialStore())
    }
    // LLM provider registry + settings (Epic 6 Settings UI). llmRegistryRefreshToken forces a
    // rebuild after the user adds/edits/removes a credential through the new Settings UI —
    // LlmCredentialStore itself isn't reactive (no Flow), so this is the simplest way to keep
    // the provider list in sync within a session without adding a new observable layer.
    // MutableState, not a destructured `var ... by` — see GraphContentActiveShellInputs's doc.
    val llmRegistryRefreshTokenState = remember { androidx.compose.runtime.mutableStateOf(0) }
    val llmSettings = remember(platformSettings) { dev.stapler.stelekit.llm.LlmSettings(platformSettings) }
    val llmProviderRegistry = remember(llmCredentialStore, llmSettings, llmRegistryRefreshTokenState.value) {
        dev.stapler.stelekit.llm.buildLlmProviderRegistry(llmCredentialStore, llmSettings)
    }

    // One-shot migrations (Epic 2 Story 2.3 credential migration; Epic 8 Story 8.1b voice
    // on-device flag migration; Epic 8 Story 8.2b tag-suggestion existing-install guard). Run
    // synchronously in this remember block — not LaunchedEffect — so it completes before
    // tagEngine/voicePipeline provider selection or any other code path reads
    // voiceSettings/llmCredentialStore/llmSettings on the first frame. runIfNeeded() is
    // idempotent per-step (each step has its own one-shot flag), so re-execution on
    // recomposition is safe. The return value signals whether Story 8.2's one-time
    // "on-device tag suggestions available" notice should be shown this session — consumed
    // below, once snackbarHostState is in scope.
    val showTagSuggestionOnDeviceNotice = remember(voiceSettings, platformSettings, llmCredentialStore, llmSettings) {
        if (voiceSettings != null) {
            dev.stapler.stelekit.llm.LlmCredentialMigration(
                voiceSettings, llmCredentialStore, platformSettings, llmSettings,
            ).runIfNeeded()
        } else false
    }

    // See GraphContentVaultSetup.kt: active-graph info/path, effective FileSystem, and
    // paranoid-mode/vault state (VaultState/VaultManager/VaultCredentialStore).
    val vaultSetup = rememberGraphContentVaultSetup(graphManager, fileSystem, cryptoEngine)
    val activeGraphInfo = vaultSetup.activeGraphInfo
    val activeGraphPath = vaultSetup.activeGraphPath
    val effectiveFileSystem = vaultSetup.effectiveFileSystem
    var isParanoidMode by vaultSetup.isParanoidModeState
    var vaultState by vaultSetup.vaultStateState
    var vaultManager by vaultSetup.vaultManagerState
    val vaultCredentialStore = vaultSetup.vaultCredentialStore

    // See GraphContentGraphIoSetup.kt: sidecar managers, image-import service, GraphLoader, GraphWriter.
    val graphIoStack = rememberGraphContentGraphIoStack(deps, effectiveFileSystem, activeGraphPath, activeGraphInfo, graphContentLogger)
    val sidecarManager = graphIoStack.sidecarManager
    val imageSidecarManager = graphIoStack.imageSidecarManager
    val imageImportService = graphIoStack.imageImportService
    val graphLoader = graphIoStack.graphLoader
    val graphWriter = graphIoStack.graphWriter

    // See GraphContentGitSyncSetup.kt: git config repository, gitSyncGraphId, GitSyncService.
    val gitSyncStack = rememberGraphContentGitSyncStack(deps, graphIoStack, vaultCredentialStore)
    val gitConfigRepository = gitSyncStack.gitConfigRepository
    val gitSyncGraphId = gitSyncStack.gitSyncGraphId
    val gitSyncService = gitSyncStack.gitSyncService

    // See GraphContentViewModelSetup.kt: blockStateManager, exportService, shareProvider, viewModel.
    val viewModelStack = rememberGraphContentViewModelStack(deps, effectiveFileSystem, graphIoStack, clipboardProvider)
    val blockStateManager = viewModelStack.blockStateManager
    val exportService = viewModelStack.exportService
    val shareProvider = viewModelStack.shareProvider
    val viewModel = viewModelStack.viewModel

    // See GraphContentStorageMove.kt: GraphRelocationCoordinator, StorageMoveUiState, and the
    // Move-storage-location callbacks wired into the sidebar and StorageMoveProgressDialog.
    val storageMoveController = rememberGraphContentStorageMoveController(deps, graphWriter, scope, graphContentLogger, viewModel)

    // See GraphContentBootstrapEffects.kt: memory-pressure registration + /image command wiring.
    WireGraphContentBootstrapEffects(
        viewModel, onMemoryPressure, attachmentService,
        GraphContentBootstrapEnv(blockStateManager, scope, graphContentLogger),
    )

    // See GraphContentVaultActions.kt: vault unlock/create/keyslot/lock handlers, plus the
    // vault-lock cleanup and post-unlock bootstrap-load effects.
    val vaultActions = rememberGraphContentVaultActions(
        vaultSetup, graphIoStack, deps, GraphContentVaultEnv(scope, graphContentLogger), viewModel,
    )
    val onVaultUnlock = vaultActions.onVaultUnlock

    // See GraphContentGoogleAuth.kt — threaded into SettingsDialog via GraphDialogLayer.
    val googleAuthState = rememberGraphContentGoogleAuthState(googleAuthManager, scope, graphContentLogger)

    // MutableState, not a destructured `var ... by` — see GraphContentActiveShellInputs's doc.
    val debugMenuStateState = remember {
        mutableStateOf(repos.debugFlagRepository?.loadDebugMenuState() ?: DebugMenuState())
    }
    val debugMenuState = debugMenuStateState.value

    // Sync span capture toggle → ring buffer enabled flag so histograms remain always-on
    // but span recording only runs when explicitly requested.
    androidx.compose.runtime.LaunchedEffect(debugMenuState.isSpanCaptureEnabled) {
        repos.ringBuffer?.enabled = debugMenuState.isSpanCaptureEnabled
    }

    // See GraphContentPerformanceTelemetry.kt: eager Performance-tab data collection plus the
    // always-on frame-duration/jank recorder.
    val perfTelemetry = rememberGraphContentPerformanceTelemetry(repos, viewModel, debugMenuState)
    val frameMetricState = perfTelemetry.frameMetricState
    val perfSpans = perfTelemetry.perfSpans
    val perfHistograms = perfTelemetry.perfHistograms
    val perfQueryStats = perfTelemetry.perfQueryStats

    // See GraphContentSupportingViewModels.kt: Journals/AllPages/LibraryStats/Search view models,
    // plus the disposal effect that closes all of them (and blockStateManager/viewModel) when
    // GraphContent leaves composition.
    val supportingViewModels = rememberGraphContentSupportingViewModels(deps, blockStateManager, viewModel)
    val activeSectionIds = supportingViewModels.activeSectionIds
    val journalsViewModel = supportingViewModels.journalsViewModel
    val allPagesViewModel = supportingViewModels.allPagesViewModel
    val libraryStatsViewModel = supportingViewModels.libraryStatsViewModel
    val searchViewModel = supportingViewModels.searchViewModel

    // See GraphContentTagVoiceSetup.kt: tag-suggestion engine/settings and voice capture.
    val tagVoiceStack = rememberGraphContentTagVoiceStack(deps, llmProviderRegistry, llmSettings, viewModel)
    val tagSettings = tagVoiceStack.tagSettings
    val qrTransferSettings = tagVoiceStack.qrTransferSettings
    val hasTagSuggestionLlmProvider = tagVoiceStack.hasTagSuggestionLlmProvider
    val tagSuggestionViewModel = tagVoiceStack.tagSuggestionViewModel
    val voiceCaptureViewModel = tagVoiceStack.voiceCaptureViewModel

    // See GraphContentLifecycleEffects.kt: force-flush pending writes on Android lifecycle pause/stop.
    ObserveGraphContentLifecycle(viewModel, voiceCaptureViewModel, graphIoStack) { vaultManager }

    val appState by viewModel.uiState.collectAsState()

    StelekitTheme(themeMode = appState.themeMode) {
        CompositionLocalProvider(LocalI18n provides I18n(appState.language)) {
            when {
                !appState.onboardingCompleted -> {
                    GraphContentOnboarding(fileSystem, graphManager, viewModel, scope)
                }
                isParanoidMode && vaultState !is VaultState.Unlocked -> {
                    VaultUnlockScreen(
                        graphName = activeGraphInfo?.displayName ?: activeGraphPath,
                        vaultState = vaultState,
                        onUnlock = onVaultUnlock,
                    )
                }
                else -> {
                    GraphContentActiveShell(
                        deps,
                        viewModel,
                        GraphContentActiveShellInputs(
                            scope = scope,
                            graphContentLogger = graphContentLogger,
                            llmCredentialStore = llmCredentialStore,
                            llmRegistryRefreshTokenState = llmRegistryRefreshTokenState,
                            llmSettings = llmSettings,
                            llmProviderRegistry = llmProviderRegistry,
                            showTagSuggestionOnDeviceNotice = showTagSuggestionOnDeviceNotice,
                            activeGraphInfo = activeGraphInfo,
                            activeGraphPath = activeGraphPath,
                            effectiveFileSystem = effectiveFileSystem,
                            isParanoidMode = isParanoidMode,
                            vaultState = vaultState,
                            vaultManager = vaultManager,
                            graphIoStack = graphIoStack,
                            gitSyncStack = gitSyncStack,
                            viewModelStack = viewModelStack,
                            storageMoveController = storageMoveController,
                            vaultActions = vaultActions,
                            googleAuthState = googleAuthState,
                            debugMenuStateState = debugMenuStateState,
                            perfTelemetry = perfTelemetry,
                            supportingViewModels = supportingViewModels,
                            tagVoiceStack = tagVoiceStack,
                        ),
                    )
                }
            } // when
        }
    }
    } // CompositionLocalProvider(LocalSpanRecorder, LocalFileSystem)
}

/** Bundles [onGraphKeyEvent]'s per-shortcut callbacks (Parameter Object pattern). */
internal data class GraphKeyEventHandlers(
    val onCommandPalette: () -> Unit,
    val onSearch: () -> Unit,
    val onToggleSidebar: () -> Unit,
    val onToggleRightSidebar: () -> Unit,
    val onSettings: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onBack: () -> Unit,
    val onForward: () -> Unit,
    val onDebugMenu: () -> Unit = {},
)

/** Which "add a new graph" UI [onAddGraph] should show, given this platform's [FileSystem]. */
internal enum class AddGraphFlowMode { ShowLocationPicker, ImmediateNativePicker, ShowNameDialog }

/**
 * Pure decision logic for the sidebar's "add graph" affordance (Story 2.2.1) — factored out of
 * `onAddGraph` so its "no SAF intent for `AppOwned`" acceptance criterion is testable without
 * mounting the whole `App.kt` composable tree (see `AddGraphAppOwnedTest.kt`).
 */
internal fun addGraphFlowMode(fileSystem: FileSystem): AddGraphFlowMode = when {
    fileSystem.supportsAppOwnedStorage -> AddGraphFlowMode.ShowLocationPicker
    fileSystem.supportsNativeDirectoryPicker -> AddGraphFlowMode.ImmediateNativePicker
    else -> AddGraphFlowMode.ShowNameDialog
}

/**
 * Where "New graph…" should put a graph created by name: `null` when [mode] uses a resolved
 * [StorageLocation] instead (app storage / picked folder), otherwise the path to register. Only
 * [AddGraphFlowMode.ImmediateNativePicker] needs the folder created on disk first.
 */
internal fun newGraphPathFor(mode: AddGraphFlowMode, parent: String, name: String): String? = when (mode) {
    AddGraphFlowMode.ShowLocationPicker -> null
    AddGraphFlowMode.ImmediateNativePicker -> "${parent.trimEnd('/')}/${name.trim()}"
    AddGraphFlowMode.ShowNameDialog -> "/stelekit/${name.trim()}"
}

/**
 * Pure platform-copy logic for `UnifiedLocationPicker`'s "App storage" row subtitle (Story 2.1.1,
 * `design/ux.md` §2) — factored out so the Android/Web wording is testable without mounting the
 * whole composable tree, mirroring [addGraphFlowMode] above. Shared by
 * `GraphContentNewGraphFlow.kt`'s `UnifiedLocationPicker` call site (new-graph flow) and
 * `GitSetupScreen.kt`'s `Step2RepoPath` call site. Uses the same [platform] string convention as
 * that same file's `warningCopy` (see `getDeviceInfo`'s `platform` field and
 * `SloChecker.diskThresholdsFor`).
 */
internal fun appStorageSubtitleFor(platform: String): String = if (platform == "Android") {
    "Kept inside SteleKit only — not visible in your device's file manager, and removed if you " +
        "uninstall the app."
} else {
    PLAIN_GRAPH_APP_OWNED_WARNING_WEB_COPY
}

/**
 * Pure function — handles keyboard shortcuts for the graph content area.
 * Extracted from GraphContent to make shortcut logic testable without a Compose runtime.
 * See ADR-001.
 */
internal fun onGraphKeyEvent(keyEvent: KeyEvent, handlers: GraphKeyEventHandlers): Boolean {
    if (keyEvent.type != KeyEventType.KeyDown) return false
    val isMod = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
    val isShift = keyEvent.isShiftPressed
    return when {
        isMod && isShift && keyEvent.key == Key.P -> { handlers.onCommandPalette(); true }
        isMod && keyEvent.key == Key.K -> { handlers.onSearch(); true }
        isMod && isShift && keyEvent.key == Key.B -> { handlers.onToggleRightSidebar(); true }
        isMod && keyEvent.key == Key.B -> { handlers.onToggleSidebar(); true }
        isMod && keyEvent.key == Key.Comma -> { handlers.onSettings(); true }
        isMod && !isShift && keyEvent.key == Key.Z -> { handlers.onUndo(); true }
        (isMod && isShift && keyEvent.key == Key.Z) || (isMod && keyEvent.key == Key.Y) -> { handlers.onRedo(); true }
        (keyEvent.isAltPressed && keyEvent.key == Key.DirectionLeft) ||
        (isMod && keyEvent.key == Key.LeftBracket) -> { handlers.onBack(); true }
        (keyEvent.isAltPressed && keyEvent.key == Key.DirectionRight) ||
        (isMod && keyEvent.key == Key.RightBracket) -> { handlers.onForward(); true }
        isMod && isShift && keyEvent.key == Key.D -> { handlers.onDebugMenu(); true }
        else -> false
    }
}

/**
 * Fowler's Remove Flag Argument: [EncryptedStatus]/[UnencryptedStatus] are two distinct call
 * paths, each rendering its own icon/text directly — neither converts [EncryptionState] back into
 * a boolean and branches on it internally.
 */
internal enum class EncryptionState { ENCRYPTED, UNENCRYPTED }

@Composable
private fun RowScope.EncryptedStatus() {
    Icon(
        imageVector = Icons.Default.Lock,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(
        text = t("status.encrypted"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RowScope.UnencryptedStatus() {
    Icon(
        imageVector = Icons.Default.LockOpen,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(
        text = t("status.not_encrypted"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Status bar row — pure presentational composable. Receives only primitives;
 * no ViewModel dependency. See ADR-001.
 */
@Composable
internal fun StatusBarContent(
    encryptionState: EncryptionState,
    statusMessage: String,
    activeGraphName: String,
    pluginCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (encryptionState) {
            EncryptionState.ENCRYPTED -> EncryptedStatus()
            EncryptionState.UNENCRYPTED -> UnencryptedStatus()
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = activeGraphName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp).weight(2f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$pluginCount ${t("status.plugins_active")}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

