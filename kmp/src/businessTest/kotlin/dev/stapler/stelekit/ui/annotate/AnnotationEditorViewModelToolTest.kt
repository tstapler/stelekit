package dev.stapler.stelekit.ui.annotate

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.ANNOTATOR_AUTO_RETURN_TO_SELECT_KEY
import dev.stapler.stelekit.platform.ANNOTATOR_STICKY_TOOLS_KEY
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.repository.MeasurementAnnotationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-005: one-shot placement tools return to SELECT after a commit; LOCKED and the settings opt out. */
class AnnotationEditorViewModelToolTest {

    private class MapSettings(vararg initial: Pair<String, Boolean>) : Settings {
        private val values = mutableMapOf<String, Any>().apply { initial.forEach { put(it.first, it.second) } }
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun containsKey(key: String) = key in values
    }

    /** Fails every row save with a Left once [gate] opens, so a test can act before the rollback lands. */
    @OptIn(DirectRepositoryWrite::class)
    private class FailingRepository(
        private val gate: CompletableDeferred<Unit>,
        private val delegate: MeasurementAnnotationRepository = InMemoryMeasurementAnnotationRepository(),
    ) : MeasurementAnnotationRepository by delegate {
        override suspend fun saveMeasurementAnnotation(measurement: MeasurementAnnotation): Either<DomainError, Unit> {
            gate.await()
            return DomainError.DatabaseError.WriteFailed("boom").left()
        }
    }

    private val image = ImageAnnotation(
        uuid = "img-tool", blockUuid = "blk", pageUuid = "page", graphPath = "/test", filePath = "/test/i.jpg",
    )

    private fun vm(
        settings: Settings? = null,
        repo: MeasurementAnnotationRepository = InMemoryMeasurementAnnotationRepository(),
    ) = AnnotationEditorViewModel(
        measurementRepository = repo,
        imageWidthPx = 1000.0,
        imageHeightPx = 1000.0,
        settings = settings,
    ).also { it.initialize(image) }

    private fun AnnotationEditorViewModel.drawDistance() {
        addPoint(NormalizedPoint(0.2, 0.3))
        addPoint(NormalizedPoint(0.6, 0.7))
    }

    private fun AnnotationEditorViewModel.await(condition: (AnnotationEditorState) -> Boolean) = runBlocking {
        withTimeout(5_000) { state.first(condition) }
    }

    @Test
    fun freshViewModel_should_StartInSelect_AndAutoReturnAfterDistanceCommit() {
        val vm = vm()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)

        vm.selectTool(AnnotationTool.DISTANCE)
        assertTrue(!vm.canUndo.value)
        vm.drawDistance()

