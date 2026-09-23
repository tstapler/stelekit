package dev.stapler.stelekit.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.stapler.stelekit.sections.SectionDefinition
import dev.stapler.stelekit.ui.components.SectionBadge
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import io.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot and semantic tests for [SectionBadge] (Story 6.5, FR-13 ambient context baseline).
 *
 * Verifies that the pill badge renders a color dot and the section display name in both
 * light and dark themes. Color-parsing of the hex string is covered by rendering at all —
 * a bad hex parse falls back to the theme secondary color, which still renders.
 *
 * To record new golden images run:
 *   ./gradlew jvmTest -Proborazzi.test.record=true
 */
class SectionBadgeScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val workSection = SectionDefinition(
        id = "acme-work",
        displayName = "Work – Acme Corp",
        color = "#4A90D9",
        pagePathPrefix = "pages/acme-work",
        journalPathPrefix = "journals/acme-work",
    )

    // ---- Semantic tests (no golden required) ------------------------------------

    @Test
    fun `section badge label is displayed in light theme`() {
        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                Surface { SectionBadge(section = workSection) }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Work – Acme Corp").assertIsDisplayed()
    }

    @Test
    fun `section badge label is displayed in dark theme`() {
        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.DARK) {
                Surface { SectionBadge(section = workSection) }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Work – Acme Corp").assertIsDisplayed()
    }

    @Test
    fun `section badge with null color still renders display name`() {
        val noColorSection = workSection.copy(color = null)
        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                Surface { SectionBadge(section = noColorSection) }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Work – Acme Corp").assertIsDisplayed()
    }

    // ---- Screenshot tests (require golden recording on first run) ---------------

    @Test
    fun sectionBadge_light() {
        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                Surface { SectionBadge(section = workSection) }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/section_badge_light.png")
    }

    @Test
    fun sectionBadge_dark() {
        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.DARK) {
                Surface { SectionBadge(section = workSection) }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/section_badge_dark.png")
    }
}
