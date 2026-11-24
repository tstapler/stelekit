// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sp
import dev.stapler.stelekit.sketch.v2.tools.su
import dev.stapler.stelekit.ui.sketch.lengthTextOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Persistent dimension annotations: which are drawn, where, and the invariants (no overlap, inside the canvas, deterministic). */
class DimensionAnnotationModelTest {
    private val touch = 44.0

    private fun canvas(zoom: Double = 1.0, panX: Double = 0.0, panY: Double = 0.0, w: Double = 360.0, h: Double = 700.0) =
        PillCanvas({ x, y -> (x * zoom + panX) to (y * zoom + panY) }, zoom, w, h)

    private fun ellipse(id: String, cx: Int, cy: Int, rx: Int, ry: Int) = EllipseElement(ElementBase(ElementId(id)), su(cx), su(cy), su(rx), su(ry))

    private fun line(id: String, ax: Int, ay: Int, bx: Int, by: Int) = LineElement(ElementBase(ElementId(id)), listOf(sp(ax, ay), sp(bx, by)))

    private fun layout(elements: List<SceneElement>, canvas: PillCanvas = canvas(), reserved: List<AnnotationRect> = emptyList(), fontScale: Double = 1.0) =
        DimensionAnnotationLayout.layout(elements, elements, canvas, reserved, fontScale, touch)

    private fun List<DimensionAnnotation>.of(id: String, kind: PillKind) = first { it.elementId.value == id && it.kind == kind }

    @Test
    fun aRect_should_GetItsWidthAboveAndItsHeightToTheRight_WithExtensionLines() {
        val a = layout(listOf(filledRectEl("r", 100, 200, 160, 90)))
        val w = a.of("r", PillKind.Width)
        val h = a.of("r", PillKind.Height)
        assertTrue(w.line.a.y < 200.0 && w.line.b.y < 200.0, "the width line sits above the top edge")
        assertEquals(100.0, w.line.a.x, 0.001)
        assertEquals(260.0, w.line.b.x, 0.001)
        assertTrue(w.textRect.centreX in 100.0..260.0)
        assertTrue(h.line.a.x > 260.0 && h.line.b.x > 260.0, "the height line sits right of the right edge")
        assertEquals(2, w.extensions.size)
        assertEquals(lengthTextOf(160), w.text)
        assertEquals(lengthTextOf(90), h.text)
    }

    @Test
    fun aCircle_should_GetOneDiameterAnnotation_WithTheDiameterSymbol_AndALineGetsItsAlignedLength() {
        val a = layout(listOf(ellipse("c", 150, 300, 50, 50), line("l", 40, 500, 220, 560)))
        assertEquals(1, a.count { it.elementId.value == "c" })
        assertTrue(a.of("c", PillKind.Diameter).text.startsWith("Ø"))
        val l = a.of("l", PillKind.Length)
        assertEquals((220 - 40.0) / (560 - 500.0), (l.line.b.x - l.line.a.x) / (l.line.b.y - l.line.a.y), 0.001, "parallel to the line")
    }

    @Test
    fun anEllipse_should_GetWidthAndHeight_AndAPolylineNone() {
        val poly = LineElement(ElementBase(ElementId("p")), listOf(sp(0, 0), sp(10, 0), sp(10, 10)))
        val a = layout(listOf(ellipse("e", 150, 300, 70, 40), poly))
        assertEquals(setOf(PillKind.Width, PillKind.Height), a.filter { it.elementId.value == "e" }.map { it.kind }.toSet())
        assertTrue(a.none { it.elementId.value == "p" })
    }

    @Test
    fun aShapeTooSmallForItsText_should_BeHidden_AndFadeInAsTheZoomGrows() {
        val rect = filledRectEl("r", 10, 20, 30, 30)
        assertTrue(layout(listOf(rect), canvas(zoom = 0.5)).isEmpty())
        val alphas = listOf(1.0, 1.3, 1.6, 2.0, 4.0).map { z -> layout(listOf(rect), canvas(zoom = z)).firstOrNull { it.kind == PillKind.Width }?.alpha ?: 0.0 }
        assertEquals(alphas.sorted(), alphas, "alpha never drops as the zoom grows")
        assertEquals(1.0, alphas.last(), 0.0001)
    }

    @Test
    fun theText_should_NeverBeSmallerThanTheLegibleFloor_AtAnyFontScale() {
        assertTrue(DimensionAnnotationLayout.TEXT_SP >= DimensionAnnotationLayout.MIN_TEXT_SP)
        val (w1, h1) = DimensionAnnotationLayout.textSize("3' 7\"", 1.0)
        val (w2, h2) = DimensionAnnotationLayout.textSize("3' 7\"", 1.5)
        assertTrue(w2 > w1 && h2 > h1)
    }

    @Test
    fun stackedNeighbours_should_GetTheirOwnRows_WithoutOverlap() {
        val rects = listOf(filledRectEl("a", 60, 300, 200, 80), filledRectEl("b", 60, 340, 200, 80))
        val a = layout(rects)
        assertNoOverlap(a)
        assertEquals(2, a.count { it.kind == PillKind.Width })
    }

