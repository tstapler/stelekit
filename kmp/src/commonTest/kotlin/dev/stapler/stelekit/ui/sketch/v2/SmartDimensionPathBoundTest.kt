// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.W6Kit.circle
import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.W6Kit.line
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.Committed
import dev.stapler.stelekit.sketch.v2.commands.DocPatch
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 4.2.7d, "the bracket-plate smart-dimension variant bound" (UX-115, ux.md section 9). The bracket plate is dimensioned
 * through the real pickers, chips and label editor under the ONE counting convention (a typed value plus its Enter is one
 * action) and must reach 0 degrees of freedom in at most 34 actions with the smart-dimension chips and 42 through the
 * Constrain menu. This drives the pure models and the real [SolveRunner]; the Robolectric whole-screen run is Task 6.2.4f.
 * The drawing of the outline and the drag of a corner are counted, not simulated, because they belong to the canvas tools.
 */
class SmartDimensionPathBoundTest {
    private val plateHeight = 64.0
    private val holeRadius = 8.0
    private val holeInset = 16.0
    private val widenedWidth = 112.0

    private class Session(val runner: SolveRunner) {
        var doc: SketchDocumentV2 = SketchDocumentV2()
        val history = mutableListOf<SketchDocumentV2>()
        var actions = 0
        val trace = mutableListOf<String>()
        private var ids = 0
        val newId = { ConstraintId("k${++ids}") }
        val editor = DimensionEditor(technical = true)

        fun act(count: Int = 1) {
            actions += count
        }

        suspend fun commit(command: Command): SolveOutcome.Done {
            val done = assertIs<SolveOutcome.Done>(runner.commitEdit(doc, command))
            history += doc
            doc = done.step.after
            return done
        }

        suspend fun dof(): Int = assertNotNull(ConstraintAnalysis.from(runner.analyze(doc)).dof)
    }

    private fun TestScope.session() = Session(SolveRunner(context = StandardTestDispatcher(testScheduler)))

    private fun at(element: String, part: TargetPart) = ConstraintTarget(ElementId(element), part)

    private fun whole(element: String) = ConstraintTarget(ElementId(element))

    private suspend fun Session.outline() {
        doc = AddElements(
            listOf(line("l1", 0, 0, 100, 0), line("l2", 100, 0, 100, 60), line("l3", 100, 60, 0, 60), line("l4", 0, 60, 0, 0)),
        ).apply(doc).get().doc
        val auto = listOf(
            Constraint(newId(), ConstraintKind.Coincident, listOf(at("l1", TargetPart.End), at("l2", TargetPart.Start))),
            Constraint(newId(), ConstraintKind.Coincident, listOf(at("l2", TargetPart.End), at("l3", TargetPart.Start))),
            Constraint(newId(), ConstraintKind.Coincident, listOf(at("l3", TargetPart.End), at("l4", TargetPart.Start))),
            Constraint(newId(), ConstraintKind.Coincident, listOf(at("l4", TargetPart.End), at("l1", TargetPart.Start))),
            Constraint(newId(), ConstraintKind.Horizontal, listOf(whole("l1"))),
            Constraint(newId(), ConstraintKind.Horizontal, listOf(whole("l3"))),
            Constraint(newId(), ConstraintKind.Vertical, listOf(whole("l2"))),
            Constraint(newId(), ConstraintKind.Vertical, listOf(whole("l4"))),
        )
        for (c in auto) commit(AddConstraint(c))
        act(OUTLINE_ACTIONS)
        assertEquals(4, dof(), "the outline alone: 4 degrees of freedom")
    }

    /** Select V, K, Fixed, click the lower-left marker: the picker arms and one pick pins the corner. */
    /** Select V, K, Fixed, click the lower-left marker: the picker arms and one pick pins the corner. */
    private suspend fun Session.pinCorner() {
        act(PIN_ACTIONS)
        val armed = TargetPicker.arm(ConstraintKind.Fixed)
        val corner = TargetPicker.candidates(doc, armed, doc.scene.order).first { it.target == at("l1", TargetPart.Start) }
        assertEquals("Endpoint of Line 1, also: Line 4 end", corner.chipText)
        val done = assertIs<PickStep.Complete>(TargetPicker.pick(doc, armed, corner.target, newId))
        commit(AddConstraint(done.constraint))
        assertEquals(2, dof())
    }

