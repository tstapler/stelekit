package dev.stapler.stelekit.units

import arrow.core.Either
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.boolean
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RationalPropertyTest {
    private val smallRational: Arb<Rational> = arbitrary { rs ->
        Rational(Arb.long(-10_000L..10_000L).sample(rs).value, Arb.long(1L..10_000L).sample(rs).value)
    }
    private val nonZeroRational: Arb<Rational> = smallRational.map { if (it.isZero) Rational.ONE else it }
    private val sixteenths: Arb<Long> = Arb.long(-100_000L..100_000L)
    private val nearMax: Arb<Long> = Arb.long(Long.MAX_VALUE - 1_000L..Long.MAX_VALUE)

    private fun Either<ArithmeticError, Rational>.ok(): Rational =
        fold({ error("unexpected $it") }, { it })

    private fun Rational.plus(o: Rational) = add(o).ok()
    private fun Rational.neg() = negate().fold({ error("neg $it") }, { it })

    // 1. Normalisation
    @Test
    fun normalisationIsLowestTermsPositiveDenominatorAndIdempotent() = runTest {
        val nonZeroDen = Arb.long(1L..1_000_000L)
        checkAll(Arb.long(-1_000_000L..1_000_000L), nonZeroDen, Arb.boolean()) { n, d, negateDen ->
            val signedDen = if (negateDen) -d else d
            val r = Rational(n, signedDen)
            assertTrue(r.den > 0)
            assertEquals(if (r.num == 0L) r.den else 1L, CheckedMath.gcd(r.num, r.den))
            assertEquals(r, Rational(r.num, r.den))
            assertEquals(r, Rational(n * 3, signedDen * 3))
        }
    }

    // 2. Field axioms
    @Test
    fun additionIsCommutativeAndAssociative() = runTest {
        checkAll(smallRational, smallRational, smallRational) { a, b, c ->
            assertEquals(a.plus(b), b.plus(a))
            assertEquals(a.plus(b).plus(c), a.plus(b.plus(c)))
        }
    }

    @Test
    fun additiveAndMultiplicativeInverses() = runTest {
        checkAll(nonZeroRational) { a ->
            assertEquals(Rational.ZERO, a.plus(a.neg()))
            assertEquals(Rational.ONE, a.multiply(a.inverse().ok()).ok())
            assertEquals(Rational.ONE, a.divide(a).ok())
        }
    }

    @Test
    fun multiplicationDistributesOverAddition() = runTest {
        checkAll(smallRational, smallRational, smallRational) { a, b, c ->
            assertEquals(a.multiply(b.plus(c)).ok(), a.multiply(b).ok().plus(a.multiply(c).ok()))
        }
    }

    // 3. Order consistency
    @Test
    fun orderMatchesSignOfDifferenceAndCrossMultiplication() = runTest {
        checkAll(smallRational, smallRational) { a, b ->
            val diff = b.subtract(a).ok()
            assertEquals(a.compareTo(b) < 0, diff.num > 0)
            assertEquals(a == b, diff.isZero)
            val cross = (a.num * b.den).compareTo(b.num * a.den)
            assertEquals(cross.coerceIn(-1, 1), a.compareTo(b).coerceIn(-1, 1))
            assertEquals(-a.compareTo(b).coerceIn(-1, 1), b.compareTo(a).coerceIn(-1, 1))
        }
    }

    @Test
    fun orderNeverOverflowsNearLongMax() = runTest {
        checkAll(nearMax, nearMax, nearMax, nearMax) { n1, d1, n2, d2 ->
            val a = Rational(n1, d1)
            val b = Rational(n2, d2)
            assertEquals(-a.compareTo(b).coerceIn(-1, 1), b.compareTo(a).coerceIn(-1, 1))
            assertEquals(0, a.compareTo(a))
        }
    }

    // 4. Rounding
    @Test
    fun roundingIsWithinHalfASixteenthIdempotentAndSignSymmetric() = runTest {
        checkAll(smallRational) { r ->
            val rounded = r.roundToInches(RoundingPolicy.HALF_UP).fold({ error("$it") }, { it })
            val asRational = rounded.toRational().ok()
            val error = asRational.subtract(r).ok().abs().fold({ error("$it") }, { it })
            assertTrue(error <= Rational(1, 32), "rounding error $error for $r")
            assertEquals(rounded, asRational.roundToInches().fold({ error("$it") }, { it }))
            val mirrored = r.negate().fold({ error("$it") }, { it }).roundToInches().fold({ error("$it") }, { it })
            assertEquals(-rounded.sixteenths, mirrored.sixteenths)
        }
    }

    @Test
    fun floorAndCeilBracketTheValue() = runTest {
        checkAll(smallRational) { r ->
            val lo = r.roundToInches(RoundingPolicy.FLOOR).fold({ error("$it") }, { it }).toRational().ok()
            val hi = r.roundToInches(RoundingPolicy.CEIL).fold({ error("$it") }, { it }).toRational().ok()
            assertTrue(lo <= r && r <= hi)
        }
    }

    @Test
    fun sixteenthsRoundTripExactlyThroughRational() = runTest {
        checkAll(sixteenths) { s ->
            val back = Inches(s).toRational().ok().toInches().fold({ error("$it") }, { it })
            assertEquals(s, back.sixteenths)
        }
    }

    // 5. Overflow never wraps
    @Test
    fun checkedLongOpsReturnLeftInsteadOfWrapping() = runTest {
        checkAll(nearMax, nearMax) { a, b ->
            assertIs<Either.Left<*>>(CheckedMath.addExact(a, b))
            assertIs<Either.Left<*>>(CheckedMath.mulExact(a, b.coerceAtLeast(2L)))
            assertIs<Either.Left<*>>(CheckedMath.addExact(Long.MAX_VALUE - a + Long.MIN_VALUE, Long.MAX_VALUE - b + Long.MIN_VALUE))
        }
    }

    @Test
    fun rationalOpsReturnLeftOnOverflow() = runTest {
        checkAll(nearMax, nearMax) { a, b ->
            val x = Rational(a, 1L)
            val y = Rational(b, 1L)
            assertIs<Either.Left<*>>(x.add(y))
            assertIs<Either.Left<*>>(x.multiply(y))
        }
    }

    @Test
    fun resultsThatFitAreNeverRejected() = runTest {
        checkAll(Arb.long(-1_000_000L..1_000_000L), Arb.long(-1_000_000L..1_000_000L)) { a, b ->
            assertEquals(a + b, CheckedMath.addExact(a, b).fold({ error("$it") }, { it }))
            assertEquals(a * b, CheckedMath.mulExact(a, b).fold({ error("$it") }, { it }))
        }
        assertFalse(CheckedMath.mulExact(Long.MIN_VALUE, -1L).isRight())
    }
}
