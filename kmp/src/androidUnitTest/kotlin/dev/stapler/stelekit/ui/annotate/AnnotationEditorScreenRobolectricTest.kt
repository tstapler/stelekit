// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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

/** Renders the whole editor screen headlessly so its canvases and overlays are exercised. */
@RunWith(RobolectricTestRunner::class)
class AnnotationEditorScreenRobolectricTest {
    @get:Rule
    val rule = createComposeRule()

    private fun image(method: CalibrationMethod = CalibrationMethod.MANUAL_REFERENCE) = ImageAnnotation(
        uuid = "img-rob-001",
        blockUuid = "blk-rob-001",
        pageUuid = "page-rob-001",
        graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(method, if (method == CalibrationMethod.NONE) 0.0 else 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    private fun viewModel(img: ImageAnnotation) = AnnotationEditorViewModel(
        measurementRepository = InMemoryMeasurementAnnotationRepository(),
        imageWidthPx = 1000.0,
        imageHeightPx = 1000.0,
    ).also { it.initialize(img) }

    @Test
    fun rendersEveryCommittedAnnotationTypeAndInProgressPolygon_doesNotCrash() {
        val img = image()
        val vm = viewModel(img)
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.1, 0.1)); vm.addPoint(NormalizedPoint(0.4, 0.1))
        vm.selectTool(AnnotationTool.AREA)
        listOf(NormalizedPoint(0.2, 0.3), NormalizedPoint(0.6, 0.3), NormalizedPoint(0.6, 0.7)).forEach { vm.addPoint(it) }
        vm.closePolygon()
        vm.selectTool(AnnotationTool.ANGLE)
        vm.addPoint(NormalizedPoint(0.7, 0.2)); vm.addPoint(NormalizedPoint(0.8, 0.2)); vm.addPoint(NormalizedPoint(0.8, 0.4))
        vm.selectTool(AnnotationTool.LABEL)
        vm.addPoint(NormalizedPoint(0.5, 0.5)); vm.updateLabelText("Door"); vm.confirmLabel()
        val selected = vm.state.value.committedAnnotations.first().uuid
        vm.selectAnnotation(selected)
        vm.selectTool(AnnotationTool.AREA)
        vm.addPoint(NormalizedPoint(0.1, 0.8)); vm.addPoint(NormalizedPoint(0.3, 0.8))

        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()
        rule.onNodeWithText("Tags").assertExists()
    }

    @Test
    fun uncalibrated_showsNudgeBanner() {
        val img = image(CalibrationMethod.NONE)
        val vm = viewModel(img)
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()
        rule.onNodeWithText("Set a scale to measure distances").assertExists()
    }

    @Test
    fun optionalPanels_depthExportAndPeerCalibration_render() {
        val img = image()
        val vm = viewModel(img)
        rule.setContent {
            AnnotationEditorScreen(
                viewModel = vm,
                imageAnnotation = img,
                onExportToDrive = {},
                driveExportState = DriveExportUiState.Success("https://drive/x"),
                onDownloadDepthModel = {},
                onCancelDownloadDepthModel = {},
                onEstimateDepth = {},
                peerCalibration = "Kitchen" to Calibration(CalibrationMethod.MANUAL_REFERENCE, 120.0, 100),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Saved to Drive").assertExists()
        rule.onNodeWithText("Download depth model (~100MB)").assertExists()
    }

    @Test
    fun gridRefDialog_isShownWhenViewModelRequestsIt() {
        val img = image()
        val vm = viewModel(img)
        vm.selectTool(AnnotationTool.GRID_REF)
        vm.addPoint(NormalizedPoint(0.1, 0.1))
        vm.addPoint(NormalizedPoint(0.6, 0.1))
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()
        rule.onNodeWithText("Set reference length").assertExists()
    }
}
