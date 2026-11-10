package dev.stapler.stelekit.units

import arrow.core.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Golden table for the length grammar. Every number here is invented; none is project data. */
class LengthParserGoldenTest {
    private fun parse(text: String, ctx: LengthContext = LengthContext()): Either<ParseError, ParsedLength> =
        parseLength(text, ctx)

    private fun ok(text: String, ctx: LengthContext = LengthContext()): ParsedLength =
        parse(text, ctx).fold({ error("'$text' failed with $it") }, { it })

    private fun measured(text: String): ParsedLength.Measured = assertIs<ParsedLength.Measured>(ok(text))

    private fun fails(text: String): ParseError = parse(text).fold({ it }, { error("'$text' parsed as $it") })

    private fun assertValue(expected: Rational, text: String) = assertEquals(expected, measured(text).value, text)

    @Test
    fun mixedFractions_should_ParseExactly() {
        assertValue(Rational(277, 16), "17 5/16\"")
        assertValue(Rational(2, 1) + Rational(3, 16), "2-3/16\"")
        assertValue(Rational(253, 8), "31-5/8\"")
        assertValue(Rational(27, 2), "13½")
        assertValue(Rational(21, 2), "10 8/16\"")
        assertValue(Rational(545, 16), "33 17/16\"")
        assertValue(Rational(1, 2), "½\"")
        assertValue(Rational(7, 16), "7/16")
    }

    @Test
    fun feetAndInches_should_ParseWithSpaceHyphenOrNoSeparator() {
        assertValue(Rational(257, 4), "5' 4 1/4\"")
        assertValue(Rational(257, 4), "5'-4 1/4\"")
        assertValue(Rational(257, 4), "5′ 4¼″")
        assertValue(Rational(122), "10' 2\"")
        assertValue(Rational(114), "9'-6\"")
        assertValue(Rational(51), "4'3\"")
        assertValue(Rational(96), "8'")
        assertValue(Rational(120), "10 ft")
        assertValue(Rational(-257, 4), "-5' 4 1/4\"")
        assertValue(Rational(72), "6' 0\"")
    }

    @Test
    fun unmarkedNumbers_should_FollowTheContext() {
        assertValue(Rational(12), "12")
        assertEquals(ParseError.UnitRequired, parse("12", LengthContext(UnitContext.UNKNOWN)).leftOrNull())
        assertTrue(parse("12\"", LengthContext(UnitContext.UNKNOWN)).isRight())
    }

    @Test
    fun hyphenBetweenIntegers_should_BeARange() {
        val bare = assertIs<ParsedLength.Range>(ok("51-54\""))
        assertEquals(Rational(51), bare.low.value)
        assertEquals(Rational(54), bare.high.value)
        val dashed = assertIs<ParsedLength.Range>(ok("20\"–32\""))
        assertEquals(Rational(20) to Rational(32), dashed.low.value to dashed.high.value)
        val worded = assertIs<ParsedLength.Range>(ok("3 to 5 in"))
        assertEquals(Rational(3) to Rational(5), worded.low.value to worded.high.value)
        val feet = assertIs<ParsedLength.Range>(ok("8-10'"))
        assertEquals(Rational(96) to Rational(120), feet.low.value to feet.high.value)
        val feetBoth = assertIs<ParsedLength.Range>(ok("8'-10'"))
        assertEquals(Rational(96) to Rational(120), feetBoth.low.value to feetBoth.high.value)
        val fractional = assertIs<ParsedLength.Range>(ok("1-1/2\" - 2-1/2\""))
        assertEquals(Rational(3, 2) to Rational(5, 2), fractional.low.value to fractional.high.value)
    }

    @Test
    fun approxAndBoundQualifiers_should_BeMetadata() {
        val approx = measured("~18\"")
        assertEquals(Rational(18), approx.value)
        assertTrue(approx.isApprox)
        assertEquals(setOf(LengthQualifier.MIN), measured("24\" min").qualifiers)
        assertEquals(setOf(LengthQualifier.VIF), measured("24\" VIF").qualifiers)
        assertEquals(setOf(LengthQualifier.APPROX), measured("≈24\"").qualifiers)
        assertEquals(emptySet(), measured("24\"").qualifiers)
    }

    @Test
    fun tolerance_should_BeItsOwnShape() {
        val tolerance = assertIs<ParsedLength.Tolerance>(ok("±1/16\""))
        assertEquals(Rational(1, 16), tolerance.amount)
        assertEquals(Rational(1, 20), assertIs<ParsedLength.Tolerance>(ok("±0.05")).amount)
    }

    @Test
    fun decimals_should_StayExactWithPrecision() {
        val lossy = measured("37.9\"")
        assertEquals(Rational(379, 10), lossy.value)
        assertEquals(Rational(1, 20), lossy.precision)
        val twoPlaces = measured("27.31\"")
        assertEquals(Rational(2731, 100), twoPlaces.value)
        assertEquals(Rational(1, 200), twoPlaces.precision)
        val dyadic = measured("33.4375\"")
        assertEquals(Rational(535, 16), dyadic.value)
        assertEquals(Rational.ZERO, dyadic.precision)
        assertEquals(Rational(1, 20000), measured("0.1234\"").precision)
        assertEquals(Rational(1, 2), measured(".5\"").value)
        assertEquals(Rational(559, 10), measured("55.9\"").value)
    }

