package dev.stapler.stelekit.ui.annotate

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.takahirom.roborazzi.captureRoboImage
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

/**
 * The editor sits inside the app shell, whose top bar already owns the status-bar inset. The editor's
 * own Scaffold must not apply the system-bar insets a second time, or a blank band opens above the
 * calibration badge (the "dead space" in the backlog screenshot).
 *
 * Goldens: `./gradlew :kmp:recordRoborazziDebug` writes the screenshot to the Roborazzi output dir.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AnnotationEditorInsetsLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val image = ImageAnnotation(
        uuid = "imginsets01", blockUuid = "blkinsets01", pageUuid = "pageinsets01",
        graphPath = "/test", filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.FEET_INCHES,
    )

    @Test
    fun calibrationBadge_isNotPushedDownBySystemBarInsets() {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(image) }
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = image) }

        // 48dp status bar and 48dp navigation bar at xhdpi (2x).
        rule.runOnUiThread {
            val content = rule.activity.findViewById<View>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(
                content,
                WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 96, 0, 96)).build(),
            )
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage()

        val badgeTop = rule.onNodeWithText("Scale set").getUnclippedBoundsInRoot().top
        assertTrue(badgeTop < 24.dp, "calibration badge starts ${badgeTop} below the top; expected no inset band")
    }
}
