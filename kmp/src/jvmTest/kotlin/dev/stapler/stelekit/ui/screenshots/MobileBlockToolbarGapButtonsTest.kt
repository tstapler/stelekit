package dev.stapler.stelekit.ui.screenshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.ComposeUITestBase
import dev.stapler.stelekit.ui.components.EditorCapabilities
import dev.stapler.stelekit.ui.components.EditorToolbar
import dev.stapler.stelekit.ui.screens.FormatAction
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import org.junit.Test

/**
 * Regression coverage for the 4 buttons Phase D added to `MobileBlockToolbar.kt` (Story
 * D.2.1/D.4.2), which shipped with no semantics-tree assertion that clicking them actually
 * dispatches the right [dev.stapler.stelekit.ui.state.BlockStateManager] call:
 *
 * - "☑ TODO"        → `blockStateManager.requestTodoToggle()`
 * - "{ } Code block" → `blockStateManager.requestFormat(FormatAction.CODE_BLOCK)`
 * - "▦ Table"        → `blockStateManager.requestFormat(FormatAction.TABLE_INSERT)`
 * - "Select blocks"  → `blockStateManager.enterSelectionMode(...)`
 *
 * Each test renders the real [EditorToolbar] wiring (not just [MobileBlockToolbar] in isolation)
 * against the [ComposeUITestBase] fake dependency graph, so it exercises the exact production
 * wiring path (`EditorToolbar.kt`) between the button and `BlockStateManager`, not a re-declared
 * stand-in for it.
 */
class MobileBlockToolbarGapButtonsTest : ComposeUITestBase() {

    private val pageUuid = PageUuid("page-1")
    private val blockUuid = BlockUuid("block-1")

    private fun renderToolbarEditingBlock() {
        blockStateManager.observePage(pageUuid)
        blockStateManager.requestEditBlock(blockUuid, cursorIndex = 0)

        composeTestRule.setContent {
            MaterialTheme {
                EditorToolbar(
                    blockStateManager = blockStateManager,
                    capabilities = EditorCapabilities(),
                    searchViewModel = null,
                    isLeftHanded = false,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `TODO button dispatches requestTodoToggle`() {
        val toggled = mutableListOf<Unit>()
        scope.launch { blockStateManager.todoToggleEvents.collect { toggled.add(it) } }

        renderToolbarEditingBlock()

        composeTestRule.onNodeWithContentDescription("Toggle formatting").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Toggle TODO").assertIsDisplayed().performClick()

        assertEquals(
            1,
            toggled.size,
            "Clicking the '☑ TODO' toolbar button must dispatch " +
                "blockStateManager.requestTodoToggle() exactly once.",
        )
    }

    @Test
    fun `Code block button dispatches requestFormat(CODE_BLOCK)`() {
        val requestedFormats = mutableListOf<FormatAction>()
        scope.launch { blockStateManager.formatEvents.collect { requestedFormats.add(it) } }

        renderToolbarEditingBlock()

        composeTestRule.onNodeWithContentDescription("Toggle formatting").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Code block").assertIsDisplayed().performClick()

        assertEquals(
            listOf(FormatAction.CODE_BLOCK),
            requestedFormats,
            "Clicking the '{ } Code block' toolbar button must dispatch " +
                "blockStateManager.requestFormat(FormatAction.CODE_BLOCK).",
        )
    }

    @Test
    fun `Table button dispatches requestFormat(TABLE_INSERT)`() {
        val requestedFormats = mutableListOf<FormatAction>()
        scope.launch { blockStateManager.formatEvents.collect { requestedFormats.add(it) } }

        renderToolbarEditingBlock()

        composeTestRule.onNodeWithContentDescription("Toggle formatting").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Table").assertIsDisplayed().performClick()

        assertEquals(
            listOf(FormatAction.TABLE_INSERT),
            requestedFormats,
            "Clicking the '▦ Table' toolbar button must dispatch " +
                "blockStateManager.requestFormat(FormatAction.TABLE_INSERT).",
        )
    }

    @Test
    fun `Select blocks button dispatches enterSelectionMode`() {
        renderToolbarEditingBlock()

        assertTrue(
            !blockStateManager.isInSelectionMode.value,
            "Precondition: selection mode should not already be active before the click.",
        )

        composeTestRule.onNodeWithContentDescription("Select blocks").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        assertTrue(
            blockStateManager.isInSelectionMode.value,
            "Clicking the 'Select blocks' toolbar button must dispatch " +
                "blockStateManager.enterSelectionMode(...) and enter selection mode.",
        )
        assertEquals(
            setOf(blockUuid.value),
            blockStateManager.selectedBlockUuids.value,
            "enterSelectionMode(...) must be called with the currently-editing block's uuid.",
        )
    }
}
