// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.LadderAction
import dev.stapler.stelekit.table.LadderKey
import dev.stapler.stelekit.ui.components.sheet.SheetCloseReason
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 5.3d: one test per rung of the sheet's Back and Esc ladder and per touch equivalent, over the real host.
 * Back reaches the ladder through `TableSheetSession.onBack`, the same call the host `Dialog`'s dismiss request makes.
 * Rungs owned by later workers (header menus, paste fallback box) and platform popups (top-bar overflow, shortcuts
 * panel) are not exercised here.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class SheetBackLadderTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private val f = SheetFixture(rule, gridContent(rows = 5, cols = 4))

    @After
    fun tearDown() = f.close()

    private fun back(key: LadderKey = LadderKey.Back): LadderAction {
        var action: LadderAction? = null
        rule.runOnUiThread { action = f.h.session!!.onBack(key) }
        rule.waitForIdle()
        return action!!
    }

    private fun press(key: Key, ctrl: Boolean = false, shift: Boolean = false, target: String = "sheetGridFocus") {
        rule.onNodeWithTag(target).performKeyInput {
            if (ctrl) keyDown(Key.CtrlLeft)
            if (shift) keyDown(Key.ShiftLeft)
            pressKey(key)
            if (shift) keyUp(Key.ShiftLeft)
            if (ctrl) keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
    }

    private fun editB3(text: String) {
        rule.runOnUiThread { f.s.startEdit(CellAddress(3, 2)) }
        rule.waitForIdle()
        rule.onNodeWithTag("barField").performTextReplacement(text)
        rule.waitForIdle()
    }

    @Test
    fun rung1_back_should_DismissOnlyTheBanner_KeepDraftAndShowTheResolveChip() {
        f.show()
        editB3("mine")
        f.h.foreignWrite(withCell(f.h.c0, CellAddress(2, 1), "theirs"))
        rule.waitForIdle()
        rule.onNodeWithTag("conflictBanner").assertExists()
        assertEquals(LadderAction.DismissBanner, back())
        rule.onNodeWithTag("conflictBanner").assertDoesNotExist()
        rule.onNodeWithTag("resolveChip").assertExists()
        assertEquals("mine", f.s.fieldValue.text)
        assertEquals(GridMode.Edit, f.s.mode)
        assertTrue(f.h.closes.isEmpty())
    }

    @Test
    fun rung2_back_should_CloseOnlyTheEditBarMenu() {
        f.show()
        editB3("x")
        rule.onNodeWithTag("barOverflow").performClick()
        rule.onNodeWithTag("menuNextColumn").assertExists()
        assertEquals(LadderAction.CloseMenu, back())
        rule.onNodeWithTag("menuNextColumn").assertDoesNotExist()
        assertEquals(GridMode.Edit, f.s.mode)
        assertEquals("x", f.s.fieldValue.text)
        assertTrue(f.h.closes.isEmpty())
    }

    @Test
    fun rung2_back_should_CloseOnlyTheExtendPicker() {
        f.show()
        editB3("=SUM(")
        rule.onNodeWithTag("cell-B2").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("extendButton").performClick()
        rule.onNodeWithTag("extend-Down").assertExists()
        assertEquals(LadderAction.CloseMenu, back())
        rule.onNodeWithTag("extend-Down").assertDoesNotExist()
        assertEquals(GridMode.Edit, f.s.mode)
    }

    @Test
    fun rung3_back_should_HideTheImeAndKeepEdit() {
        f.show()
        editB3("x")
        var hidden = 0
        rule.runOnUiThread {
            f.s.imeVisible = true
            f.s.hideIme = { hidden++; f.s.imeVisible = false }
        }
        assertEquals(LadderAction.HideIme, back())
        assertEquals(1, hidden)
        assertEquals(GridMode.Edit, f.s.mode)
    }

    @Test
    fun rung4_back_should_CommitOnceAndStayOnTheCell_When_EditImeHidden() {
        f.show()
        val before = f.h.managerVersion()
        editB3("done")
        assertEquals(LadderAction.Done, back())
        assertEquals(before + 1, f.h.managerVersion())
        assertEquals(GridMode.Navigate, f.s.mode)
        assertEquals(CellAddress(3, 2), f.s.selection.anchor)
        assertTrue(f.h.closes.isEmpty())
    }

    @Test
    fun rung4_esc_should_RestoreTheDraft_And_NotCommit() {
        f.show()
        val before = f.h.managerVersion()
        editB3("nope")
        press(Key.Escape, target = "barField")
        assertEquals(GridMode.Navigate, f.s.mode)
        assertEquals(before, f.h.managerVersion())
        assertEquals("3-2", f.s.doc!!.cell(CellAddress(3, 2))!!.value)
        assertTrue(f.h.closes.isEmpty())
    }

    @Test
    fun rung5_backAndEsc_should_CollapseARange() {
        f.show()
        rule.runOnUiThread { f.s.extendSelectionTo(CellAddress(4, 3)) }
        assertEquals(LadderAction.CollapseRange, back())
        assertTrue(f.s.selection.isSingleCell)
        rule.runOnUiThread { f.s.extendSelectionTo(CellAddress(4, 3)) }
        press(Key.Escape)
        assertTrue(f.s.selection.isSingleCell)
        assertTrue(f.h.closes.isEmpty())
    }

    @Test
    fun rung6_back_should_CommitFlushAndCloseTheSheet_When_NavigateSingleCell() {
        f.show()
        assertEquals(LadderAction.CloseSheet, back())
        rule.waitUntil(5_000) { f.h.flushes == 1 }
        assertEquals(listOf(SheetCloseReason.User), f.h.closes)
    }

    @Test
    fun esc_should_NotCloseSheet_When_NavigateSingleCell() {
        f.show()
        press(Key.Escape)
        assertEquals(LadderAction.NotHandled, back(LadderKey.Esc))
        assertTrue(f.h.closes.isEmpty())
        rule.onNodeWithTag("tableSheetHost").assertExists()
    }

    @Test
    fun chord_should_CloseSheet_When_CtrlShiftEnter() {
        f.show()
        editB3("chord")
        press(Key.Enter, ctrl = true, shift = true, target = "barField")
        rule.waitUntil(5_000) { f.h.flushes == 1 }
        assertEquals(listOf(SheetCloseReason.User), f.h.closes)
        assertTrue(f.h.managerContent().contains("chord"), "the pending draft was committed before closing")
    }

    @Test
    fun touch_imeActionNext_should_CommitOnceMoveDownAndShowTheNextLiteral() {
        f.show()
        val before = f.h.managerVersion()
        editB3("9")
        rule.onNodeWithTag("barField").performImeAction()
        rule.waitForIdle()
        assertEquals(before + 1, f.h.managerVersion())
        assertEquals(CellAddress(4, 2), f.s.selection.anchor)
        assertEquals(GridMode.Navigate, f.s.mode)
        assertEquals("4-2", rule.editableTextOf("barField"))
        rule.onNodeWithTag("cell-B4").performClick()
        rule.waitForIdle()
        assertEquals(GridMode.Edit, f.s.mode, "tapping the selected cell opens the editor again")
    }

    @Test
    fun touch_nextColumnMenuItem_should_CommitAndMoveRight() {
        f.show()
        val before = f.h.managerVersion()
        editB3("9")
        rule.onNodeWithTag("barOverflow").performClick()
        rule.onNodeWithTag("menuNextColumn").performClick()
        rule.waitForIdle()
        assertEquals(before + 1, f.h.managerVersion())
        assertEquals(CellAddress(3, 3), f.s.selection.anchor)
    }

    @Test
    fun touch_insertLineBreakMenuItem_should_WriteBr() {
        f.show()
        editB3("a")
        rule.onNodeWithTag("barOverflow").performClick()
        rule.onNodeWithTag("menuInsertLineBreak").performClick()
        rule.waitForIdle()
        rule.runOnUiThread { f.s.onFieldChange(androidx.compose.ui.text.input.TextFieldValue(f.s.fieldValue.text + "b", androidx.compose.ui.text.TextRange(3))) }
        rule.onNodeWithTag("barDone").performClick()
        rule.waitForIdle()
        assertTrue(f.h.managerContent().contains("| a<br>b |"), f.h.managerContent())
        assertFalse(f.h.closes.isNotEmpty())
    }
}
