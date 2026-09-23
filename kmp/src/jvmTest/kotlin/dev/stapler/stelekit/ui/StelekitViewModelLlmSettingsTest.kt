// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Epic 6 Story 6.1: [AppState.llmProviderSettingsVisible] + [StelekitViewModel]'s
 * open/dismiss pair, structurally identical to gitSetupVisible/openGitSetup()/dismissGitSetup().
 */
class StelekitViewModelLlmSettingsTest {

    private fun makeViewModel(): StelekitViewModel {
        val fileSystem = FakeFileSystem()
        val pageRepo = FakePageRepository()
        val blockRepo = FakeBlockRepository()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo)
        val graphWriter = GraphWriter(PlatformFileSystem())
        val searchRepo = InMemorySearchRepository()
        return StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fileSystem,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = searchRepo,
                graphLoader = graphLoader,
                graphWriter = graphWriter,
                platformSettings = InMemorySettings(),
                scope = scope,
            )
        )
    }

    @Test
    fun `openLlmProviderSettings should SetVisibleTrue And dismiss should SetFalse`() {
        val vm = makeViewModel()

        assertFalse(vm.uiState.value.llmProviderSettingsVisible, "should default to false")

        vm.openLlmProviderSettings()
        assertTrue(vm.uiState.value.llmProviderSettingsVisible)

        vm.dismissLlmProviderSettings()
        assertFalse(vm.uiState.value.llmProviderSettingsVisible)
    }
}
