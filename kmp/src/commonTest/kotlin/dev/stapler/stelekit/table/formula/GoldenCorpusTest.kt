package dev.stapler.stelekit.table.formula

import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Hand-checked expectations written from ADR-002 and documented Sheets/Excel behaviour, not from the
 * implementation. Default grid (columns A..E, header row 1, formula under test in E7 unless [Case.at]):
 *
 * ```
 *      A      B     C      D      E
 *  2   3      10    1.5    =1/0
 *  3   1      20    2.5    a
 *  4   2      30    -3.5
 *  5   (blank) 40
 *  6   x      50
 *  7                              <formula>
 * ```
 */
class GoldenCorpusTest {
    private class Case(
        val formula: String,
        val expected: String,
        val overrides: Map<String, String> = emptyMap(),
        val at: String = "E7",
    )

    private val defaults: Map<String, String> = mapOf(
        "A2" to "3", "A3" to "1", "A4" to "2", "A6" to "x",
        "B2" to "10", "B3" to "20", "B4" to "30", "B5" to "40", "B6" to "50",
        "C2" to "1.5", "C3" to "2.5", "C4" to "-3.5",
        "D2" to "=1/0", "D3" to "a",
    )

    private fun addr(s: String) = CellAddress(s.substring(1).toInt(), s[0] - 'A' + 1)

    private fun build(cells: Map<String, String>): TableDoc {
        val cols = 5
        val lines = ArrayList<String>()
        lines.add("| " + List(cols) { "h" }.joinToString(" | ") + " |")
        lines.add("|" + " --- |".repeat(cols))
        for (row in 2..7) {
            val cs = (1..cols).map { cells["${'A' + it - 1}$row"] ?: "" }
            lines.add("| " + cs.joinToString(" | ") + " |")
        }
        return assertIs<TableParseResult.Parsed>(TableParser.parse(lines.joinToString("\n"))).doc
    }

    private fun run(c: Case): String {
        val cells = defaults + c.overrides + (c.at to c.formula)
        return TableCalc(build(cells)).displayAt(addr(c.at))
    }

    private fun f(formula: String, expected: String, overrides: Map<String, String> = emptyMap(), at: String = "E7") =
        Case(formula, expected, overrides, at)

