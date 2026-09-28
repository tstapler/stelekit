// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import dev.stapler.stelekit.capture.HotkeyRegistrationFailure
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import dev.stapler.stelekit.db.GraphEpoch
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.GraphRelocationCoordinator
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.StorageMoveUiState
import dev.stapler.stelekit.migration.registerAllMigrations
import dev.stapler.stelekit.db.SidecarManager
import dev.stapler.stelekit.platform.DemoFileSystem
import dev.stapler.stelekit.platform.HostAccessState
import dev.stapler.stelekit.service.markdownImageLink
import dev.stapler.stelekit.service.toMarkdown
import dev.stapler.stelekit.export.ExportService
import dev.stapler.stelekit.export.HtmlExporter
import dev.stapler.stelekit.export.JsonExporter
import dev.stapler.stelekit.export.MarkdownExporter
import dev.stapler.stelekit.export.PlainTextExporter
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.DEMO_GRAPH_ID
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import dev.stapler.stelekit.performance.DebugBuildConfig
import dev.stapler.stelekit.performance.DebugMenuState
import dev.stapler.stelekit.performance.getDeviceInfo
import dev.stapler.stelekit.performance.LocalSpanRecorder
import dev.stapler.stelekit.performance.PlatformJankStatsEffect
import dev.stapler.stelekit.platform.*
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.repository.*
import dev.stapler.stelekit.ui.components.*
import dev.stapler.stelekit.ui.components.git.GitDetectionBanner
import dev.stapler.stelekit.ui.components.settings.SettingsCategory
import dev.stapler.stelekit.ui.components.settings.SettingsDialog
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.i18n.Language
import dev.stapler.stelekit.ui.i18n.LocalI18n
import dev.stapler.stelekit.ui.i18n.t
import dev.stapler.stelekit.ui.onboarding.Onboarding
import dev.stapler.stelekit.ui.screens.AllPagesViewModel
import dev.stapler.stelekit.ui.screens.EmptyGraphStateScreen
import dev.stapler.stelekit.ui.screens.LibraryStatsViewModel
import dev.stapler.stelekit.ui.screens.JournalsViewModel
import dev.stapler.stelekit.ui.screens.LibrarySetupScreen
import dev.stapler.stelekit.ui.screens.PageView
import dev.stapler.stelekit.ui.screens.PermissionRecoveryScreen
import dev.stapler.stelekit.ui.screens.SearchViewModel
import dev.stapler.stelekit.ui.screens.VaultUnlockScreen
import dev.stapler.stelekit.vault.VaultManager.VaultEvent
import dev.stapler.stelekit.voice.VoiceCaptureState
import dev.stapler.stelekit.voice.VoiceCaptureViewModel
import dev.stapler.stelekit.tags.LlmTagProvider
import dev.stapler.stelekit.tags.TagSettings
import dev.stapler.stelekit.tags.TagSuggestionEngine
import dev.stapler.stelekit.tags.TagSuggestionViewModel
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.performance.PercentileSummary
import dev.stapler.stelekit.performance.QueryStat
import dev.stapler.stelekit.performance.SerializedSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.plus
import arrow.core.Either
import dev.stapler.stelekit.sections.SectionState
import dev.stapler.stelekit.sections.getSectionStates
import dev.stapler.stelekit.db.ImageImportService
import dev.stapler.stelekit.error.toUiMessage
import dev.stapler.stelekit.model.ImageSource
import dev.stapler.stelekit.platform.sensor.SensorModule
import dev.stapler.stelekit.platform.sensor.PlatformImageFile

/** Runs [importOperation] bounded by [timeoutMs], returning `null` on timeout instead of hanging. */
internal suspend fun <T> withImportTimeout(
    timeoutMs: Long = 20_000L,
    importOperation: suspend () -> T,
): T? = withTimeoutOrNull(timeoutMs) { importOperation() }

/** SAF permission was revoked after being granted — folder content is still there, just re-grant. */
@Composable
private fun PermissionRecoveryScreenThemed(
    libraryDisplayName: String?,
    folderPickError: String?,
    onRequestFolder: () -> Unit,
) {
    StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
        PermissionRecoveryScreen(
            folderName = libraryDisplayName,
            onReconnectFolder = onRequestFolder,
            onChooseDifferentFolder = onRequestFolder,
            errorMessage = folderPickError,
        )
    }
}

