// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.v2.SheetFixtures.u
import dev.stapler.stelekit.sketch.v2.W6Kit.circle
import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.bridge.DimensionExpectation
import dev.stapler.stelekit.sketch.v2.bridge.DimensionMismatch
import dev.stapler.stelekit.sketch.v2.bridge.Extent
import dev.stapler.stelekit.sketch.v2.bridge.MeasuredPart
import dev.stapler.stelekit.sketch.v2.bridge.ProjectionReports
import dev.stapler.stelekit.sketch.v2.bridge.V2ToV1
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.runtime.projection.SheetProjection
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.ui.sketch.v2.ProjectionMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 4.2.4a (metric 6): rounding happens once, at projection; a mismatch with a named dimension is reported, never snapped. */
class ProjectionFusionTest {
    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 = AddElements(elements.toList()).apply(SketchDocumentV2()).get().doc

    private fun wall(id: String, x: Double, width: Double) = RectElement(ElementBase(ElementId(id)), u(x), u(0.0), u(width), u(10.0))

    private fun wallA(requiredSixteenths: Long = WIDTH) =
        DimensionExpectation("Wall A", listOf(MeasuredPart(ElementId("wallA"), Extent.Width)), requiredSixteenths)

    private fun report(doc: SketchDocumentV2, vararg expectations: DimensionExpectation) =
        ProjectionReports.of(V2ToV1.projectScene(doc.scene), doc.scene.elementsInOrder.toList(), expectations.toList())

    @Test
    fun solvedWidthJustOverTheDimension_should_RoundOnceAndStillMatch() {
        val doc = docOf(wall("wallA", 0.0, 100.03))
        val projected = V2ToV1.projectScene(doc.scene).primitives.single() as SketchPrimitive.Rect
        assertEquals(WIDTH, projected.b.x.sixteenths - projected.a.x.sixteenths, "100.03 rounds to 100 sixteenths, once")
        val report = report(doc, wallA())
        assertEquals(emptyList(), report.mismatches)
        assertEquals(1, report.rounded, "the rectangle had a fractional edge")
    }

    @Test
    fun cornersThatRoundTheOtherWay_should_BeReportedAsOffByOneSixteenth_NeverSnapped() {
        val doc = docOf(wall("wallA", 0.4, 100.2))
        val report = report(doc, wallA())
        val mismatch = report.mismatches.single()
        assertEquals(DimensionMismatch("Wall A", WIDTH, WIDTH + 1), mismatch)
        assertEquals(1, mismatch.offBy)
        assertEquals("Wall A is off by 1/16 in", ProjectionMessages.mismatch(mismatch))
        val projected = V2ToV1.projectScene(doc.scene).primitives.single() as SketchPrimitive.Rect
        assertEquals(0, projected.a.x.sixteenths, "the primitive keeps its rounded value; nothing is adjusted to fit the dimension")
    }

    @Test
    fun aSumDimension_should_AddItsPartsExtents() {
        val doc = docOf(wall("p1", 0.0, 60.0), wall("p2", 60.0, 40.0))
        val sum = DimensionExpectation("Wall A", listOf(MeasuredPart(ElementId("p1"), Extent.Width), MeasuredPart(ElementId("p2"), Extent.Width)), WIDTH)
        assertEquals(emptyList(), report(doc, sum).mismatches)
        val short = sum.copy(requiredSixteenths = WIDTH + 16)
        assertEquals(16, report(doc, short).mismatches.single().offBy)
        assertEquals("Wall A is off by 1 in", ProjectionMessages.mismatch(report(doc, short).mismatches.single()))
    }

    @Test
    fun droppedElements_should_BeCountedAndWordedForTheUser() {
        val doc = docOf(wall("wallA", 0.0, 100.0), circle("c", 50, 50, 5))
        val report = report(doc, wallA())
        assertEquals(1, report.dropped)
        assertEquals(listOf("1 element not used for dimensions"), ProjectionMessages.lines(report))
        val two = docOf(circle("c1", 5, 5, 2), circle("c2", 20, 5, 2), wall("wallA", 0.4, 100.2))
        assertEquals(listOf("Wall A is off by 1/16 in", "2 elements not used for dimensions"), ProjectionMessages.lines(report(two, wallA())))
    }

    @Test
    fun aDimensionWhoseElementIsMissingOrDropped_should_NotBeReportedAsAMismatch() {
        val doc = docOf(circle("wallA", 10, 10, 4))
        assertEquals(emptyList(), report(doc, wallA()).mismatches, "there is nothing to measure, so there is nothing to call off")
        assertTrue(ProjectionMessages.lines(report(docOf(wall("wallA", 0.0, 100.0)), wallA())).isEmpty(), "an exact projection says nothing")
    }

    @Test
    fun sheetProjection_should_CheckItsPrimitivesAgainstTheNamedDimensions() {
        val doc = docOf(wall("wallA", 0.4, 100.2))
        val projection = V2ToV1.projectScene(doc.scene)
        val sheet = SheetProjection("sha", projection.primitives, projection.layers, projection.droppedCount, doc.scene, roundedCount = 1)
        assertEquals(1, sheet.mismatches(listOf(wallA())).single().offBy)
        assertEquals(1, sheet.roundedCount)
    }

    private companion object {
        const val WIDTH = 100L
    }
}
