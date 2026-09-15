package dev.stapler.stelekit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.stapler.stelekit.ui.components.DemoBanner
import org.junit.Rule
import org.junit.Test

class DemoBannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // T-12: DemoBanner is visible when demo active
    @Test
    fun demoBanner_isVisibleWhenDemoActive() {
        composeTestRule.setContent {
            MaterialTheme {
                DemoBanner(onDismiss = {})
            }
        }
        composeTestRule.onNodeWithText("Exploring the demo — changes won't be saved")
            .assertIsDisplayed()
    }

    // T-13: DemoBanner dismiss callback fires and caller can remove it from composition
    @Test
    fun demoBanner_dismissRemovesItFromComposition() {
        composeTestRule.setContent {
            MaterialTheme {
                var dismissed by remember { mutableStateOf(false) }
                AnimatedVisibility(
                    visible = !dismissed,
                    exit = fadeOut() + shrinkVertically()
                ) {
                    DemoBanner(onDismiss = { dismissed = true })
                }
            }
        }
        composeTestRule.onNodeWithContentDescription("Dismiss demo notice").performClick()
        // Advance past AnimatedVisibility exit animation (fadeOut + shrinkVertically, ~300 ms default)
        composeTestRule.mainClock.advanceTimeBy(1000L)
        composeTestRule.onNodeWithText("Exploring the demo — changes won't be saved")
            .assertDoesNotExist()
    }

    // T-14: DemoBanner dismiss button contentDescription matches spec
    @Test
    fun demoBanner_dismissButtonContentDescriptionMatchesSpec() {
        composeTestRule.setContent {
            MaterialTheme {
                DemoBanner(onDismiss = {})
            }
        }
        composeTestRule.onNodeWithContentDescription("Dismiss demo notice").assertExists()
    }

    // T-15: DemoBanner uses tertiaryContainer background (compile + render check)
    @Test
    fun demoBanner_rendersWithoutCrash_tertiaryContainerBackground() {
        composeTestRule.setContent {
            MaterialTheme {
                DemoBanner(onDismiss = {})
            }
        }
        // Visual: banner uses MaterialTheme.colorScheme.tertiaryContainer — verified by inspection.
        // This test confirms the composable renders without crashing under the real theme.
        composeTestRule.onNodeWithText("Exploring the demo — changes won't be saved")
            .assertExists()
    }
}
