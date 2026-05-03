// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.migration.registerAllMigrations
import dev.stapler.stelekit.db.SidecarManager
import dev.stapler.stelekit.export.ExportService
import dev.stapler.stelekit.export.HtmlExporter
import dev.stapler.stelekit.export.JsonExporter
import dev.stapler.stelekit.export.MarkdownExporter
import dev.stapler.stelekit.export.PlainTextExporter
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.performance.DebugBuildConfig
import dev.stapler.stelekit.performance.DebugMenuState
import dev.stapler.stelekit.performance.LocalSpanRecorder
import dev.stapler.stelekit.performance.NavigationTracingEffect
import dev.stapler.stelekit.performance.NoOpSpanRecorder
import dev.stapler.stelekit.performance.PlatformJankStatsEffect
import dev.stapler.stelekit.performance.SpanRecorder
import dev.stapler.stelekit.platform.*
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.repository.*
import dev.stapler.stelekit.ui.components.*
import dev.stapler.stelekit.ui.components.settings.SettingsDialog
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.i18n.LocalI18n
import dev.stapler.stelekit.ui.i18n.t
import dev.stapler.stelekit.ui.onboarding.Onboarding
import dev.stapler.stelekit.ui.screens.AllPagesScreen
import dev.stapler.stelekit.ui.screens.AllPagesViewModel
import dev.stapler.stelekit.ui.screens.LibraryStatsScreen
import dev.stapler.stelekit.ui.screens.LibraryStatsViewModel
import dev.stapler.stelekit.stats.LibraryStatsProvider
import dev.stapler.stelekit.stats.NoOpLibraryStatsProvider
import dev.stapler.stelekit.ui.screens.GlobalUnlinkedReferencesScreen
import dev.stapler.stelekit.ui.screens.JournalsView
import dev.stapler.stelekit.ui.screens.JournalsViewModel
import dev.stapler.stelekit.ui.screens.LibrarySetupScreen
import dev.stapler.stelekit.ui.screens.PageView
import dev.stapler.stelekit.ui.screens.PermissionRecoveryScreen
import dev.stapler.stelekit.ui.screens.SearchViewModel
import dev.stapler.stelekit.domain.NoOpUrlFetcher
import dev.stapler.stelekit.domain.UrlFetcher
import dev.stapler.stelekit.voice.VoiceCaptureState
import dev.stapler.stelekit.voice.VoiceCaptureViewModel
import dev.stapler.stelekit.voice.VoicePipelineConfig
import dev.stapler.stelekit.voice.VoiceSettings
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Root Composable for the Logseq application.
 * Updated to use multi-graph support with GraphManager.
 */
