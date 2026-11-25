// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridCommand
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.table.LadderAction
import dev.stapler.stelekit.table.LadderKey
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.ui.components.TableMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stories 5.2 (draft hoisting, keys, Saver), 5.3 (commit-and-move) and 5.8 (point mode, error detail) at the state level. */
class SheetStateTest {
    private class FakeHost(override val readOnly: Boolean = false) : SheetHostPort {
        val commits = mutableListOf<Pair<String, String>>()
        var accept = true
        var undos = 0
        var redos = 0
        var closes = 0
        var markdown = 0
        override fun commit(text: String, label: String, draft: DraftText?): Boolean {
            if (!accept) return false
            commits += text to label
            return true
        }
        override fun undo() { undos++ }
        override fun redo() { redos++ }
        override fun closeSheet() { closes++ }
        override fun editAsMarkdown() { markdown++ }
    }

    private class RecordingSink : SheetCommandSink {
        val seen = mutableListOf<GridCommand>()
        override fun onCommand(command: GridCommand, selection: GridSelection): Boolean = true.also { seen += command }
    }

    private val table = "| Item | Qty | Price |\n|---|---|---|\n| Apple | 3 | 1 |\n| Pear | 5 | 2 |\n| Fig | 7 | 3 |"
    private val host = FakeHost()
    private val sink = RecordingSink()

    private fun state(content: String = table, h: FakeHost = host) = SheetState(parseSheet(content), h, sink)

    private fun SheetState.type(text: String) = onFieldChange(TextFieldValue(text, TextRange(text.length)))

    private fun SheetState.typeWithCaret(text: String, caret: Int) = onFieldChange(TextFieldValue(text, TextRange(caret)))

    // ---- navigate and edit transitions (5.3) -----------------------------------------------------

    @Test
    fun enter_should_StartEditWithCaretAtEnd_When_Navigate() {
        val s = state()
        s.onKey(SheetKeyDecision.Run(GridCommand.StartEdit))
        assertEquals(GridMode.Edit, s.mode)
        assertEquals("Apple", s.fieldValue.text)
        assertEquals(TextRange(5), s.fieldValue.selection)
    }

    @Test
    fun typing_should_ReplaceCellAndEnterEdit_When_Navigate() {
        val s = state()
        s.onKey(SheetKeyDecision.TypeChar("x"))
        assertEquals(GridMode.Edit, s.mode)
        assertEquals("x", s.fieldValue.text)
    }

    @Test
    fun tab_should_CommitOnceAndMoveRight_When_EditDraftChanged() {
        val s = state()
        s.startEdit(CellAddress(2, 2))
        s.type("9")
        s.perform(GridCommand.Tab())
        assertEquals(1, host.commits.size)
        assertTrue(host.commits.single().first.contains("| 9 |"), host.commits.single().first)
        assertEquals(CellAddress(2, 3), s.selection.anchor)
        assertEquals(GridMode.Navigate, s.mode)
    }

    @Test
    fun shiftEnter_should_CommitAndMoveUp() {
        val s = state()
        s.startEdit(CellAddress(3, 2))
        s.type("8")
        s.perform(GridCommand.Enter(back = true))
        assertEquals(1, host.commits.size)
        assertEquals(CellAddress(2, 2), s.selection.anchor)
    }

    @Test
    fun enter_should_CommitStayAndAnnounceEndOfTableOnce_When_LastRow() {
        val s = state()
        s.startEdit(CellAddress(4, 2))
        s.type("9")
        val rows = s.rowCount
        val before = s.announcementSeq
        s.perform(GridCommand.Enter())
        assertEquals(1, host.commits.size)
        assertEquals(rows, s.rowCount, "Enter at the last row never adds one")
        assertEquals(CellAddress(4, 2), s.selection.anchor)
        assertEquals(GridMode.Navigate, s.mode)
        assertEquals(TableMessages.END_OF_TABLE, s.announcement)
        assertEquals(before + 1, s.announcementSeq)
    }

