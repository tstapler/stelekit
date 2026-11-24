package dev.stapler.stelekit.units

import dev.stapler.stelekit.sketch.DimensionInput
import dev.stapler.stelekit.sketch.DimensionInputError
import dev.stapler.stelekit.ui.sketch.v2.DimensionHint
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Metric lengths: parse and format round trips in every unit, rounding boundaries, decimal comma, sub-sixteenth exactness. */
class MetricLengthTest {
    private val tenths: Arb<Long> = Arb.long(1L..5_000_000L)
    private val metricFormats = listOf(LengthFormat.METRIC_MM, LengthFormat.METRIC_CM_M)

    private fun read(text: String, bare: MetricUnit? = null): MetricReading = assertIs<MetricParse.Ok>(MetricLength.parse(text, bare)).reading

    @Test
    fun formatThenParse_should_ReturnTheSameTenthsOfAMillimetre_InEveryMetricFormat() = runTest {
        checkAll(tenths, Arb.element(metricFormats)) { t, format ->
            val text = MetricLength.format(t, format)
            assertEquals(MetricReading(t, rounded = false), read(text), "$format $text")
        }
    }

    @Test
    fun aBareNumber_should_RoundTripInTheUnitItWasWrittenIn() = runTest {
        checkAll(Arb.long(1L..9_999L)) { t ->
            assertEquals(t, read(MetricLength.format(t, LengthFormat.METRIC_MM).removeSuffix(" mm"), bare = MetricUnit.MM).tenthsMm)
            assertEquals(t, read(MetricLength.format(t, LengthFormat.METRIC_CM_M).removeSuffix(" cm"), bare = MetricUnit.CM).tenthsMm)
        }
    }

    @Test
    fun everySixteenth_should_FormatAndReadBackAsItself_InEveryFormat() = runTest {
        checkAll(Arb.long(1L..100_000L), Arb.element(LengthFormat.entries)) { n, format ->
            val text = LengthFormatter.format(Inches(n), format)
            assertEquals(Inches(n), DimensionInput.parse(text).getOrNull(), "$format $text")
            assertNull(DimensionInput.interpret(text).getOrNull()!!.roundedFrom, "$format $text")
        }
    }

    @Test
    fun unitsFromTenths_should_ComeBackAsTheSameTenths() = runTest {
        checkAll(tenths) { t -> assertEquals(t, MetricLength.tenthsOf(MetricLength.toUnits(t))) }
    }

    @Test
    fun format_should_WriteKnownLengths() {
        assertEquals("25.4 mm", LengthFormatter.format(Inches(16), LengthFormat.METRIC_MM))
        assertEquals("1.6 mm", LengthFormatter.format(Inches(1), LengthFormat.METRIC_MM))
        assertEquals("0 mm", LengthFormatter.format(Inches(0), LengthFormat.METRIC_MM))
        assertEquals("-25.4 mm", LengthFormatter.format(Inches(-16), LengthFormat.METRIC_MM))
        assertEquals("2.54 cm", LengthFormatter.format(Inches(16), LengthFormat.METRIC_CM_M))
        assertEquals("1.0001 m", LengthFormatter.format(Inches(630), LengthFormat.METRIC_CM_M))
        assertEquals("99.99 cm", MetricLength.format(9999, LengthFormat.METRIC_CM_M))
        assertEquals("1 m", MetricLength.format(10_000, LengthFormat.METRIC_CM_M))
        assertEquals("12.3 mm", LengthFormatter.formatUnits(MetricLength.toUnits(123), LengthFormat.METRIC_MM))
    }

    @Test
    fun parse_should_ReadEveryUnitSuffix_InAnyCase_WithOrWithoutASpace() {
        assertEquals(254L, read("25.4 mm").tenthsMm)
        assertEquals(254L, read("25.4MM").tenthsMm)
        assertEquals(254L, read("2.54cm").tenthsMm)
        assertEquals(254L, read("0.0254 m").tenthsMm)
        assertEquals(12_000L, read("1.2 m").tenthsMm)
        assertEquals(15L, read("1.5 mm").tenthsMm)
    }

