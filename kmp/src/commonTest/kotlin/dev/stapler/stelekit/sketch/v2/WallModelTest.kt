// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.codec.SheetError
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.FlipOpening
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.SetOpening
import dev.stapler.stelekit.sketch.v2.commands.SetWallPoints
import dev.stapler.stelekit.sketch.v2.commands.SetWallProperties
import dev.stapler.stelekit.sketch.v2.commands.SetWallSegmentLength
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningKind
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import dev.stapler.stelekit.sketch.v2.scene.WallJustification
import dev.stapler.stelekit.sketch.v2.scene.WallScene
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Walls and openings as scene elements: persistence, hosting, the commands, drawing and export. */
class WallModelTest {
    private fun u(d: Double) = SheetFixtures.u(d)

    private fun pt(x: Double, y: Double) = SheetFixtures.pt(x, y)

    private val roomPoints = listOf(pt(0.0, 0.0), pt(400.0, 0.0), pt(400.0, 300.0), pt(0.0, 300.0))

    private fun room(thickness: Double = 8.0) =
        WallElement(ElementBase(ElementId("w1"), ordinal = 1, style = ElementStyle(backgroundColor = "#ced4da", roughness = 0)), roomPoints, u(thickness), WallJustification.Centre, closed = true)

    private fun door(id: String = "d1", segment: Int = 0, offset: Double = 100.0, width: Double = 60.0, kind: OpeningKind = OpeningKind.Door) =
        OpeningElement(ElementBase(ElementId(id), ordinal = 1), ElementId("w1"), segment, u(offset), u(width), kind)

    private fun docOf(vararg elements: dev.stapler.stelekit.sketch.v2.scene.SceneElement): SketchDocumentV2 {
        val scene = elements.fold(SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull() ?: error("add ${e.id}") }
        return SketchDocumentV2(reconcile(scene).scene, "00000000-0000-0000-0000-000000000042")
    }

    private fun SketchDocumentV2.then(command: Command): SketchDocumentV2 = (command.apply(this).getOrNull() ?: error("command failed: $command")).doc

    private fun SketchDocumentV2.wall() = scene[ElementId("w1")] as WallElement

    private fun SketchDocumentV2.opening(id: String = "d1") = scene[ElementId(id)] as OpeningElement

    // ---- persistence -----------------------------------------------------------------------------------------------

    @Test
    fun codec_should_RoundTripWallsAndOpenings_ByteForByte() {
        val doc = docOf(room(), door(), door("win", 2, 150.0, 80.0, OpeningKind.Window))
        val bytes = SheetFixtures.bytes(doc)
        val back = SheetCodecV2.decode(bytes).getOrNull() ?: error("decode")
        assertEquals(doc.scene.elementsInOrder, back.scene.elementsInOrder)
        assertEquals(bytes.decodeToString(), SheetFixtures.bytes(back).decodeToString())
    }

    @Test
    fun codec_should_WriteTheWallAndOpeningShapeTheSchemaDocuments() {
        val text = SheetFixtures.bytes(docOf(room(), door())).decodeToString()
        assertTrue("\"type\":\"wall\"" in text && "\"just\":\"Centre\"" in text && "\"closed\":true" in text && "\"t\":8" in text, text)
        assertTrue("\"type\":\"opening\"" in text && "\"host\":\"w1\"" in text && "\"seg\":0" in text && "\"off\":100" in text && "\"kind\":\"Door\"" in text, text)
    }

    @Test
    fun codec_should_FailTheWholeSheetClosed_When_AnElementTypeIsUnknown() {
        // An older build has no "wall" type: it takes the same path as this unknown type and refuses the file (ADR-001), it does not drop the element.
        val text = SheetFixtures.bytes(docOf(room())).decodeToString().replace("\"type\":\"wall\"", "\"type\":\"wall-from-the-future\"")
        val error = SheetCodecV2.decode(text.encodeToByteArray()).swap().getOrNull()
        assertEquals(SheetError.InvalidElement(0, "type"), error)
    }