@Composable
fun StelekitApp(
    fileSystem: FileSystem,
    graphPath: String,
    graphManager: GraphManager? = null,
    pluginHost: PluginHost = remember { PluginHost() },
    encryptionManager: EncryptionManager = remember { DefaultEncryptionManager() },
    urlFetcher: UrlFetcher = remember { NoOpUrlFetcher() },
    libraryStatsProvider: LibraryStatsProvider = NoOpLibraryStatsProvider,
    voicePipeline: VoicePipelineConfig = remember { VoicePipelineConfig() },
    voiceSettings: VoiceSettings? = null,
    onRebuildVoicePipeline: (() -> Unit)? = null,
    deviceSttAvailable: Boolean = false,
    deviceLlmAvailable: Boolean = false,
    spanRecorder: SpanRecorder = NoOpSpanRecorder,
    /** Called once the GraphManager instance is ready. Used by the host Activity for onTrimMemory. */
    onGraphManagerReady: ((GraphManager) -> Unit)? = null,
    /**
     * Registers a memory-pressure handler. The lambda receives a [() -> Unit] callback;
     * the host Activity should store it and invoke it when onTrimMemory fires. Mirrors the
     * [onGraphManagerReady] pattern.
     */
    onMemoryPressure: (((() -> Unit) -> Unit))? = null,
) {
    val platformSettings = remember { PlatformSettings() }
    val scope = rememberCoroutineScope()

    // Register all content migrations once before any graph is opened
    remember { registerAllMigrations() }

    // Create GraphManager - this owns all graph lifecycle
    val graphManager = graphManager ?: remember {
        GraphManager(platformSettings, DriverFactory(), fileSystem)
    }
    LaunchedEffect(graphManager) { onGraphManagerReady?.invoke(graphManager) }

    // Observe the active repository set
    val activeRepoSet by graphManager.activeRepositorySet.collectAsState()
    val graphRegistry by graphManager.graphRegistry.collectAsState()
    val activeGraphId = graphRegistry.activeGraphId

    // Track whether the one-shot UUID migration has completed for the active graph.
    // Reset to false whenever the active graph changes so the gate re-applies.
    // try/finally ensures migrationReady always returns to true even if the effect
    // is cancelled mid-run (e.g. activeGraphId changes twice in quick succession),
    // preventing the CircularProgressIndicator from spinning forever.
    var migrationReady by remember { mutableStateOf(false) }
    LaunchedEffect(activeGraphId) {
        migrationReady = false
        try {
            graphManager.awaitPendingMigration()
        } finally {
            migrationReady = true
        }
    }

    // Android SAF permission routing — reactive so state refreshes after folder pick.
    // Prefer the GraphManager's persisted active graph over the filesystem default so
    // we don't force a graph switch (and an unnecessary migration cycle) on every launch
    // when the user has already chosen a different graph.
    var currentGraphPath by remember {
        val persistedPath = graphManager.getActiveGraphInfo()?.path
        mutableStateOf(if (!persistedPath.isNullOrEmpty()) persistedPath else graphPath)
    }
    var permissionGranted by remember { mutableStateOf(fileSystem.hasStoragePermission()) }
    var folderPickError by remember { mutableStateOf<String?>(null) }

    val appLogger = remember { Logger("StelekitApp") }
    val isSafPath = currentGraphPath.startsWith("saf://")

    // ON_RESUME re-check: detect permission revoked mid-session (e.g. user cleared app storage
    // from Android Settings while the app was backgrounded). The wasGrantedBeforePause guard
    // prevents a spurious PermissionRecoveryScreen flash when returning from the folder picker,
    // which also causes an ON_PAUSE → ON_RESUME cycle.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var wasGrantedBeforePause = permissionGranted
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> wasGrantedBeforePause = permissionGranted
                Lifecycle.Event.ON_RESUME -> {
                    if (wasGrantedBeforePause) {
                        permissionGranted = fileSystem.hasStoragePermission()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Suspend callback used by both setup and recovery screens
    val onFolderPicked: suspend () -> Unit = {
        folderPickError = null
        appLogger.info("onFolderPicked: launching folder picker")
        val newPath = fileSystem.pickDirectoryAsync()
        appLogger.info("onFolderPicked: pickDirectoryAsync returned '$newPath'")
        if (newPath != null) {
            currentGraphPath = newPath
            val granted = fileSystem.hasStoragePermission()
            appLogger.info("onFolderPicked: hasStoragePermission=$granted after picking '$newPath'")
            permissionGranted = granted
            if (!granted) {
                folderPickError = "Folder selected but permission not granted. Try choosing the folder again."
            }
        } else {
            appLogger.info("onFolderPicked: picker returned null (cancelled or folder type not supported)")
            folderPickError = "No folder was selected. Please choose a local folder on your device (not Google Drive or cloud storage)."
        }
    }

    if (!permissionGranted) {
        StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
            if (isSafPath) {
                // Permission was revoked — show recovery screen
                PermissionRecoveryScreen(
                    folderName = fileSystem.getLibraryDisplayName(),
                    onReconnectFolder = { scope.launch { onFolderPicked() } },
                    onChooseDifferentFolder = { scope.launch { onFolderPicked() } },
                    errorMessage = folderPickError,
                )
            } else {
                // First launch — no folder chosen yet
                LibrarySetupScreen(
                    onChooseFolder = { scope.launch { onFolderPicked() } },
                    errorMessage = folderPickError
                )
            }
        }
        return
    }

    // Initialize with graph path if provided.
    // Always call addGraph so repositories are initialized — this is idempotent
    // (returns the existing ID if already registered) and handles reconnect after
    // SAF permission loss, where activeGraphId may be non-null from the persisted
    // registry but the in-memory repos have not been set up in this process.
    LaunchedEffect(currentGraphPath) {
        if (currentGraphPath.isNotEmpty()) {
            val graphId = graphManager.addGraph(currentGraphPath)
            graphManager.switchGraph(graphId)
        }
    }

    val notificationManager = remember { NotificationManager() }

    val repos = activeRepoSet

    if (repos == null || !migrationReady) {
        // Show loading state while repositories are being initialized or migration is running
        StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
            LoadingOverlay("Initializing…")
        }
        return
    }

    // Use key(graphId) to recreate ViewModels when graph changes
    key(activeGraphId) {
        GraphContent(
            repos = repos,
            fileSystem = fileSystem,
            platformSettings = platformSettings,
            pluginHost = pluginHost,
            encryptionManager = encryptionManager,
            graphManager = graphManager,
            notificationManager = notificationManager,
            urlFetcher = urlFetcher,
            libraryStatsProvider = libraryStatsProvider,
            voicePipeline = voicePipeline,
            voiceSettings = voiceSettings,
            onRebuildVoicePipeline = onRebuildVoicePipeline,
            deviceSttAvailable = deviceSttAvailable,
            deviceLlmAvailable = deviceLlmAvailable,
            spanRecorder = spanRecorder,
            onMemoryPressure = onMemoryPressure,
        )
    }
}

