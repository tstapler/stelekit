// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.MetricScene
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchDimensionStrings
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.MetricLength
import dev.stapler.stelekit.units.MetricUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Measure mode: the keypad's keys, the sticky unit across the fields of one edit, the unit cycle and the label editor in mm. */
class MetricEntryTest {
    private fun type(text: String, key: MeasureKey, start: Int = text.length, end: Int = start) = LengthKeyInsertion.apply(CaretText(text, start, end), key)

    @Test
    fun fractionKeys_should_TypeAMixedNumber() {
        assertEquals(CaretResult("3 1/2", 5), type("3", MeasureKey.Half))
        assertEquals(CaretResult("1/4", 3), type("", MeasureKey.Quarter))
        assertEquals(CaretResult("3' 1/8", 6), type("3' ", MeasureKey.Eighth))
        assertEquals(CaretResult("12 1/16", 7), type("12", MeasureKey.Sixteenth))
        assertEquals(CaretResult("1/2", 3), type("3' 7", MeasureKey.Half, 0, 4), "a selection is replaced")
    }

    @Test
    fun unitKeys_should_FollowTheNumber_AndTheCommaTypesStraightAway() {
        assertEquals(CaretResult("25 mm", 5), type("25", MeasureKey.Millimetre))
        assertEquals(CaretResult("2,5 cm", 6), type("2,5", MeasureKey.Centimetre))
        assertEquals(CaretResult("1 m", 3), type("1", MeasureKey.Metre))
        assertEquals(CaretResult("1,", 2), type("1", MeasureKey.Comma))
        assertEquals(CaretResult("mm", 2), type("", MeasureKey.Millimetre))
        assertEquals(CaretResult("25 mm 7", 5), type("25 7", MeasureKey.Millimetre, 2, 2))
    }

    @Test
    fun everyMeasureKey_should_HaveALabel_AReadableDescription_AndAUniqueTag() {
        val tags = MeasureKey.entries.map { LengthKeyTags.key(it) }
        assertEquals(tags.size, tags.toSet().size)
        assertTrue(tags.none { it in LengthKey.entries.map(LengthKeyTags::key) })
        for (key in MeasureKey.entries) {
            assertTrue(key.label.isNotBlank() && key.description.isNotBlank(), key.name)
            assertTrue(key.description.none { it.isDigit() }, "a description is words a screen reader can say: ${key.description}")
        }
        assertEquals(listOf("one half", "one quarter", "one eighth", "one sixteenth"), MeasureKey.FRACTIONS.map { it.description })
        assertEquals(listOf("millimetres", "centimetres", "metres", "decimal comma"), MeasureKey.METRIC.map { it.description })
    }

    @Test
    fun theUnitChip_should_CycleFeetInchesInchesDecimalMmCmM_AndRoundAgain() {
        val order = generateSequence(LengthFormat.FEET_INCHES) { it.next() }.take(6).toList()
        assertEquals(
            listOf(LengthFormat.FEET_INCHES, LengthFormat.INCHES_MIXED, LengthFormat.DECIMAL_INCHES, LengthFormat.METRIC_MM, LengthFormat.METRIC_CM_M, LengthFormat.FEET_INCHES),
            order,
        )
        assertEquals(listOf("ft-in", "in", "in.dec", "mm", "cm/m"), LengthFormat.entries.map { it.chipLabel })
        for (format in LengthFormat.entries) assertTrue(format.chipDescription.contains(format.displayName) && format.chipDescription.contains(format.next().displayName))
    }

    @Test
    fun aMetricUnitWritten_should_BeStickyForTheNextFieldOfTheSameEdit() {
        val edit = DimensionPillEdit(filledRectEl("r", 0, 0, 160, 80), PillKind.Width)
        edit.format = LengthFormat.METRIC_MM
        assertEquals(PillStep.Next(PillKind.Height), edit.submit("1,5 cm"))
        assertEquals(MetricUnit.CM, edit.stickyUnit)
        val done = assertIs<PillStep.Done>(edit.submit("4"))
        val command = assertIs<ResizeElement>(done.command)
        assertEquals(MetricScene.toUnits(150), command.width)
        assertEquals(MetricScene.toUnits(400), command.height)
    }

    @Test
    fun aPrefillPassedOnUntouched_should_NotMakeAUnitSticky() {
        val edit = DimensionPillEdit(filledRectEl("r", 0, 0, 160, 80), PillKind.Width)
        edit.format = LengthFormat.METRIC_CM_M
        edit.submit(edit.textOf(PillKind.Width, edit.format), untouched = true)
        assertNull(edit.stickyUnit)
        val done = assertIs<PillStep.Done>(edit.submit("3"))
        assertEquals(MetricScene.toUnits(300), done.command?.height)
    }

    @Test
    fun changingTheDisplay_should_ForgetTheStickyUnit() {
        val edit = DimensionPillEdit(filledRectEl("r", 0, 0, 160, 80), PillKind.Width)
        edit.format = LengthFormat.METRIC_MM
        edit.submit("1 m")
        assertEquals(MetricUnit.M, edit.stickyUnit)
        edit.format = LengthFormat.METRIC_CM_M
        assertNull(edit.stickyUnit)
    }

