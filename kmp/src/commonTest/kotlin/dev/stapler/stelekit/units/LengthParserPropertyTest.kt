package dev.stapler.stelekit.units

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LengthParserPropertyTest {
    private val sixteenths: Arb<Long> = Arb.long(-100_000L..100_000L)
    private val grammarSoup: Arb<String> =
        Arb.list(Arb.element("0 1 2 5 9 / - – ' \" x × ~ ± ( ) ½ . # ft in to e + W a".split(' ') + " "), 0..14)
            .map { it.joinToString("") }

    private fun valueOf(text: String): Rational =
        assertIs<ParsedLength.Measured>(parseLength(text).fold({ error("'$text' failed with $it") }, { it })).value

    // Properties 1 and 8 of stack-and-pitfalls.md section 2.
    @Test
    fun parseOfFormat_should_RoundTripEverySixteenthInBothFormats() = runTest {
        checkAll(sixteenths) { n ->
            val expected = Rational(n, 16)
            for (format in LengthFormat.entries) {
                val text = LengthFormatter.format(Inches(n), format)
                assertEquals(expected, valueOf(text), "$format $text")
            }
        }
    }

    @Test
    fun formattedFractions_should_BeInLowestTerms() = runTest {
        val fraction = Regex("""(\d+)/(\d+)""")
        checkAll(sixteenths) { n ->
            for (format in LengthFormat.entries) {
                val text = LengthFormatter.format(Inches(n), format)
                fraction.findAll(text).forEach { m ->
                    assertEquals(1L, CheckedMath.gcd(m.groupValues[1].toLong(), m.groupValues[2].toLong()), text)
                    assertTrue(m.groupValues[1].toLong() < m.groupValues[2].toLong(), text)
                }
            }
        }
    }

    // Property 5: n/16 has a finite 4-place decimal, so the decimal text parses to exactly n/16.
    @Test
    fun fourPlaceDecimal_should_ParseToTheExactSixteenth() = runTest {
        checkAll(sixteenths) { n ->
            val magnitude = if (n < 0) -n else n
            val fraction = (magnitude % 16L) * 625L
            val text = (if (n < 0) "-" else "") + "${magnitude / 16L}." + fraction.toString().padStart(4, '0') + "\""
            val parsed = assertIs<ParsedLength.Measured>(parseLength(text).fold({ error("$text $it") }, { it }))
            assertEquals(Rational(n, 16), parsed.value, text)
            assertEquals(Rational.ZERO, parsed.precision, text)
        }
    }

    @Test
    fun shortDecimals_should_KeepTheExactValueAndHalfALastPlacePrecision() = runTest {
        checkAll(Arb.long(0L..99_999L), Arb.int(1..3)) { digits, places ->
            var scale = 1L
            repeat(places) { scale *= 10L }
            val whole = digits / scale
            val frac = (digits % scale).toString().padStart(places, '0')
            val parsed = assertIs<ParsedLength.Measured>(parseLength("$whole.$frac\"").fold({ error("$it") }, { it }))
            assertEquals(Rational(digits, scale), parsed.value)
            assertEquals(Rational(1, 2 * scale), parsed.precision)
        }
    }

    @Test
    fun parsers_should_NeverThrowOnArbitraryText() = runTest {
        checkAll(Arb.string(0..40)) { s ->
            parseLength(s)
            parseCount(s)
        }
        checkAll(grammarSoup) { s ->
            parseLength(s)
            parseLength(s, LengthContext(UnitContext.UNKNOWN))
            parseCount(s)
        }
    }
}
