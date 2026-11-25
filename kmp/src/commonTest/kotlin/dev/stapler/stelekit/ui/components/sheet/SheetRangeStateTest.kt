// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.GridBounds
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.table.LadderAction
import dev.stapler.stelekit.table.LadderKey
import dev.stapler.stelekit.table.RefusalReason
import dev.stapler.stelekit.table.TableEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stories 5.4a, 5.4c, 5.5 and 5.6 below the UI: range mode, handle drags, stats, refusals and the view-state follow-up of edits. */
class SheetRangeStateTest {
    private class Host : SheetHostPort {
        override val readOnly = false
        val commits = mutableListOf<String>()
        val texts = mutableListOf<String>()
        override fun commit(text: String, label: String, draft: DraftText?): Boolean = true.also { commits += label; texts += text }
        override fun undo() = Unit
        override fun redo() = Unit
        override fun closeSheet() = Unit
        override fun editAsMarkdown() = Unit
    }

    private val table = "| A | B | C |\n|---|---|---|\n| 1 | 2 | x |\n| 3 | 4 | =1/0 |\n| 5 | 6 | y |"
    private val host = Host()
    private fun state() = SheetState(parseSheet(table), host)
    private fun at(row: Int, col: Int) = CellAddress(row, col)

    @Test
    fun enterRangeMode_should_SelectTheCellAndShowHandles_AndNotifyForTheHapticTick() {
        val s = state()
        var ticks = 0
        s.onRangeModeEntered = { ticks++ }
        s.enterRangeMode(at(2, 1))
        assertTrue(s.rangeMode)
        assertEquals(GridSelection.single(at(2, 1), s.bounds), s.selection)
        assertEquals(1, ticks)
    }

    @Test
    fun handleDrag_should_KeepTheFixedCornerAndClampToTheTable() {
        val s = state()
        s.enterRangeMode(at(2, 1))
        s.dragHandle(fixed = at(2, 1), target = at(4, 2))
        assertEquals(GridSelection(at(2, 1), at(4, 2)), s.selection)
        assertTrue(s.rangeMode, "range mode survives a handle drag")
        s.dragHandle(fixed = at(2, 1), target = at(99, 99))
        assertEquals(at(4, 3), s.selection.focus, "clamped to the table bounds")
    }

    @Test
    fun aTapOrKeyMove_should_LeaveRangeMode() {
        val s = state()
        s.enterRangeMode(at(2, 1))
        s.onCellTap(at(3, 2))
        assertFalse(s.rangeMode)
    }

    @Test
    fun back_should_CollapseARangeModeSelectionOfOneCellFirst() {
        val s = state()
        s.enterRangeMode(at(2, 1))
        assertEquals(LadderAction.CollapseRange, s.onBack(LadderKey.Back))
        assertFalse(s.rangeMode)
        assertEquals(LadderAction.NotHandled, s.onBack(LadderKey.Back), "the next press belongs to the session")
    }

    @Test
    fun handleTarget_should_StepOutwardWhenThePointerLeavesTheGrid() {
        val size = Size(100f, 100f)
        val focus = at(3, 2)
        val b = GridBounds(5, 3)
        assertEquals(at(4, 1), handleTarget(at(4, 1), Offset(5f, 5f), size, focus, b))
        assertEquals(at(4, 2), handleTarget(null, Offset(50f, 150f), size, focus, b))
        assertEquals(at(2, 2), handleTarget(null, Offset(50f, -5f), size, focus, b))
        assertEquals(at(3, 3), handleTarget(null, Offset(150f, 50f), size, focus, b))
        assertEquals(at(5, 3), handleTarget(null, Offset(150f, 150f), size, at(5, 3), b), "never past the table")
    }

    @Test
    fun stats_should_SumNumbersByComputedValueAndCountErrors() {
        val s = state()
        val stats = s.statsOf(GridSelection(at(2, 1), at(4, 3)))
        assertEquals(1, stats.errors)
        assertEquals(6, stats.numbers?.count)
        assertEquals("Count 6  Sum 21  Average 3.5  1 error", stats.text)
        assertNull(s.statsOf(GridSelection(at(2, 3))).text, "a single text cell shows nothing")
        assertEquals("1 error", s.statsOf(GridSelection(at(3, 3))).text)
    }

