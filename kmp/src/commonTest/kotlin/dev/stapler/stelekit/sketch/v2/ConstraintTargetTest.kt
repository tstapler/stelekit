// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.sketch.constraints.SolveResult
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintParts
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.PartRef
import dev.stapler.stelekit.sketch.v2.constraints.SceneToProblem
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 4.2.6a: the part model, its codec field, the accepted-parts table against the adapter, and named-part solving. */
class ConstraintTargetTest {
    private fun <L, R> Either<L, R>.right(): R = fold({ error("unexpected Left: $it") }, { it })

    private fun <L, R> Either<L, R>.left(): L = fold({ it }, { error("unexpected Right: $it") })

    private fun u(d: Double) = SheetFixtures.u(d)

    private fun id(s: String) = ElementId(s)

    private fun base(s: String) = ElementBase(id = id(s))

    private fun pts(vararg xy: Double) = xy.toList().chunked(2).map { ScenePoint(u(it[0]), u(it[1])) }

    private fun line(s: String, vararg xy: Double) = LineElement(base(s), pts(*xy))

    private fun point(s: String, x: Double, y: Double) = PointElement(base(s), u(x), u(y))

    private fun circle(s: String, x: Double, y: Double, r: Double) = EllipseElement(base(s), u(x), u(y), u(r), u(r))

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 = AddElements(elements.toList()).apply(SketchDocumentV2()).right().doc

    private fun on(s: String, part: TargetPart) = ConstraintTarget(id(s), part)

    private fun near(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < TOLERANCE, "expected $expected but was $actual")

    private fun solve(doc: SketchDocumentV2): SketchDocumentV2 {
        val out = SceneToProblem.build(doc.scene, doc.constraints).right().solve(doc.scene).right()
        assertTrue(out.result is SolveResult.Solved || out.result is SolveResult.Redundant, "solve ended as ${out.result}")
        return doc.copy(scene = out.scene)
    }

    private fun SketchDocumentV2.add(c: Constraint): SketchDocumentV2 = AddConstraint(c).apply(this).right().doc

    private val allParts = listOf(
        TargetPart.Whole, TargetPart.Start, TargetPart.End, TargetPart.Vertex(0), TargetPart.Vertex(31), TargetPart.Edge(0), TargetPart.Edge(7),
        TargetPart.Centre, TargetPart.ArcStart, TargetPart.ArcEnd,
    )

    @Test
    fun label_should_RoundTripThroughParse_ForEveryPart() {
        for (part in allParts) assertEquals(part, TargetPart.parse(part.label, LIMIT), part.label)
    }

    @Test
    fun parse_should_RejectUnknownLabelsAndBadIndexes() {
        for (bad in listOf("", "Whole", "vertex:", "vertex:-1", "vertex:01", "vertex:1.5", "vertex:x", "edge:", "edge:32", "vertex:99999999999", "centre ", "arcstart")) {
            assertNull(TargetPart.parse(bad, LIMIT), "'$bad' must not parse")
        }
    }

    @Test
    fun codec_should_RoundTripNamedParts_And_RejectAnUnknownPart() {
        val doc = docOf(line("l", 0.0, 0.0, 4.0, 0.0), line("m", 4.0, 0.0, 4.0, 3.0), circle("c", 9.0, 9.0, 2.0))
            .add(Constraint(ConstraintId("k1"), ConstraintKind.Coincident, listOf(on("l", TargetPart.End), on("m", TargetPart.Start))))
            .add(Constraint(ConstraintId("k2"), ConstraintKind.Distance, listOf(on("m", TargetPart.Edge(0))), 3.0))
            .add(Constraint(ConstraintId("k3"), ConstraintKind.Coincident, listOf(on("c", TargetPart.Centre), on("l", TargetPart.Vertex(0)))))
        val bytes = SheetCodecV2.encode(doc).right()
        val back = SheetCodecV2.decode(bytes).right()
        assertEquals(doc.constraints, back.constraints)
        assertTrue(bytes.decodeToString().contains("\"part\":\"edge:0\""))

        val tampered = bytes.decodeToString().replace("\"part\":\"edge:0\"", "\"part\":\"edge:minus\"").encodeToByteArray()
        assertIs<dev.stapler.stelekit.sketch.v2.codec.SheetError.Malformed>(SheetCodecV2.decode(tampered).left())
    }

    @Test
    fun partsOf_should_ListEachTargetOncePerKind() {
        assertEquals(listOf(TargetPart.Whole), ConstraintParts.partsOf(point("p", 0.0, 0.0)))
        assertEquals(
            listOf(TargetPart.Start, TargetPart.End, TargetPart.Vertex(1), TargetPart.Edge(0), TargetPart.Edge(1)),
            ConstraintParts.partsOf(line("l", 0.0, 0.0, 1.0, 1.0, 2.0, 0.0)),
        )
        assertEquals(
            listOf(TargetPart.Start, TargetPart.End, TargetPart.Vertex(1), TargetPart.Edge(0), TargetPart.Edge(1), TargetPart.Edge(2)),
            ConstraintParts.partsOf(LineElement(base("g"), pts(0.0, 0.0, 1.0, 1.0, 2.0, 0.0), polygon = true)),
        )
        assertEquals(8, ConstraintParts.partsOf(RectElement(base("r"), u(0.0), u(0.0), u(2.0), u(2.0))).size)
        assertEquals(listOf(TargetPart.Centre, TargetPart.Whole), ConstraintParts.partsOf(circle("c", 0.0, 0.0, 1.0)))
        assertEquals(4, ConstraintParts.partsOf(ArcElement(base("a"), u(0.0), u(0.0), u(2.0), 0.0, 1.0)).size)
        assertEquals(emptyList(), ConstraintParts.partsOf(EllipseElement(base("e"), u(0.0), u(0.0), u(2.0), u(1.0))))
    }