    private val cases: List<Case> = listOf(
        // arithmetic and precedence
        f("=1+2", "3"),
        f("=2*3+4", "10"),
        f("=2+3*4", "14"),
        f("=(2+3)*4", "20"),
        f("=10/4", "2.5"),
        f("=2^3", "8"),
        f("=-2^2", "4"),
        f("=-(2^2)", "-4"),
        f("=2^3^2", "64"),
        f("=2^(3^2)", "512"),
        f("=(2^3)^2", "64"),
        f("=2^-1", "0.5"),
        f("=--3", "3"),
        f("=+5", "5"),
        f("=50%", "0.5"),
        f("=200*10%", "20"),
        f("=0.1+0.2", "0.3"),
        f("=1/0", "#DIV/0!"),
        f("=007+1", "8"),
        f("=1/3*3", "1"),
        f("=2^0.5", "1.41421356237"),
        f("=\"a\"+1", "#VALUE!"),
        f("=  A2 +  B2  ", "13"),
        // comparison and IF
        f("=IF(1<2,\"yes\",\"no\")", "yes"),
        f("=IF(2<=1,1,2)", "2"),
        f("=IF(3>=3,1,0)", "1"),
        f("=IF(1<>2,\"y\",\"n\")", "y"),
        f("=IF(A2>A3,\"big\",\"small\")", "big"),
        f("=IF(A6=\"x\",1,0)", "1"),
        f("=IF(\"a\"=\"A\",1,0)", "1"),
        f("=IF(A2>5,1,B2)", "10"),
        f("=IF(B2=10,\"ten\",\"other\")", "ten"),
        // aggregates over ranges, blanks and text
        f("=SUM(A2:A6)", "6"),
        f("=SUM(A2:A4)", "6"),
        f("=sum(A2:A4)", "6"),
        f("=SUM( A2:A4 )", "6"),
        f("=SUM(A5:A5)", "0"),
        f("=SUM(B2:B6)", "150"),
        f("=SUM(B2:B5)", "100"),
        f("=SUM(B2:C3)", "34"),
        f("=SUM(B4:B2)", "60"),
        f("=SUM(A2:A3,B2)", "14"),
        f("=SUM(1,2,3)", "6"),
        f("=COUNT(A2:A6)", "3"),
        f("=COUNTA(A2:A6)", "4"),
        f("=MIN(A2:A6)", "1"),
        f("=MAX(A2:A6)", "3"),
        f("=MAX(1,5,3)", "5"),
        f("=MIN(A5:A6)", "0"),
        f("=AVERAGE(A2:A4)", "2"),
        f("=AVERAGE(B2:B6)", "30"),
        f("=AVERAGE(A5:A5)", "#DIV/0!"),
        f("=AVERAGE(A5:A6)", "#DIV/0!"),
        f("=ROUND(C2,0)", "2"),
        f("=ROUND(C3,0)", "3"),
        f("=ROUND(C4,0)", "-4"),
        f("=ROUND(C2*C3,1)", "3.8"),
        f("=ABS(C4)", "3.5"),
        f("=ABS(A6)", "#VALUE!"),
        // references
        f("=\$A\$2+\$B\$2", "13"),
        f("=A\$2*2", "6"),
        f("=a2+b2", "13"),
        f("=C2*2", "3"),
        f("=A5+1", "1"),
        f("=B2/A5", "#DIV/0!"),
        f("=A6+1", "#VALUE!"),
        f("=A1", "h"),
        f("=A1+1", "#VALUE!"),
        f("=Z2", "#REF!"),
        f("=A100", "#REF!"),
        f("=SUM(Z2:Z5)", "0"),
        f("=COUNT(Z2:Z5)", "0"),
        f("=COUNTA(Z2:Z5)", "0"),
        f("=AVERAGE(Z2:Z5)", "#DIV/0!"),
        f("=MIN(Z2:Z5)", "0"),
        f("=MAX(Z2:Z5)", "0"),
        f("=SUM(A2:A3,Z2:Z5)", "4"),
        f("=SUM(Z2)", "#REF!"),
        f("=SUM(A2:A100)", "6"),
        // errors propagate
        f("=D2+1", "#DIV/0!"),
        f("=SUM(D2:D3)", "#DIV/0!"),
        // names and parse failures
        f("=FOO(1)", "#NAME?"),
        f("=SUM(A2:A4", "#PARSE!"),
        f("=1+", "#PARSE!"),
        f("=()", "#PARSE!"),
        f("=A2 B2", "#PARSE!"),
        f("=1.2.3", "#PARSE!"),
        // cycles
        f("=E7", "#CYCLE!"),
        f("=SUM(E2:E7)", "#CYCLE!"),
        f("=E2+1", "#CYCLE!", mapOf("E2" to "=E3", "E3" to "=E2"), at = "E4"),
        f("=E3", "#CYCLE!", mapOf("E2" to "=E3", "E3" to "=E2"), at = "E2"),
        f("=E3", "7", mapOf("E2" to "=A2*2", "E3" to "=E2+1")),
        f("=SUM(E2:E6)", "3", mapOf("E2" to "=1+1", "E3" to "=E2/2")),
        // number grammar in plain cells
        f("=A2+1", "1235", mapOf("A2" to "1,234")),
        f("=A2+1", "#VALUE!", mapOf("A2" to "1,23")),
        f("=A2", "0.5", mapOf("A2" to "50%")),
        f("=A2+1", "8", mapOf("A2" to "007")),
        f("=A2", "1000", mapOf("A2" to "1e3")),
        f("=A2*2", "1", mapOf("A2" to ".5")),
        f("=A2+1", "#VALUE!", mapOf("A2" to "NaN")),
        f("=A2+1", "#VALUE!", mapOf("A2" to "Infinity")),
        f("=A2+1", "#VALUE!", mapOf("A2" to "1d")),
        f("=A2+1", "#VALUE!", mapOf("A2" to "0x10")),
        // limits
        f("=1" + "+1".repeat(600), "#LIMIT!"),
        f("=" + "(".repeat(65) + "1" + ")".repeat(65), "#LIMIT!"),
    )

    @Test
    fun golden_should_Pass60IndependentlyAuthoredCases_When_RunAgainstTableCalc() {
        assertTrue(cases.size >= 60, "corpus has ${cases.size} cases")
        val failures = cases.mapNotNull { c ->
            val actual = run(c)
            if (actual == c.expected) null else "${c.formula.take(60)} [${c.overrides}] at ${c.at}: expected ${c.expected} got $actual"
        }
        assertEquals(emptyList(), failures, "${failures.size} of ${cases.size} golden cases disagree")
    }

    private class Rewrite(val formula: String, val edit: TableEdit, val expected: String?)

