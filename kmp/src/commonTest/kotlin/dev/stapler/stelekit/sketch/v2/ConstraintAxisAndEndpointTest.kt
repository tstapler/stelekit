// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.sketch.constraints.SolveResult
import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.Command
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
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.ui.sketch.v2.ConstrainFlyout
import dev.stapler.stelekit.ui.sketch.v2.ConstrainFlyoutModel
import dev.stapler.stelekit.ui.sketch.v2.ConstrainMenuItem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The solver's DistanceX/DistanceY and the endpoint tangents, reached through the constraint kinds, the parts table and the Constrain flyout. */
class ConstraintAxisAndEndpointTest {
    private fun <L, R> Either<L, R>.right(): R = fold({ error("unexpected Left: $it") }, { it })

    private fun u(d: Double) = SheetFixtures.u(d)

    private fun id(s: String) = ElementId(s)

    private fun base(s: String) = ElementBase(id = id(s))

    private fun point(s: String, x: Double, y: Double) = PointElement(base(s), u(x), u(y))

    private fun line(s: String, x0: Double, y0: Double, x1: Double, y1: Double) = LineElement(base(s), listOf(SheetFixtures.pt(x0, y0), SheetFixtures.pt(x1, y1)))

    private fun arc(s: String, cx: Double, cy: Double, r: Double, from: Double, to: Double) = ArcElement(base(s), u(cx), u(cy), u(r), from, to)

    private fun on(s: String, part: TargetPart = TargetPart.Whole) = ConstraintTarget(id(s), part)

    private fun c(name: String, kind: ConstraintKind, vararg targets: ConstraintTarget, value: Double? = null) = Constraint(ConstraintId(name), kind, targets.toList(), value)

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 = AddElements(elements.toList()).apply(SketchDocumentV2()).right().doc

    private fun SketchDocumentV2.with(vararg commands: Command): SketchDocumentV2 = commands.fold(this) { doc, command -> command.apply(doc).right().doc }

    private fun solved(doc: SketchDocumentV2): SketchSceneV2 {
        val out = SceneToProblem.build(doc.scene, doc.constraints).right().solve(doc.scene).right()
        assertTrue(out.result is SolveResult.Solved || out.result is SolveResult.Redundant, "solve ended as ${out.result}")
        return out.scene
    }

    private fun near(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < TOLERANCE, "expected $expected but was $actual")

    private fun pin(s: String) = AddConstraint(c("pin-$s", ConstraintKind.Fixed, on(s)))

    @Test
    fun distanceX_should_SetTheHorizontalGapAndLeaveTheVerticalOne() {
        val doc = docOf(point("p", 0.0, 0.0), point("q", 3.0, 4.0)).with(pin("p"), AddConstraint(c("k", ConstraintKind.DistanceX, on("p"), on("q"), value = 10.0)))
        val q = solved(doc)[id("q")] as PointElement
        near(10.0, q.x.value)
        near(4.0, q.y.value)
    }

    @Test
    fun distanceX_should_KeepTheSideTheSecondPointStartedOn() {
        val doc = docOf(point("p", 0.0, 0.0), point("q", -3.0, 4.0)).with(pin("p"), AddConstraint(c("k", ConstraintKind.DistanceX, on("p"), on("q"), value = 10.0)))
        near(-10.0, (solved(doc)[id("q")] as PointElement).x.value)
    }

    @Test
    fun distanceY_should_SetTheVerticalGapAndLeaveTheHorizontalOne() {
        val doc = docOf(point("p", 0.0, 0.0), point("q", 3.0, 4.0)).with(pin("p"), AddConstraint(c("k", ConstraintKind.DistanceY, on("p"), on("q"), value = 7.0)))
        val q = solved(doc)[id("q")] as PointElement
        near(3.0, q.x.value)
        near(7.0, q.y.value)
    }

    @Test
    fun axisDistances_should_BeDimensionsThatTheSheetFileKeeps() {
        val doc = docOf(point("p", 0.0, 0.0), point("q", 3.0, 4.0)).with(AddConstraint(c("k", ConstraintKind.DistanceX, on("p"), on("q"), value = 10.0)))
        assertTrue(doc.constraints.single().isDimensional)
        val back = SheetCodecV2.decode(SheetCodecV2.encode(doc).right()).right()
        assertEquals(ConstraintKind.DistanceX, back.constraints.single().kind)
        assertEquals(10.0, back.constraints.single().value)
    }

    @Test
    fun partsTable_should_AcceptTwoPointsForAnAxisDistanceAndNothingElse() {
        val p = point("p", 0.0, 0.0)
        val q = point("q", 1.0, 1.0)
        val l = line("l", 0.0, 0.0, 5.0, 0.0)
        assertTrue(ConstraintParts.accepts(ConstraintKind.DistanceX, listOf(PartRef(p, TargetPart.Whole), PartRef(q, TargetPart.Whole))))
        assertTrue(ConstraintParts.accepts(ConstraintKind.DistanceY, listOf(PartRef(l, TargetPart.Start), PartRef(l, TargetPart.End))))
        assertFalse(ConstraintParts.accepts(ConstraintKind.DistanceX, listOf(PartRef(l, TargetPart.Whole))))
    }

    @Test
    fun flyout_should_OfferTheAxisDistancesForTwoPointsOnTheFullTierOnly() {
        val points = listOf<SceneElement>(point("p", 0.0, 0.0), point("q", 1.0, 1.0))
        val full = ConstrainFlyoutModel.of(points, SolverTier.Full) as ConstrainFlyout.Open
        assertTrue(ConstrainMenuItem.HorizontalDistance in full.enabled && ConstrainMenuItem.VerticalDistance in full.enabled)
        val geometric = ConstrainFlyoutModel.of(points, SolverTier.GeometricOnly) as ConstrainFlyout.Open
        assertTrue(geometric.items.none { it.item == ConstrainMenuItem.HorizontalDistance || it.item == ConstrainMenuItem.VerticalDistance })
    }

    @Test
    fun tangentAtAnArcEnd_should_TurnALineIntoTheArcsTangentThere() {
        val bow = arc("a", 0.0, -5.0, 5.0, 0.0, PI / 2)
        val doc = docOf(bow, line("l", -10.0, 3.0, 0.5, 0.2)).with(
            pin("a"),
            AddConstraint(c("join", ConstraintKind.Coincident, on("l", TargetPart.End), on("a", TargetPart.ArcEnd))),
            AddConstraint(c("tan", ConstraintKind.Tangent, on("l"), on("a", TargetPart.ArcEnd))),
        )
        val out = solved(doc)[id("l")] as LineElement
        near(0.0, out.points.last().x.value)
        near(0.0, out.points.last().y.value)
        near(0.0, out.points.first().y.value)
    }

    @Test
    fun tangentOfTwoArcEnds_should_PutTheCentresOnOneLineWithTheJoint() {
        val first = arc("a", 0.0, -5.0, 5.0, 0.0, PI / 2)
        val second = arc("b", 0.4, 5.3, 5.0, -PI / 2, 0.0)
        val doc = docOf(first, second).with(
            pin("a"),
            AddConstraint(c("join", ConstraintKind.Coincident, on("a", TargetPart.ArcEnd), on("b", TargetPart.ArcStart))),
            AddConstraint(c("tan", ConstraintKind.Tangent, on("a", TargetPart.ArcEnd), on("b", TargetPart.ArcStart))),
        )
        val out = solved(doc)[id("b")] as ArcElement
        near(0.0, out.cx.value)
        near(0.0, out.cx.value + out.radius.value * cos(out.startAngle))
    }

    private companion object {
        const val TOLERANCE = 1e-4
    }
}
