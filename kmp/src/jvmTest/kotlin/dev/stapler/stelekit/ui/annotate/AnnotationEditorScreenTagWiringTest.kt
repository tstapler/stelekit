package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.assertIsDisplayed
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for GAP-G01 (gap-backlog.md): [TagEditorPanel] was a fully-implemented,
 * backend-wired image-tagging UI (backed by [AnnotationEditorViewModel.addTag]/[AnnotationEditorViewModel.removeTag],
 * consumed downstream by Gallery's tag filter) with **zero call sites** anywhere in the app —
 * image-level tagging was completely unreachable. This test verifies [AnnotationEditorScreen]
 * now composes [TagEditorPanel] and that adding a tag reaches the ViewModel's real state.
 */
class AnnotationEditorScreenTagWiringTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeImageAnnotation() = ImageAnnotation(
        uuid = "img-tag-001",
        blockUuid = "blk-tag-001",
        pageUuid = "page-tag-001",
        graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(
            method = CalibrationMethod.MANUAL_REFERENCE,
            pixelsPerMeter = 100.0,
            confidencePercent = 100,
        ),
        unit = MeasurementUnit.METERS,
    )

    @Test
    fun annotationEditorScreen_should_renderTagEditorPanel_When_imageAnnotationLoaded() {
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

        // The "Tags" section label from TagEditorPanel must now be reachable from the screen —
        // previously there was no call site anywhere in the codebase.
        composeTestRule.onNodeWithText("Tags").assertIsDisplayed()

        // Adding a tag through the UI must reach the real ViewModel-backed state (not a
        // decorative, disconnected panel).
        composeTestRule.onNodeWithTag("annotationTagInput").performTextInput("kitchen")
        composeTestRule.onNodeWithTag("annotationTagInput").performImeAction()

        composeTestRule.waitUntil(timeoutMillis = 2_000) {
            viewModel.state.value.imageAnnotation?.tags?.contains("kitchen") == true
        }
    }
}