    @Test
    fun table_should_AgreeWithTheAdapter_ForEveryKindAndPartCombination() {
        val elements = listOf(
            point("p", 1.0, 1.0),
            line("l", 0.0, 0.0, 6.0, 0.0, 6.0, 4.0),
            RectElement(base("r"), u(10.0), u(10.0), u(8.0), u(5.0)),
            circle("c", 30.0, 5.0, 3.0),
            ArcElement(base("a"), u(40.0), u(5.0), u(4.0), 0.2, 2.0),
        )
        val doc = docOf(*elements.toTypedArray())
        val refs = elements.flatMap { e ->
            val parts = ConstraintParts.partsOf(e) + (TargetPart.Whole.takeIf { e !is PointElement && e !is EllipseElement && e !is ArcElement }?.let { listOf(it) } ?: emptyList()) + listOf(TargetPart.Centre, TargetPart.Vertex(9), TargetPart.Edge(9))
            parts.map { PartRef(e, it) }
        }
        var accepted = 0
        for (kind in ConstraintKind.entries) {
            val arity = Constraint.arityOf(kind)
            for (size in arity.min..arity.max) {
                for (combo in combinations(refs, size)) {
                    val constraint = Constraint(
                        ConstraintId("k"), kind, combo.map { ConstraintTarget(it.element.id, it.part) },
                        value = if (kind in Constraint.DIMENSIONAL) 1.0 else null,
                    )
                    val builds = SceneToProblem.build(doc.scene, listOf(constraint)).isRight()
                    val table = ConstraintParts.accepts(kind, combo)
                    assertEquals(builds, table, "$kind ${combo.map { it.element.id.value + ":" + it.part.label }}")
                    if (table) accepted++
                }
            }
        }
        assertTrue(accepted > MIN_ACCEPTED_COMBINATIONS, "the sweep must cover real combinations, saw $accepted")
    }

    private fun combinations(refs: List<PartRef>, size: Int): List<List<PartRef>> =
        if (size == 0) listOf(emptyList()) else combinations(refs, size - 1).flatMap { prefix -> refs.map { prefix + it } }

    @Test
    fun addConstraint_should_RefuseAPartTheElementDoesNotHave() {
        val doc = docOf(line("l", 0.0, 0.0, 4.0, 0.0), RectElement(base("r"), u(0.0), u(5.0), u(2.0), u(2.0)))
        val badIndex = Constraint(ConstraintId("k"), ConstraintKind.Fixed, listOf(on("l", TargetPart.Vertex(9))))
        assertEquals(CommandError.InvalidConstraint("k", "operands"), AddConstraint(badIndex).apply(doc).left())
        val rectCentre = Constraint(ConstraintId("k"), ConstraintKind.Fixed, listOf(on("r", TargetPart.Centre)))
        assertEquals(CommandError.InvalidConstraint("k", "operands"), AddConstraint(rectCentre).apply(doc).left())
    }

    @Test
    fun candidates_should_ShowOnlyTargetsTheArmedConstraintCanUse() {
        val l = line("l", 0.0, 0.0, 4.0, 0.0)
        val r = RectElement(base("r"), u(0.0), u(5.0), u(2.0), u(2.0))
        val c = circle("c", 9.0, 9.0, 2.0)
        val afterEnd = listOf(PartRef(l, TargetPart.End))
        val coincident = ConstraintParts.candidates(ConstraintKind.Coincident, afterEnd, r)
        assertTrue(TargetPart.Vertex(2) in coincident && TargetPart.Edge(1) in coincident)
        assertEquals(listOf(TargetPart.Centre, TargetPart.Whole), ConstraintParts.candidates(ConstraintKind.Coincident, afterEnd, c))
        assertEquals(emptyList(), ConstraintParts.candidates(ConstraintKind.Parallel, listOf(PartRef(l, TargetPart.Edge(0))), c))
        assertEquals(setOf<TargetPart>(TargetPart.Edge(0), TargetPart.Edge(1), TargetPart.Edge(2), TargetPart.Edge(3)), ConstraintParts.candidates(ConstraintKind.Parallel, listOf(PartRef(l, TargetPart.Edge(0))), r).toSet())
        assertEquals(listOf(TargetPart.Whole), ConstraintParts.candidates(ConstraintKind.Radius, emptyList(), c).filter { it == TargetPart.Whole })
    }