/**
 * Composition root for a single active graph.
 * Owns ViewModel creation and lifecycle wiring only — all UI is delegated
 * to focused child composables. Recreated via key(graphId) on graph switch.
 *
 * See ADR-001 for decomposition rationale.
 */
@Composable
private fun GraphContent(
    repos: RepositorySet,
    fileSystem: FileSystem,
    platformSettings: Settings,
    pluginHost: PluginHost,
    encryptionManager: EncryptionManager,
    graphManager: GraphManager,
    notificationManager: NotificationManager,
    urlFetcher: UrlFetcher = NoOpUrlFetcher(),
    libraryStatsProvider: LibraryStatsProvider = NoOpLibraryStatsProvider,
    voicePipeline: VoicePipelineConfig = VoicePipelineConfig(),
    voiceSettings: VoiceSettings? = null,
    onRebuildVoicePipeline: (() -> Unit)? = null,
    deviceSttAvailable: Boolean = false,
    deviceLlmAvailable: Boolean = false,
    spanRecorder: SpanRecorder = NoOpSpanRecorder,
    onMemoryPressure: (((() -> Unit) -> Unit))? = null,
) {
    CompositionLocalProvider(LocalSpanRecorder provides spanRecorder) {
    val scope = rememberCoroutineScope()
    val composeClipboard = LocalClipboardManager.current
    val clipboardProvider = rememberClipboardProvider(composeClipboard)
    val sidecarManager = remember {
        val graphPath = graphManager.getActiveGraphInfo()?.path
        if (graphPath != null) SidecarManager(fileSystem, graphPath) else null
    }
    val graphLoader = remember {
        GraphLoader(
            fileSystem,
            repos.pageRepository,
            repos.blockRepository,
            repos.journalService,
            externalWriteActor = repos.writeActor,
            backgroundPageRepository = repos.backgroundPageRepository,
            sidecarManager = sidecarManager,
            spanRepository = repos.spanRepository,
        ).also { it.onBulkImportComplete = repos.onBulkImportComplete }
    }
    val graphWriter = remember {
        GraphWriter(
            fileSystem,
            repos.writeActor,
            onFileWritten = graphLoader::markFileWrittenByUs,
            sidecarManager = sidecarManager,
        )
    }

    // Break the circular dependency: blockStateManager needs the graph path from viewModel,
    // and viewModel needs blockStateManager. We use a captured var so blockStateManager is
    // created first with a lazy lambda that resolves viewModel after both are initialised.
    var viewModelRef: StelekitViewModel? = null

    val blockStateManager = remember {
        dev.stapler.stelekit.ui.state.BlockStateManager(
            blockRepository = repos.blockRepository,
            graphLoader = graphLoader,
            graphWriter = graphWriter,
            pageRepository = repos.pageRepository,
            graphPathProvider = { viewModelRef?.uiState?.value?.currentGraphPath ?: "" }
        )
    }

    val exportService = remember {
        ExportService(
            exporters = listOf(
                MarkdownExporter(),
                PlainTextExporter(),
                HtmlExporter(),
                JsonExporter(),
            ),
            clipboard = clipboardProvider,
            blockRepository = repos.blockRepository,
        )
    }

    val viewModel = remember {
        StelekitViewModel(
            fileSystem,
            repos.pageRepository,
            repos.blockRepository,
            repos.searchRepository,
            graphLoader,
            graphWriter,
            platformSettings,
            journalService = repos.journalService,
            blockStateManager = blockStateManager,
            writeActor = repos.writeActor,
            undoManager = repos.undoManager,
            exportService = exportService,
            bugReportBuilder = repos.bugReportBuilder,
            debugFlagRepository = repos.debugFlagRepository,
            histogramWriter = repos.histogramWriter,
            ringBuffer = repos.ringBuffer,
        ).also {
            viewModelRef = it
            it.startAutoSave()
        }
    }

    // Keep the export service's clipboard in sync when the Compose clipboard changes
    // (e.g. after an activity recreation on Android).
    LaunchedEffect(clipboardProvider) {
        viewModel.setClipboardProvider(clipboardProvider)
    }

    // Register the memory-pressure handler so the host Activity can invoke it.
    LaunchedEffect(viewModel) {
        onMemoryPressure?.invoke { viewModel.onMemoryPressure() }
    }

    // Bootstrap loadGraph when the ViewModel has no persisted path but GraphManager has an
    // active graph. This happens when Onboarding completes via onComplete (not onGraphSelected),
    // or when lastGraphPath was never written to SharedPreferences.
    LaunchedEffect(Unit) {
        if (viewModel.uiState.value.currentGraphPath.isEmpty()) {
            val path = graphManager.getActiveGraphInfo()?.path
            if (!path.isNullOrEmpty()) {
                viewModel.setGraphPath(path)
            }
        }
    }

    val frameMetricState = remember { kotlinx.coroutines.flow.MutableStateFlow(dev.stapler.stelekit.performance.FrameMetric()) }

    var debugMenuState by remember {
        mutableStateOf(repos.debugFlagRepository?.loadDebugMenuState() ?: DebugMenuState())
    }

    PlatformJankStatsEffect(
        histogramWriter = repos.histogramWriter,
        isEnabled = debugMenuState.isJankStatsEnabled,
    )

    androidx.compose.runtime.LaunchedEffect(repos.histogramWriter) {
        var lastNanos = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    val durationMs = (nanos - lastNanos) / 1_000_000L
                    // Only record when a frame was actively rendered. withFrameNanos fires on
                    // demand — when the app is idle Compose can skip frames for hundreds of ms.
                    // Gaps > 100ms are idle sleeps, not slow renders; recording them inflates
                    // the histogram and triggers false jank alerts.
                    if (durationMs in 1L..100L) {
                        repos.histogramWriter?.record("frame_duration", durationMs)
                        val isJank = durationMs > 32L
                        frameMetricState.value = dev.stapler.stelekit.performance.FrameMetric(durationMs, isJank)
                        if (isJank) {
                            repos.ringBuffer?.record(
                                dev.stapler.stelekit.performance.SerializedSpan(
                                    name = "jank_frame",
                                    startEpochMs = dev.stapler.stelekit.performance.HistogramWriter.epochMs() - durationMs,
                                    endEpochMs = dev.stapler.stelekit.performance.HistogramWriter.epochMs(),
                                    durationMs = durationMs,
                                    attributes = mapOf("frame.duration_ms" to durationMs.toString()),
                                )
                            )
                        }
                    }
                }
                lastNanos = nanos
            }
        }
    }

    val journalsViewModel = remember {
        JournalsViewModel(repos.journalService, blockStateManager)
    }
    val voiceCaptureViewModel = remember(voicePipeline) {
        VoiceCaptureViewModel(
            voicePipeline,
            repos.journalService,
            currentOpenPageUuid = { viewModel.uiState.value.currentPage?.uuid },
        )
    }
    DisposableEffect(voiceCaptureViewModel) {
        onDispose { voiceCaptureViewModel.close() }
    }

    // Force-flush pending writes on Android lifecycle pause/stop.
    // Keyed on voiceCaptureViewModel so the observer is re-registered whenever the VM is
    // recreated (e.g. after voicePipeline changes), preventing calls on a stale closed instance.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, voiceCaptureViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                viewModel.savePendingChanges()
                scope.launch { blockStateManager.flush() }
                voiceCaptureViewModel.cancel()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val allPagesViewModel = remember {
        AllPagesViewModel(repos.pageRepository, repos.blockRepository)
    }
    val libraryStatsViewModel = remember {
        LibraryStatsViewModel(libraryStatsProvider, graphManager.getActiveGraphInfo()?.path ?: "")
    }
    val searchViewModel = remember {
        SearchViewModel(repos.searchRepository)
    }

    // Cancel all ViewModel scopes when GraphContent leaves composition (key(activeGraphId) re-keys).
    // Without this, orphaned scopes from the previous composition keep running concurrently,
    // causing duplicate graph loads and write-actor contention (batchDeleteBlocks slowdowns).
    DisposableEffect(viewModel) {
        onDispose {
            blockStateManager.close()
            journalsViewModel.close()
            allPagesViewModel.close()
            libraryStatsViewModel.close()
            searchViewModel.close()
            viewModel.close()
        }
    }

    val appState by viewModel.uiState.collectAsState()
    val voiceCaptureState by voiceCaptureViewModel.state.collectAsState()
    val graphRegistry by graphManager.graphRegistry.collectAsState()
    val activeGraphInfo = graphManager.getActiveGraphInfo()
    val activeGraphId = graphRegistry.activeGraphId

    StelekitTheme(themeMode = appState.themeMode) {
        CompositionLocalProvider(LocalI18n provides I18n(appState.language)) {
            if (!appState.onboardingCompleted) {
                Onboarding(
                    fileSystem = fileSystem,
                    onComplete = { viewModel.setOnboardingCompleted(true) },
                    onGraphSelected = { path ->
                        scope.launch {
                            val graphId = graphManager.addGraph(path)
                            if (graphId != null) {
                                // Persist BEFORE switchGraph — switchGraph updates activeGraphId which
                                // triggers key(activeGraphId) to destroy and recreate GraphContent
                                // along with its scope. The new ViewModel reads these persisted values
                                // in its init block and calls loadGraph automatically.
                                viewModel.setGraphPath(path)
                                viewModel.setOnboardingCompleted(true)
                                graphManager.switchGraph(graphId)
                            }
                        }
                    }
                )
            } else {
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
                                onCommandPalette = { viewModel.setCommandPaletteVisible(true) },
                                onSearch = { viewModel.setSearchDialogVisible(true) },
                                onToggleSidebar = { viewModel.toggleSidebar() },
                                onToggleRightSidebar = { viewModel.toggleRightSidebar() },
                                onSettings = { viewModel.setSettingsVisible(true) },
                                onUndo = { journalsViewModel.undo() },
                                onRedo = { journalsViewModel.redo() },
                                onBack = { viewModel.goBack() },
                                onForward = { viewModel.goForward() },
                                onDebugMenu = { viewModel.showDebugMenu() }
                            )
                        }
                ) {
                    val windowSizeClass = windowSizeClassFor(maxWidth)
                    val isMobile = windowSizeClass.isMobile

                    CompositionLocalProvider(
                        LocalWindowSizeClass provides windowSizeClass,
                        LocalOpenSearchWithText provides { text -> viewModel.setSearchDialogVisible(true, text) }
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
                                onExportPage = { formatId -> viewModel.exportPage(formatId) },
                                onShowDebugMenu = if (DebugBuildConfig.isDebugBuild) {{ viewModel.showDebugMenu() }} else null,
                            )
                        },
                        leftSidebar = {
                            LeftSidebar(
                                expanded = appState.sidebarExpanded,
                                isLoading = appState.isLoading,
                                favoritePages = appState.favoritePages,
                                recentPages = appState.recentPages,
                                currentScreen = appState.currentScreen,
                                currentGraphName = activeGraphInfo?.displayName ?: "",
                                availableGraphs = graphRegistry.graphs,
                                activeGraphId = activeGraphId,
                                onPageClick = { page ->
                                    viewModel.navigateTo(Screen.PageView(page))
                                    closeSidebarIfMobile()
                                },
                                onNavigate = { route ->
                                    viewModel.navigateTo(route)
                                    closeSidebarIfMobile()
                                },
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onGraphSelected = { id ->
                                    scope.launch { graphManager.switchGraph(id) }
                                    closeSidebarIfMobile()
                                },
                                onAddGraph = {
                                    val selectedPath = fileSystem.pickDirectory()
                                    if (selectedPath != null) {
                                        scope.launch {
                                            val newGraphId = graphManager.addGraph(selectedPath)
                                            newGraphId?.let { graphManager.switchGraph(it) }
                                        }
                                    }
                                    closeSidebarIfMobile()
                                },
                                onRemoveGraph = { scope.launch { graphManager.removeGraph(it) } },
                                onCollapse = { viewModel.toggleSidebar() }
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
                            ScreenRouter(
                                screen = appState.currentScreen,
                                repos = repos,
                                blockStateManager = blockStateManager,
                                journalsViewModel = journalsViewModel,
                                allPagesViewModel = allPagesViewModel,
                                libraryStatsViewModel = libraryStatsViewModel,
                                viewModel = viewModel,
                                searchViewModel = searchViewModel,
                                notificationManager = notificationManager,
                                appState = appState,
                                graphWriter = graphWriter,
                                urlFetcher = urlFetcher,
                            )
                        },
                        statusBar = {
                            if (!isMobile) {
                                StatusBarContent(
                                    isEncrypted = encryptionManager.isEncryptionEnabled(appState.currentGraphPath),
                                    statusMessage = appState.statusMessage,
                                    activeGraphName = activeGraphInfo?.displayName ?: "",
                                    pluginCount = pluginHost.getAllPlugins().size
                                )
                            }
                        },
                        bottomBar = {
                            PlatformBottomBar(
                                currentScreen = appState.currentScreen,
                                onNavigate = { screen ->
                                    viewModel.navigateTo(screen)
                                    closeSidebarIfMobile()
                                },
                                onSearch = { viewModel.setSearchDialogVisible(true) },
                                isLeftHanded = appState.isLeftHanded,
                                voiceCaptureButton = {
                                    VoiceCaptureButton(
                                        state = voiceCaptureState,
                                        onTap = { voiceCaptureViewModel.onMicTapped() },
                                        onDismissError = { voiceCaptureViewModel.dismissError() },
                                        onAutoReset = { voiceCaptureViewModel.resetToIdle() },
                                        amplitudeFlow = voicePipeline.effectiveAmplitudeFlow,
                                    )
                                },
                            )
                        }
                    )

                    GraphDialogLayer(
                        appState = appState,
                        searchViewModel = searchViewModel,
                        viewModel = viewModel,
                        notificationManager = notificationManager,
                        fileSystem = fileSystem,
                        voiceSettings = voiceSettings,
                        onRebuildVoicePipeline = onRebuildVoicePipeline,
                        deviceSttAvailable = deviceSttAvailable,
                        deviceLlmAvailable = deviceLlmAvailable,
                        frameMetric = frameMetricState,
                        debugState = debugMenuState,
                        onDebugStateChange = { newState ->
                            debugMenuState = newState
                            viewModel.onDebugMenuStateChange(newState)
                        },
                    )

                    } // CompositionLocalProvider(LocalWindowSizeClass)
                }
            }
        }
    }
    } // CompositionLocalProvider(LocalSpanRecorder)
}

