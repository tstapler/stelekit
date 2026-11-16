package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.logging.LogSink
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 2.3b: thin ViewModel methods behind the drag-measure / aim-place gesture layer (ADR-007). */
class AnnotationEditorViewModelPlacementTest {

    private val image = ImageAnnotation(
        uuid = "img-place", blockUuid = "blk", pageUuid = "page", graphPath = "/test", filePath = "/test/i.jpg",
    )

    private var nowMs = 1_000L

    private fun vm() = AnnotationEditorViewModel(
        measurementRepository = InMemoryMeasurementAnnotationRepository(),
        imageWidthPx = 1000.0,
        imageHeightPx = 1000.0,
        nowMillis = { nowMs },
    ).also { it.initialize(image) }

    private class RecordingSink : LogSink {
        val lines = mutableListOf<String>()
        override fun write(entry: LogEntry, formatted: String) {
            if (entry.message.startsWith("placement ")) lines += entry.message
        }
    }

    private fun <T> recording(block: (RecordingSink) -> T): T {
        val sink = RecordingSink()
        val handle = LogManager.addSink(sink)
        try { return block(sink) } finally { handle.close() }
    }

    @Test
    fun placeDrag_should_CommitOneMeasurementWithOneUndoEntry_And_ReturnToSelect() {
        val vm = vm()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.placeDrag(NormalizedPoint(0.2, 0.3), NormalizedPoint(0.7, 0.8))
        val st = vm.state.value
        assertEquals(1, st.committedAnnotations.size)
        assertEquals(listOf(NormalizedPoint(0.2, 0.3), NormalizedPoint(0.7, 0.8)), st.committedAnnotations.single().normalizedPoints)
        assertEquals(AnnotationTool.SELECT, st.currentTool)
        vm.undo()
        assertTrue(!vm.canUndo.value, "exactly one undo entry")
        vm.close()
    }

    @Test
    fun discardFirstPoint_should_DropPendingPoint_And_KeepToolArmed() {
        val vm = vm()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.2, 0.3))
        vm.discardFirstPoint()
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)
        vm.close()
    }

    @Test
    fun disarm_should_DropPendingPoint_And_SelectSelect() {
        val vm = vm()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.2, 0.3))
        vm.disarm()
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)
        assertTrue(vm.state.value.committedAnnotations.isEmpty())
        vm.close()
    }

    @Test
    fun cancelPlacementGesture_should_ShowCancelled_KeepToolArmed_And_KeepFirstPoint() {
        val vm = vm()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.2, 0.3))
        vm.cancelPlacementGesture()
        assertEquals("Cancelled", vm.state.value.placementMessage)
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)
        assertEquals(1, vm.state.value.inProgressPoints.size)
        vm.addPoint(NormalizedPoint(0.6, 0.7))
        assertNull(vm.state.value.placementMessage)
        vm.close()
    }

    @Test
    fun dragMeasureCommit_should_LogDragMeasureOnce_WithoutCoordinates() = recording { sink ->
        val vm = vm()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.placeDrag(NormalizedPoint(0.2, 0.3), NormalizedPoint(0.7, 0.8))
        assertEquals(listOf("placement gesture=drag-measure"), sink.lines)
        vm.close()
    }

    @Test
    fun undoWithin5sOfDragMeasure_should_LogUndoAfterDragMeasureOnce() = recording { sink ->
        val vm = vm()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.placeDrag(NormalizedPoint(0.2, 0.3), NormalizedPoint(0.7, 0.8))
        nowMs += 4_999
        vm.undo()
        vm.redo()
        vm.undo()
        assertEquals(listOf("placement gesture=drag-measure", "placement undo-after-drag-measure"), sink.lines)
        vm.close()
    }

    @Test
    fun undoAfter5sOfDragMeasure_should_NotLogUndoCounter() = recording { sink ->
        val vm = vm()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.placeDrag(NormalizedPoint(0.2, 0.3), NormalizedPoint(0.7, 0.8))
        nowMs += 5_001
        vm.undo()
        assertEquals(listOf("placement gesture=drag-measure"), sink.lines)
        vm.close()
    }

    @Test
    fun undoAfterTapTapCommit_should_NotLogUndoCounter() = recording { sink ->
        val vm = vm()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.2, 0.3))
        vm.addPoint(NormalizedPoint(0.6, 0.7))
        vm.undo()
        assertTrue(sink.lines.isEmpty())
        vm.close()
    }
}