    private suspend fun Session.typeValue(text: String) {
        act()
        editor.typed(text)
        val done = assertIs<SolveOutcome.Done>(assertNotNull(editor.submit(doc, runner)))
        history += doc
        doc = done.step.after
        (doc.scene[ElementId("c")] as? EllipseElement)?.let { trace += "$text -> c=(${it.cx.value}, ${it.cy.value}) r=${it.rx.value}" }
    }

    /** K, the kind, [clicks] counted marker clicks, then the typed value; [picks] are the operands (a selected circle needs no click). */
    private suspend fun Session.menuDimension(kind: ConstraintKind, picks: List<ConstraintTarget>, clicks: Int, text: String) {
        act(MENU_OPEN_AND_KIND)
        act(clicks)
        var state = TargetPicker.arm(kind)
        var complete: PickStep.Complete? = null
        for (p in picks) {
            when (val step = TargetPicker.pick(doc, state, p, newId)) {
                is PickStep.Next -> state = step.state
                is PickStep.Complete -> complete = step
                is PickStep.Rejected -> error("refused ${p.part}: ${step.message}")
            }
        }
        editor.openNew(doc, assertNotNull(complete).constraint, "")
        typeValue(text)
    }

    /** [selectActions] clicks to select the operands, a tap on the chip, then the typed value. */
    private suspend fun Session.chipDimension(selection: List<String>, selectActions: Int, text: String) {
        val suggestion = assertNotNull(DimensionChips.suggestion(doc, selection.map { ElementId(it) }, null, SolverTier.Full, newId), "no chip for $selection")
        act(selectActions)
        act()
        assertTrue(editor.openSuggestion(doc, suggestion))
        assertEquals(0, doc.constraints.count { it.id == suggestion.constraint.id }, "the chip added nothing yet")
        typeValue(text)
    }

    private suspend fun Session.drawHole() {
        act(HOLE_DRAW_ACTIONS)
        doc = AddElements(listOf(circle("c", 30, 30, 6))).apply(doc).get().doc
        assertEquals(0, dof(), "an element no constraint names adds no degrees of freedom to the status")
    }

    private suspend fun Session.checkAndEdit() {
        act(CHECK_ACTIONS)
        val parallel = Constraint(newId(), ConstraintKind.Parallel, listOf(whole("l1"), whole("l2")))
        val rejected = assertIs<SolveOutcome.Rejected>(runner.commitEdit(doc, AddConstraint(parallel)))
        assertEquals(doc, rejected.lastGood, "geometry unchanged")
        val card = ConflictCards.forRejected(doc, parallel, assertIs(rejected.reason))
        assertTrue(card.actions.any { it.kind == ConflictActionKind.RemoveNew } && card.actions.any { it.kind == ConflictActionKind.ShowConflicts })
        assertTrue(card.actions.none { it.kind == ConflictActionKind.MakeReference }, "Make reference is not offered for a geometric constraint")

        act()
        val width = doc.constraints.first { it.kind == ConstraintKind.Distance && it.elementIds == setOf(ElementId("l1")) }.id
        assertTrue(editor.openExisting(doc, width))
        val before = doc
        typeValue("7\"")
        assertEquals(widenedWidth, length(doc, "l1"), TOLERANCE)
        assertEquals(holeInset, (doc.scene[ElementId("c")] as EllipseElement).cx.value, TOLERANCE, "the hole stays 1 in from the left edge; trace $trace")
        val step = Committed(before, doc, DocPatch.diff(before, doc))
        assertEquals(before, HistoryV2.of(before).commit(step).get().undo().present, "one undo step")
    }