    @Test
    fun codec_should_RefuseDegenerateWallsAndOpenings() {
        val good = SheetFixtures.bytes(docOf(room(), door())).decodeToString()
        val tooFewPoints = good.replace("\"pts\":[[0,0],[400,0],[400,300],[0,300]]", "\"pts\":[[0,0],[400,0]]")
        assertIs<SheetError.InvalidElement>(SheetCodecV2.decode(tooFewPoints.encodeToByteArray()).swap().getOrNull())
        val zeroThick = good.replace("\"t\":8", "\"t\":0")
        assertIs<SheetError.InvalidElement>(SheetCodecV2.decode(zeroThick.encodeToByteArray()).swap().getOrNull())
        val zeroWide = good.replace("\"w\":60", "\"w\":0")
        assertIs<SheetError.InvalidElement>(SheetCodecV2.decode(zeroWide.encodeToByteArray()).swap().getOrNull())
        val repeated = good.replace("[400,0],[400,300]", "[400,0],[400,0]")
        assertIs<SheetError.InvalidElement>(SheetCodecV2.decode(repeated.encodeToByteArray()).swap().getOrNull())
    }

    // ---- hosting ---------------------------------------------------------------------------------------------------

    @Test
    fun reconcile_should_DeriveTheOpeningFootprintFromItsWall_And_BeIdempotent() {
        val doc = docOf(room(), door())
        val d = doc.opening()
        assertEquals(130.0, d.x.value + d.width.value / 2.0, 1e-9)
        assertEquals(0.0, d.y.value + d.height.value / 2.0, 1e-9)
        assertEquals(8.0, d.height.value, 1e-9)
        val again = reconcile(doc.scene)
        assertTrue(again.isClean, "a second pass reports nothing: ${again.reasons}")
        assertEquals(doc.scene.elementsInOrder, again.scene.elementsInOrder)
    }

    @Test
    fun opening_should_MoveWithItsWall() {
        val doc = docOf(room(), door()).then(MoveElements(listOf(ElementId("w1")), 50.0, 20.0))
        assertEquals(WallGeometry.vec(pt(50.0, 20.0)), WallGeometry.vec(doc.wall().points.first()))
        val d = doc.opening()
        assertEquals(180.0, d.x.value + d.width.value / 2.0, 1e-9)
        assertEquals(20.0, d.y.value + d.height.value / 2.0, 1e-9)
    }

    @Test
    fun opening_should_FollowItsSegmentWhenAnotherVertexMoves() {
        val doc = docOf(room(), door(segment = 1, offset = 50.0, width = 60.0)).then(SetWallPoints(ElementId("w1"), listOf(pt(0.0, 0.0), pt(500.0, 0.0), pt(500.0, 300.0), pt(0.0, 300.0))))
        val d = doc.opening()
        assertEquals(1, d.segment)
        assertEquals(500.0, d.x.value + d.width.value / 2.0, 1e-9)
        assertEquals(80.0, d.y.value + d.height.value / 2.0, 1e-9)
    }

    @Test
    fun opening_should_StayInsideItsWall_When_TheWallShrinksUnderIt() {
        val doc = docOf(room(), door(offset = 300.0, width = 80.0)).then(SetWallPoints(ElementId("w1"), listOf(pt(0.0, 0.0), pt(200.0, 0.0), pt(200.0, 300.0), pt(0.0, 300.0))))
        val d = doc.opening()
        val span = assertNotNull(WallGeometry.span(doc.wall(), 0))
        assertTrue(d.offset.value >= span.lo - 1e-9 && d.offset.value + d.width.value <= span.hi + 1e-9, "the opening was refitted inside the segment: $d vs $span")
        assertTrue(reconcile(doc.scene).isClean)
    }

    @Test
    fun deletingAWall_should_DeleteTheOpeningsOnIt() {
        val doc = docOf(room(), door(), door("d2", 3, 40.0, 50.0, OpeningKind.Window)).then(DeleteElements(listOf(ElementId("w1"))))
        assertEquals(0, doc.scene.size)
    }

    @Test
    fun openingWithoutAWall_should_BeKeptAsAnOrphan_Drawing_Nothing() {
        val doc = docOf(door())
        assertEquals(listOf(ElementId("d1")), WallScene.orphans(doc.scene).map { it.id })
        assertTrue(DrawListBuilder(FixedAdvanceTextMetricsProvider()).build(doc.scene, RenderStyle.Clean).isEmpty())
    }

    // ---- commands --------------------------------------------------------------------------------------------------

