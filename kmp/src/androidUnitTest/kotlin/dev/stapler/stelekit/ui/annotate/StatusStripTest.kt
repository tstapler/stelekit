// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import android.window.BackEvent
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Strip priority, strip Cancel, active-chip disarm and the Back order of ux.md 3.3 / 3.5. */
@RunWith(RobolectricTestRunner::class)
class StatusStripTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val img = ImageAnnotation(
        uuid = "img-strip-001", blockUuid = "blk-strip-001", pageUuid = "page-strip-001",
        graphPath = "/test", filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )
    private var navigatedBack = 0

    private fun launch(tool: AnnotationTool, vararg points: NormalizedPoint): AnnotationEditorViewModel {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        vm.selectTool(tool)
        points.forEach { vm.addPoint(it) }
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img, onNavigateBack = { navigatedBack++ }) }
        rule.waitForIdle()
        return vm
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun swipe(progress: Float) = BackEventCompat(0.1f, 0.5f, progress, BackEvent.EDGE_LEFT)

    @Test
    fun distanceArmedWithNoPoint_stripPromptsForADrag() {
        launch(AnnotationTool.DISTANCE)
        rule.onNodeWithText("Distance: drag from point A to B").assertExists()
        rule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun pendingPointA_showsPromptWithCancel_andCancelKeepsToolArmed() {
        val vm = launch(AnnotationTool.DISTANCE, NormalizedPoint(0.2, 0.2))
        rule.onNodeWithText("Point A placed. Tap point B").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)
        rule.onNodeWithText("Distance: drag from point A to B").assertExists()
    }

    @Test
    fun cancelledMessage_replacesThePrompt() {
        val vm = launch(AnnotationTool.DISTANCE)
        vm.cancelPlacementGesture()
        rule.waitForIdle()
        rule.onNodeWithText("Cancelled").assertExists()
        rule.onNodeWithText("Distance: drag from point A to B").assertDoesNotExist()
    }

    @Test
    fun tappingTheActiveChip_disarmsAndDiscardsPointA() {
        val vm = launch(AnnotationTool.DISTANCE, NormalizedPoint(0.2, 0.2))
        rule.onNodeWithContentDescription("Distance").performClick()
        // A chip waits out the double-tap window (Keep tool) before a single tap counts.
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
    }

    @Test
    fun fineTuneHint_showsOnlyForTheFirstThreeDragMeasures() {
        val settings = MapSettings()
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        assertEquals(0, annotatorDragHintCount(settings))
        repeat(3) { assertTrue(shouldShowDragHint(settings)); recordDragHint(settings) }
        assertTrue(!shouldShowDragHint(settings))
        assertEquals(3, annotatorDragHintCount(settings))
        assertTrue(vm.state.value.committedAnnotations.isEmpty())
    }

    @Test
    fun backWithPendingPointA_disarmsOnceAndKeepsTheEditorOpen() {
        val vm = launch(AnnotationTool.DISTANCE, NormalizedPoint(0.2, 0.2))
        pressBack()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
        assertEquals(0, navigatedBack)
    }

    @Test
    fun backWithTwoUnfinishedPoints_confirmsFirstAndKeepEditingChangesNothing() {
        val vm = launch(AnnotationTool.AREA, NormalizedPoint(0.1, 0.1), NormalizedPoint(0.4, 0.1))
        pressBack()
        rule.onNodeWithText("Discard 2 unfinished points?").assertExists()
        rule.onNodeWithText("Keep editing").performClick()
        rule.waitForIdle()
        assertEquals(AnnotationTool.AREA, vm.state.value.currentTool)
        assertEquals(2, vm.state.value.inProgressPoints.size)
        assertEquals(0, navigatedBack)

        pressBack()
        rule.onNodeWithText("Discard").performClick()
        rule.waitForIdle()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)
        assertEquals(0, navigatedBack)
    }

    @Test
    fun backInSelectWithNothingPending_runsTheExistingLeavePath() {
        launch(AnnotationTool.SELECT)
        pressBack()
        rule.waitUntil(5_000) { navigatedBack == 1 }
        assertEquals(1, navigatedBack)
    }

    @Test
    fun backWithADialogOpen_closesOnlyTheDialog() {
        val vm = launch(AnnotationTool.AREA, NormalizedPoint(0.1, 0.1), NormalizedPoint(0.4, 0.1))
        pressBack()
        rule.onNodeWithText("Discard 2 unfinished points?").assertExists()
        pressBack()
        rule.onNodeWithText("Discard 2 unfinished points?").assertDoesNotExist()
        assertEquals(AnnotationTool.AREA, vm.state.value.currentTool)
        assertEquals(0, navigatedBack)
    }

    @Test
    fun predictiveBack_commitTakesTheSameStepAsTheButton() {
        val vm = launch(AnnotationTool.DISTANCE, NormalizedPoint(0.2, 0.2))
        rule.runOnUiThread {
            val dispatcher = rule.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(swipe(0f))
            dispatcher.dispatchOnBackProgressed(swipe(0.6f))
            dispatcher.onBackPressed()
        }
        rule.waitForIdle()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)
        assertEquals(0, navigatedBack)
    }

    @Test
    fun predictiveBack_abandonedGestureChangesNothing() {
        val vm = launch(AnnotationTool.DISTANCE, NormalizedPoint(0.2, 0.2))
        rule.runOnUiThread {
            val dispatcher = rule.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(swipe(0f))
            dispatcher.dispatchOnBackProgressed(swipe(0.4f))
            dispatcher.dispatchOnBackCancelled()
        }
        rule.waitForIdle()
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)
        assertEquals(1, vm.state.value.inProgressPoints.size)
        assertEquals(0, navigatedBack)
    }

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key]?.toBooleanStrictOrNull() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = map[key] ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }
}
