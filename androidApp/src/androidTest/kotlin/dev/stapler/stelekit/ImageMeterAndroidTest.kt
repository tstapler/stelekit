package dev.stapler.stelekit

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.annotate.AnnotationEditorScreen
import dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel
import dev.stapler.stelekit.ui.theme.StelekitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TC-IMGMETER-001..003: Android instrumented tests for the image measurement annotation editor.
 *
 * Verifies [AnnotationEditorScreen] composes and renders its core UI elements on-device:
 * calibration status, measurement tool buttons, and the annotation canvas tap target.
 *
 * These tests catch Android-specific regressions that desktop Compose previews miss:
 * missing Activity back-press dispatcher, Coil [AsyncImage] lifecycle on Android, and
 * Material 3 theme resolution with the real Android font stack.
 *
 * Runs on a real (or emulated) Android device via connectedAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class ImageMeterAndroidTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun makeAnnotation(calibrated: Boolean = true) = ImageAnnotation(
        uuid = "img-android-001",
        blockUuid = "blk-android-001",
        pageUuid = "page-android-001",
        graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = if (calibrated) Calibration(
            method = CalibrationMethod.MANUAL_REFERENCE,
            pixelsPerMeter = 100.0,
            confidencePercent = 90,
        ) else Calibration(),
        unit = MeasurementUnit.METERS,
    )

    private fun makeViewModel(annotation: ImageAnnotation): AnnotationEditorViewModel {
        return AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(annotation) }
    }

    private fun launchEditor(annotation: ImageAnnotation, viewModel: AnnotationEditorViewModel) {
        composeRule.setContent {
            StelekitTheme {
                AnnotationEditorScreen(viewModel = viewModel, imageAnnotation = annotation)
            }
        }
        composeRule.waitForIdle()
    }

    // TC-IMGMETER-001: Tool buttons (Distance, Area, Angle) are visible when image is calibrated
    @Test
    fun calibratedToolButtonsAreDisplayed() {
        val annotation = makeAnnotation(calibrated = true)
        launchEditor(annotation, makeViewModel(annotation))
        composeRule.onNodeWithText("Distance").assertIsDisplayed()
        composeRule.onNodeWithText("Area").assertIsDisplayed()
        composeRule.onNodeWithText("Angle").assertIsDisplayed()
    }

    // TC-IMGMETER-002: Annotation canvas exposes accessibility content description for screen readers
    @Test
    fun annotationCanvasHasAccessibleContentDescription() {
        val annotation = makeAnnotation(calibrated = true)
        launchEditor(annotation, makeViewModel(annotation))
        composeRule.onNodeWithContentDescription("Annotation canvas — tap to place points")
            .assertIsDisplayed()
    }

    // TC-IMGMETER-003: Calibration status bar shows "Not calibrated" when annotation has no calibration
    @Test
    fun uncalibratedAnnotationShowsNotCalibratedStatus() {
        val annotation = makeAnnotation(calibrated = false)
        launchEditor(annotation, makeViewModel(annotation))
        composeRule.onNodeWithText("Not calibrated").assertIsDisplayed()
    }
}
