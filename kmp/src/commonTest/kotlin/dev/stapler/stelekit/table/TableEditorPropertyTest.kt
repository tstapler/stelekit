package dev.stapler.stelekit.table

import dev.stapler.stelekit.table.TableEdit.DeleteCols
import dev.stapler.stelekit.table.TableEdit.DeleteRows
import dev.stapler.stelekit.table.TableEdit.InsertCols
import dev.stapler.stelekit.table.TableEdit.InsertRows
import dev.stapler.stelekit.table.TableEdit.MoveCols
import dev.stapler.stelekit.table.TableEdit.MoveRows
import dev.stapler.stelekit.table.TableEdit.SetAlignment
import dev.stapler.stelekit.table.TableEdit.SetCell
import dev.stapler.stelekit.table.TableEdit.SortRows
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TableEditorPropertyTest {
    private val editor = TableEditor()

    private fun doc(rnd: Random, withFormulas: Boolean = false): TableDoc {
        val cols = rnd.nextInt(1, 5)
        val rows = rnd.nextInt(0, 7)
        val pool = listOf("", "1", "2", "10", "abc", "Abc", "x y", "-3", "0.5") + if (withFormulas) listOf("=SUM(B2:B3)", "=A2*2") else emptyList()
        fun line(cells: List<String>) = "|" + cells.joinToString("|") { " $it " } + "|"
        val lines = listOf(line(List(cols) { "h$it" }), "|" + List(cols) { "---" }.joinToString("|") + "|") +
            List(rows) { line(List(cols) { pool[rnd.nextInt(pool.size)] }) }
        return assertIs<TableParseResult.Parsed>(TableParser.parse(lines.joinToString("\n"))).doc
    }

    private fun randomEdit(rnd: Random, d: TableDoc): TableEdit = when (rnd.nextInt(9)) {
        0 -> InsertRows(rnd.nextInt(2, d.rowCount + 2), rnd.nextInt(1, 3))
        1 -> InsertCols(rnd.nextInt(1, d.columnCount + 2), rnd.nextInt(1, 3))
        2 -> DeleteRows(rnd.nextInt(1, d.rowCount + 1), 1)
        3 -> DeleteCols(rnd.nextInt(1, d.columnCount + 1), 1)
        4 -> if (d.rowCount < 2) SetAlignment(1, ColumnAlign.LEFT) else MoveRows(rnd.nextInt(2, d.rowCount + 1), 1, rnd.nextInt(2, d.rowCount + 2))
        5 -> MoveCols(rnd.nextInt(1, d.columnCount + 1), 1, rnd.nextInt(1, d.columnCount + 2))
        6 -> SetAlignment(rnd.nextInt(1, d.columnCount + 1), ColumnAlign.entries[rnd.nextInt(4)])
        7 -> SetCell(CellAddress(rnd.nextInt(1, d.rowCount + 1), rnd.nextInt(1, d.columnCount + 1)), CellText("v${rnd.nextInt(3)}"))
        else -> SortRows(rnd.nextInt(1, d.columnCount + 1), rnd.nextBoolean())
    }

    @Test
    fun structuralOps_should_KeepEveryRowAtHeaderCellCount_When_NonRaggedInput() = runTest {
        checkAll(500, Arb.long()) { seed ->
            val rnd = Random(seed)
            var d = doc(rnd)
            repeat(4) {
                val out = editor.apply(d, randomEdit(rnd, d))
                if (out is TableEditOutcome.Applied) d = out.doc
                assertTrue((1..d.rowCount).all { d.row(it).cells.size == d.columnCount }, "seed=$seed")
                assertEquals(d.columnCount, d.delimiter.aligns.size, "seed=$seed")
            }
        }
    }

    @Test
    fun sort_should_NeverYieldFormulaWhoseRangeContainsOwnRow_OrBeRefused() = runTest {
        checkAll(500, Arb.long()) { seed ->
            val rnd = Random(seed)
            val d = doc(rnd, withFormulas = true)
            if (d.rowCount < 2) return@checkAll
            val out = editor.apply(d, SortRows(rnd.nextInt(1, d.columnCount + 1), rnd.nextBoolean(), if (rnd.nextBoolean() && d.rowCount >= 3) RowRange(2, d.rowCount) else null))
            if (out !is TableEditOutcome.Applied) return@checkAll
            val moved = (2..out.doc.rowCount).filter { out.doc.row(it) !== d.row(it) }
            for (row in moved) {
                out.doc.row(row).cells.forEachIndexed { i, c ->
                    if (!c.value.startsWith("=")) return@forEachIndexed
                    FormulaRefScan.scan(c.value).filter { it.isRange }.forEach { r ->
                        assertTrue(!(row in r.r1..r.r2 && i + 1 in r.c1..r.c2), "seed=$seed ${c.value} at row $row")
                    }
                }
            }
        }
    }

    @Test
    fun descendingSort_should_KeepBlanksLastAndTiesStable_When_Generated() = runTest {
        checkAll(500, Arb.long()) { seed ->
            val rnd = Random(seed)
            // column 1 holds the sort key, column 2 the original position (tie-break witness)
            val keys = List(rnd.nextInt(2, 9)) { listOf("", "1", "2", "b", "B").random(rnd) }
            val lines = listOf("| k | i |", "|---|---|") + keys.mapIndexed { i, k -> "| $k | $i |" }
            val d = assertIs<TableParseResult.Parsed>(TableParser.parse(lines.joinToString("\n"))).doc
            for (asc in listOf(true, false)) {
                val out = editor.apply(d, SortRows(1, asc))
                val sorted = (out as TableEditOutcome.Applied).doc
                val rows = (2..sorted.rowCount).map { sorted.cell(CellAddress(it, 1))!!.value to sorted.cell(CellAddress(it, 2))!!.value.toInt() }
                val firstBlank = rows.indexOfFirst { it.first.isEmpty() }
                if (firstBlank >= 0) assertTrue(rows.drop(firstBlank).all { it.first.isEmpty() }, "seed=$seed asc=$asc blanks last")
                rows.zipWithNext().forEach { (a, b) ->
                    if (a.first.lowercase() == b.first.lowercase()) assertTrue(a.second < b.second, "seed=$seed asc=$asc ties stable")
                }
            }
        }
    }

    @Test
    fun moveRows_should_BePermutationAndKeepRowKeys_When_Generated() = runTest {
        checkAll(500, Arb.long()) { seed ->
            val rnd = Random(seed)
            val d = doc(rnd)
            if (d.rowCount < 3) return@checkAll
            val from = rnd.nextInt(2, d.rowCount + 1)
            val count = rnd.nextInt(1, d.rowCount - from + 2)
            val out = editor.apply(d, MoveRows(from, count, rnd.nextInt(2, d.rowCount + 2)))
            val a = out as TableEditOutcome.Applied
            assertEquals(d.rowCount, a.doc.rowCount, "seed=$seed")
            val sameLines = (2..d.rowCount).map { d.row(it) }
            val newLines = (2..a.doc.rowCount).map { a.doc.row(it) }
            assertEquals(sameLines.size, newLines.size, "seed=$seed")
            assertTrue(sameLines.all { l -> newLines.count { it === l } == 1 }, "seed=$seed")
            (a.summary as? EditSummary.Moved)?.let { assertEquals((1..d.rowCount).toList(), it.permutation.sorted(), "seed=$seed") }
        }
    }

    @Test
    fun edits_should_NeverTouchUntouchedRowLines_When_IdentityChecked() = runTest {
        checkAll(500, Arb.long()) { seed ->
            val rnd = Random(seed)
            val d = doc(rnd)
            val target = CellAddress(rnd.nextInt(1, d.rowCount + 1), rnd.nextInt(1, d.columnCount + 1))
            val out = editor.apply(d, SetCell(target, CellText("changed"))) as TableEditOutcome.Applied
            for (r in 1..d.rowCount) if (r != target.row) assertSame(d.row(r), out.doc.row(r), "seed=$seed row=$r")
            assertSame(d.delimiter, out.doc.delimiter)
        }
    }

    @Test
    fun refusedEdit_should_BeDistinctTypeAndAppliedDocDiffersOrIsNoChange_When_Generated() = runTest {
        checkAll(1000, Arb.long()) { seed ->
            val rnd = Random(seed)
            val d = doc(rnd)
            when (val out = editor.apply(d, randomEdit(rnd, d))) {
                is TableEditOutcome.Refused -> Unit
                is TableEditOutcome.Applied ->
                    if (out.summary !is EditSummary.NoChange) assertNotEquals(d, out.doc, "seed=$seed")
                    else assertEquals(d, out.doc, "seed=$seed")
            }
        }
    }

    /** Header width 2-4; body rows randomly shorter than the header (never longer) so implicit empty slots exist. */
    private fun raggedDoc(rnd: Random): TableDoc {
        val cols = rnd.nextInt(2, 5)
        val rows = rnd.nextInt(1, 6)
        val pool = listOf("", "1", "2", "10", "abc", "x y")
        fun line(cells: List<String>) = "|" + cells.joinToString("|") { " $it " } + "|"
        val body = List(rows) { line(List(rnd.nextInt(1, cols + 1)) { pool[rnd.nextInt(pool.size)] }) }
        val lines = listOf(line(List(cols) { "h$it" }), "|" + List(cols) { "---" }.joinToString("|") + "|") + body
        return assertIs<TableParseResult.Parsed>(TableParser.parse(lines.joinToString("\n"))).doc
    }

    private fun padded(d: TableDoc): TableDoc {
        fun pad(r: RowLine) = RowLine.fresh(r.cells + List((d.columnCount - r.cells.size).coerceAtLeast(0)) { CellText("") }, r.cr)
        return d.copy(header = pad(d.header), body = d.body.map(::pad))
    }

    private fun grid(d: TableDoc): List<List<String>> =
        (1..d.rowCount).map { r -> (1..d.columnCount).map { c -> d.cell(CellAddress(r, c))?.value ?: "" } }

    @Test
    fun moveCols_should_KeepImplicitEmptySlots_When_RowIsShorterThanHeader() {
        val doc = assertIs<TableParseResult.Parsed>(TableParser.parse("| A | B | C |\n|---|---|---|\n| a | b |")).doc
        val out = editor.apply(doc, MoveCols(from = 3, count = 1, before = 1)) as TableEditOutcome.Applied
        assertEquals(listOf("C", "A", "B"), (1..3).map { out.doc.cell(CellAddress(1, it))!!.value })
        assertEquals(listOf("", "a", "b"), (1..3).map { out.doc.cell(CellAddress(2, it))?.value ?: "<missing>" })
    }

    @Test
    fun structuralAndSortOps_should_MatchPaddedTableSemantics_When_BodyRowsAreRagged() = runTest {
        checkAll(800, Arb.long()) { seed ->
            val rnd = Random(seed)
            val ragged = raggedDoc(rnd)
            val full = padded(ragged)
            val edit = randomEdit(rnd, ragged)
            val a = editor.apply(ragged, edit)
            val b = editor.apply(full, edit)
            assertEquals(b::class, a::class, "seed=$seed $edit")
            if (a is TableEditOutcome.Applied && b is TableEditOutcome.Applied) {
                assertEquals(grid(b.doc), grid(a.doc), "seed=$seed $edit")
            }
        }
    }
}
