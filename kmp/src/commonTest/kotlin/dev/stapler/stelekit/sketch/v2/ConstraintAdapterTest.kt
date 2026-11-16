// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.sketch.constraints.SolveResult
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.DocPatch
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.ElementKind
import dev.stapler.stelekit.sketch.v2.constraints.RemoveConstraint
import dev.stapler.stelekit.sketch.v2.constraints.SceneToProblem
import dev.stapler.stelekit.sketch.v2.constraints.SetConstraintReference
import dev.stapler.stelekit.sketch.v2.constraints.SketchReasonText
import dev.stapler.stelekit.sketch.v2.constraints.removedConstraintCount
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Story 4.2.1 (Tasks 4.2.1a-c): the typed model, the scene adapter, the cascade, the cap and the codec fields. */
class ConstraintAdapterTest {
    private fun <L, R> Either<L, R>.right(): R = fold({ error("unexpected Left: $it") }, { it })

    private fun <L, R> Either<L, R>.left(): L = fold({ it }, { error("unexpected Right: $it") })

    private fun u(d: Double) = SceneUnit.of(d).right()

    private fun id(s: String) = ElementId(s)

    private fun base(s: String) = ElementBase(id = id(s))

    private fun line(s: String, x0: Double, y0: Double, x1: Double, y1: Double) =
        LineElement(base(s), listOf(ScenePoint(u(x0), u(y0)), ScenePoint(u(x1), u(y1))))

    private fun point(s: String, x: Double, y: Double) = PointElement(base(s), u(x), u(y))

    private fun circle(s: String, x: Double, y: Double, r: Double) = EllipseElement(base(s), u(x), u(y), u(r), u(r))

    private fun target(s: String) = ConstraintTarget(id(s))

    private fun c(name: String, kind: ConstraintKind, vararg targets: String, value: Double? = null, reference: Boolean = false) =
        Constraint(ConstraintId(name), kind, targets.map { target(it) }, value, reference)

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 =
        AddElements(elements.toList()).apply(SketchDocumentV2()).right().doc

    private fun SketchDocumentV2.with(vararg commands: Command): SketchDocumentV2 =
        commands.fold(this) { doc, command -> command.apply(doc).right().doc }

    private fun solved(doc: SketchDocumentV2): SketchSceneV2 {
        val problem = SceneToProblem.build(doc.scene, doc.constraints).right()
        val out = problem.solve(doc.scene).right()
        assertTrue(out.result is SolveResult.Solved || out.result is SolveResult.Redundant, "solve ended as ${out.result}")
        return out.scene
    }

    private fun near(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < TOLERANCE, "expected $expected but was $actual")

    @Test
    fun coincident_should_PutPointOnLine_When_PointAndLineAreOperands() {
        val doc = docOf(point("p", 5.0, 3.0), line("l", 0.0, 0.0, 10.0, 0.0)).with(AddConstraint(c("pin", ConstraintKind.Fixed, "l")), AddConstraint(c("k", ConstraintKind.Coincident, "p", "l")))
        val scene = solved(doc)
        near(0.0, (scene[id("p")] as PointElement).y.value)
    }

    @Test
    fun coincident_should_JoinPrimaryPoints_When_BothOperandsAreNotPoints() {
        val doc = docOf(circle("a", 0.0, 0.0, 2.0), circle("b", 5.0, 5.0, 3.0)).with(AddConstraint(c("k", ConstraintKind.Coincident, "a", "b")))
        val scene = solved(doc)
        val a = scene[id("a")] as EllipseElement
        val b = scene[id("b")] as EllipseElement
        near(a.cx.value, b.cx.value)
        near(a.cy.value, b.cy.value)
    }

    @Test
    fun distance_should_ResizeAxisAlignedRect_When_TopEdgeIsDimensioned() {
        val rect = RectElement(base("r"), u(0.0), u(0.0), u(10.0), u(6.0))
        val doc = docOf(rect).with(AddConstraint(c("k", ConstraintKind.Distance, "r", value = 20.0)))
        val out = solved(doc)[id("r")] as RectElement
        near(20.0, out.width.value)
        near(6.0, out.height.value)
        assertEquals(0.0, out.angle)
    }

