package dev.stapler.stelekit.table.formula

import dev.stapler.stelekit.table.NumberGrammar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class EvaluatorTest {
    /** Grid of raw values, row-major, row 1 first; counts reads so clamping is observable. */
    private class Grid(val rows: Int, val cols: Int, val cells: Map<Pair<Int, Int>, CellValue> = emptyMap()) : CellResolver {
        var reads = 0
        override val rowCount get() = rows
        override val colCount get() = cols
        override fun valueAt(col: Int, row: Int): CellValue {
            reads++
            return cells[col to row] ?: CellValue.Blank
        }
    }

    private fun grid(rows: Int, cols: Int, vararg cells: Triple<String, Int, String>): Grid =
        Grid(rows, cols, cells.associate { (c, r, raw) -> (c[0] - 'A' + 1) to r to CellValue.fromRaw(raw) })

    private fun eval(text: String, g: Grid = Grid(10, 10)): CellValue {
        val parsed = FormulaParser.parse(text)
        if (parsed is FormulaParse.Failure) return CellValue.Err(parsed.error.toCellError())
        return FormulaEvaluator().evaluate((parsed as FormulaParse.Ok).expr, g)
    }

    private fun num(d: Double) = CellValue.Num(d)
    private fun err(e: CellError) = CellValue.Err(e)

    @Test
    fun evaluate_should_FollowAdr002Semantics_When_BlankTextAndErrorsMix() {
        val g = grid(6, 4, Triple("B", 2, "3"), Triple("B", 3, "5"), Triple("B", 4, ""), Triple("B", 5, "x"))
        assertEquals(num(8.0), eval("=SUM(B2:B5)", g))
        assertEquals(err(CellError.DivZero), eval("=AVERAGE(D2:D5)", g))
        assertEquals(err(CellError.Value), eval("=B2+B5", g))
        assertEquals(num(3.0), eval("=B2+B4", g)) // blank is 0 in arithmetic
        assertEquals(num(0.0), eval("=SUM(D2:D5)", g))
        assertEquals(num(0.0), eval("=MIN(D2:D5)", g))
        assertEquals(num(0.0), eval("=MAX(D2:D5)", g))
        assertEquals(num(4.0), eval("=AVERAGE(B2:B5)", g))
        assertEquals(num(3.0), eval("=MIN(B2:B5)", g))
        assertEquals(num(5.0), eval("=MAX(B2:B5)", g))
        assertEquals(num(2.0), eval("=COUNT(B2:B5)", g))
        assertEquals(num(3.0), eval("=COUNTA(B2:B5)", g))
        assertEquals(err(CellError.DivZero), eval("=1/0"))
        assertEquals(err(CellError.DivZero), eval("=B2/B4", g))
        assertEquals(err(CellError.Ref), eval("=#REF!*2"))
        assertEquals(err(CellError.Ref), eval("=SUM(#REF!)"))
        assertEquals(err(CellError.Ref), eval("=Z99", g))
        assertEquals(err(CellError.Value), eval("=B2:B3", g))
        assertEquals(err(CellError.Value), eval("=SUM(\"x\")"))
        assertEquals(num(0.0), eval("=B4", g)) // a blank result shows 0
    }

    @Test
    fun evaluate_should_ApplyPrecedenceFunctionsAndComparisons_When_Evaluated() {
        assertEquals(num(4.0), eval("=-2^2"))
        assertEquals(num(64.0), eval("=2^3^2"))
        assertEquals(num(512.0), eval("=2^(3^2)"))
        assertEquals(num(3.0), eval("=10-4-3"))
        assertEquals(num(0.25), eval("=2^-2"))
        assertEquals(num(1.5), eval("=1+50%"))
        assertEquals(num(1.0), eval("=2>1"))
        assertEquals(num(0.0), eval("=2<=1"))
        assertEquals(num(1.0), eval("=\"abc\"=\"ABC\""))
        assertEquals(num(1.0), eval("=\"a\">5"))
        assertEquals(CellValue.Text("yes"), eval("=IF(1,\"yes\",\"no\")"))
        assertEquals(CellValue.Text("no"), eval("=IF(0,\"yes\",\"no\")"))
        assertEquals(num(0.0), eval("=IF(0,1)"))
        assertEquals(err(CellError.Value), eval("=IF(\"x\",1,2)"))
        assertEquals(num(1.0), eval("=IF(1,1,1/0)")) // the untaken branch is never evaluated
        assertEquals(num(2.68), eval("=ROUND(2.675,2)"))
        assertEquals(num(-2.0), eval("=ROUND(-1.5)"))
        assertEquals(num(1200.0), eval("=ROUND(1234,-2)"))
        assertEquals(num(7.0), eval("=ABS(-7)"))
        assertEquals(err(CellError.Parse), eval("=ABS(1,2)"))
        assertEquals(err(CellError.Parse), eval("=IF(1)"))
        assertEquals(err(CellError.DivZero), eval("=0^-1"))
        assertEquals("#NUM!", (eval("=(-8)^(1/3)") as CellValue.Err).error.display)
        assertEquals("#NUM!", (eval("=10^400") as CellValue.Err).error.display)
    }

    @Test
    fun evaluate_should_ClampRangeAndReturnLimit_When_HugeRangeOrOverBudget() {
        val three = Grid(3, 3, (1..3).flatMap { c -> (1..3).map { r -> (c to r) to num(1.0) } }.toMap())
        assertEquals(num(9.0), eval("=SUM(A1:ZZ99999)", three))
        assertEquals(9, three.reads)

        val big = Grid(9_000, 2, (1..9_000).flatMap { r -> (1..2).map { c -> (c to r) to num(1.0) } }.toMap())
        assertEquals(err(CellError.Limit), eval("=SUM(A1:A9000)+SUM(B1:B9000)", big))
        assertEquals(num(9_000.0), eval("=SUM(A1:A9000)", big))
        // Exactly at the budget passes, one over fails, and the same text gives the same answer every time.
        val tall = Grid(10_001, 1, (1..10_001).associate { (1 to it) to num(1.0) })
        assertEquals(num(10_000.0), eval("=SUM(A1:A10000)", tall))
        assertEquals(err(CellError.Limit), eval("=SUM(A1:A10001)", tall))
        assertEquals(err(CellError.Limit), eval("=SUM(A1:A10001)", tall))
        // Reads are charged per formula, so a limit hit never reads the oversized range.
        tall.reads = 0
        eval("=SUM(A1:A10001)", tall)
        assertEquals(0, tall.reads)
        // A range wholly outside the table is empty (Excel semantics); a single-cell ref outside stays #REF!.
        assertEquals(num(0.0), eval("=SUM(D1:E5)", three))
        assertEquals(num(0.0), eval("=COUNT(D1:E5)", three))
        assertEquals(num(0.0), eval("=MIN(D1:E5)", three))
        assertEquals(num(0.0), eval("=MAX(D1:E5)", three))
        assertEquals(err(CellError.DivZero), eval("=AVERAGE(D1:E5)", three))
        assertEquals(num(9.0), eval("=SUM(A1:C3,D1:E5)", three))
        assertEquals(err(CellError.Ref), eval("=D1", three))
        assertEquals(err(CellError.Ref), eval("=SUM(D1)", three))
    }

    @Test
    fun evaluate_should_HandleLeftDeepChainsWithoutDeepRecursion_When_FlatFormulaAtLengthCap() {
        val flat = "=" + "1+".repeat(499) + "1"
        assertEquals(num(500.0), eval(flat))
        assertEquals(num(1.0), eval("=" + "-".repeat(30) + "1"))
    }

    @Test
    fun numberGrammar_should_AcceptInvariantDecimalPercentAndStrictThousands_When_Parsed() {
        val accepted = mapOf(
            "5" to 5.0, "007" to 7.0, "-3.5" to -3.5, "+2" to 2.0, ".5" to 0.5, "5." to 5.0, "1e3" to 1000.0,
            "1.5E-2" to 0.015, "1,234" to 1234.0, "12,345,678.9" to 12345678.9, "50%" to 0.5, " 4 " to 4.0,
            "-1,000%" to -10.0, "1,234e1" to 12340.0,
        )
        for ((raw, v) in accepted) assertEquals(v, NumberGrammar.parse(raw), raw)
        for (
            raw in listOf(
                "", " ", "NaN", "Infinity", "-Infinity", "0x1F", "0x1.8p3", "1d", "1f", "1D", "1,23", "1,2345", ",5", "1,,000",
                "1,000,00", "1 000", "1_000", "abc", "1e", "e5", "--1", "1.2.3", "%", "5%%", "\$5", "1e400",
            )
        ) assertNull(NumberGrammar.parse(raw), raw)
        assertEquals(CellValue.Blank, CellValue.fromRaw("  "))
        assertEquals(CellValue.Text("x"), CellValue.fromRaw("x"))
        assertEquals(num(1234.0), CellValue.fromRaw("1,234"))

        assertEquals("0.3", NumberDisplay.format(0.1 + 0.2))
        assertEquals("0", NumberDisplay.format(0.0))
        assertEquals("0", NumberDisplay.format(-0.0))
        assertEquals("8", NumberDisplay.format(8.0))
        assertEquals("-1234.5", NumberDisplay.format(-1234.5))
        assertEquals("0.000001", NumberDisplay.format(0.000001))
        assertEquals("100000000000000", NumberDisplay.format(1e14))
        assertEquals("1E+15", NumberDisplay.format(1e15))
        assertEquals("1.5E-8", NumberDisplay.format(1.5e-8))
        assertEquals("0.333333333333", NumberDisplay.format(1.0 / 3.0))
        assertEquals("0.666666666667", NumberDisplay.format(2.0 / 3.0))
        assertEquals("1", NumberDisplay.format(0.9999999999999))
        assertEquals("12345.6789", NumberDisplay.format(12345.6789))
        assertEquals("123456789.123", NumberDisplay.format(123456789.1234567))
        assertEquals("2.68", NumberDisplay.format(2.68))
    }

    @Test
    fun evaluate_should_UseOverriddenFunctionImpl_When_RegistryReplacesAbs() {
        val registry = FunctionRegistry.default.with("ABS") { args -> (args[0] as EvalArg.Scalar).value }
        val parsed = FormulaParser.parse("=ABS(-3)") as FormulaParse.Ok
        assertEquals(num(-3.0), FormulaEvaluator(registry).evaluate(parsed.expr, Grid(2, 2)))
        assertIs<CellValue.Num>(FormulaEvaluator().evaluate(parsed.expr, Grid(2, 2)))
    }

    @Test
    fun round_should_NotNudgeLargeMagnitudes_When_ValueIsAtOrAbove1e14() {
        assertEquals(num(500000000000000.0), eval("=ROUND(500000000000000)"))
        assertEquals(num(1000000000000000.0), eval("=ROUND(1000000000000000)"))
        assertEquals(num(1234567890123.45), eval("=ROUND(1234567890123.454,2)"))
        assertEquals(num(1e300), eval("=ROUND(1e300,2)"))
    }

    @Test
    fun round_should_NotOverflowToError_When_ScaledValueExceedsDoubleRange() {
        assertEquals(num(1.7e308), eval("=ROUND(1.7E308,2)"))
        assertEquals(num(1.7e308), eval("=ROUND(1.7E308,15)"))
        assertEquals(num(0.0), eval("=ROUND(5e-324,15)"))
        assertEquals(num(0.0), eval("=ROUND(1234,-400)"))
    }

    @Test
    fun round_should_RoundHalfAwayFromZeroLikeASpreadsheet_When_BinaryValueIsJustBelowHalf() {
        assertEquals(num(1.01), eval("=ROUND(1.005,2)"))
        assertEquals(num(0.29), eval("=ROUND(0.285,2)"))
        assertEquals(num(1.5), eval("=ROUND(1.45,1)"))
        assertEquals(num(-2.68), eval("=ROUND(-2.675,2)"))
        assertEquals(num(1.0), eval("=ROUND(0.5)"))
        assertEquals(num(-1.0), eval("=ROUND(-0.5)"))
        assertEquals(num(1.0), eval("=ROUND(1.0049999999999997,2)"))
    }

    @Test
    fun round_should_MatchDecimalReference_When_GeneratedWithUpTo15Digits() {
        val rnd = kotlin.random.Random(15)
        repeat(3000) {
            val k = rnd.nextInt(0, 9) // fractional digits of the input
            val n = rnd.nextLong(0L, 1_000_000_000_000_000L) // < 10^15: exactly representable decimal digits
            val d = rnd.nextInt(0, k + 1)
            val neg = rnd.nextBoolean() && n != 0L
            val intPart = n / pow10(k)
            val frac = (n % pow10(k)).toString().padStart(k, '0')
            val literal = if (k == 0) "$intPart" else "$intPart.$frac"
            // reference: half away from zero on the exact decimal integer n
            val q = pow10(k - d)
            val m = (n + q / 2) / q
            val expectedText = if (d == 0) "$m" else m.toString().padStart(d + 1, '0').let { it.dropLast(d) + "." + it.takeLast(d) }
            val expected = expectedText.toDouble().let { if (neg) -it else it }
            val formula = "=ROUND(${if (neg) "-" else ""}$literal,$d)"
            assertEquals(if (expected == 0.0) 0.0 else expected, (eval(formula) as CellValue.Num).value, formula)
        }
    }

    private fun pow10(e: Int): Long {
        var r = 1L
        repeat(e) { r *= 10 }
        return r
    }

    @Test
    fun round_should_ReturnInputUntouched_When_DigitsAbove15() {
        assertEquals(eval("=1/3"), eval("=ROUND(1/3,20)"))
        assertEquals(eval("=1/3"), eval("=ROUND(1/3,16)"))
    }

    @Test
    fun round_should_NotDoubleRound_When_InputIsJustBelowHalf() {
        assertEquals(num(0.0), eval("=ROUND(0.49999999999999994)"))
        assertEquals(num(0.0), eval("=ROUND(0.49999999999999994,0)"))
        assertEquals(0.0, (eval("=ROUND(-0.49999999999999994)") as CellValue.Num).value)
    }
}
