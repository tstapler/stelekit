package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.withKeyDown
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for the GAP-G02 gap-backlog follow-up flagged by validation.md: Phase G wired
 * a `Modifier.onKeyEvent` handler onto [AnnotationEditorScreen]'s `Scaffold` dispatching Ctrl+Z
 * (undo) / Ctrl+Shift+Z (redo) to [AnnotationEditorViewModel.undo]/[AnnotationEditorViewModel.redo]
 * (see `onAnnotationEditorKeyEvent` in `AnnotationEditorScreen.kt`), but only the toolbar tooltip
 * text got covered ([AnnotationToolbarShortcutTest]) — the actual hardware key dispatch itself had
 * zero test coverage.
 *
 * These tests render the real [AnnotationEditorScreen] composable (not a harness), commit a real
 * annotation through the ViewModel, and verify that dispatching the hardware key combo through the
 * screen actually mutates the screen's own undo/redo stack — not just that a tooltip mentions it.
 *
 * Focus target: the "Select" tool button in [AnnotationToolbar]. Clicking it only changes
 * [AnnotationEditorState.currentTool] (harmless to the undo/redo assertions here) while granting
 * hardware-keyboard focus inside the [AnnotationEditorScreen] composition, so the dispatched key
 * event bubbles up through the `Scaffold`'s `onKeyEvent` per the topology documented on
 * `onAnnotationEditorKeyEvent`.
 */
@OptIn(ExperimentalTestApi::class)
class AnnotationEditorScreenKeyboardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeImageAnnotation() = ImageAnnotation(
        uuid = "img-kbd-001",
        blockUuid = "blk-kbd-001",
        pageUuid = "page-kbd-001",
        graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(
            method = CalibrationMethod.MANUAL_REFERENCE,
            pixelsPerMeter = 100.0,
            confidencePercent = 100,
        ),
        unit = MeasurementUnit.METERS,
    )

    private fun focusScreen() {
        // Clicking the always-enabled "Select" tool button grants hardware-keyboard focus
        // somewhere inside the AnnotationEditorScreen composition so subsequent key events bubble
        // up through the Scaffold's onKeyEvent modifier. Selecting this tool is a no-op against
        // committedAnnotations/undo-redo state — it only clears any in-progress points.
        composeTestRule.onNodeWithContentDescription("Select").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `Ctrl+Z dispatched through the screen undoes the most recent committed annotation`() {
        val repo = InMemoryMeasurementAnnotationRepository()
        val viewModel = AnnotationEditorViewModel(
            measurementRepository = repo,
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        )
        val imageAnnotation = makeImageAnnotation()
        viewModel.initialize(imageAnnotation)

        composeTestRule.setContent {
            AnnotationEditorScreen(
                viewModel = viewModel,
                imageAnnotation = imageAnnotation,
            )
        }
        composeTestRule.waitForIdle()

        // Commit one DISTANCE annotation directly through the ViewModel (default currentTool is
        // DISTANCE, which auto-commits once 2 points are collected).
        viewModel.addPoint(NormalizedPoint(0.1, 0.1))
        viewModel.addPoint(NormalizedPoint(0.5, 0.5))
        composeTestRule.waitForIdle()

        assertEquals(1, viewModel.state.value.committedAnnotations.size, "Precondition: one annotation should be committed")
        assertTrue(viewModel.canUndo.value, "Precondition: canUndo should be true after a commit")

        focusScreen()

        composeTestRule.onNodeWithContentDescription("Select").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                keyDown(Key.Z)
                keyUp(Key.Z)
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(
            0,
            viewModel.state.value.committedAnnotations.size,
            "Ctrl+Z dispatched through AnnotationEditorScreen's onKeyEvent handler should undo the committed annotation",
        )
        assertFalse(viewModel.canUndo.value, "canUndo should be false after the stack is fully undone")
        assertTrue(viewModel.canRedo.value, "canRedo should become true after an undo")
    }

    @Test
    fun `Ctrl+Shift+Z dispatched through the screen redoes the most recently undone annotation`() {
        val repo = InMemoryMeasurementAnnotationRepository()
        val viewModel = AnnotationEditorViewModel(
            measurementRepository = repo,
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        )
        val imageAnnotation = makeImageAnnotation()
        viewModel.initialize(imageAnnotation)

        composeTestRule.setContent {
            AnnotationEditorScreen(
                viewModel = viewModel,
                imageAnnotation = imageAnnotation,
            )
        }
        composeTestRule.waitForIdle()

        viewModel.addPoint(NormalizedPoint(0.1, 0.1))
        viewModel.addPoint(NormalizedPoint(0.5, 0.5))
        composeTestRule.waitForIdle()

        // Undo directly via the ViewModel to set up the redo precondition — this test targets the
        // Ctrl+Shift+Z dispatch path specifically, independent of the Ctrl+Z test above.
        viewModel.undo()
        composeTestRule.waitForIdle()

        assertEquals(0, viewModel.state.value.committedAnnotations.size, "Precondition: annotation should be undone")
        assertTrue(viewModel.canRedo.value, "Precondition: canRedo should be true after an undo")

        focusScreen()

        composeTestRule.onNodeWithContentDescription("Select").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.ShiftLeft) {
                    keyDown(Key.Z)
                    keyUp(Key.Z)
                }
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(
            1,
            viewModel.state.value.committedAnnotations.size,
            "Ctrl+Shift+Z dispatched through AnnotationEditorScreen's onKeyEvent handler should redo the undone annotation",
        )
        assertFalse(viewModel.canRedo.value, "canRedo should be false after the stack is fully redone")
        assertTrue(viewModel.canUndo.value, "canUndo should become true again after a redo")
    }
}
