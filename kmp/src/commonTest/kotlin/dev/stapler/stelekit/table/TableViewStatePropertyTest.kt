package dev.stapler.stelekit.table

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

// The ADR-007 "never changes serialized output" property needs TableDoc and is deferred with it.
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
}
