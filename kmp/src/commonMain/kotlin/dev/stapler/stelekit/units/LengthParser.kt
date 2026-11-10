package dev.stapler.stelekit.units

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either

/*
 * Grammar (research/wiki-corpus-summary.md section 4). Tokens first, then recursive descent:
 *
 *   length      := qualifier* ( tolerance | body ) qualifier*
 *   tolerance   := "±" quantity
 *   body        := quantity ( rangeSep quantity | ( "x" quantity )+ | "(" quantity ")" )?
 *   quantity    := sign? ( feet inches? | inches )
 *   feet        := number ( "'" | "ft" | "foot" | "feet" ) ( WS | "-" )?
 *   inches      := number ( '"' | "in" | "inch" | "inches" )?
 *   number      := mixed | fraction | decimal | integer
 *   mixed       := integer ( WS | "-" )? ( integer "/" integer | vulgar )
 *   rangeSep    := "-" | "to"            (en/em dashes are normalised to "-" by the tokenizer)
 *
 * Disambiguation, numbered as in the corpus summary:
 *  1. integer "-" integer "/" integer is a mixed number; "-" after a feet mark is a separator;
 *     integer "-" integer with no fraction is a range.
 *  2. a parenthetical equivalent is a consistency check, not a second dimension.
 *  3. decimals with 4+ places that are dyadic are exact; shorter decimals carry half a unit in the
 *     last place as precision; fractions carry 1/denominator.
 *  4. the exact rational is kept; nothing is rounded.
 *  5. "x" between lengths is a size list; "#10 × 2.5"" and "W12x26" are not lengths.
 *  6. "~", "±", min, max, VIF, typ are metadata.
 *  7. bare numbers are inches only when the context says so.
 *  8. vulgar fractions and the prime marks are accepted; dashes are normalised to "-".
 */

private val QUALIFIER_WORDS = mapOf(
    "approx" to LengthQualifier.APPROX,
    "approximately" to LengthQualifier.APPROX,
    "about" to LengthQualifier.APPROX,
    "min" to LengthQualifier.MIN,
    "max" to LengthQualifier.MAX,
    "vif" to LengthQualifier.VIF,
    "typ" to LengthQualifier.TYP,
)
private val INCH_WORDS = setOf("in", "inch", "inches")
private val FEET_WORDS = setOf("ft", "foot", "feet")

private const val INCHES_PER_FOOT = 12L
private const val MAX_DECIMAL_DIGITS = 18
private const val DYADIC_EXACT_PLACES = 4

private enum class T { DIGITS, VULGAR, DOT, SLASH, HYPHEN, PLUS, FOOT, INCH, TILDE, PLUS_MINUS, LPAREN, RPAREN, HASH, WORD }

private class Token(val kind: T, val text: String, val ws: Boolean, val vulgar: Pair<Long, Long>? = null)

private val VULGARS = mapOf(
    '½' to (1L to 2L), '¼' to (1L to 4L), '¾' to (3L to 4L),
    '⅛' to (1L to 8L), '⅜' to (3L to 8L), '⅝' to (5L to 8L), '⅞' to (7L to 8L),
)

private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

private fun tokenize(text: String): Either<ParseError, List<Token>> = either {
    val out = ArrayList<Token>()
    var i = 0
    var ws = false
    while (i < text.length) {
        val c = text[i]
        if (c.isWhitespace()) {
            ws = true
            i++
            continue
        }
        var len = 1
        var kind: T
        var word: String? = null
        when {
            c in '0'..'9' -> {
                kind = T.DIGITS
                while (i + len < text.length && text[i + len] in '0'..'9') len++
            }
            isAsciiLetter(c) -> {
                kind = T.WORD
                while (i + len < text.length && isAsciiLetter(text[i + len])) len++
                word = text.substring(i, i + len).lowercase()
            }
            c == '×' -> { kind = T.WORD; word = "x" }
            c in VULGARS -> kind = T.VULGAR
            c == '.' -> kind = T.DOT
            c == '/' -> kind = T.SLASH
            c == '-' || c == '–' || c == '—' || c == '−' -> kind = T.HYPHEN
            c == '+' -> kind = T.PLUS
            c == '\'' && text.getOrNull(i + 1) == '\'' -> { kind = T.INCH; len = 2 }
            c == '\'' || c == '′' || c == '’' -> kind = T.FOOT
            c == '"' || c == '″' || c == '”' || c == '“' -> kind = T.INCH
            c == '~' || c == '≈' -> kind = T.TILDE
            c == '±' -> kind = T.PLUS_MINUS
            c == '(' -> kind = T.LPAREN
            c == ')' -> kind = T.RPAREN
            c == '#' -> kind = T.HASH
            else -> raise(ParseError.UnexpectedCharacter(i))
        }
        out.add(Token(kind, word ?: text.substring(i, i + len), ws, VULGARS[c]))
        i += len
        ws = false
    }
    out
}

