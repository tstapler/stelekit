package dev.stapler.stelekit.units

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right

/**
 * An exact fraction `num/den` in lowest terms with `den > 0` (ADR-002). Equal values are equal
 * objects. All arithmetic is overflow-checked and returns `Left(Overflow)` rather than wrapping;
 * there is no floating point anywhere in this package.
 */
class Rational private constructor(val num: Long, val den: Long) : Comparable<Rational> {

    companion object {
        val ZERO = Rational(0, 1)
        val ONE = Rational(1, 1)

        /** Throws `IllegalArgumentException` for a zero denominator or a `Long.MIN_VALUE` component. */
        operator fun invoke(num: Long, den: Long = 1L): Rational =
            of(num, den).fold({ e -> throw IllegalArgumentException("invalid rational $num/$den: $e") }, { it })

        /** Normalising factory: lowest terms, sign carried by the numerator. */
        fun of(num: Long, den: Long): Either<ArithmeticError, Rational> {
            if (den == 0L) return ArithmeticError.DivideByZero.left()
            if (num == Long.MIN_VALUE || den == Long.MIN_VALUE) return ArithmeticError.Overflow.left()
            if (num == 0L) return ZERO.right()
            val g = CheckedMath.gcd(num, den)
            val sign = if (den < 0) -1L else 1L
            return Rational(sign * (num / g), sign * (den / g)).right()
        }
    }

    val isZero: Boolean get() = num == 0L
    val signum: Int get() = if (num > 0) 1 else if (num < 0) -1 else 0

    fun negate(): Either<ArithmeticError.Overflow, Rational> =
        CheckedMath.negateExact(num).map { Rational(it, den) }

    fun abs(): Either<ArithmeticError.Overflow, Rational> = if (num < 0) negate() else this.right()

    fun add(other: Rational): Either<ArithmeticError, Rational> = either {
        val g = CheckedMath.gcd(den, other.den)
        val thisScale = den / g
        val otherScale = other.den / g
        val n = CheckedMath.addExact(
            CheckedMath.mulExact(num, otherScale).bind(),
            CheckedMath.mulExact(other.num, thisScale).bind(),
        ).bind()
        val d = CheckedMath.mulExact(den, otherScale).bind()
        of(n, d).bind()
    }

    fun subtract(other: Rational): Either<ArithmeticError, Rational> =
        other.negate().flatMap { add(it) }

    fun multiply(other: Rational): Either<ArithmeticError, Rational> = either {
        // Cross-reduce first so a product that fits after reduction does not spuriously overflow.
        val g1 = CheckedMath.gcd(num, other.den)
        val g2 = CheckedMath.gcd(other.num, den)
        val n = CheckedMath.mulExact(num / g1, other.num / g2).bind()
        val d = CheckedMath.mulExact(den / g2, other.den / g1).bind()
        of(n, d).bind()
    }

    /** The reciprocal; `Left(DivideByZero)` for zero. */
    fun inverse(): Either<ArithmeticError, Rational> = of(den, num)

    fun divide(other: Rational): Either<ArithmeticError, Rational> =
        other.inverse().flatMap { multiply(it) }

    /**
     * Exact total order by continued-fraction expansion: it cannot overflow, where
     * cross-multiplication would for operands near `Long.MAX_VALUE`.
     */
    override fun compareTo(other: Rational): Int {
        var n1 = num
        var d1 = den
        var n2 = other.num
        var d2 = other.den
        while (true) {
            val q1 = n1.floorDiv(d1)
            val q2 = n2.floorDiv(d2)
            if (q1 != q2) return if (q1 < q2) -1 else 1
            val r1 = n1.mod(d1)
            val r2 = n2.mod(d2)
            if (r1 == 0L && r2 == 0L) return 0
            if (r1 == 0L) return -1
            if (r2 == 0L) return 1
            // r1/d1 < r2/d2 exactly when d1/r1 > d2/r2: invert both and swap sides.
            val newN1 = d2
            val newD1 = r2
            val newN2 = d1
            val newD2 = r1
            n1 = newN1; d1 = newD1; n2 = newN2; d2 = newD2
        }
    }

    override fun equals(other: Any?): Boolean = other is Rational && num == other.num && den == other.den

    override fun hashCode(): Int = 31 * num.hashCode() + den.hashCode()

    override fun toString(): String = if (den == 1L) "$num" else "$num/$den"
}