    @Test
    fun rect_should_StayARectangle_When_RotatedAndOneEdgeIsDimensioned() {
        val rect = RectElement(base("r"), u(0.0), u(0.0), u(10.0), u(6.0), angle = 0.5)
        val doc = docOf(rect).with(AddConstraint(c("k", ConstraintKind.Distance, "r", value = 12.0)))
        val out = solved(doc)[id("r")] as RectElement
        near(12.0, out.width.value)
        assertTrue(out.height.value > 0.0)
    }

    @Test
    fun radius_should_KeepCircleCircular_And_ArcEndOnItsCircle() {
        val arc = ArcElement(base("a"), u(0.0), u(0.0), u(5.0), 0.0, 1.5)
        val doc = docOf(circle("c", 1.0, 1.0, 3.0), arc)
            .with(AddConstraint(c("k1", ConstraintKind.Radius, "c", value = 7.0)), AddConstraint(c("k2", ConstraintKind.Diameter, "a", value = 16.0)))
        val scene = solved(doc)
        val ring = scene[id("c")] as EllipseElement
        assertEquals(ring.rx, ring.ry)
        near(7.0, ring.rx.value)
        val out = scene[id("a")] as ArcElement
        near(8.0, out.radius.value)
        assertTrue(out.endAngle > out.startAngle, "the arc keeps its winding")
    }

    @Test
    fun tangent_should_TouchLineToCircle_And_EqualMatchCircles() {
        val doc = docOf(line("l", -10.0, 0.0, 10.0, 0.0), circle("c", 0.0, 5.0, 3.0), circle("d", 20.0, 20.0, 9.0))
            .with(AddConstraint(c("pin", ConstraintKind.Fixed, "l")), AddConstraint(c("k1", ConstraintKind.Tangent, "l", "c")), AddConstraint(c("k2", ConstraintKind.Equal, "c", "d")))
        val scene = solved(doc)
        val c = scene[id("c")] as EllipseElement
        val d = scene[id("d")] as EllipseElement
        near(c.rx.value, d.rx.value)
        near(c.rx.value, c.cy.value)
    }

    @Test
    fun orientation_angle_and_symmetry_should_SolveOverLines() {
        val doc = docOf(line("a", 0.0, 0.0, 10.0, 2.0), line("b", 0.0, 5.0, 4.0, 9.0), point("p", 1.0, 1.0), point("q", 3.0, 5.0), line("axis", 0.0, 0.0, 0.0, 10.0))
            .with(
                AddConstraint(c("h", ConstraintKind.Horizontal, "a")),
                AddConstraint(c("perp", ConstraintKind.Perpendicular, "a", "b")),
                AddConstraint(c("sym", ConstraintKind.Symmetric, "p", "q", "axis")),
            )
        val scene = solved(doc)
        val a = scene[id("a")] as LineElement
        near(a.points[0].y.value, a.points[1].y.value)
        val b = scene[id("b")] as LineElement
        near(b.points[0].x.value, b.points[1].x.value)
    }

    @Test
    fun fixed_should_HoldEveryPointOfALine() {
        val doc = docOf(line("l", 0.0, 0.0, 10.0, 0.0), point("p", 4.0, 4.0)).with(
            AddConstraint(c("f", ConstraintKind.Fixed, "l")),
            AddConstraint(c("k", ConstraintKind.Coincident, "p", "l")),
        )
        val scene = solved(doc)
        val held = scene[id("l")] as LineElement
        near(0.0, held.points[0].y.value)
        near(10.0, held.points[1].x.value)
        near(0.0, (scene[id("p")] as PointElement).y.value)
    }

    @Test
    fun add_should_RejectUnequalEllipse_And_NonTechnicalElements() {
        val doc = docOf(EllipseElement(base("e"), u(0.0), u(0.0), u(3.0), u(4.0)), TextElement(base("t"), u(0.0), u(0.0), u(5.0), u(5.0), text = "x"), point("p", 0.0, 0.0))
        val ellipse = AddConstraint(c("k", ConstraintKind.Radius, "e", value = 2.0)).apply(doc).left()
        assertEquals(CommandError.InvalidConstraint("k", "ellipse"), ellipse)
        val text = AddConstraint(c("k", ConstraintKind.Fixed, "t")).apply(doc).left()
        assertEquals(CommandError.NotConstrainable(ElementKind.Text), text)
        assertEquals(SketchReasonText.NotConstrainable.isNotEmpty(), true)
    }

