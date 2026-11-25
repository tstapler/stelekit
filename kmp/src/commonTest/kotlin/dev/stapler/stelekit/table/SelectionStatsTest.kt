package dev.stapler.stelekit.table

import dev.stapler.stelekit.table.formula.TableCalc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectionStatsTest {
    private fun calc(vararg rows: String): TableCalc {
        val content = "| h | v |\n| --- | --- |\n" + rows.joinToString("\n") { "| $it |" }
        return TableCalc((TableParser.parse(content) as TableParseResult.Parsed).doc)
    }

    private fun col(from: Int, to: Int) = GridSelection(CellAddress(from, 2), CellAddress(to, 2))

    @Test
    fun stats_should_ReportCount2Sum8Average4_When_3_5_x_Blank() {
        val c = calc("a | 3", "b | 5", "c | x", "d | ")
        val s = SelectionStats.compute(col(2, 5), c::numberAt)
        assertEquals(SelectionStatsResult(2, 8.0, 4.0), s)
    }

    @Test
    fun stats_should_BeNull_When_NoNumbers() {
        val c = calc("a | x", "b | ", "c | =1/0")
        assertNull(SelectionStats.compute(col(2, 4), c::numberAt))
    }

    @Test
    fun stats_should_CountFormulaValues_When_SelectionHasFormulas() {
        val c = calc("a | 3", "b | =B2*2")
        assertEquals(SelectionStatsResult(2, 9.0, 4.5), SelectionStats.compute(col(2, 3), c::numberAt))
    }

    @Test
    fun toTsv_should_GiveValueAndFormulaForms_When_RangeCopied() {
        val c = calc("a | 3", "b | =B2*2", "c, \"q\" | x")
        val sel = GridSelection(CellAddress(2, 1), CellAddress(4, 2))
        assertEquals("a\t3\r\nb\t6\r\n\"c, \"\"q\"\"\"\tx", SelectionStats.toTsv(sel, c::displayAt))
        assertEquals("a\t3\r\nb\t=B2*2\r\n\"c, \"\"q\"\"\"\tx", SelectionStats.toTsv(sel, c::rawAt))
    }

    @Test
    fun toTsv_should_NormalizeReversedAnchorFocus_When_SelectedBackwards() {
        val c = calc("a | 1", "b | 2")
        val sel = GridSelection(CellAddress(3, 2), CellAddress(2, 1))
        assertEquals("a\t1\r\nb\t2", SelectionStats.toTsv(sel, c::displayAt))
    }
}
