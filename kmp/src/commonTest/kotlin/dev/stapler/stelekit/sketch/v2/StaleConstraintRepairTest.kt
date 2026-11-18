// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.W6Kit.run
import dev.stapler.stelekit.sketch.v2.W6Kit.u
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SetLinePoints
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.SceneToProblem
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Review-fix D3: a constraint on a vertex that does not exist is dropped at decode and on edit, and one bad constraint cannot fail every solve. */
class StaleConstraintRepairTest {
    private fun line(id: String, n: Int) =
        LineElement(ElementBase(ElementId(id)), List(n) { ScenePoint(u(it * 10), u(it * 3)) })

    private fun fixed(name: String, element: String, part: TargetPart) =
        Constraint(ConstraintId(name), ConstraintKind.Fixed, listOf(ConstraintTarget(ElementId(element), part)), null)

    private fun horizontal(name: String, element: String) =
        Constraint(ConstraintId(name), ConstraintKind.Horizontal, listOf(ConstraintTarget(ElementId(element))), null)

    private val twoPointWithStaleVertex: SketchDocumentV2 =
        run(SketchDocumentV2(), AddElements(listOf(line("L", 2)))).copy(constraints = listOf(fixed("stale", "L", TargetPart.Vertex(3)), horizontal("h", "L")))

    @Test
    fun decode_should_DropAConstraintWhosePartDoesNotExist() {
        val bytes = SheetCodecV2.encode(twoPointWithStaleVertex).get()

        val back = SheetCodecV2.decode(bytes).get()

        assertEquals(listOf("h"), back.constraints.map { it.id.value }, "vertex:3 of a 2-point line is gone, the good constraint stays")
    }

    @Test
    fun setLinePoints_should_PruneConstraintsOnRemovedVertices() {
        val doc = run(SketchDocumentV2(), AddElements(listOf(line("L", 4))), AddConstraint(fixed("v2", "L", TargetPart.Vertex(2))), AddConstraint(horizontal("h", "L")))

        val after = run(doc, SetLinePoints(ElementId("L"), listOf(ScenePoint(u(0), u(0)), ScenePoint(u(9), u(0)))))

        assertEquals(listOf("h"), after.constraints.map { it.id.value })
    }

    @Test
    fun build_should_SkipAndReportOneBadConstraint_InsteadOfFailingTheWholeProblem() {
        val strict = SceneToProblem.build(twoPointWithStaleVertex.scene, twoPointWithStaleVertex.constraints)
        assertTrue(strict.isLeft(), "the strict build still refuses it (AddConstraint relies on that)")

        val lenient = SceneToProblem.build(twoPointWithStaleVertex.scene, twoPointWithStaleVertex.constraints, skipInvalid = true).get()

        assertEquals(listOf(ConstraintId("stale")), lenient.skipped)
        assertEquals(4, lenient.start.size, "only the good constraint's line is in the problem")
    }

    @Test
    fun anEditOnASheetWithAStaleConstraint_should_StillSolve() = runTest {
        val runner = SolveRunner(context = StandardTestDispatcher(testScheduler))
        val edit = SetLinePoints(ElementId("L"), listOf(ScenePoint(u(0), u(0)), ScenePoint(u(50), u(30))))

        val out = runner.commitEdit(twoPointWithStaleVertex, edit)

        val done = assertIs<SolveOutcome.Done>(out)
        assertEquals(listOf("h"), done.step.after.constraints.map { it.id.value }, "the edit also pruned the stale constraint")
        val end = (done.step.after.scene[ElementId("L")] as LineElement).points[1]
        assertEquals(0.0, end.y.value, 1e-6, "the horizontal constraint was still applied")
        runner.close()
    }
}
