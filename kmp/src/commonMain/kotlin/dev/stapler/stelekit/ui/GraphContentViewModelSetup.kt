// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.stapler.stelekit.export.ClipboardProvider
import dev.stapler.stelekit.export.ExportService
import dev.stapler.stelekit.export.HtmlExporter
import dev.stapler.stelekit.export.JsonExporter
import dev.stapler.stelekit.export.MarkdownExporter
import dev.stapler.stelekit.export.PlainTextExporter
import dev.stapler.stelekit.export.ShareProvider
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.ui.state.BlockStateManager

/** Bundles [GraphContent]'s core ViewModel stack (Parameter Object pattern). */
internal class GraphContentViewModelStack(
    val blockStateManager: BlockStateManager,
    val exportService: ExportService,
    val shareProvider: ShareProvider,
    val viewModel: StelekitViewModel,
)

/**
 * Builds [BlockStateManager], [ExportService], the platform [ShareProvider], and
 * [StelekitViewModel] for the active graph. Breaks the circular dependency between
 * `blockStateManager` (needs the graph path from `viewModel`) and `viewModel` (needs
 * `blockStateManager`) with a captured var so `blockStateManager` is created first with a lazy
 * lambda that resolves `viewModel` after both are initialised. Also keeps the export service's
 * clipboard in sync when the Compose clipboard changes (e.g. after an activity recreation on
 * Android).
 */
@Composable
internal fun rememberGraphContentViewModelStack(
    deps: GraphContentDeps,
    effectiveFileSystem: dev.stapler.stelekit.platform.FileSystem,
    graphIoStack: GraphContentGraphIoStack,
    clipboardProvider: ClipboardProvider,
): GraphContentViewModelStack {
    val repos = deps.repos

    // Break the circular dependency: blockStateManager needs the graph path from viewModel,
    // and viewModel needs blockStateManager. We use a captured var so blockStateManager is
    // created first with a lazy lambda that resolves viewModel after both are initialised.
    var viewModelRef: StelekitViewModel? = null

    val blockStateManager = rememberBlockStateManager(repos, graphIoStack) { viewModelRef }

    val exportService = remember(clipboardProvider, repos) {
        ExportService(
            exporters = listOf(MarkdownExporter(), PlainTextExporter(), HtmlExporter(), JsonExporter()),
            clipboard = clipboardProvider,
            blockRepository = repos.blockRepository,
        )
    }

    // Platform-specific share provider (share sheet, file save, etc.)
    val shareProvider = rememberShareProvider()

    // ViewModel scope must NOT be rememberCoroutineScope() — that scope is cancelled when the
    // composable leaves the composition, which would cancel all ViewModel coroutines on pause.
    val viewModelScope = remember {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    }
    val onSectionsLoaded = remember(repos) {
        dev.stapler.stelekit.sections.platformSectionSyncCallback(repos.pageRepository)
    }
    val seed = ViewModelSeedInputs(blockStateManager, exportService, onSectionsLoaded, viewModelScope)

    val viewModel = rememberStelekitViewModel(deps, effectiveFileSystem, graphIoStack, seed) { viewModelRef = it }

    LaunchedEffect(clipboardProvider) {
        viewModel.setClipboardProvider(clipboardProvider)
    }

    return GraphContentViewModelStack(blockStateManager, exportService, shareProvider, viewModel)
}

@Composable
private fun rememberBlockStateManager(
    repos: dev.stapler.stelekit.repository.RepositorySet,
    graphIoStack: GraphContentGraphIoStack,
    viewModelRefProvider: () -> StelekitViewModel?,
): BlockStateManager {
    val graphLoader = graphIoStack.graphLoader
    val graphWriter = graphIoStack.graphWriter
    return remember(repos, graphLoader, graphWriter) {
        BlockStateManager(
            blockRepository = repos.blockRepository,
            graphLoader = graphLoader,
            graphWriter = graphWriter,
            pageRepository = repos.pageRepository,
            graphPathProvider = { viewModelRefProvider()?.uiState?.value?.currentGraphPath ?: "" },
            histogramWriter = repos.histogramWriter,
            writeActor = repos.writeActor,
            invalidationSource = repos.writeActor?.blockInvalidations,
            pushSource = repos.writeActor?.blocksPushed,
        )
    }
}

/** Late-bound inputs to [rememberStelekitViewModel]'s construction (parameter-count relief). */
private class ViewModelSeedInputs(
    val blockStateManager: BlockStateManager,
    val exportService: ExportService,
    val onSectionsLoaded: (suspend (dev.stapler.stelekit.sections.SectionManifest, Map<String, dev.stapler.stelekit.sections.SectionState>) -> Unit)?,
    val viewModelScope: kotlinx.coroutines.CoroutineScope,
)

@Composable
private fun rememberStelekitViewModel(
    deps: GraphContentDeps,
    effectiveFileSystem: dev.stapler.stelekit.platform.FileSystem,
    graphIoStack: GraphContentGraphIoStack,
    seed: ViewModelSeedInputs,
    onCreate: (StelekitViewModel) -> Unit,
): StelekitViewModel {
    val repos = deps.repos
    val graphManager = deps.graphManager

    return remember(
        effectiveFileSystem, repos, deps.platformSettings, graphIoStack.graphLoader, graphIoStack.graphWriter,
        seed.blockStateManager, seed.exportService, graphManager, seed.viewModelScope,
    ) {
        StelekitViewModel(
            buildViewModelDependencies(deps, effectiveFileSystem, graphIoStack, seed)
        ).also {
            onCreate(it)
            it.startAutoSave()
        }
    }
}

private fun buildViewModelDependencies(
    deps: GraphContentDeps,
    effectiveFileSystem: dev.stapler.stelekit.platform.FileSystem,
    graphIoStack: GraphContentGraphIoStack,
    seed: ViewModelSeedInputs,
): StelekitViewModelDependencies {
    val repos = deps.repos
    val graphManager = deps.graphManager
    return StelekitViewModelDependencies(
        fileSystem = effectiveFileSystem,
        pageRepository = repos.pageRepository,
        blockRepository = repos.blockRepository,
        searchRepository = repos.searchRepository,
        graphLoader = graphIoStack.graphLoader,
        graphWriter = graphIoStack.graphWriter,
        platformSettings = deps.platformSettings,
        journalService = repos.journalService,
        blockStateManager = seed.blockStateManager,
        writeActor = repos.writeActor,
        undoManager = repos.undoManager,
        exportService = seed.exportService,
        bugReportBuilder = repos.bugReportBuilder,
        debugFlagRepository = repos.debugFlagRepository,
        histogramWriter = repos.histogramWriter,
        ringBuffer = repos.ringBuffer,
        activeGitSyncService = graphManager.activeGitSyncService,
        localChangesCountFlow = deps.webSyncDeps.localChangesCountFlow,
        activeGraphIdProvider = { graphManager.getActiveGraphId()?.value },
        initialGraphPathProvider = {
            graphManager.getActiveGraphInfo()?.takeIf { !it.isDemo }?.effectiveNotesPath?.value
        },
        onDismissGitDetection = { graphId -> graphManager.setGitDetectionDismissed(GraphId(graphId), true) },
        onDismissBrowserOnlySyncBanner = { graphId ->
            graphManager.setBrowserOnlySyncBannerDismissed(GraphId(graphId), true)
        },
        onDismissContentMismatchBanner = { graphId ->
            graphManager.setContentMismatchBannerDismissed(GraphId(graphId), true)
        },
        onSectionsLoaded = seed.onSectionsLoaded,
        scope = seed.viewModelScope,
    )
}