/**
 * Pure function — handles keyboard shortcuts for the graph content area.
 * Extracted from GraphContent to make shortcut logic testable without a Compose runtime.
 * See ADR-001.
 */
private fun onGraphKeyEvent(
    keyEvent: KeyEvent,
    onCommandPalette: () -> Unit,
    onSearch: () -> Unit,
    onToggleSidebar: () -> Unit,
    onToggleRightSidebar: () -> Unit,
    onSettings: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onDebugMenu: () -> Unit = {},
): Boolean {
    if (keyEvent.type != KeyEventType.KeyDown) return false
    val isMod = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
    val isShift = keyEvent.isShiftPressed
    return when {
        isMod && isShift && keyEvent.key == Key.P -> { onCommandPalette(); true }
        isMod && keyEvent.key == Key.K -> { onSearch(); true }
        isMod && isShift && keyEvent.key == Key.B -> { onToggleRightSidebar(); true }
        isMod && keyEvent.key == Key.B -> { onToggleSidebar(); true }
        isMod && keyEvent.key == Key.Comma -> { onSettings(); true }
        isMod && !isShift && keyEvent.key == Key.Z -> { onUndo(); true }
        (isMod && isShift && keyEvent.key == Key.Z) || (isMod && keyEvent.key == Key.Y) -> { onRedo(); true }
        (keyEvent.isAltPressed && keyEvent.key == Key.DirectionLeft) ||
        (isMod && keyEvent.key == Key.LeftBracket) -> { onBack(); true }
        (keyEvent.isAltPressed && keyEvent.key == Key.DirectionRight) ||
        (isMod && keyEvent.key == Key.RightBracket) -> { onForward(); true }
        isMod && isShift && keyEvent.key == Key.D -> { onDebugMenu(); true }
        else -> false
    }
}

