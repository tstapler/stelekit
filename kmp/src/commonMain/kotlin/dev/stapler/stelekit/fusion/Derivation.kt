package dev.stapler.stelekit.fusion

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import dev.stapler.stelekit.units.ArithmeticError
import dev.stapler.stelekit.units.Rational

/** A value with the half-width of its uncertainty. */
data class Quantity(val value: Rational, val precision: Rational)

sealed interface DerivationExpr {
    data class Literal(val value: Rational) : DerivationExpr

    data class Ref(val key: String) : DerivationExpr

    data class Negate(val operand: DerivationExpr) : DerivationExpr

    data class Binary(val op: Char, val left: DerivationExpr, val right: DerivationExpr) : DerivationExpr
}

sealed interface DerivationError {
    /** Position in the expression text of the first unexpected character. */
    data class Syntax(val index: Int) : DerivationError

    data class UnknownKey(val key: String) : DerivationError

    data class Cycle(val keys: List<String>) : DerivationError

    data object DivideByZero : DerivationError

    data class Arithmetic(val error: ArithmeticError) : DerivationError
}

/**
 * The registry's derivation language: `+ - * /`, parentheses, rational literals and references
 * to other dimension keys (`radius*2`, or `{rod-carrier.count}*2` for keys with other
 * characters). It is a closed grammar over [Rational]; nothing is evaluated as code.
 *
 * Precision propagates to first order: sums add, a product adds each operand's precision scaled
 * by the other's magnitude.
 */
object Derivation {
    fun parse(text: String): Either<DerivationError, DerivationExpr> = either {
        val parser = ExprParser(text)
        val expr = with(parser) { expression() }
        parser.skipSpaces()
        if (!parser.atEnd) raise(DerivationError.Syntax(parser.position))
        expr
    }

    fun references(expr: DerivationExpr): Set<String> = when (expr) {
        is DerivationExpr.Literal -> emptySet()
        is DerivationExpr.Ref -> setOf(expr.key)
        is DerivationExpr.Negate -> references(expr.operand)
        is DerivationExpr.Binary -> references(expr.left) + references(expr.right)
    }

    /** [lookup] returns null for a key with no value, which is an [DerivationError.UnknownKey]. */
    fun evaluate(expr: DerivationExpr, lookup: (String) -> Quantity?): Either<DerivationError, Quantity> =
        either { eval(expr, lookup) }

    private fun <R> Raise<DerivationError>.exact(result: Either<ArithmeticError, R>): R =
        result.fold({ raise(DerivationError.Arithmetic(it)) }, { it })

    private fun Raise<DerivationError>.eval(expr: DerivationExpr, lookup: (String) -> Quantity?): Quantity =
        when (expr) {
            is DerivationExpr.Literal -> Quantity(expr.value, Rational.ZERO)
            is DerivationExpr.Ref -> lookup(expr.key) ?: raise(DerivationError.UnknownKey(expr.key))
            is DerivationExpr.Negate -> eval(expr.operand, lookup).let { Quantity(exact(it.value.negate()), it.precision) }
            is DerivationExpr.Binary -> combine(expr.op, eval(expr.left, lookup), eval(expr.right, lookup))
        }

    private fun Raise<DerivationError>.combine(op: Char, a: Quantity, b: Quantity): Quantity = when (op) {
        '+' -> Quantity(exact(a.value.add(b.value)), exact(a.precision.add(b.precision)))
        '-' -> Quantity(exact(a.value.subtract(b.value)), exact(a.precision.add(b.precision)))
        '*' -> Quantity(exact(a.value.multiply(b.value)), productPrecision(a, b))
        else -> {
            if (b.value.isZero) raise(DerivationError.DivideByZero)
            val value = exact(a.value.divide(b.value))
            val absB = exact(b.value.abs())
            val fromA = exact(a.precision.divide(absB))
            val fromB = exact(exact(exact(a.value.abs()).multiply(b.precision)).divide(exact(absB.multiply(absB))))
            Quantity(value, exact(fromA.add(fromB)))
        }
    }

    private fun Raise<DerivationError>.productPrecision(a: Quantity, b: Quantity): Rational {
        val fromA = exact(exact(b.value.abs()).multiply(a.precision))
        val fromB = exact(exact(a.value.abs()).multiply(b.precision))
        return exact(fromA.add(fromB))
    }

    private class ExprParser(private val text: String) {
        var position = 0
            private set
        val atEnd: Boolean get() = position >= text.length

        fun skipSpaces() {
            while (!atEnd && text[position].isWhitespace()) position++
        }

        private fun peekChar(): Char? {
            skipSpaces()
            return text.getOrNull(position)
        }

        fun Raise<DerivationError>.expression(): DerivationExpr {
            var left = term()
            while (true) {
                val op = peekChar()?.takeIf { it == '+' || it == '-' } ?: return left
                position++
                left = DerivationExpr.Binary(op, left, term())
            }
        }

        private fun Raise<DerivationError>.term(): DerivationExpr {
            var left = unary()
            while (true) {
                val op = peekChar()?.takeIf { it == '*' || it == '/' } ?: return left
                position++
                left = DerivationExpr.Binary(op, left, unary())
            }
        }

        private fun Raise<DerivationError>.unary(): DerivationExpr {
            if (peekChar() == '-') {
                position++
                return DerivationExpr.Negate(unary())
            }
            return primary()
        }

        private fun Raise<DerivationError>.primary(): DerivationExpr {
            val c = peekChar() ?: raise(DerivationError.Syntax(position))
            return when {
                c == '(' -> {
                    position++
                    val inner = expression()
                    if (peekChar() != ')') raise(DerivationError.Syntax(position))
                    position++
                    inner
                }
                c == '{' -> braced()
                c in '0'..'9' || c == '.' -> number()
                c.isLetter() || c == '_' -> identifier()
                else -> raise(DerivationError.Syntax(position))
            }
        }

        private fun Raise<DerivationError>.braced(): DerivationExpr {
            val close = text.indexOf('}', position)
            if (close < 0 || close == position + 1) raise(DerivationError.Syntax(position))
            val key = text.substring(position + 1, close)
            position = close + 1
            return DerivationExpr.Ref(key)
        }

        private fun isIdentifierChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.'

        private fun identifier(): DerivationExpr {
            val start = position
            while (!atEnd && isIdentifierChar(text[position])) position++
            return DerivationExpr.Ref(text.substring(start, position))
        }

        private fun Raise<DerivationError>.number(): DerivationExpr {
            val start = position
            while (!atEnd && text[position] in '0'..'9') position++
            val whole = text.substring(start, position)
            var frac = ""
            if (!atEnd && text[position] == '.') {
                position++
                val fracStart = position
                while (!atEnd && text[position] in '0'..'9') position++
                frac = text.substring(fracStart, position)
                if (frac.isEmpty()) raise(DerivationError.Syntax(position))
            }
            val digits = (whole + frac).trimStart('0')
            if (digits.length > MAX_DIGITS || frac.length > MAX_DIGITS - 1) raise(DerivationError.Arithmetic(ArithmeticError.Overflow))
            var scale = 1L
            repeat(frac.length) { scale *= 10L }
            val numerator = if (digits.isEmpty()) 0L else digits.toLong()
            val value = Rational.of(numerator, scale).fold({ raise(DerivationError.Arithmetic(it)) }, { it })
            return DerivationExpr.Literal(value)
        }
    }

    private const val MAX_DIGITS = 18
}
