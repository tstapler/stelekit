@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.StelekitViewModel
import dev.stapler.stelekit.ui.StelekitViewModelDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 3.3: with `homeSketch` off the Sketch route cannot be navigated to; with it on it can. */
class SketchNavigationGateTest {
    private var viewModel: StelekitViewModel? = null

    @AfterTest
    fun cleanup() {
        viewModel?.close()
    }

    private fun TestScope.viewModel(settings: BridgeMapSettings): StelekitViewModel {
        val pages = InMemoryPageRepository()
        val blocks = InMemoryBlockRepository()
        val fs = StubFileSystem()
        return StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fs,
                pageRepository = pages,
                blockRepository = blocks,
                searchRepository = InMemorySearchRepository(),
                graphLoader = GraphLoader(fs, pages, blocks),
                graphWriter = GraphWriter(fs),
                platformSettings = settings,
                scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
            ),
        ).also { viewModel = it }
    }

    @Test
    fun flagOffMeansNoRouteAndNoNavigationEntry() = runTest {
        val vm = viewModel(BridgeMapSettings())
        advanceUntilIdle()
        val before = vm.uiState.value.currentScreen
        assertFalse(vm.homeSketchEnabled)
        vm.navigateTo(Screen.Sketch())
        assertEquals(before, vm.uiState.value.currentScreen)
    }

    @Test
    fun flagOnAllowsTheSketchRoute() = runTest {
        val settings = BridgeMapSettings().also { ProjectBridgeSettings(it).homeSketchEnabled = true }
        val vm = viewModel(settings)
        advanceUntilIdle()
        assertTrue(vm.homeSketchEnabled)
        vm.navigateTo(Screen.Sketch())
        assertEquals(Screen.Sketch(), vm.uiState.value.currentScreen)
    }
}
