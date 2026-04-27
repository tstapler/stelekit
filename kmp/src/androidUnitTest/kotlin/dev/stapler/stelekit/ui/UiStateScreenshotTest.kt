package dev.stapler.stelekit.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.ui.components.LoadingOverlay
import dev.stapler.stelekit.ui.screens.PermissionRecoveryScreen
import dev.stapler.stelekit.ui.theme.StelekitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TR-4: Screenshot regression tests for the four critical UI states.
 *
 * Running:
 *   Record goldens:  ./gradlew :kmp:recordRoborazziDebug
 *   Verify against goldens: ./gradlew :kmp:verifyRoborazziDebug
 *
 * The first time you run recordRoborazziDebug, commit the generated images from
 *   kmp/src/androidUnitTest/snapshots/images/
 * so CI can verify them on future PRs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h891dp-xhdpi-keyshidden-nonav")
class UiStateScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    // TR-4 state 1: Loading (graph in progress)
    @Test
    fun loadingOverlay_screenshot() {
        composeRule.setContent {
            StelekitTheme {
                LoadingOverlay(message = "Loading your notes…")
            }
        }
        composeRule.onRoot().captureRoboImage()
    }

    // TR-4 state 2: Permission lost — the re-grant prompt shown after reinstall
    @Test
    fun permissionRecoveryScreen_screenshot() {
        composeRule.setContent {
            StelekitTheme {
                PermissionRecoveryScreen(
                    folderName = "notes",
                    onReconnectFolder = {},
                    onChooseDifferentFolder = {},
                )
            }
        }
        composeRule.onRoot().captureRoboImage()
    }

    // TR-4 state 3: Permission lost with no known folder name
    @Test
    fun permissionRecoveryScreen_noFolderName_screenshot() {
        composeRule.setContent {
            StelekitTheme {
                PermissionRecoveryScreen(
                    folderName = null,
                    onReconnectFolder = {},
                    onChooseDifferentFolder = {},
                )
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
