// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** X-11 / X-12 (UX-11, UX-12): the armed state is visible twice, every disarm path reaches Select, and tap-tap equals drag. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ToolDisarmTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val img = ImageAnnotation(
        uuid = "img-disarm-001", blockUuid = "blk-disarm-001", pageUuid = "page-disarm-001",
        graphPath = "/test", filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    private fun launch(tool: AnnotationTool, vararg points: NormalizedPoint): AnnotationEditorViewModel {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        vm.selectTool(tool)
        points.forEach { vm.addPoint(it) }
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()
        return vm
    }

    private fun canvas() = rule.onNodeWithContentDescription(ANNOTATION_CANVAS_DESCRIPTION)

    private fun warmUp(vm: AnnotationEditorViewModel) {
        // The first gesture after a fresh composition or a commit is dropped before the pointer handler listens (see AnnotationZoomPanTest).
        canvas().performTouchInput { down(Offset(300f, 20f)); up() }
        rule.waitForIdle()
        vm.cancelInProgress()
    }

    private fun clickChip(label: String) {
        rule.onNode(hasContentDescription(label)).performClick()
        // A chip waits out the double-tap window (Keep tool) before a single tap counts.
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun assertDisarmedWithNothingCreated(vm: AnnotationEditorViewModel) {
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)
        assertTrue(vm.state.value.committedAnnotations.isEmpty())
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
    }

    @Test
    fun armedState_should_ShowChipAndStripAndDisarmViaChipSelectBack_AndNotViaSecondFingerOrOffCanvasCancel() {
        val vm = launch(AnnotationTool.DISTANCE, NormalizedPoint(0.2, 0.2))
        rule.onNode(hasContentDescription("Distance")).assertIsSelected()
        rule.onNodeWithText("Point A placed. Tap point B").assertExists()

        // Not disarm paths: a pinch and an off-canvas cancel keep the tool and point A.
        canvas().performTouchInput { pinch(Offset(300f, 200f), Offset(320f, 200f), Offset(300f, 200f), Offset(380f, 200f)) }
        rule.waitForIdle()
        vm.cancelPlacementGesture()
        rule.waitForIdle()
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)
        assertEquals(1, vm.state.value.inProgressPoints.size)

        clickChip("Distance")
        assertDisarmedWithNothingCreated(vm)
    }

    @Test
    fun selectChip_should_DisarmAndDiscardPointA() {
        val vm = launch(AnnotationTool.DISTANCE, NormalizedPoint(0.2, 0.2))
        clickChip("Select")
        assertDisarmedWithNothingCreated(vm)
    }

    @Test
    fun back_should_DisarmOnceAndKeepTheEditorOpen() {
        val vm = launch(AnnotationTool.DISTANCE, NormalizedPoint(0.2, 0.2))
        pressBack()
        assertDisarmedWithNothingCreated(vm)
    }

    @Test
    fun escapeKey_should_DisarmWhenArmedAndIgnoreItInSelect() {
        var disarmed = 0
        fun press(tool: AnnotationTool) = onAnnotationToolKeyEvent(
            keyEvent = KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_ESCAPE)),
            enabled = true,
            currentTool = tool,
            isCalibrated = true,
            onSelectTool = { if (it == AnnotationTool.SELECT) disarmed++ },
            onToggleKeep = {},
        )
        assertTrue(press(AnnotationTool.DISTANCE))
        assertEquals(1, disarmed)
        assertFalse(press(AnnotationTool.SELECT))
    }

    @Test
    fun tapTap_should_CreateSameMeasurementAsDrag_AndDiscardFirstPointWithoutCreating() {
        val vm = launch(AnnotationTool.DISTANCE)
        warmUp(vm)
        val a = Offset(200f, 100f)
        val b = Offset(500f, 160f)

        canvas().performTouchInput { down(a); up() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.inProgressPoints.size == 1 }
        rule.onNodeWithText("Point A placed. Tap point B").assertExists()
        canvas().performTouchInput { down(b); up() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.committedAnnotations.size == 1 }
        val byTaps = vm.state.value.committedAnnotations.single()

        vm.selectTool(AnnotationTool.DISTANCE)
        rule.waitForIdle()
        // Shifted down so the drag does not start on the first measurement's selection UI.
        val shift = Offset(0f, 300f)
        warmUp(vm)
        canvas().performTouchInput { down(a + shift); moveTo(Offset(350f, 130f) + shift); moveTo(b + shift); up() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.committedAnnotations.size == 2 }
        val byDrag = vm.state.value.committedAnnotations.last()

        assertEquals(byTaps.normalizedPoints.size, byDrag.normalizedPoints.size)
        // The strip grows by a 48 dp Cancel row once A is placed, which resizes the canvas under the second tap,
        // so x must match exactly and y/length only to within that layout shift.
        val (t0, t1) = byTaps.normalizedPoints
        val (d0, d1) = byDrag.normalizedPoints
        assertEquals(t0.x, d0.x, 1e-6)
        assertEquals(t1.x, d1.x, 1e-6)
        assertEquals(t1.y - t0.y, d1.y - d0.y, 0.02)
        assertEquals(byTaps.valueMeters!!, byDrag.valueMeters!!, byTaps.valueMeters!! * 0.02)

        // Variant: tap A, then Cancel in the strip: no measurement, the tool stays armed.
        vm.selectTool(AnnotationTool.DISTANCE)
        rule.waitForIdle()
        warmUp(vm)
        rule.waitForIdle()
        canvas().performTouchInput { down(Offset(700f, 100f)); up() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.inProgressPoints.size == 1 }
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(2, vm.state.value.committedAnnotations.size)
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
    }

    @Test
    fun commit_should_AnnounceTheValueAndTheReturnToSelectInALiveRegion() {
        val vm = launch(AnnotationTool.DISTANCE)
        vm.setDisplayUnit(MeasurementUnit.CENTIMETERS)
        vm.addPoint(NormalizedPoint(0.20, 0.3))
        vm.addPoint(NormalizedPoint(0.29, 0.3))
        rule.waitForIdle()
        val announced = rule.onAllNodes(hasContentDescription("added", substring = true)).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.ContentDescription].joinToString() }
        assertEquals(listOf("Distance 90.0 cm added, returned to Select"), announced)
    }
}
