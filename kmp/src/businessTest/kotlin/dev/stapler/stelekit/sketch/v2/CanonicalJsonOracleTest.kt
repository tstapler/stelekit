// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 1.1.2b oracle: JDK 21's `Double.toString` is shortest round-trip (JDK-4511638), so the codec's own
 * digit generator must agree with it value-for-value on random bit patterns, subnormals and powers of two.
 */
class CanonicalJsonOracleTest {
    private fun canonical(d: Double): String = CanonicalJson.formatDouble(d).fold({ error("non-finite") }, { it })

    private fun digitCount(s: String): Int = BigDecimal(s).stripTrailingZeros().precision()

    @Test
    fun canonical_should_MatchTheJdkShortestDigits_ForRandomBitPatterns() {
        val rnd = Random(20261116)
        var checked = 0
        repeat(200_000) {
            val d = Double.fromBits(rnd.nextLong())
            if (!d.isFinite() || d == 0.0) return@repeat
            val c = canonical(d)
            assertEquals(d, c.toDouble(), "round trip of $c")
            assertEquals(0, BigDecimal(c).compareTo(BigDecimal(d.toString())), "digits of $d: canonical $c")
            checked++
        }
        assertTrue(checked > 199_000)
    }

    @Test
    fun canonical_should_BeShortest_ForPowersOfTwoSubnormalsAndBoundaryValues() {
        val specials = buildList {
            for (e in -1074..1023) add(Math.scalb(1.0, e))
            for (b in 1L..2_000L) add(Double.fromBits(b))
            for (e in -300..300) add(Math.nextUp(Math.scalb(1.0, e))).also { add(Math.nextDown(Math.scalb(1.0, e))) }
            addAll(listOf(Double.MIN_VALUE, Double.MAX_VALUE, java.lang.Double.MIN_NORMAL, 0.1, 0.2, 0.3, 1.0 / 3.0, 9007199254740993.0))
        }
        for (d in specials) {
            val c = canonical(d)
            assertEquals(d, c.toDouble(), "round trip of $c")
            // JDK prints at least two digits for tiny values ("4.9E-324"); the shortest form is never longer.
            assertTrue(digitCount(c) <= maxOf(digitCount(d.toString()), 2), "canonical $c longer than JDK ${d}")
        }
    }

    @Test
    fun parser_should_MatchTheJdk_ForShortestAndExactExpansionsAndRandomDecimals() {
        val rnd = Random(77)
        fun same(text: String) {
            val mine = CanonicalJson.parseDouble(text)
            val jdk = text.toDouble()
            assertTrue(mine != null && mine.toRawBits() == jdk.toRawBits(), "parse of $text: $mine vs JDK $jdk")
        }
        repeat(60_000) {
            val d = Double.fromBits(rnd.nextLong())
            if (!d.isFinite()) return@repeat
            same(d.toString())
            if (it % 20 == 0) same(BigDecimal(d).toString()) // the exact binary expansion: up to ~770 digits, ties and subnormals
        }
        repeat(60_000) {
            val digits = (1..rnd.nextInt(1, 40)).joinToString("") { rnd.nextInt(0, 10).toString() }
            val exp = rnd.nextInt(-340, 330)
            val text = (if (rnd.nextBoolean()) "-" else "") + digits.take(1).ifEmpty { "0" } + "." + digits.drop(1).ifEmpty { "0" } + "e" + exp
            val jdk = text.toDouble()
            if (jdk.isInfinite()) assertEquals(null, CanonicalJson.parseDouble(text), text) else same(text)
        }
        for (t in listOf("0", "-0", "0.0", "5e-324", "2.4703282292062327e-324", "2.4703282292062328e-324", "4.9406564584124654e-324", "1.7976931348623157e308", "9007199254740993", "0.1", "1e22", "1e23", "123456789012345678901234567890")) same(t)
        for (t in listOf("", "-", ".", "e5", "1e", "1e+", "1.2.3", "abc", "NaN", "Infinity", "1e999", "1x")) assertEquals(null, CanonicalJson.parseDouble(t), t)
    }

    @Test
    fun canonical_should_MatchTheJdk_ForSixteenthsAndMoneyLikeValues() {
        for (i in -200_000..200_000 step 7) {
            val d = i / 16.0
            assertEquals(0, BigDecimal(canonical(d)).compareTo(BigDecimal(d.toString())), "sixteenths $d")
        }
        for (i in 0..50_000) {
            val d = i / 100.0
            assertEquals(0, BigDecimal(canonical(d)).compareTo(BigDecimal(d.toString())), "hundredths $d")
        }
    }
}