    // ADR-003: insert shifts at-or-after, delete shrinks ranges and poisons dead refs, move follows the data.
    private val rewrites = listOf(
        Rewrite("=SUM(B2:D2)", TableEdit.DeleteCols(at = 2), "=SUM(B2:C2)"),
        Rewrite("=SUM(A2:B2)", TableEdit.DeleteCols(at = 2), "=SUM(A2:A2)"),
        Rewrite("=SUM(C2:E2)", TableEdit.DeleteCols(at = 1), "=SUM(B2:D2)"),
        Rewrite("=SUM(B2:B5)", TableEdit.DeleteRows(at = 2), "=SUM(B2:B4)"),
        Rewrite("=SUM(B2:B5)", TableEdit.DeleteRows(at = 5), "=SUM(B2:B4)"),
        Rewrite("=SUM(B2:B5)", TableEdit.DeleteRows(at = 3), "=SUM(B2:B4)"),
        Rewrite("=SUM(B3:B4)", TableEdit.DeleteRows(at = 3, count = 2), "=SUM(#REF!)"),
        Rewrite("=B3*2", TableEdit.DeleteRows(at = 3), "=#REF!*2"),
        Rewrite("=B4*2", TableEdit.DeleteRows(at = 3), "=B3*2"),
        Rewrite("=B2*2", TableEdit.DeleteRows(at = 3), null),
        Rewrite("=B2", TableEdit.InsertRows(before = 2), "=B3"),
        Rewrite("=B2", TableEdit.InsertRows(before = 3), null),
        Rewrite("=SUM(B2:B5)", TableEdit.InsertRows(before = 4), "=SUM(B2:B6)"),
        Rewrite("=SUM(B2:B5)", TableEdit.InsertRows(before = 6), null),
        Rewrite("=SUM(B2:B5)", TableEdit.InsertCols(before = 1), "=SUM(C2:C5)"),
        Rewrite("=\$B\$2+C\$3", TableEdit.InsertRows(before = 2), "=\$B\$3+C\$4"),
        Rewrite("=B2+B3", TableEdit.MoveRows(from = 2, count = 1, before = 4), "=B3+B2"),
        Rewrite("=SUM(B2:B3)", TableEdit.MoveRows(from = 2, count = 1, before = 4), null),
        Rewrite("=A2+B2", TableEdit.MoveCols(from = 1, count = 1, before = 3), "=B2+A2"),
        Rewrite("=B2*2", TableEdit.SortMove(fromRow = 2, toRow = 4), "=B4*2"),
        Rewrite("=SUM(B2:B5)", TableEdit.SortMove(fromRow = 2, toRow = 4), null),
    )

    @Test
    fun golden_should_RewriteReferencesPerAdr003_When_StructuralEdit() {
        val problems = rewrites.mapNotNull { r ->
            val actual = RefRewriter.rewrite(r.formula, r.edit)
            if (actual == r.expected) null else "${r.formula} ${r.edit}: expected ${r.expected} got $actual"
        }
        assertEquals(emptyList(), problems)
    }

    private class Shape(val raw: String, val shaped: Boolean, val display: String)

    private val classification = listOf(
        Shape("=5 apples", true, "#PARSE!"),
        Shape("=2024 plan", true, "#PARSE!"),
        Shape("==", false, "=="),
        Shape("=#nope", false, "=#nope"),
        Shape("=== heading ===", false, "=== heading ==="),
        Shape("=", false, "="),
        Shape("=!", false, "=!"),
        Shape("=#REF!*2", true, "#REF!"),
        Shape("=SUM(#REF!)", true, "#REF!"),
        Shape("= 1+1", true, "2"),
        Shape("=(", true, "#PARSE!"),
        Shape("=.5", true, "0.5"),
        Shape("=-1", true, "-1"),
    )

    @Test
    fun golden_should_ClassifyTextStartingWithEquals_When_5ApplesAnd2024PlanAndDoubleEquals() {
        val problems = classification.mapNotNull { s ->
            val shaped = FormulaParser.isFormulaShaped(s.raw)
            val doc = build(mapOf("A2" to s.raw))
            val calc = TableCalc(doc)
            val display = calc.displayAt(CellAddress(2, 1))
            when {
                shaped != s.shaped -> "${s.raw}: shaped=$shaped expected ${s.shaped}"
                display != s.display -> "${s.raw}: display '$display' expected '${s.display}'"
                calc.rawAt(CellAddress(2, 1)) != s.raw -> "${s.raw}: raw text was altered"
                else -> null
            }
        }
        assertEquals(emptyList(), problems)
    }
}
