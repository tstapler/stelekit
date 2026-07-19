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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.stapler.stelekit.db.GraphWriterPort
import dev.stapler.stelekit.domain.NoOpUrlFetcher
import dev.stapler.stelekit.domain.UrlFetcher
import dev.stapler.stelekit.performance.NavigationTracingEffect
import dev.stapler.stelekit.performance.PercentileSummary
import dev.stapler.stelekit.performance.QueryStat
import dev.stapler.stelekit.performance.SerializedSpan
import dev.stapler.stelekit.platform.sensor.SensorModule
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.transfer.qrcode.QrImportService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import dev.stapler.stelekit.ui.annotate.AnnotationEditorScreen
import dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel
import dev.stapler.stelekit.ui.components.*
import dev.stapler.stelekit.ui.gallery.GalleryScreen
import dev.stapler.stelekit.ui.gallery.GalleryViewModel
import dev.stapler.stelekit.ui.screens.AllPagesScreen
import dev.stapler.stelekit.ui.screens.AllPagesViewModel
import dev.stapler.stelekit.ui.screens.GlobalUnlinkedReferencesScreen
import dev.stapler.stelekit.ui.screens.JournalsView
import dev.stapler.stelekit.ui.screens.JournalsViewModel
import dev.stapler.stelekit.ui.screens.LibraryStatsScreen
import dev.stapler.stelekit.ui.screens.LibraryStatsViewModel
import dev.stapler.stelekit.ui.screens.PageView
import dev.stapler.stelekit.ui.screens.SearchViewModel
import dev.stapler.stelekit.tags.TagSuggestionViewModel
import dev.stapler.stelekit.ui.transfer.QrDecodeScreen
import dev.stapler.stelekit.ui.transfer.QrDecodeViewModel

/**
 * Routes the current [Screen] to its composable. Owns screen transition animations.
 * Forward navigation (historyIndex increases): slide in from right.
 * Back navigation (historyIndex decreases): slide in from left, suppress exit so the
 * system predictive-back preview is the only visual during the back swipe.
 *
 * Rule (ADR-001): every branch must call a named composable — no inline content.
 */
