package dev.stapler.stelekit.table.formula

import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.EditSummary
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.table.TableEditOutcome
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TableEngineTest {
    private val editor = TableEngine.editor()

    private fun parsed(s: String): TableDoc = assertIs<TableParseResult.Parsed>(TableParser.parse(s)).doc
    private fun applied(o: TableEditOutcome): TableEditOutcome.Applied = assertIs(o)
    private fun col(doc: TableDoc, c: Int) = (2..doc.rowCount).map { doc.cell(CellAddress(it, c))!!.value }

    @Test
    fun insertRows_should_RewriteFormulasInSameApplied_When_ProductionEditor() {
        val doc = parsed("| k | v |\n|---|---|\n| a | 1 |\n| b | =SUM(B2:B3) |")
        val out = applied(editor.apply(doc, TableEdit.InsertRows(before = 3)))
        assertEquals(listOf("1", "", "=SUM(B2:B4)"), col(out.doc, 2))
    }

    @Test
    fun deleteRows_should_CountBrokenFormulas_When_ReferencedRowDeleted() {
        val doc = parsed("| k | v |\n|---|---|\n| a | 1 |\n| b | =B2*2 |\n| c | =B2+1 |")
        val out = applied(editor.apply(doc, TableEdit.DeleteRows(at = 2)))
        assertEquals(EditSummary.RowsDeleted(1, 2), out.summary)
        assertEquals(listOf("=#REF!*2", "=#REF!+1"), col(out.doc, 2))
    }

    @Test
    fun deleteRows_should_ShiftReferences_When_RowAboveDeleted() {
        val doc = parsed("| k | v |\n|---|---|\n| a | 1 |\n| b | 2 |\n| c | =B3*2 |")
        val out = applied(editor.apply(doc, TableEdit.DeleteRows(at = 2)))
        assertEquals(listOf("2", "=B2*2"), col(out.doc, 2))
    }

    @Test
    fun sort_should_FollowOwnRowRefs_When_ProductionEditorAndMovedRows() {
        val doc = parsed("| q | p | t |\n|---|---|---|\n| 3 | 1 | =A2*B2 |\n| 1 | 5 | =A3*B3 |")
        val out = applied(editor.apply(doc, TableEdit.SortRows(col = 1, ascending = true)))
        assertEquals(EditSummary.Sorted(column = 1, blankCount = 0, rewrittenFormulaCount = 2, crossRowFormulaCount = 0), out.summary)
        assertEquals(listOf("=A2*B2", "=A3*B3"), col(out.doc, 3))
        assertEquals(listOf("1", "3"), col(out.doc, 1))
    }

    @Test
    fun sort_should_LeaveRangesAndCountCrossRow_When_UnmovedTotalsRow() {
        val doc = parsed("| q | v |\n|---|---|\n| 3 | 10 |\n| 1 | 20 |\n| tot | =SUM(B2:B3) |")
        val out = applied(editor.apply(doc, TableEdit.SortRows(col = 1, ascending = true)))
        assertEquals(listOf("20", "10", "=SUM(B2:B3)"), col(out.doc, 2))
        assertEquals(0, (out.summary as EditSummary.Sorted).rewrittenFormulaCount)
    }

    @Test
    fun sort_should_OrderByComputedValue_When_FormulaColumn() {
        val doc = parsed("| a | b |\n|---|---|\n| 3 | =A2*2 |\n| 1 | =A3*2 |\n| 2 | =A4*2 |")
        val out = applied(editor.apply(doc, TableEdit.SortRows(col = 2, ascending = true)))
        assertEquals(listOf("1", "2", "3"), col(out.doc, 1))
        assertEquals(listOf("=A2*2", "=A3*2", "=A4*2"), col(out.doc, 2))
    }

    @Test
    fun edit_should_ReturnSameDoc_When_NoReferenceChanges() {
        val doc = parsed("| a | b |\n|---|---|\n| 1 | =A2 |")
        val out = applied(editor.apply(doc, TableEdit.InsertRows(before = doc.rowCount + 1)))
        assertEquals(doc.rowCount + 1, out.doc.rowCount)
        assertEquals("=A2", out.doc.cell(CellAddress(2, 2))!!.value)
    }
}
