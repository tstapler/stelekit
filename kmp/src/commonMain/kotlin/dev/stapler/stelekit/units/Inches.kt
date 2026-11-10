package dev.stapler.stelekit.units

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.either
import kotlin.jvm.JvmInline

/** How [Rational.roundToInches] resolves a value that falls between two sixteenths. */
enum class RoundingPolicy {
    /** Nearest sixteenth; exact halves go away from zero, so rounding is sign-symmetric. */
    HALF_UP,
    FLOOR,
    CEIL,
}

/** A [Rational] that cannot be shown as whole sixteenths of an inch. */
sealed interface InchesError {
    /**
     * [exact] is not a multiple of 1/16". [remainder] is `exact` minus the next lower whole
     * sixteenth, so `0 < remainder < 1/16`.
     */
    data class NotRepresentable(val exact: Rational, val remainder: Rational) : InchesError

    data class Failed(val error: ArithmeticError) : InchesError
}

/**
 * A length in whole sixteenths of an inch: a VIEW over rationals whose denominator divides 16,
 * used for sheet geometry (ADR-002). Observation values stay [Rational].
 */
@JvmInline
value class Inches(val sixteenths: Long) {

    fun toRational(): Either<ArithmeticError, Rational> = Rational.of(sixteenths, SIXTEENTHS_PER_INCH)

    fun plus(other: Inches): Either<ArithmeticError.Overflow, Inches> =
        CheckedMath.addExact(sixteenths, other.sixteenths).map { Inches(it) }

    fun negate(): Either<ArithmeticError.Overflow, Inches> =
        CheckedMath.negateExact(sixteenths).map { Inches(it) }

    fun minus(other: Inches): Either<ArithmeticError.Overflow, Inches> =
        other.negate().flatMap { plus(it) }

    override fun toString(): String = "${sixteenths}/16in"

    companion object {
        const val SIXTEENTHS_PER_INCH: Long = 16L
    }
}

/**
 * Exact conversion: succeeds only when the denominator divides 16, otherwise returns
 * `NotRepresentable` with the exact value and remainder (never a silent rounding).
 */
fun Rational.toInches(): Either<InchesError, Inches> {
    if (Inches.SIXTEENTHS_PER_INCH % den == 0L) {
        return CheckedMath.mulExact(num, Inches.SIXTEENTHS_PER_INCH / den)
            .mapLeft { InchesError.Failed(it) }
            .map { Inches(it) }
    }
    return either {
        val floorSixteenths = floorSixteenths().mapLeft { InchesError.Failed(it) }.bind()
        val lower = floorSixteenths.toRational().mapLeft { InchesError.Failed(it) }.bind()
        val remainder = subtract(lower).mapLeft { InchesError.Failed(it) }.bind()
        raise(InchesError.NotRepresentable(this@toInches, remainder))
    }
}

/** Rounds to the nearest representable sixteenth per [policy]; every non-overflowing value succeeds. */
fun Rational.roundToInches(policy: RoundingPolicy = RoundingPolicy.HALF_UP): Either<ArithmeticError, Inches> = either {
    val scaled = CheckedMath.mulExact(num, Inches.SIXTEENTHS_PER_INCH).bind()
    val floor = scaled.floorDiv(den)
    val rem = scaled.mod(den)
    val roundUp = when (policy) {
        RoundingPolicy.FLOOR -> false
        RoundingPolicy.CEIL -> rem > 0L
        // Compare rem with den - rem (not 2 * rem) so nothing can overflow. Ties go away from zero.
        RoundingPolicy.HALF_UP -> if (floor >= 0L) rem >= den - rem else rem > den - rem
    }
    Inches(if (roundUp) CheckedMath.addExact(floor, 1L).bind() else floor)
}

private fun Rational.floorSixteenths(): Either<ArithmeticError, Inches> = either {
    val scaled = CheckedMath.mulExact(num, Inches.SIXTEENTHS_PER_INCH).bind()
    Inches(scaled.floorDiv(den))
}
