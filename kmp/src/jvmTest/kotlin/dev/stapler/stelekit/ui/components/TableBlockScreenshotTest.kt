package dev.stapler.stelekit.ui.components

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import io.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot and semantic tests for [TableBlock].
 *
 * The screenshots guard against the class of bug where `.background()` is used without
 * an explicit `surface` background on the outer container, or where `Text` cells lack an
 * explicit `color` — both of which cause unreadable text when the theme is switched.
 *
 * To record new golden images run:
 *   ./gradlew jvmTest -Proborazzi.test.record=true
 */
class TableBlockScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(themeMode: StelekitThemeMode) {
        composeTestRule.setContent {
            StelekitTheme(themeMode = themeMode) {
                Surface {
                    TableBlock(
                        content = TABLE_CONTENT,
                        onStartEditing = {}
                    )
                }
            }
        }
    }

    // ---- Semantic tests (no golden required) ------------------------------------

    @Test
    fun `header cells are displayed in light theme`() {
        render(StelekitThemeMode.LIGHT)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Scenario").assertIsDisplayed()
        composeTestRule.onNodeWithText("Prefer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reason").assertIsDisplayed()
    }

    @Test
    fun `header cells are displayed in dark theme`() {
        render(StelekitThemeMode.DARK)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Scenario").assertIsDisplayed()
        composeTestRule.onNodeWithText("Prefer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reason").assertIsDisplayed()
    }

    @Test
    fun `body cells are displayed`() {
        render(StelekitThemeMode.LIGHT)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Collection").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sequence").assertIsDisplayed()
    }

    // ---- Screenshot tests (require golden recording on first run) ---------------

    @Test
    fun tableBlock_light() {
        render(StelekitThemeMode.LIGHT)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/table_block_light.png")
    }

    @Test
    fun tableBlock_dark() {
        render(StelekitThemeMode.DARK)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/table_block_dark.png")
    }

    companion object {
        private val TABLE_CONTENT = """
            | Scenario | Prefer | Reason |
            |---|---|---|
            | Small collection | Collection | Inline functions |
            | Large collection | Sequence | Avoids allocations |
        """.trimIndent()
    }
}
