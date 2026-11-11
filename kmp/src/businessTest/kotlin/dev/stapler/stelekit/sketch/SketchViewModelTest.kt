@file:OptIn(DirectRepositoryWrite::class, ExperimentalCoroutinesApi::class)

package dev.stapler.stelekit.sketch

import arrow.core.left
import dev.stapler.stelekit.model.ProjectUuid
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

    @Test
    fun selectAndDeleteSelectedRemovesThePrimitiveAndSavesTheDeletion() = runTest {
        val f = fixture()
        f.vm.placePoint(pt(0, 0)); f.vm.placePoint(pt(16, 0))
        f.vm.select("id-1")
        assertEquals("id-1", f.vm.uiState.value.selectedId)
        f.vm.deleteSelected()
        assertTrue(f.vm.uiState.value.document.scene.primitives.isEmpty())
        assertNull(f.vm.uiState.value.selectedId)
        f.vm.flush()
        assertTrue(f.storedPrimitives().isEmpty())
    }

    @Test
    fun deleteSelectedWithNothingSelectedIsANoOp() = runTest {
        val f = fixture()
        f.vm.deleteSelected()
        assertEquals(0, f.vm.uiState.value.history.undoDepth)
    }

    @Test
    fun failedCommandSurfacesAnErrorAndKeepsTheDocument() = runTest {
        val f = fixture()
        f.vm.commit(dev.stapler.stelekit.sketch.SketchCommand.RemovePrimitive("missing"))
        assertTrue(f.vm.uiState.value.error!!.contains("no such shape"))
        assertEquals(0, f.vm.uiState.value.history.undoDepth)
    }

    @Test
    fun zoneSelectToolPlacesNothing() = runTest {
        val f = fixture()
        f.vm.setTool(SketchTool.ZONE_SELECT)
        f.vm.placePoint(pt(0, 0))
        assertNull(f.vm.uiState.value.pendingStart)
        f.vm.placePoint(pt(0, 0))
    }

    @Test
    fun placingTheAnchorTwiceCommitsNothing() = runTest {
        val f = fixture()
        f.vm.placePoint(pt(0, 0)); f.vm.placePoint(pt(0, 0))
        assertEquals(0, f.vm.uiState.value.history.undoDepth)
    }

    @Test
    fun unknownProjectFailsLoading() = runTest {
        val vm = SketchViewModel(inMemorySketchRepositories(), ProjectUuid("nope"), null, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(SketchPhase.FAILED, vm.uiState.value.phase)
        vm.onRemembered(); vm.onAbandoned()
    }

    @Test
    fun noProjectIdOpensTheFirstProjectOrCreatesOne() = runTest {
        val repos = inMemorySketchRepositories()
        var n = 0
        val created = SketchViewModel(repos, null, null, StandardTestDispatcher(testScheduler)) { "gen-${n++}" }
        runCurrent()
        assertEquals(SketchPhase.READY, created.uiState.value.phase)
        val projects = repos.projects.getProjects(10, 0).first().getOrNull()!!
        assertEquals(listOf("gen-0"), projects.map { it.uuid.value })
        assertTrue(projects.single().slug.startsWith("proj-"))
        val reopened = SketchViewModel(repos, null, null, StandardTestDispatcher(testScheduler)) { "gen-${n++}" }
        runCurrent()
        assertEquals(SketchPhase.READY, reopened.uiState.value.phase)
        assertEquals(1, repos.projects.getProjects(10, 0).first().getOrNull()!!.size)
        created.onForgotten(); reopened.onForgotten()
    }

    @Test
    fun routerFactoryBuildsAViewModelForTheRouteUuid() = runTest {
        val repos = inMemorySketchRepositories()
        repos.projects.saveProject(SketchTestSupport.project())
        val vm = dev.stapler.stelekit.ui.sketch.sketchViewModelFor(repos, null, "p1")
        vm.onForgotten()
    }

    @Test
    fun loadsMoreThanOnePageOfPrimitivesAndSkipsUnknownKindsAndMalformedZones() = runTest {
        val repos = inMemorySketchRepositories()
        repos.projects.saveProject(SketchTestSupport.project())
        val sheet = SketchTestSupport.sheet("s1").copy(
            zones = kotlinx.serialization.json.JsonArray(
                listOf(
                    kotlinx.serialization.json.JsonPrimitive("junk"),
                    kotlinx.serialization.json.JsonObject(mapOf("nokey" to kotlinx.serialization.json.JsonPrimitive(1))),
                    kotlinx.serialization.json.JsonObject(mapOf("key" to kotlinx.serialization.json.JsonPrimitive("z"), "total" to kotlinx.serialization.json.JsonPrimitive(true))),
                ),
            ),
        )
        val prims = (0 until 620).map { SketchTestSupport.primitive("pr$it", "s1", it.toLong()).copy(kind = if (it == 0) "circle" else if (it == 1) "rect" else "line", y1 = it.toLong(), y2 = it.toLong()) }
        repos.projects.replaceProjectContents(
            SketchTestSupport.P,
            dev.stapler.stelekit.model.ProjectBundle(SketchTestSupport.project(), listOf(sheet), prims, emptyList()),
        )
        val vm = SketchViewModel(repos, SketchTestSupport.P, null, StandardTestDispatcher(testScheduler))
        runCurrent()
        val s = vm.uiState.value
        assertEquals(619, s.document.scene.size, "the circle is skipped, everything else across two pages loads")
        assertEquals(listOf(dev.stapler.stelekit.sketch.Zone("z", null, true)), s.document.zones.zones)
        vm.onForgotten()
    }

    private class FailingSheets(private val inner: dev.stapler.stelekit.repository.SketchSheetRepository) :
        dev.stapler.stelekit.repository.SketchSheetRepository by inner {
        override suspend fun savePrimitives(
            primitives: List<dev.stapler.stelekit.model.SketchPrimitive>,
            token: dev.stapler.stelekit.model.RevisionToken,
        ) = dev.stapler.stelekit.error.DomainError.DatabaseError.WriteFailed("stale-editor").left()
    }

    @Test
    fun staleEditorSaveFailureIsSurfacedAndTriggersAReload() = runTest {
        val base = inMemorySketchRepositories()
        base.projects.saveProject(SketchTestSupport.project())
        val vm = SketchViewModel(base.copy(sheets = FailingSheets(base.sheets)), SketchTestSupport.P, null, StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.placePoint(pt(0, 0)); vm.placePoint(pt(16, 0))
        vm.flush()
        runCurrent()
        assertEquals(SketchPhase.READY, vm.uiState.value.phase, "reloaded after the stale-editor failure")
        assertTrue(vm.uiState.value.document.scene.primitives.isEmpty(), "reload discards the unsaved edit")
        vm.onForgotten()
    }
}
