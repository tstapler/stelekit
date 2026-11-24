// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.SetTextBox
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.StyleText
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import dev.stapler.stelekit.sketch.v2.text.TextAutoSize
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextSizeLimits
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A font whose glyphs are half an em wide and whose line is the size, so a size change visibly changes every measurement. */
private class EmProvider(override val fontStatus: FontStatus = FontStatus.Loaded) : TextMetricsProvider {
    override fun lineHeight(style: TextStyle): Double = style.fontSize

    override fun advance(text: String, style: TextStyle): Double = text.length * style.fontSize / 2.0

    override fun hasGlyph(codePoint: Int, style: TextStyle): Boolean = true
}

/** Text auto-size and resize: the box follows the glyphs, a handle drag scales the font, the font size stays inside its limits. */
class TextAutoSizeTest {
    private val metrics = EmProvider()
    private val id = ElementId("t")

    /** "Door opening" (12 chars) at [size]: 6 wide per unit of size, one line of 1.25 * size. */
    private fun text(size: Double = 20.0, raw: String = "Door opening", x: Int = 100, y: Int = 100, auto: Boolean = true, width: Double? = null, align: TextAlign = TextAlign.Left, v: VerticalAlign = VerticalAlign.Top): TextElement {
        val fit = TextAutoSize.fit(raw, TextStyle(1, size), TextElement.DEFAULT_LINE_HEIGHT, width, metrics)!!
        return TextElement(
            ElementBase(id), su(x), su(y), unit(fit.width), unit(fit.height), text = fit.text, originalText = raw,
            fontSize = size, autoResize = auto, textAlign = align, verticalAlign = v,
        )
    }

    private fun ownBox(e: TextElement) = Box(e.x.value, e.y.value, e.width.value, e.height.value)

    private fun unit(v: Double) = dev.stapler.stelekit.sketch.v2.scene.SceneUnit.of(v).getOrNull()!!

    private fun doc(e: SceneElement) = SketchDocumentV2(SketchSceneV2.empty().add(e).getOrNull()!!)

    private fun run(e: SceneElement, c: Command): TextElement = assertIs(c.apply(doc(e)).getOrNull()!!.doc.scene[e.id])

    @Test
    fun fit_should_GrowWithTheFontSize_AndFollowTheLongestLine() {
        val small = TextAutoSize.fit("ab\nabcd", TextStyle(1, 10.0), 1.25, null, metrics)!!
        val big = TextAutoSize.fit("ab\nabcd", TextStyle(1, 20.0), 1.25, null, metrics)!!
        assertEquals(20.0, small.width)
        assertEquals(40.0, big.width)
        assertEquals(2 * 10.0 * 1.25, small.height)
        assertEquals(2 * small.height, big.height)
    }

    @Test
    fun fit_should_WrapAtAFixedWidth_AndKeepThatWidthAsTheBox() {
        val fit = TextAutoSize.fit("aa bb cc dd", TextStyle(1, 10.0), 1.25, 30.0, metrics)!!
        assertEquals("aa bb\ncc dd", fit.text)
        assertEquals(30.0, fit.width)
        assertEquals(2 * 12.5, fit.height)
    }

    @Test
    fun fit_should_BeNull_WhileTheFontIsLoading() {
        assertEquals(null, TextAutoSize.fit("x", TextStyle(1, 10.0), 1.25, null, EmProvider(FontStatus.Loading)))
    }

    @Test
    fun fontSizeChange_should_ReFitTheBox_SoTheFrameMatchesTheGlyphs() {
        val before = text(20.0)
        val after = run(before, StyleText(listOf(id), fontSize = 40.0, metrics = metrics))
        assertEquals(40.0, after.fontSize)
        assertEquals(12 * 20.0, after.width.value)
        assertEquals(1.25 * 40.0, after.height.value)
        assertEquals(before.x.value, after.x.value, "left-aligned text keeps its left edge")
        assertEquals(before.y.value, after.y.value, "top-aligned text keeps its top")
    }