    @Test
    fun setWallSegmentLength_should_MoveTheEndVertex_And_CarryTheRestOfAnOpenChain() {
        val open = WallElement(ElementBase(ElementId("w1")), listOf(pt(0.0, 0.0), pt(100.0, 0.0), pt(100.0, 50.0)), u(8.0))
        val longer = docOf(open).then(SetWallSegmentLength(ElementId("w1"), 0, 150.0)).wall()
        assertEquals(listOf(WallGeometry.vec(pt(0.0, 0.0)), WallGeometry.vec(pt(150.0, 0.0)), WallGeometry.vec(pt(150.0, 50.0))), longer.points.map(WallGeometry::vec))
        val closed = docOf(room()).then(SetWallSegmentLength(ElementId("w1"), 0, 450.0)).wall()
        assertEquals(WallGeometry.vec(pt(450.0, 0.0)), WallGeometry.vec(closed.points[1]))
        assertEquals(WallGeometry.vec(pt(400.0, 300.0)), WallGeometry.vec(closed.points[2]))
        assertTrue(closed.closed)
    }

    @Test
    fun setWallSegmentLength_should_MoveTheFirstVertexOfAClosedWall_ForItsLastSegment() {
        val moved = docOf(room()).then(SetWallSegmentLength(ElementId("w1"), 3, 500.0)).wall()
        assertEquals(WallGeometry.vec(pt(0.0, -200.0)), WallGeometry.vec(moved.points[0]))
    }

    @Test
    fun flippingARoomWithItsDoor_should_KeepTheDoorSwingingTheSameWayRelativeToTheRoom() {
        val doc = docOf(room(), door().copy(flipSide = true))
        val flipped = doc.then(dev.stapler.stelekit.sketch.v2.commands.FlipElements(listOf(ElementId("w1"), ElementId("d1")), horizontal = true))
        assertEquals(false, flipped.opening().flipSide, "mirroring reverses the winding, so the inward swing is the other side of travel")
    }

    @Test
    fun setWallSegmentLength_should_RefuseAMissingSegmentAndANonPositiveLength() {
        val doc = docOf(room())
        assertFalse(SetWallSegmentLength(ElementId("w1"), 9, 10.0).apply(doc).isRight())
        assertFalse(SetWallSegmentLength(ElementId("w1"), 0, 0.0).apply(doc).isRight())
        assertFalse(SetWallSegmentLength(ElementId("w1"), 0, -5.0).apply(doc).isRight())
    }

    @Test
    fun setWallPoints_should_RefuseCoincidentVerticesAndTooFewPoints() {
        val doc = docOf(room())
        assertFalse(SetWallPoints(ElementId("w1"), listOf(pt(0.0, 0.0), pt(0.0, 0.0), pt(5.0, 5.0))).apply(doc).isRight())
        assertFalse(SetWallPoints(ElementId("w1"), listOf(pt(0.0, 0.0), pt(5.0, 5.0))).apply(doc).isRight(), "a closed wall keeps three vertices")
    }

    @Test
    fun setWallProperties_should_ChangeThicknessJustificationAndClosure() {
        val open = WallElement(ElementBase(ElementId("w1")), roomPoints, u(8.0))
        val doc = docOf(open).then(SetWallProperties(ElementId("w1"), thickness = 12.0, justification = WallJustification.Inside, closed = true))
        assertEquals(12.0, doc.wall().thickness.value)
        assertEquals(WallJustification.Inside, doc.wall().justification)
        assertTrue(doc.wall().closed)
        assertFalse(SetWallProperties(ElementId("w1"), thickness = 0.0).apply(doc).isRight())
    }

    @Test
    fun setOpening_should_TypeAWidthAndOffset_AndClampToTheWall() {
        val doc = docOf(room(), door())
        val typed = doc.then(SetOpening(ElementId("d1"), offset = 20.0, width = 36.0)).opening()
        assertEquals(20.0, typed.offset.value, 1e-9)
        assertEquals(36.0, typed.width.value, 1e-9)
        val past = doc.then(SetOpening(ElementId("d1"), offset = 5000.0)).opening()
        val span = assertNotNull(WallGeometry.span(doc.wall(), 0))
        assertEquals(span.hi, past.offset.value + past.width.value, 1e-9)
        assertFalse(SetOpening(ElementId("d1"), width = 0.0).apply(doc).isRight())
    }

