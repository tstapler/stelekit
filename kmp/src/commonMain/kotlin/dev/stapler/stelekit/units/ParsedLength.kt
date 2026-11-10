package dev.stapler.stelekit.units

/** Metadata words and marks that qualify a length without changing its value (grammar rule 6). */
enum class LengthQualifier { APPROX, MIN, MAX, VIF, TYP }

/** What a bare number (no unit mark) means in the sheet the text came from (grammar rule 7). */
enum class UnitContext {
    /** Bare numbers are inches. */
    INCHES,

    /** The sheet unit is not known to be inches, so a length without a unit mark is `UnitRequired`. */
    UNKNOWN,
}

data class LengthContext(val unmarked: UnitContext = UnitContext.INCHES)

/** Why text is not a length. */
enum class NotALengthReason { MEMBER_CODE, SCREW_GAUGE, TEXT }

/** Parse failures. None carries input text, so they are safe to log. */
sealed interface ParseError {
    data object Empty : ParseError

    /** The text is deliberately not a length (structural member code, screw gauge, plain words). */
    data class NotALength(val reason: NotALengthReason) : ParseError

    data class UnexpectedCharacter(val index: Int) : ParseError

    /** The token at [tokenIndex] does not fit the grammar at that point. */
    data class Unexpected(val tokenIndex: Int) : ParseError

    data object DivideByZero : ParseError

    data object ExponentNotation : ParseError

    data object UnitRequired : ParseError

    data object NotAnInteger : ParseError

    data class Arithmetic(val error: ArithmeticError) : ParseError
}

/**
 * Result of the wiki length grammar. Values are exact [Rational] inches; `precision` is the
 * half-width of the interval the written number stands for ([Rational.ZERO] when written exactly),
 * which fusion uses as tolerance. Lossy decimals keep their exact value and say how lossy they
 * are: `37.9"` is `379/10` with precision `1/20`.
 */
sealed interface ParsedLength {
    val qualifiers: Set<LengthQualifier>

    data class Measured(
        val value: Rational,
        val precision: Rational,
        override val qualifiers: Set<LengthQualifier> = emptySet(),
    ) : ParsedLength {
        val isApprox: Boolean get() = LengthQualifier.APPROX in qualifiers
    }

    /** `51-54"`; both ends in written order. */
    data class Range(
        val low: Measured,
        val high: Measured,
        override val qualifiers: Set<LengthQualifier> = emptySet(),
    ) : ParsedLength

    /** `±1/16"`. */
    data class Tolerance(
        val amount: Rational,
        override val qualifiers: Set<LengthQualifier> = emptySet(),
    ) : ParsedLength

    /**
     * `122" (10' 2")`: one dimension written two ways, a consistency check rather than two
     * dimensions. [agrees] is exact equality of the two values.
     */
    data class Consistency(
        val primary: Measured,
        val equivalent: Measured,
        val agrees: Boolean,
        override val qualifiers: Set<LengthQualifier> = emptySet(),
    ) : ParsedLength

    /** `24 x 36"`: a size list, not arithmetic. */
    data class SizeList(
        val items: List<Measured>,
        override val qualifiers: Set<LengthQualifier> = emptySet(),
    ) : ParsedLength
}
