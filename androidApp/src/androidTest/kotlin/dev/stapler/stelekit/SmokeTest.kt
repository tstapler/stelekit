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
    // After loading settles, the app must display one of:
    //   - Loading indicator (loading in progress)
    //   - Onboarding screen ("Welcome to SteleKit" or "Select Your Graph")
    //   - Permission recovery screen ("Can't access your notes folder")
    // Checking for ANY Compose text node in the root — the invariant is "not crashed/blank".
    @Test
    fun freshInstallShowsMeaningfulUi() {
        composeRule.waitForIdle()

        val candidates = listOf(
            "loadingIndicator",         // testTag on the circular progress indicator
        )
        val textCandidates = listOf(
            "Welcome to SteleKit",       // onboarding welcome step
            "Select Your Graph",         // onboarding graph-picker step
            "Can't access your notes folder", // permission recovery (reinstall path)
            "SteleKit",                  // any screen containing the app name
        )

        val hasLoadingIndicator = candidates.any { tag ->
            runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
        }
        val hasKnownText = textCandidates.any { text ->
            runCatching { composeRule.onNodeWithText(text, substring = true).assertIsDisplayed() }.isSuccess
        }

        assert(hasLoadingIndicator || hasKnownText) {
            "Fresh install must show a loading indicator or a known UI screen — not a blank or crashed state. " +
                "Checked testTags: $candidates and text fragments: $textCandidates"
        }
    }
}
