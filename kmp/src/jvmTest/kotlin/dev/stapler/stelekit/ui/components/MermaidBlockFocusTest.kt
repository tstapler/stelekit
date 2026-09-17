package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import dev.stapler.stelekit.ui.theme.StelekitTheme
import org.junit.Rule
import org.junit.Test

/**
 * REQ-4 / ux.md AC11: the platform diagram surface (WebView/canvas) must not trap focus —
 * tabbing through a [LazyColumn] with a plain block above and below a [MermaidBlock] must pass
 * through it in one focus stop each direction, with no focus loss or double-tab-stop.
 */
class MermaidBlockFocusTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val validSvg = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"></svg>"""
    private val mermaidLabel = "Mermaid diagram — tap to view source"

    @Test
    fun `tabbing through the diagram surface does not trap or double-stop focus`() {
        lateinit var focusManager: FocusManager

        composeTestRule.setContent {
            StelekitTheme {
                Surface {
                    focusManager = LocalFocusManager.current
                    LazyColumn {
                        item {
                            Text(text = "above", modifier = Modifier.testTag("above").focusable())
                        }
                        item {
                            MermaidBlock(
                                content = "```mermaid\ngraph TD; A-->B\n```",
                                onStartEditing = {},
                                renderer = { MermaidRenderResult.Rendered(validSvg) },
                            )
                        }
                        item {
                            Text(text = "below", modifier = Modifier.testTag("below").focusable())
                        }
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("above").requestFocus()
        composeTestRule.onNodeWithTag("above").assertIsFocused()

        composeTestRule.runOnIdle { focusManager.moveFocus(FocusDirection.Next) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription(mermaidLabel).assertIsFocused()

        composeTestRule.runOnIdle { focusManager.moveFocus(FocusDirection.Next) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("below").assertIsFocused()
    }
}
