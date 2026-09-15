package dev.stapler.stelekit.ui.screenshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.ComposeUITestBase
import dev.stapler.stelekit.ui.components.MobileBlockToolbar
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Regression coverage for design/ux.md's cross-cutting error-state table, criterion 14
 * ("Disk-conflict pending resolution"): `MobileBlockToolbar`'s Undo/Redo buttons must render
 * `enabled = false` with a "Resolve file conflict to continue" tooltip/label while a disk
 * conflict is pending, instead of silently no-op'ing on tap, and must re-enable automatically
 * once the conflict resolves (i.e. they must read `hasDiskConflictPending` live, not a value
 * captured at first composition).
 */
class MobileBlockToolbarDiskConflictTest : ComposeUITestBase() {

    private val conflictTooltip = "Resolve file conflict to continue"

    @Test
    fun `Undo and Redo render disabled with conflict tooltip when a disk conflict is pending`() {
        var undoClicks = 0
        var redoClicks = 0

        composeTestRule.setContent {
            MaterialTheme {
                MobileBlockToolbar(
                    editingBlockId = "block-1",
                    onIndent = {},
                    onOutdent = {},
                    onMoveUp = {},
                    onMoveDown = {},
                    onUndo = { undoClicks++ },
                    onRedo = { redoClicks++ },
                    hasDiskConflictPending = true,
                )
            }
        }
        composeTestRule.waitForIdle()

        // Both Undo and Redo now share the same conflict-blocked description/tooltip text.
        val blockedButtons = composeTestRule.onAllNodesWithContentDescription(conflictTooltip)
        blockedButtons.assertCountEquals(2)
        blockedButtons[0].assertIsDisplayed().assertIsNotEnabled()
        blockedButtons[1].assertIsDisplayed().assertIsNotEnabled()

        // The original "Undo"/"Redo" labels must no longer be present while blocked.
        composeTestRule.onAllNodesWithContentDescription("Undo").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Redo").assertCountEquals(0)

        assertEquals(0, undoClicks, "Disabled Undo must not dispatch onUndo.")
        assertEquals(0, redoClicks, "Disabled Redo must not dispatch onRedo.")
    }

    @Test
    fun `Undo and Redo render normally when no disk conflict is pending`() {
        var undoClicks = 0
        var redoClicks = 0

        composeTestRule.setContent {
            MaterialTheme {
                MobileBlockToolbar(
                    editingBlockId = "block-1",
                    onIndent = {},
                    onOutdent = {},
                    onMoveUp = {},
                    onMoveDown = {},
                    onUndo = { undoClicks++ },
                    onRedo = { redoClicks++ },
                    hasDiskConflictPending = false,
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Undo").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Redo").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onAllNodesWithContentDescription(conflictTooltip).assertCountEquals(0)

        composeTestRule.onNodeWithContentDescription("Undo").performClick()
        composeTestRule.onNodeWithContentDescription("Redo").performClick()

        assertEquals(1, undoClicks, "Enabled Undo must dispatch onUndo exactly once per tap.")
        assertEquals(1, redoClicks, "Enabled Redo must dispatch onRedo exactly once per tap.")
    }

    @Test
    fun `Undo and Redo re-enable automatically once the conflict resolves (live state, not cached)`() {
        var hasDiskConflictPending by mutableStateOf(true)

        composeTestRule.setContent {
            MaterialTheme {
                MobileBlockToolbar(
                    editingBlockId = "block-1",
                    onIndent = {},
                    onOutdent = {},
                    onMoveUp = {},
                    onMoveDown = {},
                    hasDiskConflictPending = hasDiskConflictPending,
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithContentDescription(conflictTooltip).assertCountEquals(2)

        // Resolve the conflict — MobileBlockToolbar was not recreated, only its live input changed.
        hasDiskConflictPending = false
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Undo").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Redo").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onAllNodesWithContentDescription(conflictTooltip).assertCountEquals(0)
    }
}