/** First launch — no folder chosen yet. */
@Composable
private fun FirstLaunchSetupScreenThemed(folderPickError: String?, onRequestFolder: () -> Unit) {
    StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
        LibrarySetupScreen(onChooseFolder = onRequestFolder, errorMessage = folderPickError)
    }
}

private enum class InitializingDebugScreen { SETTINGS, PERFORMANCE, LOGS }

/**
 * Pre-repos "Initializing…" state — the Sidebar (and with it, the settings-gear button and
 * Logs/Performance nav entries) can't mount yet: it's built from repos-backed data (page lists,
 * graph registry, sync state) that doesn't exist until [repos] is non-null. Without an escape
 * hatch here, a slow or wedged load — e.g. flipping `db.libsql.enabled` to a driver that hangs
 * on open — leaves the user with no way back to the toggle that caused it.
 *
 * These buttons are a minimal stand-in for that missing entry point, not a reimplementation of
 * it: they open the same [SettingsDialog]/[PerformanceDashboard]/[LogDashboard] the real menu
 * navigates to, which already work without repos (the developer toggle reads/writes
 * [platformSettings] directly; Performance/Logs read process-global singletons).
 */
@Composable
private fun InitializingScreenThemed(platformSettings: Settings) {
    var openScreen by remember { mutableStateOf<InitializingDebugScreen?>(null) }

    StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
        Box(modifier = Modifier.fillMaxSize()) {
            LoadingOverlay("Initializing…")
            InitializingDebugAccessButtons(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                onOpen = { openScreen = it },
            )
        }

        InitializingSettingsDialog(
            visible = openScreen == InitializingDebugScreen.SETTINGS,
            onDismiss = { openScreen = null },
            platformSettings = platformSettings,
        )

        if (openScreen == InitializingDebugScreen.PERFORMANCE || openScreen == InitializingDebugScreen.LOGS) {
            InitializingFullScreenDialog(screen = openScreen, onDismiss = { openScreen = null })
        }
    }
}

@Composable
private fun InitializingDebugAccessButtons(modifier: Modifier, onOpen: (InitializingDebugScreen) -> Unit) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { onOpen(InitializingDebugScreen.SETTINGS) }) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
        IconButton(onClick = { onOpen(InitializingDebugScreen.PERFORMANCE) }) {
            Icon(Icons.Default.BarChart, contentDescription = "Performance")
        }
        IconButton(onClick = { onOpen(InitializingDebugScreen.LOGS) }) {
            Icon(Icons.Default.Description, contentDescription = "Logs")
        }
    }
}

@Composable
private fun InitializingSettingsDialog(visible: Boolean, onDismiss: () -> Unit, platformSettings: Settings) {
    var libsqlEnabled by remember {
        mutableStateOf(platformSettings.getBoolean("db.libsql.enabled", false))
    }
    SettingsDialog(
        visible = visible,
        onDismiss = onDismiss,
        currentTheme = StelekitThemeMode.SYSTEM,
        onThemeChange = {},
        currentLanguage = Language.ENGLISH,
        onLanguageChange = {},
        onReindex = {},
        initialCategory = SettingsCategory.DEVELOPER,
        isLibsqlDriverEnabled = libsqlEnabled,
        onLibsqlDriverToggle = { enabled ->
            libsqlEnabled = enabled
            platformSettings.putBoolean("db.libsql.enabled", enabled)
        },
    )
}