    @Test
    fun altEnter_should_WriteLineBreakAsBr_When_CommitAfterTypingBothSides() {
        val s = state()
        s.startEdit(CellAddress(2, 1))
        s.type("a")
        s.perform(GridCommand.InsertLineBreak)
        s.type(s.fieldValue.text + "b")
        s.perform(GridCommand.Tab())
        assertTrue(host.commits.single().first.contains("| a<br>b |"), host.commits.single().first)
    }

    @Test
    fun commit_should_NotRun_When_DraftUnchanged() {
        val s = state()
        s.startEdit(CellAddress(2, 1))
        s.perform(GridCommand.Tab())
        assertTrue(host.commits.isEmpty())
    }

    @Test
    fun composing_should_PassToImeAndConsumeNothing() {
        val s = state()
        s.startEdit(CellAddress(2, 1))
        s.onFieldChange(TextFieldValue("ni", TextRange(2), composition = TextRange(0, 2)))
        assertTrue(s.composing)
        val enter = decideSheetKey(dev.stapler.stelekit.table.Chord("Enter"), GridMode.Edit, composing = s.composing)
        assertEquals(SheetKeyDecision.PassToIme, enter)
        assertFalse(s.onKey(enter))
        assertTrue(host.commits.isEmpty())
    }

    @Test
    fun held_commit_should_KeepDraftAndStayInEdit_When_HostRefuses() {
        val s = state()
        s.startEdit(CellAddress(2, 2))
        s.type("9")
        host.accept = false
        s.perform(GridCommand.Tab())
        assertEquals(GridMode.Edit, s.mode)
        assertEquals("9", s.fieldValue.text)
        assertEquals(CellAddress(2, 2), s.selection.anchor)
    }

    @Test
    fun readOnly_should_RefuseEditAndAnnounce() {
        val ro = FakeHost(readOnly = true)
        val s = state(h = ro)
        s.perform(GridCommand.StartEdit)
        assertEquals(GridMode.Navigate, s.mode)
        assertEquals(TableMessages.READ_ONLY, s.announcement)
    }

    @Test
    fun tapOnSelectedCell_should_Edit_And_TapOnOther_should_Select() {
        val s = state()
        s.onCellTap(CellAddress(3, 1))
        assertEquals(CellAddress(3, 1), s.selection.anchor)
        assertEquals(GridMode.Navigate, s.mode)
        s.onCellTap(CellAddress(3, 1))
        assertEquals(GridMode.Edit, s.mode)
    }

    @Test
    fun tapOtherCell_should_CommitAndSelect_When_EditingPlainText() {
        val s = state()
        s.startEdit(CellAddress(2, 1))
        s.type("abc")
        s.onCellTap(CellAddress(3, 3))
        assertEquals(1, host.commits.size)
        assertEquals(GridMode.Navigate, s.mode)
        assertEquals(CellAddress(3, 3), s.selection.anchor)
    }

    // ---- every non-navigation command reaches its owner (5.3c) ---------------------------------------

    @Test
    fun clipboardStructureAndMenuCommands_should_ReachTheSink() {
        val s = state()
        listOf(
            GridCommand.Copy, GridCommand.Cut, GridCommand.Paste, GridCommand.InsertRowOrColumn,
            GridCommand.DeleteRowOrColumn, GridCommand.OpenContextMenu,
        ).forEach { assertTrue(s.perform(it), it.toString()) }
        assertEquals(6, sink.seen.size)
    }

    @Test
    fun undoRedoMaximizeAndMarkdown_should_ReachTheHost() {
        val s = state()
        s.perform(GridCommand.Undo)
        s.perform(GridCommand.Redo)
        s.perform(GridCommand.ToggleMaximize)
        s.perform(GridCommand.EditAsMarkdown)
        assertEquals(listOf(1, 1, 1, 1), listOf(host.undos, host.redos, host.closes, host.markdown))
    }

    @Test
    fun clearCells_should_EmptyTheRangeInOneCommit() {
        val s = state()
        s.extendSelectionTo(CellAddress(3, 2))
        s.perform(GridCommand.ClearCells)
        assertEquals(1, host.commits.size)
        val doc = s.doc!!
        listOf(CellAddress(2, 1), CellAddress(2, 2), CellAddress(3, 1), CellAddress(3, 2)).forEach {
            assertEquals("", doc.cell(it)!!.value)
        }
    }

