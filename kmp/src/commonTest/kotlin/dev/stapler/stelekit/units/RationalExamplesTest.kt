package dev.stapler.stelekit.units

import arrow.core.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RationalExamplesTest {
    private fun sum(vararg parts: Rational): Rational =
        parts.fold(Rational.ZERO) { acc, p -> acc.add(p).fold({ error("$it") }, { it }) }

    @Test
    fun closetZoneSumIsExactly531Over16() {
        // 10 1/2 + 12 3/16 + 10 1/2 = 33 3/16
        val total = sum(Rational(21, 2), Rational(195, 16), Rational(21, 2))
        assertEquals(Rational(531, 16), total)
        assertEquals(531L, total.toInches().fold({ error("$it") }, { it }).sixteenths)
    }

    @Test
    fun largerNamedSumsHold() {
        assertEquals(Rational(1107, 16), sum(Rational(45, 2), Rational(387, 16), Rational(45, 2)))
        assertEquals(Rational(136), sum(Rational(535, 16), Rational(267, 8), Rational(1107, 16)))
    }

    @Test
    fun lossyDecimalsStayExactAndAreNeverRounded() {
        val a = Rational(379, 10)
        val b = Rational(27, 5)
        assertEquals(379L, a.num)
        assertEquals(10L, a.den)
        assertEquals(Rational(27, 5), Rational(54, 10))
        assertEquals(Rational(65, 2), a.subtract(b).fold({ error("$it") }, { it }))
    }

    @Test
    fun nonSixteenthValueIsNotRepresentableWithExactAndRemainder() {
        val exact = Rational(137, 5)
        val left = assertIs<Either.Left<InchesError>>(exact.toInches()).value
        val nr = assertIs<InchesError.NotRepresentable>(left)
        assertEquals(exact, nr.exact)
        // 137/5 in = 438.4 sixteenths; the next lower sixteenth is 438/16, remainder 2/5 sixteenth = 1/40 in.
        assertEquals(Rational(1, 40), nr.remainder)
    }

    @Test
    fun roundingPolicies() {
        val r = Rational(137, 5)
        assertEquals(438L, r.roundToInches(RoundingPolicy.FLOOR).fold({ error("$it") }, { it }).sixteenths)
        assertEquals(439L, r.roundToInches(RoundingPolicy.CEIL).fold({ error("$it") }, { it }).sixteenths)
        assertEquals(438L, r.roundToInches(RoundingPolicy.HALF_UP).fold({ error("$it") }, { it }).sixteenths)
        // Exactly half a sixteenth goes away from zero in both directions.
        assertEquals(1L, Rational(1, 32).roundToInches().fold({ error("$it") }, { it }).sixteenths)
        assertEquals(-1L, Rational(-1, 32).roundToInches().fold({ error("$it") }, { it }).sixteenths)
    }

    @Test
    fun overflowIsLeftNotWrapped() {
        val big = Rational(Long.MAX_VALUE)
        assertIs<Either.Left<ArithmeticError>>(big.add(Rational.ONE))
        assertIs<Either.Left<ArithmeticError>>(big.multiply(Rational(2)))
        assertIs<Either.Left<ArithmeticError>>(Rational(Long.MAX_VALUE).toInches())
    }

    @Test
    fun invalidConstructionIsRejected() {
        assertFailsWith<IllegalArgumentException> { Rational(1, 0) }
        assertIs<Either.Left<ArithmeticError>>(Rational.ZERO.inverse())
        assertEquals(ArithmeticError.DivideByZero, (Rational.ZERO.inverse() as Either.Left).value)
    }

    @Test
    fun signAndLowestTermsNormalisation() {
        val r = Rational(8, -16)
        assertEquals(-1L, r.num)
        assertEquals(2L, r.den)
        assertEquals("-1/2", r.toString())
        assertEquals(Rational(21, 2), Rational(168, 16))
    }
}
