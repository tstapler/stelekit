package dev.stapler.stelekit.table.formula

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FormulaParserTest {
    private fun ok(text: String): Expr = assertIs<FormulaParse.Ok>(FormulaParser.parse(text), text).expr

    private fun failure(text: String): FormulaParseError =
        assertIs<FormulaParse.Failure>(FormulaParser.parse(text), text).error

    /** Minimal numeric fold, test-only: the real evaluator is Task 2.2a. */
    private fun fold(e: Expr): Double = when (e) {
        is Expr.Num -> e.value
        is Expr.Neg -> -fold(e.operand)
        is Expr.Pct -> fold(e.operand) / 100
        is Expr.Bin -> {
            val l = fold(e.left)
            val r = fold(e.right)
            when (e.op) {
                BinOp.Add -> l + r
                BinOp.Sub -> l - r
                BinOp.Mul -> l * r
                BinOp.Div -> l / r
                BinOp.Pow -> l.pow(r)
                else -> error("comparison not folded")
            }
        }
        else -> error("not numeric: $e")
    }

    private fun containsRefError(e: Expr): Boolean = when (e) {
        is Expr.RefError -> true
        is Expr.Bin -> containsRefError(e.left) || containsRefError(e.right)
        is Expr.Neg -> containsRefError(e.operand)
        is Expr.Pct -> containsRefError(e.operand)
        is Expr.Call -> e.args.any(::containsRefError)
        else -> false
    }

    @Test
    fun parse_should_ApplyPrecedenceAndAssociativity_When_UnaryMinusPowerSubtraction() {
        assertEquals(-4.0, fold(ok("=-2^2")))
        assertEquals(512.0, fold(ok("=2^3^2")))
        assertEquals(3.0, fold(ok("=10-4-3")))
        assertEquals(7.0, fold(ok("=1+2*3")))
        assertEquals(9.0, fold(ok("=(1+2)*3")))
        assertEquals(0.5, fold(ok("=50%")))
        assertEquals(0.25, fold(ok("=2^-2")))
        assertEquals(-6.0, fold(ok("=-2*3")))
        assertEquals(1.5, fold(ok("=1+50%")))
        assertEquals(3.0, fold(ok("=+3")))
        val tree = ok("=10-4-3") as Expr.Bin
        assertIs<Expr.Bin>(tree.left) // left-associative: (10-4)-3
    }

    @Test
    fun parse_should_ReturnParseNameLimitErrors_When_MalformedUnknownOrTooLong() {
        assertEquals(FormulaParseError.Parse, failure("=SUM("))
        assertEquals(FormulaParseError.Name, failure("=FOO(1)"))
        assertEquals(FormulaParseError.Limit, failure("=" + "1+".repeat(2_500) + "1"))
        assertEquals(FormulaParseError.Parse, failure("=1+"))
        assertEquals(FormulaParseError.Parse, failure("=(1"))
        assertEquals(FormulaParseError.Parse, failure("=1 2"))
        assertEquals(FormulaParseError.Parse, failure("=\"open"))
        assertEquals(FormulaParseError.Parse, failure("=1;2"))
        assertEquals(FormulaParseError.Parse, failure("=A1:"))
        assertEquals(FormulaParseError.Name, failure("=foo"))
        assertEquals(FormulaParseError.Name, failure("=ABC1+1"))
        assertEquals("#PARSE!", FormulaParseError.Parse.display)
        assertEquals("#NAME?", FormulaParseError.Name.display)
        assertEquals("#LIMIT!", FormulaParseError.Limit.display)
        val atLimit = "=1" + "+1".repeat((FormulaParser.MAX_LENGTH - 2) / 2)
        assertEquals(FormulaParser.MAX_LENGTH, atLimit.length)
        assertIs<FormulaParse.Ok>(FormulaParser.parse(atLimit))
        assertEquals(FormulaParseError.Limit, failure(atLimit + "+"))
    }

    @Test
    fun parse_should_BuildClosedGrammarNodes_When_RefsRangesCallsAndStrings() {
        assertEquals(Expr.Ref(2, 3, absCol = false, absRow = false, span = Span(1, 3)), ok("=B3"))
        assertEquals(Expr.Ref(1, 1, absCol = true, absRow = true, span = Span(1, 5)), ok("=\$A\$1"))
        val range = ok("=a1:zz99") as Expr.Range
        assertEquals(702, range.to.col)
        val call = ok("=sum(B2:B5, 1)") as Expr.Call
        assertEquals("SUM", call.name)
        assertEquals(2, call.args.size)
        assertEquals(Span(1, 14), call.span)
        assertEquals(Expr.Str("a\"b", Span(1, 7)), ok("=\"a\"\"b\""))
        assertEquals(BinOp.Ge, (ok("=A1>=2") as Expr.Bin).op)
        assertEquals(BinOp.Ne, (ok("=A1<>2") as Expr.Bin).op)
        assertEquals(0, (ok("=SUM()") as Expr.Call).args.size)
        assertEquals(1.5e3, fold(ok("=1.5e3")))
        assertEquals(0.5, fold(ok("=.5")))
    }

    @Test
    fun formulaShaped_should_ClassifyHashRefAndLiteral_When_RewrittenOrPlainText() {
        for (text in listOf("=#REF!*2", "=SUM(#REF!)", "=#ref!", "=1", "= A1", "=-1", "=(1)", "=\"x\"", "=SUM(B2)")) {
            assertTrue(FormulaParser.isFormulaShaped(text), text)
        }
        for (text in listOf("=#nope", "=== heading ===", "=", "= ", "a=1", "", " =1", "=*2", "=%")) {
            assertFalse(FormulaParser.isFormulaShaped(text), text)
        }
        assertTrue(containsRefError(ok("=#REF!*2")))
        assertTrue(containsRefError(ok("=SUM(#REF!)")))
        assertEquals(Expr.RefError(Span(1, 6)), ok("=#REF!"))
        assertEquals(FormulaParseError.Parse, failure("=#nope"))
    }

    @Test
    fun parse_should_RejectNonAllowListFunctionsAndExternalTokens_When_FileNetworkNames() {
        assertEquals(FormulaParseError.Name, failure("=HYPERLINK(\"http://x\",\"y\")"))
        assertEquals(FormulaParseError.Name, failure("=IMPORTDATA(\"http://x\")"))
        assertEquals(FormulaParseError.Name, failure("=INDIRECT(\"A1\")"))
        assertEquals(setOf("SUM", "AVERAGE", "MIN", "MAX", "COUNT", "COUNTA", "IF", "ROUND", "ABS"), FormulaFunctions.names)
        for (name in FormulaFunctions.names) {
            assertIs<FormulaParse.Ok>(FormulaParser.parse("=${name.lowercase()}(1)"), name)
        }
        assertEquals(FormulaParseError.Parse, failure("='Sheet1'!A1"))
        assertEquals(FormulaParseError.Name, failure("=Sheet1!A1"))
    }

    @Test
    fun parse_should_ReturnLimitWithinDepth64_When_10000NestedParens() {
        val huge = "=" + "(".repeat(10_000) + "1" + ")".repeat(10_000)
        assertEquals(FormulaParseError.Limit, failure(huge))

        fun nested(n: Int) = "=" + "(".repeat(n) + "1" + ")".repeat(n)
        assertIs<FormulaParse.Ok>(FormulaParser.parse(nested(FormulaParser.MAX_DEPTH - 1)))
        assertEquals(FormulaParseError.Limit, failure(nested(FormulaParser.MAX_DEPTH)))
        assertEquals(FormulaParseError.Limit, failure("=" + "-".repeat(500) + "1"))
        assertEquals(FormulaParseError.Limit, failure("=" + "2^".repeat(300) + "2"))
        assertEquals(FormulaParseError.Limit, failure("=" + "SUM(".repeat(200) + "1" + ")".repeat(200)))
        // Flat chains are iterative and must not count toward depth.
        assertIs<FormulaParse.Ok>(FormulaParser.parse("=" + "1+".repeat(499) + "1"))
    }
}
