// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import dev.stapler.stelekit.model.AnnotationType
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

/** X-66 / UX-66: only Distance values open the editor; Area and Angle select with a strip message and no "Edit value" action. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ValueEditorScopeTest {
    @get:Rule
    val rule = createComposeRule()

    private val img = ImageAnnotation(
        uuid = "img-scope-001", blockUuid = "blk-scope-001", pageUuid = "page-scope-001",
        graphPath = "/test", filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    private lateinit var vm: AnnotationEditorViewModel

    private fun setUp() {
        vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.1, 0.2)); vm.addPoint(NormalizedPoint(0.4, 0.2))
        vm.selectTool(AnnotationTool.AREA)
        listOf(NormalizedPoint(0.3, 0.5), NormalizedPoint(0.7, 0.5), NormalizedPoint(0.5, 0.7)).forEach { vm.addPoint(it) }
        vm.closePolygon()
        vm.selectTool(AnnotationTool.ANGLE)
        vm.addPoint(NormalizedPoint(0.7, 0.85)); vm.addPoint(NormalizedPoint(0.8, 0.9)); vm.addPoint(NormalizedPoint(0.9, 0.85))
        vm.selectTool(AnnotationTool.SELECT)
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()
        // The first injected gesture on a fresh composition is dropped (see OneShotToolFlowTest).
        canvas().performTouchInput { down(Offset(5f, 5f)); up() }
        rule.waitForIdle()
    }

    private fun canvas() = rule.onNodeWithContentDescription(ANNOTATION_CANVAS_DESCRIPTION)

    private fun uuidOf(type: AnnotationType) = vm.state.value.committedAnnotations.first { it.annotationType == type }.uuid

    /** Taps the value pill of the first annotation of [type]: 40 dp above its anchor, as the label overlay draws it. */
    private fun tapValueOf(type: AnnotationType) {
        val a = vm.state.value.committedAnnotations.first { it.annotationType == type }
        val anchor = when (type) {
            AnnotationType.DISTANCE -> NormalizedPoint((a.normalizedPoints[0].x + a.normalizedPoints[1].x) / 2, (a.normalizedPoints[0].y + a.normalizedPoints[1].y) / 2)
            AnnotationType.AREA -> NormalizedPoint(a.normalizedPoints.map { it.x }.average(), a.normalizedPoints.map { it.y }.average())
            else -> a.normalizedPoints[1]
        }
        val size = canvas().fetchSemanticsNode().size
        val dp40 = 40f * rule.density.density
        // The strip message clears itself after 2 s of test-clock time, so hold the clock while asserting.
        rule.mainClock.autoAdvance = false
        canvas().performTouchInput { down(Offset((anchor.x * size.width).toFloat(), (anchor.y * size.height).toFloat() - dp40)); up() }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun distanceValueTap_should_OpenTheEditor() {
        setUp()
        tapValueOf(AnnotationType.DISTANCE)
        rule.onNodeWithText("What does this mean?").assertExists()
        assertEquals(uuidOf(AnnotationType.DISTANCE), vm.state.value.selectedAnnotationUuid)
    }

    @Test
    fun areaValueTap_should_SelectWithMessageAndNoEditor() {
        setUp()
        tapValueOf(AnnotationType.AREA)
        rule.onNodeWithText("Only distance values can be edited").assertExists()
        rule.onNodeWithText("What does this mean?").assertDoesNotExist()
        assertEquals(uuidOf(AnnotationType.AREA), vm.state.value.selectedAnnotationUuid)
    }

    @Test
    fun angleValueTap_should_SelectWithMessageAndNoEditor() {
        setUp()
        tapValueOf(AnnotationType.ANGLE)
        rule.onNodeWithText("Only distance values can be edited").assertExists()
        rule.onNodeWithText("What does this mean?").assertDoesNotExist()
        assertEquals(uuidOf(AnnotationType.ANGLE), vm.state.value.selectedAnnotationUuid)
    }

    @Test
    fun editValueAction_should_ExistOnDistanceNodesOnlyAndOpenTheEditor() {
        setUp()
        fun actions(prefix: String) = rule.onNode(
            SemanticsMatcher("desc starts with $prefix") {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith(prefix) == true
            },
        ).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val edit = actions("Distance measurement").single { it.label == "Edit value" }
        assertTrue(actions("Area measurement").none { it.label == "Edit value" })
        assertTrue(actions("Angle measurement").none { it.label == "Edit value" })
        rule.runOnUiThread { edit.action() }
        rule.waitForIdle()
        rule.onNodeWithText("What does this mean?").assertExists()
    }

    @Test
    fun labelAnnotationTap_should_NeitherOpenTheEditorNorShowTheScopeMessage() {
        setUp()
        vm.selectTool(AnnotationTool.LABEL)
        vm.addPoint(NormalizedPoint(0.5, 0.3)); vm.updateLabelText("Door"); vm.confirmLabel()
        rule.waitForIdle()
        val a = vm.state.value.committedAnnotations.first { it.annotationType == AnnotationType.LABEL }
        val size = canvas().fetchSemanticsNode().size
        canvas().performTouchInput { down(Offset((a.normalizedPoints[0].x * size.width).toFloat(), (a.normalizedPoints[0].y * size.height).toFloat())); up() }
        rule.waitForIdle()
        rule.onNodeWithText("What does this mean?").assertDoesNotExist()
        rule.onNodeWithText("Only distance values can be edited").assertDoesNotExist()
    }
}