    @Test
    fun fractionsAndIntegers_should_CarryPrecision() {
        assertEquals(Rational(1, 16), measured("17 5/16\"").precision)
        assertEquals(Rational(1, 4), measured("1/4\"").precision)
        assertEquals(Rational.ZERO, measured("10' 2\"").precision)
        assertEquals(Rational.ZERO, measured("24\"").precision)
        assertEquals(Rational(1, 2), measured("5' 4 1/2\"").precision)
    }

    @Test
    fun parentheticalEquivalent_should_BeAConsistencyCheck() {
        val agree = assertIs<ParsedLength.Consistency>(ok("122\" (10' 2\")"))
        assertTrue(agree.agrees)
        assertEquals(Rational(122), agree.primary.value)
        assertEquals(Rational(122), agree.equivalent.value)
        val disagree = assertIs<ParsedLength.Consistency>(ok("120\" (10' 2\")"))
        assertEquals(false, disagree.agrees)
    }

    @Test
    fun xBetweenLengths_should_BeASizeList() {
        val list = assertIs<ParsedLength.SizeList>(ok("24 x 36\""))
        assertEquals(listOf(Rational(24), Rational(36)), list.items.map { it.value })
        val three = assertIs<ParsedLength.SizeList>(ok("1½ × 3½ × 8'"))
        assertEquals(listOf(Rational(3, 2), Rational(7, 2), Rational(96)), three.items.map { it.value })
    }

    @Test
    fun nonLengths_should_BeRejectedWithAReason() {
        assertEquals(ParseError.NotALength(NotALengthReason.SCREW_GAUGE), fails("#10 × 2.5\""))
        assertEquals(ParseError.NotALength(NotALengthReason.MEMBER_CODE), fails("W12x26"))
        assertEquals(ParseError.NotALength(NotALengthReason.MEMBER_CODE), fails("L3x3x1/4"))
        assertEquals(ParseError.NotALength(NotALengthReason.TEXT), fails("Ceiling Heights"))
    }

    @Test
    fun malformedInput_should_BeLeft() {
        assertEquals(ParseError.DivideByZero, fails("3/0"))
        assertEquals(ParseError.DivideByZero, fails("1 3/0\""))
        assertEquals(ParseError.Empty, fails(""))
        assertEquals(ParseError.Empty, fails("   "))
        assertEquals(ParseError.ExponentNotation, fails("1e5"))
        assertEquals(ParseError.ExponentNotation, fails("1.5e3\""))
        assertEquals(ParseError.UnexpectedCharacter(2), fails("12$"))
        assertIs<ParseError.Arithmetic>(fails("99999999999999999999\""))
        assertIs<ParseError.Unexpected>(fails("12\" 13\""))
        assertIs<ParseError.Unexpected>(fails("5' -"))
    }

    @Test
    fun counts_should_AcceptOnlyIntegers() {
        assertEquals(6L, parseCount("qty 6").getOrNull())
        assertEquals(6L, parseCount("6 brackets").getOrNull())
        assertEquals(6L, parseCount("6").getOrNull())
        assertEquals(ParseError.NotAnInteger, parseCount("6.5").leftOrNull())
        assertEquals(ParseError.NotAnInteger, parseCount("1/2").leftOrNull())
        assertEquals(ParseError.NotAnInteger, parseCount("½").leftOrNull())
        assertEquals(ParseError.Empty, parseCount("").leftOrNull())
        assertIs<ParseError.Unexpected>(parseCount("qty").leftOrNull())
        assertIs<ParseError.Unexpected>(parseCount("6 7").leftOrNull())
        assertIs<ParseError.Unexpected>(parseCount("6\"").leftOrNull())
    }

    @Test
    fun formatter_should_UseLowestTermsAndFeetInches() {
        fun f(sixteenths: Long, format: LengthFormat) = LengthFormatter.format(Inches(sixteenths), format)
        assertEquals("10 1/2\"", f(168, LengthFormat.INCHES_MIXED))
        assertEquals("33 7/16\"", f(535, LengthFormat.INCHES_MIXED))
        assertEquals("7/16\"", f(7, LengthFormat.INCHES_MIXED))
        assertEquals("0\"", f(0, LengthFormat.INCHES_MIXED))
        assertEquals("-1/2\"", f(-8, LengthFormat.INCHES_MIXED))
        assertEquals("5' 4 1/4\"", f(1028, LengthFormat.FEET_INCHES))
        assertEquals("6' 0\"", f(1152, LengthFormat.FEET_INCHES))
        assertEquals("1' 1/2\"", f(200, LengthFormat.FEET_INCHES))
        assertEquals("4 1/4\"", f(68, LengthFormat.FEET_INCHES))
        assertEquals("-5' 4 1/4\"", f(-1028, LengthFormat.FEET_INCHES))
    }

    private operator fun Rational.plus(other: Rational): Rational = add(other).fold({ error("$it") }, { it })
}
