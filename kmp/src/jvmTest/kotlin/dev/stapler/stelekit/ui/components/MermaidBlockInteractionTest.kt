package dev.stapler.stelekit.ui.components

import androidx.compose.material3.Surface
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.stapler.stelekit.ui.theme.StelekitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * REQ-4: click and keyboard activation on a successfully rendered [MermaidBlock] both preserve
 * [CodeFenceBlock]'s `onStartEditing` parity (`CodeFenceBlock.kt:48`).
 */
class MermaidBlockInteractionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val validSvg = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"></svg>"""

    private fun renderMermaidBlock(onStartEditing: () -> Unit) {
        composeTestRule.setContent {
            StelekitTheme {
                Surface {
                    MermaidBlock(
                        content = "```mermaid\ngraph TD; A-->B\n```",
                        onStartEditing = onStartEditing,
                        renderer = { MermaidRenderResult.Rendered(validSvg) },
                    )
                }
            }
        }
    }

    @Test
    fun `clicking rendered diagram invokes onStartEditing`() {
        var callCount = 0
        renderMermaidBlock(onStartEditing = { callCount++ })
        composeTestRule.waitForIdle()

        val diagram = composeTestRule.onNodeWithContentDescription("Mermaid diagram — tap to view source")
        diagram.assertIsDisplayed()
        diagram.performClick()

        assertEquals(1, callCount)
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun `pressing Enter while focused invokes onStartEditing without a pointer event`() {
        var callCount = 0
        renderMermaidBlock(onStartEditing = { callCount++ })
        composeTestRule.waitForIdle()

        val diagram = composeTestRule.onNodeWithContentDescription("Mermaid diagram — tap to view source")
        diagram.requestFocus()
        diagram.performKeyInput { pressKey(Key.Enter) }

        assertEquals(1, callCount)
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun `pressing Space while focused invokes onStartEditing without a pointer event`() {
        var callCount = 0
        renderMermaidBlock(onStartEditing = { callCount++ })
        composeTestRule.waitForIdle()

        val diagram = composeTestRule.onNodeWithContentDescription("Mermaid diagram — tap to view source")
        diagram.requestFocus()
        diagram.performKeyInput { pressKey(Key.Spacebar) }

        assertEquals(1, callCount)
    }
}
