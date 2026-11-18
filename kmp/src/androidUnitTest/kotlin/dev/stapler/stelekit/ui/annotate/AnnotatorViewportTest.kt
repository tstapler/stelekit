// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** Short windows (landscape phone, folded, 200% font) must keep a usable photo viewport despite the toolbar and strip. */
@RunWith(RobolectricTestRunner::class)
class AnnotatorViewportTest {
    @get:Rule
    val rule = createComposeRule()

    private val img = ImageAnnotation(
        uuid = "img-vp-001", blockUuid = "blk-vp-001", pageUuid = "page-vp-001",
        graphPath = "/test", filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    private fun assertViewportUsable(tool: AnnotationTool, fraction: Float = MIN_VIEWPORT_FRACTION) {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        vm.selectTool(tool)
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()
        val window = rule.onRoot().getUnclippedBoundsInRoot().let { it.bottom.value - it.top.value }
        val canvas = rule.onNodeWithContentDescription(ANNOTATION_CANVAS_DESCRIPTION).getUnclippedBoundsInRoot()
        val height = canvas.bottom.value - canvas.top.value
        val minimum = minOf(MIN_VIEWPORT_DP, fraction * window)
        assertTrue(height >= minimum, "photo viewport ${height}dp < ${minimum}dp in a ${window}dp-high window (tool=$tool)")
    }

    @Test
    @Config(sdk = [29], qualifiers = "w640dp-h360dp-xhdpi")
    fun viewport_should_StayUsable_OnLandscapePhone() = assertViewportUsable(AnnotationTool.SELECT)

    @Test
    @Config(sdk = [29], qualifiers = "w640dp-h360dp-xhdpi")
    fun viewport_should_StayUsable_OnLandscapePhoneWithToolPrompt() = assertViewportUsable(AnnotationTool.DISTANCE)

    @Test
    @Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi", fontScale = 2.0f)
    fun viewport_should_StayUsable_OnPortraitPhoneAtFontScale2() = assertViewportUsable(AnnotationTool.SELECT)

    @Test
    @Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi", fontScale = 2.0f)
    fun viewport_should_StayUsable_OnPortraitPhoneAtFontScale2WithToolPrompt() = assertViewportUsable(AnnotationTool.DISTANCE)

    @Test
    @Config(sdk = [29], qualifiers = "w320dp-h470dp-mdpi")
    fun viewport_should_StayUsable_AtRobolectricDefault320x470() = assertViewportUsable(AnnotationTool.DISTANCE, TINY_WINDOW_FRACTION)

    @Test
    @Config(sdk = [29], qualifiers = "w320dp-h470dp-mdpi", fontScale = 2.0f)
    fun viewport_should_StayUsable_AtRobolectricDefaultWithFontScale2() = assertViewportUsable(AnnotationTool.SELECT, TINY_WINDOW_FRACTION)

    private companion object {
        const val MIN_VIEWPORT_DP = 200f
        const val MIN_VIEWPORT_FRACTION = 0.3f
        /** 320x470 dp is Robolectric's default, smaller than any shipping phone; the floor is relaxed there. */
        const val TINY_WINDOW_FRACTION = 0.2f
    }
}