private enum class Mark { NONE, INCH, FOOT }

private enum class UnitKind { NONE, INCHES, FEET_ONLY, FEET_INCHES }

private class Num(val value: Rational, val precision: Rational)

private class Quantity(val value: Rational, val precision: Rational, val unit: UnitKind)

/**
 * Parses one length written in the wiki's loose fractional-inch grammar into exact rational
 * inches. Never throws: every failure is a [ParseError], none of which carries input text.
 */
fun parseLength(text: String, ctx: LengthContext = LengthContext()): Either<ParseError, ParsedLength> = either {
    val tokens = tokenize(text).bind()
    if (tokens.isEmpty()) raise(ParseError.Empty)
    with(LengthParser(tokens, ctx)) { parseAll() }
}

/** Parses a small non-negative integer count: `6`, `qty 6`, `6 brackets`. Fractions and decimals are rejected. */
fun parseCount(text: String): Either<ParseError, Long> = either {
    val tokens = tokenize(text).bind()
    if (tokens.isEmpty()) raise(ParseError.Empty)
    var digits: Token? = null
    tokens.forEachIndexed { index, token ->
        when (token.kind) {
            T.WORD -> Unit
            T.DIGITS -> if (digits == null) digits = token else raise(ParseError.Unexpected(index))
            T.DOT, T.SLASH, T.VULGAR -> raise(ParseError.NotAnInteger)
            else -> raise(ParseError.Unexpected(index))
        }
    }
    val found = digits ?: raise(ParseError.Unexpected(0))
    found.text.toLongOrNull() ?: raise(ParseError.Arithmetic(ArithmeticError.Overflow))
}

private class LengthParser(private val tokens: List<Token>, private val ctx: LengthContext) {
    private var pos = 0

    private fun peek(offset: Int = 0): Token? = tokens.getOrNull(pos + offset)

    private fun <R> Raise<ParseError>.exact(result: Either<ArithmeticError, R>): R =
        result.fold({ raise(ParseError.Arithmetic(it)) }, { it })

    private fun isNumberStart(offset: Int): Boolean {
        val t = peek(offset) ?: return false
        return t.kind == T.DIGITS || t.kind == T.VULGAR ||
            (t.kind == T.DOT && peek(offset + 1)?.let { it.kind == T.DIGITS && !it.ws } == true)
    }

    private fun adjacent(offset: Int, kind: T): Boolean = peek(offset)?.let { it.kind == kind && !it.ws } == true

    fun Raise<ParseError>.parseAll(): ParsedLength {
        val qualifiers = linkedSetOf<LengthQualifier>()
        while (true) {
            val t = peek() ?: break
            if (t.kind == T.TILDE) qualifiers.add(LengthQualifier.APPROX)
            else if (t.kind == T.WORD && t.text in QUALIFIER_WORDS) qualifiers.add(QUALIFIER_WORDS.getValue(t.text))
            else break
            pos++
        }
        val first = peek() ?: raise(ParseError.Empty)
        if (first.kind == T.HASH) raise(ParseError.NotALength(NotALengthReason.SCREW_GAUGE))
        if (first.kind == T.WORD) {
            val reason = if (adjacent(1, T.DIGITS)) NotALengthReason.MEMBER_CODE else NotALengthReason.TEXT
            raise(ParseError.NotALength(reason))
        }
        val result: ParsedLength = if (first.kind == T.PLUS_MINUS) {
            pos++
            val amount = measured(listOf(quantity(allowSign = false))).single().value
            ParsedLength.Tolerance(exact(amount.abs()))
        } else {
            body()
        }
        while (true) {
            val t = peek() ?: break
            if (t.kind == T.WORD && t.text in QUALIFIER_WORDS) qualifiers.add(QUALIFIER_WORDS.getValue(t.text)) else break
            pos++
        }
        val rest = peek()
        if (rest != null) {
            val isExponent = rest.kind == T.WORD && rest.text == "e" && !rest.ws && tokens.getOrNull(pos - 1)?.kind == T.DIGITS
            raise(if (isExponent) ParseError.ExponentNotation else ParseError.Unexpected(pos))
        }
        return when (result) {
            is ParsedLength.Measured -> result.copy(qualifiers = qualifiers)
            is ParsedLength.Range -> result.copy(qualifiers = qualifiers)
            is ParsedLength.Tolerance -> result.copy(qualifiers = qualifiers)
            is ParsedLength.Consistency -> result.copy(qualifiers = qualifiers)
            is ParsedLength.SizeList -> result.copy(qualifiers = qualifiers)
        }
    }

