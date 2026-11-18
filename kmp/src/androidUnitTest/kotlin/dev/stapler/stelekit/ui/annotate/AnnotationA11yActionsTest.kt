// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
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
import kotlin.test.assertTrue

/** X-54 / X-55 (UX-54, UX-55) plus the X-15 announcement and the X-16 strip hint, driven through the whole editor screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h891dp-xhdpi")
class AnnotationA11yActionsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun image(calibrated: Boolean = true) = ImageAnnotation(
        uuid = "img-a11y-001", blockUuid = "blk-a11y-001", pageUuid = "page-a11y-001",
        graphPath = "/test", filePath = "/test/image.jpg",
        calibration = if (calibrated) Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100) else Calibration(),
        unit = MeasurementUnit.METERS,
    )

    private fun launch(tool: AnnotationTool, calibrated: Boolean = true): AnnotationEditorViewModel {
        val img = image(calibrated)
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(), imageWidthPx = 1000.0, imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        vm.setDisplayUnit(MeasurementUnit.CENTIMETERS)
        vm.selectTool(tool)
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()
        return vm
    }

    private fun invokeCustomAction(matcher: SemanticsMatcher, label: String) {
        val actions = rule.onNode(matcher).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val action = actions.firstOrNull { it.label == label } ?: error("no '$label' in ${actions.map { it.label }}")
        rule.runOnUiThread { action.action() }
        rule.waitForIdle()
    }

    private fun chipActions(chip: String) =
        rule.onNode(hasContentDescription(chip)).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun announced(text: String) {
        val live = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.trimEnd('\u200B') }
        assertTrue(text in live, "expected live-region announcement '$text', found $live")
    }

    private fun clickViaSemantics(text: String) {
        // A semantic click: the Robolectric window is small enough for the toolbar to overlap the pad.
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    @Test
    fun toolChip_should_ExposeKeepToolAndReleaseToolCustomActions() {
        val vm = launch(AnnotationTool.DISTANCE)
        assertEquals(listOf(KEEP_TOOL_LABEL), chipActions("Distance"))
        invokeCustomAction(hasContentDescription("Distance"), KEEP_TOOL_LABEL)
        assertEquals(ToolStickiness.LOCKED, vm.state.value.stickiness)
        assertEquals(listOf(RELEASE_TOOL_LABEL), chipActions("Distance"))
        invokeCustomAction(hasContentDescription("Distance"), RELEASE_TOOL_LABEL)
        assertEquals(ToolStickiness.ONE_SHOT, vm.state.value.stickiness)
        assertEquals(emptyList(), chipActions("Select"), "Select has no Keep tool action")
    }

    @Test
    fun visibleKeepToolToggle_should_LockAndKeepTheToolArmedAfterACommit() {
        val vm = launch(AnnotationTool.DISTANCE)
        rule.onNode(hasText("Keep tool")).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(ToolStickiness.LOCKED, vm.state.value.stickiness)
        vm.addPoint(NormalizedPoint(0.1, 0.5))
        vm.addPoint(NormalizedPoint(0.19, 0.5))
        rule.waitForIdle()
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)
        announced("Distance 90.0 cm added")
    }

    @Test
    fun crosshairCustomAction_should_PlacePointsAndAnnounceCoordinatesAndLength() {
        val vm = launch(AnnotationTool.DISTANCE)
        val canvas = hasContentDescription(ANNOTATION_CANVAS_DESCRIPTION)
        assertTrue(
            rule.onNode(canvas).fetchSemanticsNode().config[SemanticsActions.CustomActions].any { it.label == PLACE_POINT_WITH_CROSSHAIR_ACTION },
        )
        invokeCustomAction(canvas, PLACE_POINT_WITH_CROSSHAIR_ACTION)
        assertTrue(vm.state.value.placement.active, "the crosshair session started")
        announced("Crosshair at 50 percent across, 50 percent down")

        clickViaSemantics("Place point")
        assertEquals(1, vm.state.value.inProgressPoints.size)
        announced(POINT_A_PLACED_ANNOUNCEMENT)

        rule.onNodeWithContentDescription("Nudge right, 10 pixels step").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        val live = rule.onNode(hasContentDescription("Crosshair at", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first().trimEnd('\u200B')
        assertTrue(", length " in live, "nudge announces the live length once a first point exists: $live")

        rule.runOnUiThread { vm.tapCrosshair(AnnotationPlacementSpace(1000, 1000), NormalizedPoint(0.59, 0.5)) }
        rule.waitForIdle()
        clickViaSemantics("Place point")
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)
        announced("Distance 90.0 cm added, returned to Select")
    }

    @Test
    fun canvas_should_ExposeOnlyThePanActions_WhileSelectIsActive() {
        launch(AnnotationTool.SELECT)
        val select = rule.onNode(hasContentDescription(ANNOTATION_CANVAS_DESCRIPTION)).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Pan left", "Pan up", "Pan down", "Pan right"), select)
    }

    @Test
    fun referenceTap_should_ShowTheCalibrateFirstHintInTheStrip_WhenNotCalibrated() {
        val vm = launch(AnnotationTool.SELECT, calibrated = false)
        rule.onNode(hasContentDescription("Reference")).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("Calibrate first to use Reference").assertExists()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)
    }
}
