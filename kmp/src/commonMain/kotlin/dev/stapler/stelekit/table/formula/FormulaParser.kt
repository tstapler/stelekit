package dev.stapler.stelekit.table.formula

/** Allow-listed function; arity is advisory for the evaluator, the parser only checks the name. */
data class FunctionSpec(val name: String, val minArgs: Int, val maxArgs: Int)

/** Table-driven allow-list (ADR-002). Anything else parses to `#NAME?`. */
object FormulaFunctions {
    private val specs = listOf(
        FunctionSpec("SUM", 1, Int.MAX_VALUE),
        FunctionSpec("AVERAGE", 1, Int.MAX_VALUE),
        FunctionSpec("MIN", 1, Int.MAX_VALUE),
        FunctionSpec("MAX", 1, Int.MAX_VALUE),
        FunctionSpec("COUNT", 1, Int.MAX_VALUE),
        FunctionSpec("COUNTA", 1, Int.MAX_VALUE),
        FunctionSpec("IF", 2, 3),
        FunctionSpec("ROUND", 1, 2),
        FunctionSpec("ABS", 1, 1),
    ).associateBy { it.name }

    val names: Set<String> get() = specs.keys

    fun lookup(name: String): FunctionSpec? = specs[name.uppercase()]
}

enum class FormulaParseError(val display: String) {
    Parse("#PARSE!"),
    Name("#NAME?"),
    Limit("#LIMIT!"),
}

sealed interface FormulaParse {
    data class Ok(val expr: Expr) : FormulaParse
    data class Failure(val error: FormulaParseError) : FormulaParse
}

/** Pratt parser for the closed grammar. Never throws; recursion is bounded by [MAX_DEPTH]. */
object FormulaParser {
    const val MAX_LENGTH = 1000
    const val MAX_DEPTH = 64

    // Binding powers: precedence low to high. Unary minus sits below `^` so `-2^2` is -4 (ADR-002).
    private const val BP_COMPARE = 10
    private const val BP_ADD = 20
    private const val BP_MUL = 30
    private const val BP_UNARY = 40
    private const val BP_POW = 50
    private const val BP_PERCENT = 60

    /** `=` at index 0 and the next non-space token can start an expression (ADR-002). */
    fun isFormulaShaped(text: String): Boolean {
        if (text.isEmpty() || text[0] != '=') return false
        var i = 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return false
        val c = text[i]
        return c.isDigit() || c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == '.' ||
            c == '$' || c == '(' || c == '+' || c == '-' || c == '"' ||
            text.regionMatches(i, "#REF!", 0, 5, ignoreCase = true)
    }

    fun parse(text: String): FormulaParse {
        if (text.length > MAX_LENGTH) return FormulaParse.Failure(FormulaParseError.Limit)
        if (!isFormulaShaped(text)) return FormulaParse.Failure(FormulaParseError.Parse)
        return try {
            FormulaParse.Ok(Session(Tokenizer.tokenize(text, from = 1)).parseAll())
        } catch (e: ParseFailure) {
            FormulaParse.Failure(e.error)
        }
    }

    private class ParseFailure(val error: FormulaParseError) : RuntimeException()

    private class Session(private val tokens: List<Token>) {
        private var pos = 0
        private var depth = 0

        private fun peek(): Token = tokens[pos]
        private fun next(): Token = tokens[pos].also { if (pos < tokens.size - 1) pos++ }
        private fun fail(error: FormulaParseError = FormulaParseError.Parse): Nothing = throw ParseFailure(error)
        private fun isOp(t: Token, symbol: String) = t is Token.Op && t.symbol == symbol

        fun parseAll(): Expr {
            val expr = parseExpr(0)
            if (peek() !is Token.End) fail()
            return expr
        }

        private fun parseExpr(minBp: Int): Expr {
            if (++depth > MAX_DEPTH) fail(FormulaParseError.Limit)
            try {
                var left = prefix()
                while (true) {
                    val op = peek() as? Token.Op ?: break
                    val bp = infixPower(op.symbol)
                    if (bp < minBp) break
                    next()
                    left = if (op.symbol == "%") {
                        Expr.Pct(left, Span(left.span.start, op.end))
                    } else {
                        // `^` is right-associative, everything else left-associative.
                        val right = parseExpr(if (op.symbol == "^") bp else bp + 1)
                        Expr.Bin(binOp(op.symbol), left, right, Span(left.span.start, right.span.end))
                    }
                }
                return left
            } finally {
                depth--
            }
        }

        private fun infixPower(symbol: String): Int = when (symbol) {
            "=", "<>", "<", "<=", ">", ">=" -> BP_COMPARE
            "+", "-" -> BP_ADD
            "*", "/" -> BP_MUL
            "^" -> BP_POW
            "%" -> BP_PERCENT
            else -> -1 // ')' ',' ':' '(' end the expression
        }

        private fun binOp(symbol: String): BinOp = BinOp.entries.first { it.symbol == symbol }

        private fun prefix(): Expr {
            val t = next()
            return when (t) {
                is Token.Number -> Expr.Num(t.value, Span(t.start, t.end))
                is Token.Text -> Expr.Str(t.value, Span(t.start, t.end))
                is Token.RefErr -> Expr.RefError(Span(t.start, t.end))
                is Token.CellRef -> refOrRange(t)
                is Token.Ident -> call(t)
                is Token.Op -> when (t.symbol) {
                    "-" -> parseExpr(BP_UNARY).let { Expr.Neg(it, Span(t.start, it.span.end)) }
                    "+" -> parseExpr(BP_UNARY) // a leading plus is a no-op
                    "(" -> {
                        val inner = parseExpr(0)
                        if (!isOp(next(), ")")) fail()
                        inner
                    }
                    else -> fail()
                }
                else -> fail()
            }
        }

        private fun toRef(t: Token.CellRef) = Expr.Ref(t.col, t.row, t.absCol, t.absRow, Span(t.start, t.end))

        private fun refOrRange(t: Token.CellRef): Expr {
            val from = toRef(t)
            if (!isOp(peek(), ":")) return from
            next()
            val end = next() as? Token.CellRef ?: fail()
            val to = toRef(end)
            return Expr.Range(from, to, Span(from.span.start, to.span.end))
        }

        private fun call(t: Token.Ident): Expr {
            if (!isOp(peek(), "(")) fail(FormulaParseError.Name)
            val spec = FormulaFunctions.lookup(t.name) ?: fail(FormulaParseError.Name)
            next()
            val args = ArrayList<Expr>()
            if (isOp(peek(), ")")) {
                val close = next()
                return Expr.Call(spec.name, args, Span(t.start, close.end))
            }
            while (true) {
                args.add(parseExpr(0))
                val sep = next()
                if (isOp(sep, ")")) return Expr.Call(spec.name, args, Span(t.start, sep.end))
                if (!isOp(sep, ",")) fail()
            }
        }
    }
}
