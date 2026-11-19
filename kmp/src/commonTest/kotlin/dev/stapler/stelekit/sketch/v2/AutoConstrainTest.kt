// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.constraints.SolveResult
import dev.stapler.stelekit.sketch.constraints.StopReason
import dev.stapler.stelekit.sketch.v2.W6Kit.circle
import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.W6Kit.line
import dev.stapler.stelekit.sketch.v2.W6Kit.u
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AutoConstrain
import dev.stapler.stelekit.sketch.v2.constraints.AutoConstrainDefault
import dev.stapler.stelekit.sketch.v2.constraints.AutoConstrainOptions
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.runtime.constraints.AutoConstrainResult
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveEngine
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.runtime.constraints.commitAutoConstrained
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.ui.sketch.v2.AutoConstrainChips
import dev.stapler.stelekit.ui.sketch.v2.AutoConstrainState
import dev.stapler.stelekit.ui.sketch.v2.ConstraintToasts
import dev.stapler.stelekit.ui.sketch.v2.SketchNoticeQueue
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 4.2.2: snap-inferred constraints, their toast and Undo, the per-sheet toggle default and the one-shot suppression. */
class AutoConstrainTest {
    private var next = 0
    private val newId = { ConstraintId("k${++next}") }
    private val on = AutoConstrainOptions(enabled = true)

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 =
        AddElements(elements.toList()).apply(SketchDocumentV2()).get().doc

    private fun slanted(id: String, x0: Double, y0: Double, x1: Double, y1: Double) =
        LineElement(ElementBase(ElementId(id)), listOf(SheetFixtures.pt(x0, y0), SheetFixtures.pt(x1, y1)))

    private fun TestScope.runner(engine: SolveEngine? = null) =
        if (engine == null) SolveRunner(context = StandardTestDispatcher(testScheduler)) else SolveRunner(engine = engine, context = StandardTestDispatcher(testScheduler))

    @Test
    fun plan_should_AddCoincident_When_LineEndsOnAnotherLinesEndpoint() {
        val doc = docOf(line("a", 0, 0, 50, 40))
        val plan = AutoConstrain.plan(doc, line("b", 50, 40, 90, 100), on, newId)
        val coincident = plan.constraints.single { it.kind == ConstraintKind.Coincident }
        assertEquals(listOf(TargetPart.Start, TargetPart.End), coincident.targets.map { it.part })
        assertEquals(listOf(ElementId("b"), ElementId("a")), coincident.targets.map { it.elementId })
    }

    @Test
    fun plan_should_AddHorizontalOrVertical_OnlyWithinTwoDegrees() {
        val doc = SketchDocumentV2()
        val nearlyHorizontal = AutoConstrain.plan(doc, slanted("h", 0.0, 0.0, 100.0, 3.0), on, newId)
        assertEquals(listOf(ConstraintKind.Horizontal), nearlyHorizontal.kinds, "1.7 degrees is within 2")
        val tooSteep = AutoConstrain.plan(doc, slanted("s", 0.0, 0.0, 100.0, 4.0), on, newId)
        assertEquals(emptyList(), tooSteep.kinds, "2.3 degrees is not")
        val nearlyVertical = AutoConstrain.plan(doc, slanted("v", 0.0, 0.0, 3.0, 100.0), on, newId)
        assertEquals(listOf(ConstraintKind.Vertical), nearlyVertical.kinds)
        assertEquals(listOf(ConstraintKind.Horizontal), AutoConstrain.plan(doc, line("e", 100, 0, 0, 0), on, newId).kinds, "a leftward line is horizontal too")
        assertEquals(emptyList(), AutoConstrain.plan(doc, line("z", 5, 5, 5, 5), on, newId).kinds, "a zero-length line has no direction")
    }

    @Test
    fun plan_should_InferNothing_When_SuppressedDisabledOrNotConstrainable() {
        val doc = docOf(line("a", 0, 0, 50, 0))
        val drawn = line("b", 50, 0, 90, 0)
        assertEquals(emptyList(), AutoConstrain.plan(doc, drawn, AutoConstrainOptions(enabled = true, suppressed = true), newId).kinds, "Ctrl or the Free draw chip")
        assertEquals(emptyList(), AutoConstrain.plan(doc, drawn, AutoConstrainOptions(enabled = false), newId).kinds, "toggle off")
        val arrow = ArrowElement(ElementBase(ElementId("arr")), listOf(ScenePoint(u(50), u(0)), ScenePoint(u(90), u(0))))
        assertEquals(emptyList(), AutoConstrain.plan(doc, arrow, on, newId).kinds, "arrows are never constrained")
        val text = TextElement(ElementBase(ElementId("t")), u(0), u(0), u(10), u(10), text = "x")
        val withText = docOf(text)
        assertEquals(emptyList(), AutoConstrain.plan(withText, line("c", 0, 0, 20, 90), on, newId).kinds, "text is never a snap target")
    }

