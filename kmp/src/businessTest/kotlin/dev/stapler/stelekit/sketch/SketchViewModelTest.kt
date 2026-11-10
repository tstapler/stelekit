@file:OptIn(DirectRepositoryWrite::class, ExperimentalCoroutinesApi::class)

package dev.stapler.stelekit.sketch

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.ui.sketch.SaveStatus
import dev.stapler.stelekit.ui.sketch.SketchPhase
import dev.stapler.stelekit.ui.sketch.SketchTool
import dev.stapler.stelekit.ui.sketch.SketchViewModel
import dev.stapler.stelekit.units.Inches
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SketchViewModelTest {
    private fun pt(x: Long, y: Long) = SketchPoint(Inches(x), Inches(y))

    /** `id-0` is taken by the blank sheet the view model creates, so shapes start at `id-1`. */
    private class Fixture(scope: TestScope, val repos: SketchRepositories = inMemorySketchRepositories()) {
        private var n = 0
        val vm = SketchViewModel(repos, SketchTestSupport.P, null, StandardTestDispatcher(scope.testScheduler)) { "id-${n++}" }
    }

    private suspend fun TestScope.fixture(): Fixture {
        val repos = inMemorySketchRepositories()
        repos.projects.saveProject(SketchTestSupport.project())
        return Fixture(this, repos).also { runCurrent() }
    }

    private suspend fun Fixture.storedPrimitives(): List<String> {
        val sheet = repos.sheets.getSheets(SketchTestSupport.P).first().getOrNull()!!.items.single()
        return repos.sheets.getPrimitives(sheet.uuid).first().getOrNull()!!.map { it.uuid }
    }

    @Test
    fun loadsAndCreatesABlankSheetWhenTheProjectHasNone() = runTest {
        val f = fixture()
        assertEquals(SketchPhase.READY, f.vm.uiState.value.phase)
        assertEquals(1, f.repos.sheets.getSheets(SketchTestSupport.P).first().getOrNull()!!.items.size)
    }

    @Test
    fun twoPlacementsCommitOneLineAndOneHistoryStep() = runTest {
        val f = fixture()
        f.vm.placePoint(pt(16, 16))
        assertEquals(pt(16, 16), f.vm.uiState.value.pendingStart)
        assertEquals(0, f.vm.uiState.value.history.undoDepth)
        f.vm.placePoint(pt(64, 16))
        val s = f.vm.uiState.value
        assertNull(s.pendingStart)
        assertEquals(1, s.history.undoDepth)
        assertEquals(SketchPrimitive.Line("id-1", pt(16, 16), pt(64, 16)), s.document.scene.primitives.single())
    }

    @Test
    fun rectangleToolCommitsARect() = runTest {
        val f = fixture()
        f.vm.setTool(SketchTool.RECTANGLE)
        f.vm.placePoint(pt(0, 0))
        f.vm.placePoint(pt(32, 48))
        assertTrue(f.vm.uiState.value.document.scene.primitives.single() is SketchPrimitive.Rect)
    }

    @Test
    fun undoNeverResurrectsAnInProgressPoint() = runTest {
        val f = fixture()
        f.vm.placePoint(pt(0, 0))
        f.vm.placePoint(pt(16, 0))
        f.vm.placePoint(pt(0, 16)) // in progress
        f.vm.undo()
        assertNull(f.vm.uiState.value.pendingStart)
        assertTrue(f.vm.uiState.value.document.scene.primitives.isEmpty())
        f.vm.redo()
        assertNull(f.vm.uiState.value.pendingStart)
        assertEquals(1, f.vm.uiState.value.document.scene.size)
    }

    @Test
    fun autosaveIsDebouncedAndSavesOnceForABurst() = runTest {
        val f = fixture()
        f.vm.placePoint(pt(0, 0)); f.vm.placePoint(pt(16, 0))
        advanceTimeBy(300); runCurrent()
        f.vm.placePoint(pt(0, 16)); f.vm.placePoint(pt(16, 16))
        advanceTimeBy(300); runCurrent()
        assertEquals(SaveStatus.PENDING, f.vm.uiState.value.saveStatus)
        assertTrue(f.storedPrimitives().isEmpty())
        advanceTimeBy(250); runCurrent()
        assertEquals(listOf("id-1", "id-2"), f.storedPrimitives().sorted())
        assertEquals(SaveStatus.SAVED, f.vm.uiState.value.saveStatus)
    }

    @Test
    fun undoneShapeIsDeletedFromTheStoreOnNextSave() = runTest {
        val f = fixture()
        f.vm.placePoint(pt(0, 0)); f.vm.placePoint(pt(16, 0))
        f.vm.flush()
        assertEquals(1, f.storedPrimitives().size)
        f.vm.undo()
        f.vm.flush()
        assertTrue(f.storedPrimitives().isEmpty())
    }

    @Test
    fun rebuiltProjectCancelsPendingAutosaveAndReloads() = runTest {
        val f = fixture()
        f.vm.placePoint(pt(0, 0)); f.vm.placePoint(pt(16, 0)) // pending autosave carrying the old token
        f.repos.projects.replaceProjectContents(SketchTestSupport.P, SketchTestSupport.bundle("p1", sheets = 1, primitivesPerSheet = 2))
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        val s = f.vm.uiState.value
        assertEquals(SketchPhase.READY, s.phase)
        assertEquals(setOf("p1-s0-pr0", "p1-s0-pr1"), s.document.scene.primitives.map { it.id }.toSet())
        assertFalse(s.error != null, "stale save must not surface: ${s.error}")
        assertEquals(setOf("p1-s0-pr0", "p1-s0-pr1"), f.storedPrimitives().toSet())
    }

    @Test
    fun typingZoneLengthReSolvesAndFlagsAViolatedSum() = runTest {
        val f = fixture()
        f.vm.addZone(Zone("total", isTotal = true))
        f.vm.addZone(Zone("a"))
        f.vm.addZone(Zone("b"))
        f.vm.typeZoneText("total", "36")
        f.vm.typeZoneText("a", "12 3/16")
        assertFalse(f.vm.uiState.value.zoneReport!!.sumViolated)
        f.vm.typeZoneText("b", "20")
        assertTrue(f.vm.uiState.value.zoneReport!!.sumViolated)
        f.vm.flush()
        val sheet = f.repos.sheets.getSheets(SketchTestSupport.P).first().getOrNull()!!.items.single()
        assertEquals(3, sheet.zones.size)
    }

    @Test
    fun placementIsIgnoredBeforeTheSheetLoaded() = runTest {
        val repos = inMemorySketchRepositories()
        repos.projects.saveProject(SketchTestSupport.project())
        val f = Fixture(this, repos) // no runCurrent: still loading
        f.vm.placePoint(pt(0, 0))
        assertNull(f.vm.uiState.value.pendingStart)
        runCurrent()
        assertEquals(SheetUuid("id-0").value, repos.sheets.getSheets(SketchTestSupport.P).first().getOrNull()!!.items.single().uuid.value)
    }
}