/**
 * Routes the current [Screen] to its composable. Owns screen transition animations.
 * Forward navigation (historyIndex increases): slide in from right.
 * Back navigation (historyIndex decreases): slide in from left, suppress exit so the
 * system predictive-back preview is the only visual during the back swipe.
 *
 * Rule (ADR-001): every branch must call a named composable — no inline content.
 */
@Composable
private fun ScreenRouter(
    screen: Screen,
    repos: RepositorySet,
    blockStateManager: dev.stapler.stelekit.ui.state.BlockStateManager,
    journalsViewModel: JournalsViewModel,
    allPagesViewModel: AllPagesViewModel,
    libraryStatsViewModel: LibraryStatsViewModel,
    viewModel: StelekitViewModel,
    searchViewModel: SearchViewModel,
    notificationManager: NotificationManager,
    appState: AppState,
    graphWriter: GraphWriter,
    urlFetcher: UrlFetcher = NoOpUrlFetcher(),
) {
    if (appState.isLoading) {
        LoadingOverlay(
            message = if (appState.statusMessage.isNotEmpty()) appState.statusMessage
                      else "Loading your notes…"
        )
        return
    }

    val suggestionMatcher by viewModel.suggestionMatcher.collectAsState()

    // Track navigation direction for slide transition orientation.
    var previousHistoryIndex by remember { mutableStateOf(appState.historyIndex) }
    val isBack = appState.historyIndex < previousHistoryIndex

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            if (isBack) {
                // Back: enter from left; suppress exit (predictive back handles the visual)
                (slideInHorizontally(tween(300)) { -it / 4 } + fadeIn(tween(300))) togetherWith
                        fadeOut(tween(1)) // near-instant exit — system back gesture owns the animation
            } else {
                // Forward: enter from right, exit to left
                (slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(300))) togetherWith
                        (slideOutHorizontally(tween(300)) { -it / 4 } + fadeOut(tween(300)))
            }
        },
        label = "screen-transition"
    ) { currentScreen ->
        // Update direction tracking after composition
        previousHistoryIndex = appState.historyIndex

        when (currentScreen) {
            is Screen.PageView -> PageView(
                page = currentScreen.page,
                blockRepository = repos.blockRepository,
                pageRepository = repos.pageRepository,
                blockStateManager = blockStateManager,
                currentGraphPath = appState.currentGraphPath,
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                onRefresh = { viewModel.refreshCurrentPage() },
                onLinkClick = { viewModel.navigateToPageByName(it) },
                viewModel = viewModel,
                searchViewModel = searchViewModel,
                writeActor = repos.writeActor,
                isDebugMode = appState.isDebugMode,
                isLeftHanded = appState.isLeftHanded
            )
            is Screen.Journals -> JournalsView(
                viewModel = journalsViewModel,
                blockRepository = repos.blockRepository,
                isDebugMode = appState.isDebugMode,
                onLinkClick = { viewModel.navigateToPageByName(it) },
                searchViewModel = searchViewModel,
                onSearchPages = { query -> viewModel.searchPages(query) },
                suggestionMatcher = suggestionMatcher,
                isLeftHanded = appState.isLeftHanded
            )
            is Screen.Flashcards -> {
                NavigationTracingEffect("Flashcards")
                FlashcardsScreen(blockStateManager)
            }
            is Screen.AllPages -> AllPagesScreen(
                viewModel = allPagesViewModel,
                onPageClick = { page -> viewModel.navigateTo(Screen.PageView(page)) },
                onBulkDelete = { uuids -> viewModel.bulkDeletePages(uuids) }
            )
            is Screen.LibraryStats -> LibraryStatsScreen(viewModel = libraryStatsViewModel)
            is Screen.Notifications -> {
                NavigationTracingEffect("Notifications")
                NotificationHistory(notificationManager)
            }
            is Screen.Logs -> {
                NavigationTracingEffect("Logs")
                LogDashboard()
            }
            is Screen.Performance -> {
                NavigationTracingEffect("Performance")
                PerformanceDashboard(
                    histogramWriter = repos.histogramWriter,
                    ringBuffer = repos.ringBuffer,
                    spanRepository = repos.spanRepository,
                    perfExporter = repos.perfExporter,
                )
            }
            is Screen.GlobalUnlinkedReferences -> GlobalUnlinkedReferencesScreen(
                pageRepository = repos.pageRepository,
                blockRepository = repos.blockRepository,
                writeActor = repos.writeActor,
                graphPath = appState.currentGraphPath,
                suggestionMatcher = suggestionMatcher,
                onNavigateTo = { viewModel.navigateTo(it) },
            )
            is Screen.Import -> {
                val graphPath = appState.currentGraphPath
                val importScope = rememberCoroutineScope()
                val importViewModel = remember(graphPath) {
                    dev.stapler.stelekit.ui.screens.ImportViewModel(
                        coroutineScope = importScope,
                        pageRepository = repos.pageRepository,
                        graphWriter = graphWriter,
                        graphPath = graphPath,
                        urlFetcher = urlFetcher,
                        matcherFlow = viewModel.suggestionMatcher,
                    )
                }
                dev.stapler.stelekit.ui.screens.ImportScreen(
                    viewModel = importViewModel,
                    onDismiss = {
                        val savedName = importViewModel.state.value.savedPageName
                        viewModel.goBack()
                        if (savedName != null) {
                            viewModel.navigateToPageByName(savedName)
                        }
                    },
                )
            }
        }
    }
}

