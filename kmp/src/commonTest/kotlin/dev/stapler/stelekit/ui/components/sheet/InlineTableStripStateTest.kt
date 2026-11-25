// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.EntryProfile
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.table.LadderAction
import dev.stapler.stelekit.table.LadderKey
import dev.stapler.stelekit.table.NoticeType
import dev.stapler.stelekit.table.formula.TableCalc
import dev.stapler.stelekit.ui.components.TableMessages
import dev.stapler.stelekit.ui.components.TableUndoHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stories 4.4 and 4.5 at the state level: selection, strip actions (commit-first), copy, undo, back ladder. */
class InlineTableStripStateTest {
    private val table = "| Item | Qty | Note |\n|---|---|---|\n| Apple | 3 | red |\n| Pear | 5 | green |"
    private val sent = mutableListOf<String>()
    private val copied = mutableListOf<String>()
    private var markdown = 0
    private var maximized = 0
    private val canUndo = MutableStateFlow(true)
    private val canRedo = MutableStateFlow(true)
    private val records = MutableStateFlow(0L)
    private var undoCalls = 0
    private var redoCalls = 0

    private fun state(content: String = table, host: Boolean = true): InlineTableState {
        val s = InlineTableState(content) { sent += it }
        s.hooks.copyToClipboard = { text, done -> copied += text; done(true) }
        s.hooks.editAsMarkdown = { markdown++ }
        s.hooks.openSheet = { maximized++ }
        if (host) {
            s.undoHost = TableUndoHost(
                undo = { undoCalls++ },
                redo = { redoCalls++ },
                canUndo = canUndo,
                canRedo = canRedo,
                recordCount = records,
            )
        }
        return s
    }

    private fun InlineTableState.type(text: String) = onFieldChange(TextFieldValue(text, TextRange(text.length)))

    // ---- selection and copy (4.4a, 4.4b) ----------------------------------------------------------

    @Test
    fun mouseDrag_should_SelectRangeAndCopyTsvWithoutTrailingNewline() {
        val s = state()
        s.dragSelect(CellAddress(2, 1), CellAddress(3, 2))
        assertEquals(4, s.selection?.cellCount)
        s.copySelection()
        assertEquals(listOf("Apple\t3\r\nPear\t5"), copied)
    }

    @Test
    fun selectRowThenCopy_should_CopyOneTsvLine() {
        val s = state()
        s.startEdit(CellAddress(2, 1), EntryProfile.Touch)
        s.perform(StripAction.SelectRow)
        s.perform(StripAction.Copy)
        assertEquals(listOf("Apple\t3\tred"), copied)
    }

    @Test
    fun extendSelectionTo_should_GrowFromAnchor_When_ShiftClick() {
        val s = state()
        s.dragSelect(CellAddress(2, 1), CellAddress(2, 1))
        s.extendSelectionTo(CellAddress(3, 3))
        assertEquals(CellAddress(2, 1), s.selection?.anchor)
        assertEquals(CellAddress(3, 3), s.selection?.focus)
    }

    @Test
    fun extendPicker_should_OfferFiveChoicesThatGrowFromTheAnchor() {
        val s = state()
        s.dragSelect(CellAddress(2, 1), CellAddress(2, 1))
        s.performExtend(ExtendChoice.Right)
        assertEquals(CellAddress(2, 2), s.selection?.focus)
        s.performExtend(ExtendChoice.Down)
        assertEquals(CellAddress(3, 2), s.selection?.focus)
        s.performExtend(ExtendChoice.RowEnd)
        assertEquals(CellAddress(3, 3), s.selection?.focus)
        s.performExtend(ExtendChoice.ColumnEnd)
        assertEquals(CellAddress(3, 3), s.selection?.focus)
        s.performExtend(ExtendChoice.TableEnd)
        assertEquals(CellAddress(3, 3), s.selection?.focus)
        assertEquals(ExtendChoice.entries.size, 5)
        assertEquals(CellAddress(2, 1), s.selection?.anchor)
    }

    @Test
    fun selectColumnAndTable_should_SpanWholeColumnAndTable() {
        val s = state()
        s.dragSelect(CellAddress(2, 2), CellAddress(2, 2))
        s.perform(StripAction.SelectColumn)
        assertEquals(GridSelection.Kind.Columns, s.selection?.kind)
        assertEquals(3, s.selection?.cellCount)
        s.perform(StripAction.SelectTable)
        assertEquals(9, s.selection?.cellCount)
    }