    @Test
    fun commitPending_should_NotRoundAnEarlierMetricFieldToASixteenth() {
        val edit = DimensionPillEdit(filledRectEl("r", 0, 0, 160, 80), PillKind.Width)
        edit.format = LengthFormat.METRIC_MM
        assertEquals(PillStep.Next(PillKind.Height), edit.submit("1,23 cm"))
        assertTrue(edit.back("4.5 cm"))
        val done = assertIs<PillStep.Done>(edit.commitPending("1,23 cm", untouched = false))
        assertEquals(MetricScene.toUnits(450), done.command?.height)
        assertEquals(MetricScene.toUnits(123), done.command?.width)
    }

    @Test
    fun inInchDisplay_should_NotStickAUnit_AndBackShouldKeepAnExactMetricValue() {
        val edit = DimensionPillEdit(filledRectEl("r", 0, 0, 160, 80), PillKind.Width)
        assertEquals(PillStep.Next(PillKind.Height), edit.submit("25 mm"))
        assertNull(edit.stickyUnit)
        assertTrue(edit.back(edit.textOf(PillKind.Height)))
        val done = assertIs<PillStep.Done>(edit.commitPending(edit.textOf(PillKind.Width), untouched = false))
        assertEquals(MetricScene.toUnits(250), done.command?.width)
    }

    @Test
    fun inchesTyped_should_StillCommitWholeSixteenths_InAMetricEdit() {
        val edit = DimensionPillEdit(filledRectEl("r", 0, 0, 160, 80), PillKind.Width)
        edit.format = LengthFormat.METRIC_MM
        edit.submit("1\"")
        val command = assertIs<PillStep.Done>(edit.submit("1/2\"")).command
        assertEquals(16.0, command?.width)
        assertEquals(8.0, command?.height)
    }

    @Test
    fun theFieldPrefill_should_ShowTheExactMetricSize_NotTheSixteenthRounding() {
        val edit = DimensionPillEdit(filledRectEl("r", 0, 0, 160, 80), PillKind.Width)
        assertEquals("254 mm", edit.textOf(PillKind.Width, LengthFormat.METRIC_MM))
        assertEquals("25.4 cm", edit.textOf(PillKind.Width, LengthFormat.METRIC_CM_M))
        assertEquals("127 mm", edit.textOf(PillKind.Height, LengthFormat.METRIC_MM))
        val typed = DimensionPillEdit(filledRectEl("r", 0, 0, 160, 80), PillKind.Width)
        typed.format = LengthFormat.METRIC_MM
        typed.submit("12.3 mm")
        assertEquals("12.3 mm", typed.textOf(PillKind.Width, LengthFormat.METRIC_MM))
    }

    @Test
    fun pillText_should_ReadTheExactMetricSize() {
        val rect = filledRectEl("r", 0, 0, 160, 80)
        val spec = DimensionPillModel.specs(rect).first()
        assertEquals("W 254 mm", DimensionPillModel.pillText(spec, LengthFormat.METRIC_MM))
        val fractional = PillSpec(PillKind.Width, 8L, MetricScene.toUnits(123))
        assertEquals("W 12.3 mm", DimensionPillModel.pillText(fractional, LengthFormat.METRIC_MM))
        assertEquals("W 1/2\"", DimensionPillModel.pillText(fractional, LengthFormat.INCHES_MIXED))
    }

    @Test
    fun labelEditorParser_should_KeepTenthsOfAMillimetre_AndSayWhenItRounds() {
        val exact = DimensionValueParser.interpret(ConstraintKind.Distance, "12,3 mm", technical = true, format = LengthFormat.METRIC_MM).getOrNull()!!
        assertEquals(MetricScene.toUnits(123), exact.value)
        assertNull(exact.roundedNotice)
        val finer = DimensionValueParser.interpret(ConstraintKind.Distance, "12,34", technical = true, format = LengthFormat.METRIC_MM).getOrNull()!!
        assertEquals(MetricScene.toUnits(123), finer.value)
        assertEquals("12,34 is finer than 0.1 mm. Rounded to 12.3 mm", finer.roundedNotice)
        assertEquals(40.0 * 16, DimensionValueParser.interpret(ConstraintKind.Distance, "40", technical = true).getOrNull()!!.value)
        assertTrue(DimensionValueParser.parse(ConstraintKind.Distance, "12.34 mm", technical = true, format = LengthFormat.METRIC_MM).isLeft())
        assertEquals(MetricScene.toUnits(254), DimensionValueParser.parse(ConstraintKind.Distance, "25.4 mm", technical = true).getOrNull())
        assertEquals("12.3 mm", ConstraintValueTextCompat.of(MetricScene.toUnits(123), LengthFormat.METRIC_MM))
        assertEquals(SketchDimensionStrings.Units.CHIP_METRIC_MM, LengthFormat.METRIC_MM.chipLabel)
    }
}

private object ConstraintValueTextCompat {
    fun of(units: Double, format: LengthFormat): String {
        val c = dev.stapler.stelekit.sketch.v2.constraints.Constraint(
            dev.stapler.stelekit.sketch.v2.constraints.ConstraintId("c"),
            ConstraintKind.Distance,
            emptyList(),
            value = units,
        )
        return ConstraintValueText.of(c, technical = true, format = format).orEmpty()
    }
}