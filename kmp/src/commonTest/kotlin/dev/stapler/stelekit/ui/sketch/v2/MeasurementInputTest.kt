// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.DimensionInput
import dev.stapler.stelekit.sketch.DimensionInputError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.runtime.settings.LengthFormatSettings
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sceneOfElements
import dev.stapler.stelekit.ui.sketch.v2.guide.MapSettings
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Typed measurements: the forms that parse, exactness (no snap), the rounding notice, the live reading, formats and the symbol keys. */
class MeasurementInputTest {
    private fun sixteenths(text: String): Long = DimensionInput.interpret(text).getOrNull()!!.inches.sixteenths

    private fun resized(width: Long, neighbourLeft: Int): RectElement {
        val target = filledRectEl("t", 0, 0, 700, 1386)
        val neighbour = filledRectEl("n", neighbourLeft, 0, 100, 100)
        val doc = SketchDocumentV2(scene = sceneOfElements(target, neighbour))
        val command = assertNotNull(PillResize.command(target, mapOf(PillKind.Width to width)))
        return command.apply(doc).getOrNull()!!.doc.scene[target.id] as RectElement
    }

    // ---- root cause evidence: the typed path is exact; the odd numbers come from dragged geometry shown in feet and inches

    @Test
    fun typedWidth_should_CommitExactly_When_ANeighbourEdgeIsOneUnitFromTheNewRightEdge() {
        val typed = sixteenths("44")
        assertEquals(704L, typed)
        // The new right edge is x=704; a neighbour's left edge at 703, 704 and 705 is exactly where an object snap would pull it.
        for (neighbour in listOf(703, 704, 705, 700)) assertEquals(704.0, resized(typed, neighbour).width.value)
    }

    @Test
    fun twoFieldEdit_should_KeepTheOtherSizeAsItWas_And_ReadBackTheTypedRoundNumber() {
        val rect = filledRectEl("r", 0, 0, 700, 1386)
        val edit = DimensionPillEdit(rect, PillKind.Width)
        assertEquals("3' 7 3/4\"", edit.textOf(PillKind.Width))
        assertIs<PillStep.Next>(edit.submit("44"))
        assertEquals("7' 2 5/8\"", edit.textOf(PillKind.Height))
        val done = assertIs<PillStep.Done>(edit.submit("7' 2 5/8\""))
        val command = assertNotNull(done.command)
        val after = command.apply(SketchDocumentV2(scene = sceneOfElements(rect))).getOrNull()!!.doc.scene[rect.id] as RectElement
        assertEquals("44\"", lengthOf(after.width.value))
        assertEquals("86 5/8\"", LengthFormatter.format(Inches(after.height.value.toLong()), LengthFormat.INCHES_MIXED))
    }

    private fun lengthOf(units: Double) = LengthFormatter.format(Inches(units.toLong()), LengthFormat.INCHES_MIXED)

    @Test
    fun pillText_should_ShowTheSameSizeInTheChosenFormat() {
        val spec = PillSpec(PillKind.Height, 1386)
        assertEquals("H 7' 2 5/8\"", DimensionPillModel.pillText(spec))
        assertEquals("H 86 5/8\"", DimensionPillModel.pillText(spec, LengthFormat.INCHES_MIXED))
        assertEquals("H 86.625\"", DimensionPillModel.pillText(spec, LengthFormat.DECIMAL_INCHES))
    }

    // ---- input forms

    @Test
    fun interpret_should_AcceptEveryNaturalForm() {
        val forms = mapOf(
            "43" to 688L, "43\"" to 688L, "3'7" to 688L, "3' 7 3/4\"" to 700L, "3ft 7in" to 688L, "3.5'" to 672L,
            "43.75" to 700L, "1/2" to 8L, "12'6" to 2400L, "3' 7\"" to 688L, "43 3/4" to 700L, "3 ft 7 3/4 in" to 700L,
        )
        for ((text, expected) in forms) assertEquals(expected, sixteenths(text), text)
    }

    @Test
    fun interpret_should_KeepTheExistingErrors_ForTextThatIsNotOneLength() {
        assertEquals(DimensionInputError.EMPTY, DimensionInput.interpret(" ").swap().getOrNull())
        assertEquals(DimensionInputError.NOT_A_LENGTH, DimensionInput.interpret("abc").swap().getOrNull())
        assertEquals(DimensionInputError.NOT_POSITIVE, DimensionInput.interpret("0").swap().getOrNull())
        assertEquals(DimensionInputError.NOT_POSITIVE, DimensionInput.interpret("0.01").swap().getOrNull())
    }

    // ---- rounding is reported, never silent

    @Test
    fun interpret_should_RoundToTheNearestSixteenthAndSayWhat_When_TheValueFallsBetweenSixteenths() {
        val typed = DimensionInput.interpret("0.3").getOrNull()!!
        assertEquals(5L, typed.inches.sixteenths)
        assertEquals("0.3", typed.roundedFrom)
        assertEquals("0.3 is between sixteenths. Rounded to 5/16\"", DimensionHint.reading("0.3", LengthFormat.INCHES_MIXED)!!.roundedNotice)
        assertNull(DimensionInput.interpret("43.75").getOrNull()!!.roundedFrom)
        assertEquals(37L * 16 + 14, sixteenths("37.9"))
    }

