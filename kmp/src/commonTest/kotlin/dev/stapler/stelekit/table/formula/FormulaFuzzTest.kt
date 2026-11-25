package dev.stapler.stelekit.table.formula

import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FormulaFuzzTest {
    private val alphabet = "=0123456789+-*/^%()<>:,.\$ \"ABCZabcz#REF!SUMIFAVERAGEROUNDABSCOUNTMINMAX_;'&\u0663\u0661\u0967\uFF11\u00B2"
    private val fragments = listOf("SUM(", "IF(", "A1:B2", "\$A\$1", "#REF!", "ROUND(", ")", "1e308", "99999999999", "ZZ9999", "\"", "<>", ">=", "^^", "%%", "A\u0663", "\u0661.\u0662", "SUM(A\u0661:B\u0662)", "\$A\$\u0663")

    private fun randomString(rnd: Random): String {
        val sb = StringBuilder("=")
        repeat(rnd.nextInt(0, 40)) {
            if (rnd.nextInt(5) == 0) sb.append(fragments[rnd.nextInt(fragments.size)]) else sb.append(alphabet[rnd.nextInt(alphabet.length)])
        }
        return sb.toString()
    }

    private fun doc(formula: String): TableDoc {
        val lines = listOf("| h | h | h |", "|---|---|---|", "| 1 | 2 | 3 |", "| 4 | x |  |", "|  |  | $formula |")
        return assertIs<TableParseResult.Parsed>(TableParser.parse(lines.joinToString("\n"))).doc
    }

    private val resolver = object : CellResolver {
        override val rowCount = 4
        override val colCount = 3
        override fun valueAt(col: Int, row: Int): CellValue = if (row == 2) CellValue.Num(col.toDouble()) else CellValue.Blank
    }

    @Test
    fun fuzz_should_NeverThrow_When_5000RandomFormulaAlphabetStrings() {
        val rnd = Random(20261124)
        repeat(5000) {
            val s = randomString(rnd)
            when (val parsed = FormulaParser.parse(s)) {
                is FormulaParse.Ok -> {
                    FormulaEvaluator().evaluate(parsed.expr, resolver)
                    FormulaPrinter.print(parsed.expr)
                }
                is FormulaParse.Failure -> Unit
            }
            FormulaParser.isFormulaShaped(s)
            RefRewriter.rewrite(s, TableEdit.InsertRows(before = 2))
        }
    }

    @Test
    fun fuzz_should_ReturnValueOrError_When_5000RandomStrings() {
        val rnd = Random(77)
        repeat(5000) {
            val s = randomString(rnd)
            // pipes and newlines cannot occur in a table cell; the alphabet has none
            val calc = TableCalc(doc(s))
            val v = calc.valueAt(CellAddress(4, 3))
            assertEquals(v, calc.valueAt(CellAddress(4, 3)), "stable for '$s'")
            calc.displayAt(CellAddress(4, 3))
        }
    }

    @Test
    fun parse_should_RejectNonAsciiDigits_When_RowOrNumberUsesUnicodeDigits() {
        for (f in listOf("=A\u0663", "=\u0661+\u0662", "=SUM(A\u0661:B\u0662)", "=ROUND(1,\u0662)", "=A\u0967", "=A\uFF11", "=1.\u0665")) {
            val parsed = FormulaParser.parse(f)
            assertIs<FormulaParse.Failure>(parsed, "'$f' must not parse")
            RefRewriter.rewrite(f, TableEdit.InsertRows(before = 2))
            FormulaParser.isFormulaShaped(f)
        }
    }
}
