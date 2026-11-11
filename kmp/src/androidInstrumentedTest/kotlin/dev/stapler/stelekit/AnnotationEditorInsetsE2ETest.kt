// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.annotate.AnnotationEditorScreen
import dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * Real-device check that the annotator adds no second system-bar inset band above the calibration badge.
 * The window is edge-to-edge (as in the app) so the status bar inset is real.
 *
 * Run: ./gradlew :kmp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.stapler.stelekit.AnnotationEditorInsetsE2ETest
 */
@RunWith(AndroidJUnit4::class)
class AnnotationEditorInsetsE2ETest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun calibrationBadge_sitsFlushUnderTheTopOfTheContentArea() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        val img = ImageAnnotation(
            uuid = "img-insets-e2e", blockUuid = "blk-insets-e2e", pageUuid = "page-insets-e2e",
            graphPath = "/test", filePath = "/test/none.jpg",
            calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
            unit = MeasurementUnit.FEET_INCHES,
        )
        val vm = AnnotationEditorViewModel(measurementRepository = InMemoryMeasurementAnnotationRepository())
            .also { it.initialize(img) }
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()

        val density = rule.activity.resources.displayMetrics.density
        val statusBarDp = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)!!
            .getInsets(WindowInsetsCompat.Type.statusBars()).top / density
        assertTrue(statusBarDp > 0f, "precondition: the window must report a real status-bar inset")

        // Flush means the badge starts well inside one status-bar height of the top; a doubled inset puts it past it.
        val badgeTop = rule.onNodeWithText("Scale set").getUnclippedBoundsInRoot().top
        assertTrue(badgeTop < statusBarDp.dp, "badge starts at $badgeTop but the status bar is only ${statusBarDp}dp: an extra inset band remains")
    }
}
