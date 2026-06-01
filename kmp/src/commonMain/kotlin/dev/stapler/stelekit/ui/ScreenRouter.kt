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
import androidx.compose.runtime.*
import dev.stapler.stelekit.db.GraphWriterPort
import dev.stapler.stelekit.domain.NoOpUrlFetcher
import dev.stapler.stelekit.domain.UrlFetcher
import dev.stapler.stelekit.performance.NavigationTracingEffect
import dev.stapler.stelekit.performance.PercentileSummary
import dev.stapler.stelekit.performance.QueryStat
import dev.stapler.stelekit.performance.SerializedSpan
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
            )
            is Screen.Journals -> JournalsView(
                viewModel = journalsViewModel,
                isDebugMode = appState.isDebugMode,
                onLinkClick = { viewModel.navigateToPageByName(it) },
                searchViewModel = searchViewModel,
                onSearchPages = { query -> viewModel.searchPages(query) },
                suggestionMatcher = suggestionMatcher,
                isLeftHanded = appState.isLeftHanded,
                onOpenAnnotationEditor = { uuid -> viewModel.navigateToAnnotationEditor(uuid) },
                capabilities = capabilities,
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

            is Screen.AnnotationEditor -> {
                NavigationTracingEffect("AnnotationEditor")
                val imageAnnotationUuid = currentScreen.imageAnnotationUuid
                val annotationEditorViewModel = remember(imageAnnotationUuid) {
                    AnnotationEditorViewModel(
                        measurementRepository = repos.measurementAnnotationRepository,
                        imageAnnotationRepository = repos.imageAnnotationRepository,
                    )
                }
                // Collect the annotation reactively; initialize the viewModel once on first non-null load.
                var initialized by remember(imageAnnotationUuid) { mutableStateOf(false) }
                var resolvedAnnotation by remember(imageAnnotationUuid) {
                    mutableStateOf<dev.stapler.stelekit.model.ImageAnnotation?>(null)
                }
                LaunchedEffect(imageAnnotationUuid) {
                    repos.imageAnnotationRepository.getImageAnnotationByUuid(imageAnnotationUuid)
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