    @Test
    fun parse_should_AcceptACommaAsTheDecimalMark() {
        assertEquals(read("1.5 cm"), read("1,5 cm"))
        assertEquals(125L, read("12,5", bare = MetricUnit.MM).tenthsMm)
        assertEquals(read(".5 mm"), read(",5 mm"))
        assertEquals(MetricParse.NotMetric, MetricLength.parse("1,5,2 mm", null))
        assertEquals(MetricParse.NotMetric, MetricLength.parse("1.000,5 mm", null))
        assertEquals(MetricParse.NotMetric, MetricLength.parse("1,2.3 m", null))
        assertEquals(MetricParse.NotMetric, MetricLength.parse("1,000 mm", null), "three digits after a comma read as a thousands group, not 1 mm")
        assertEquals(10L, read("1.000 mm").tenthsMm)
    }

    @Test
    fun parse_should_LeaveInchTextAlone() {
        for (text in listOf("3' 4\"", "12 3/16\"", "40", "5 ft", "3 in", "", "mm", "m", "-5 mm", "abc mm", "1 2 mm")) {
            assertEquals(MetricParse.NotMetric, MetricLength.parse(text, null), text)
        }
        assertEquals(MetricParse.NotMetric, MetricLength.parse("3' 4\"", MetricUnit.MM))
    }

    @Test
    fun parse_should_RoundHalfUpToTenthsOfAMillimetre_AndSayWhen() {
        assertEquals(MetricReading(123, rounded = true), read("12.34 mm"))
        assertEquals(MetricReading(124, rounded = true), read("12.35 mm"), "an exact half goes up")
        assertEquals(MetricReading(123, rounded = true), read("12.349 mm"))
        assertEquals(MetricReading(1, rounded = true), read("0.05 mm"))
        assertEquals(MetricReading(0, rounded = true), read("0.049 mm"))
        assertEquals(MetricReading(123, rounded = false), read("12.30 mm"))
        assertEquals(MetricReading(12_345, rounded = false), read("1.2345 m"))
    }

    @Test
    fun parse_should_RefuseTooManyDigitsInsteadOfOverflowing() {
        assertEquals(MetricParse.TooLarge, MetricLength.parse("9999999999999 mm", null))
        assertEquals(MetricParse.TooLarge, MetricLength.parse("999999999999 m", null))
    }

    @Test
    fun typedMetric_should_BeStoredExactToTenthsOfAMillimetre_NotRoundedToASixteenth() {
        val typed = DimensionInput.interpretUnits("12.3 mm", LengthFormat.FEET_INCHES).getOrNull()!!
        assertEquals(MetricLength.toUnits(123), typed.units)
        assertNotEquals(typed.units, kotlin.math.floor(typed.units + 0.5), "12.3 mm is 7.75 sixteenths, not 8")
        assertNull(typed.roundedFrom)
        assertEquals(123L, typed.metricTenthsMm)
        assertEquals(16.0, DimensionInput.interpretUnits("25.4 mm", LengthFormat.INCHES_MIXED).getOrNull()!!.units)
        val finer = DimensionInput.interpretUnits("12.34 mm", LengthFormat.METRIC_MM).getOrNull()!!
        assertEquals("12.34 mm", finer.roundedFrom)
        assertEquals(MetricLength.toUnits(123), finer.units)
    }

    @Test
    fun aBareNumber_should_MeanTheActiveUnit() {
        assertEquals(400L, DimensionInput.interpretUnits("40", LengthFormat.METRIC_MM).getOrNull()!!.metricTenthsMm)
        assertEquals(4_000L, DimensionInput.interpretUnits("40", LengthFormat.METRIC_CM_M).getOrNull()!!.metricTenthsMm)
        assertEquals(400_000L, DimensionInput.interpretUnits("40", LengthFormat.METRIC_MM, bare = MetricUnit.M).getOrNull()!!.metricTenthsMm)
        val inches = DimensionInput.interpretUnits("40", LengthFormat.FEET_INCHES).getOrNull()!!
        assertEquals(640.0, inches.units)
        assertNull(inches.metricTenthsMm)
        assertEquals(640.0, DimensionInput.interpretUnits("3' 4\"", LengthFormat.METRIC_MM).getOrNull()!!.units)
    }

