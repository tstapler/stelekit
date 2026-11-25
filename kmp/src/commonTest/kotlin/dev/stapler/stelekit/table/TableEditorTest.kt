package dev.stapler.stelekit.table

import dev.stapler.stelekit.table.TableEdit.ClearRange
import dev.stapler.stelekit.table.TableEdit.DeleteCols
import dev.stapler.stelekit.table.TableEdit.DeleteRows
import dev.stapler.stelekit.table.TableEdit.InsertCols
import dev.stapler.stelekit.table.TableEdit.InsertRows
import dev.stapler.stelekit.table.TableEdit.MoveCols
import dev.stapler.stelekit.table.TableEdit.MoveRows
import dev.stapler.stelekit.table.TableEdit.PasteRange
import dev.stapler.stelekit.table.TableEdit.SetAlignment
import dev.stapler.stelekit.table.TableEdit.SetCell
import dev.stapler.stelekit.table.TableEdit.SortMove
import dev.stapler.stelekit.table.TableEdit.SortRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TableEditorTest {
    private val editor = TableEditor()

    private fun parsed(s: String): TableDoc = assertIs<TableParseResult.Parsed>(TableParser.parse(s)).doc
    private fun applied(o: TableEditOutcome): TableEditOutcome.Applied = assertIs(o)
    private fun ser(o: TableEditOutcome): String = TableSerializer.serialize(applied(o).doc)
    private fun col(doc: TableDoc, c: Int): List<String> = (2..doc.rowCount).map { doc.cell(CellAddress(it, c))!!.value }
    private fun table(header: String, rows: List<String>): String =
        (listOf(header, "|---|---|") + rows).joinToString("\n")

    @Test
    fun deleteCols_should_RemoveCellAtIndexInEveryRow_When_MiddleColumnIncludingRagged() {
        val doc = parsed("| a | b | c |\n|:--|---|--:|\n| 1 | 2 | 3 |\n| 4 | 5 |\n| 7 | 8 | 9 | 10 |")
        val out = applied(editor.apply(doc, DeleteCols(2)))
        assertEquals(EditSummary.ColsDeleted(1, 0), out.summary)
        assertEquals("| a | c |\n| :-- | --: |\n| 1 | 3 |\n| 4 |\n| 7 | 9 | 10 |", TableSerializer.serialize(out.doc))
    }

    @Test
    fun deleteRows_should_BeRefused_When_HeaderRow() {
        val doc = parsed("| a | b |\n|---|---|")
        assertEquals(TableEditOutcome.Refused(RefusalReason.HeaderRow), editor.apply(doc, DeleteRows(1)))
    }

    @Test
    fun deleteCols_should_BeRefused_When_LastColumn() {
        val doc = parsed("| a |\n|---|\n| 1 |")
        assertEquals(TableEditOutcome.Refused(RefusalReason.LastColumn), editor.apply(doc, DeleteCols(1)))
    }

    @Test
    fun insertRows_should_AddEmptyBodyRow_When_HeaderOnlyTable() {
        val doc = parsed("| a | b |\n|---|---|")
        val out = applied(editor.apply(doc, InsertRows(before = 2)))
        assertEquals(2, out.doc.rowCount)
        assertEquals(listOf("", ""), out.doc.row(2).cells.map { it.value })
        assertEquals("| a | b |\n|---|---|\n| | |", TableSerializer.serialize(out.doc))
    }

    @Test
    fun insertRows_should_RefuseHeaderRow_When_BeforeIsOne() {
        assertEquals(
            TableEditOutcome.Refused(RefusalReason.HeaderRow),
            editor.apply(parsed("| a |\n|---|"), InsertRows(before = 1)),
        )
    }

    @Test
    fun insertCols_should_AddCellPerRowAndDelimiterMarker_When_BeforeSecond() {
        val doc = parsed("| a | b |\n|:--|--:|\n| 1 | 2 |")
        assertEquals("| a | | b |\n| :-- | --- | --: |\n| 1 | | 2 |", ser(editor.apply(doc, InsertCols(before = 2))))
    }

    @Test
    fun moveRows_should_SwapAndReturnPermutation_When_Row3Up() {
        val doc = parsed("| a |\n|---|\n| 1 |\n| 2 |\n| 3 |")
        val out = applied(editor.apply(doc, MoveRows(from = 3, count = 1, before = 2)))
        assertEquals(EditSummary.Moved(listOf(1, 3, 2, 4), 0), out.summary)
        assertEquals(listOf("2", "1", "3"), col(out.doc, 1))
        assertSame(doc.row(3), out.doc.row(2), "moved rows keep their RowLine (verbatim source)")
    }

    @Test
    fun moveCols_should_ReorderCellsAndAlignments_When_ColumnThreeMovesFirst() {
        val doc = parsed("| a | b | c |\n|:--|---|--:|\n| 1 | 2 | 3 |")
        assertEquals("| c | a | b |\n| --: | :-- | --- |\n| 3 | 1 | 2 |", ser(editor.apply(doc, MoveCols(from = 3, count = 1, before = 1))))
    }

    @Test
    fun setCell_should_EscapePipeAndNewline_When_TextContainsPipeOrLineBreak() {
        val doc = parsed("| a | b |\n|---|---|\n| 1 | 2 |")
        val out = applied(editor.apply(doc, SetCell(CellAddress(2, 1), CellText("a|b\nc"))))
        val text = TableSerializer.serialize(out.doc)
        assertTrue(text.contains("a\\|b<br>c"), text)
        assertEquals("a|b\nc", parsed(text).cell(CellAddress(2, 1))!!.value)
    }

    @Test
    fun setCell_should_BeNoChange_When_SameText() {
        val doc = parsed("| a | b |\n|---|---|\n| 1 | 2 |")
        val o = applied(editor.apply(doc, SetCell(CellAddress(2, 1), CellText("1"))))
        assertEquals(EditSummary.NoChange, o.summary)
        assertSame(doc, o.doc)
    }

    @Test
    fun clearRange_should_EmptyCellsInRange_When_TwoByOne() {
        val doc = parsed("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |")
        val out = applied(editor.apply(doc, ClearRange(CellRange.of(CellAddress(2, 2), CellAddress(3, 2)))))
        assertEquals(EditSummary.CellsChanged(2), out.summary)
        assertEquals(listOf("1", "3"), col(out.doc, 1))
        assertEquals(listOf("", ""), col(out.doc, 2))
    }

    @Test
    fun setAlignment_should_RewriteOnlyThatDelimiterCell_When_AlignRightOnColumnC() {
        val doc = parsed("| a | b | c |\n|---|---|---|\n| 1 | 2 | 3 |")
        assertEquals("| a | b | c |\n| --- | --- | --: |\n| 1 | 2 | 3 |", ser(editor.apply(doc, SetAlignment(3, ColumnAlign.RIGHT))))
    }

    @Test
    fun setAlignment_should_BeNoChange_When_SameAlignment() {
        val s = "| a | b |\n|---|--:|\n| 1 | 2 |"
        val o = applied(editor.apply(parsed(s), SetAlignment(2, ColumnAlign.RIGHT)))
        assertEquals(EditSummary.NoChange, o.summary)
        assertEquals(s, TableSerializer.serialize(o.doc))
    }

    @Test
    fun delimiterRow_should_RegenerateOnlyChangedCells_When_AlignmentChanged() {
        val doc = parsed("|a|b|c|\n|:-|-:|-|\n|1|2|3|")
        assertEquals("|a|b|c|\n|:-|-:|:-:|\n|1|2|3|", ser(editor.apply(doc, SetAlignment(3, ColumnAlign.CENTER))))
    }

    @Test
    fun editor_should_NeverTouchUntouchedRowLines_When_CellSetElsewhere() {
        val doc = parsed("| a | b |\n|---|---|\n|  1   |2|\n| 3 | 4 |")
        val out = applied(editor.apply(doc, SetCell(CellAddress(3, 2), CellText("9")))).doc
        assertSame(doc.row(2), out.row(2))
        assertSame(doc.header, out.header)
    }

    @Test
    fun structuralOps_should_CallReferenceTransformForEveryFormulaCell_When_Supplied() {
        val seen = ArrayList<Pair<String, TableEdit>>()
        val transform = FormulaReferenceTransform { cell, edit ->
            seen.add(cell.value to edit)
            if (cell.value == "=B2") CellText("=B3") else null
        }
        val doc = parsed("| a | b |\n|---|---|\n| =B2 | 2 |\n| =#REF! | =B3 |\n| x | y |")
        val edit = InsertRows(before = 2)
        val out = applied(TableEditor(transform).apply(doc, edit))
        assertEquals(listOf("=B2", "=#REF!", "=B3"), seen.map { it.first })
        assertTrue(seen.all { it.second == edit })
        assertEquals("=B3", out.doc.cell(CellAddress(3, 1))!!.value)
        assertSame(doc.row(3), out.doc.row(4), "rows whose formulas the transform left alone stay untouched")
    }

    @Test
    fun deleteRows_should_CountFormulasBroken_When_TransformIntroducesRefError() {
        val transform = FormulaReferenceTransform { cell, _ -> if (cell.value == "=B2") CellText("=#REF!") else null }
        val doc = parsed("| a | b |\n|---|---|\n| 1 | 2 |\n| =B2 | 3 |")
        val out = applied(TableEditor(transform).apply(doc, DeleteRows(at = 2)))
        assertEquals(EditSummary.RowsDeleted(1, 1), out.summary)
    }

    @Test
    fun editor_should_RefuseSortMove_When_IssuedByCaller() {
        val doc = parsed("| a |\n|---|\n| 1 |")
        assertEquals(TableEditOutcome.Refused(RefusalReason.OutOfRange), editor.apply(doc, SortMove(2, 3)))
    }

    // --- sort ---

    @Test
    fun sortRows_should_BeStableBodyOnlyAscending_When_MixedNumbersTextBlank() {
        // rows: r2=10, r3=abc, r4=2(first), r5=blank, r6=2(second); distinguish equal keys by column a
        val doc = parsed(table("| k | v |", listOf("| a | 10 |", "| b | abc |", "| first | 2 |", "| d | |", "| second | 2 |")))
        val asc = applied(editor.apply(doc, SortRows(col = 2, ascending = true))).doc
        assertEquals(listOf("first", "second", "a", "b", "d"), col(asc, 1))
        assertSame(doc.header, asc.header)
        val desc = applied(editor.apply(doc, SortRows(col = 2, ascending = false))).doc
        assertEquals(listOf("b", "a", "first", "second", "d"), col(desc, 1))
    }

    @Test
    fun sortRows_should_ReadThousandsSeparatedNumbersAsNumbers_When_FormulasDo() {
        val doc = parsed(table("| k | v |", listOf("| a | 2000 |", "| b | 1,234 |", "| c | 10 |")))
        val out = applied(editor.apply(doc, SortRows(col = 2, ascending = true))).doc
        assertEquals(listOf("c", "b", "a"), col(out, 1))
    }

    @Test
    fun sortRows_should_ExcludeTrailingTotalsRow_When_NoSelection() {
        for (total in listOf("=SUM(B2:B5)", "=B2+B3+B4+B5", "=B2*2+B3", "=AVERAGE(B2:B5)", "=B2")) {
            val doc = parsed(table("| k | v |", listOf("| a | 3 |", "| b | 1 |", "| c | 2 |", "| d | 0 |", "| Total | $total |")))
            val out = applied(editor.apply(doc, SortRows(col = 2, ascending = true))).doc
            assertEquals(listOf("d", "b", "c", "a", "Total"), col(out, 1), total)
            assertEquals(total, out.cell(CellAddress(6, 2))!!.value)
        }
    }

    @Test
    fun sortRows_should_ExtendTotalsRunUpward_When_ChainedTrailingRows() {
        val doc = parsed(table("| k | v |", listOf("| a | 3 |", "| b | 1 |", "| c | =B2+B3 |", "| d | =B4*2 |")))
        val out = applied(editor.apply(doc, SortRows(col = 2, ascending = true))).doc
        assertEquals(listOf("b", "a", "c", "d"), col(out, 1))
        assertEquals(listOf("1", "3", "=B2+B3", "=B4*2"), col(out, 2))
    }

    @Test
    fun sortRows_should_TreatRowLocalFormulaAsOrdinary_When_OnlyOwnRowReferenced() {
        val doc = parsed("| q | p | t |\n|---|---|---|\n| 3 | 1 | =A2*B2 |\n| 1 | 5 | =A3*B3 |")
        val out = applied(editor.apply(doc, SortRows(col = 1, ascending = true)))
        assertEquals(EditSummary.Sorted(column = 1, blankCount = 0, rewrittenFormulaCount = 0, crossRowFormulaCount = 0), out.summary)
        // no transform supplied: formula text moves with its row, unrewritten
        assertEquals(listOf("=A3*B3", "=A2*B2"), col(out.doc, 3))
    }

    @Test
    fun sortRows_should_RewriteOwnRowRefsThroughTransform_When_RowsMove() {
        val seen = ArrayList<TableEdit>()
        val transform = FormulaReferenceTransform { cell, edit ->
            seen.add(edit)
            val m = edit as? SortMove ?: return@FormulaReferenceTransform null
            CellText(cell.value.replace("A${m.fromRow}", "A${m.toRow}"))
        }
        val doc = parsed("| q | t |\n|---|---|\n| 3 | =A2 |\n| 1 | =A3 |")
        val out = applied(TableEditor(transform).apply(doc, SortRows(col = 1, ascending = true)))
        assertEquals(listOf(SortMove(3, 2), SortMove(2, 3)).toSet(), seen.toSet())
        assertEquals(listOf("=A2", "=A3"), col(out.doc, 2))
        assertEquals(2, (out.summary as EditSummary.Sorted).rewrittenFormulaCount)
    }

    @Test
    fun sortRows_should_SortOnlySelectedRows_When_RangeRows3To4() {
        val doc = parsed(table("| k | v |", listOf("| a | 9 |", "| b | 3 |", "| c | 1 |", "| d | 0 |")))
        val out = applied(editor.apply(doc, SortRows(col = 2, ascending = true, rows = RowRange(3, 4)))).doc
        assertEquals(listOf("a", "c", "b", "d"), col(out, 1))
    }

    @Test
    fun sortRows_should_NotRewriteRanges_When_RangeTouchesOwnRowAndOthers() {
        val doc = parsed("| a | b | c | d |\n|---|---|---|---|\n| 5 | 1 | 1 | =SUM(A1:C2) |\n| 3 | 1 | 1 | 7 |\n| 1 | 1 | 1 | 8 |")
        val out = applied(editor.apply(doc, SortRows(col = 1, ascending = true))).doc
        assertEquals("=SUM(A1:C2)", out.cell(CellAddress(4, 4))!!.value)
    }

    @Test
    fun sortRows_should_RefuseSortWouldSelfReference_When_MovedRangeContainsOwnRow() {
        val doc = parsed("| k | v |\n|---|---|\n| a | 5 |\n| b | 7 |\n| c | =SUM(B2:B3) |")
        // descending by k moves the formula row from 4 to 2, inside its own literal range B2:B3
        val o = editor.apply(doc, SortRows(col = 1, ascending = false, rows = RowRange(2, 4)))
        assertEquals(TableEditOutcome.Refused(RefusalReason.SortWouldSelfReference, 1), o)
    }

    @Test
    fun sortRows_should_RefuseSortWouldChangeValues_When_MovedFormulaHasSingleCellCrossRowRefOutsideTotals() {
        val doc = parsed(table("| k | v |", listOf("| a | 3 |", "| b | 1 |", "| c | 2 |", "| d | =B3*2 |", "| e | 0 |")))
        assertEquals(
            TableEditOutcome.Refused(RefusalReason.SortWouldChangeValues, 1),
            editor.apply(doc, SortRows(col = 1, ascending = false)),
        )
        // an unmoved totals row with a cross-row single-cell ref applies and is counted
        val totals = parsed(table("| k | v |", listOf("| a | 3 |", "| b | 1 |", "| T | =B2 |")))
        val out = applied(editor.apply(totals, SortRows(col = 2, ascending = true)))
        assertEquals(1, (out.summary as EditSummary.Sorted).crossRowFormulaCount)
        assertEquals(listOf("b", "a", "T"), col(out.doc, 1))
    }

    @Test
    fun sortRows_should_CountBlanksInSummary_When_BlankCellsSortedLast() {
        val doc = parsed(table("| k | v |", listOf("| a | |", "| b | 2 |", "| c | |", "| d | 1 |")))
        val out = applied(editor.apply(doc, SortRows(col = 2, ascending = true)))
        assertEquals(2, (out.summary as EditSummary.Sorted).blankCount)
        assertEquals(listOf("d", "b", "a", "c"), col(out.doc, 1))
    }

    @Test
    fun sortRows_should_BeNoChange_When_AlreadySorted() {
        val doc = parsed(table("| k | v |", listOf("| a | 1 |", "| b | 2 |")))
        val o = applied(editor.apply(doc, SortRows(col = 2, ascending = true)))
        assertEquals(EditSummary.NoChange, o.summary)
        assertSame(doc, o.doc)
    }

    @Test
    fun sortRows_should_UseInjectedKeys_When_SortKeyOfSupplied() {
        val doc = parsed(table("| k | v |", listOf("| a | x |", "| b | y |")))
        val reverse = SortKeyOf { _, addr -> SortKey.Num(-addr.row.toDouble()) }
        val out = applied(TableEditor(sortKeyOf = reverse).apply(doc, SortRows(col = 2, ascending = true))).doc
        assertEquals(listOf("b", "a"), col(out, 1))
    }

    // --- paste ---

    private fun grid(rows: Int, cols: Int, cell: (Int, Int) -> String = { r, c -> "r${r}c$c" }) =
        List(rows) { r -> List(cols) { c -> cell(r, c) } }

    @Test
    fun pasteRange_should_GrowTable_When_8x5IntoB3Of3x3() {
        val doc = parsed("| a | b | c |\n|---|---|---|\n| 1 | 2 | 3 |\n| 4 | 5 | 6 |")
        val out = applied(editor.apply(doc, PasteRange(CellAddress(3, 2), grid(8, 5))))
        assertEquals(EditSummary.Pasted(rows = 8, cols = 5, addedRows = 7, addedCols = 3, transformedCells = 0), out.summary)
        assertEquals(10, out.doc.rowCount)
        assertEquals(6, out.doc.columnCount)
        assertTrue(out.doc.body.all { it.cells.size == 6 })
        assertEquals("r0c0", out.doc.cell(CellAddress(3, 2))!!.value)
        assertEquals(6, out.doc.delimiter.aligns.size)
    }

    @Test
    fun pasteRange_should_FillSelection_When_SingleCellClipboardOnB2toB4() {
        val doc = parsed("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n| 5 | 6 |")
        val sel = CellRange.of(CellAddress(2, 2), CellAddress(4, 2))
        val out = applied(editor.apply(doc, PasteRange(CellAddress(2, 2), listOf(listOf("0")), sel)))
        assertEquals(listOf("0", "0", "0"), col(out.doc, 2))
        assertEquals(listOf("1", "3", "5"), col(out.doc, 1))
    }

    @Test
    fun pasteRange_should_RefusePasteTooLarge_When_30000Cells() {
        val doc = parsed("| a |\n|---|\n| 1 |")
        assertEquals(
            TableEditOutcome.Refused(RefusalReason.PasteTooLarge),
            editor.apply(doc, PasteRange(CellAddress(2, 1), grid(300, 100) { _, _ -> "x" })),
        )
    }

    @Test
    fun pasteRange_should_ApplyAt10000AndRefuseAt10001_When_PasteCellCapBoundary() {
        assertEquals(10_000, PASTE_CELL_CAP)
        val doc = parsed("| a |\n|---|\n| 1 |")
        val ok = editor.apply(doc, PasteRange(CellAddress(1, 1), grid(100, 100) { _, _ -> "x" }))
        assertEquals(100, (applied(ok).summary as EditSummary.Pasted).rows)
        val over = listOf(List(10_001) { "x" })
        assertEquals(TableEditOutcome.Refused(RefusalReason.PasteTooLarge), editor.apply(doc, PasteRange(CellAddress(1, 1), over)))
    }

    @Test
    fun pasteRange_should_TransformPipeAndNewline_When_PastedCellsContainThem() {
        val doc = parsed("| a | b |\n|---|---|\n| 1 | 2 |")
        val out = applied(editor.apply(doc, PasteRange(CellAddress(2, 1), listOf(listOf("p|q", "l1\r\nl2")))))
        assertEquals(2, (out.summary as EditSummary.Pasted).transformedCells)
        val text = TableSerializer.serialize(out.doc)
        assertTrue(text.contains("p\\|q") && text.contains("l1<br>l2"), text)
        assertEquals("l1\nl2", parsed(text).cell(CellAddress(2, 2))!!.value)
    }

    @Test
    fun pasteRange_should_KeepFormulaTextVerbatim_When_Pasted() {
        val doc = parsed("| a | b |\n|---|---|\n| 1 | 2 |")
        val out = applied(TableEditor(FormulaReferenceTransform { _, _ -> CellText("=NEVER") }).apply(doc, PasteRange(CellAddress(2, 1), listOf(listOf("=B9")))))
        assertEquals("=B9", out.doc.cell(CellAddress(2, 1))!!.value)
    }

    @Test
    fun pasteRange_should_RefuseOutOfRange_When_OriginOutsideTable() {
        val doc = parsed("| a |\n|---|\n| 1 |")
        assertEquals(TableEditOutcome.Refused(RefusalReason.OutOfRange), editor.apply(doc, PasteRange(CellAddress(9, 1), grid(1, 1))))
    }
}
