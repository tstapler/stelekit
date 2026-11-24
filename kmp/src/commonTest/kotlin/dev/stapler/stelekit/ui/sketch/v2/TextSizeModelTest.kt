// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.SetContainerText
import dev.stapler.stelekit.sketch.v2.commands.StyleText
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextSizeLimits
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class HalfEmMetrics : TextMetricsProvider {
    override val fontStatus = FontStatus.Loaded

    override fun lineHeight(style: TextStyle) = style.fontSize

    override fun advance(text: String, style: TextStyle) = text.length * style.fontSize / 2.0

    override fun hasGlyph(codePoint: Int, style: TextStyle) = true
}

/** The A-, A+ and size-field rules, and that a size change reaches a shape's label as well as stand-alone text. */
class TextSizeModelTest {
    private fun unit(i: Int) = SceneUnit.ofInt(i)

    private fun text(id: String, size: Double = 20.0, container: String? = null) = TextElement(
        ElementBase(ElementId(id)), unit(0), unit(0), unit(100), unit(25), text = "t", fontSize = size, containerId = container?.let(::ElementId),
    )

    @Test
    fun typed_should_ApplyAValidSize_AndClampAnOutOfRangeOneWithAMessage() {
        assertEquals(TextSizeChange.Apply(28.0, null), TextSizeModel.typed(" 28 "))
        assertEquals(TextSizeChange.Apply(27.5, null), TextSizeModel.typed("27.5"))
        val low = assertIs<TextSizeChange.Apply>(TextSizeModel.typed("2"))
        assertEquals(TextSizeLimits.MIN, low.size)
        assertNotNull(low.notice)
        assertTrue(low.notice!!.contains("8"), "the message names the limit: ${low.notice}")
        val high = assertIs<TextSizeChange.Apply>(TextSizeModel.typed("9999"))
        assertEquals(TextSizeLimits.MAX, high.size)
        assertTrue(high.notice!!.contains("256"))
    }

    @Test
    fun typed_should_RefuseWhatIsNotAPositiveNumber() {
        for (bad in listOf("", "abc", "-5", "0", "NaN", "Infinity", "1e999")) {
            assertIs<TextSizeChange.Reject>(TextSizeModel.typed(bad), "'$bad'")
        }
    }

    @Test
    fun steps_should_MoveOneLadderSize_AndSayWhenTheyCannotGoFurther() {
        assertEquals(TextSizeChange.Apply(24.0, null), TextSizeModel.larger(20.0))
        assertEquals(TextSizeChange.Apply(18.0, null), TextSizeModel.smaller(20.0))
        assertNotNull(assertIs<TextSizeChange.Apply>(TextSizeModel.larger(TextSizeLimits.MAX)).notice)
        assertNotNull(assertIs<TextSizeChange.Apply>(TextSizeModel.smaller(TextSizeLimits.MIN)).notice)
    }

    @Test
    fun format_should_DropTheDecimalOnAWholeSize() {
        assertEquals("28", TextSizeModel.format(28.0))
        assertEquals("27.5", TextSizeModel.format(27.5))
        assertEquals("20", TextSizeModel.format(19.96))
    }

    @Test
    fun targets_should_ExcludeBoundLabels_AndCurrentStepsFromTheFirst() {
        val rect = RectElement(ElementBase(ElementId("r")), unit(0), unit(0), unit(50), unit(50))
        val selection = listOf(rect, text("a", 24.0), text("b", 16.0), text("label", 12.0, container = "r"))
        assertEquals(listOf("a", "b"), TextSizeModel.targets(selection).map { it.id.value })
        assertEquals(24.0, TextSizeModel.current(selection))
        assertNull(TextSizeModel.current(listOf(rect)))
    }

    @Test
    fun aSizeChange_should_ReFitAShapesLabel_WhenItsSizeIsChangedDirectly() {
        val metrics = HalfEmMetrics()
        val rect = RectElement(ElementBase(ElementId("r")), unit(0), unit(0), unit(60), unit(40))
        val scene = SketchSceneV2.empty().add(rect).getOrNull()!!
        val labelled = SetContainerText(ElementId("r"), ElementId("l"), "ab cd ef", metrics).apply(SketchDocumentV2(scene)).getOrNull()!!.doc
        val before = assertIs<TextElement>(labelled.scene[ElementId("l")])
        val after = assertIs<TextElement>(
            StyleText(listOf(ElementId("l")), fontSize = 40.0, metrics = metrics).apply(labelled).getOrNull()!!.doc.scene[ElementId("l")],
        )
        assertEquals(40.0, after.fontSize)
        assertTrue(after.height.value > before.height.value, "the larger label is taller: ${before.height.value} -> ${after.height.value}")
        assertTrue(after.text.lines().size >= before.text.lines().size)
    }
}