    @Test
    fun plan_should_SnapAPointElementOntoAnExistingEndpoint() {
        val doc = docOf(line("a", 0, 0, 50, 40))
        val plan = AutoConstrain.plan(doc, PointElement(ElementBase(ElementId("p")), u(50), u(40)), on, newId)
        val c = plan.constraints.single()
        assertEquals(ConstraintKind.Coincident, c.kind)
        assertEquals(listOf(TargetPart.Whole, TargetPart.End), c.targets.map { it.part })
    }

    @Test
    fun plan_should_NeverExceedTheConstraintCap() {
        val limits = SceneLimits(maxConstraints = 1)
        val seeded = AddElements(listOf(line("a", 0, 0, 50, 40))).apply(SketchDocumentV2(scene = SketchSceneV2.empty(limits))).get().doc
        val plan = AutoConstrain.plan(seeded, line("b", 50, 40, 150, 40), on, newId)
        assertEquals(1, plan.constraints.size, "Coincident and Horizontal were wanted, one fits")
    }

    @Test
    fun commit_should_PutTheShapeAndItsConstraintsInOneUndoStep_AndTidyTheLine() = runTest {
        val runner = runner()
        val base = docOf(line("a", 0, 0, 50, 40))
        val drawn = slanted("b", 50.0, 40.0, 150.0, 43.0)
        val plan = AutoConstrain.plan(base, drawn, on, newId)
        assertEquals(setOf(ConstraintKind.Coincident, ConstraintKind.Horizontal), plan.kinds.toSet())

        val result = assertIs<AutoConstrainResult.WithConstraints>(runner.commitAutoConstrained(base, plan))

        val after = result.done.step.after
        val b = after.scene[ElementId("b")] as LineElement
        assertTrue(abs(b.points[0].y.value - b.points[1].y.value) < TOLERANCE, "the solver made it exactly horizontal")
        assertEquals(2, after.constraints.size)
        val history = HistoryV2.of(base).commit(result.done.step).get()
        assertEquals(1, history.undoDepth, "shape and constraints are one history entry")
        assertEquals(base, history.undo().present)
    }

    @Test
    fun toastUndo_should_RemoveTheConstraintsButKeepTheShape() = runTest {
        val runner = runner()
        val base = docOf(line("a", 0, 0, 50, 40))
        val plan = AutoConstrain.plan(base, line("b", 50, 40, 90, 100), on, newId)
        val result = assertIs<AutoConstrainResult.WithConstraints>(runner.commitAutoConstrained(base, plan))

        val undone = assertIs<SolveOutcome.Done>(runner.commitEdit(result.done.step.after, ConstraintToasts.undo(result)))

        assertTrue(undone.step.after.constraints.isEmpty(), "constraints removed")
        assertNotNull(undone.step.after.scene[ElementId("b")], "the drawn shape stays")
    }

    @Test
    fun commit_should_DrawThePlainShape_When_TheSolverRefusesTheInferredSet() = runTest {
        val stalled = SolveEngine { _, start -> SolveResult.NoConvergence(StopReason.Stalled, start.copyOf(), 0, 1.0, 5, null) }
        val runner = runner(stalled)
        val base = docOf(line("a", 0, 0, 50, 40))
        val plan = AutoConstrain.plan(base, line("b", 50, 40, 90, 100), on, newId)
        assertTrue(plan.constraints.isNotEmpty())

        val result = assertIs<AutoConstrainResult.Plain>(runner.commitAutoConstrained(base, plan))

        assertTrue(result.refused)
        assertNotNull(result.done.step.after.scene[ElementId("b")])
        assertTrue(result.done.step.after.constraints.isEmpty())
    }

