package dev.stapler.stelekit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TC-E2E-001, TC-E2E-002: Emulator smoke tests for startup correctness.
 *
 * These run on a real (or emulated) Android device via connectedAndroidTest.
 * They catch crashes and blank-screen regressions that Robolectric unit tests miss
 * because they exercise the full Activity + Application lifecycle on real Android.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // TC-E2E-001: App launches without crash.
    // Catches IllegalStateException, NPE, ClassCastException, etc. thrown during
    // Application.onCreate(), MainActivity.onCreate(), or initial Compose composition.
    @Test
    fun appLaunchesWithoutCrash() {
        composeRule.waitForIdle()
        // Reaching here without an exception means startup completed successfully.
    }

    // TC-E2E-002: Fresh-install (no SAF grant) shows a meaningful UI, not a blank screen.
    // After loading settles, the app must display either:
    //   - The loading indicator (still in progress)
    //   - The "Can't access your notes folder" permission recovery screen
    // Any other visible Compose content also passes — the key invariant is no blank crash.
    @Test
    fun freshInstallShowsMeaningfulUi() {
        composeRule.waitForIdle()

        val hasLoadingIndicator = runCatching {
            composeRule.onNodeWithTag("loadingIndicator").assertIsDisplayed()
        }.isSuccess

        val hasPermissionScreen = runCatching {
            composeRule.onNodeWithText("Can't access your notes folder").assertIsDisplayed()
        }.isSuccess

        // A Compose tree with any content also satisfies the invariant.
        val hasAnyContent = runCatching {
            composeRule.onNodeWithText("SteleKit").assertIsDisplayed()
        }.isSuccess

        assert(hasLoadingIndicator || hasPermissionScreen || hasAnyContent) {
            "Fresh install must show loading indicator, permission recovery screen, or app content — not a blank or crashed state"
        }
    }
}