@Composable
private fun InitializingFullScreenDialog(screen: InitializingDebugScreen?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            InitializingDialogContent(screen, onDismiss)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InitializingDialogContent(screen: InitializingDebugScreen?, onDismiss: () -> Unit) {
    val isPerformance = screen == InitializingDebugScreen.PERFORMANCE
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (isPerformance) "Performance" else "Logs") },
            navigationIcon = { CloseDialogButton(onDismiss) },
        )
        if (isPerformance) {
            PerformanceDashboard(modifier = Modifier.weight(1f))
        } else {
            LogDashboard(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CloseDialogButton(onDismiss: () -> Unit) {
    IconButton(onClick = onDismiss) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
    }
}

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

    if (PermissionGateAndGraphInit(fileSystem, graphPath, graphManager, scope)) return

    val notificationManager = remember { NotificationManager() }
    LaunchedEffect(notificationManager) { deps.lifecycleHooks.onNotificationManagerReady?.invoke(notificationManager) }

    if (EmptyGraphGate(graphManager, fileSystem, scope, graphManagerState.activeGraphId)) return

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
        // Includes an escape hatch to Settings/Performance/Logs — see InitializingScreenThemed's
        // doc for why a stuck load would otherwise leave the user with no way back to the
        // toggle that caused it (e.g. db.libsql.enabled).
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
private fun initialGraphPath(graphManager: GraphManager, graphPath: String): String {
    val persistedPath = graphManager.getActiveGraphInfo()?.path
    return if (!persistedPath.isNullOrEmpty()) persistedPath else graphPath
}

/**
 * Android SAF permission gate — reactive so state refreshes after folder pick. Prefers the
 * GraphManager's persisted active graph over the filesystem default so we don't force a graph
 * switch (and an unnecessary migration cycle) on every launch when the user has already chosen a
 * different graph. Returns true when the setup/recovery screen was shown (SAF permission is
 * required but missing) — [StelekitApp] should return early in that case. Otherwise, registers
 * (idempotently) and activates [graphPath]/the persisted graph path before returning false.
 */
@Composable
private fun PermissionGateAndGraphInit(
    fileSystem: FileSystem,
    graphPath: String,
    graphManager: GraphManager,
    scope: kotlinx.coroutines.CoroutineScope,
): Boolean {
    var currentGraphPath by remember { mutableStateOf(initialGraphPath(graphManager, graphPath)) }
    var permissionGranted by remember { mutableStateOf(fileSystem.hasStoragePermission()) }
    var folderPickError by remember { mutableStateOf<String?>(null) }

    val appLogger = remember { Logger("StelekitApp") }
    val isSafPath = currentGraphPath.startsWith("saf://")

    // ON_RESUME re-check: detect permission revoked mid-session (e.g. user cleared app storage
    // from Android Settings while the app was backgrounded).
    ObservePermissionRevocationOnResume(
        fileSystem = fileSystem,
        permissionGranted = { permissionGranted },
        onPermissionGrantedChange = { permissionGranted = it },
    )

    // Show the setup/recovery screen only when SAF permission is required. Non-SAF paths (e.g.
    // /data/local/tmp/ benchmark paths or desktop paths) don't require a document-tree grant, so
    // skip the screen for those.
    if (!permissionGranted && (isSafPath || currentGraphPath.isEmpty())) {
        PermissionGateScreen(fileSystem, currentGraphPath, folderPickError) {
            fileSystem.requestDirectoryPickerNow()
            launchFolderPick(scope, fileSystem, appLogger, FolderPickCallbacks(
                onPathPicked = { currentGraphPath = it },
                onPermissionGrantedChange = { permissionGranted = it },
                onError = { folderPickError = it },
            ))
        }
        return true
    }

    InitializeGraphFromPath(graphManager, currentGraphPath)
    return false
}

/**
 * Renders whichever setup/recovery screen [PermissionGateAndGraphInit] determined is needed: SAF
 * permission revoked → recovery screen; no path at all (first launch) → setup screen. [isSafPath]
 * is computed locally from [currentGraphPath] rather than taken as a parameter so this stays a
 * plain string/callback signature (see [EncryptionState] for why a boolean *parameter* branched on
 * directly is the pattern to avoid — a locally-derived boolean is not that).
 */
@Composable
private fun PermissionGateScreen(
    fileSystem: FileSystem,
    currentGraphPath: String,
    folderPickError: String?,
    onRequestFolder: () -> Unit,
) {
    val isSafPath = currentGraphPath.startsWith("saf://")
    if (isSafPath) {
        // Permission was revoked — show recovery screen
        PermissionRecoveryScreenThemed(fileSystem.getLibraryDisplayName(), folderPickError, onRequestFolder)
    } else {
        // First launch — no folder chosen yet
        FirstLaunchSetupScreenThemed(folderPickError, onRequestFolder)
    }
}

/**
 * Registers (idempotently) and activates [currentGraphPath]. Always calls addGraph so
 * repositories are initialized — idempotent (returns the existing ID if already registered) and
 * handles reconnect after SAF permission loss, where activeGraphId may be non-null from the
 * persisted registry but the in-memory repos have not been set up in this process. Skips the demo
 * path — demo is managed by `addDemoGraph()` and must not be registered as a real graph here.
 */
@Composable
private fun InitializeGraphFromPath(graphManager: GraphManager, currentGraphPath: String) {
    LaunchedEffect(currentGraphPath) {
        if (currentGraphPath.isNotEmpty() && graphManager.getActiveGraphInfo()?.id != DEMO_GRAPH_ID) {
            val graphId = graphManager.addGraph(currentGraphPath)
            graphManager.switchGraph(graphId)
        }
    }
}

/**
 * Shown when the user has explicitly removed their only graph (see [GraphManager.removeGraph]'s
 * "last real graph" path) — checking `graphsExplicitlyEmptied` rather than `activeGraphId == null`
 * alone is deliberate: the latter is also transiently true for one frame on a brand-new install
 * before [PermissionGateAndGraphInit]'s LaunchedEffect self-heals by adding/activating a default
 * graph, which would otherwise flash this screen on every first launch. Session-scoped (a page
 * reload creates a fresh GraphManager, resetting the flag) — matches removeGraph's own "graph
 * files are not deleted" precedent, so nothing durable needs undoing here either. Returns true when
 * the empty-graph screen was shown — [StelekitApp] should return early in that case.
 */
@Composable
private fun EmptyGraphGate(
    graphManager: GraphManager,
    fileSystem: FileSystem,
    scope: kotlinx.coroutines.CoroutineScope,
    activeGraphId: GraphId?,
): Boolean {
    val graphsExplicitlyEmptied by graphManager.graphsExplicitlyEmptied.collectAsState()
    if (!(graphsExplicitlyEmptied && activeGraphId == null)) return false

    var emptyStateError by remember { mutableStateOf<String?>(null) }
    val onEmptyGraphError: (String?) -> Unit = { emptyStateError = it }
    EmptyGraphScreen(
        onCreateGraph = buildCreateGraphHandler(fileSystem, graphManager, scope, onEmptyGraphError),
        errorMessage = emptyStateError,
        onTryDemo = { scope.launch { tryLoadDemoGraph(graphManager, onEmptyGraphError) } },
    )
    return true
}

/**
 * ON_RESUME re-check: detect permission revoked mid-session (e.g. user cleared app storage from
 * Android Settings while the app was backgrounded). The wasGrantedBeforePause guard prevents a
 * spurious PermissionRecoveryScreen flash when returning from the folder picker, which also
 * causes an ON_PAUSE → ON_RESUME cycle. [permissionGranted] is a live-read lambda (not a plain
 * Boolean) so the effect — created once per [lifecycleOwner] — always observes the current value.
 */
@Composable
private fun ObservePermissionRevocationOnResume(
    fileSystem: FileSystem,
    permissionGranted: () -> Boolean,
    onPermissionGrantedChange: (Boolean) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var priorPermissionState =
            if (permissionGranted()) PriorPermissionState.WAS_GRANTED else PriorPermissionState.WAS_NOT_GRANTED
        val observer = LifecycleEventObserver { _, event ->
            priorPermissionState = nextPriorPermissionState(
                event = event,
                priorPermissionState = priorPermissionState,
                permissionGranted = permissionGranted,
                fileSystem = fileSystem,
                onPermissionGrantedChange = onPermissionGrantedChange,
            )
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
private fun buildCreateGraphHandler(
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
private suspend fun tryLoadDemoGraph(graphManager: GraphManager, onError: (String) -> Unit) {
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
private class FolderPickCallbacks(
    val onPathPicked: (String) -> Unit,
    val onPermissionGrantedChange: (Boolean) -> Unit,
    val onError: (String?) -> Unit,
)

/** Runs [pickFolderAndUpdateState] on [scope] — kept as its own function so call sites stay a single statement. */
private fun launchFolderPick(
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
    val urlFetcher = deps.coreServices.urlFetcher
    val libraryStatsProvider = deps.coreServices.libraryStatsProvider
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
    val requestCameraPermission = deps.platformIntegrations.requestCameraPermission
    val graphMoveQuiesceStrategy = deps.platformIntegrations.graphMoveQuiesceStrategy
    val hostLinkStep = deps.platformIntegrations.hostLinkStep
    val storageLocationResolver = deps.platformIntegrations.storageLocationResolver
    val insufficientSpaceCheck = deps.platformIntegrations.insufficientSpaceCheck
    val gitSyncBusyCounter = deps.platformIntegrations.gitSyncBusyCounter
    val localChangesCountFlow = deps.webSyncDeps.localChangesCountFlow
    val hostAccessStateFlow = deps.webSyncDeps.hostAccessStateFlow
    val hostWritePendingCountFlow = deps.webSyncDeps.hostWritePendingCountFlow
    val hostWriteStuckFlow = deps.webSyncDeps.hostWriteStuckFlow
    val onReconnectHostDirectory = deps.webSyncDeps.onReconnectHostDirectory
    val onConnectHostDirectory = deps.webSyncDeps.onConnectHostDirectory
    val onUnlinkHostDirectory = deps.webSyncDeps.onUnlinkHostDirectory
    val graphMergeService = deps.graphMergeService
    val mergePendingPageCount by graphMergeService.pendingPageCount.collectAsState()

    // Epic 2.3 (Task 2.3.1c): resolved here (not passed as raw StateFlow into StelekitViewModel,
    // unlike localChangesCountFlow) — FolderSyncStatusBadge is a pure sidebar-header composable,
    // not part of syncState, so collectAsState() directly feeds its call site below.
    val hostAccessState = hostAccessStateFlow?.collectAsState()?.value ?: HostAccessState.NotApplicable
    val hostWritePendingCount = hostWritePendingCountFlow?.collectAsState()?.value ?: 0
    // Epic 4.4 (Task 4.4.1c): SyncDegraded signal — see FolderSyncStatusBadge's state table.
    val hostWriteStuck = hostWriteStuckFlow?.collectAsState()?.value ?: false

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
    var llmRegistryRefreshToken by remember { androidx.compose.runtime.mutableStateOf(0) }
    val llmSettings = remember(platformSettings) { dev.stapler.stelekit.llm.LlmSettings(platformSettings) }
    val llmProviderRegistry = remember(llmCredentialStore, llmSettings, llmRegistryRefreshToken) {
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
    var storageMoveState by storageMoveController.stateState
    val storageMoveGraphName by storageMoveController.graphNameState
    val onStorageLocationChoose = storageMoveController.onStorageLocationChoose

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
    val onCreateVault = vaultActions.onCreateVault
    val onAddKeyslot = vaultActions.onAddKeyslot
    val onRemoveKeyslot = vaultActions.onRemoveKeyslot
    val onLockVault = vaultActions.onLockVault
    val onListActiveSlots = vaultActions.onListActiveSlots

    // See GraphContentGoogleAuth.kt — threaded into SettingsDialog via GraphDialogLayer.
    val googleAuthState = rememberGraphContentGoogleAuthState(googleAuthManager, scope)
    val isGoogleAuthenticated = googleAuthState.isAuthenticated
    val googleConnectedEmail = googleAuthState.connectedEmail
    val isGoogleConnecting = googleAuthState.isConnecting
    val googleAuthError = googleAuthState.authError
    val onConnectGoogle = googleAuthState.onConnect
    val onDisconnectGoogle = googleAuthState.onDisconnect

    var debugMenuState by remember {
        mutableStateOf(repos.debugFlagRepository?.loadDebugMenuState() ?: DebugMenuState())
    }

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
    val voiceCaptureState by voiceCaptureViewModel.state.collectAsState()
    val graphRegistry by graphManager.graphRegistry.collectAsState()
    val activeGraphId = graphRegistry.activeGraphId
    val syncState by viewModel.syncState.collectAsState()
    val gitLastSyncAt by viewModel.gitLastSyncAt.collectAsState()

    StelekitTheme(themeMode = appState.themeMode) {
        CompositionLocalProvider(LocalI18n provides I18n(appState.language)) {
            if (!appState.onboardingCompleted) {
                GraphContentOnboarding(fileSystem, graphManager, viewModel, scope)
            } else {
                val focusManager = LocalFocusManager.current
                if (isParanoidMode && vaultState !is VaultState.Unlocked) {
                    VaultUnlockScreen(
                        graphName = activeGraphInfo?.displayName ?: activeGraphPath,
                        vaultState = vaultState,
                        onUnlock = onVaultUnlock,
                    )
                } else {
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
                    var demoBannerDismissed by demoBannerDismissedState
                    // Story 2.2.1: new-graph "App storage" flow (Android-only for now — gated on
                    // fileSystem.supportsAppOwnedStorage). showNewGraphLocationPicker/
                    // pendingNewGraphAppOwnedPath are the picker dialog's own open-state and its
                    // pre-generated "App storage" candidate path (needed up front because
                    // UnifiedLocationPicker takes graphId as a constructor param, before the user
                    // has chosen a row). pendingPlainGraphWarning holds the resolved AppOwned
                    // location + path awaiting the ADR-003 warning's "Create anyway"/"Go back".
                    var showNewGraphLocationPicker by remember { mutableStateOf(false) }
                    var pendingNewGraphAppOwnedPath by remember { mutableStateOf("") }
                    // Set alongside the StorageLocation.SafFolder returned from the picker's
                    // onBrowseRequest — SafFolder.treeUri only carries the tree-root segment
                    // (see that lambda's comment), so the real picked path is stashed here rather
                    // than reconstructed from that shorter field.
                    var pendingNewGraphSafPath by remember { mutableStateOf("") }
                    // "New graph..." flow: name/description live here so they survive the location
                    // picker and the ADR-003 warning; newGraphFlow tells those shared dialogs to
                    // return here instead of creating/opening a graph directly.
                    var newGraphFlow by remember { mutableStateOf(false) }
                    var showNewGraphDialog by remember { mutableStateOf(false) }
                    var newGraphName by remember { mutableStateOf("") }
                    var newGraphDescription by remember { mutableStateOf("") }
                    var newGraphParent by remember { mutableStateOf("") }
                    var newGraphLocation by remember { mutableStateOf<StorageLocation?>(null) }
                    var newGraphPath by remember { mutableStateOf("") }
                    var pendingPlainGraphWarning by remember {
                        mutableStateOf<Pair<StorageLocation.AppOwned, String>?>(null)
                    }
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

                    // Shared by the sidebar's "New graph..." action and, on platforms with no
                    // "open an existing folder" concept (AddGraphFlowMode.ShowNameDialog), by
                    // "Open local folder..." too — see onAddGraph below.
                    fun startNewGraphFlow() {
                        newGraphFlow = true
                        newGraphName = ""
                        newGraphDescription = ""
                        newGraphLocation = null
                        newGraphPath = ""
                        if (addGraphFlowMode(fileSystem) == AddGraphFlowMode.ShowLocationPicker) {
                            pendingNewGraphAppOwnedPath = fileSystem.newAppOwnedGraphPath()
                            newGraphPath = pendingNewGraphAppOwnedPath
                            newGraphLocation = StorageLocation.AppOwned(
                                graphManager.graphIdFromPath(fileSystem.expandTilde(newGraphPath)).value
                            )
                        } else {
                            newGraphParent = fileSystem.getDefaultGraphPath().substringBeforeLast('/')
                        }
                        showNewGraphDialog = true
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
                                    mergePendingPageCount = mergePendingPageCount,
                                    graphMergeService = graphMergeService,
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
                                    onStartNewGraphFlow = { startNewGraphFlow() },
                                    onShowNewGraphLocationPicker = { appOwnedPath ->
                                        pendingNewGraphAppOwnedPath = appOwnedPath
                                        showNewGraphLocationPicker = true
                                    },
                                    scope = scope,
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
                                onLlmCredentialsChange = { llmRegistryRefreshToken++ },
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
                            ),
                            gitSync = GitSyncDeps(
                                gitSyncService = gitSyncService,
                                gitRepository = gitRepository,
                                gitConfigRepository = gitConfigRepository,
                                activeGraphId = activeGraphId?.value,
                                onCloneAndAdd = if (gitRepository != null) {
                                    { url, localPath, auth, location, displayName, description, onProgress ->
                                        graphManager.cloneAndAdd(gitRepository, url, localPath, auth, onProgress, location, displayName, description).map { it.value }
                                    }
                                } else null,
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
                            loadPageBlocks = { pageUuidStr -> repos.blockRepository.getBlocksForPage(dev.stapler.stelekit.model.PageUuid(pageUuidStr)) },
                            onDebugStateChange = { newState ->
                                debugMenuState = newState
                                viewModel.onDebugMenuStateChange(newState)
                            },
                        ),
                    )

                    // Story 2.2.1: replaces the immediate SAF-picker call in onAddGraph above,
                    // whenever fileSystem.supportsAppOwnedStorage (Android today) — see that
                    // callback's comment. createNewGraph is shared by both this picker's SafFolder
                    // branch (no warning needed) and the AppOwned warning's "Create anyway" below.
                    val createNewGraph: (String, StorageLocation?) -> Unit = { path, location ->
                        val name = newGraphName.takeIf { newGraphFlow }
                        val description = newGraphDescription.takeIf { newGraphFlow } ?: ""
                        scope.launch {
                            graphManager.switchGraph(graphManager.addGraph(path, location, name, description))
                            newGraphFlow = false
                        }
                    }
                    if (showNewGraphDialog) {
                        val nameOk = isValidGraphFolderName(newGraphName)
                        val newGraphMode = addGraphFlowMode(fileSystem)
                        NewGraphDialog(
                            name = newGraphName,
                            onNameChange = { newGraphName = it },
                            description = newGraphDescription,
                            onDescriptionChange = { newGraphDescription = it },
                            canCreate = nameOk && (newGraphMode != AddGraphFlowMode.ImmediateNativePicker || newGraphParent.isNotBlank()),
                            onDismiss = { showNewGraphDialog = false; newGraphFlow = false },
                            onCreate = {
                                showNewGraphDialog = false
                                val location = newGraphLocation
                                when {
                                    location is StorageLocation.AppOwned ->
                                        pendingPlainGraphWarning = location to newGraphPath
                                    location != null -> createNewGraph(newGraphPath, location)
                                    else -> {
                                        val path = newGraphPathFor(newGraphMode, newGraphParent, newGraphName) ?: return@NewGraphDialog
                                        if (newGraphMode == AddGraphFlowMode.ShowNameDialog) {
                                            createNewGraph(path, null)
                                        } else if (fileSystem.directoryExists(path)) {
                                            newGraphFlow = false
                                            viewModel.sendSnackbar("A folder named \"${newGraphName.trim()}\" already exists there")
                                        } else if (!fileSystem.createDirectory(path)) {
                                            newGraphFlow = false
                                            viewModel.sendSnackbar("Couldn't create $path")
                                        } else {
                                            createNewGraph(path, null)
                                        }
                                    }
                                }
                            },
                            location = {
                                if (newGraphMode == AddGraphFlowMode.ShowNameDialog) {
                                    Text(
                                        "Stored in your browser's private storage.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else if (newGraphMode == AddGraphFlowMode.ShowLocationPicker) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Save to", style = MaterialTheme.typography.labelMedium)
                                            Text(
                                                if (newGraphLocation is StorageLocation.AppOwned) "App storage (default)" else "Folder you picked",
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        }
                                        TextButton(onClick = {
                                            showNewGraphDialog = false
                                            showNewGraphLocationPicker = true
                                        }) { Text("Change…") }
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = newGraphParent,
                                        onValueChange = { newGraphParent = it },
                                        label = { Text("Parent folder") },
                                        supportingText = { Text("Creates a folder named after the graph inside it.") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        trailingIcon = {
                                            IconButton(onClick = {
                                                fileSystem.requestDirectoryPickerNow()
                                                scope.launch { fileSystem.pickDirectoryAsync()?.let { newGraphParent = it } }
                                            }) { Icon(Icons.Default.FolderOpen, contentDescription = "Browse for parent folder") }
                                        },
                                    )
                                }
                            },
                        )
                    }
                    if (showNewGraphLocationPicker) {
                        UnifiedLocationPicker(
                            title = "Choose where to keep this graph",
                            graphId = graphManager.graphIdFromPath(
                                fileSystem.expandTilde(pendingNewGraphAppOwnedPath)
                            ).value,
                            appStorageSubtitle = appStorageSubtitleFor(getDeviceInfo().platform),
                            platformCapabilities = fileSystem.supportsNativeDirectoryPicker,
                            onBrowseClick = {
                                // Must run synchronously here, not inside onBrowseRequest's
                                // scope.launch — see UnifiedLocationPicker's onBrowseClick doc.
                                fileSystem.requestDirectoryPickerNow()
                            },
                            onBrowseRequest = {
                                val path = fileSystem.pickDirectoryAsync()
                                path?.let {
                                    val expanded = fileSystem.expandTilde(it)
                                    pendingNewGraphSafPath = expanded
                                    // Only the tree-root segment — see the matching comment at
                                    // GitSetupScreen's own onBrowseRequest (Story 2.2.2).
                                    val treeUri = expanded.removePrefix("saf://").substringBefore("/")
                                    StorageLocation.SafFolder(graphManager.graphIdFromPath(expanded).value, treeUri)
                                }
                            },
                            onConfirm = { location ->
                                showNewGraphLocationPicker = false
                                if (newGraphFlow) {
                                    newGraphLocation = location
                                    newGraphPath = if (location is StorageLocation.SafFolder) pendingNewGraphSafPath else pendingNewGraphAppOwnedPath
                                    showNewGraphDialog = true
                                    return@UnifiedLocationPicker
                                }
                                when (location) {
                                    is StorageLocation.AppOwned ->
                                        // ADR-003: a plain (non-git) graph in AppOwned storage has
                                        // no backup at all — warn before creating, not after.
                                        pendingPlainGraphWarning = location to pendingNewGraphAppOwnedPath
                                    is StorageLocation.SafFolder ->
                                        createNewGraph(pendingNewGraphSafPath, location)
                                    else ->
                                        Unit
                                }
                            },
                            onDismiss = { showNewGraphLocationPicker = false; if (newGraphFlow) showNewGraphDialog = true },
                        )
                    }

                    pendingPlainGraphWarning?.let { (location, path) ->
                        val zipExporter = rememberGraphZipExporter()
                        // ADR-003's Amendment: Android and Web describe a different concrete
                        // consequence (uninstalling the app vs. clearing site data) — see
                        // getDeviceInfo's "platform" field convention (SloChecker.diskThresholdsFor).
                        val warningCopy = remember { getDeviceInfo().platform }.let { platform ->
                            if (platform == "Android") PLAIN_GRAPH_APP_OWNED_WARNING_ANDROID_COPY
                            else PLAIN_GRAPH_APP_OWNED_WARNING_WEB_COPY
                        }
                        PlainGraphAppOwnedWarningDialog(
                            bodyText = warningCopy,
                            onExportZip = zipExporter?.let { exporter ->
                                {
                                    exporter.export(fileSystem, path, "stelekit-graph").isRight()
                                }
                            },
                            onCreateAnyway = {
                                pendingPlainGraphWarning = null
                                createNewGraph(path, location)
                            },
                            onGoBack = {
                                // ux.md Surface 11: returns to an editable New-graph dialog rather
                                // than all the way back to the sidebar.
                                pendingPlainGraphWarning = null
                                if (newGraphFlow) showNewGraphDialog = true else showNewGraphLocationPicker = true
                            },
                        )
                    }

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
                } // vault unlocked else
            }
        }
    }
    } // CompositionLocalProvider(LocalSpanRecorder, LocalFileSystem)
}

/** Bundles [onGraphKeyEvent]'s per-shortcut callbacks (Parameter Object pattern). */
private data class GraphKeyEventHandlers(
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
 * whole composable tree, mirroring [addGraphFlowMode] above. Shared by this file's
 * `UnifiedLocationPicker` call site (new-graph flow) and `GitSetupScreen.kt`'s `Step2RepoPath`
 * call site. Uses the same [platform] string
 * convention as `pendingPlainGraphWarning`'s `warningCopy` above (see `getDeviceInfo`'s `platform`
 * field and `SloChecker.diskThresholdsFor`).
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
private fun onGraphKeyEvent(keyEvent: KeyEvent, handlers: GraphKeyEventHandlers): Boolean {
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

/** Fowler's Remove Flag Argument: replaces a boolean `isEncrypted` branched on in the body. */
internal enum class EncryptionState { ENCRYPTED, UNENCRYPTED }

@Composable
private fun RowScope.EncryptionStatus(state: EncryptionState) {
    val isEncrypted = state == EncryptionState.ENCRYPTED
    Icon(
        imageVector = if (isEncrypted) Icons.Default.Lock else Icons.Default.LockOpen,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
        tint = if (isEncrypted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(
        text = if (isEncrypted) t("status.encrypted") else t("status.not_encrypted"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
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
        EncryptionStatus(encryptionState)
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