/**
 * All overlay dialogs for the graph content area, composed as a single layer.
 * Extracted from GraphContent so dialog additions don't modify the layout tree.
 * See ADR-001.
 */
@Composable
private fun GraphDialogLayer(
    appState: AppState,
    searchViewModel: SearchViewModel,
    viewModel: StelekitViewModel,
    notificationManager: NotificationManager,
    fileSystem: FileSystem,
    voiceSettings: VoiceSettings? = null,
    onRebuildVoicePipeline: (() -> Unit)? = null,
    deviceSttAvailable: Boolean = false,
    deviceLlmAvailable: Boolean = false,
    frameMetric: kotlinx.coroutines.flow.StateFlow<dev.stapler.stelekit.performance.FrameMetric>,
    debugState: DebugMenuState = DebugMenuState(),
    onDebugStateChange: (DebugMenuState) -> Unit = {},
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
        isIndexing = indexingProgress is IndexingState.InProgress
    )

    SettingsDialog(
        visible = appState.settingsVisible,
        onDismiss = { viewModel.setSettingsVisible(false) },
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
        onRebuildVoicePipeline = onRebuildVoicePipeline,
        deviceSttAvailable = deviceSttAvailable,
        deviceLlmAvailable = deviceLlmAvailable,
    )

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

/**
 * Status bar row — pure presentational composable. Receives only primitives;
 * no ViewModel dependency. See ADR-001.
 */