    @Test
    fun fieldScopedUndo_should_StepTheDraftAndNeverTouchTheHost() {
        val s = state()
        s.startEdit(CellAddress(2, 1))
        s.type("Applex")
        s.perform(GridCommand.Undo)
        assertEquals(0, host.undos)
        assertTrue(host.commits.isEmpty())
    }

    // ---- hoisting and keys (5.2) -----------------------------------------------------------------------

    @Test
    fun draft_should_SurviveScrollingBecauseItLivesInState_And_NeverCommitOnItsOwn() {
        val s = state()
        s.startEdit(CellAddress(3, 2))
        s.onFieldChange(TextFieldValue("abc", TextRange(1, 2)))
        s.recordScroll(150, 0, 0)
        assertEquals("abc", s.fieldValue.text)
        assertEquals(TextRange(1, 2), s.fieldValue.selection)
        assertEquals(CellAddress(3, 2), s.editingAddress)
        assertTrue(host.commits.isEmpty())
    }

    @Test
    fun rowKey_should_FollowTheEditingRow_When_RowInsertedAbove() {
        val s = state()
        s.startEdit(CellAddress(4, 2))
        val keyBefore = s.rowKeyOf(4)
        val others = listOf(1, 2, 3).map { s.rowKeyOf(it) }
        s.applyEdit(TableEdit.InsertRows(before = 2), "insert row")
        assertEquals(CellAddress(5, 2), s.editingAddress)
        assertEquals(keyBefore, s.rowKeyOf(5))
        assertEquals(others[0], s.rowKeyOf(1))
        assertTrue(s.rowKeyOf(2) !in others + keyBefore, "the new row gets a new key")
        assertEquals(others[1], s.rowKeyOf(3))
        assertNotNull(s.draft)
    }

    @Test
    fun saver_should_RestoreSelectionDraftViewAndScroll() {
        val parsed = parseSheet(table)
        val s = SheetState(parsed, host)
        s.startEdit(CellAddress(3, 2))
        s.onFieldChange(TextFieldValue("abc", TextRange(1, 2)))
        s.recordScroll(150, 12, 340)
        s.updateView(s.view.setWidth(2, 200f).withFreezeFirstColumn(true))
        val saver = SheetState.saver(parsed, host)
        val saved = with(saver) { SaverScope { true }.save(s) }
        assertNotNull(saved)
        val back = saver.restore(saved)!!
        assertEquals(GridMode.Edit, back.mode)
        assertEquals("abc", back.fieldValue.text)
        assertEquals(TextRange(1, 2), back.fieldValue.selection)
        assertEquals(CellAddress(3, 2), back.editingAddress)
        assertEquals(CellAddress(3, 2), back.selection.anchor)
        assertEquals(200f, back.view.widthOf(2))
        assertTrue(back.view.freezeFirstColumn)
        assertEquals(Triple(150, 12, 340), Triple(back.firstVisibleIndex, back.firstVisibleOffset, back.horizontalScroll))
        assertTrue(host.commits.isEmpty(), "restoring commits nothing")
    }

    // ---- rebuild, hooks (host contract) -------------------------------------------------------------------

    @Test
    fun rebuild_should_ReparseAndKeepSelectionByRowAndColumn() {
        val s = state()
        s.onCellTap(CellAddress(3, 2))
        val other = table.replace("| Pear | 5 | 2 |", "| Pear | 50 | 2 |")
        s.rebuild(other, s.selectionAnchor)
        assertEquals("50", s.doc!!.cell(CellAddress(3, 2))!!.value)
        assertEquals(CellAddress(3, 2), s.selection.anchor)
    }

    @Test
    fun hooks_should_ReportDirtyDraftAndCommitPendingOnce() {
        val s = state()
        s.startEdit(CellAddress(2, 1))
        assertFalse(s.hasDirtyDraft)
        s.type("Apples")
        assertTrue(s.hasDirtyDraft)
        assertEquals(DraftText(CellAddress(2, 1), "Apples"), s.dirtyDraft())
        s.onFieldChange(TextFieldValue("Apples", TextRange(6), composition = TextRange(0, 6)))
        s.commitPendingDraft()
        assertEquals(1, host.commits.size)
        s.commitPendingDraft()
        assertEquals(1, host.commits.size)
    }

