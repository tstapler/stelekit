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
import dev.stapler.stelekit.ui.components.git.GitDetectionBanner
import dev.stapler.stelekit.ui.components.settings.SettingsDialog
import dev.stapler.stelekit.ui.rememberShareProvider
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.i18n.LocalI18n
import dev.stapler.stelekit.ui.i18n.t
import dev.stapler.stelekit.ui.onboarding.Onboarding
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel
import dev.stapler.stelekit.ui.annotate.AnnotationEditorScreen
import dev.stapler.stelekit.ui.gallery.GalleryScreen
import dev.stapler.stelekit.ui.gallery.GalleryViewModel
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
import dev.stapler.stelekit.ui.screens.VaultUnlockScreen
import dev.stapler.stelekit.vault.VaultManager.VaultEvent
import dev.stapler.stelekit.domain.NoOpUrlFetcher
import dev.stapler.stelekit.domain.UrlFetcher
import dev.stapler.stelekit.voice.VoiceCaptureState
import dev.stapler.stelekit.voice.VoiceCaptureViewModel
import dev.stapler.stelekit.voice.VoicePipelineConfig
import dev.stapler.stelekit.voice.VoiceSettings
import dev.stapler.stelekit.tags.LlmTagProvider
import dev.stapler.stelekit.tags.TagSettings
import dev.stapler.stelekit.tags.TagSuggestionEngine
import dev.stapler.stelekit.tags.TagSuggestionViewModel
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import kotlin.math.roundToInt
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.performance.PercentileSummary
import dev.stapler.stelekit.performance.QueryStat
import dev.stapler.stelekit.performance.SerializedSpan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import arrow.core.Either
import dev.stapler.stelekit.db.ImageImportService
import dev.stapler.stelekit.error.toUiMessage
import dev.stapler.stelekit.model.ImageSource
import dev.stapler.stelekit.platform.sensor.CameraProvider
import dev.stapler.stelekit.platform.sensor.SensorModule