    @Test
    fun attemptEdit_should_ReportAppliedRefusedAndNoChange() {
        val s = state()
        assertIs<EditAttempt.Applied>(s.attemptEdit(TableEdit.SetCell(at(2, 1), CellText("9")), "edit"))
        assertEquals(EditAttempt.NoChange, s.attemptEdit(TableEdit.SetCell(at(2, 1), CellText("9")), "edit"))
        val refused = s.attemptEdit(TableEdit.DeleteRows(1), "delete header")
        assertEquals(EditAttempt.Refused(RefusalReason.HeaderRow, 0), refused)
        assertEquals(1, host.commits.size, "only the applied edit committed")
    }

    @Test
    fun everyStructuralEdit_should_CommitTextThatParsesBackToTheSameDoc() {
        val base = "| Name | Qty | Note |\n|---|---|---|\n| Pear | 5 | a |\n| Apple | 3 | b |\n| Fig | 4 | c |"
        listOf(
            TableEdit.InsertCols(3), TableEdit.InsertCols(4), TableEdit.InsertRows(3), TableEdit.DeleteCols(2), TableEdit.DeleteRows(3),
            TableEdit.MoveRows(3, 1, 2 + 0), TableEdit.MoveCols(1, 1, 3), TableEdit.SetAlignment(3, dev.stapler.stelekit.table.ColumnAlign.RIGHT),
            TableEdit.SortRows(1, ascending = true),
        ).forEach { edit ->
            val h = Host()
            val s = SheetState(parseSheet(base), h)
            s.attemptEdit(edit, "edit")
            val text = h.texts.lastOrNull() ?: return@forEach
            val parsed = dev.stapler.stelekit.table.TableParser.parse(text)
            assertIs<dev.stapler.stelekit.table.TableParseResult.Parsed>(parsed, "$edit produced: $text -> $parsed")
            assertEquals(s.doc, parsed.doc, "$edit")
        }
    }

    @Test
    fun previewEdit_should_CommitNothing() {
        val s = state()
        assertIs<dev.stapler.stelekit.table.TableEditOutcome.Applied>(s.previewEdit(TableEdit.SortRows(1, ascending = false)))
        assertTrue(host.commits.isEmpty())
    }

    @Test
    fun structuralEdits_should_ClearTheSortIndicator_AndColumnEditsShiftWidths() {
        val s = state()
        s.updateView(s.view.withSort(2, ascending = true))
        s.resizeColumn(3, 200f)
        s.attemptEdit(TableEdit.InsertCols(1), "insert column")
        assertEquals(3, s.view.sort?.col)
        assertEquals(200f, s.view.widthOf(4))
        s.attemptEdit(TableEdit.InsertRows(2), "insert row")
        assertNull(s.view.sort)
    }

    @Test
    fun menuOpen_should_RegisterOnTheBackLadderOnlyWhenTheSinkHasAMenu() {
        val s = state()
        s.openMenu(SheetMenuTarget.Column(1))
        assertFalse(s.menuOpen, "the default sink has no menu, so nothing opens")
        val withMenu = SheetState(parseSheet(table), host, object : SheetCommandSink {
            override fun menuFor(target: SheetMenuTarget) = SheetMenuModel("Column", emptyList())
        })
        withMenu.openMenu(SheetMenuTarget.Column(1))
        assertTrue(withMenu.menuOpen)
        assertEquals(LadderAction.CloseMenu, withMenu.onBack(LadderKey.Back))
        assertFalse(withMenu.menuOpen)
    }

    @Test
    fun columnTap_should_SelectTheColumnAndOpenItsMenu() {
        val withMenu = SheetState(parseSheet(table), host, object : SheetCommandSink {
            override fun menuFor(target: SheetMenuTarget) = SheetMenuModel("m", emptyList())
        })
        withMenu.selectColumn(2)
        assertEquals(GridSelection.Kind.Columns, withMenu.selection.kind)
        assertEquals(SheetMenuTarget.Column(2), withMenu.menu)
        withMenu.selectRow(3)
        assertEquals(SheetMenuTarget.Row(3), withMenu.menu)
    }
}