    @Test
    fun fontSizeChange_should_KeepTheCentre_ForCentredText() {
        val before = text(20.0, align = TextAlign.Center, v = VerticalAlign.Middle)
        val after = run(before, StyleText(listOf(id), fontSize = 10.0, metrics = metrics))
        assertEquals(before.x.value + before.width.value / 2, after.x.value + after.width.value / 2, 1e-9)
        assertEquals(before.y.value + before.height.value / 2, after.y.value + after.height.value / 2, 1e-9)
    }

    @Test
    fun fontSizeChange_should_RewrapAFixedWidthBox_AndOnlyChangeItsHeight() {
        val before = text(10.0, raw = "aa bb cc dd", auto = false, width = 60.0)
        assertEquals(1, before.text.lines().size, "at size 10 the whole line is 55 wide and fits")
        val after = run(before, StyleText(listOf(id), fontSize = 20.0, metrics = metrics))
        assertEquals(60.0, after.width.value)
        assertEquals("aa bb\ncc dd", after.text)
        assertEquals(2 * 25.0, after.height.value)
    }

    @Test
    fun fontSizeChange_should_ScaleTheBox_WhenThereIsNoMeasurer() {
        val before = text(20.0)
        val after = run(before, StyleText(listOf(id), fontSize = 40.0))
        assertEquals(before.width.value * 2, after.width.value, 1e-9)
        assertEquals(before.height.value * 2, after.height.value, 1e-9)
    }

    @Test
    fun alignmentOnlyChange_should_LeaveTheBoxAlone() {
        val before = text(20.0)
        val after = run(before, StyleText(listOf(id), textAlign = TextAlign.Right, metrics = metrics))
        assertEquals(before.width, after.width)
        assertEquals(before.x, after.x)
    }

    @Test
    fun cornerDrag_should_ScaleTheFontWithTheBox_KeepingTheOppositeCornerFixed() {
        val e = text(20.0)
        val box = ownBox(e)
        val t = TextResize.handleDrag(e, box, Handle.SE, box.width, box.height, fromCentre = false, metrics = metrics)
        assertEquals(40.0, t.fontSize, 1e-9)
        assertEquals(box.x, t.x, 1e-9)
        assertEquals(box.y, t.y, 1e-9)
        assertEquals(box.width * 2, t.width, 1e-9)
        assertEquals(box.height * 2, t.height, 1e-9)
        assertEquals(e.autoResize, t.autoResize)
    }

    @Test
    fun cornerDrag_should_KeepTheAspect_AndStayInsideTheSizeLimits_ForEveryDragAndCorner() {
        val e = text(20.0)
        val box = ownBox(e)
        val aspect = box.width / box.height
        for (handle in listOf(Handle.NW, Handle.NE, Handle.SE, Handle.SW)) {
            for (dx in -400..400 step 37) for (dy in -400..400 step 41) {
                val t = TextResize.handleDrag(e, box, handle, dx.toDouble(), dy.toDouble(), fromCentre = false, metrics = metrics)
                assertTrue(t.fontSize in TextSizeLimits.MIN..TextSizeLimits.MAX, "$handle $dx,$dy gave size ${t.fontSize}")
                assertEquals(aspect, t.width / t.height, 1e-6, "$handle $dx,$dy broke the aspect")
                assertEquals(t.fontSize / e.fontSize, t.width / box.width, 1e-6, "$handle $dx,$dy: box and font scale differ")
            }
        }
    }

    @Test
    fun cornerDrag_should_ClampTheFont_AndNeverFlipOrVanish() {
        val e = text(20.0)
        val box = ownBox(e)
        val tiny = TextResize.handleDrag(e, box, Handle.SE, -box.width * 10, -box.height * 10, fromCentre = false, metrics = metrics)
        assertEquals(TextSizeLimits.MIN, tiny.fontSize)
        assertTrue(tiny.width > 0 && tiny.height > 0)
        val huge = TextResize.handleDrag(e, box, Handle.SE, box.width * 100, box.height * 100, fromCentre = false, metrics = metrics)
        assertEquals(TextSizeLimits.MAX, huge.fontSize)
    }

