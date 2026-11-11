@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.StelekitViewModel
import dev.stapler.stelekit.ui.StelekitViewModelDependencies
import dev.stapler.stelekit.ui.nav.NavModel
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

/** Story 5.0b: the flag round-trips through Settings, gates the bridge, and drives the nav list reactively. */
class HomeSketchFlagTest {
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

    private fun sketchRowPresent(enabled: Boolean) =
        NavModel.sections(Screen.Journals, enabled).flatMap { it.items }.any { it == NavModel.sketch }

    @Test
    fun flagDefaultsOff_And_RoundTripsThroughSettings() {
        val store = BridgeMapSettings()
        val flag = HomeSketchFlag(ProjectBridgeSettings(store))
        assertFalse(flag.enabled.value)

        flag.set(true)
        assertTrue(flag.enabled.value)
        assertTrue(store.getBoolean(ProjectBridgeSettings.HOME_SKETCH_KEY, false))
        assertTrue(HomeSketchFlag(ProjectBridgeSettings(store)).enabled.value)

        flag.set(false)
        assertFalse(store.getBoolean(ProjectBridgeSettings.HOME_SKETCH_KEY, true))
    }

    @Test
    fun flagGatesTheDigest_When_FlippedThroughTheFlag() {
        val settings = ProjectBridgeSettings(BridgeMapSettings())
        val flag = HomeSketchFlag(settings)
        settings.setDigestEnabled("g1", true)
        assertFalse(settings.isDigestActive("g1"))
        flag.set(true)
        assertTrue(settings.isDigestActive("g1"))
        flag.set(false)
        assertFalse(settings.isDigestActive("g1"))
    }

    @Test
    fun readLiveResyncs_When_StorageChangedOutOfBand() {
        val store = BridgeMapSettings()
        val flag = HomeSketchFlag(ProjectBridgeSettings(store))
        ProjectBridgeSettings(store).homeSketchEnabled = true
        assertFalse(flag.enabled.value)
        assertTrue(flag.readLive())
        assertTrue(flag.enabled.value)
    }

    @Test
    fun navEntryAppearsAndDisappears_When_ViewModelFlagFlips() = runTest {
        val vm = viewModel(BridgeMapSettings())
        advanceUntilIdle()
        assertFalse(sketchRowPresent(vm.homeSketchEnabledState.value))

        vm.setHomeSketchEnabled(true)
        assertTrue(vm.homeSketchEnabledState.value)
        assertTrue(sketchRowPresent(vm.homeSketchEnabledState.value))
        vm.navigateTo(Screen.Sketch())
        assertEquals(Screen.Sketch(), vm.uiState.value.currentScreen)

        vm.setHomeSketchEnabled(false)
        assertFalse(sketchRowPresent(vm.homeSketchEnabledState.value))
        assertEquals(Screen.Journals, vm.uiState.value.currentScreen)
    }
}