    @Test
    fun add_should_RejectBadShapes_And_UnknownOperands() {
        val doc = docOf(point("p", 0.0, 0.0), line("l", 0.0, 0.0, 1.0, 1.0))
        assertEquals(CommandError.InvalidConstraint("k", "operands"), AddConstraint(c("k", ConstraintKind.Parallel, "l")).apply(doc).left())
        assertEquals(CommandError.InvalidConstraint("k", "value"), AddConstraint(c("k", ConstraintKind.Radius, "p")).apply(doc).left())
        assertEquals(CommandError.InvalidConstraint("k", "value"), AddConstraint(c("k", ConstraintKind.Fixed, "p", value = 1.0)).apply(doc).left())
        assertEquals(CommandError.InvalidConstraint("k", "operands"), AddConstraint(c("k", ConstraintKind.Parallel, "p", "l")).apply(doc).left())
        assertEquals(CommandError.UnknownElement("zz"), AddConstraint(c("k", ConstraintKind.Fixed, "zz")).apply(doc).left())
        val once = doc.with(AddConstraint(c("k", ConstraintKind.Fixed, "p")))
        assertEquals(CommandError.DuplicateConstraint("k"), AddConstraint(c("k", ConstraintKind.Fixed, "p")).apply(once).left())
    }

    @Test
    fun reference_should_MeasureWithoutDriving_And_BackToDrivingKeepsGeometry() {
        val doc = docOf(point("a", 0.0, 0.0), point("b", 3.0, 4.0)).with(AddConstraint(c("d", ConstraintKind.Distance, "a", "b", value = 99.0)))
        val asReference = doc.with(SetConstraintReference(ConstraintId("d"), true))
        val kept = asReference.constraints.single()
        assertTrue(kept.reference)
        near(5.0, assertNotNull(kept.value))
        assertEquals(doc.scene, solved(asReference))
        val driving = asReference.with(SetConstraintReference(ConstraintId("d"), false))
        near(5.0, assertNotNull(driving.constraints.single().value))
        assertEquals(doc.scene, solved(driving))
    }

    @Test
    fun delete_should_CascadeConstraints_And_OneUndoRestoresAllThree() {
        val doc = docOf(line("L1", 0.0, 0.0, 10.0, 1.0), point("p", 20.0, 20.0))
            .with(AddConstraint(c("c1", ConstraintKind.Horizontal, "L1")), AddConstraint(c("c2", ConstraintKind.Distance, "L1", value = 12.0)), AddConstraint(c("c3", ConstraintKind.Fixed, "p")))
        val history = HistoryV2.of(doc)
        val tx = Transaction.begin(history.present).apply(DeleteElements(listOf(id("L1")))).right()
        val step = tx.commit().right()
        val after = history.commit(step).right()
        assertEquals(listOf(ConstraintId("c3")), after.present.constraints.map { it.id })
        assertEquals(2, removedConstraintCount(step.before, step.after))
        val undone = after.undo().present
        assertEquals(doc, undone)
        assertEquals(listOf("c1", "c2", "c3"), undone.constraints.map { it.id.value })
        assertEquals(after.present, step.patch.forward(undone).right())
        assertEquals(undone, step.patch.backward(after.present).right())
    }

    @Test
    fun patch_should_RefuseAStaleConstraintList() {
        val doc = docOf(point("p", 0.0, 0.0))
        val withC = doc.with(AddConstraint(c("k", ConstraintKind.Fixed, "p")))
        val patch = DocPatch.diff(doc, withC)
        assertEquals(CommandError.PatchConflict("constraints"), patch.forward(withC).left())
        assertEquals(withC, patch.forward(doc).right())
    }