    @Test
    fun flipOpening_should_ToggleTheSwingSideAndTheHinge() {
        val doc = docOf(room(), door())
        val flipped = doc.then(FlipOpening(ElementId("d1"))).opening()
        assertTrue(flipped.flipSide && !flipped.flipHinge)
        val both = doc.then(FlipOpening(ElementId("d1"), swing = true, hinge = true)).then(FlipOpening(ElementId("d1"), swing = false, hinge = true)).opening()
        assertTrue(both.flipSide && !both.flipHinge)
    }

    @Test
    fun draggingAnOpening_should_SlideItAlongItsWall_Not_AcrossIt() {
        val doc = docOf(room(), door()).then(MoveElements(listOf(ElementId("d1")), 40.0, 25.0))
        assertEquals(140.0, doc.opening().offset.value, 1e-9)
        assertEquals(0.0, doc.opening().y.value + doc.opening().height.value / 2.0, 1e-9)
    }

    // ---- drawing and export ----------------------------------------------------------------------------------------

    private fun ops(doc: SketchDocumentV2) = DrawListBuilder(FixedAdvanceTextMetricsProvider()).build(doc.scene, RenderStyle.Clean)

    @Test
    fun drawList_should_FillTheBodyAndStrokeTheFaces_ForAWall() {
        val list = ops(docOf(room()))
        val fill = list.filterIsInstance<DrawOp.PathOp>().single { it.fill != null }
        assertEquals("#ced4da", fill.fill)
        assertTrue(list.filterIsInstance<DrawOp.PathOp>().any { it.stroke != null && it.fill == null })
    }

    @Test
    fun drawList_should_CutTheBodyForADoor_AndDrawItsSwing() {
        val without = ops(docOf(room())).filterIsInstance<DrawOp.PathOp>().first { it.fill != null }
        val withDoor = ops(docOf(room(), door())).filterIsInstance<DrawOp.PathOp>()
        val body = withDoor.first { it.fill != null && it.elementId == ElementId("w1") }
        assertTrue(body.segments.size > without.segments.size, "a cut splits one quad into two")
        assertTrue(withDoor.any { it.elementId == ElementId("d1") && it.stroke != null }, "the leaf and arc belong to the door")
    }

    @Test
    fun drawList_should_FollowAMovedWall_WithoutAStaleCache() {
        val builder = DrawListBuilder(FixedAdvanceTextMetricsProvider())
        val doc = docOf(room(), door())
        val before = builder.build(doc.scene, RenderStyle.Clean)
        val after = builder.build(doc.then(MoveElements(listOf(ElementId("w1")), 100.0, 0.0)).scene, RenderStyle.Clean)
        assertFalse(before == after)
        assertEquals(before.size, after.size)
    }

    @Test
    fun lowering_should_ReplaceWallsAndOpeningsWithPlainLines_KeepingTheRest() {
        val line = LineElement(ElementBase(ElementId("l1")), listOf(pt(0.0, 0.0), pt(10.0, 10.0)))
        val lowered = WallScene.lowered(docOf(room(), door(), line).scene)
        assertTrue(lowered.elementsInOrder.none { it is WallElement || it is OpeningElement })
        assertTrue(lowered.elementsInOrder.size > 3 && ElementId("l1") in lowered)
        assertTrue(lowered.elementsInOrder.filter { it.id != ElementId("l1") }.all { it.base.style.roughness == 0 })
        assertEquals(docOf(line).scene.elementsInOrder, WallScene.lowered(docOf(line).scene).elementsInOrder, "a scene with no walls is returned as it was")
    }

    @Test
    fun excalidrawExport_should_LowerWallsToLines() {
        val text = ExcalidrawExporter().export(docOf(room(), door()).scene).getOrNull()?.text ?: error("export")
        assertTrue("\"type\":\"line\"" in text, "walls become lines")
        assertFalse("\"type\":\"wall\"" in text || "\"type\":\"opening\"" in text)
    }

    @Test
    fun addElement_should_AssignKindOrdinals_ForWallsAndDoors() {
        val doc = SketchDocumentV2(SketchSceneV2.empty(), "u").then(AddElement(room().copy(base = ElementBase(ElementId("w1"))))).then(AddElement(door().copy(base = ElementBase(ElementId("d1")))))
        assertEquals("Wall 1", dev.stapler.stelekit.sketch.v2.scene.ElementNames.nameOf(doc.wall()))
        assertEquals("Door 1", dev.stapler.stelekit.sketch.v2.scene.ElementNames.nameOf(doc.opening()))
    }
}
