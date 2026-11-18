// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ADR-007 placement matrix driven through the real gesture layer at 1080x2400 px (360x800 dp, 3x). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xxhdpi")
class AnnotationGestureLayerPlacementTest {
    @get:Rule
    val rule = createComposeRule()

    private val img = ImageAnnotation(
        uuid = "img-place-001",
        blockUuid = "blk-place-001",
        pageUuid = "page-place-001",
        graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    private val canvasPx = IntSize(1080, 2400)

    private class Recorder {
        val taps = mutableListOf<Offset>()
        val dragMeasures = mutableListOf<Pair<Offset, Offset>>()
        var cancels = 0
        var editStarts = 0
        val handleTaps = mutableListOf<Pair<String, Int>>()
    }

    private lateinit var vm: AnnotationEditorViewModel
    private val rec = Recorder()
    private var view by mutableStateOf(AnnotationViewTransform.Fit)

    private fun host(
        tool: AnnotationTool = AnnotationTool.DISTANCE,
        layerModifier: Modifier = Modifier.fillMaxSize(),
    ) {
        vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img); it.selectTool(tool) }
        val handle = AnnotationViewHandle(
            canvasSize = { canvasPx },
            canvasOrigin = { Offset.Zero },
            view = { view },
            geometry = { ViewGeometry(1080f, 2400f, 0f, 0f, 1080f, 2400f) },
            setView = { view = it },
        )
        rule.setContent {
            val state by vm.state.collectAsState()
            Box(Modifier.fillMaxSize()) {
                AnnotationGestureLayer(
                    currentTool = state.currentTool,
                    annotations = state.committedAnnotations,
                    handle = handle,
                    callbacks = AnnotationGestureCallbacks(
                        onTap = { p ->
                            rec.taps += p
                            p.toNormalized(canvasPx)?.let(vm::addPoint)
                        },
                        onLoupe = {},
                        onRipple = {},
                        onSelect = { uuid -> if (uuid == null) vm.clearSelection() else vm.selectAnnotation(uuid) },
                        onEditStart = { rec.editStarts++; vm.beginAnnotationEdit() },
                        onEditPoints = { uuid, pts -> vm.updateAnnotationPoints(uuid, pts) },
                        onEditEnd = { uuid -> vm.commitAnnotationEdit(uuid) },
                        onDragMeasure = { s, e ->
                            rec.dragMeasures += s to e
                            val a = s.toNormalized(canvasPx)
                            val b = e.toNormalized(canvasPx)
                            if (a != null && b != null) vm.placeDrag(a, b)
                        },
                        onPlacementCancelled = { rec.cancels++; vm.cancelPlacementGesture() },
                        onHandleTap = { uuid, index -> rec.handleTaps += uuid to index },
                    ),
                    modifier = layerModifier.testTag(LAYER_TAG),
                    pendingPoints = state.inProgressPoints.size,
                )
            }
        }
        rule.waitForIdle()
        // Measured in the sibling gesture tests: the first injected gesture on a fresh composition is dropped.
        rule.onNodeWithTag(LAYER_TAG).performTouchInput { down(Offset(540f, 1200f)); up() }
        rule.waitForIdle()
        vm.selectTool(tool)
        rec.taps.clear()
        rec.cancels = 0
        rule.waitForIdle()
    }

    private fun drag(path: List<Offset>) {
        rule.onNodeWithTag(LAYER_TAG).performTouchInput {
            down(path.first())
            path.drop(1).forEach { moveTo(it) }
            up()
        }
        rule.waitForIdle()
    }

    private fun state() = vm.state.value

    private fun assertPoint(expected: Offset, actual: NormalizedPoint) {
        assertEquals(expected.x.toDouble() / canvasPx.width, actual.x, 1e-6)
        assertEquals(expected.y.toDouble() / canvasPx.height, actual.y, 1e-6)
    }

    @Test
    fun dragMeasure_should_CreateOneMeasurement_And_AimPlaceUnchanged_And_SecondFingerCancels_And_OffCanvasCancelsKeepingToolArmed_And_OtherToolsPlaceOnePoint() {
        host()
        val press = Offset(200f, 600f)
        val release = Offset(700f, 1500f)
        drag(listOf(press, Offset(450f, 1000f), release))

        assertEquals(listOf(press to release), rec.dragMeasures)
        val measurement = state().committedAnnotations.single()
        assertEquals(2, measurement.normalizedPoints.size)
        assertPoint(press, measurement.normalizedPoints[0])
        assertPoint(release, measurement.normalizedPoints[1])
        assertEquals(AnnotationTool.SELECT, state().currentTool)
        assertTrue(rec.taps.isEmpty(), "a drag-measure must not also aim-place")
    }

    @Test
    fun aimPlace_should_PlaceOnePointAtRelease_When30dpDragThenLift() {
        host()
        val press = Offset(500f, 1000f)
        val release = Offset(590f, 1000f) // 90 px = 30 dp, under the 48 dp commit threshold
        drag(listOf(press, Offset(550f, 1000f), release))

        assertTrue(rec.dragMeasures.isEmpty())
        assertEquals(listOf(release), rec.taps)
        assertPoint(release, state().inProgressPoints.single())
        assertTrue(state().committedAnnotations.isEmpty())
        assertEquals(AnnotationTool.DISTANCE, state().currentTool)
    }

    @Test
    fun aimThenReturn_should_BeAimPlace_And_LeaveNoMeasurement() {
        host()
        val release = Offset(560f, 1000f) // back within 48 dp (144 px) of the press
        drag(listOf(Offset(500f, 1000f), Offset(800f, 1000f), release))

        assertTrue(rec.dragMeasures.isEmpty())
        assertEquals(listOf(release), rec.taps)
        assertTrue(state().committedAnnotations.isEmpty())
        assertEquals(1, state().inProgressPoints.size)
    }

    @Test
    fun tapTap_should_CommitOnSecondTap_And_PendingPointDragPlacesSecondAtRelease() {
        host()
        drag(listOf(Offset(300f, 600f)))
        assertEquals(1, state().inProgressPoints.size)
        val release = Offset(900f, 1800f)
        drag(listOf(Offset(300f, 1200f), Offset(600f, 1500f), release)) // far drag with a pending point is never a drag-measure

        assertTrue(rec.dragMeasures.isEmpty())
        val measurement = state().committedAnnotations.single()
        assertPoint(Offset(300f, 600f), measurement.normalizedPoints[0])
        assertPoint(release, measurement.normalizedPoints[1])
    }

    @Test
    fun dragBelowFit_should_AimPlaceOnePoint_And_AtOrAboveFitDragMeasures() {
        view = AnnotationViewTransform(scale = 0.5f, offsetX = 270f, offsetY = 600f)
        host()
        val release = Offset(700f, 1500f)
        drag(listOf(Offset(200f, 600f), Offset(450f, 1000f), release))

        assertTrue(rec.dragMeasures.isEmpty())
        assertEquals(1, rec.taps.size)
        assertPoint(Offset((700f - 270f) / 0.5f, (1500f - 600f) / 0.5f), state().inProgressPoints.single())

        view = AnnotationViewTransform(scale = 3f)
        vm.cancelInProgress()
        rule.waitForIdle()
        drag(listOf(Offset(200f, 600f), Offset(450f, 1000f), release))
        assertEquals(1, rec.dragMeasures.size)
    }

    @Test
    fun secondFinger_should_DropPlacement_KeepToolArmedAndFirstPoint() {
        host()
        drag(listOf(Offset(300f, 600f)))
        rule.onNodeWithTag(LAYER_TAG).performTouchInput {
            down(0, Offset(500f, 1000f))
            moveTo(0, Offset(700f, 1400f))
            down(1, Offset(800f, 1500f))
            moveTo(1, Offset(850f, 1550f))
            up(0)
            up(1)
        }
        rule.waitForIdle()

        assertEquals(AnnotationTool.DISTANCE, state().currentTool)
        assertEquals(1, state().inProgressPoints.size)
        assertPoint(Offset(300f, 600f), state().inProgressPoints.single())
        assertTrue(state().committedAnnotations.isEmpty())
        assertEquals(1, rec.taps.size, "only the first tap placed anything")
        assertTrue(rec.dragMeasures.isEmpty())
    }

    @Test
    fun releaseOutsideLayerBounds_should_CancelKeepingToolArmedAndFirstPoint() {
        // A 900x2100 px layer inside the 1080x2400 window so a release can land genuinely off-canvas.
        host(layerModifier = Modifier.size(300.dp, 700.dp))
        drag(listOf(Offset(300f, 600f)))
        drag(listOf(Offset(500f, 1000f), Offset(800f, 1000f), Offset(960f, 1000f)))
        assertCancelledKeepingFirstPoint()
    }

    private fun assertCancelledKeepingFirstPoint() {
        assertEquals(1, rec.cancels)
        assertEquals(AnnotationTool.DISTANCE, state().currentTool)
        assertEquals(1, state().inProgressPoints.size)
        assertTrue(state().committedAnnotations.isEmpty())
        assertEquals("Cancelled", state().placementMessage)
    }

    @Test
    fun otherTools_should_PlaceOnePointAtRelease_Never_AMeasurement() {
        host()
        val release = Offset(700f, 1500f)
        listOf(AnnotationTool.AREA, AnnotationTool.ANGLE, AnnotationTool.LABEL, AnnotationTool.GRID_REF).forEach { tool ->
            vm.selectTool(tool)
            rule.waitForIdle()
            rec.taps.clear()
            drag(listOf(Offset(200f, 600f), Offset(450f, 1000f), release))
            assertEquals(listOf(release), rec.taps, "tool $tool")
            assertTrue(rec.dragMeasures.isEmpty(), "tool $tool")
            assertTrue(state().committedAnnotations.isEmpty(), "tool $tool")
        }
    }

    @Test
    fun immediateRepressAfterAutoReturn_should_NotBeLost() {
        host()
        val a = Offset(200f, 600f)
        drag(listOf(a, Offset(450f, 1000f), Offset(700f, 1500f)))
        assertEquals(AnnotationTool.SELECT, state().currentTool)

        // No settle between the commit and the next press: the gesture loop must already be listening.
        val moved = Offset(260f, 700f)
        rule.onNodeWithTag(LAYER_TAG).performTouchInput { down(a); moveTo(Offset(230f, 650f)); moveTo(moved); up() }
        rule.waitForIdle()

        assertEquals(1, rec.editStarts, "the re-press on A's handle started an edit")
        val points = state().committedAnnotations.single().normalizedPoints
        assertPoint(moved, points[0])
        assertNotNull(state().selectedAnnotationUuid)
    }

    @Test
    fun selectTapOnHandle_should_ReportHandle_And_DragOrEmptyTapShouldNot() {
        host()
        drag(listOf(Offset(200f, 600f), Offset(450f, 1000f), Offset(700f, 1500f)))
        val uuid = state().committedAnnotations.single().uuid
        assertEquals(AnnotationTool.SELECT, state().currentTool)

        drag(listOf(Offset(700f, 1500f))) // first tap after the commit only (re)selects
        assertTrue(rec.handleTaps.isEmpty(), "a tap right after a commit must not open the nudge pad")
        drag(listOf(Offset(700f, 1500f))) // deliberate second tap on the selected handle
        assertEquals(listOf(uuid to 1), rec.handleTaps)

        drag(listOf(Offset(200f, 600f), Offset(260f, 700f), Offset(320f, 800f))) // dragging a handle moves it instead
        assertEquals(1, rec.handleTaps.size)
        drag(listOf(Offset(1000f, 2300f))) // empty space
        assertEquals(1, rec.handleTaps.size)
    }

    private companion object {
        const val LAYER_TAG = "gesture-layer"
    }
}
