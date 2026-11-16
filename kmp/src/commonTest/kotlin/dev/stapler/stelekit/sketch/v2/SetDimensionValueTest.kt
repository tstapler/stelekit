// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.DimensionAdd
import dev.stapler.stelekit.sketch.v2.constraints.DimensionAddPlan
import dev.stapler.stelekit.sketch.v2.constraints.SetConstraintReference
import dev.stapler.stelekit.sketch.v2.constraints.SetDimensionValue
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 4.2.7a: `SetDimensionValue`, the duplicate-dimension redirect and the Replace-existing lookup. */
class SetDimensionValueTest {
    private fun <L, R> Either<L, R>.right(): R = fold({ error("unexpected Left: $it") }, { it })

    private fun <L, R> Either<L, R>.left(): L = fold({ it }, { error("unexpected Right: $it") })

    private fun u(d: Double) = SheetFixtures.u(d)

    private fun id(s: String) = ElementId(s)

    private fun line(s: String, x0: Double, y0: Double, x1: Double, y1: Double) =
        LineElement(ElementBase(id = id(s)), listOf(SheetFixtures.pt(x0, y0), SheetFixtures.pt(x1, y1)))

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 = AddElements(elements.toList()).apply(SketchDocumentV2()).right().doc

    private fun SketchDocumentV2.with(vararg commands: Command): SketchDocumentV2 = commands.fold(this) { doc, c -> c.apply(doc).right().doc }

    private fun on(s: String, part: TargetPart = TargetPart.Whole) = ConstraintTarget(id(s), part)

    private fun c(name: String, kind: ConstraintKind, targets: List<ConstraintTarget>, value: Double? = null, reference: Boolean = false) =
        Constraint(ConstraintId(name), kind, targets, value, reference)

    private fun length(doc: SketchDocumentV2, name: String): Double {
        val l = doc.scene[id(name)] as LineElement
        return hypot(l.points[1].x.value - l.points[0].x.value, l.points[1].y.value - l.points[0].y.value)
    }

    private fun near(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < TOLERANCE, "expected $expected but was $actual")

    private fun pinnedHorizontalLine(): SketchDocumentV2 = docOf(line("l", 0.0, 0.0, 5.0, 0.0)).with(
        AddConstraint(c("pin", ConstraintKind.Fixed, listOf(on("l", TargetPart.Start)))),
        AddConstraint(c("h", ConstraintKind.Horizontal, listOf(on("l")))),
        AddConstraint(c("d", ConstraintKind.Distance, listOf(on("l")), 5.0)),
    )

    @Test
    fun setValue_should_ChangeTheValueAndMoveTheGeometry_InOneResult() {
        val doc = pinnedHorizontalLine()
        val applied = SetDimensionValue(ConstraintId("d"), 9.0).apply(doc).right()
        assertEquals(9.0, applied.doc.constraints.first { it.id.value == "d" }.value)
        near(9.0, length(applied.doc, "l"))
        assertTrue(id("l") in applied.changedIds)
        val l = applied.doc.scene[id("l")] as LineElement
        near(0.0, l.points[0].x.value)
        near(0.0, l.points[1].y.value)
    }

    @Test
    fun setValue_should_BeOneUndoStep_ThatRestoresValueAndGeometry() {
        val doc = pinnedHorizontalLine()
        val committed = Transaction.begin(doc).apply(SetDimensionValue(ConstraintId("d"), 9.0)).right().commit().right()
        val history = HistoryV2.of(doc).commit(committed).right()
        near(9.0, length(history.present, "l"))
        val undone = history.undo().present
        assertEquals(doc, undone)
        assertEquals(history.present, history.undo().redo().present)
    }

    @Test
    fun setValue_should_RefuseWhatItCannotDrive() {
        val doc = pinnedHorizontalLine().with(AddConstraint(c("ref", ConstraintKind.Distance, listOf(on("l", TargetPart.Start), on("l", TargetPart.End)), 5.0, reference = true)))
        assertEquals(CommandError.UnknownConstraint("nope"), SetDimensionValue(ConstraintId("nope"), 1.0).apply(doc).left())
        assertEquals(CommandError.InvalidConstraint("h", "reference"), SetDimensionValue(ConstraintId("h"), 1.0).apply(doc).left())
        assertEquals(CommandError.InvalidConstraint("ref", "reference"), SetDimensionValue(ConstraintId("ref"), 1.0).apply(doc).left())
        assertEquals(CommandError.InvalidConstraint("d", "value"), SetDimensionValue(ConstraintId("d"), 0.0).apply(doc).left())
        assertEquals(CommandError.InvalidConstraint("d", "value"), SetDimensionValue(ConstraintId("d"), -3.0).apply(doc).left())
        assertEquals(CommandError.InvalidConstraint("d", "value"), SetDimensionValue(ConstraintId("d"), Double.NaN).apply(doc).left())
    }