    // ---- format

    @Test
    fun format_should_RoundTripEverySixteenth_InEveryFormat() {
        for (format in LengthFormat.entries) {
            for (n in 1L..(16L * 300)) {
                val text = LengthFormatter.format(Inches(n), format)
                assertEquals(n, sixteenths(text), "$format $n -> $text")
            }
        }
    }

    @Test
    fun format_should_PrintNoFraction_For_AWholeNumberOfInches() {
        assertEquals("43\"", LengthFormatter.format(Inches(688), LengthFormat.INCHES_MIXED))
        assertEquals("43\"", LengthFormatter.format(Inches(688), LengthFormat.DECIMAL_INCHES))
        assertEquals("3' 7\"", LengthFormatter.format(Inches(688), LengthFormat.FEET_INCHES))
        assertEquals("43.75\"", LengthFormatter.format(Inches(700), LengthFormat.DECIMAL_INCHES))
        assertEquals("0.0625\"", LengthFormatter.format(Inches(1), LengthFormat.DECIMAL_INCHES))
        assertEquals("43 3/4\"", LengthFormatter.format(Inches(700), LengthFormat.INCHES_MIXED))
    }

    @Test
    fun format_should_CycleThroughAllThreeAndBack() {
        assertEquals(LengthFormat.INCHES_MIXED, LengthFormat.FEET_INCHES.next())
        assertEquals(LengthFormat.DECIMAL_INCHES, LengthFormat.INCHES_MIXED.next())
        assertEquals(LengthFormat.FEET_INCHES, LengthFormat.DECIMAL_INCHES.next())
    }

    @Test
    fun unitState_should_PersistTheChoice_And_StartFromIt() {
        val store = MapSettings()
        val first = LengthUnitState(LengthFormatSettings(store))
        assertEquals(LengthFormat.FEET_INCHES, first.format)
        first.cycle()
        assertEquals(LengthFormat.INCHES_MIXED, LengthUnitState(LengthFormatSettings(store)).format)
        assertEquals(LengthFormat.FEET_INCHES, LengthUnitState(null).format)
    }

    // ---- live reading

    @Test
    fun reading_should_ShowTheChosenUnitsFirst_And_TheOtherSpellingBeside() {
        assertEquals("= 3' 7 3/4\" (43 3/4\")", DimensionHint.reading("43.75", LengthFormat.FEET_INCHES)!!.text)
        assertEquals("= 43 3/4\" (3' 7 3/4\")", DimensionHint.reading("3'7 3/4", LengthFormat.INCHES_MIXED)!!.text)
        assertEquals("= 43.75\" (3' 7 3/4\")", DimensionHint.reading("43 3/4", LengthFormat.DECIMAL_INCHES)!!.text)
        assertEquals("= 5 3/4\"", DimensionHint.reading("5 3/4", LengthFormat.FEET_INCHES)!!.text)
        assertNull(DimensionHint.reading("", LengthFormat.FEET_INCHES))
        assertNull(DimensionHint.reading("3'x", LengthFormat.FEET_INCHES))
        assertTrue(DimensionHint.reading("43", LengthFormat.FEET_INCHES)!!.roundedNotice == null)
    }

    // ---- symbol keys

    private fun type(text: String, start: Int, end: Int, key: LengthKey) = LengthKeyInsertion.apply(CaretText(text, start, end), key)

    @Test
    fun keys_should_InsertAtTheCaret_AndReplaceASelection() {
        assertEquals(CaretResult("3' ", 3), type("3", 1, 1, LengthKey.FeetMark))
        assertEquals(CaretResult("3' 7", 2), type("3 7", 1, 1, LengthKey.FeetMark))
        assertEquals(CaretResult("3' 7\"", 5), type("3' 7", 4, 4, LengthKey.InchMark))
        assertEquals(CaretResult("7/", 2), type("7", 1, 1, LengthKey.Slash))
        assertEquals(CaretResult("3/4", 2), type("34", 1, 1, LengthKey.Slash))
        assertEquals(CaretResult("\"", 1), type("3' 7 3/4\"", 0, 9, LengthKey.InchMark), "a selection is replaced")
        assertEquals(CaretResult("3ft ", 4), type("3", 1, 1, LengthKey.FeetWord))
        assertEquals(CaretResult("3ft 7in", 7), type("3ft 7", 5, 5, LengthKey.InchWord))
        assertEquals(CaretResult("0.", 2), type("0", 1, 1, LengthKey.Point))
    }

    @Test
    fun keys_should_ClampACaretOutsideTheText() {
        assertEquals(CaretResult("3/", 2), type("3", 5, 9, LengthKey.Slash))
    }

    @Test
    fun keys_should_AllBeAtLeastOneCharacterAndDescribed() {
        for (key in LengthKey.entries) {
            assertTrue(key.insert.isNotEmpty() && key.description.isNotBlank() && key.label.isNotEmpty(), key.name)
        }
    }
}
