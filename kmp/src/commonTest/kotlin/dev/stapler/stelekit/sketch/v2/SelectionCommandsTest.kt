// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.FlipElements
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.SequenceCommand
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `FlipElements` and `SequenceCommand`, the two commands the Select tool added (Story 3.2.2b). */
class SelectionCommandsTest {
    private fun u(i: Int) = SceneUnit.ofInt(i)

    private fun id(s: String) = ElementId(s)

    private fun docOf(vararg e: SceneElement) = AddElements(e.toList()).apply(SketchDocumentV2()).getOrNull()!!.doc

    private fun flip(doc: SketchDocumentV2, horizontal: Boolean, vararg ids: String) =
        FlipElements(ids.map { id(it) }, horizontal).apply(doc).getOrNull()!!.doc

    @Test
    fun flipHorizontal_should_MirrorBoxesPointsEllipsesAndKeepSizes() {
        val rect = RectElement(ElementBase(id("r")), u(0), u(0), u(40), u(20))
        val line = LineElement(ElementBase(id("l")), listOf(ScenePoint(u(0), u(0)), ScenePoint(u(100), u(10))))
        val ell = EllipseElement(ElementBase(id("e")), u(80), u(50), u(10), u(5))
        val dot = PointElement(ElementBase(id("p")), u(100), u(100))
        val out = flip(docOf(rect, line, ell, dot), true, "r", "l", "e", "p")
        val r = out.scene[id("r")] as RectElement
        assertEquals(60.0, r.x.value)
        assertEquals(40.0, r.width.value)
        assertEquals(listOf(100.0, 0.0), (out.scene[id("l")] as LineElement).points.map { it.x.value })
        assertEquals(20.0, (out.scene[id("e")] as EllipseElement).cx.value)
        assertEquals(0.0, (out.scene[id("p")] as PointElement).x.value)
    }

    @Test
    fun flippingOneRotatedBox_should_KeepItsPlace_And_NegateItsAngle() {
        val rect = RectElement(ElementBase(id("r")), u(10), u(10), u(40), u(20), angle = 0.5)
        val out = flip(docOf(rect), true, "r").scene[id("r")] as RectElement
        assertEquals(-0.5, out.angle)
        assertEquals(10.0, out.x.value, 1e-9)
    }

    @Test
    fun flipVertical_should_MirrorYAndToggleImageFlag_And_FlipTwiceReturnsTheOriginal() {
        val img = ImageElement(ElementBase(id("i")), u(0), u(0), u(10), u(10), fileId = "abc")
        val dot = PointElement(ElementBase(id("p")), u(5), u(100))
        val doc = docOf(img, dot)
        val once = flip(doc, false, "i", "p")
        assertTrue((once.scene[id("i")] as ImageElement).flipY)
        assertEquals(0.0, (once.scene[id("p")] as PointElement).y.value)
        val twice = flip(once, false, "i", "p")
        assertEquals(false, (twice.scene[id("i")] as ImageElement).flipY)
        assertEquals(0.0, (twice.scene[id("i")] as ImageElement).y.value)
        assertEquals(5.0, (twice.scene[id("p")] as PointElement).x.value)
        assertEquals(100.0, (twice.scene[id("p")] as PointElement).y.value)
    }

    @Test
    fun flippedArc_should_KeepEndAngleAfterStart_And_BeItsOwnMirror() {
        val arc = ArcElement(ElementBase(id("a")), u(0), u(0), u(10), 0.2, 1.0)
        val h = flip(docOf(arc), true, "a").scene[id("a")] as ArcElement
        assertTrue(h.endAngle > h.startAngle)
        assertEquals(PI - 1.0, h.startAngle, 1e-12)
        assertEquals(PI - 0.2, h.endAngle, 1e-12)
        val v = flip(docOf(arc), false, "a").scene[id("a")] as ArcElement
        assertEquals(-1.0, v.startAngle, 1e-12)
        assertEquals(-0.2, v.endAngle, 1e-12)
    }

    @Test
    fun flip_should_RefuseAnUnknownElement() {
        val result = FlipElements(listOf(id("nope")), true).apply(SketchDocumentV2())
        assertIs<CommandError.UnknownElement>(result.leftOrNull())
    }

    @Test
    fun sequence_should_ApplyInOrder_And_FailAsAWhole() {
        val doc = docOf(RectElement(ElementBase(id("r")), u(0), u(0), u(10), u(10)))
        val applied = SequenceCommand(listOf(MoveElements(listOf(id("r")), 5.0, 0.0), MoveElements(listOf(id("r")), 0.0, 7.0))).apply(doc).getOrNull()!!
        val moved = applied.doc.scene[id("r")] as RectElement
        assertEquals(5.0, moved.x.value)
        assertEquals(7.0, moved.y.value)
        val failed = SequenceCommand(listOf(MoveElements(listOf(id("r")), 1.0, 0.0), MoveElements(listOf(id("missing")), 1.0, 0.0))).apply(doc)
        assertTrue(failed.isLeft())
        assertEquals(0.0, (doc.scene[id("r")] as RectElement).x.value)
    }
}
