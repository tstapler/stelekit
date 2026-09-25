package dev.stapler.stelekit.ui.components

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import io.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot test for [MermaidBlock]'s fallback rendering.
 *
 * Task 7.2.1a (validation.md REQ-3): forces the deterministic `Failed` branch via the injectable
 * `renderer` parameter — never a live async JS/WebView render, which would be non-deterministic
 * and unsafe in CI (ADR-001's per-platform renderers are not exercised here at all). Visual parity
 * with [CodeFenceBlock]'s existing raw rendering (ux.md Surface 1 AC7) is structural, not merely
 * asserted: `MermaidBlock`'s `Failed`/loading branch calls the real, unmodified `CodeFenceBlock`
 * with the same arguments a directly-dispatched `language = "mermaid"` fence would use.
 *
 * To record new golden images run:
 *   ./gradlew jvmTest -Proborazzi.test.record=true
 */
class MermaidBlockScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(themeMode: StelekitThemeMode) {
        composeTestRule.setContent {
            StelekitTheme(themeMode = themeMode) {
                Surface {
                    MermaidBlock(
                        content = MERMAID_CONTENT,
                        onStartEditing = {},
                        renderer = { MermaidRenderResult.Failed("test") },
                        fallback = { fallbackContent, fallbackModifier ->
                            CodeFenceBlock(
                                content = fallbackContent,
                                language = "mermaid",
                                onStartEditing = {},
                                modifier = fallbackModifier,
                            )
                        },
                    )
                }
            }
        }
    }

    // ---- Semantic tests (no golden required) -------------------------------------

    @Test
    fun `raw source is displayed when render fails`() {
        render(StelekitThemeMode.LIGHT)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("mermaid").assertExists()
        composeTestRule.onNodeWithText("graph TD", substring = true).assertExists()
    }

    // ---- Screenshot tests (require golden recording on first run) ----------------

    @Test
    fun mermaidBlock_fallback_light() {
        render(StelekitThemeMode.LIGHT)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/mermaid_block_fallback_light.png")
    }

    @Test
    fun mermaidBlock_fallback_dark() {
        render(StelekitThemeMode.DARK)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/mermaid_block_fallback_dark.png")
    }

    companion object {
        private val MERMAID_CONTENT = """
            ```mermaid
            graph TD
              A[Start] --> B{Decision?}
              B -->|Yes| C[End]
            ```
        """.trimIndent()
    }
}