    private fun Raise<ParseError>.body(): ParsedLength {
        val first = quantity(allowSign = true)
        val next = peek()
        return when {
            next == null -> measured(listOf(first)).single()
            next.kind == T.HYPHEN || (next.kind == T.WORD && next.text == "to") -> {
                pos++
                val (low, high) = measured(listOf(first, quantity(allowSign = false)))
                ParsedLength.Range(low, high)
            }
            next.kind == T.WORD && next.text == "x" -> {
                val items = arrayListOf(first)
                while (peek()?.let { it.kind == T.WORD && it.text == "x" } == true) {
                    pos++
                    items.add(quantity(allowSign = false))
                }
                // Size lists are lumber-style ("1½ x 3½ x 8'"): bare members stay inches.
                ParsedLength.SizeList(measured(items, bareTakesFeet = false))
            }
            next.kind == T.LPAREN -> {
                pos++
                val equivalent = quantity(allowSign = true)
                if (peek()?.kind != T.RPAREN) raise(ParseError.Unexpected(pos))
                pos++
                val primary = measured(listOf(first)).single()
                val other = measured(listOf(equivalent)).single()
                ParsedLength.Consistency(primary, other, agrees = primary.value == other.value)
            }
            else -> measured(listOf(first)).single()
        }
    }

    /**
     * Resolves bare numbers: they take the unit of a marked sibling (`51-54"`, `8-10'`), else
     * the context's (rule 7).
     */
    private fun Raise<ParseError>.measured(quantities: List<Quantity>, bareTakesFeet: Boolean = true): List<ParsedLength.Measured> {
        val reference = quantities.firstOrNull { it.unit != UnitKind.NONE }?.unit
        if (reference == null && ctx.unmarked == UnitContext.UNKNOWN) raise(ParseError.UnitRequired)
        val twelve = Rational(INCHES_PER_FOOT)
        return quantities.map { q ->
            if (bareTakesFeet && q.unit == UnitKind.NONE && reference == UnitKind.FEET_ONLY) {
                ParsedLength.Measured(exact(q.value.multiply(twelve)), exact(q.precision.multiply(twelve)))
            } else {
                ParsedLength.Measured(q.value, q.precision)
            }
        }
    }

    private fun Raise<ParseError>.quantity(allowSign: Boolean): Quantity {
        val sign = if (allowSign) signAhead() else null
        val negative = sign == T.HYPHEN
        if (sign != null) pos++
        val n = number()
        val mark = mark()
        var value = n.value
        var precision = n.precision
        var unit = if (mark == Mark.INCH) UnitKind.INCHES else UnitKind.NONE
        if (mark == Mark.FOOT) {
            val twelve = Rational(INCHES_PER_FOOT)
            val feetValue = exact(n.value.multiply(twelve))
            value = feetValue
            precision = exact(n.precision.multiply(twelve))
            unit = UnitKind.FEET_ONLY
            val afterFeet = pos
            if (peek()?.kind == T.HYPHEN) pos++
            if (isNumberStart(0)) {
                val inches = number()
                if (mark() == Mark.FOOT) {
                    // "8'-10'" is a range of feet, so the hyphen is a separator and "10" is not inches.
                    pos = afterFeet
                } else {
                    value = exact(feetValue.add(inches.value))
                    precision = inches.precision
                    unit = UnitKind.FEET_INCHES
                }
            } else {
                pos = afterFeet
            }
        }
        if (negative) value = exact(value.negate())
        return Quantity(value, precision, unit)
    }

    /** A leading "-" or "+" glued to the number that follows it. */
    private fun signAhead(): T? {
        val sign = peek()?.kind
        val isSign = sign == T.HYPHEN || sign == T.PLUS
        return if (isSign && isNumberStart(1) && peek(1)?.ws == false) sign else null
    }

