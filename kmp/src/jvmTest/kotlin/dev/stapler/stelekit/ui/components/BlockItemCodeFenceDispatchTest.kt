package dev.stapler.stelekit.ui.components

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockTypes
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.theme.StelekitTheme
import org.junit.Rule
import org.junit.Test
import kotlin.time.Clock

/**
 * Regression guard for `BlockItem.kt`'s `CODE_FENCE` dispatch arm (Task 6.1.2a): a mermaid-tagged
 * fence must route to [MermaidBlock], everything else must keep going to [CodeFenceBlock] exactly
 * as before. [CodeFenceBlock]'s fallback path inside [MermaidBlock] always relabels its language
 * as `"mermaid"` (see `MermaidBlock.kt`), so a block that were wrongly routed to [MermaidBlock]
 * would show a `"mermaid"` label instead of its real language tag — the observable difference
 * these tests assert on.
 */
class BlockItemCodeFenceDispatchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun now() = Clock.System.now()

    private fun codeFenceBlock(content: String) = Block(
        uuid = BlockUuid("b1"),
        pageUuid = PageUuid("p1"),
        content = content,
        position = 0,
        createdAt = now(),
        updatedAt = now(),
        blockType = BlockTypes.CODE_FENCE,
    )

    private fun render(block: Block) {
        composeTestRule.setContent {
            StelekitTheme {
                Surface {
                    BlockItem(
                        block = block,
                        isEditing = false,
                        onStartEditing = {},
                        onStopEditing = {},
                        onContentChange = { _, _ -> },
                        onLinkClick = {},
                        onNewBlock = {},
                        onSplitBlock = { _, _ -> },
                    )
                }
            }
        }
    }

    @Test
    fun blockItem_should_dispatchToCodeFenceBlock_when_languageIsKotlin() {
        render(codeFenceBlock("```kotlin\nval x = 1\n```"))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("kotlin").assertExists()
    }

    @Test
    fun blockItem_should_dispatchToCodeFenceBlock_when_languageTagAbsent() {
        render(codeFenceBlock("```\nplain text body\n```"))
        composeTestRule.waitForIdle()

        // MermaidBlock's fallback always hard-codes language = "mermaid"; its absence here
        // confirms CodeFenceBlock (language = "") was called directly, not via MermaidBlock.
        composeTestRule.onNodeWithText("mermaid").assertDoesNotExist()
        composeTestRule.onNodeWithText("plain text body").assertExists()
    }
}