    @Test
    fun cornerDrag_should_ScaleAboutTheCentre_WhenAltIsHeld() {
        val e = text(20.0)
        val box = ownBox(e)
        val t = TextResize.handleDrag(e, box, Handle.SE, box.width / 2, box.height / 2, fromCentre = true, metrics = metrics)
        assertEquals(box.cx, t.x + t.width / 2, 1e-9)
        assertEquals(box.cy, t.y + t.height / 2, 1e-9)
    }

    @Test
    fun topEdgeDrag_should_ScaleTheFont_NotStretchTheBox() {
        val e = text(20.0)
        val box = ownBox(e)
        val t = TextResize.handleDrag(e, box, Handle.S, 0.0, box.height, fromCentre = false, metrics = metrics)
        assertEquals(40.0, t.fontSize, 1e-9)
        assertEquals(box.width * 2, t.width, 1e-9)
    }

    @Test
    fun sideDrag_should_SetAFixedWrapWidth_RewrapTheText_AndKeepTheFont() {
        val e = text(20.0, raw = "Door opening wide")
        val box = ownBox(e)
        val t = TextResize.handleDrag(e, box, Handle.E, 60.0 - box.width, 0.0, fromCentre = false, metrics = metrics)
        assertEquals(20.0, t.fontSize)
        assertEquals(60.0, t.width, 1e-9)
        assertEquals(false, t.autoResize)
        val lines = t.text.lines()
        assertTrue(lines.size > 1, "narrowed text wraps: ${t.text}")
        assertTrue(lines.all { it.length * 10.0 <= 60.0 }, "every line fits the wrap width: $lines")
        assertEquals(lines.size * 25.0, t.height, 1e-9)
        assertEquals(box.x, t.x, 1e-9)
        assertEquals(box.y, t.y, 1e-9, "the top stays put")
    }

    @Test
    fun sideDrag_on_theLeftEdge_should_KeepTheRightEdgeFixed() {
        val e = text(20.0, raw = "Door opening wide")
        val box = ownBox(e)
        val t = TextResize.handleDrag(e, box, Handle.W, box.width - 70.0, 0.0, fromCentre = false, metrics = metrics)
        assertEquals(70.0, t.width, 1e-9)
        assertEquals(box.x + box.width, t.x + t.width, 1e-9)
    }

    @Test
    fun sideDrag_on_rotatedText_should_KeepTheOppositeEdgeFixedInTheWorld() {
        for (angle in listOf(0.7, 1.5707963267948966, -2.1, 3.0)) {
            val e = text(20.0, raw = "Door opening wide").copy(angle = angle)
            val box = ownBox(e)
            val t = TextResize.handleDrag(e, box, Handle.E, 40.0 * kotlin.math.cos(angle), 40.0 * kotlin.math.sin(angle), fromCentre = false, metrics = metrics)
            fun westEdge(x: Double, y: Double, w: Double, h: Double): Pair<Double, Double> {
                val cx = x + w / 2
                val cy = y + h / 2
                return (cx - w / 2 * kotlin.math.cos(angle)) to (cy - w / 2 * kotlin.math.sin(angle))
            }
            val before = westEdge(box.x, box.y, box.width, box.height)
            val after = westEdge(t.x, t.y, t.width, t.height)
            assertEquals(before.first, after.first, 1e-6, "angle $angle: west edge x moved")
            assertEquals(before.second, after.second, 1e-6, "angle $angle: west edge y moved")
        }
    }

    @Test
    fun sideDrag_should_NeverWrapNarrowerThanOneEm() {
        val e = text(20.0)
        val box = ownBox(e)
        val t = TextResize.handleDrag(e, box, Handle.E, -box.width * 5, 0.0, fromCentre = false, metrics = metrics)
        assertTrue(t.width >= e.fontSize)
    }

    @Test
    fun sideDrag_should_ScaleLikeACorner_WhenNoMeasurerIsAvailable() {
        val e = text(20.0)
        val box = ownBox(e)
        val t = TextResize.handleDrag(e, box, Handle.E, box.width, 0.0, fromCentre = false, metrics = null)
        assertEquals(40.0, t.fontSize, 1e-9)
        assertEquals(e.autoResize, t.autoResize)
    }

