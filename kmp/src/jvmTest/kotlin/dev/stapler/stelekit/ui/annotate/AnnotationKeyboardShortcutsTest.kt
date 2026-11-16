// jvmTest-reason: desktop-only
package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** I-E2-08 (Story 2.6): tool keys arm a tool, K toggles Keep tool, re-arming is one key press, and a mouse drag measures. */
@OptIn(ExperimentalTestApi::class)
class AnnotationKeyboardShortcutsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val img = ImageAnnotation(
        uuid = "img-kbd-002", blockUuid = "blk-kbd-002", pageUuid = "page-kbd-002", graphPath = "/test", filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100), unit = MeasurementUnit.METERS,
    )

    private fun launch(): AnnotationEditorViewModel {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(), imageWidthPx = 1000.0, imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        composeTestRule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        composeTestRule.waitForIdle()
        // Clicking Select grants hardware-keyboard focus inside the screen without changing any state.
        composeTestRule.onNodeWithContentDescription("Select").performClick()
        composeTestRule.waitForIdle()
        return vm
    }

    private fun press(key: Key) {
        composeTestRule.onNodeWithContentDescription("Select").performKeyInput { keyDown(key); keyUp(key) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun toolLetters_should_BeIgnored_WhileTheTagFieldHasFocus() {
        val vm = launch()
        composeTestRule.onNode(hasSetTextAction()).performClick()
        composeTestRule.waitForIdle()
        for (key in listOf(Key.D, Key.A, Key.G, Key.L, Key.R, Key.K, Key.Escape)) {
            composeTestRule.onNode(hasSetTextAction()).performKeyInput { keyDown(key); keyUp(key) }
        }
        composeTestRule.waitForIdle()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool, "a tool shortcut fired while typing a tag")
        composeTestRule.onNodeWithContentDescription("Select").performClick()
        composeTestRule.waitForIdle()
        press(Key.D)
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool, "control: D works once focus leaves the field")
    }

    @Test
    fun keepToolShortcutAndToolKeys_should_ToggleAndRearm() {
        val vm = launch()
        press(Key.K)
        assertEquals(ToolStickiness.ONE_SHOT, vm.state.value.stickiness, "K is ignored while Select is active")

        press(Key.D)
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)
        vm.addPoint(NormalizedPoint(0.1, 0.1))
        vm.addPoint(NormalizedPoint(0.5, 0.5))
        composeTestRule.waitForIdle()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool, "auto-return after the commit")

        press(Key.D)
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool, "one key press re-arms")

        press(Key.K)
        assertEquals(ToolStickiness.LOCKED, vm.state.value.stickiness)
        vm.addPoint(NormalizedPoint(0.2, 0.2))
        vm.addPoint(NormalizedPoint(0.6, 0.6))
        composeTestRule.waitForIdle()
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool, "a locked tool stays armed")

        press(Key.K)
        assertEquals(ToolStickiness.ONE_SHOT, vm.state.value.stickiness)
        press(Key.A)
        assertEquals(AnnotationTool.AREA, vm.state.value.currentTool)
        press(Key.A)
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool, "the armed tool's key disarms, like tapping its chip")
    }

    @Test
    fun mouseDragPast48dp_should_CreateAMeasurement() {
        val vm = launch()
        press(Key.D)
        val canvas = composeTestRule.onNodeWithContentDescription(ANNOTATION_CANVAS_DESCRIPTION)
        val size = canvas.fetchSemanticsNode().size
        val start = Offset(size.width * 0.2f, size.height * 0.5f)
        // The first injected gesture is dropped before the pointer handler listens (see the Robolectric gesture tests).
        canvas.performMouseInput { moveTo(start); press(); release() }
        composeTestRule.waitForIdle()
        vm.cancelInProgress()
        canvas.performMouseInput { moveTo(start); press(); moveTo(start + Offset(size.width * 0.5f, 0f)); release() }
        composeTestRule.waitForIdle()
        assertTrue(vm.state.value.committedAnnotations.size == 1, "a drag past 48 dp commits one distance: ${vm.state.value.committedAnnotations.size}")
    }
}
