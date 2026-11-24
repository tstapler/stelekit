package dev.stapler.stelekit.table

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableViewStatePropertyTest {
    @Test
    fun viewState_should_KeepWidthsInRange_When_AnySetWidthSequence() = runTest {
        checkAll(Arb.list(Arb.int(-2..8), 0..20), Arb.list(Arb.float(-1_000f..2_000f), 20..20), Arb.boolean()) { cols, widths, sorted ->
            var v = TableViewState()
            cols.zip(widths).forEach { (c, w) -> v = v.setWidth(c, w) }
            if (sorted) v = v.withSort(1, true).onRowOrderChanged()
            assertTrue(v.columnWidthsDp.values.all { it in TableViewState.MIN_WIDTH_DP..TableViewState.MAX_WIDTH_DP })
            assertTrue(v.columnWidthsDp.keys.all { it >= 1 })
            assertTrue(v.sort == null)
        }
    }

    private val content = "|  a  | b | c |\n|:-|-:|--|\n| 1 | =A2*2 | x |\n| 2 | | <br/> |\n"

    @Test
    fun viewState_should_NeverChangeSerializedOutput_When_AnyViewStateSequence() = runTest {
        val doc = (TableParser.parse(content) as TableParseResult.Parsed).doc
        val expected = TableSerializer.serialize(doc)
        val ops = Arb.element(0, 1, 2, 3, 4, 5)
        checkAll(Arb.list(ops, 0..30), Arb.int(-2..5), Arb.float(-500f..1_000f)) { seq, col, w ->
            var v = TableViewState()
            seq.forEach { op ->
                v = when (op) {
                    0 -> v.setWidth(col, w)
                    1 -> v.withFreezeHeaderRow(col % 2 == 0)
                    2 -> v.withFreezeFirstColumn(col % 2 != 0)
                    3 -> v.withSort(col, w > 0)
                    4 -> v.onRowOrderChanged()
                    else -> TableViewState()
                }
            }
            assertEquals(expected, TableSerializer.serialize(doc))
        }
    }
}