@Composable
private fun StatusBarContent(
    isEncrypted: Boolean,
    statusMessage: String,
    activeGraphName: String,
    pluginCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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

@Composable
private fun FlashcardsScreen(blockStateManager: dev.stapler.stelekit.ui.state.BlockStateManager) {
    val allBlocks by blockStateManager.blocks.collectAsState()
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    // Find all blocks with card:: true property that are due today
    val dueCards = remember(allBlocks) {
        allBlocks.values.flatten()
            .filter { block ->
                block.properties["card"] == "true" &&
                    (block.properties["card-next-review"].let { dateStr ->
                        if (dateStr == null) true  // new card, always due
                        else runCatching { LocalDate.parse(dateStr) }.getOrNull()?.let { it <= today } ?: true
                    })
            }
    }

    var currentIndex by remember(dueCards) { mutableStateOf(0) }
    var showBack by remember(currentIndex) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun onPass() {
        val card = dueCards.getOrNull(currentIndex) ?: return
        val ease = card.properties["card-ease"]?.toDoubleOrNull() ?: 2.5
        val interval = card.properties["card-interval"]?.toIntOrNull() ?: 1
        val newInterval = maxOf(1, (interval * ease).toInt())
        val newEase = minOf(2.5, ease + 0.1)
        val nextReview = today.plus(newInterval, DateTimeUnit.DAY)
        val newProps = card.properties.toMutableMap().apply {
            put("card-next-review", nextReview.toString())
            put("card-ease", kotlin.math.round(newEase * 100).toLong().let { "${it / 100}.${(it % 100).toString().padStart(2, '0')}" })
            put("card-interval", newInterval.toString())
        }
        scope.launch { blockStateManager.updateBlockProperties(card.uuid, newProps) }
        showBack = false
        currentIndex++
    }

    fun onFail() {
        val card = dueCards.getOrNull(currentIndex) ?: return
        val ease = card.properties["card-ease"]?.toDoubleOrNull() ?: 2.5
        val newEase = maxOf(1.3, ease - 0.2)
        val nextReview = today.plus(1, DateTimeUnit.DAY)
        val newProps = card.properties.toMutableMap().apply {
            put("card-next-review", nextReview.toString())
            put("card-ease", kotlin.math.round(newEase * 100).toLong().let { "${it / 100}.${(it % 100).toString().padStart(2, '0')}" })
            put("card-interval", "1")
        }
        scope.launch { blockStateManager.updateBlockProperties(card.uuid, newProps) }
        showBack = false
        currentIndex++
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Flashcards",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "${minOf(currentIndex, dueCards.size)} / ${dueCards.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (dueCards.isEmpty()) {
            // Empty state
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Style,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No cards due for review",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tag a block with card:: true to create a flashcard",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else if (currentIndex >= dueCards.size) {
            // All done state
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "All done!",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You've reviewed all ${dueCards.size} cards",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = { currentIndex = 0 }) {
                        Text("Review again")
                    }
                }
            }
        } else {
            val card = dueCards[currentIndex]

            // Card with swipe gesture
            var offsetX by remember(currentIndex) { mutableStateOf(0f) }
            val swipeThreshold = 200f

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.7f)
                        .offset { IntOffset(offsetX.roundToInt() / 4, 0) }
                        .pointerInput(currentIndex) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    when {
                                        offsetX > swipeThreshold -> onPass()
                                        offsetX < -swipeThreshold -> onFail()
                                        else -> offsetX = 0f
                                    }
                                },
                                onDragCancel = { offsetX = 0f }
                            ) { _, dragAmount -> offsetX += dragAmount }
                        }
                        .clickable { showBack = !showBack },
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            offsetX > 80f -> MaterialTheme.colorScheme.primaryContainer
                            offsetX < -80f -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surface
                        }
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Front: strip properties from content display
                            val displayContent = card.content.lines()
                                .filterNot { it.contains("::") }
                                .joinToString("\n")
                                .trim()
                            Text(
                                displayContent.ifBlank { card.content },
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            if (!showBack) {
                                Spacer(Modifier.height(24.dp))
                                Text(
                                    "Tap to reveal",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            } else {
                                // Back: show child blocks hint (we don't have them here easily, show "Answer revealed")
                                Spacer(Modifier.height(16.dp))
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                Text(
                                    "How did you do?",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Pass/Fail buttons (shown after revealing back)
            if (showBack) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = ::onFail,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Again")
                    }
                    Button(
                        onClick = ::onPass,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Good")
                    }
                }
            } else {
                // Swipe hint
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("← Fail", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                    Text("Pass →", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                }
            }
        }
    }
}