    @Test
    fun coincident_should_JoinNamedEndpoints() {
        val doc = docOf(line("l", 0.0, 0.0, 4.0, 0.0), line("m", 7.0, 2.0, 7.0, 9.0))
            .add(Constraint(ConstraintId("pin"), ConstraintKind.Fixed, listOf(on("l", TargetPart.Edge(0)))))
            .add(Constraint(ConstraintId("k"), ConstraintKind.Coincident, listOf(on("l", TargetPart.End), on("m", TargetPart.Start))))
        val m = solve(doc).scene[id("m")] as LineElement
        near(4.0, m.points[0].x.value)
        near(0.0, m.points[0].y.value)
        near(7.0, m.points[1].x.value)
    }

    @Test
    fun distance_should_DimensionBetweenNamedPoints_And_AnEdgeOfARectangle() {
        val doc = docOf(line("l", 0.0, 0.0, 4.0, 0.0), RectElement(base("r"), u(0.0), u(10.0), u(8.0), u(5.0)))
            .add(Constraint(ConstraintId("pin"), ConstraintKind.Fixed, listOf(on("l", TargetPart.Start))))
            .add(Constraint(ConstraintId("h"), ConstraintKind.Horizontal, listOf(on("l", TargetPart.Start), on("l", TargetPart.End))))
            .add(Constraint(ConstraintId("d1"), ConstraintKind.Distance, listOf(on("l", TargetPart.Start), on("l", TargetPart.End)), 9.0))
            .add(Constraint(ConstraintId("d2"), ConstraintKind.Distance, listOf(on("r", TargetPart.Edge(1))), 6.0))
        val out = solve(doc).scene
        val l = out[id("l")] as LineElement
        near(9.0, hypot(l.points[1].x.value - l.points[0].x.value, l.points[1].y.value - l.points[0].y.value))
        near(0.0, l.points[1].y.value)
        near(6.0, (out[id("r")] as RectElement).height.value)
    }

    @Test
    fun distance_should_MeasureACircleCentreToTheLineOfAnEdge_InEitherOrder() {
        val base = docOf(line("l", 0.0, 0.0, 40.0, 0.0), circle("c", 10.0, 7.0, 3.0))
            .add(Constraint(ConstraintId("pin"), ConstraintKind.Fixed, listOf(on("l", TargetPart.Whole))))
        for ((n, targets) in listOf(listOf(on("c", TargetPart.Centre), on("l", TargetPart.Edge(0))), listOf(on("l", TargetPart.Edge(0)), on("c", TargetPart.Centre))).withIndex()) {
            val out = solve(base.add(Constraint(ConstraintId("d$n"), ConstraintKind.Distance, targets, 12.0))).scene
            val c = out[id("c")] as EllipseElement
            near(12.0, kotlin.math.abs(c.cy.value))
        }
    }

    @Test
    fun fixed_should_PinOnlyTheNamedPoint() {
        val doc = docOf(line("l", 0.0, 0.0, 4.0, 0.0), point("p", 3.0, 3.0))
            .add(Constraint(ConstraintId("pin"), ConstraintKind.Fixed, listOf(on("l", TargetPart.Start))))
            .add(Constraint(ConstraintId("k"), ConstraintKind.Coincident, listOf(on("p", TargetPart.Whole), on("l", TargetPart.End))))
        val out = solve(doc).scene
        val l = out[id("l")] as LineElement
        near(0.0, l.points[0].x.value)
        near(0.0, l.points[0].y.value)
        val p = out[id("p")] as PointElement
        near(l.points[1].x.value, p.x.value)
        near(l.points[1].y.value, p.y.value)
    }

    @Test
    fun coincident_should_PutAnArcEndOnALineEdge_And_CircleCentreOnAPoint() {
        val doc = docOf(circle("c", 5.0, 5.0, 2.0), point("p", 0.0, 0.0), line("l", -5.0, 1.0, 5.0, 1.0), ArcElement(base("a"), u(0.0), u(0.0), u(2.0), 0.0, 1.0))
            .add(Constraint(ConstraintId("pinP"), ConstraintKind.Fixed, listOf(on("p", TargetPart.Whole))))
            .add(Constraint(ConstraintId("pinL"), ConstraintKind.Fixed, listOf(on("l", TargetPart.Edge(0)))))
            .add(Constraint(ConstraintId("k1"), ConstraintKind.Coincident, listOf(on("c", TargetPart.Centre), on("p", TargetPart.Whole))))
            .add(Constraint(ConstraintId("k2"), ConstraintKind.Coincident, listOf(on("a", TargetPart.ArcEnd), on("l", TargetPart.Edge(0)))))
        val out = solve(doc).scene
        val c = out[id("c")] as EllipseElement
        near(0.0, c.cx.value)
        near(0.0, c.cy.value)
        val a = out[id("a")] as ArcElement
        near(1.0, a.cy.value + a.radius.value * kotlin.math.sin(a.endAngle))
    }

    private companion object {
        const val LIMIT = 32
        const val TOLERANCE = 1e-6
        const val MIN_ACCEPTED_COMBINATIONS = 200
    }
}