    // ---- back ladder (5.3d) ----------------------------------------------------------------------------------

    @Test
    fun back_should_CollapseRangeThenLeaveSingleCellToTheSession() {
        val s = state()
        s.extendSelectionTo(CellAddress(3, 2))
        assertEquals(LadderAction.CollapseRange, s.onBack(LadderKey.Back))
        assertTrue(s.selection.isSingleCell)
        assertEquals(LadderAction.NotHandled, s.onBack(LadderKey.Back), "the session closes the sheet")
        assertEquals(LadderAction.NotHandled, s.onBack(LadderKey.Esc))
    }

    @Test
    fun back_should_HideImeThenDoneThenStay_When_EditWithIme() {
        val s = state()
        s.startEdit(CellAddress(2, 2))
        s.type("9")
        s.imeVisible = true
        var hidden = 0
        s.hideIme = { hidden++; s.imeVisible = false }
        assertEquals(LadderAction.HideIme, s.onBack(LadderKey.Back))
        assertEquals(GridMode.Edit, s.mode)
        assertEquals(1, hidden)
        assertEquals(LadderAction.Done, s.onBack(LadderKey.Back))
        assertEquals(1, host.commits.size)
        assertEquals(GridMode.Navigate, s.mode)
        assertEquals(CellAddress(2, 2), s.selection.anchor)
    }

    @Test
    fun esc_should_RestoreOriginal_When_EditAndOfferTheDiscardedText() {
        val s = state()
        var discarded: DraftText? = null
        s.onDiscarded = { discarded = it }
        s.startEdit(CellAddress(2, 2))
        s.type("99")
        assertEquals(LadderAction.CancelEdit, s.onBack(LadderKey.Esc))
        assertTrue(host.commits.isEmpty())
        assertEquals("3", s.doc!!.cell(CellAddress(2, 2))!!.value)
        assertEquals(DraftText(CellAddress(2, 2), "99"), discarded)
    }

    @Test
    fun menu_should_CloseFirst_And_RestoreInvoker() {
        val s = state()
        s.extendSelectionTo(CellAddress(3, 2))
        var closed = 0
        val unregister = s.registerMenu { closed++; }
        assertEquals(LadderAction.CloseMenu, s.onBack(LadderKey.Back))
        assertEquals(1, closed)
        unregister()
        assertEquals(LadderAction.CollapseRange, s.onBack(LadderKey.Back))
    }

    // ---- formula UX (5.8) ----------------------------------------------------------------------------------------

    private val formulas = "| A | B | C |\n|---|---|---|\n| 5 | =2+3 | x |\n| 1 | 2 | 3 |\n| 4 | 5 | 6 |\n| 7 | 8 | 9 |"

    @Test
    fun formulaCell_should_ShowValueWithFxAndLiteralSourceInTheBar() {
        val s = state(formulas)
        assertTrue(s.display(CellAddress(2, 2)) is CellDisplay.Formula)
        assertEquals("5", s.display(CellAddress(2, 2)).text)
        assertTrue(s.display(CellAddress(2, 1)) is CellDisplay.Plain)
        s.onCellTap(CellAddress(2, 2))
        assertEquals("=2+3", s.barText)
    }

    @Test
    fun cycleDetail_should_NameThePath() {
        val s = state("| A | B | C |\n|---|---|---|\n| 1 | =C2+1 | =B2+1 |")
        s.onCellTap(CellAddress(2, 2))
        assertEquals("#CYCLE!", s.display(CellAddress(2, 2)).text)
        assertEquals("Circular reference B2 -> C2 -> B2", s.errorDetail)
    }

