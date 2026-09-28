package dev.stapler.stelekit.ui.components

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.stapler.stelekit.ui.theme.StelekitTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * REQ-7/REQ-8: [MermaidBlock]'s cache-hit and size-gate behavior, verified via the injectable
 * `renderer` parameter (never a live JS/WebView render — see `MermaidBlockScreenshotTest`).
 */
class MermaidBlockTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val validSvg = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"></svg>"""

    // mermaidRenderCache is a process-wide singleton; other tests populating it with the same
    // (content, theme, width) key would make a "cache miss" assertion here flaky.
    @Before
    fun clearMermaidCache() {
        mermaidRenderCache.invalidateAll()
    }

    private fun countingRenderer(result: MermaidRenderResult): Pair<AtomicInteger, suspend (MermaidRenderKey) -> MermaidRenderResult> {
        val callCount = AtomicInteger(0)
        val renderer: suspend (MermaidRenderKey) -> MermaidRenderResult = {
            callCount.incrementAndGet()
            result
        }
        return callCount to renderer
    }

    // The production fallback chrome (BlockItem passes the real CodeFenceBlock); kept
    // identical here so the fallback-path assertions below verify the real chrome.
    private val realFallback: @Composable (String, Modifier) -> Unit =
        { fallbackContent, fallbackModifier ->
            CodeFenceBlock(
                content = fallbackContent,
                language = "mermaid",
                onStartEditing = {},
                modifier = fallbackModifier,
            )
        }

    @Test
    fun mermaidBlock_should_invokeRendererExactlyOnce_when_recomposedWithUnchangedKey() {
        val (callCount, renderer) = countingRenderer(MermaidRenderResult.Rendered(validSvg))
        var recomposeTrigger by mutableStateOf(0)

        composeTestRule.setContent {
            StelekitTheme {
                Surface {
                    // Reading recomposeTrigger forces recomposition of this subtree without
                    // changing MermaidBlock's `content` input, so its MermaidRenderKey is unchanged.
                    @Suppress("UNUSED_EXPRESSION") recomposeTrigger
                    MermaidBlock(
                        content = "```mermaid\ngraph TD; A-->B\n```",
                        onStartEditing = {},
                        renderer = renderer,
                        fallback = realFallback,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(1, callCount.get())

        composeTestRule.runOnIdle { recomposeTrigger++ }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { recomposeTrigger++ }
        composeTestRule.waitForIdle()

        assertEquals(1, callCount.get())
    }

    @Test
    fun mermaidBlock_should_renderCodeFenceBlock_when_sourceExceedsMaxLength() {
        val (callCount, renderer) = countingRenderer(MermaidRenderResult.Rendered(validSvg))
        val oversizedBody = "A".repeat(MAX_MERMAID_SOURCE_LENGTH + 1)

        composeTestRule.setContent {
            StelekitTheme {
                Surface {
                    MermaidBlock(
                        content = "```mermaid\n$oversizedBody\n```",
                        onStartEditing = {},
                        renderer = renderer,
                        fallback = realFallback,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(0, callCount.get())
        composeTestRule.onNodeWithText("mermaid").assertExists()
    }

    @Test
    fun mermaidBlock_should_callRenderer_when_sourceAtExactlyMaxLength() {
        val (callCount, renderer) = countingRenderer(MermaidRenderResult.Rendered(validSvg))
        val boundaryBody = "A".repeat(MAX_MERMAID_SOURCE_LENGTH)

        composeTestRule.setContent {
            StelekitTheme {
                Surface {
                    MermaidBlock(
                        content = "```mermaid\n$boundaryBody\n```",
                        onStartEditing = {},
                        renderer = renderer,
                        fallback = realFallback,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(1, callCount.get())
    }

    @Test
    fun mermaidBlock_should_skipRenderer_when_sourceBodyIsEmpty() {
        val (callCount, renderer) = countingRenderer(MermaidRenderResult.Rendered(validSvg))

        composeTestRule.setContent {
            StelekitTheme {
                Surface {
                    MermaidBlock(
                        content = "```mermaid\n```",
                        onStartEditing = {},
                        renderer = renderer,
                        fallback = realFallback,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(0, callCount.get())
        composeTestRule.onNodeWithText("mermaid").assertExists()
    }
}