    @Test
    fun errors_should_BeTheSameKindAsForInches() {
        assertEquals(DimensionInputError.NOT_POSITIVE, DimensionInput.interpretUnits("0 mm", LengthFormat.METRIC_MM).leftOrNullCompat())
        assertEquals(DimensionInputError.NOT_POSITIVE, DimensionInput.interpretUnits("0.04 mm", LengthFormat.METRIC_MM).leftOrNullCompat())
        assertEquals(DimensionInputError.TOO_LARGE, DimensionInput.interpretUnits("9999999 m", LengthFormat.METRIC_MM).leftOrNullCompat())
        assertEquals(DimensionInputError.EMPTY, DimensionInput.interpretUnits("  ", LengthFormat.METRIC_MM).leftOrNullCompat())
        assertEquals(DimensionInputError.NOT_A_LENGTH, DimensionInput.interpretUnits("abc", LengthFormat.METRIC_MM).leftOrNullCompat())
        assertTrue(DimensionInputError.NOT_A_LENGTH.message(LengthFormat.METRIC_MM).contains("25 mm"))
        assertTrue(DimensionInputError.NOT_A_LENGTH.message(LengthFormat.FEET_INCHES).contains("12 3/16"))
    }

    private fun <A, B> arrow.core.Either<A, B>.leftOrNullCompat(): A? = (this as? arrow.core.Either.Left)?.value

    @Test
    fun whenOnlyWholeSixteenthsCanBeStored_should_TheNoticeNameBothSpellings() {
        val typed = DimensionInput.interpret("25.3 mm", LengthFormat.METRIC_MM).getOrNull()!!
        assertEquals(16L, typed.inches.sixteenths)
        assertEquals("25.3 mm", typed.roundedFrom)
        assertEquals("25.4 mm (1\")", DimensionInput.roundedNoticeText(typed.inches, LengthFormat.METRIC_MM))
        assertEquals("1\"", DimensionInput.roundedNoticeText(typed.inches, LengthFormat.FEET_INCHES))
        assertNull(DimensionInput.interpret("25.4 mm", LengthFormat.METRIC_MM).getOrNull()!!.roundedFrom)
        assertNull(DimensionInput.interpret("25.4 mm").getOrNull()!!.roundedFrom)
    }

    @Test
    fun hint_should_ShowTheStoredValue_AndTheInchSpelling() {
        val exact = DimensionHint.reading("12,3", LengthFormat.METRIC_MM)!!
        assertEquals("= 12.3 mm (about 1/2\")", exact.text)
        assertNull(exact.roundedNotice)
        assertEquals(MetricLength.toUnits(123), exact.units)
        val finer = DimensionHint.reading("12,34", LengthFormat.METRIC_MM)!!
        assertEquals("12,34 is finer than 0.1 mm. Rounded to 12.3 mm", finer.roundedNotice)
        val inInches = DimensionHint.reading("25.4 mm", LengthFormat.FEET_INCHES)!!
        assertEquals("= 25.4 mm (about 1\")", inInches.text)
        assertEquals(MetricUnit.MM, inInches.writtenUnit)
        val sixteenth = DimensionHint.reading("0.3\"", LengthFormat.METRIC_MM)!!
        assertEquals("0.3\" is between sixteenths. Rounded to 7.9 mm (5/16\")", sixteenth.roundedNotice)
        assertEquals("= 7.9 mm (5/16\")", sixteenth.text)
        assertFalse(DimensionHint.reading("40", LengthFormat.FEET_INCHES)!!.text.contains("mm"))
    }

    @Test
    fun spoken_should_UseUnitWords() {
        assertEquals("12.3 millimetres", dev.stapler.stelekit.ui.sketch.v2.SpokenLength.ofMetricText("12.3 mm"))
        assertEquals("1.2 metres", dev.stapler.stelekit.ui.sketch.v2.SpokenLength.ofMetricText("1.2 m"))
        assertEquals("2.54 centimetres", dev.stapler.stelekit.ui.sketch.v2.SpokenLength.ofMetricText("2.54 cm"))
    }
}