        val st = vm.state.value
        assertEquals(AnnotationTool.SELECT, st.currentTool)
        assertEquals(st.committedAnnotations.single().uuid, st.selectedAnnotationUuid)
        assertTrue(st.inProgressPoints.isEmpty())
        assertTrue(vm.canUndo.value)
        vm.undo()
        assertTrue(!vm.canUndo.value, "exactly one undo step")
        vm.close()
    }

    @Test
    fun lockedStickiness_should_KeepToolAfterCommit() {
        val vm = vm()
        vm.setStickiness(ToolStickiness.LOCKED)
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.drawDistance()
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)
        assertEquals(1, vm.state.value.committedAnnotations.size)
        vm.close()
    }

    @Test
    fun autoReturnSettingOff_should_KeepToolAfterCommit() {
        val vm = vm(MapSettings(ANNOTATOR_AUTO_RETURN_TO_SELECT_KEY to false))
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.drawDistance()
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)
        assertNull(vm.state.value.selectedAnnotationUuid)
        vm.close()
    }

    @Test
    fun stickyToolsSetting_should_StartLocked() {
        val vm = vm(MapSettings(ANNOTATOR_STICKY_TOOLS_KEY to true))
        assertEquals(ToolStickiness.LOCKED, vm.state.value.stickiness)
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.drawDistance()
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)
        vm.close()
    }

    @Test
    fun multiStepSessionsGridRefRulerAreaPlane_should_StayArmedUntilConfirm() {
        val vm = vm()

        // GRID_REF two-point reference: dialog open, tool stays until the calibration is confirmed.
        vm.selectTool(AnnotationTool.GRID_REF)
        vm.drawDistance()
        assertEquals(AnnotationTool.GRID_REF, vm.state.value.currentTool)
        vm.updateGridRefLengthText("1")
        vm.confirmGridRefCalibration()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)

        // Ruler session: ticks keep GRID_REF armed; confirm returns to SELECT.
        vm.beginRulerSession()
        vm.addPoint(NormalizedPoint(0.1, 0.5))
        vm.addPoint(NormalizedPoint(0.5, 0.5))
        vm.updateRulerTickValue(0, "0")
        vm.updateRulerTickValue(1, "10")
        assertEquals(AnnotationTool.GRID_REF, vm.state.value.currentTool)
        vm.confirmRulerSession()
        assertNull(vm.state.value.rulerSession)
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)

        // AREA: open-ended, stays armed while vertices are placed; closing commits and returns.
        vm.selectTool(AnnotationTool.AREA)
        vm.addPoint(NormalizedPoint(0.1, 0.1))
        vm.addPoint(NormalizedPoint(0.5, 0.1))
        vm.addPoint(NormalizedPoint(0.5, 0.5))
        assertEquals(AnnotationTool.AREA, vm.state.value.currentTool)
        vm.closePolygon()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)

        // Plane session: corners keep GRID_REF armed; confirm returns to SELECT.
        vm.beginPlaneSession()
        vm.setPlaneRectangle(dev.stapler.stelekit.calibration.ReferenceRectangle.LETTER)
        listOf(0.1 to 0.1, 0.3794 to 0.1, 0.3794 to 0.3159, 0.1 to 0.3159).forEach { (x, y) ->
            vm.addPoint(NormalizedPoint(x, y))
        }
        assertEquals(AnnotationTool.GRID_REF, vm.state.value.currentTool)
        assertTrue(vm.planePreview()!!.canApply)
        vm.confirmPlaneSession()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)
        vm.close()
    }

    @Test
    fun undoRedo_should_NotRearmTool_And_NotChangeStickiness() {
        val vm = vm()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.drawDistance()
        vm.undo()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)
        vm.redo()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)

        vm.setStickiness(ToolStickiness.LOCKED)
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.drawDistance()
        vm.undo()
        assertEquals(ToolStickiness.LOCKED, vm.state.value.stickiness)
        vm.redo()
        assertEquals(ToolStickiness.LOCKED, vm.state.value.stickiness)
        vm.close()
    }

    @Test
    fun persistFailure_should_RollbackMeasurementAndRestoreToolOnlyIfUserHasNotChosenAnother() {
        // No other tool chosen: the previous tool comes back with the rollback.
        val gate1 = CompletableDeferred<Unit>()
        val a = vm(repo = FailingRepository(gate1))
        a.selectTool(AnnotationTool.DISTANCE)
        a.drawDistance()
        assertEquals(AnnotationTool.SELECT, a.state.value.currentTool)
        gate1.complete(Unit)
        a.await { it.committedAnnotations.isEmpty() }
        assertEquals(AnnotationTool.DISTANCE, a.state.value.currentTool)
        a.close()

        // The user picked AREA before the failure landed: that choice wins.
        val gate2 = CompletableDeferred<Unit>()
        val b = vm(repo = FailingRepository(gate2))
        b.selectTool(AnnotationTool.DISTANCE)
        b.drawDistance()
        b.selectTool(AnnotationTool.AREA)
        gate2.complete(Unit)
        b.await { it.committedAnnotations.isEmpty() }
        assertEquals(AnnotationTool.AREA, b.state.value.currentTool)
        b.close()
    }
}