    @Test
    fun setValue_should_KeepTheLastGoodState_WhenTheNewValueConflicts() {
        val doc = pinnedHorizontalLine().with(AddConstraint(c("d2", ConstraintKind.Distance, listOf(on("l", TargetPart.Start), on("l", TargetPart.End)), 5.0)))
        val error = SetDimensionValue(ConstraintId("d"), 9.0).apply(doc).left()
        val unsolvable = assertIs<CommandError.Unsolvable>(error)
        assertEquals("d", unsolvable.id)
        assertTrue(unsolvable.conflictIds.isNotEmpty() && unsolvable.conflictIds.all { it in setOf("d", "d2") }, "named ${unsolvable.conflictIds}")
    }

    @Test
    fun setValue_should_ResizeARectangleEdge() {
        val doc = docOf(RectElement(ElementBase(id = id("r")), u(0.0), u(0.0), u(10.0), u(6.0)))
            .with(AddConstraint(c("d", ConstraintKind.Distance, listOf(on("r", TargetPart.Edge(1))), 6.0)))
        val out = SetDimensionValue(ConstraintId("d"), 11.0).apply(doc).right().doc.scene[id("r")] as RectElement
        near(11.0, out.height.value)
        near(10.0, out.width.value)
    }

    @Test
    fun setValue_should_NotMoveTheGeometry_WhenAReferenceBecomesDriving() {
        val doc = pinnedHorizontalLine().with(SetConstraintReference(ConstraintId("d"), true))
        assertNull(DimensionAdd.replaceable(doc, doc.constraints.first { it.id.value == "d" }, listOf("d")))
        near(5.0, length(doc, "l"))
    }

    @Test
    fun addConstraint_should_RedirectASecondDrivingDimensionOnTheSameTarget() {
        val doc = pinnedHorizontalLine()
        val second = c("d3", ConstraintKind.Distance, listOf(on("l")), 7.0)
        assertEquals(DimensionAddPlan.EditExisting(ConstraintId("d"), 5.0), DimensionAdd.plan(doc, second))
        assertEquals(CommandError.DuplicateDimension("d"), AddConstraint(second).apply(doc).left())
        assertEquals(DimensionAddPlan.Add, DimensionAdd.plan(doc, second.copy(reference = true)))
        assertEquals(DimensionAddPlan.Add, DimensionAdd.plan(doc, c("h2", ConstraintKind.Horizontal, listOf(on("l")))))
    }

    @Test
    fun twoPointDistance_should_RedirectInEitherOrder() {
        val doc = docOf(line("l", 0.0, 0.0, 5.0, 0.0)).with(AddConstraint(c("d", ConstraintKind.Distance, listOf(on("l", TargetPart.Start), on("l", TargetPart.End)), 5.0)))
        val reversed = c("d2", ConstraintKind.Distance, listOf(on("l", TargetPart.End), on("l", TargetPart.Start)), 8.0)
        assertEquals(DimensionAddPlan.EditExisting(ConstraintId("d"), 5.0), DimensionAdd.plan(doc, reversed))
    }

    @Test
    fun replaceable_should_NameTheDrivingDimensionOfTheSameMeasure_AndNothingForOtherConflicts() {
        val doc = pinnedHorizontalLine()
        val respelled = c("d2", ConstraintKind.Distance, listOf(on("l", TargetPart.Edge(0))), 7.0)
        assertEquals(ConstraintId("d"), DimensionAdd.replaceable(doc, respelled, listOf("d", "d2")))
        assertNull(DimensionAdd.replaceable(doc, respelled, listOf("h", "d2")), "the existing dimension must be in the conflict")
        val other = docOf(line("a", 0.0, 0.0, 5.0, 0.0), line("b", 0.0, 3.0, 5.0, 3.0))
            .with(AddConstraint(c("h", ConstraintKind.Horizontal, listOf(on("a")))))
        assertNull(DimensionAdd.replaceable(other, c("par", ConstraintKind.Parallel, listOf(on("a"), on("b"))), listOf("h", "par")))
    }

    private companion object {
        const val TOLERANCE = 1e-6
    }
}
