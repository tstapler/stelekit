// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.table

import dev.stapler.stelekit.table.formula.TableCalc
import dev.stapler.stelekit.table.formula.TableEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Story 8.5: the cost of one commit on a 200x12 table, as counters (no wall clock: that flakes on CI and means
 * nothing on Wasm). A commit is apply edit, incremental evaluate, serialize.
 */
class TableCommitCountersTest {
    private val body = 200
    private val cols = 12

    /** Header, 199 data rows and a totals row: 200 body rows, one `=SUM` per column. */
    private fun table(grandTotal: Boolean = false): String = buildString {
        append((1..cols).joinToString(" | ", "| ", " |") { "H$it" })
        append("\n").append((1..cols).joinToString("|", "|", "|") { "---" })
        for (r in 1 until body) append("\n").append((1..cols).joinToString(" | ", "| ", " |") { c -> "${r * c}" })
        val last = body + 1
        append("\n").append((1..cols).joinToString(" | ", "| ", " |") { c ->
            val col = 'A' + c - 1
            if (grandTotal && c == cols) "=SUM(A$last:K$last)" else "=SUM($col" + "2:$col${last - 1})"
        })
    }

    private fun parse(text: String): TableDoc = (TableParser.parse(text) as TableParseResult.Parsed).doc

    private class Commit(val evaluations: Int, val dirtyRows: Int, val changedLines: Int, val lines: Int)

    private fun commit(text: String, address: CellAddress, value: String): Commit {
        val doc = parse(text)
        val calc = assertNotNull(TableCalc.orNull(doc), "a 200x12 table is inside the calc cap")
        val before = calc.evaluationCount
        val edit = TableEdit.SetCell(address, CellText(value))
        val applied = assertNotNull(TableEngine.editor().apply(doc, edit) as? TableEditOutcome.Applied)
        calc.update(applied.doc, edit)
        val out = TableSerializer.serialize(applied.doc).split("\n")
        val original = text.split("\n")
        return Commit(
            evaluations = calc.evaluationCount - before,
            dirtyRows = (listOf(applied.doc.header) + applied.doc.body).count { it.isDirty },
            changedLines = out.indices.count { out[it] != original.getOrNull(it) },
            lines = out.size,
        )
    }

    @Test
    fun singleCellCommit_should_EvaluateOnlyTheColumnTotal_AndDirtyExactlyOneLine() {
        val text = table()
        val c = commit(text, CellAddress(row = 57, col = 5), "1000")
        assertEquals(1, c.evaluations, "the SUM below the edited cell is the only dependent")
        assertEquals(1, c.dirtyRows)
        assertEquals(1, c.changedLines, "the other ${c.lines - 1} lines are byte for byte")
        assertEquals(body + 2, c.lines)
    }

    @Test
    fun commitInAnotherColumn_should_CostTheSame() {
        val c = commit(table(), CellAddress(row = 2, col = 12), "7")
        assertEquals(1, c.evaluations)
        assertEquals(1, c.dirtyRows)
    }

    @Test
    fun transitiveDependents_should_BeCounted_And_OnlyThose() {
        val c = commit(table(grandTotal = true), CellAddress(row = 30, col = 3), "9")
        assertEquals(2, c.evaluations, "column total and the grand total that reads it")
        assertEquals(1, c.dirtyRows)
        assertEquals(1, c.changedLines)
    }

    @Test
    fun replacingATotalWithAConstant_should_EvaluateNothing() {
        val c = commit(table(), CellAddress(row = body + 1, col = 2), "5")
        assertEquals(0, c.evaluations, "a constant has nothing to evaluate and nothing reads the total")
        assertEquals(1, c.dirtyRows)
    }
}