@Composable
internal fun ScreenRouter(
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
    graphWriter: GraphWriterPort,
    urlFetcher: UrlFetcher = NoOpUrlFetcher(),
    capabilities: EditorCapabilities = EditorCapabilities(),
    onImportImage: (() -> Unit)? = null,
    platformSettings: dev.stapler.stelekit.platform.Settings? = null,
    perfSpans: StateFlow<List<SerializedSpan>> = MutableStateFlow(emptyList()),
    perfHistograms: StateFlow<Map<String, PercentileSummary>> = MutableStateFlow(emptyMap()),
    perfQueryStats: StateFlow<List<QueryStat>> = MutableStateFlow(emptyList()),
    tagSuggestionViewModel: TagSuggestionViewModel? = null,
    /**
     * Story 4.1.1: threaded down to [PageView]'s "Send via QR" menu item (Story 3.1.4). Null hides
     * the menu trigger entirely, mirroring [PageView]'s own null-hides-trigger contract.
     */
    qrTransferSettings: dev.stapler.stelekit.transfer.qrcode.QrTransferSettings? = null,
    /**
     * Story 3.2.4/S7 equivalent of the [qrTransferSettings] wiring above: threaded down to
     * [dev.stapler.stelekit.ui.screens.ImportScreen]'s "Import via camera" menu item and used to
     * build the [QrImportService] backing [QrDecodeViewModel]. Null disables the entry point
     * entirely, same null-hides-trigger contract as the send side.
     */
    graphLoader: dev.stapler.stelekit.db.GraphLoader? = null,
) {
    if (appState.fatalError != null) {
        FatalErrorScreen(
            message = appState.fatalError,
            onDismiss = { viewModel.clearFatalError() },
            onRetry = { viewModel.loadGraph(appState.currentGraphPath) },
        )
        return
    }

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
        // Update direction tracking after composition (must be in SideEffect, not inline assignment)
        SideEffect { previousHistoryIndex = appState.historyIndex }

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
                isLeftHanded = appState.isLeftHanded,
                capabilities = capabilities,
                onReloadFromDisk = { viewModel.reloadCurrentPageFromDisk() },
                isExporting = appState.isExporting,
                tagSuggestionViewModel = tagSuggestionViewModel,
                currentManifest = appState.currentManifest,
                onSectionBadgeClick = { viewModel.showSectionPicker(currentScreen.page) },
                hasDiskConflictPending = appState.diskConflict != null,
                qrTransferSettings = qrTransferSettings,
            )
            is Screen.Journals -> JournalsView(
                viewModel = journalsViewModel,
                isDebugMode = appState.isDebugMode,
                onLinkClick = { viewModel.navigateToPageByName(it) },
                graphPath = appState.currentGraphPath,
                searchViewModel = searchViewModel,
                onSearchPages = { query -> viewModel.searchPages(query) },
                suggestionMatcher = suggestionMatcher,
                isLeftHanded = appState.isLeftHanded,
                onOpenAnnotationEditor = { uuid -> viewModel.navigateToAnnotationEditor(uuid) },
                capabilities = capabilities,
                tagSuggestionViewModel = tagSuggestionViewModel,
                currentGraphId = appState.currentGraphId,
                conflictFilePaths = appState.pendingConflictFilePaths,
                hasDiskConflictPending = appState.diskConflict != null,
                onExportEntry = { page, blocks, formatId ->
                    viewModel.exportScopeToClipboard(
                        shareScope = dev.stapler.stelekit.ui.ShareScope.CurrentPage,
                        page = page,
                        allBlocks = blocks,
                        selectedUuids = emptySet(),
                        formatId = formatId,
                    )
                },
            )
            is Screen.Flashcards -> {
                NavigationTracingEffect("Flashcards")
                FlashcardsScreen(blockStateManager)
            }
            is Screen.AllPages -> AllPagesScreen(
                viewModel = allPagesViewModel,
                onPageClick = { page -> viewModel.navigateTo(Screen.PageView(page)) },
                onBulkDelete = { uuids -> viewModel.bulkDeletePages(uuids) },
                conflictFilePaths = appState.pendingConflictFilePaths,
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
                    ringBuffer = repos.ringBuffer,
                    spanRepository = repos.spanRepository,
                    perfExporter = repos.perfExporter,
                    queryPlanRepository = repos.queryPlanRepository,
                    queryStatsCollector = repos.queryStatsCollector,
                    queryStatsRepository = repos.queryStatsRepository,
                    perfSpans = perfSpans,
                    perfHistograms = perfHistograms,
                    perfQueryStats = perfQueryStats,
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
                val importViewModel = remember(graphPath) {
                    dev.stapler.stelekit.ui.screens.ImportViewModel(
                        pageRepository = repos.pageRepository,
                        graphWriter = graphWriter,
                        graphPath = graphPath,
                        urlFetcher = urlFetcher,
                        matcherFlow = viewModel.suggestionMatcher,
                    )
                }
                DisposableEffect(importViewModel) {
                    onDispose { importViewModel.close() }
                }
                // Story 3.2.4/S7 — decode-side equivalent of the QrEncodeScreen wiring in
                // PageView.kt: "Import via camera" opens QrDecodeScreen in an overlay Dialog.
                var showQrDecodeScreen by remember { mutableStateOf(false) }
                val writeActor = repos.writeActor
                dev.stapler.stelekit.ui.screens.ImportScreen(
                    viewModel = importViewModel,
                    onDismiss = {
                        val savedName = importViewModel.state.value.savedPageName
                        viewModel.goBack()
                        if (savedName != null) {
                            viewModel.navigateToPageByName(savedName)
                        }
                    },
                    qrTransferSettings = qrTransferSettings,
                    onImportViaCamera = { showQrDecodeScreen = true },
                )
                // Split into two conditions (each under detekt's complexity threshold) rather than
                // one 4-term && chain — local vals preserve Kotlin's smart-cast to non-null inside
                // the inner block, which QrImportService/QrDecodeScreen below depend on.
                if (showQrDecodeScreen) {
                    val settings = qrTransferSettings
                    val loader = graphLoader
                    val actor = writeActor
                    if (settings != null && loader != null && actor != null) {
                        val qrDecodeViewModel = remember(graphPath) {
                            QrDecodeViewModel(
                                cameraFrameSource = SensorModule.cameraFrameSource,
                                qrImportService = QrImportService(
                                    graphLoader = loader,
                                    pageRepository = repos.pageRepository,
                                    writeActor = actor,
                                ),
                                settings = settings,
                            )
                        }
                        DisposableEffect(qrDecodeViewModel) {
                            onDispose { qrDecodeViewModel.close() }
                        }
                        Dialog(
                            onDismissRequest = { showQrDecodeScreen = false },
                            properties = DialogProperties(usePlatformDefaultWidth = false),
                        ) {
                            QrDecodeScreen(
                                viewModel = qrDecodeViewModel,
                                settings = settings,
                                onDismiss = { showQrDecodeScreen = false },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            is Screen.VaultUnlock -> {
                // Vault unlock is handled by the outer StelekitApp scaffold — no-op here
            }

            is Screen.Gallery -> {
                NavigationTracingEffect("Gallery")
                val galleryViewModel = remember {
                    GalleryViewModel(repos.imageAnnotationRepository)
                }
                DisposableEffect(galleryViewModel) {
                    onDispose { galleryViewModel.close() }
                }
                GalleryScreen(
                    viewModel = galleryViewModel,
                    onOpenAnnotationEditor = { uuid ->
                        viewModel.navigateToAnnotationEditor(uuid)
                    },
                    onNavigateToPage = { pageUuid ->
                        viewModel.navigateToPageByUuid(pageUuid)
                    },
                    onImportImage = onImportImage,
                )
            }

            is Screen.AssetBrowser -> {
                NavigationTracingEffect("AssetBrowser")
                val assetBrowserViewModel = remember {
                    dev.stapler.stelekit.ui.assets.AssetBrowserViewModel(assetRepository = repos.assetRepository, writeActor = repos.writeActor)
                }
                dev.stapler.stelekit.ui.assets.AssetBrowserScreen(
                    viewModel = assetBrowserViewModel,
                    onNavigateBack = { viewModel.goBack() },
                    onNavigateToAsset = { uuid -> viewModel.navigateTo(Screen.AssetDetail(uuid)) },
                )
            }

            is Screen.AssetDetail -> {
                NavigationTracingEffect("AssetDetail")
                val assetDetailViewModel = remember(currentScreen.assetUuid) {
                    dev.stapler.stelekit.ui.assets.AssetDetailViewModel(
                        assetRepository = repos.assetRepository,
                        assetUuid = currentScreen.assetUuid,
                        imageAnnotationRepository = repos.imageAnnotationRepository,
                        blockRepository = repos.blockRepository,
                        writeActor = repos.writeActor,
                        graphPath = appState.currentGraphPath,
                    )
                }
                val annotateScope = rememberCoroutineScope()
                dev.stapler.stelekit.ui.assets.AssetDetailScreen(
                    viewModel = assetDetailViewModel,
                    onNavigateBack = { viewModel.goBack() },
                    onNavigateToPage = { pageUuid -> viewModel.navigateToPageByUuid(pageUuid) },
                    onAnnotate = { asset ->
                        annotateScope.launch {
                            assetDetailViewModel.resolveOrCreateAnnotation(asset)?.let { annotationUuid ->
                                viewModel.navigateToAnnotationEditor(annotationUuid, asset.pageUuids.firstOrNull())
                            }
                        }
                    },
                )
            }

            is Screen.AnnotationEditor -> {
                NavigationTracingEffect("AnnotationEditor")
                val imageAnnotationUuid = currentScreen.imageAnnotationUuid
                val annotationEditorViewModel = remember(imageAnnotationUuid) {
                    AnnotationEditorViewModel(
                        measurementRepository = repos.measurementAnnotationRepository,
                        imageAnnotationRepository = repos.imageAnnotationRepository,
                    )
                }
                DisposableEffect(annotationEditorViewModel) {
                    onDispose { annotationEditorViewModel.close() }
                }
                // Collect the annotation reactively; initialize the viewModel once on first non-null load.
                var initialized by remember(imageAnnotationUuid) { mutableStateOf(false) }
                var resolvedAnnotation by remember(imageAnnotationUuid) {
                    mutableStateOf<dev.stapler.stelekit.model.ImageAnnotation?>(null)
                }
                LaunchedEffect(imageAnnotationUuid) {
                    repos.imageAnnotationRepository.getImageAnnotationByUuid(imageAnnotationUuid.value)
                        .collect { either ->
                            either.onRight { annotation ->
                                resolvedAnnotation = annotation
                                if (!initialized && annotation != null) {
                                    initialized = true
                                    annotationEditorViewModel.initialize(annotation)
                                }
                            }
                        }
                }
                resolvedAnnotation?.let { annotation ->
                    AnnotationEditorScreen(
                        viewModel = annotationEditorViewModel,
                        imageAnnotation = annotation,
                        platformSettings = platformSettings,
                        onNavigateBack = {
                            if (currentScreen.pageUuid != null) {
                                viewModel.navigateToPageByUuid(currentScreen.pageUuid)
                            } else {
                                viewModel.goBack()
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Full-screen error page shown when a Throwable-level crash is caught and converted to a
 * recoverable state. Displays the error message in selectable text and provides a Copy
 * button so the user can paste it into a bug report.
 */
@Composable
private fun FatalErrorScreen(
    message: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "An unexpected error prevented the graph from loading. Copy the details below and share them when filing a bug report.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { clipboardManager.setText(AnnotatedString(message)) }) {
            Text("Copy error")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDismiss) {
            Text("Dismiss")
        }
    }
}