    @Test
    fun errorDetail_should_DismissOnEscWithoutClosingTheSheet() {
        val s = state("| A | B |\n|---|---|\n| 1 | =1/0 |")
        s.onCellTap(CellAddress(2, 2))
        assertNotNull(s.errorDetail)
        assertEquals(LadderAction.CloseMenu, s.onBack(LadderKey.Esc))
        assertNull(s.errorDetail)
        assertEquals(LadderAction.NotHandled, s.onBack(LadderKey.Esc))
        s.onCellTap(CellAddress(2, 1))
        s.onCellTap(CellAddress(2, 2))
        assertNotNull(s.errorDetail, "a new selection shows the reason again")
    }

    @Test
    fun tap_should_InsertReference_When_CaretAfterOperator() {
        val s = state(formulas)
        s.startEdit(CellAddress(3, 3))
        s.type("=SUM(")
        s.onCellTap(CellAddress(2, 1))
        assertEquals("=SUM(A2", s.fieldValue.text)
        assertEquals(GridMode.Edit, s.mode)
        assertEquals(CellAddress(3, 3), s.editingAddress)
        assertTrue(host.commits.isEmpty())
        assertEquals(TableMessages.insertedReference("A2"), s.pointIndicator)
    }

    @Test
    fun tap_should_CommitAndMove_When_CaretNotAfterOperator() {
        val s = state(formulas)
        s.startEdit(CellAddress(3, 3))
        s.type("=B2")
        assertNull(s.pointIndicator)
        s.onCellTap(CellAddress(4, 1))
        assertEquals(1, host.commits.size)
        assertEquals(CellAddress(4, 1), s.selection.anchor)
        assertEquals(GridMode.Navigate, s.mode)
    }

    @Test
    fun extend_should_BuildRangeWithoutDrag_When_ReferenceJustInserted() {
        val s = state(formulas)
        s.startEdit(CellAddress(2, 3))
        s.type("=SUM(")
        s.onCellTap(CellAddress(2, 1))
        repeat(3) { s.extendReference(ExtendChoice.Down) }
        assertEquals("=SUM(A2:A5", s.fieldValue.text)
        s.extendReference(ExtendChoice.Down)
        assertEquals("=SUM(A2:A5", s.fieldValue.text, "clamped at the table end")
    }

    @Test
    fun secondTap_should_ReplaceTheInsertedReference_UntilTyping() {
        val s = state(formulas)
        s.startEdit(CellAddress(2, 3))
        s.type("=SUM(")
        s.onCellTap(CellAddress(3, 1))
        s.onCellTap(CellAddress(4, 2))
        assertEquals("=SUM(B4", s.fieldValue.text)
        s.type(s.fieldValue.text + ",")
        s.onCellTap(CellAddress(5, 2))
        assertEquals("=SUM(B4,B5", s.fieldValue.text)
    }

    @Test
    fun stopInserting_should_MakeNextTapCommitAndMove_And_ClearTheIndicator() {
        val s = state(formulas)
        s.startEdit(CellAddress(2, 3))
        s.type("=SUM(")
        assertEquals(TableMessages.TAP_TO_INSERT_REFERENCE, s.pointIndicator)
        s.stopInserting()
        assertNull(s.pointIndicator)
        s.onCellTap(CellAddress(3, 1))
        assertEquals(CellAddress(3, 1), s.selection.anchor)
        assertEquals(GridMode.Navigate, s.mode)
    }

    @Test
    fun literalEqualsText_should_NotArm_When_NotFormulaPrefix() {
        listOf("=5 apples", "= total", "==").forEach { text ->
            val s = state(formulas)
            s.startEdit(CellAddress(2, 3))
            s.type(text)
            assertNull(s.pointIndicator, text)
            s.onCellTap(CellAddress(3, 1))
            assertEquals(GridMode.Navigate, s.mode, text)
        }
    }

    @Test
    fun caretInsideFormula_should_ArmOnlyAfterOperator() {
        val s = state(formulas)
        s.startEdit(CellAddress(2, 3))
        s.typeWithCaret("=1+2", 3)
        assertEquals(TableMessages.TAP_TO_INSERT_REFERENCE, s.pointIndicator)
        s.typeWithCaret("=1+2", 4)
        assertNull(s.pointIndicator)
    }
}