    @Test
    fun groupScale_should_ScaleTheFontByTheSmallerAxis() {
        val e = text(20.0)
        val box = ownBox(e)
        val t = TextResize.groupScale(e, box, Box(0.0, 0.0, box.width * 2, box.height * 3))
        assertEquals(40.0, t.fontSize, 1e-9)
        assertEquals(box.width * 2, t.width, 1e-9)
    }

    @Test
    fun selectTool_should_ScaleTheFont_When_ACornerOfATextIsDragged() {
        val e = text(20.0, x = 100, y = 100)
        val ctx = testToolContext(sceneOfElements(e), zoom = 4.0).copy(textMetrics = metrics)
        val right = (e.x.value + e.width.value).toInt()
        val bottom = (e.y.value + e.height.value).toInt()
        val r = runScenario(SelectTool(listOf(id)), ctx) { down(right, bottom); move(right + 100, bottom + 20); up(right + 100, bottom + 20) }
        val step = assertIs<TransformSelectionCommand>(r.commands.single()).steps.single()
        val box = assertIs<ElementTransform.TextBox>(step)
        assertTrue(box.fontSize > 20.0, "a corner drag outward grows the font, got ${box.fontSize}")
        val applied = assertNotNull(commitToolCommand(doc(e), r.commands.single()).present.scene[id] as? TextElement)
        assertEquals(box.fontSize, applied.fontSize)
        assertEquals(box.width, applied.width.value, 1e-9)
    }

    @Test
    fun setTextBox_should_RoundTripThroughTheSheetCodec() {
        val e = text(20.0, raw = "Door opening wide")
        val moved = SetTextBox(id, 10.0, 20.0, 60.0, 75.0, 20.0, "Door\nopening\nwide", autoResize = false).apply(doc(e)).getOrNull()!!.doc
        val back = SheetCodecV2.decode(SheetCodecV2.encode(moved).getOrNull()!!).getOrNull()!!
        val t = assertIs<TextElement>(back.scene[id])
        assertEquals(false, t.autoResize)
        assertEquals("Door\nopening\nwide", t.text)
        assertEquals("Door opening wide", t.originalText)
        assertEquals(60.0, t.width.value)
    }

    @Test
    fun sizeLimits_should_StepThroughTheLadder_AndReportTheBoundTheyHit() {
        assertEquals(24.0, TextSizeLimits.stepUp(20.0).size)
        assertEquals(18.0, TextSizeLimits.stepDown(20.0).size)
        assertEquals(TextSizeLimits.Bound.Max, TextSizeLimits.stepUp(TextSizeLimits.MAX).hit)
        assertEquals(TextSizeLimits.Bound.Min, TextSizeLimits.stepDown(TextSizeLimits.MIN).hit)
        assertEquals(TextSizeLimits.Bound.Min, TextSizeLimits.clamp(1.0).hit)
        assertEquals(TextSizeLimits.MAX, TextSizeLimits.clamp(5000.0).size)
        assertEquals(null, TextSizeLimits.clamp(20.0).hit)
        assertEquals(22.0, TextSizeLimits.clamp(22.0).size)
        assertEquals(24.0, TextSizeLimits.stepUp(22.0).size, "an in-between size steps to the next ladder size")
        assertTrue(TextSizeLimits.LADDER.all { it in TextSizeLimits.MIN..TextSizeLimits.MAX })
    }

    @Test
    fun stepping_should_AlwaysMoveInTheRightDirection_FromAnySize() {
        var size = 0.5
        while (size < 4000.0) {
            assertTrue(TextSizeLimits.stepUp(size).size >= minOf(size, TextSizeLimits.MAX) - 1e-9 || size > TextSizeLimits.MAX)
            assertTrue(TextSizeLimits.stepDown(size).size <= maxOf(size, TextSizeLimits.MIN) + 1e-9 || size < TextSizeLimits.MIN)
            assertTrue(abs(TextSizeLimits.clamp(size).size - size) < 1e-9 || size !in TextSizeLimits.MIN..TextSizeLimits.MAX)
            assertTrue(TextSizeLimits.stepUp(size).size >= size, "A+ from $size gave ${TextSizeLimits.stepUp(size).size}")
            assertTrue(TextSizeLimits.stepDown(size).size <= size, "A- from $size gave ${TextSizeLimits.stepDown(size).size}")
            size *= 1.37
        }
    }