    private fun mark(): Mark {
        val t = peek() ?: return Mark.NONE
        val found = when {
            t.kind == T.INCH || (t.kind == T.WORD && t.text in INCH_WORDS) -> Mark.INCH
            t.kind == T.FOOT || (t.kind == T.WORD && t.text in FEET_WORDS) -> Mark.FOOT
            else -> Mark.NONE
        }
        if (found != Mark.NONE) pos++
        return found
    }

    private fun Raise<ParseError>.number(): Num {
        val t = peek() ?: raise(ParseError.Unexpected(pos))
        return when (t.kind) {
            T.VULGAR -> {
                pos++
                vulgarOf(t, Rational.ZERO)
            }
            T.DOT -> {
                pos++
                val frac = peek()?.takeIf { it.kind == T.DIGITS && !it.ws } ?: raise(ParseError.Unexpected(pos))
                pos++
                decimal("", frac.text)
            }
            T.DIGITS -> {
                pos++
                digitsNumber(t)
            }
            else -> raise(ParseError.Unexpected(pos))
        }
    }

    private fun Raise<ParseError>.digitsNumber(whole: Token): Num {
        val wholeValue = integer(whole.text)
        val dot = peek()
        val fracDigits = peek(1)
        return when {
            dot != null && dot.kind == T.DOT && !dot.ws && fracDigits != null && fracDigits.kind == T.DIGITS && !fracDigits.ws -> {
                pos += 2
                decimal(whole.text, fracDigits.text)
            }
            adjacent(0, T.SLASH) && adjacent(1, T.DIGITS) -> fraction(wholeValue = null)
            peek()?.kind == T.VULGAR -> {
                val v = peek()!!
                pos++
                vulgarOf(v, wholeValue)
            }
            adjacent(0, T.HYPHEN) && peek(1)?.let { it.kind == T.VULGAR && !it.ws } == true -> {
                val v = peek(1)!!
                pos += 2
                vulgarOf(v, wholeValue)
            }
            // Rule 1: integer "-" integer "/" integer is a mixed number (not a range).
            adjacent(0, T.HYPHEN) && adjacent(1, T.DIGITS) && adjacent(2, T.SLASH) && adjacent(3, T.DIGITS) -> {
                pos++
                fraction(wholeValue)
            }
            peek()?.let { it.kind == T.DIGITS && it.ws } == true && adjacent(1, T.SLASH) && adjacent(2, T.DIGITS) -> fraction(wholeValue)
            else -> Num(wholeValue, Rational.ZERO)
        }
    }

    /** Parses `n/d` at the cursor (cursor on the numerator unless [wholeValue] is null, then on the slash). */
    private fun Raise<ParseError>.fraction(wholeValue: Rational?): Num {
        val numerator: Long
        if (wholeValue == null) {
            numerator = integerLong(tokens[pos - 1].text)
            pos++
        } else {
            numerator = integerLong(peek()!!.text)
            pos += 2
        }
        val den = integerLong(peek()!!.text)
        pos++
        if (den == 0L) raise(ParseError.DivideByZero)
        val part = exact(Rational.of(numerator, den))
        val value = if (wholeValue == null) part else exact(wholeValue.add(part))
        return Num(value, exact(Rational.of(1, den)))
    }

    private fun Raise<ParseError>.vulgarOf(token: Token, whole: Rational): Num {
        val (n, d) = token.vulgar ?: raise(ParseError.Unexpected(pos))
        return Num(exact(whole.add(Rational(n, d))), Rational(1, d))
    }

    private fun Raise<ParseError>.integerLong(digits: String): Long =
        if (digits.length > MAX_DECIMAL_DIGITS) raise(ParseError.Arithmetic(ArithmeticError.Overflow)) else digits.toLong()

    private fun Raise<ParseError>.integer(digits: String): Rational = exact(Rational.of(integerLong(digits), 1))

    private fun Raise<ParseError>.decimal(whole: String, frac: String): Num {
        val digits = (whole + frac).trimStart('0')
        if (digits.length > MAX_DECIMAL_DIGITS || frac.length > MAX_DECIMAL_DIGITS - 1) {
            raise(ParseError.Arithmetic(ArithmeticError.Overflow))
        }
        var scale = 1L
        repeat(frac.length) { scale *= 10L }
        val value = exact(Rational.of(if (digits.isEmpty()) 0L else digits.toLong(), scale))
        val dyadic = value.den and (value.den - 1) == 0L
        val precision = if (frac.length >= DYADIC_EXACT_PLACES && dyadic) Rational.ZERO else exact(Rational.of(1, 2 * scale))
        return Num(value, precision)
    }
}
