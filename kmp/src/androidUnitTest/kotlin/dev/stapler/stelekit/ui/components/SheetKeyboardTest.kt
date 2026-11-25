// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridCommand
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.TableShortcutMap
import dev.stapler.stelekit.ui.components.sheet.chordKeyName
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

/** Story 5.3 on the real sheet host: ADR-009 keys reach the grid, the bar's field owns `Edit`, and one commit happens per move. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class SheetKeyboardTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private val content = gridContent(rows = 4, cols = 3)
    private val f = SheetFixture(rule, content)

    @After
    fun tearDown() = f.close()

    private fun press(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, target: String = "sheetGridFocus") {
        f.unconsumed.clear()
        rule.onNodeWithTag(target).performKeyInput {
            if (ctrl) keyDown(Key.CtrlLeft)
            if (shift) keyDown(Key.ShiftLeft)
            if (alt) keyDown(Key.AltLeft)
            pressKey(key)
            if (alt) keyUp(Key.AltLeft)
            if (shift) keyUp(Key.ShiftLeft)
            if (ctrl) keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
    }

    private fun typeInBar(text: String) {
        rule.onNodeWithTag("barField").performTextReplacement(text)
        rule.waitForIdle()
    }

    @Test
    fun arrows_should_MoveTheSelection_When_Navigate() {
        f.show()
        press(Key.DirectionDown)
        assertEquals(CellAddress(3, 1), f.s.selection.anchor)
        press(Key.DirectionRight)
        assertEquals(CellAddress(3, 2), f.s.selection.anchor)
        press(Key.DirectionRight, shift = true)
        assertEquals(2, f.s.selection.cellCount)
    }

    @Test
    fun enter_should_StartEditWithCaretAtEnd_When_NavigateAtB2() {
        f.show()
        press(Key.DirectionRight)
        press(Key.Enter)
        assertEquals(GridMode.Edit, f.s.mode)
        assertEquals("2-2", f.s.fieldValue.text)
        assertEquals(3, f.s.fieldValue.selection.start)
        assertEquals("2-2", rule.editableTextOf("barField"))
    }

    @Test
    fun typingAPrintableKey_should_ReplaceTheCellAndEnterEdit() {
        f.show()
        press(Key.X)
        assertEquals(GridMode.Edit, f.s.mode)
        assertEquals("x", f.s.fieldValue.text)
        assertTrue(f.unconsumed.isEmpty(), "the grid consumed the key so the field does not type it twice")
    }

    @Test
    fun tab_should_CommitOnceAndMoveRight_When_EditHasADraft() {
        f.show()
        val before = f.h.managerVersion()
        press(Key.DirectionRight)
        press(Key.Enter)
        typeInBar("9")
        press(Key.Tab, target = "barField")
        assertEquals(before + 1, f.h.managerVersion(), "exactly one write")
        assertTrue(f.h.managerContent().contains("| 2-1 | 9 | 2-3 |"), f.h.managerContent())
        assertEquals(CellAddress(2, 3), f.s.selection.anchor)
        assertEquals(GridMode.Navigate, f.s.mode)
    }

    @Test
    fun shiftEnter_should_CommitAndMoveUp() {
        f.show()
        press(Key.DirectionDown)
        press(Key.Enter)
        typeInBar("up")
        press(Key.Enter, shift = true, target = "barField")
        assertTrue(f.h.managerContent().contains("| up | 3-2 | 3-3 |"), f.h.managerContent())
        assertEquals(CellAddress(2, 1), f.s.selection.anchor)
    }

    @Test
    fun altEnter_should_WriteLineBreakAsBr_When_TypedOnBothSides() {
        f.show()
        press(Key.Enter)
        typeInBar("a")
        press(Key.Enter, alt = true, target = "barField")
        rule.onNodeWithTag("barField").performTextInput("b")
        rule.waitForIdle()
        press(Key.Tab, target = "barField")
        assertTrue(f.h.managerContent().contains("| a<br>b |"), f.h.managerContent())
    }

    @Test
    fun enter_should_CommitStayAndAnnounceEndOfTableOnce_When_LastRow() {
        f.show()
        repeat(3) { press(Key.DirectionDown) }
        assertEquals(CellAddress(5, 1), f.s.selection.anchor)
        val rows = f.s.rowCount
        val before = f.h.managerVersion()
        press(Key.Enter)
        typeInBar("9")
        val seq = f.s.announcementSeq
        press(Key.Enter, target = "barField")
        assertEquals(before + 1, f.h.managerVersion())
        assertEquals(rows, f.s.rowCount)
        assertEquals(CellAddress(5, 1), f.s.selection.anchor)
        assertEquals(GridMode.Navigate, f.s.mode)
        assertEquals(TableMessages.END_OF_TABLE, f.s.announcement)
        assertEquals(seq + 1, f.s.announcementSeq)
    }

    @Test
    fun ctrlZ_should_BeFieldScoped_When_Edit_And_NeverReachThePageHistory() {
        f.show()
        press(Key.Enter)
        typeInBar("abc")
        val before = f.h.managerVersion()
        press(Key.Z, ctrl = true, target = "barField")
        assertTrue(f.unconsumed.isEmpty(), "the field consumed its own Ctrl+Z")
        assertEquals(before, f.h.managerVersion())
        assertEquals(GridMode.Edit, f.s.mode)
    }

    @Test
    fun escapeAndPlainKeys_should_RideTheLadder_And_NotCloseTheSheet() {
        f.show()
        press(Key.Escape)
        assertTrue(Key.Escape in f.unconsumed, "the grid leaves Esc to the ladder")
        assertTrue(f.h.closes.isEmpty())
    }

    @Test
    fun everyNavigateBinding_should_BeConsumedByTheGrid() {
        f.show()
        val skipped = setOf(GridCommand.ToggleMaximize, GridCommand.EditAsMarkdown, GridCommand.Cancel)
        val tested = mutableSetOf<GridCommand>()
        TableShortcutMap.bindings.filter { it.mode == GridMode.Navigate && it.command !in skipped }.forEach { b ->
            val key = KEYS.first { chordKeyName(it) == b.chord.key }
            press(key, ctrl = b.chord.mod, shift = b.chord.shift, alt = b.chord.alt)
            assertTrue(f.unconsumed.isEmpty(), "${b.chord} -> ${b.command} was not consumed: ${f.unconsumed}")
            tested += b.command
            if (f.s.mode == GridMode.Edit) f.s.cancelEdit()
        }
        listOf(GridCommand.Copy, GridCommand.Cut, GridCommand.Paste, GridCommand.InsertRowOrColumn, GridCommand.DeleteRowOrColumn, GridCommand.OpenContextMenu)
            .forEach { assertTrue(it in f.sink.seen, "$it reached the command sink") }
        assertEquals(
            TableShortcutMap.bindings.filter { it.mode == GridMode.Navigate }.map { it.command }.toSet() - skipped,
            tested,
        )
        assertTrue(f.h.closes.isEmpty())
    }

    @Test
    fun maximizeAndEditAsMarkdownChords_should_CloseTheSheetThroughTheHostPath() {
        f.show()
        press(Key.Enter, ctrl = true, shift = true)
        assertEquals(listOf(dev.stapler.stelekit.ui.components.sheet.SheetCloseReason.User), f.h.closes)
    }

    @Test
    fun shiftF2_should_CloseIntoTheRawEditor() {
        f.show()
        press(Key.F2, shift = true)
        assertEquals(listOf(dev.stapler.stelekit.ui.components.sheet.SheetCloseReason.EditAsMarkdown), f.h.closes)
    }

    @Test
    fun readOnly_should_NotEnterEdit_And_ShowTheReadOnlyState() {
        f.show(readOnly = true)
        press(Key.X)
        assertEquals(GridMode.Navigate, f.s.mode)
        rule.onNodeWithTag("barReadOnly").assertExists()
        assertFalse(rule.exists("barDone"))
    }

    private companion object {
        val KEYS = listOf(
            Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.Tab, Key.Enter, Key.F2, Key.MoveHome, Key.MoveEnd,
            Key.PageUp, Key.PageDown, Key.Spacebar, Key.A, Key.C, Key.X, Key.V, Key.Delete, Key.Backspace, Key.Z, Key.Y, Key.Equals, Key.Minus,
            Key.F10, Key.Menu,
        )
    }
}