    @Test
    fun anAnnotation_should_KeepClearOfTheReservedPills() {
        val reserved = listOf(AnnotationRect(100.0, 140.0, 260.0, 190.0))
        val a = layout(listOf(filledRectEl("r", 100, 200, 160, 90)), reserved = reserved)
        assertTrue(a.none { it.textRect.overlaps(reserved.first()) })
        assertTrue(a.none { an -> reserved.first().crosses(an.line) })
    }

    @Test
    fun anIsolatedAnnotation_should_HaveAFingerSizedTarget_ThatContainsItsText() {
        val a = layout(listOf(filledRectEl("r", 100, 300, 160, 90))).of("r", PillKind.Width)
        assertTrue(a.tapRect.width >= touch - 0.001 && a.tapRect.height >= touch - 0.001)
        assertTrue(a.tapRect.contains(a.textRect))
    }

    @Test
    fun aShapePartlyOffscreen_should_KeepItsTextInsideTheCanvas() {
        val a = layout(listOf(filledRectEl("r", -400, 200, 800, 90)))
        assertTrue(a.isNotEmpty())
        assertInside(a, canvas())
    }

    @Test
    fun aLongAnnotatedList_should_BeCappedAtTheNamedLimit() {
        val many = (0 until DimensionAnnotationLayout.MAX_ANNOTATED_SHAPES * 2).map { filledRectEl("r$it", (it % 20) * 16, (it / 20) * 30, 14, 14) }
        val shapes = layout(many, canvas(zoom = 40.0, w = 5000.0, h = 5000.0)).map { it.elementId }.toSet()
        assertTrue(shapes.size <= DimensionAnnotationLayout.MAX_ANNOTATED_SHAPES)
    }

    @Test
    fun theLayout_should_BeTheSame_WhateverOrderTheShapesComeIn() {
        val shapes = randomShapes(Random(7), 12)
        assertEquals(layout(shapes), layout(shapes.reversed()))
        assertEquals(layout(shapes), layout(shapes))
    }

    @Test
    fun noTwoAnnotations_should_Overlap_AndEveryTextShouldStayInsideTheCanvas_Property() = runTest {
        checkAll(Arb.int(0, 5000), Arb.int(1, 8), Arb.int(0, 400)) { seed, zoomTenths, count ->
            val zoom = zoomTenths / 2.0
            val c = canvas(zoom = zoom, panX = (seed % 90).toDouble(), panY = (seed % 50).toDouble())
            val shapes = randomShapes(Random(seed), count % 40 + 1)
            val a = layout(shapes, c)
            assertNoOverlap(a)
            assertInside(a, c)
            assertTapRects(a)
            assertEquals(a, layout(shapes, c), "deterministic")
        }
    }

    @Test
    fun spokenLength_should_ReadFeetInchesAndFractionsInWords() {
        assertEquals("3 feet 7 inches", SpokenLength.of(3 * 192L + 7 * 16))
        assertEquals("1 foot", SpokenLength.of(192))
        assertEquals("1 inch", SpokenLength.of(16))
        assertEquals("7 and 3 eighths inches", SpokenLength.of(7 * 16L + 6))
        assertEquals("1 quarter of an inch", SpokenLength.of(4))
        assertEquals("1 sixteenth of an inch", SpokenLength.of(1))
        assertEquals("2 feet 1 and 1 half inches", SpokenLength.of(2 * 192L + 24))
    }

    @Test
    fun description_should_NameTheKindTheShapeAndTheSpokenValue() {
        val a = layout(listOf(filledRectEl("r", 100, 300, 3 * 192 + 7 * 16, 90))).of("r", PillKind.Width)
        assertTrue(DimensionAnnotationLayout.description(a).let { it.startsWith("Width of ") && it.endsWith(": 3 feet 7 inches") })
        assertNotEquals("", a.elementName)
    }

    private fun randomShapes(r: Random, n: Int): List<SceneElement> = (0 until n).map { i ->
        val x = r.nextInt(-100, 500)
        val y = r.nextInt(-100, 800)
        when (r.nextInt(3)) {
            0 -> filledRectEl("s$i", x, y, r.nextInt(10, 300), r.nextInt(10, 300))
            1 -> ellipse("s$i", x, y, r.nextInt(8, 100), r.nextInt(8, 100))
            else -> line("s$i", x, y, x + r.nextInt(-300, 300), y + r.nextInt(-300, 300))
        }
    }

    private fun assertNoOverlap(a: List<DimensionAnnotation>) {
        for (i in a.indices) for (j in i + 1 until a.size) {
            assertTrue(!a[i].textRect.overlaps(a[j].textRect), "texts of ${a[i].elementId.value} and ${a[j].elementId.value} overlap")
            assertTrue(!a[i].textRect.crosses(a[j].line) && !a[j].textRect.crosses(a[i].line), "a line crosses another text")
        }
    }

    private fun assertInside(a: List<DimensionAnnotation>, c: PillCanvas) {
        val area = AnnotationRect(0.0, 0.0, c.width, c.height)
        a.forEach { assertTrue(area.contains(it.textRect), "text ${it.textRect} outside the canvas") }
    }

    private fun assertTapRects(a: List<DimensionAnnotation>) {
        a.forEach { assertTrue(it.tapRect.contains(it.textRect), "a target must hold its text") }
        for (i in a.indices) for (j in i + 1 until a.size) assertTrue(!a[i].tapRect.overlaps(a[j].tapRect), "targets overlap")
    }
}
