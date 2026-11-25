// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 4.5a: Maximize, raw-edit escape, Ctrl+Shift+Enter, the four-row flag matrix, one editor per block. Gradle-only (Compose host). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class TableInlineAffordancesTest {
    @get:Rule
    val rule = createComposeRule()
    private val host = TableTestHost(rule)

    private fun touch(text: String) {
        rule.onNodeWithText(text).performTouchInput { click() }
        rule.waitForIdle()
    }

    private fun mouse(text: String) {
        rule.onNodeWithText(text).performMouseInput { click() }
        rule.waitForIdle()
    }

    private fun editor() = rule.onNode(hasSetTextAction())
    private fun maximizeNodes() = rule.onAllNodes(hasText(TableMessages.MAXIMIZE))

    private fun chord() {
        rule.onAllNodes(isRoot()).onFirst().performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.ShiftLeft)
            pressKey(Key.Enter)
            keyUp(Key.ShiftLeft)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
    }

    // ---- TableFlagMatrixTest: one test per row ---------------------------------------------------

    @Test
    fun flagMatrix_offOff_should_BeRawMarkdownWithNoStripNoMaximizeNoChord() {
        host.show(inline = false, sheet = false)
        touch("Apple")
        assertEquals(1, host.started)
        rule.onAllNodes(hasTestTag("tableStrip")).assertCountEquals(0)
        maximizeNodes().assertCountEquals(0)
        chord()
        assertEquals(0, host.sheetOpened)
    }

    @Test
    fun flagMatrix_onOff_should_EditWithStripWithoutMaximizeAndNoChord() {
        host.show(inline = true, sheet = false)
        touch("Apple")
        assertEquals(0, host.started)
        rule.onNodeWithTag("tableStrip").assertExists()
        maximizeNodes().assertCountEquals(0)
        rule.onNodeWithTag("strip:Markdown").assertExists()
        chord()
        assertEquals(0, host.sheetOpened)
    }

    @Test
    fun flagMatrix_offOn_should_BeRawMarkdownWithA48dpMaximizeButtonAndNoChord() {
        host.show(inline = false, sheet = true)
        touch("Apple")
        assertEquals(1, host.started)
        rule.onAllNodes(hasTestTag("tableStrip")).assertCountEquals(0)
        maximizeNodes().assertCountEquals(1)
        rule.onNodeWithText(TableMessages.MAXIMIZE).assertHeightIsAtLeast(48.dp)
        chord()
        assertEquals(0, host.sheetOpened)
        rule.onNodeWithText(TableMessages.MAXIMIZE).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, host.sheetOpened)
    }

    @Test
    fun flagMatrix_onOn_should_EditWithStripMaximizeButtonAndChord() {
        host.show(inline = true, sheet = true)
        maximizeNodes().assertCountEquals(1)
        touch("Apple")
        assertEquals(0, host.started)
        // The standalone button yields to the strip; the strip's own Maximize is the entry.
        maximizeNodes().assertCountEquals(1)
        rule.onNodeWithTag("strip:Maximize").assertExists()
        rule.onNodeWithTag("strip:CancelEdit").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        chord()
        assertEquals(1, host.sheetOpened)
    }

    // ---- Maximize and raw edit commit first --------------------------------------------------------------

    @Test
    fun maximizeButton_should_CommitTheDraftOnceThenOpenTheSheetOnce() {
        host.show(inline = true, sheet = true)
        mouse("3") // a mouse edit keeps the strip hidden, so the standalone button is composed
        editor().performTextReplacement("45")
        rule.onNodeWithText(TableMessages.MAXIMIZE).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText(TableMessages.MAXIMIZE).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(1, host.commits.size)
        assertTrue(host.commits.single().contains("| Apple | 45 | red |"))
        assertEquals(1, host.sheetOpened)
    }

    @Test
    fun editAsMarkdown_should_CommitFirstThenStartTheRawEditor() {
        host.show()
        touch("3")
        editor().performTextReplacement("45")
        rule.onNodeWithTag("strip:Markdown").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(1, host.commits.size)
        assertEquals(1, host.started)
    }

    @Test
    fun ctrlShiftEnter_should_CommitThePendingDraftOnceAndOpenTheSheetOnce() {
        host.show(inline = true, sheet = true)
        mouse("3")
        editor().performTextReplacement("45")
        editor().performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.ShiftLeft)
            pressKey(Key.Enter)
            keyUp(Key.ShiftLeft)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
        assertEquals(1, host.commits.size)
        assertEquals(1, host.sheetOpened)
    }

    // ---- one editor per block -----------------------------------------------------------------------------

    @Test
    fun inlineTable_should_BeInertWhileTheSheetOwnsTheBlock_AndInteractiveAgainAfter() {
        host.show(inline = true, sheet = true)
        touch("Apple")
        rule.onNodeWithTag("tableStrip").assertExists()
        host.sheetOwner = host.uuid
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("tableStrip")).assertCountEquals(0)
        touch("Pear")
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertTrue(host.commits.isEmpty())
        host.sheetOwner = null
        host.content = "| Item | Qty | Note |\n|---|---|---|\n| Apple | 9 | red |\n| Pear | 5 | green |"
        rule.waitForIdle()
        rule.onNodeWithText("9").assertExists()
        touch("Pear")
        editor().assertExists()
    }

    // ---- block selection and held conflicts (UX-77, UX-79) ---------------------------------------------------------

    @Test
    fun enteringBlockSelection_should_CommitTheDraftOnceAndHideTheStrip() {
        host.show()
        touch("3")
        editor().performTextReplacement("45")
        host.blockSelection = true
        rule.waitForIdle()
        assertEquals(1, host.commits.size)
        rule.onAllNodes(hasTestTag("tableStrip")).assertCountEquals(0)
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun heldConflict_should_ShowReducedStripAnnounceOnceAndRefuseEveryCommittingKey() {
        host.show()
        mouse("3")
        editor().performTextReplacement("45")
        host.content = "| Item | Qty | Note |\n|---|---|---|\n| Apple | 99 | red |\n| Pear | 5 | green |"
        rule.waitForIdle()
        rule.onNodeWithTag("tableStrip").assertExists()
        rule.onNodeWithTag("strip:CancelEdit").assertExists()
        rule.onNodeWithTag("strip:Resolve").assertExists()
        rule.onNodeWithTag("strip:Markdown").assertExists()
        rule.onAllNodes(hasTestTag("strip:Copy")).assertCountEquals(0)
        rule.onAllNodes(hasContentDescription("Your edit to B2 is not saved. Resolve it first")).assertCountEquals(1)
        for (key in listOf(Key.Enter, Key.Tab)) {
            editor().performKeyInput { pressKey(key) }
            rule.waitForIdle()
        }
        assertTrue(host.commits.isEmpty())
        editor().assertExists()
        // Esc first closes the banner (ladder rung 1, UX-68), then it is Cancel edit with its Undo notice.
        editor().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("conflictBanner")).assertCountEquals(0)
        editor().assertExists()
        editor().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText(TableMessages.DISCARDED_EDIT).assertExists()
    }
}