    @Test
    fun outOfRangeSizes_should_BeLeftAlone_ByIdentityAndTinyChanges() {
        val huge = text(300.0)
        assertEquals(300.0, TextResize.groupScale(huge, ownBox(huge), ownBox(huge)).fontSize, "a group scale of exactly 1 keeps 300")
        val tiny = text(4.0)
        val box = ownBox(tiny)
        val nudged = TextResize.handleDrag(tiny, box, Handle.SE, 0.01, 0.01, fromCentre = false, metrics = metrics)
        assertTrue(nudged.fontSize < 4.1, "a 0.01 nudge on size 4 gave ${nudged.fontSize}")
        assertTrue(nudged.width < box.width * 1.05, "a 0.01 nudge on size 4 grew the box from ${box.width} to ${nudged.width}")
        val grown = TextResize.handleDrag(huge, ownBox(huge), Handle.SE, 10.0, 10.0, fromCentre = false, metrics = metrics)
        assertTrue(grown.fontSize >= 256.0 && grown.fontSize <= 300.0, "growing size 300 neither shrinks below the limit nor grows: ${grown.fontSize}")
        assertEquals(300.0, TextResize.handleDrag(huge, ownBox(huge), Handle.SE, 0.0, 0.0, fromCentre = false, metrics = metrics).fontSize)
    }

    /** Every guide the preview draws must sit on an edge of the text box that commits (#723: a guide shows a snap that happened). */
    private fun assertGuidesHonoured(drags: List<Pair<Int, Int>>, vararg neighbours: SceneElement) {
        val e = text(20.0, x = 0, y = 0)
        val ctx = testToolContext(sceneOfElements(e, *neighbours), zoom = 4.0).copy(snap = dev.stapler.stelekit.sketch.v2.snap.SnapSettings(objects = true), textMetrics = metrics)
        for ((tx, ty) in drags) {
            val r = runScenario(SelectTool(listOf(id)), ctx) { down(120, 25); move(tx, ty); up(tx, ty) }
            val box = assertIs<ElementTransform.TextBox>(assertIs<TransformSelectionCommand>(r.commands.single()).steps.single())
            val guides = r.previews.filterNotNull().last().guides
            for (g in guides) {
                val edges = if (g.axis == dev.stapler.stelekit.sketch.v2.snap.GuideAxis.Vertical) listOf(box.x, box.x + box.width) else listOf(box.y, box.y + box.height)
                assertTrue(edges.any { abs(it - g.position) < 1e-6 }, "drag to $tx,$ty shows $g but commits edges $edges (font ${box.fontSize})")
            }
        }
    }

    @Test
    fun cornerDrag_should_OnlyShowAGuide_ForAnEdgeThatLandsOnIt() {
        assertGuidesHonoured(listOf(299 to 200, 299 to 40, 297 to 30), filledRectEl("right", 300, 0, 100, 120))
    }

    @Test
    fun cornerDrag_should_SnapTheTextEdge_WhenItsAxisLeadsTheRatio() {
        val e = text(20.0, x = 0, y = 0)
        val ctx = testToolContext(sceneOfElements(e, filledRectEl("right", 300, 0, 100, 120)), zoom = 4.0)
            .copy(snap = dev.stapler.stelekit.sketch.v2.snap.SnapSettings(objects = true), textMetrics = metrics)
        val r = runScenario(SelectTool(listOf(id)), ctx) { down(120, 25); move(299, 40); up(299, 40) }
        val box = assertIs<ElementTransform.TextBox>(assertIs<TransformSelectionCommand>(r.commands.single()).steps.single())
        assertEquals(300.0, box.x + box.width, 1e-6, "x grows most, so the right edge snaps to the neighbour")
        assertEquals(50.0, box.fontSize, 1e-6)
        assertEquals(1, r.previews.filterNotNull().last().guides.size)
    }

    @Test
    fun cornerDrag_should_DropTheGuide_WhenTheFontLimitStopsTheEdgeShortOfIt() {
        assertGuidesHonoured(listOf(2999 to 30), filledRectEl("far", 3000, 0, 100, 120))
    }
}
