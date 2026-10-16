// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.FileSystemLibraryStatsProvider
import dev.stapler.stelekit.stats.LibraryStatsProvider
import dev.stapler.stelekit.stats.NoOpLibraryStatsProvider
import dev.stapler.stelekit.sections.SectionState
import dev.stapler.stelekit.sections.getSectionStates
import dev.stapler.stelekit.ui.screens.AllPagesViewModel
import dev.stapler.stelekit.ui.screens.JournalsViewModel
import dev.stapler.stelekit.ui.screens.LibraryStatsViewModel
import dev.stapler.stelekit.ui.screens.SearchViewModel
import dev.stapler.stelekit.ui.state.BlockStateManager

internal fun graphContentLibraryStatsProvider(
    provider: LibraryStatsProvider,
    effectiveFileSystem: FileSystem,
): LibraryStatsProvider = provider.takeUnless { it === NoOpLibraryStatsProvider }
    ?: FileSystemLibraryStatsProvider(effectiveFileSystem)

/** Bundles [GraphContent]'s section-scoped Journals view model plus the other screen view models. */
internal class GraphContentSupportingViewModels(
    val activeSectionIds: List<String>?,
    val journalsViewModel: JournalsViewModel,
    val allPagesViewModel: AllPagesViewModel,
    val libraryStatsViewModel: LibraryStatsViewModel,
    val searchViewModel: SearchViewModel,
)

/**
 * Builds the Journals/AllPages/LibraryStats/Search view models for the active graph, and cancels
 * all of them (plus [blockStateManager] and [viewModel]) when [GraphContent] leaves composition
 * (`key(activeGraphId)` re-keys). Without this, orphaned scopes from the previous composition keep
 * running concurrently, causing duplicate graph loads and write-actor contention
 * (batchDeleteBlocks slowdowns).
 */
@Composable
internal fun rememberGraphContentSupportingViewModels(
    deps: GraphContentDeps,
    effectiveFileSystem: FileSystem,
    graphPath: String,
    blockStateManager: BlockStateManager,
    viewModel: StelekitViewModel,
): GraphContentSupportingViewModels {
    val repos = deps.repos
    val platformSettings = deps.platformSettings

    val activeSectionIds = remember(platformSettings) {
        val states = platformSettings.getSectionStates()
        if (states.isEmpty()) null
        else states.filterValues { it == SectionState.ACTIVE }.keys.toList()
    }
    val journalsViewModel = remember(repos, blockStateManager, activeSectionIds) {
        JournalsViewModel(repos.journalService, blockStateManager, activeSectionIds = activeSectionIds)
    }
    val allPagesViewModel = remember { AllPagesViewModel(repos.pageRepository, repos.blockRepository) }
    val libraryStatsViewModel = remember(deps.coreServices.libraryStatsProvider, effectiveFileSystem, graphPath) {
        val provider = graphContentLibraryStatsProvider(deps.coreServices.libraryStatsProvider, effectiveFileSystem)
        LibraryStatsViewModel(provider, graphPath)
    }
    val searchViewModel = remember { SearchViewModel(repos.searchRepository, pageRepository = repos.pageRepository) }

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

    return GraphContentSupportingViewModels(activeSectionIds, journalsViewModel, allPagesViewModel, libraryStatsViewModel, searchViewModel)
}
