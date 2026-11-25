package dev.stapler.stelekit.table.formula

import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.TableEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RefRewriterTest {
    private fun rw(text: String, edit: TableEdit) = RefRewriter.rewrite(text, edit)

    @Test
    fun insertRows_should_ShiftReferencesAndGrowSpanningRange_When_RowInsertedAbove() {
        assertEquals("=SUM(B2:B6)", rw("=SUM(B2:B5)", TableEdit.InsertRows(before = 3)))
        assertEquals("=B4*2", rw("=B3*2", TableEdit.InsertRows(before = 3)))
        assertNull(rw("=B2*2", TableEdit.InsertRows(before = 3)))
        assertEquals("=\$B\$4+C2", rw("=\$B\$3+C2", TableEdit.InsertRows(before = 3)))
    }

    @Test
    fun insertCols_should_UseBase26_When_ColumnCrossesZ() {
        assertEquals("=AA1", rw("=Z1", TableEdit.InsertCols(before = 1)))
        assertEquals("=AB2", rw("=AA2", TableEdit.InsertCols(before = 5)))
    }

    @Test
    fun deleteRows_should_PoisonSingleRefAndShrinkRange_When_RowDeleted() {
        assertEquals("=#REF!*2", rw("=B3*2", TableEdit.DeleteRows(at = 3)))
        assertEquals("=SUM(B2:B4)", rw("=SUM(B2:B5)", TableEdit.DeleteRows(at = 5)))
        assertEquals("=SUM(B2:B3)", rw("=SUM(B2:B5)", TableEdit.DeleteRows(at = 3, count = 2)))
        assertEquals("=B2", rw("=B3", TableEdit.DeleteRows(at = 2)))
        assertEquals("=SUM(#REF!)", rw("=SUM(B3:B4)", TableEdit.DeleteRows(at = 3, count = 2)))
    }

    @Test
    fun deleteCols_should_ShrinkOrPoisonRanges_When_BColumnDeleted() {
        assertEquals("=SUM(B2:B5)", rw("=SUM(B2:C5)", TableEdit.DeleteCols(at = 2)))
        assertEquals("=SUM(#REF!)", rw("=SUM(B2:B5)", TableEdit.DeleteCols(at = 2)))
        assertEquals("=SUM(A2:A5)", rw("=SUM(A2:B5)", TableEdit.DeleteCols(at = 2)))
        assertEquals("=SUM(B2:B4)", rw("=SUM(B2:B5)", TableEdit.DeleteRows(at = 5)))
    }

    @Test
    fun move_should_FollowDataAndNormalizeRange_When_RowsOrColsMoved() {
        // rows 3..4 move before row 6: 3->4, 4->5, 5->3
        val m = TableEdit.MoveRows(from = 3, count = 2, before = 6)
        assertEquals("=B4", rw("=B3", m))
        assertEquals("=B3", rw("=B5", m))
        assertEquals("=SUM(B2:B3)", rw("=SUM(B2:B5)", m))
        // row 5 moves before row 2: 5->2, 2..4 shift down
        val up = TableEdit.MoveRows(from = 5, count = 1, before = 2)
        assertEquals("=B2", rw("=B5", up))
        assertEquals("=B3", rw("=B2", up))
        assertEquals("=SUM(B2:B3)", rw("=SUM(B5:B2)", up))
        assertNull(rw("=B2", TableEdit.MoveRows(from = 2, count = 1, before = 3)))
        assertEquals("=B1+C1", rw("=A1+C1", TableEdit.MoveCols(from = 1, count = 1, before = 3)))
    }

    @Test
    fun sort_should_FollowOwnRowSingleRefsOnly_When_SortMove() {
        val mv = TableEdit.SortMove(fromRow = 2, toRow = 3)
        assertEquals("=B3*2", rw("=B2*2", mv))
        assertNull(rw("=B5*2+SUM(B2:B3)", mv))
        assertEquals("=B3*C3+SUM(B2:B3)", rw("=B2*C2+SUM(B2:B3)", mv))
    }

    @Test
    fun rewrite_should_NotTouchStrings_When_RefLookalikeInQuotes() {
        assertEquals("=IF(A1>0,\"B2\",C4)", rw("=IF(A1>0,\"B2\",C3)", TableEdit.InsertRows(before = 2)))
    }

    @Test
    fun rewrite_should_ReturnNullAndKeepBytes_When_NoReferenceMoves() {
        val insertBelow = TableEdit.InsertRows(before = 99)
        listOf("=sum( b2:b5 )", "=007+1", "=1.50*2", "plain", "=#nope", "=5 +").forEach { assertNull(rw(it, insertBelow), it) }
        assertNull(rw("=B2", TableEdit.SetCell(CellAddress(1, 1), CellText("x"))))
    }

    @Test
    fun rewrite_should_KeepSpacingAndCase_When_OnlyReferenceSpansChange() {
        assertEquals("=sum( b3:b6 )", rw("=sum( b2:b5 )", TableEdit.InsertRows(before = 2)))
        assertEquals("=SUM(\$B\$3:C\$6)", rw("=SUM(\$B\$2:C\$5)", TableEdit.InsertRows(before = 2)))
    }

    @Test
    fun rewrite_should_ReparseRewrittenRefError_When_PoisonedFormulaEdited() {
        val poisoned = rw("=B3*2", TableEdit.DeleteRows(at = 3))!!
        assertNull(rw(poisoned, TableEdit.InsertRows(before = 1)))
        assertEquals(CellValue.Err(CellError.Ref), FormulaEvaluator().evaluate((FormulaParser.parse(poisoned) as FormulaParse.Ok).expr, object : CellResolver {
            override val rowCount = 5
            override val colCount = 5
            override fun valueAt(col: Int, row: Int) = CellValue.Blank
        }))
    }

    @Test
    fun rewrite_should_StayBounded_When_DeepNestingOrLongInput() {
        val deep = "=" + "(".repeat(10_000) + "B2" + ")".repeat(10_000)
        assertNull(rw(deep, TableEdit.InsertRows(before = 1)))
        val long = "=" + "B2+".repeat(400) + "1"
        assertNull(rw(long, TableEdit.InsertRows(before = 1)))
    }

    @Test
    fun asTransform_should_WrapRewrite_When_UsedAsPort() {
        val t = RefRewriter.asTransform()
        assertEquals(CellText("=B4"), t.apply(CellText("=B3"), TableEdit.InsertRows(before = 1)))
        assertNull(t.apply(CellText("=B3"), TableEdit.SetCell(CellAddress(1, 1), CellText("x"))))
    }
}
