package dev.stapler.stelekit.table.formula

import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.logging.LogSink
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableCalcTest {
    private fun doc(rows: List<List<String>>): TableDoc {
        val cols = rows[0].size
        val lines = rows.map { "| " + it.joinToString(" | ") + " |" }.toMutableList()
        lines.add(1, "|" + " --- |".repeat(cols))
        val parsed = TableParser.parse(lines.joinToString("\n"))
        return (parsed as TableParseResult.Parsed).doc
    }

    private fun a(s: String) = CellAddress(s.substring(1).toInt(), s[0] - 'A' + 1)
    private fun num(d: Double) = CellValue.Num(d)

    private val header = listOf("h", "x", "y")

    @Test
    fun valueAt_should_FollowAdr002_When_BlankTextAndFormulasMix() {
        val d = doc(listOf(header, listOf("a", "3", "=SUM(B2:B5)"), listOf("b", "5", "=AVERAGE(B4:B5)"), listOf("c", "", "=B2+B5"), listOf("d", "x", "")))
        val c = TableCalc(d)
        assertEquals(num(8.0), c.valueAt(a("C2")))
        assertEquals(CellValue.Err(CellError.DivZero), c.valueAt(a("C3")))
        assertEquals(CellValue.Err(CellError.Value), c.valueAt(a("C4")))
        assertEquals(num(3.0), c.valueAt(a("B2")))
        assertEquals(CellValue.Text("x"), c.valueAt(a("B5")))
        assertEquals(CellValue.Blank, c.valueAt(a("Z99")))
    }

    @Test
    fun valueAt_should_MarkEveryMember_When_CycleThenClear_When_Broken() {
        val d = doc(listOf(header, listOf("a", "=C2+1", "=B2+1"), listOf("b", "=SUM(B2:B3)", "")))
        val c = TableCalc(d)
        assertEquals(CellValue.Err(CellError.Cycle), c.valueAt(a("B2")))
        assertEquals(CellValue.Err(CellError.Cycle), c.valueAt(a("C2")))
        assertEquals(CellValue.Err(CellError.Cycle), c.valueAt(a("B3")), "range containing itself")
        val d2 = d.setCell(a("C2"), CellText("4"))
        c.update(d2, listOf(a("C2")))
        assertEquals(num(5.0), c.valueAt(a("B2")))
        assertEquals(num(4.0), c.valueAt(a("C2")))
    }

    @Test
    fun update_should_EvaluateOnlyTransitiveDependents_When_OneInputChanges() {
        val rows = mutableListOf(List(12) { "h$it" })
        repeat(199) { r -> rows.add(List(12) { c -> if (r == 198) "=SUM(${'A' + c}2:${'A' + c}199)" else "${r + c}" }) }
        val d = doc(rows)
        val c = TableCalc(d)
        val before = c.evaluationCount
        val d2 = d.setCell(CellAddress(5, 3), CellText("1000"))
        c.update(d2, listOf(CellAddress(5, 3)))
        assertEquals(1, c.evaluationCount - before)
    }

    @Test
    fun valueAt_should_ClampHugeRange_When_RangeExceedsTable() {
        val d = doc(listOf(header, listOf("=SUM(B1:ZZ99999)", "1", "2"), listOf("b", "2", "")))
        val c = TableCalc(d)
        assertEquals(num(5.0), c.valueAt(a("A2")))
    }

    @Test
    fun valueAt_should_KeepParseErrorsPerCell_When_FormulaMalformed() {
        val d = doc(listOf(header, listOf("a", "=1+", "=#nope")))
        val c = TableCalc(d)
        assertEquals(CellValue.Err(CellError.Parse), c.valueAt(a("B2")))
        assertEquals(CellValue.Text("=#nope"), c.valueAt(a("C2")))
    }

    @Test
    fun guard_should_ReturnErrAndLog_When_FunctionThrows() {
        val logs = mutableListOf<LogEntry>()
        val handle = LogManager.addSink(object : LogSink {
            override fun write(entry: LogEntry, formatted: String) { logs.add(entry) }
        })
        try {
            val boom = FunctionRegistry.default
                .with("SUM", FunctionImpl { throw IllegalStateException("boom") })
                .with("MAX", FunctionImpl { throw Error("fatal") })
            val d = doc(listOf(header, listOf("a", "=SUM(A1:A2)", "=MAX(A1:A2)"), listOf("b", "=B2+1", "")))
            val c = TableCalc(d, boom)
            assertEquals(CellValue.Err(CellError.Value), c.valueAt(a("B2")))
            assertEquals(CellValue.Err(CellError.Limit), c.valueAt(a("C2")))
            assertEquals(CellValue.Err(CellError.Value), c.valueAt(a("B3")), "downstream reads the error")
            assertTrue(logs.count { it.tag == "TableCalc" && it.throwable != null } >= 2, "guard must log each failure")
        } finally {
            handle.close()
        }
    }

    @Test
    fun orNull_should_NotBuild_When_TableOverCap() {
        val big = doc(listOf(List(201) { "h" }) + List(100) { List(201) { "1" } })
        assertNull(TableCalc.orNull(big))
        assertNotNull(TableCalc.orNull(doc(listOf(header, listOf("a", "1", "2")))))
    }

    @Test
    fun update_should_RebuildOrPatch_When_GivenTableEdit() {
        val d = doc(listOf(header, listOf("a", "1", "=B2*2")))
        val c = TableCalc(d)
        val edit = dev.stapler.stelekit.table.TableEdit.SetCell(a("B2"), CellText("4"))
        c.update(d.setCell(a("B2"), CellText("4")), edit)
        assertEquals(num(8.0), c.valueAt(a("C2")))
    }
}