    private fun length(doc: SketchDocumentV2, name: String): Double {
        val l = doc.scene[ElementId(name)] as LineElement
        return hypot(l.points[1].x.value - l.points[0].x.value, l.points[1].y.value - l.points[0].y.value)
    }

    @Test
    fun bracketPlate_should_ReachZeroDofWithin34Actions_WithSmartDimensionChips() = runTest {
        val s = session()
        s.outline()
        s.pinCorner()
        s.chipDimension(listOf("l1"), selectActions = 1, text = "6\"")
        assertEquals(1, s.dof())
        s.chipDimension(listOf("l4"), selectActions = 1, text = "4\"")
        assertEquals(0, s.dof(), "the outline is fully constrained")
        s.drawHole()
        s.chipDimension(listOf("c"), selectActions = 0, text = "1/2\"")
        assertEquals(2, s.dof())
        s.chipDimension(listOf("c", "l4"), selectActions = 1, text = "1\"")
        assertEquals(1, s.dof())
        s.chipDimension(listOf("c", "l1"), selectActions = 2, text = "1\"")
        assertEquals(holeInset, (s.doc.scene[ElementId("c")] as EllipseElement).cx.value, TOLERANCE, "trace ${s.trace}")
        assertEquals(0, s.dof(), "the sketch is fully constrained")
        assertEquals(plateHeight, length(s.doc, "l4"), TOLERANCE)
        assertEquals(holeRadius, (s.doc.scene[ElementId("c")] as EllipseElement).rx.value, TOLERANCE)
        s.checkAndEdit()
        assertTrue(s.actions <= SMART_BOUND, "${s.actions} actions with the chips; the bound is $SMART_BOUND")
        assertEquals(SMART_BOUND, s.actions, "the recount of ux.md section 9")
    }

    @Test
    fun bracketPlate_should_ReachZeroDofWithin42Actions_ThroughTheConstrainMenu() = runTest {
        val s = session()
        s.outline()
        s.pinCorner()
        s.menuDimension(ConstraintKind.Distance, listOf(at("l1", TargetPart.Start), at("l1", TargetPart.End)), clicks = 2, text = "6\"")
        assertEquals(1, s.dof())
        s.menuDimension(ConstraintKind.Distance, listOf(at("l4", TargetPart.Start), at("l4", TargetPart.End)), clicks = 2, text = "4\"")
        assertEquals(0, s.dof())
        s.drawHole()
        s.menuDimension(ConstraintKind.Radius, listOf(whole("c")), clicks = 0, text = "1/2\"")
        assertEquals(2, s.dof())
        s.menuDimension(ConstraintKind.Distance, listOf(at("c", TargetPart.Centre), at("l4", TargetPart.Edge(0))), clicks = 2, text = "1\"")
        s.menuDimension(ConstraintKind.Distance, listOf(at("c", TargetPart.Centre), at("l1", TargetPart.Edge(0))), clicks = 2, text = "1\"")
        assertEquals(0, s.dof())
        assertEquals(holeInset, (s.doc.scene[ElementId("c")] as EllipseElement).cx.value, TOLERANCE, "trace ${s.trace}")
        s.checkAndEdit()
        assertTrue(s.actions <= MENU_BOUND, "${s.actions} actions through the menu; the bound is $MENU_BOUND")
        assertEquals(MENU_BOUND, s.actions, "the recount of ux.md section 9")
    }

    @Test
    fun theSmartPath_should_BeShorterThanTheMenuPath() {
        assertTrue(SMART_BOUND < MENU_BOUND)
    }

    private companion object {
        const val OUTLINE_ACTIONS = 6
        const val PIN_ACTIONS = 4
        const val MENU_OPEN_AND_KIND = 2
        const val HOLE_DRAW_ACTIONS = 2
        const val CHECK_ACTIONS = 5
        const val TOLERANCE = 1e-4
        const val SMART_BOUND = 34
        const val MENU_BOUND = 42
    }
}
