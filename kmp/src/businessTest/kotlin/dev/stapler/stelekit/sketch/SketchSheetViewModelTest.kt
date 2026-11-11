@file:OptIn(DirectRepositoryWrite::class, ExperimentalCoroutinesApi::class)

package dev.stapler.stelekit.sketch

import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.ui.sketch.SketchViewModel
import dev.stapler.stelekit.units.Inches
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Story 3.5 through the view model: typed lengths, layers, sheet settings and zones persist and reload. */
class SketchSheetViewModelTest {
    private fun pt(x: Long, y: Long) = SketchPoint(Inches(x), Inches(y))

    private fun TestScope.viewModel(repos: SketchRepositories): SketchViewModel {
        var n = 0
        return SketchViewModel(repos, SketchTestSupport.P, null, StandardTestDispatcher(testScheduler)) { "id-${n++}" }
            .also { runCurrent() }
    }

    @Test
    fun sheetSettingsLayersAndLengthsSurviveAReload() = runTest {
        val repos = inMemorySketchRepositories().also { it.projects.saveProject(SketchTestSupport.project()) }
        val vm = viewModel(repos)
        vm.placePoint(pt(0, 0)); vm.placePoint(pt(64, 0))
        val lineId = vm.uiState.value.document.scene.primitives.single().id
        vm.setLineLength(lineId, Inches(10 * 16 + 4))
        vm.addLayer("wall")
        vm.select(lineId)
        vm.setSelectedLayer("wall")
        vm.setLayerLocked("wall", true)
        vm.setSheetKind(SketchSheetKind.ELEVATION)
        vm.setViewDirection(ViewDirection.FROM_BACK)
        vm.setScale(8)
        vm.flush()

        val reloaded = viewModel(repos).uiState.value.document
        assertEquals(SketchSheetKind.ELEVATION, reloaded.sheet.kind)
        assertEquals(ViewDirection.FROM_BACK, reloaded.sheet.viewDirection)
        assertEquals(8L, reloaded.sheet.scaleSixteenths)
        assertEquals(listOf(SketchLayer("wall", visible = true, locked = true)), reloaded.sheet.layers)
        val line = reloaded.scene.primitives.single() as SketchPrimitive.Line
        assertEquals("wall", line.layer)
        assertEquals(pt(10 * 16 + 4, 0), line.b)
    }

    @Test
    fun refusedEditsSurfaceAPlainMessageAndChangeNothing() = runTest {
        val repos = inMemorySketchRepositories().also { it.projects.saveProject(SketchTestSupport.project()) }
        val vm = viewModel(repos)
        vm.placePoint(pt(0, 0)); vm.placePoint(pt(32, 32))
        val id = vm.uiState.value.document.scene.primitives.single().id
        val depth = vm.uiState.value.history.undoDepth
        vm.setLineLength(id, Inches(16))
        val s = vm.uiState.value
        assertEquals(depth, s.history.undoDepth)
        assertNotNull(s.error)
        assertTrue(s.error!!.contains("horizontal or vertical"), s.error)
    }

    @Test
    fun zoneTotalToggleAndFromBackDirectionReachTheReport() = runTest {
        val repos = inMemorySketchRepositories().also { it.projects.saveProject(SketchTestSupport.project()) }
        val vm = viewModel(repos)
        vm.addZone(Zone("total")); vm.addZone(Zone("a")); vm.addZone(Zone("b"))
        vm.setZoneTotal("total", true)
        vm.typeZoneText("total", "36"); vm.typeZoneText("a", "20"); vm.typeZoneText("b", "20")
        assertTrue(vm.uiState.value.zoneReport!!.sumViolated)
        vm.setZoneTotal("total", false)
        assertEquals(false, vm.uiState.value.zoneReport!!.sumViolated)
        vm.flush()
        val stored = repos.sheets.getSheets(SketchTestSupport.P).first().getOrNull()!!.items.single()
        assertEquals(3, stored.zones.size)
    }
}