    @Test
    fun cap_should_RefuseThe201st_And_ChangeNothing() {
        val limit = SceneLimits.Default.maxConstraints
        var doc = docOf(point("p", 0.0, 0.0))
        repeat(limit) { n -> doc = doc.with(AddConstraint(c("k$n", ConstraintKind.Fixed, "p"))) }
        assertEquals(limit, doc.constraints.size)
        val refused = AddConstraint(c("extra", ConstraintKind.Fixed, "p")).apply(doc).left()
        assertEquals(CommandError.ConstraintCapExceeded(limit), refused)
        assertEquals("Constraint limit reached ($limit)", SketchReasonText.constraintLimitReached(limit))
        assertEquals(limit, doc.constraints.size)
    }

    @Test
    fun remove_should_DropOneConstraint_And_RefuseAnUnknownId() {
        val doc = docOf(point("p", 0.0, 0.0)).with(AddConstraint(c("k", ConstraintKind.Fixed, "p")))
        assertEquals(emptyList(), doc.with(RemoveConstraint(ConstraintId("k"))).constraints)
        assertEquals(CommandError.UnknownConstraint("zz"), RemoveConstraint(ConstraintId("zz")).apply(doc).left())
    }

    @Test
    fun codec_should_RoundTripTypedConstraints_WithStableIds() {
        val doc = docOf(point("p", 1.0, 2.0), line("l", 0.0, 0.0, 3.0, 4.0), circle("c", 0.0, 0.0, 2.0)).with(
            AddConstraint(c("k-1", ConstraintKind.Coincident, "p", "l")),
            AddConstraint(c("k-2", ConstraintKind.Distance, "l", value = 5.0)),
            AddConstraint(c("k-3", ConstraintKind.Radius, "c", value = 0.1, reference = true)),
        ).copy(sheetUuid = "s1")
        val bytes = SheetCodecV2.encode(doc).right()
        val back = SheetCodecV2.decode(bytes).right()
        assertEquals(doc.constraints, back.constraints)
        assertEquals(bytes.decodeToString(), SheetCodecV2.encode(back).right().decodeToString())
    }

    @Test
    fun codec_should_RefuseMalformedConstraints_And_DropOrphans() {
        fun sheet(constraints: String) = "{\"schema\":2,\"sheetUuid\":\"s\",\"constraints\":[$constraints],\"elements\":[]}".encodeToByteArray()
        val orphan = "{\"id\":\"k\",\"kind\":\"Fixed\",\"targets\":[{\"e\":\"gone\",\"part\":\"whole\"}]}"
        assertEquals(emptyList(), SheetCodecV2.decode(sheet(orphan)).right().constraints)
        val unknownKind = "{\"id\":\"k\",\"kind\":\"Wobble\",\"targets\":[]}"
        assertIs<dev.stapler.stelekit.sketch.v2.codec.SheetError.Malformed>(SheetCodecV2.decode(sheet(unknownKind)).left())
        val noValue = "{\"id\":\"k\",\"kind\":\"Radius\",\"targets\":[{\"e\":\"x\",\"part\":\"whole\"}]}"
        assertIs<dev.stapler.stelekit.sketch.v2.codec.SheetError.Malformed>(SheetCodecV2.decode(sheet(noValue)).left())
        val dup = "{\"id\":\"k\",\"kind\":\"Fixed\",\"targets\":[{\"e\":\"x\",\"part\":\"whole\"}]}"
        assertIs<dev.stapler.stelekit.sketch.v2.codec.SheetError.Malformed>(SheetCodecV2.decode(sheet("$dup,$dup")).left())
        val part = "{\"id\":\"k\",\"kind\":\"Fixed\",\"targets\":[{\"e\":\"x\",\"part\":\"start\"}]}"
        assertIs<dev.stapler.stelekit.sketch.v2.codec.SheetError.Malformed>(SheetCodecV2.decode(sheet(part)).left())
    }

    @Test
    fun measure_should_ReportCurrentDistance() {
        val doc = docOf(point("a", 0.0, 0.0), point("b", 3.0, 4.0))
        val measured = SceneToProblem.measure(doc.scene, c("m", ConstraintKind.Distance, "a", "b", value = 0.0)).right()
        near(hypot(3.0, 4.0), measured)
    }

    private companion object {
        const val TOLERANCE = 1e-6
    }
}
