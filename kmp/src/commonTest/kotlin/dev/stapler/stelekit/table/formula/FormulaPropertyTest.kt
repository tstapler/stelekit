package dev.stapler.stelekit.table.formula

import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.FormulaRefScan
import dev.stapler.stelekit.table.RefusalReason
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.table.TableEditOutcome
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import dev.stapler.stelekit.table.TableSerializer
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FormulaPropertyTest {
    private val editor = TableEngine.editor()

    private fun parse(rows: List<List<String>>): TableDoc {
        val cols = rows[0].size
        val lines = rows.map { "| " + it.joinToString(" | ") + " |" }.toMutableList()
        lines.add(1, "|" + " --- |".repeat(cols))
        return assertIs<TableParseResult.Parsed>(TableParser.parse(lines.joinToString("\n"))).doc
    }

    private fun ref(row: Int, col: Int) = CellAddress(row, col).toString()

    /** Numeric data cells plus formula cells that read only data cells, so values never chain. */
    private class Grid(val doc: TableDoc, val formulas: Set<CellAddress>)

    private fun singleRefGrid(rnd: Random): Grid {
        val rows = rnd.nextInt(3, 9)
        val cols = rnd.nextInt(2, 6)
        val formulaCells = HashSet<CellAddress>()
        for (r in 2..rows) for (c in 1..cols) if (rnd.nextInt(4) == 0) formulaCells.add(CellAddress(r, c))
        val dataCells = (2..rows).flatMap { r -> (1..cols).map { CellAddress(r, it) } }.filter { it !in formulaCells }
        if (dataCells.isEmpty()) return singleRefGrid(rnd)
        val table = ArrayList<List<String>>()
        table.add(List(cols) { "h$it" })
        for (r in 2..rows) {
            table.add((1..cols).map { c ->
                val a = CellAddress(r, c)
                if (a in formulaCells) {
                    val x = dataCells[rnd.nextInt(dataCells.size)]
                    val y = dataCells[rnd.nextInt(dataCells.size)]
                    "=${ref(x.row, x.col)}*2+${ref(y.row, y.col)}"
                } else {
                    rnd.nextInt(0, 12).toString()
                }
            })
        }
        return Grid(parse(table), formulaCells)
    }

    private fun allValues(doc: TableDoc): Map<CellAddress, CellValue> {
        val calc = TableCalc(doc)
        return (1..doc.rowCount).flatMap { r -> (1..doc.columnCount).map { CellAddress(r, it) } }.associateWith { calc.valueAt(it) }
    }

    @Test
    fun incrementalEval_should_EqualFullRecompute_When_RandomEditSequences() = runTest {
        val pool = listOf("1", "2.5", "", "x", "=A2+B2", "=SUM(A2:A5)", "=B3*2", "=C2+1", "=SUM(B2:C4)", "=AVERAGE(A2:A4)", "=D5", "=A3", "=IF(A2>1,B2,C2)", "=MAX(A2:C5)", "=1/0")
        checkAll(300, Arb.long()) { seed ->
            val rnd = Random(seed)
            val rows = rnd.nextInt(3, 7)
            val cols = rnd.nextInt(2, 5)
            val table = listOf(List(cols) { "h" }) + List(rows - 1) { List(cols) { pool[rnd.nextInt(pool.size)] } }
            var doc = parse(table)
            val calc = TableCalc(doc)
            repeat(8) {
                val a = CellAddress(rnd.nextInt(2, rows + 1), rnd.nextInt(1, cols + 1))
                val edit = TableEdit.SetCell(a, CellText(pool[rnd.nextInt(pool.size)]))
                doc = doc.setCell(edit.address, edit.text)
                calc.update(doc, edit)
                val full = TableCalc(doc)
                for (r in 1..doc.rowCount) for (c in 1..doc.columnCount) {
                    val at = CellAddress(r, c)
                    assertEquals(full.valueAt(at), calc.valueAt(at), "seed=$seed at $at")
                }
            }
        }
    }

    @Test
    fun incrementalEval_should_EqualFullRecompute_When_PerFormulaBudgetIsHit() {
        val cols = 100
        val rows = 102
        fun build(corner: String): TableDoc {
            val lines = ArrayList<String>()
            lines.add("|" + " h |".repeat(cols))
            lines.add("|" + " --- |".repeat(cols))
            for (r in 2..rows) {
                val cells = (1..cols).map { c ->
                    when {
                        r == rows && c == 1 -> "=SUM(A1:${CellAddress.columnLabel(cols)}${rows - 1})"
                        r == rows && c == 2 -> "=SUM(A2:A10)"
                        r == 2 && c == 1 -> corner
                        else -> "1"
                    }
                }
                lines.add("| " + cells.joinToString(" | ") + " |")
            }
            return assertIs<TableParseResult.Parsed>(TableParser.parse(lines.joinToString("\n"))).doc
        }
        val before = build("1")
        val calc = TableCalc(before)
        val over = CellAddress(rows, 1)
        assertEquals(CellValue.Err(CellError.Limit), calc.valueAt(over), "range of ${(rows - 1) * cols} reads exceeds the 10 000 budget")
        val after = before.setCell(CellAddress(2, 1), CellText("5"))
        calc.update(after, TableEdit.SetCell(CellAddress(2, 1), CellText("5")))
        val full = TableCalc(after)
        for (a in listOf(over, CellAddress(rows, 2), CellAddress(2, 1), CellAddress(3, 3))) assertEquals(full.valueAt(a), calc.valueAt(a), "$a")
        assertEquals(CellValue.Num(13.0), calc.valueAt(CellAddress(rows, 2)), "SUM(A2:A10) after A2=5")
    }

    @Test
    fun structuralEdit_should_LeaveUnaffectedFormulasUnchangedAndDeletedEndpointHashRef_When_Generated() = runTest {
        checkAll(400, Arb.long()) { seed ->
            val rnd = Random(seed)
            val g = singleRefGrid(rnd)
            val d = g.doc
            val before = allValues(d)
            val at = rnd.nextInt(2, d.rowCount + 1)
            val out = editor.apply(d, TableEdit.DeleteRows(at))
            if (out !is TableEditOutcome.Applied) return@checkAll
            val after = allValues(out.doc)
            for (a in g.formulas) {
                if (a.row == at) continue
                val newAt = CellAddress(if (a.row > at) a.row - 1 else a.row, a.col)
                val refs = FormulaRefScan.scan(d.cell(a)!!.value)
                val deleted = refs.any { it.r1 == at }
                val v = after.getValue(newAt)
                if (deleted) {
                    assertEquals(CellValue.Err(CellError.Ref), v, "seed=$seed ${d.cell(a)!!.value} must be #REF!")
                } else {
                    assertEquals(before.getValue(a), v, "seed=$seed ${d.cell(a)!!.value} must keep its value")
                }
            }
        }
    }

    @Test
    fun structuralEdit_should_KeepEveryFormulaValue_When_InsertOrMoveGenerated() = runTest {
        checkAll(400, Arb.long()) { seed ->
            val rnd = Random(seed)
            val g = singleRefGrid(rnd)
            val d = g.doc
            val before = allValues(d)
            val edit = when (rnd.nextInt(4)) {
                0 -> TableEdit.InsertRows(rnd.nextInt(2, d.rowCount + 1))
                1 -> TableEdit.InsertCols(rnd.nextInt(1, d.columnCount + 1))
                2 -> TableEdit.MoveRows(rnd.nextInt(2, d.rowCount + 1), 1, rnd.nextInt(2, d.rowCount + 2))
                else -> TableEdit.MoveCols(rnd.nextInt(1, d.columnCount + 1), 1, rnd.nextInt(1, d.columnCount + 2))
            }
            val out = editor.apply(d, edit)
            if (out !is TableEditOutcome.Applied) return@checkAll
            val after = allValues(out.doc)
            val rowMap = rowMapping(edit, d.rowCount)
            val colMap = colMapping(edit, d.columnCount)
            for (a in g.formulas) {
                val newAt = CellAddress(rowMap(a.row), colMap(a.col))
                assertEquals(before.getValue(a), after.getValue(newAt), "seed=$seed $edit ${d.cell(a)!!.value}")
            }
        }
    }

    private fun rowMapping(e: TableEdit, rows: Int): (Int) -> Int = when (e) {
        is TableEdit.InsertRows -> { r -> if (r >= e.before) r + e.count else r }
        is TableEdit.MoveRows -> permutationOf(e.from, e.count, e.before)
        else -> { r -> r }
    }

    private fun colMapping(e: TableEdit, cols: Int): (Int) -> Int = when (e) {
        is TableEdit.InsertCols -> { c -> if (c >= e.before) c + e.count else c }
        is TableEdit.MoveCols -> permutationOf(e.from, e.count, e.before)
        else -> { c -> c }
    }

    /** Independent model of a block move: the block lands before original index [before]. */
    private fun permutationOf(from: Int, count: Int, before: Int): (Int) -> Int = { v ->
        val end = from + count
        when {
            before in from..end -> v
            v in from until end -> if (before > end) before - count + (v - from) else before + (v - from)
            before > end && v in end until before -> v - count
            before < from && v in before until from -> v + count
            else -> v
        }
    }

    @Test
    fun nonMovingEdit_should_LeaveFormulaTextAndRowLineIdentity_When_NoReferenceChanges() = runTest {
        checkAll(300, Arb.long()) { seed ->
            val rnd = Random(seed)
            val g = singleRefGrid(rnd)
            val d = g.doc
            val lastRow = d.rowCount
            // formulas only read rows above the last, so editing the last row's end moves no reference
            val safe = g.formulas.all { a -> FormulaRefScan.scan(d.cell(a)!!.value).none { it.r1 >= lastRow } }
            if (!safe) return@checkAll
            for (edit in listOf(TableEdit.InsertRows(before = lastRow + 1), TableEdit.DeleteRows(at = lastRow))) {
                if (edit is TableEdit.DeleteRows && g.formulas.any { it.row == lastRow }) continue
                val out = editor.apply(d, edit)
                val doc = (out as? TableEditOutcome.Applied)?.doc ?: continue
                for (a in g.formulas) {
                    if (a.row >= lastRow && edit is TableEdit.DeleteRows) continue
                    assertEquals(d.cell(a)!!.value, doc.cell(a)!!.value, "seed=$seed $edit")
                    assertNull2(RefRewriter.rewrite(d.cell(a)!!.value, edit), "seed=$seed $edit")
                }
                for (r in 1 until lastRow) assertSame(d.row(r), doc.row(r), "seed=$seed row $r identity after $edit")
            }
        }
    }

    private fun assertNull2(actual: Any?, message: String) = assertTrue(actual == null, "$message but was $actual")

    @Test
    fun evaluator_should_MatchIndependentNaiveModel_When_ExactlyRepresentableDoubles() = runTest {
        checkAll(1000, Arb.long()) { seed ->
            val rnd = Random(seed)
            val tree = randomTree(rnd, 4)
            val text = "=" + render(tree, 0)
            val calc = TableCalc(parse(listOf(listOf("h", "h"), listOf("", text))))
            // primitive == so -0.0 equals 0.0 (the display is "0" either way)
            val actual = assertIs<CellValue.Num>(calc.valueAt(CellAddress(2, 2)), "seed=$seed $text").value
            assertTrue(actual == eval(tree), "seed=$seed $text expected ${eval(tree)} got $actual")
        }
    }

    @Test
    fun pow_should_FoldLeftToRight_When_UnparenthesizedChainGenerated() = runTest {
        checkAll(500, Arb.long()) { seed ->
            val rnd = Random(seed)
            val operands = List(rnd.nextInt(2, 6)) { rnd.nextInt(1, 4) }
            val negateFirst = rnd.nextBoolean()
            val text = "=" + (if (negateFirst) "-" else "") + operands.joinToString("^")
            // Excel: unary minus binds tighter than ^ and ^ is left-associative.
            var expected = (if (negateFirst) -operands[0] else operands[0]).toDouble()
            for (x in operands.drop(1)) expected = expected.pow(x.toDouble())
            val calc = TableCalc(parse(listOf(listOf("h", "h"), listOf("", text))))
            val actual = assertIs<CellValue.Num>(calc.valueAt(CellAddress(2, 2)), "seed=$seed $text").value
            assertTrue(actual == expected, "seed=$seed $text expected $expected got $actual")
        }
    }

    // Straight-line model: its own tree, printer with minimal parentheses, and evaluator. Shares no code with the engine.
    private sealed interface N
    private class Lit(val v: Int) : N
    private class Op(val op: Char, val l: N, val r: N) : N
    private class Fn(val name: String, val args: List<N>) : N

    private fun randomTree(rnd: Random, depth: Int): N = when {
        depth == 0 || rnd.nextInt(4) == 0 -> Lit(rnd.nextInt(0, 21))
        rnd.nextInt(5) == 0 -> Fn(listOf("SUM", "MIN", "MAX", "ABS")[rnd.nextInt(4)], List(rnd.nextInt(1, 4)) { randomTree(rnd, depth - 1) }).let {
            if (it.name == "ABS") Fn("ABS", it.args.take(1)) else it
        }
        else -> Op("+-*"[rnd.nextInt(3)], randomTree(rnd, depth - 1), randomTree(rnd, depth - 1))
    }

    private fun prec(n: N) = if (n is Op) (if (n.op == '*') 2 else 1) else 3

    private fun render(n: N, parentPrec: Int, rightOfMinus: Boolean = false): String = when (n) {
        is Lit -> n.v.toString()
        is Fn -> n.name + "(" + n.args.joinToString(",") { render(it, 0) } + ")"
        is Op -> {
            val p = prec(n)
            val s = render(n.l, p) + n.op + render(n.r, p, rightOfMinus = true)
            if (p < parentPrec || (p == parentPrec && rightOfMinus)) "($s)" else s
        }
    }

    private fun eval(n: N): Double = when (n) {
        is Lit -> n.v.toDouble()
        is Op -> when (n.op) {
            '+' -> eval(n.l) + eval(n.r)
            '-' -> eval(n.l) - eval(n.r)
            else -> eval(n.l) * eval(n.r)
        }
        is Fn -> {
            val a = n.args.map { eval(it) }
            when (n.name) {
                "SUM" -> a.fold(0.0) { x, y -> x + y }
                "MIN" -> a.reduce { x, y -> if (y < x) y else x }
                "MAX" -> a.reduce { x, y -> if (y > x) y else x }
                else -> if (a[0] < 0) -a[0] else a[0]
            }
        }
    }

    @Test
    fun deleteThenSerializeThenReparse_should_StayHashRef_When_ReferencedCellDeleted() = runTest {
        checkAll(300, Arb.long()) { seed ->
            val rnd = Random(seed)
            val g = singleRefGrid(rnd)
            val d = g.doc
            val at = rnd.nextInt(2, d.rowCount + 1)
            val out = editor.apply(d, TableEdit.DeleteRows(at)) as? TableEditOutcome.Applied ?: return@checkAll
            val reparsed = assertIs<TableParseResult.Parsed>(TableParser.parse(TableSerializer.serialize(out.doc))).doc
            assertEquals(out.doc, reparsed, "seed=$seed")
            for (r in 1..reparsed.rowCount) for (c in 1..reparsed.columnCount) {
                val text = reparsed.cell(CellAddress(r, c))!!.value
                if ("#REF!" !in text) continue
                assertTrue(FormulaParser.isFormulaShaped(text), "seed=$seed $text stays a formula")
                val p = assertIs<FormulaParse.Ok>(FormulaParser.parse(text), "seed=$seed $text")
                assertTrue(containsRefError(p.expr), "seed=$seed $text has a RefError node")
                assertEquals("#REF!", TableCalc(reparsed).displayAt(CellAddress(r, c)), "seed=$seed $text")
                assertNull2(RefRewriter.rewrite(text, TableEdit.InsertRows(before = 1000)), "seed=$seed")
            }
        }
    }

    private fun containsRefError(e: Expr): Boolean = when (e) {
        is Expr.RefError -> true
        is Expr.Bin -> containsRefError(e.left) || containsRefError(e.right)
        is Expr.Neg -> containsRefError(e.operand)
        is Expr.Pct -> containsRefError(e.operand)
        is Expr.Call -> e.args.any { containsRefError(it) }
        else -> false
    }

    @Test
    fun sortedTable_should_KeepUnmovedOrderInsensitiveFormulaValues_When_Applied() = runTest {
        var applied = 0
        var refused = 0
        checkAll(400, Arb.long()) { seed ->
            val rnd = Random(seed)
            val n = rnd.nextInt(2, 7)
            val last = n + 1
            val body = ArrayList<List<String>>()
            for (r in 2..last) {
                val c = when (rnd.nextInt(4)) {
                    0 -> "=A$r*B$r"
                    1 -> "=A$r+1"
                    2 -> if (rnd.nextInt(3) == 0 && r < last) "=B${r + 1}*2" else "=B$r"
                    else -> "=B$r*C"
                }.replace("*C", "*2")
                body.add(listOf(rnd.nextInt(0, 6).toString(), rnd.nextInt(0, 9).toString(), c))
            }
            val totals = listOf(
                listOf("tot", "=SUM(B2:B$last)", "=AVERAGE(A2:A$last)"),
                listOf("max", "=MAX(B2:B$last)", "=COUNT(A2:A$last)"),
            )
            val d = parse(listOf(listOf("a", "b", "c")) + body + totals)
            val out = editor.apply(d, TableEdit.SortRows(col = rnd.nextInt(1, 3), ascending = rnd.nextBoolean()))
            if (out is TableEditOutcome.Refused) {
                refused++
                assertTrue(out.reason == RefusalReason.SortWouldChangeValues || out.reason == RefusalReason.SortWouldSelfReference, "seed=$seed ${out.reason}")
                return@checkAll
            }
            applied++
            val sorted = (out as TableEditOutcome.Applied).doc
            val before = TableCalc(d)
            val after = TableCalc(sorted)
            for (r in (last + 1)..(last + 2)) for (c in 2..3) {
                assertEquals(before.valueAt(CellAddress(r, c)), after.valueAt(CellAddress(r, c)), "seed=$seed totals ($r,$c)")
            }
            // a moved row keeps computing from itself: ADR-003 refuses a moved formula that reads another body row
            for (r in (2..last).filter { sorted.row(it) != d.row(it) }) {
                val f = sorted.cell(CellAddress(r, 3))!!.value
                assertTrue(FormulaRefScan.scan(f).all { it.r1 == r }, "seed=$seed row $r keeps own-row refs: $f")
            }
        }
        assertTrue(applied > 20 && refused > 0, "generator should reach both outcomes: applied=$applied refused=$refused")
    }
}