internal suspend fun executeCaptureAndImport(
    imageImportService: ImageImportService?,
    getActiveGraphPath: () -> String?,
    pageUuid: String,
    navigateAfterImport: Boolean,
    cameraProvider: CameraProvider = SensorModule.cameraProvider,
    onSnackbar: (String) -> Unit,
    onNavigate: (annotationUuid: String, pageUuid: String) -> Unit,
    onWarn: (String) -> Unit,
) {
    val service = imageImportService ?: run {
        onWarn("captureAndImport called with no imageImportService")
        return
    }
    val graphPath = getActiveGraphPath()?.takeIf { it.isNotEmpty() } ?: run {
        onWarn("captureAndImport called with no active graph path")
        return
    }
    when (val captured = cameraProvider.capturePhoto()) {
        is Either.Left -> {
            onWarn("Camera capture failed: ${captured.value.message}")
            onSnackbar(captured.value.toUiMessage())
        }
        is Either.Right -> {
            val result = service.import(
                tempFile = captured.value,
                graphPath = graphPath,
                pageUuid = pageUuid,
                source = ImageSource.CAMERA,
                insertToJournalPage = false,
            )
            result.onLeft { err ->
                onWarn("Camera image import failed: ${err.message}")
                onSnackbar(err.toUiMessage())
            }
            if (navigateAfterImport) {
                result.onRight { annotation ->
                    onNavigate(annotation.uuid, pageUuid)
                }
            }
        }
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
    /**
     * Platform-specific git implementation. Pass [JvmGitRepository] on Desktop,
     * [AndroidGitRepository] on Android. When null, git sync is disabled.
     */
    gitRepository: dev.stapler.stelekit.git.GitRepository? = null,
    /**
     * Platform-specific crypto engine for paranoid-mode vault operations.
     * Pass [JvmCryptoEngine] on Desktop. Android support is pending an AndroidCryptoEngine.
     * When null, paranoid mode is unavailable.
     */
    cryptoEngine: dev.stapler.stelekit.vault.CryptoEngine? = null,
    /**
     * Platform-specific media attachment service. When non-null the attach-image button
     * is shown in [MobileBlockToolbar] on the PageView screen.
     *
     * Pass [JvmMediaAttachmentService] on Desktop.
     * Pass the Android service (from [rememberAndroidMediaAttachmentService]) on Android.
     * Pass null (default) to hide the button entirely.
     */
    attachmentService: dev.stapler.stelekit.service.MediaAttachmentService? = null,
    /**
     * Platform-specific Google OAuth manager. When non-null the Google Account settings
     * panel becomes interactive (Connect / Disconnect buttons are wired up).
     *
     * Pass [AndroidGoogleAuthManager] on Android, [JvmGoogleAuthManager] on Desktop.
     * When null (default), the panel is rendered but the buttons are no-ops.
     */
    googleAuthManager: dev.stapler.stelekit.platform.google.GoogleAuthManager? = null,
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

    // Show the setup/recovery screen only when SAF permission is required:
    //  - SAF path with no/revoked permission → PermissionRecoveryScreen
    //  - No path at all (first launch, no folder ever chosen) → LibrarySetupScreen
    // Non-SAF paths (e.g. /data/local/tmp/ benchmark paths or desktop paths) don't
    // require a document-tree grant, so skip the screen for those.
    if (!permissionGranted && (isSafPath || currentGraphPath.isEmpty())) {
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
            gitRepository = gitRepository,
            cryptoEngine = cryptoEngine,
            attachmentService = attachmentService,
            googleAuthManager = googleAuthManager,
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
    gitRepository: dev.stapler.stelekit.git.GitRepository? = null,
    cryptoEngine: dev.stapler.stelekit.vault.CryptoEngine? = null,
    attachmentService: dev.stapler.stelekit.service.MediaAttachmentService? = null,
    googleAuthManager: dev.stapler.stelekit.platform.google.GoogleAuthManager? = null,
) {
    CompositionLocalProvider(LocalSpanRecorder provides spanRecorder) {
    val scope = rememberCoroutineScope()
    val graphContentLogger = remember { Logger("GraphContent") }
    val composeClipboard = LocalClipboardManager.current
    val clipboardProvider = rememberClipboardProvider(composeClipboard)

    val activeGraphInfo = remember { graphManager.getActiveGraphInfo() }
    val activeGraphPath = activeGraphInfo?.path ?: ""

    // Paranoid mode: true when a .stele-vault file exists for this graph and a crypto engine is available.
    var isParanoidMode by remember {
        androidx.compose.runtime.mutableStateOf(
            cryptoEngine != null && activeGraphPath.isNotEmpty() &&
                fileSystem.fileExists(dev.stapler.stelekit.vault.VaultManager.vaultFilePath(activeGraphPath))
        )
    }

    // Vault state drives the unlock screen and gates graph loading.
    var vaultState by remember {
        androidx.compose.runtime.mutableStateOf<VaultState>(
            if (isParanoidMode) VaultState.Locked else VaultState.Unlocked(dev.stapler.stelekit.vault.VaultNamespace.OUTER)
        )
    }

    var vaultManager by remember {
        androidx.compose.runtime.mutableStateOf(
            if (!isParanoidMode || cryptoEngine == null) null
            else dev.stapler.stelekit.vault.VaultManager(
                crypto = cryptoEngine,
                fileReadBytes = { path -> fileSystem.readFileBytes(path) },
                fileWriteBytes = { path, data -> fileSystem.writeFileBytes(path, data) },
            )
        )
    }

    // Vault-integrated credential store — non-null when paranoid mode is on and CryptoEngine is available.
    // Swapped into gitRepository.credentialAccess on vault unlock/lock.
    val vaultCredentialStore = remember(activeGraphPath, isParanoidMode, cryptoEngine) {
        if (isParanoidMode && cryptoEngine != null && activeGraphPath.isNotEmpty()) {
            dev.stapler.stelekit.git.VaultCredentialStore(activeGraphPath, cryptoEngine, fileSystem)
        } else null
    }

    LaunchedEffect(vaultCredentialStore) {
        graphManager.registerVaultCredentialStore(vaultCredentialStore)
    }

    val sidecarManager = remember {
        val graphPath = activeGraphPath.ifEmpty { null }
        if (graphPath != null) SidecarManager(fileSystem, graphPath) else null
    }
    val imageSidecarManager = remember {
        if (activeGraphPath.isNotEmpty()) dev.stapler.stelekit.db.sidecar.ImageSidecarManager(fileSystem) else null
    }
    val imageImportService = remember(imageSidecarManager) {
        if (imageSidecarManager != null && activeGraphPath.isNotEmpty()) {
            dev.stapler.stelekit.db.ImageImportService(
                fileSystem = fileSystem,
                imageAnnotationRepository = repos.imageAnnotationRepository,
                blockRepository = repos.blockRepository,
                sidecarManager = imageSidecarManager,
                journalService = repos.journalService,
                writeActor = repos.writeActor,
                measurementAnnotationRepository = repos.measurementAnnotationRepository,
            )
        } else null
    }
    LaunchedEffect(activeGraphPath) {
        if (activeGraphPath.isNotEmpty() && imageSidecarManager != null) {
            // Only rebuild if DB has no annotations — avoids full-scan on every open
            val hasExisting = repos.imageAnnotationRepository.getAllImageAnnotations()
                .first()
                .getOrNull()
                ?.isNotEmpty() == true
            if (!hasExisting) {
                dev.stapler.stelekit.db.sidecar.ImageSidecarIndexer(
                    fileSystem = fileSystem,
                    imageAnnotationRepository = repos.imageAnnotationRepository,
                    measurementAnnotationRepository = repos.measurementAnnotationRepository,
                ).rebuildFromSidecars(activeGraphPath)
                    .onLeft { err -> graphContentLogger.warn("Sidecar reindex failed: ${err.message}") }
            }
        }
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
            histogramWriter = repos.histogramWriter,
            spanRepository = repos.spanRepository,
        ).also { it.onBulkImportComplete = repos.onBulkImportComplete }
    }
    // Wire write-behind flush callbacks so FileRegistry correctly tracks SAF write windows.
    // - onFlushPreWrite: sets Long.MAX_VALUE sentinel before write, closing the mtime race
    //   window where a concurrent detectChanges poll emits a spurious event for .md.stek files.
    // - onFlushComplete: replaces sentinel with post-flush mtime after successful write.
    // - onFlushFailed: removes sentinel when write fails so the file is not permanently suppressed.
    remember(graphLoader) {
        fileSystem.setOnFlushPreWrite(graphLoader::preMarkFileWrite)
        fileSystem.setOnFlushComplete(graphLoader::markFileWrittenByUs)
        fileSystem.setOnFlushFailed(graphLoader::clearFilePendingWrite)
    }

    val graphWriter = remember {
        GraphWriter(
            fileSystem,
            repos.writeActor,
            onFileWritten = graphLoader::markFileWrittenByUs,
            sidecarManager = sidecarManager,
            onPreWrite = { filePath -> graphLoader.preMarkFileWrite(filePath) },
            onClearPendingWrite = { filePath -> graphLoader.clearFilePendingWrite(filePath) },
            checkPreWriteConflict = { filePath, diskContent ->
                val lastKnown = graphLoader.fileRegistry.getContentHash(dev.stapler.stelekit.model.FilePath(filePath))
                lastKnown != null && diskContent.hashCode() != lastKnown
            },
            onPreWriteConflict = { filePath, _, diskContent ->
                graphLoader.emitExternalFileChange(filePath, diskContent)
            },
        )
    }

    // Wire git sync service for the active graph.
    // Requires a platform-specific GitRepository; no-op when none is provided.
    val gitConfigRepository = remember(gitRepository) {
        if (gitRepository == null) null else graphManager.createGitConfigRepository()
    }
    val gitSyncService = remember(gitConfigRepository) {
        if (gitRepository == null || gitConfigRepository == null) return@remember null
        val networkMonitor = dev.stapler.stelekit.platform.NetworkMonitor()
        dev.stapler.stelekit.git.GitSyncService(
            gitRepository = gitRepository,
            graphLoader = graphLoader,
            graphWriter = graphWriter,
            editLock = dev.stapler.stelekit.git.EditLock(),
            configRepository = gitConfigRepository,
            networkMonitor = networkMonitor,
            fileSystem = fileSystem,
            credentialAccessProvider = { vaultCredentialStore ?: dev.stapler.stelekit.git.CredentialStore() },
        )
    }
    DisposableEffect(gitSyncService) {
        graphManager.registerGitSyncService(gitSyncService)
        onDispose {
            gitSyncService?.shutdown()
            graphManager.registerGitSyncService(null)
        }
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
            graphPathProvider = { viewModelRef?.uiState?.value?.currentGraphPath ?: "" },
            histogramWriter = repos.histogramWriter,
            writeActor = repos.writeActor,
            invalidationSource = repos.writeActor?.blockInvalidations,
            pushSource = repos.writeActor?.blocksPushed,
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

    // Platform-specific share provider (share sheet, file save, etc.)
    val shareProvider = rememberShareProvider()

    // ViewModel scope must NOT be rememberCoroutineScope() — that scope is cancelled when the
    // composable leaves the composition, which would cancel all ViewModel coroutines on pause.
    val viewModelScope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default) }
    val viewModel = remember {
        StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fileSystem,
                pageRepository = repos.pageRepository,
                blockRepository = repos.blockRepository,
                searchRepository = repos.searchRepository,
                graphLoader = graphLoader,
                graphWriter = graphWriter,
                platformSettings = platformSettings,
                journalService = repos.journalService,
                blockStateManager = blockStateManager,
                writeActor = repos.writeActor,
                undoManager = repos.undoManager,
                exportService = exportService,
                bugReportBuilder = repos.bugReportBuilder,
                debugFlagRepository = repos.debugFlagRepository,
                histogramWriter = repos.histogramWriter,
                ringBuffer = repos.ringBuffer,
                activeGitSyncService = graphManager.activeGitSyncService,
                activeGraphIdProvider = { graphManager.getActiveGraphId() },
                onDismissGitDetection = { graphId -> graphManager.setGitDetectionDismissed(graphId, true) },
                scope = viewModelScope,
            )
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

    // Wire the /image command in the command palette to the platform attachment service.
    // The callback reads the currently editing block from blockStateManager so it works
    // even when invoked from the command palette (no block UUID passed explicitly).
    LaunchedEffect(viewModel, attachmentService) {
        if (attachmentService != null) {
            viewModel.registerAttachImageCallback {
                scope.launch {
                    val editingBlockUuid = blockStateManager.editingBlockUuid.value
                    val graphRoot = viewModel.uiState.value.currentGraphPath
                    val result = attachmentService.pickAndAttach(
                        graphRoot = graphRoot,
                        pageRelativePath = ""
                    ) ?: return@launch
                    result.fold(
                        ifLeft = { err: dev.stapler.stelekit.error.DomainError ->
                            graphContentLogger.warn("Image attachment failed: $err")
                        },
                        ifRight = { attachment: dev.stapler.stelekit.service.AttachmentResult ->
                            val markdown = "![${attachment.displayName}](${attachment.relativePath})"
                            if (editingBlockUuid != null) {
                                blockStateManager.insertTextAtCursor(editingBlockUuid, markdown)
                            }
                        }
                    )
                }
            }
        } else {
            viewModel.registerAttachImageCallback(null)
        }
    }

    // Bootstrap loadGraph when the ViewModel has no persisted path but GraphManager has an
    // active graph. For paranoid-mode graphs, loading is deferred until after unlock so the
    // CryptoLayer is in place before any file reads.
    LaunchedEffect(Unit) {
        if (!isParanoidMode && viewModel.uiState.value.currentGraphPath.isEmpty()) {
            val path = graphManager.getActiveGraphInfo()?.path
            if (!path.isNullOrEmpty()) {
                viewModel.setGraphPath(path)
            }
        }
    }

    // When the vault locks, null out CryptoLayer references so loader/writer do not use
    // the zeroed DEK left behind by VaultManager.lock(). Subscribes to vaultEvents so
    // the cleanup runs even when lock() is triggered programmatically (not via vaultState).
    LaunchedEffect(vaultManager) {
        vaultManager?.vaultEvents?.collect { event ->
            if (event is VaultEvent.Locked) {
                // DEK is already zeroed at this point — do not flush (would write with zero-key).
                // The primary lock path (user-initiated) already flushed before calling lock().
                // closeAndClearCryptoLayer() zeroes the CryptoLayer's owned DEK copy then nulls it.
                graphLoader.closeAndClearCryptoLayer()
                graphWriter.closeAndClearCryptoLayer()
                vaultCredentialStore?.onVaultLocked()
                // Revert git repository to PBKDF2 fallback store
                gitRepository?.setCredentialAccess(dev.stapler.stelekit.git.CredentialStore())
                vaultState = VaultState.Locked   // show lock/unlock screen; gates graph content
            }
        }
    }

    // After successful vault unlock, inject CryptoLayer into loader/writer then load graph.
    LaunchedEffect(vaultState) {
        val state = vaultState
        if (state is VaultState.Unlocked && isParanoidMode && viewModel.uiState.value.currentGraphPath.isEmpty()) {
            val path = graphManager.getActiveGraphInfo()?.path ?: return@LaunchedEffect
            viewModel.setGraphPath(path)
        }
    }

    // Unlock handler — called from VaultUnlockScreen. The namespace arg is UI-only (OUTER vs HIDDEN
    // button); VaultManager determines the actual namespace from the keyslot that decrypts.
    val onVaultUnlock: (passphrase: CharArray, dev.stapler.stelekit.vault.VaultNamespace) -> Unit = handler@{ passphrase, _ ->
        val vm = vaultManager ?: run { passphrase.fill(' '); return@handler }
        val engine = cryptoEngine ?: run { passphrase.fill(' '); return@handler }
        vaultState = VaultState.Unlocking
        scope.launch {
            when (val result = vm.unlock(activeGraphPath, passphrase)) {
                is arrow.core.Either.Right -> {
                    val unlockResult = result.value
                    val layer = dev.stapler.stelekit.vault.CryptoLayer(engine, unlockResult.dek)
                    // Set graph paths before cryptoLayer so any concurrent reader that observes
                    // cryptoLayer != null will also see the correct graphPath (used as AAD base).
                    graphWriter.graphPath = activeGraphPath
                    graphLoader.setGraphPath(activeGraphPath)
                    graphLoader.setCryptoLayer(layer)
                    graphWriter.setCryptoLayer(layer)
                    vaultCredentialStore?.onVaultUnlocked(unlockResult.dek)
                    // Swap git repository credential access to vault store
                    gitRepository?.setCredentialAccess(vaultCredentialStore ?: dev.stapler.stelekit.git.CredentialStore())
                    // CryptoLayer must be injected before vaultState triggers graph load via LaunchedEffect
                    vaultState = VaultState.Unlocked(unlockResult.namespace)
                }
                is arrow.core.Either.Left -> {
                    vaultState = VaultState.Error(result.value)
                }
            }
        }
    }

    // Vault settings callbacks — threaded into SettingsDialog via GraphDialogLayer.
    val onCreateVault: (suspend (CharArray) -> arrow.core.Either<dev.stapler.stelekit.vault.VaultError, Unit>)? =
        if (cryptoEngine != null && activeGraphPath.isNotEmpty()) {
            { passphrase ->
                val engine = cryptoEngine
                val tempManager = dev.stapler.stelekit.vault.VaultManager(
                    crypto = engine,
                    fileReadBytes = { path -> fileSystem.readFileBytes(path) },
                    fileWriteBytes = { path, data -> fileSystem.writeFileBytes(path, data) },
                )
                when (val result = tempManager.createVault(activeGraphPath, passphrase)) {
                    is arrow.core.Either.Right -> {
                        val unlockResult = result.value
                        val layer = dev.stapler.stelekit.vault.CryptoLayer(engine, unlockResult.dek)
                        graphWriter.graphPath = activeGraphPath
                        graphLoader.setGraphPath(activeGraphPath)
                        graphLoader.setCryptoLayer(layer)
                        graphWriter.setCryptoLayer(layer)
                        vaultCredentialStore?.onVaultUnlocked(unlockResult.dek)
                        // Swap git repository credential access to vault store
                        gitRepository?.setCredentialAccess(vaultCredentialStore ?: dev.stapler.stelekit.git.CredentialStore())
                        // Migrate existing credentials from PBKDF2 store into vault
                        val graphId = graphManager.getActiveGraphId() ?: ""
                        if (graphId.isNotEmpty()) {
                            vaultCredentialStore?.migrateFrom(
                                source = dev.stapler.stelekit.git.CredentialStore(),
                                keys = listOf("git_https_token_$graphId", "git_ssh_passphrase_$graphId"),
                            )
                        }
                        vaultManager = tempManager
                        isParanoidMode = true
                        vaultState = VaultState.Unlocked(unlockResult.namespace)
                        arrow.core.Either.Right(Unit)
                    }
                    is arrow.core.Either.Left -> result
                }
            }
        } else null

    val capturedVaultManager = vaultManager

    val onAddKeyslot: (suspend (CharArray) -> arrow.core.Either<dev.stapler.stelekit.vault.VaultError, Unit>)? =
        if (isParanoidMode && capturedVaultManager != null) {
            { passphrase ->
                val dek = capturedVaultManager.currentDek()
                if (dek == null) {
                    arrow.core.Either.Left(dev.stapler.stelekit.vault.VaultError.InvalidCredential("Vault is locked"))
                } else {
                    capturedVaultManager.addKeyslot(activeGraphPath, dek, passphrase)
                }
            }
        } else null

    val onRemoveKeyslot: (suspend (Int) -> arrow.core.Either<dev.stapler.stelekit.vault.VaultError, Unit>)? =
        if (isParanoidMode && capturedVaultManager != null) {
            { slotIndex -> capturedVaultManager.removeKeyslot(activeGraphPath, slotIndex) }
        } else null

    val onLockVault: (() -> Unit)? =
        if (isParanoidMode && capturedVaultManager != null) {
            {
                scope.launch {
                    graphWriter.flush()
                    graphLoader.closeAndClearCryptoLayer()
                    graphWriter.closeAndClearCryptoLayer()
                    capturedVaultManager.lock()
                }
                Unit
            }
        } else null

    val onListActiveSlots: (suspend () -> List<Int>)? =
        if (isParanoidMode && capturedVaultManager != null) {
            { capturedVaultManager.listActiveKeyslotIndices(activeGraphPath) }
        } else null

    // Google Account auth state — threaded into SettingsDialog via GraphDialogLayer.
    var isGoogleAuthenticated by remember { mutableStateOf(false) }
    var googleConnectedEmail by remember { mutableStateOf<String?>(null) }
    var isGoogleConnecting by remember { mutableStateOf(false) }
    var googleAuthError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(googleAuthManager) {
        if (googleAuthManager != null) {
            isGoogleAuthenticated = googleAuthManager.isAuthenticated()
            googleConnectedEmail = googleAuthManager.getConnectedEmail()
        }
    }

    val onConnectGoogle: (() -> Unit)? = if (googleAuthManager != null) {
        {
            scope.launch {
                isGoogleConnecting = true
                googleAuthError = null
                val result = googleAuthManager.authenticate()
                when {
                    result is arrow.core.Either.Right -> {
                        isGoogleAuthenticated = true
                        googleConnectedEmail = result.value
                    }
                    result is arrow.core.Either.Left &&
                        result.value is dev.stapler.stelekit.error.DomainError.NetworkError.HttpError &&
                        (result.value as dev.stapler.stelekit.error.DomainError.NetworkError.HttpError).statusCode == 202 -> {
                        // Browser launched — auth completes via deep-link callback; nothing to show.
                    }
                    else -> googleAuthError = (result as arrow.core.Either.Left).value.message
                }
                isGoogleConnecting = false
            }
        }
    } else null

    val onDisconnectGoogle: (() -> Unit)? = if (googleAuthManager != null) {
        {
            scope.launch {
                googleAuthManager.signOut()
                isGoogleAuthenticated = false
                googleConnectedEmail = null
                googleAuthError = null
            }
        }
    } else null

    val frameMetricState = remember { kotlinx.coroutines.flow.MutableStateFlow(dev.stapler.stelekit.performance.FrameMetric()) }

    var debugMenuState by remember {
        mutableStateOf(repos.debugFlagRepository?.loadDebugMenuState() ?: DebugMenuState())
    }

    // Sync span capture toggle → ring buffer enabled flag so histograms remain always-on
    // but span recording only runs when explicitly requested.
    androidx.compose.runtime.LaunchedEffect(debugMenuState.isSpanCaptureEnabled) {
        repos.ringBuffer?.enabled = debugMenuState.isSpanCaptureEnabled
    }

    // ── Eager performance data collection ────────────────────────────────────────────────────
    // Data collection starts when the Performance screen is first opened so the tabs show
    // content immediately on first render. Pollers run only while the graph is loaded and
    // the user has visited the Performance screen at least once — no background DB reads
    // on devices that never open the Performance screen.

    val perfSpans: MutableStateFlow<List<SerializedSpan>> = remember { MutableStateFlow(emptyList()) }
    val perfHistograms: MutableStateFlow<Map<String, PercentileSummary>> = remember { MutableStateFlow(emptyMap()) }
    val perfQueryStats: MutableStateFlow<List<QueryStat>> = remember { MutableStateFlow(emptyList()) }

    // Set to true the first time the user navigates to Screen.Performance; never resets.
    // This gates the pollers so we don't run background DB reads for users who never open
    // the Performance screen.
    val perfScreenEverOpened = remember { MutableStateFlow(false) }
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.uiState.collect { state ->
            if (state.currentScreen is Screen.Performance) perfScreenEverOpened.value = true
        }
    }

    // Span data: reactive SQLDelight flow — fires whenever new spans are written to SQLite.
    // Starts immediately (spans are cheap to subscribe to; the reactive query only fires on writes).
    androidx.compose.runtime.LaunchedEffect(repos.spanRepository) {
        repos.spanRepository?.getRecentSpans(500)?.collect { result -> result.getOrNull()?.let { perfSpans.value = it } }
    }

    // Histogram summaries: poll every 2s, but only after Performance screen first opened.
    androidx.compose.runtime.LaunchedEffect(repos.histogramWriter) {
        val writer = repos.histogramWriter ?: return@LaunchedEffect
        perfScreenEverOpened.first { it }   // suspend until first Performance screen visit
        while (true) {
            perfHistograms.value = withContext(PlatformDispatcher.DB) {
                writer.queryAllOperations()
                    .mapNotNull { op -> writer.queryPercentiles(op)?.let { op to it } }
                    .toMap()
            }
            kotlinx.coroutines.delay(2_000)
        }
    }

    // Query stats: poll every 5s, but only after Performance screen first opened.
    androidx.compose.runtime.LaunchedEffect(repos.queryStatsRepository) {
        val repo = repos.queryStatsRepository ?: return@LaunchedEffect
        perfScreenEverOpened.first { it }   // suspend until first Performance screen visit
        while (true) {
            perfQueryStats.value = withContext(PlatformDispatcher.DB) {
                val version = repo.getAllVersions().firstOrNull() ?: ""
                repo.getTopByTotalMs(version, 50)
            }
            kotlinx.coroutines.delay(5_000)
        }
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

    val tagSettings = remember(platformSettings) { TagSettings(platformSettings) }
    val tagEngine = remember(viewModel.pageNameIndex, voiceSettings, tagSettings) {
        if (!tagSettings.isEnabled()) null
        else {
            val llmProvider = if (tagSettings.isLlmTierEnabled() && voiceSettings != null) {
                val llmFormatter = buildLlmFormatterForTags(voiceSettings)
                if (llmFormatter != null) LlmTagProvider(llmFormatter) else null
            } else null
            TagSuggestionEngine(
                pageNameIndex = viewModel.pageNameIndex,
                llmTagProvider = llmProvider,
            )
        }
    }
    val tagSuggestionViewModel = remember(tagEngine) {
        if (tagEngine != null) TagSuggestionViewModel(tagEngine) else null
    }
    DisposableEffect(tagSuggestionViewModel) {
        onDispose { tagSuggestionViewModel?.close() }
    }

    val voiceCaptureViewModel = remember(voicePipeline, tagEngine) {
        VoiceCaptureViewModel(
            voicePipeline,
            repos.journalService,
            currentOpenPageUuid = { viewModel.uiState.value.currentPage?.uuid?.value },
            tagSuggestionEngine = tagEngine,
        )
    }
    DisposableEffect(voiceCaptureViewModel) {
        onDispose { voiceCaptureViewModel.close() }
    }

    // Force-flush pending writes on Android lifecycle pause/stop.
    // Keyed on voiceCaptureViewModel so the observer is re-registered whenever the VM is
    // recreated (e.g. after voicePipeline changes), preventing calls on a stale closed instance.
    // Uses each object's own internal scope rather than rememberCoroutineScope to avoid
    // ForgottenCoroutineScopeException when ON_PAUSE fires after composition teardown.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, voiceCaptureViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.savePendingChanges()  // launches flush on viewModel's own scope
                    voiceCaptureViewModel.cancel()
                }
                Lifecycle.Event.ON_STOP -> {
                    val vm = vaultManager
                    if (vm != null) {
                        // Use viewModel's own scope — avoids ForgottenCoroutineScopeException
                        // if ON_STOP fires after composition teardown.
                        viewModel.flushAndLockVault(graphLoader, graphWriter, vm)
                    }
                }
                else -> Unit
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
        SearchViewModel(repos.searchRepository, pageRepository = repos.pageRepository)
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
    val activeGraphId = graphRegistry.activeGraphId
    val syncState by viewModel.syncState.collectAsState()

    StelekitTheme(themeMode = appState.themeMode) {
        CompositionLocalProvider(LocalI18n provides I18n(appState.language)) {
            if (!appState.onboardingCompleted) {
                Onboarding(
                    fileSystem = fileSystem,
                    onComplete = { viewModel.setOnboardingCompleted(true) },
                    onGraphSelected = { path ->
                        scope.launch {
                            val graphId = graphManager.addGraph(path)
                            // Persist BEFORE switchGraph — switchGraph updates activeGraphId which
                            // triggers key(activeGraphId) to destroy and recreate GraphContent
                            // along with its scope. The new ViewModel reads these persisted values
                            // in its init block and calls loadGraph automatically.
                            viewModel.setGraphPath(path)
                            viewModel.setOnboardingCompleted(true)
                            graphManager.switchGraph(graphId)
                        }
                    }
                )
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
                    val snackbarHostState = remember { SnackbarHostState() }
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
                                onShareClick = { viewModel.showShareDialog() },
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
                                onCollapse = { viewModel.toggleSidebar() },
                                syncState = syncState,
                                onSyncClick = {
                                    if (syncState is dev.stapler.stelekit.git.model.SyncState.CredentialVaultLocked) {
                                        // Vault is locked — lock() re-shows the unlock screen
                                        vaultManager?.lock()
                                    } else {
                                        viewModel.triggerSync()
                                    }
                                },
                                onGitSetup = { viewModel.openGitSetup() },
                                isGitConfigured = appState.gitConfig != null,
                                onAuthError = { viewModel.openGitSetupForCredentials() },
                                onCloneGraph = { viewModel.openGitSetupForClone() },
                                gitSyncedGraphId = if (appState.gitConfig != null) activeGraphId else null,
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
                            val cameraImportEnabled =
                                imageImportService != null && SensorModule.cameraProvider.isAvailable
                            val activeGraphInfo2 = graphRegistry.graphs.firstOrNull { it.id == activeGraphId }
                            val showGitBanner = activeGraphInfo2?.detectedRepoRoot != null &&
                                appState.gitConfig == null &&
                                activeGraphInfo2.gitDetectionDismissed == false
                            Column(modifier = Modifier.fillMaxSize()) {
                                if (showGitBanner) {
                                    GitDetectionBanner(
                                        repoRoot = activeGraphInfo2!!.detectedRepoRoot!!,
                                        onSetupSync = { viewModel.openGitSetup() },
                                        onDismiss = {
                                            val gid = activeGraphId ?: return@GitDetectionBanner
                                            viewModel.dismissGitDetection(gid)
                                        },
                                    )
                                }
                            Box(modifier = Modifier.weight(1f)) {
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
                                capabilities = dev.stapler.stelekit.ui.components.EditorCapabilities(
                                    onAttachImage = if (attachmentService != null) {
                                        { editingBlockUuid ->
                                            val graphRoot = appState.currentGraphPath
                                            scope.launch {
                                                val result = attachmentService.pickAndAttach(
                                                    graphRoot = graphRoot,
                                                    pageRelativePath = ""
                                                ) ?: return@launch
                                                result.fold(
                                                    ifLeft = { err: dev.stapler.stelekit.error.DomainError ->
                                                        graphContentLogger.warn("Image attachment failed: $err")
                                                    },
                                                    ifRight = { attachment: dev.stapler.stelekit.service.AttachmentResult ->
                                                        val safeAlt = attachment.displayName.replace("]", "\\]")
                                                        val safePath = attachment.relativePath.replace(")", "\\)")
                                                        val markdown = "![${safeAlt}](${safePath})"
                                                        blockStateManager.insertTextAtCursor(editingBlockUuid, markdown)
                                                    }
                                                )
                                            }
                                        }
                                    } else null,
                                    onFileDrop = if (attachmentService != null) {
                                        { files ->
                                            val graphRoot = appState.currentGraphPath
                                            val pageUuid = (appState.currentScreen as? Screen.PageView)?.page?.uuid
                                            if (pageUuid != null) {
                                                scope.launch {
                                                    files.forEach { file ->
                                                        val result = attachmentService.attachFilePath(
                                                            filePath = file.toString(),
                                                            graphRoot = graphRoot
                                                        ) ?: return@forEach
                                                        result.fold(
                                                            ifLeft = { err: dev.stapler.stelekit.error.DomainError ->
                                                                graphContentLogger.warn("Drag-and-drop attachment failed: $err")
                                                            },
                                                            ifRight = { attachment: dev.stapler.stelekit.service.AttachmentResult ->
                                                                val safeAlt = attachment.displayName.replace("]", "\\]")
                                                                val safePath = attachment.relativePath.replace(")", "\\)")
                                                                blockStateManager.addBlockWithContent(
                                                                    pageUuid = pageUuid,
                                                                    content = "![${safeAlt}](${safePath})"
                                                                )
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else null,
                                    onPasteImage = if (attachmentService != null) {
                                        { editingBlockUuid ->
                                            if (attachmentService.hasClipboardImage()) {
                                                val graphRoot = appState.currentGraphPath
                                                scope.launch {
                                                    val result = attachmentService.pasteFromClipboard(graphRoot)
                                                        ?: return@launch
                                                    result.fold(
                                                        ifLeft = { err: dev.stapler.stelekit.error.DomainError ->
                                                            graphContentLogger.warn("Clipboard paste failed: $err")
                                                        },
                                                        ifRight = { attachment: dev.stapler.stelekit.service.AttachmentResult ->
                                                            val safeAlt = attachment.displayName.replace("]", "\\]")
                                                            val safePath = attachment.relativePath.replace(")", "\\)")
                                                            val markdown = "![${safeAlt}](${safePath})"
                                                            if (editingBlockUuid != null) {
                                                                blockStateManager.insertTextAtCursor(editingBlockUuid, markdown)
                                                            }
                                                        }
                                                    )
                                                }
                                                true
                                            } else false
                                        }
                                    } else null,
                                    onCaptureImage = if (cameraImportEnabled) {
                                        {
                                            scope.launch {
                                                // Resolve page UUID before capturing — camera suspends for seconds,
                                                // so we snapshot navigation state at button-tap time, not return time.
                                                val pageUuid: String? =
                                                    (appState.currentScreen as? Screen.PageView)?.page?.uuid?.value
                                                        ?: appState.currentPage?.uuid?.value
                                                val resolvedPageUuid = pageUuid
                                                    ?: repos.journalService.ensureTodayJournal().uuid.value
                                                executeCaptureAndImport(
                                                    imageImportService = imageImportService,
                                                    getActiveGraphPath = { graphManager.getActiveGraphInfo()?.path },
                                                    pageUuid = resolvedPageUuid,
                                                    navigateAfterImport = false,
                                                    onSnackbar = { viewModel.sendSnackbar(it) },
                                                    onNavigate = { annUuid, pgUuid -> viewModel.navigateToAnnotationEditor(annUuid, pgUuid) },
                                                    onWarn = { graphContentLogger.warn(it) },
                                                )
                                            }
                                        }
                                    } else null,
                                ),
                                onImportImage = if (cameraImportEnabled) {
                                    {
                                        scope.launch {
                                            val page = repos.journalService.ensureTodayJournal()
                                            executeCaptureAndImport(
                                                imageImportService = imageImportService,
                                                getActiveGraphPath = { graphManager.getActiveGraphInfo()?.path },
                                                pageUuid = page.uuid.value,
                                                navigateAfterImport = true,
                                                onSnackbar = { viewModel.sendSnackbar(it) },
                                                onNavigate = { annUuid, pgUuid -> viewModel.navigateToAnnotationEditor(annUuid, pgUuid) },
                                                onWarn = { graphContentLogger.warn(it) },
                                            )
                                        }
                                        Unit
                                    }
                                } else null,
                                platformSettings = platformSettings,
                                perfSpans = perfSpans,
                                perfHistograms = perfHistograms,
                                perfQueryStats = perfQueryStats,
                                tagSuggestionViewModel = tagSuggestionViewModel,
                            )
                            } // Box
                            } // Column
                        },
                        statusBar = {
                            if (!isMobile) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    StatusBarContent(
                                        isEncrypted = encryptionManager.isEncryptionEnabled(appState.currentGraphPath),
                                        statusMessage = appState.statusMessage,
                                        activeGraphName = activeGraphInfo?.displayName ?: "",
                                        pluginCount = pluginHost.getAllPlugins().size,
                                        modifier = Modifier.weight(1f),
                                    )
                                    val vmForLockBtn = vaultManager
                                    if (isParanoidMode && vmForLockBtn != null) {
                                        IconButton(onClick = {
                                            viewModel.flushAndLockVault(graphLoader, graphWriter, vmForLockBtn)
                                        }) {
                                            Icon(
                                                imageVector = Icons.Filled.Lock,
                                                contentDescription = "Lock vault",
                                            )
                                        }
                                    }
                                }
                            }
                            SnackbarHost(hostState = snackbarHostState)
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
                        loadPageBlocks = { pageUuidStr -> repos.blockRepository.getBlocksForPage(dev.stapler.stelekit.model.PageUuid(pageUuidStr)) },
                        onDebugStateChange = { newState ->
                            debugMenuState = newState
                            viewModel.onDebugMenuStateChange(newState)
                        },
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
                        gitSyncService = gitSyncService,
                        gitRepository = gitRepository,
                        gitConfigRepository = gitConfigRepository,
                        activeGraphId = activeGraphId,
                        onCloneAndAdd = if (gitRepository != null) {
                            { url, localPath, auth, onProgress ->
                                graphManager.cloneAndAdd(gitRepository, url, localPath, auth, onProgress)
                            }
                        } else null,
                        graphPath = activeGraphPath,
                        onCloneComplete = { newGraphId ->
                            scope.launch { graphManager.switchGraph(newGraphId) }
                        },
                        onAuthError = { viewModel.openGitSetupForCredentials() },
                        shareProvider = shareProvider,
                        exportService = exportService,
                        driveClient = null, // DriveApiClient injected from platform entry point in a future phase
                        shareGoogleAuthManager = googleAuthManager,
                        tagSettings = tagSettings,
                        hasLlmKey = voiceSettings?.getAnthropicKey() != null || voiceSettings?.getOpenAiKey() != null,
                        currentPage = appState.currentPage,
                        currentBlocks = appState.currentPage?.let {
                            blockStateManager.blocksForPage(it.uuid.value)
                        } ?: emptyList(),
                        selectedBlockUuids = blockStateManager.selectedBlockUuids.collectAsState().value,
                    )

                    } // CompositionLocalProvider(LocalWindowSizeClass)
                }
                } // vault unlocked else
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
 * Builds a [dev.stapler.stelekit.voice.LlmFormatterProvider] for tag suggestions,
 * reusing the same Anthropic / OpenAI keys already stored in [VoiceSettings].
 * Returns null when no key is configured.
 */
private fun buildLlmFormatterForTags(
    voiceSettings: VoiceSettings,
): dev.stapler.stelekit.voice.LlmFormatterProvider? =
    voiceSettings.getAnthropicKey()
        ?.let { dev.stapler.stelekit.voice.ClaudeLlmFormatterProvider.withDefaults(it) }
        ?: voiceSettings.getOpenAiKey()
            ?.let { dev.stapler.stelekit.voice.OpenAiLlmFormatterProvider.withDefaults(it) }

/**
 * Status bar row — pure presentational composable. Receives only primitives;
 * no ViewModel dependency. See ADR-001.
 */
@Composable
private fun StatusBarContent(
    isEncrypted: Boolean,
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