    // ---- commit first (4.4a) ------------------------------------------------------------------------

    @Test
    fun everyStripAction_should_CommitExactlyOnceFirstThenRunInNavigate() {
        val actions = listOf(
            StripAction.SelectRow, StripAction.SelectColumn, StripAction.SelectTable, StripAction.Copy,
            StripAction.Extend, StripAction.Maximize, StripAction.Markdown,
        )
        for (action in actions) {
            sent.clear()
            copied.clear()
            maximized = 0
            markdown = 0
            val s = state()
            s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
            s.type("45")
            s.perform(action)
            assertEquals(1, sent.size, "$action commits once")
            assertTrue(sent.single().contains("| Apple | 45 | red |"), "$action")
            assertEquals(GridMode.Navigate, s.mode, "$action")
            assertNull(s.draft, "$action")
        }
    }

    @Test
    fun copyAfterDraft_should_CopyTheCommittedCell() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        s.type("45")
        s.perform(StripAction.Copy)
        assertEquals(listOf("45"), copied)
    }

    @Test
    fun maximizeAndMarkdown_should_CallHooksOnceAfterTheCommit() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        s.type("45")
        s.perform(StripAction.Maximize)
        assertEquals(1, maximized)
        assertEquals(1, sent.size)
        s.startEdit(CellAddress(3, 2), EntryProfile.Touch)
        s.type("9")
        s.perform(StripAction.Markdown)
        assertEquals(1, markdown)
        assertEquals(2, sent.size)
    }

    @Test
    fun done_should_CommitOnceAndStayOnTheSameCellInNavigate() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        s.type("45")
        s.perform(StripAction.Done)
        assertEquals(1, sent.size)
        assertEquals(GridMode.Navigate, s.mode)
        assertEquals(CellAddress(2, 2), s.selection?.anchor)
    }

    @Test
    fun nextColumn_should_CommitOnceAndEditTheCellToTheRight() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        s.type("9")
        s.perform(StripAction.NextColumn)
        assertEquals(1, sent.size)
        assertEquals(CellAddress(2, 3), s.editingAddress)
    }

    @Test
    fun lineBreak_should_InsertBreakAtCaretAndCommitAsBr() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        s.onFieldChange(TextFieldValue("ab", TextRange(1)))
        s.perform(StripAction.LineBreak)
        s.perform(StripAction.Done)
        assertTrue(sent.single().contains("| Apple | a<br>b | red |"), sent.single())
    }

    // ---- cancel edit and the strip's first slot -------------------------------------------------------

    @Test
    fun cancelEdit_should_PostNothing_When_DraftUnchanged() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        s.perform(StripAction.CancelEdit)
        assertEquals(GridMode.Navigate, s.mode)
        assertTrue(sent.isEmpty())
        assertNull(s.notices.visible)
    }

    @Test
    fun cancelEdit_should_DiscardAndOfferUndoThatRestoresTheDraft_When_DraftDiffers() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        s.type("45")
        s.perform(StripAction.CancelEdit)
        assertTrue(sent.isEmpty())
        val notice = assertNotNull(s.notices.visible)
        assertEquals(TableMessages.DISCARDED_EDIT, notice.text)
        assertEquals(NoticeType.Undo, notice.type)
        s.notices.activate()
        assertEquals(GridMode.Edit, s.mode)
        assertEquals("45", s.draft?.text)
        assertEquals(CellAddress(2, 2), s.editingAddress)
    }

    @Test
    fun close_should_CollapseRangeThenHideStrip() {
        val s = state()
        s.dragSelect(CellAddress(2, 1), CellAddress(3, 2))
        s.perform(StripAction.Close)
        assertEquals(1, s.selection?.cellCount)
        assertTrue(s.stripVisible)
        s.perform(StripAction.Close)
        assertNull(s.selection)
        assertFalse(s.stripVisible)
    }

    // ---- undo and redo (4.4c) -----------------------------------------------------------------------

    @Test
    fun navigateUndo_should_CallHostOnceAndNameTheStep_When_ATableCommitExists() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        s.type("45")
        s.perform(StripAction.Done)
        records.value = 1
        s.onUndoRecordCount(1)
        assertEquals("Undo: edit B2", s.undoLabel)
        s.perform(StripAction.Undo)
        assertEquals(1, undoCalls)
        assertEquals(TableMessages.UNDONE, s.notices.visible?.text)
        assertEquals("Redo: edit B2", s.redoLabel)
        s.perform(StripAction.Redo)
        assertEquals(1, redoCalls)
        assertEquals(TableMessages.REDONE, s.notices.visible?.text)
    }

    @Test
    fun navigateUndo_should_BeDimmedAndNeverCallHost_When_OnlyAForeignEntryExists() {
        val s = state()
        s.dragSelect(CellAddress(2, 1), CellAddress(2, 1))
        assertFalse(s.undoEnabled)
        s.perform(StripAction.Undo)
        assertEquals(0, undoCalls)
        assertEquals(TableMessages.NOTHING_TO_UNDO_TABLE, s.notices.visible?.text)
        s.perform(StripAction.Redo)
        assertEquals(0, redoCalls)
        assertEquals(TableMessages.NOTHING_TO_REDO_TABLE, s.notices.visible?.text)
    }

    @Test
    fun foreignUndoRecord_should_ResetTheDepth() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        s.type("45")
        s.perform(StripAction.Done)
        s.onUndoRecordCount(1)
        assertTrue(s.undoEnabled)
        s.onUndoRecordCount(2)
        assertFalse(s.undoEnabled)
    }

    @Test
    fun stripHidingAndReappearing_should_StartTheUndoDepthAtZero() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        s.type("45")
        s.perform(StripAction.Done)
        s.onUndoRecordCount(1)
        s.perform(StripAction.Close)
        s.perform(StripAction.Close)
        assertNull(s.selection)
        s.dragSelect(CellAddress(2, 2), CellAddress(2, 2))
        assertFalse(s.undoEnabled)
    }

    @Test
    fun editUndo_should_StepTheDraftBackWithoutCommittingOrCallingHost() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        s.type("4")
        s.type("45")
        // Two typed characters within the pause window coalesce into one undo step, so undo drops both.
        s.perform(StripAction.Undo)
        assertEquals("3", s.draft?.text)
        assertTrue(sent.isEmpty())
        assertEquals(0, undoCalls)
    }

    @Test
    fun editUndo_should_ShowCellNoticeAndStayDimmed_When_DraftIsUnchanged() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        assertFalse(s.undoEnabled)
        s.perform(StripAction.Undo)
        assertEquals(TableMessages.NOTHING_TO_UNDO_CELL, s.notices.visible?.text)
        assertEquals(0, undoCalls)
        assertEquals(GridMode.Edit, s.mode)
    }

    // ---- back ladder (4.4e) ---------------------------------------------------------------------------

    @Test
    fun back_should_HideImeAndStayInEdit_When_ImeVisible() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        s.type("45")
        var hidden = 0
        assertEquals(LadderAction.HideIme, s.onBack(LadderKey.Back, imeVisible = true) { hidden++ })
        assertEquals(1, hidden)
        assertEquals(GridMode.Edit, s.mode)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun back_should_CommitOnceAndNavigate_When_EditWithImeHidden() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        s.type("45")
        assertEquals(LadderAction.Done, s.onBack(LadderKey.Back, imeVisible = false) {})
        assertEquals(1, sent.size)
        assertEquals(GridMode.Navigate, s.mode)
    }

    @Test
    fun esc_should_CancelTheEditWithoutCommit() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Hardware)
        s.type("45")
        assertEquals(LadderAction.CancelEdit, s.onBack(LadderKey.Esc, imeVisible = false) {})
        assertTrue(sent.isEmpty())
        assertEquals(GridMode.Navigate, s.mode)
    }

    @Test
    fun back_should_CloseTheOpenPickerFirst_ThenCollapseRange_ThenCloseStrip_ThenNotHandled() {
        val s = state()
        s.dragSelect(CellAddress(2, 1), CellAddress(3, 2))
        s.perform(StripAction.Extend)
        assertEquals(StripMenu.Extend, s.openMenu)
        assertEquals(LadderAction.CloseMenu, s.onBack(LadderKey.Back, false) {})
        assertNull(s.openMenu)
        assertEquals(StripMenu.Extend, s.menuFocusReturn)
        assertEquals(LadderAction.CollapseRange, s.onBack(LadderKey.Back, false) {})
        assertEquals(1, s.selection?.cellCount)
        assertEquals(LadderAction.CloseStrip, s.onBack(LadderKey.Back, false) {})
        assertNull(s.selection)
        assertEquals(LadderAction.NotHandled, s.onBack(LadderKey.Back, false) {})
    }

    @Test
    fun back_should_DismissAWhyNotice_AsTheMenuRung() {
        val s = state()
        s.dragSelect(CellAddress(2, 2), CellAddress(2, 2))
        s.hooks.errorDetail = { _, _ -> "Division by zero" }
        s.perform(StripAction.Why)
        assertEquals(NoticeType.Why, s.notices.visible?.type)
        assertEquals(LadderAction.CloseMenu, s.onBack(LadderKey.Back, false) {})
        assertNull(s.notices.visible)
    }

    // ---- why, policy, conflict ---------------------------------------------------------------------------

    @Test
    fun why_should_ShowDetailAsNoticeThatStaysUntilActedOn() {
        val s = state()
        s.dragSelect(CellAddress(2, 2), CellAddress(2, 2))
        s.hooks.errorDetail = { _, _ -> "Division by zero" }
        s.perform(StripAction.Why)
        val n = assertNotNull(s.notices.visible)
        assertEquals("Division by zero", n.text)
        assertEquals(NoticeType.Why, n.type)
        s.notices.activate() // Edit
        assertEquals(GridMode.Edit, s.mode)
    }

    @Test
    fun stripPolicy_should_ReadEntryProfileFixedAtEditStart() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        assertTrue(s.stripVisible)
        s.cancel()
        s.startEdit(CellAddress(2, 2), EntryProfile.Hardware)
        assertFalse(s.stripVisible)
        s.perform(StripAction.CancelEdit)
        assertTrue(s.stripVisible)
    }

    @Test
    fun stripLayout_should_KeepOneRelativeOrderAcrossModes_AndPinMarkdown() {
        val nav = stripLayout(GridMode.Navigate, EntryProfile.Touch, sheetEnabled = true, hasWhy = false, conflictHeld = false, hardwareReduced = false, compact = false)
        val edit = stripLayout(GridMode.Edit, EntryProfile.Touch, sheetEnabled = true, hasWhy = false, conflictHeld = false, hardwareReduced = false, compact = false)
        assertEquals(StripAction.Close, nav.leading.first())
        assertEquals(StripAction.CancelEdit, edit.leading.first())
        assertEquals(listOf(StripAction.CancelEdit, StripAction.Done), edit.leading)
        val shared = nav.scrolling.filter { it in edit.scrolling }
        assertEquals(shared, edit.scrolling.filter { it in nav.scrolling })
        assertEquals(StripAction.Markdown, nav.trailing)
        assertEquals(StripAction.Markdown, edit.trailing)
        assertEquals(listOf(StripAction.NextColumn, StripAction.LineBreak), edit.scrolling.take(2))
        assertFalse(nav.scrolling.contains(StripAction.NextColumn))
        assertTrue(edit.scrolling.contains(StripAction.Maximize))
        val noSheet = stripLayout(GridMode.Navigate, EntryProfile.Touch, sheetEnabled = false, hasWhy = false, conflictHeld = false, hardwareReduced = false, compact = false)
        assertFalse(noSheet.scrolling.contains(StripAction.Maximize))
        val landscape = stripLayout(GridMode.Edit, EntryProfile.Touch, sheetEnabled = true, hasWhy = false, conflictHeld = false, hardwareReduced = false, compact = true)
        assertEquals(listOf(StripAction.More), landscape.scrolling)
        assertTrue(landscape.overflow.contains(StripAction.Copy))
        assertEquals(StripAction.Markdown, landscape.trailing)
    }

    @Test
    fun conflictHeld_should_ShowReducedStripInAHardwareEditAndKeepTheDraft() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Hardware)
        s.type("45")
        s.onContent("| Item | Qty | Note |\n|---|---|---|\n| Apple | 99 | red |\n| Pear | 5 | green |")
        assertTrue(s.conflictHeld)
        assertTrue(s.stripVisible)
        assertTrue(s.stripPolicy.reduced)
        s.perform(StripAction.Done)
        assertTrue(sent.isEmpty())
        assertEquals("45", s.draft?.text)
        assertEquals(GridMode.Edit, s.mode)
    }

    // ---- review fixes -----------------------------------------------------------------------------------

    private fun bigTable(rows: Int, cols: Int): String {
        val row = { r: Int -> (1..cols).joinToString(" | ", "| ", " |") { "r${r}c$it" } }
        val head = (1..cols).joinToString(" | ", "| ", " |") { "h$it" }
        val delim = (1..cols).joinToString("|", "|", "|") { "---" }
        return (listOf(head, delim) + (2..rows).map(row)).joinToString("\n")
    }

    @Test
    fun commitDraft_should_RebaseTheDraft_SoItIsNotDirtyAfterwards() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Hardware)
        s.type("45")
        assertTrue(s.commitDraft())
        assertFalse(s.draft!!.isDirty)
        assertFalse(s.commitDraft())
        s.cancelEdit()
        assertNull(s.notices.visible)
        assertEquals(1, sent.size)
    }

    @Test
    fun freeze_should_CommitADirtyDraftBeforeDroppingIt() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Hardware)
        s.type("45")
        s.freeze()
        assertEquals(1, sent.size)
        assertTrue(sent.single().contains("45"))
    }

    @Test
    fun maximize_should_IssueTheCommitBeforeOpeningTheSheet() {
        val order = mutableListOf<String>()
        val s = InlineTableState(table) { order += "commit" }
        s.hooks.openSheet = { order += "open" }
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        s.type("45")
        s.maximize()
        assertEquals(listOf("commit", "open"), order)
    }

    @Test
    fun undoEcho_should_KeepADirtyDraftAndHoldTheCommit() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Hardware)
        s.type("45")
        s.perform(StripAction.Done)
        val committed = sent.single()
        s.perform(StripAction.Undo)
        s.startEdit(CellAddress(2, 2), EntryProfile.Hardware)
        s.type("x")
        s.onContent(table)
        assertEquals("x", s.draft?.text)
        assertEquals(GridMode.Edit, s.mode)
        assertTrue(s.conflictHeld)
        assertEquals(1, sent.size)
        assertTrue(committed != table)
    }

    @Test
    fun markDone_should_RebuildFromTheHeldForeignContent_NotFromOwnCommit() {
        val foreign = "| Item | Qty | Note |\n|---|---|---|\n| Apple | 3 | red |\n| Pear | 99 | green |"
        lateinit var s: InlineTableState
        s = InlineTableState(table) { s.onContent(foreign) }
        s.startEdit(CellAddress(2, 1), EntryProfile.Hardware)
        s.type("Fig")
        s.commitDraft()
        assertEquals("99", s.doc!!.cell(CellAddress(3, 2))?.value)
    }

    @Test
    fun rebuildFromExternalEdit_should_DropHistory_EvenWhenOwnRecordAccountingDrifted() {
        // The manager recorded nothing for our commit (block missing or unchanged), so one own record is "pending" forever.
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Hardware)
        s.type("45")
        s.perform(StripAction.Done)
        val external = "| Item | Qty | Note |\n|---|---|---|\n| Apple | 77 | red |\n| Pear | 5 | green |"
        s.onContent(external)
        records.value = 1
        s.onUndoRecordCount(1)
        assertFalse(s.undoEnabled)
        assertEquals("77", s.doc!!.cell(CellAddress(2, 2))?.value)
    }

    @Test
    fun navigation_should_StayInsideTheComposedRowWindow() {
        val rows = 3_000
        val s = InlineTableState(bigTable(rows, 1), cellWindow = 1_000) { sent += it }
        s.startEdit(CellAddress(1_000, 1), EntryProfile.Hardware)
        s.handleKey(CellEditorKey.Enter(back = false))
        assertTrue((s.editingAddress?.row ?: 0) <= 1_000)
        s.startEdit(CellAddress(2_500, 1), EntryProfile.Hardware)
        assertTrue((s.editingAddress?.row ?: 0) <= 1_000)
        s.cancel()
        s.dragSelect(CellAddress(2, 1), CellAddress(2, 1))
        s.perform(StripAction.SelectTable)
        assertEquals(1_000, s.selection?.cellCount)
    }

    @Test
    fun displayData_should_ReadOnlyTheShownRows() {
        val d = (dev.stapler.stelekit.table.TableParser.parse(bigTable(50, 2)) as dev.stapler.stelekit.table.TableParseResult.Parsed).doc
        assertEquals(2, d.displayData(shownRows = 3).rows.size)
        assertEquals(49, d.displayData().rows.size)
    }

    @Test
    fun selectAllCopy_should_BuildOneTableCalc_ForAHundredByHundredTable() {
        var built = 0
        val s = InlineTableState(bigTable(100, 100)) { sent += it }
        s.hooks.bindCellDisplay(CalcMemo { built++; TableCalc.orNull(it) })
        s.hooks.copyToClipboard = { text, done -> copied += text; done(true) }
        s.dragSelect(CellAddress(1, 1), CellAddress(1, 1))
        s.perform(StripAction.SelectTable)
        s.copySelection()
        assertEquals(1, copied.size)
        assertTrue(built <= 1, "built $built calcs")
    }

    @Test
    fun copy_should_PostCopiedOnlyOnSuccess_AndReportFailure() {
        val s = state()
        var finish: ((Boolean) -> Unit)? = null
        s.hooks.copyToClipboard = { _, done -> finish = done }
        s.dragSelect(CellAddress(2, 1), CellAddress(2, 1))
        s.copySelection()
        assertNull(s.notices.visible)
        finish!!(false)
        assertEquals(TableMessages.COPY_FAILED, s.notices.visible?.text)
        s.notices.dismiss()
        s.copySelection()
        finish!!(true)
        assertEquals(TableMessages.copied(1, 1), s.notices.visible?.text)
    }

    @Test
    fun copy_should_RefuseAnOversizeSelection() {
        val s = InlineTableState(bigTable(200, 150)) { sent += it }
        var called = false
        s.hooks.copyToClipboard = { _, _ -> called = true }
        s.dragSelect(CellAddress(1, 1), CellAddress(1, 1))
        s.perform(StripAction.SelectTable)
        s.copySelection()
        assertFalse(called)
        assertEquals(TableMessages.COPY_TOO_LARGE, s.notices.visible?.text)
    }

    @Test
    fun activatingAnotherTable_should_ResetThePreviousOneToNavigateWithoutSelection() {
        val activation = TableActivation()
        val a = state()
        val b = state()
        a.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        a.type("45")
        activation.activate(a)
        b.dragSelect(CellAddress(2, 1), CellAddress(2, 1))
        activation.activate(b)
        assertNull(a.selection)
        assertEquals(GridMode.Navigate, a.mode)
        assertEquals(1, sent.size)
        assertFalse(a.stripVisible)
        assertTrue(b.stripVisible)
    }

    private val external = "| Item | Qty | Note |\n|---|---|---|\n| Apple | 77 | red |\n| Pear | 5 | green |"

    @Test
    fun commitAndClear_should_ReleaseAHeldConflict_ShowExternalContentAndOfferUndo() {
        val s = state()
        s.startEdit(CellAddress(2, 1), EntryProfile.Hardware)
        s.type("Fig")
        s.onContent(external)
        assertTrue(s.conflictHeld)
        s.commitAndClear()
        assertFalse(s.conflictHeld)
        assertEquals("77", s.doc!!.cell(CellAddress(2, 2))?.value)
        assertEquals(TableMessages.DISCARDED_EDIT, s.notices.visible?.text)
        s.startEdit(CellAddress(3, 1), EntryProfile.Hardware)
        s.type("Plum")
        assertTrue(s.commitDraft())
        assertEquals(1, sent.size)
    }

    @Test
    fun activatingAnotherTable_should_ReleaseAConflictHeldInThePreviousOne() {
        val activation = TableActivation()
        val a = state()
        val b = state()
        a.startEdit(CellAddress(2, 1), EntryProfile.Touch)
        a.type("Fig")
        a.onContent(external)
        activation.activate(a)
        b.dragSelect(CellAddress(2, 1), CellAddress(2, 1))
        activation.activate(b)
        assertFalse(a.conflictHeld)
        assertNull(a.selection)
        assertEquals("77", a.doc!!.cell(CellAddress(2, 2))?.value)
    }

    @Test
    fun selectAllCopy_should_SayItCopiedTheVisibleRows_WhenTheWindowTruncates() {
        val s = InlineTableState(bigTable(50, 1), cellWindow = 10) { sent += it }
        val notes = mutableListOf<String>()
        s.hooks.copyToClipboard = { _, done -> done(true) }
        s.dragSelect(CellAddress(1, 1), CellAddress(1, 1))
        s.perform(StripAction.SelectTable)
        s.copySelection()
        notes += s.notices.visible!!.text
        assertEquals(TableMessages.copiedVisible(10, 1), notes.single())
    }
}