    @Test
    fun toast_should_BeOneAddedConstraintNoticeWithUndo_AnnouncedPolitely_AndCoalesced() = runTest {
        val runner = runner()
        val queue = SketchNoticeQueue()
        val base = docOf(line("a", 0, 0, 50, 40))
        val plan = AutoConstrain.plan(base, line("b", 50, 40, 90, 100), on, newId)
        val result = runner.commitAutoConstrained(base, plan)

        val notice = assertNotNull(ConstraintToasts.post(queue, result, now = 0))

        assertEquals("AddedConstraint", notice.key)
        assertEquals(SketchConstraintStrings.Toast.SUMMARY, notice.text)
        assertEquals("Undo", notice.action)
        assertTrue(notice.confirmsUndoableEdit)
        assertEquals("Added Coincident", notice.details)
        val spoken = assertNotNull(queue.nextAnnouncement(now = 0))
        assertFalse(spoken.assertive)
        assertEquals("Constraint added.", spoken.text)
        ConstraintToasts.post(queue, result, now = 100)
        assertEquals(2, queue.visible(now = 100).single().count, "identical notices coalesce into a count")
    }

    @Test
    fun toast_should_NameEveryKindWithItsCount() {
        val twoEnds = AutoConstrain.plan(docOf(line("a", 0, 0, 50, 0), line("c", 100, 0, 150, 0)), line("b", 50, 0, 100, 0), on, newId)
        assertEquals("Added Coincident (2), Horizontal", ConstraintToasts.added(twoEnds.constraints).details)
    }

    @Test
    fun toast_should_NotAppear_When_NothingWasInferred() = runTest {
        val runner = runner()
        val queue = SketchNoticeQueue()
        val base = docOf(line("a", 0, 0, 50, 40))
        val plan = AutoConstrain.plan(base, line("b", 50, 40, 90, 100), AutoConstrainOptions(enabled = true, suppressed = true), newId)
        assertNull(ConstraintToasts.post(queue, runner.commitAutoConstrained(base, plan), now = 0))
        assertEquals(emptyList(), queue.visible(now = 0))
    }

    @Test
    fun default_should_BeOffOnDiagramsAndOnForTechnicalAndConvertedSheets() {
        assertFalse(AutoConstrainDefault.enabledFor(SheetKind.Diagram))
        assertTrue(AutoConstrainDefault.enabledFor(SheetKind.Technical))
        assertTrue(AutoConstrainDefault.enabledFor(SheetKind.Converted))
        assertFalse(AutoConstrainState(SheetKind.Diagram).enabled)
        assertTrue(AutoConstrainState(SheetKind.Technical).enabled)
    }

    @Test
    fun toggle_should_BeStoredPerSheet_AndNotAnEdit() {
        val stored = mutableListOf<Boolean>()
        val diagram = AutoConstrainState(SheetKind.Diagram, onStored = { stored += it })
        diagram.toggle()
        assertTrue(diagram.enabled)
        diagram.toggle()
        assertFalse(diagram.enabled)
        assertEquals(listOf(true, false), stored)
        assertTrue(AutoConstrainState(SheetKind.Technical, stored = true).enabled, "a stored choice wins over the default")
        assertFalse(AutoConstrainState(SheetKind.Technical, stored = false).enabled)
    }

    @Test
    fun freeDraw_should_SuppressTheNextShapeOnly() {
        val state = AutoConstrainState(SheetKind.Technical)
        assertNotNull(AutoConstrainChips.freeDraw(state))
        assertTrue(state.optionsForNextShape(ctrlHeld = false).active, "no suppression yet")
        AutoConstrainChips.freeDraw(state)!!.onClick()
        assertTrue(state.freeDrawArmed)
        assertFalse(state.optionsForNextShape(ctrlHeld = false).active, "the armed chip suppresses")
        assertFalse(state.freeDrawArmed, "and is spent")
        assertTrue(state.optionsForNextShape(ctrlHeld = false).active, "the following shape is constrained again")
        assertFalse(state.optionsForNextShape(ctrlHeld = true).active, "Ctrl is the desktop path")
        assertNull(AutoConstrainChips.freeDraw(AutoConstrainState(SheetKind.Diagram)), "nothing to suppress when the toggle is off")
    }

    @Test
    fun circleDrawn_should_NotBeConstrainedByInference() {
        val doc = docOf(line("a", 0, 0, 50, 40))
        assertEquals(emptyList(), AutoConstrain.plan(doc, circle("c", 50, 40, 5), on, newId).kinds)
    }

    private companion object {
        const val TOLERANCE = 1e-6
    }
}
