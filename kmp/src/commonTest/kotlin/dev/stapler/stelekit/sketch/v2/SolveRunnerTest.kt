// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLogSink
import dev.stapler.stelekit.sketch.constraints.SolveResult
import dev.stapler.stelekit.sketch.constraints.Solver
import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.constraints.StopReason
import dev.stapler.stelekit.sketch.v2.binding.BindArrowEnd
import dev.stapler.stelekit.sketch.v2.binding.BindingGeometry
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.commands.RotateElement
import dev.stapler.stelekit.sketch.v2.commands.SetLinePoints
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveEngine
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRejection
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveState
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolverEngine
import dev.stapler.stelekit.sketch.v2.runtime.constraints.UnsatisfiedKind
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.BindingEnd
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.ui.sketch.v2.SolveMessages
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Story 4.1.4: the solver runner. Drag and commit are one undo step, a refusal keeps the last good pose, a slow solve can be cancelled. */
@OptIn(ExperimentalCoroutinesApi::class)
class SolveRunnerTest {
    private fun <L, R> Either<L, R>.right(): R = fold({ error("unexpected Left: $it") }, { it })

    private fun u(d: Double) = SheetFixtures.u(d)

    private fun id(s: String) = ElementId(s)

    private fun line(s: String, x0: Double, y0: Double, x1: Double, y1: Double) =
        LineElement(ElementBase(id = id(s)), listOf(SheetFixtures.pt(x0, y0), SheetFixtures.pt(x1, y1)))

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 = AddElements(elements.toList()).apply(SketchDocumentV2()).right().doc

    private fun SketchDocumentV2.with(vararg commands: Command): SketchDocumentV2 = commands.fold(this) { doc, c -> c.apply(doc).right().doc }

    private fun c(name: String, kind: ConstraintKind, vararg targets: String, value: Double? = null) =
        Constraint(ConstraintId(name), kind, targets.map { ConstraintTarget(id(it)) }, value)

    private fun TestScope.runner(engine: SolveEngine = SolverEngine(), tier: SolverTier = SolverTier.Full, limits: SceneLimits = SceneLimits.Default) =
        SolveRunner(limits, engine, tier, StandardTestDispatcher(testScheduler))

    private fun pointsOf(doc: SketchDocumentV2, name: String) = (doc.scene[id(name)] as LineElement).points.map { it.x.value to it.y.value }

    private fun near(expected: Double, actual: Double, tolerance: Double = TOLERANCE) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")

    private fun horizontalLine(): SketchDocumentV2 =
        docOf(line("L1", 0.0, 0.0, 100.0, 0.0)).with(AddConstraint(c("h", ConstraintKind.Horizontal, "L1")))

    private fun assertFinite(doc: SketchDocumentV2) {
        for (e in doc.scene.elementsInOrder) if (e is LineElement) for (p in e.points) assertTrue(p.x.value.isFinite() && p.y.value.isFinite())
    }

    @Test
    fun solveRunner_should_CommitOneTransactionWithSolverGeometry_When_VertexDragReleased() = runTest {
        val runner = runner()
        val base = horizontalLine()
        val drag = runner.beginDrag(base)

        val posed = assertIs<SolveOutcome.Posed>(drag.update(SetLinePoints(id("L1"), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(50.0, 30.0)))))
        near(0.0, pointsOf(posed.doc, "L1")[1].second)
        val done = assertIs<SolveOutcome.Done>(drag.release())

        val end = pointsOf(done.step.after, "L1")[1]
        near(50.0, end.first)
        near(0.0, end.second, 1e-9)
        val history = HistoryV2.of(base).commit(done.step).right()
        assertEquals(1, history.undoDepth, "one new entry")
        assertEquals(base, history.undo().present, "undo restores the vertex and the constraint-derived position")

        val before = runner.counters.value
        assertTrue(before.solves > 0)
        assertEquals(done.step.after, history.undo().redo().present, "redo replays the stored patch")
        assertEquals(before, runner.counters.value, "redo never solves")
    }

    @Test
    fun drag_should_SolveEachUpdateFromThePreviousPose_AndCommitOnce() = runTest {
        val runner = runner()
        val base = horizontalLine()
        val drag = runner.beginDrag(base)

        for (y in listOf(10.0, 20.0, 30.0)) {
            val out = drag.update(SetLinePoints(id("L1"), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(60.0, y))))
            assertIs<SolveOutcome.Posed>(out)
        }

        assertTrue(runner.counters.value.solves >= 3, "every update ran the solver")
        val done = assertIs<SolveOutcome.Done>(drag.release())
        assertEquals(base, done.step.before)
        val end = pointsOf(done.step.after, "L1")[1]
        near(60.0, end.first)
        near(0.0, end.second)
        assertEquals(1, HistoryV2.of(base).commit(done.step).right().undoDepth, "three updates are still one history entry")
    }

    @Test
    fun commitEdit_should_StoreTheConstraintAndSolveItInTheSameStep() = runTest {
        val runner = runner()
        val base = docOf(line("L1", 0.0, 0.0, 100.0, 12.0))

        val done = assertIs<SolveOutcome.Done>(runner.commitEdit(base, AddConstraint(c("h", ConstraintKind.Horizontal, "L1"))))

        val pts = pointsOf(done.step.after, "L1")
        near(pts[0].second, pts[1].second)
        assertEquals(1, done.step.after.constraints.size)
        assertEquals(base, HistoryV2.of(base).commit(done.step).right().undo().present, "one undo removes the constraint and the movement")
        assertEquals(0, done.summary?.redundant?.size)
    }

    @Test
    fun ux35_nonConvergence_should_KeepLastGoodPoseAndShowCannotSatisfy() = runTest {
        val stalled = SolveEngine { _, start -> SolveResult.NoConvergence(StopReason.Stalled, start.copyOf(), 0, 1.0, 5, null) }
        val runner = runner(stalled)
        val base = horizontalLine()
        val drag = runner.beginDrag(base)

        val out = drag.update(SetLinePoints(id("L1"), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(50.0, 30.0))))

        val rejected = assertIs<SolveOutcome.Rejected>(out)
        assertEquals(SolveRejection.Unsatisfiable(emptyList(), UnsatisfiedKind.NoConvergence), rejected.reason)
        assertEquals(base, rejected.lastGood)
        assertEquals(base, drag.pose, "the pose stays at the last good geometry")
        assertFinite(rejected.lastGood)
        assertIs<SolveOutcome.Unchanged>(drag.release())
        assertEquals(SketchStrings.Solve.CANT_SATISFY_SUMMARY, SolveMessages.cantSatisfy().text)
        assertEquals("CantSatisfy", SolveMessages.cantSatisfy().key)
    }

    @Test
    fun solves_should_FeedTheQualityCounters_WithNoConvergenceAmongThem() = runTest {
        val values = mutableMapOf<String, Any>()
        val settings = object : dev.stapler.stelekit.platform.Settings {
            override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
            override fun putBoolean(key: String, value: Boolean) { values[key] = value }
            override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
            override fun putString(key: String, value: String) { values[key] = value }
            override fun containsKey(key: String) = key in values
        }
        val signals = dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualitySignals(settings)
        val log = SketchLog(SketchLogSink { }, signals = signals)
        val ok = SolveRunner(SceneLimits.Default, SolverEngine(), SolverTier.Full, StandardTestDispatcher(testScheduler), log)
        assertIs<SolveOutcome.Done>(ok.commitEdit(docOf(line("L1", 0.0, 0.0, 100.0, 12.0)), AddConstraint(c("h", ConstraintKind.Horizontal, "L1"))))
        val stalled = SolveEngine { _, start -> SolveResult.NoConvergence(StopReason.Stalled, start.copyOf(), 0, 1.0, 5, null) }
        val failing = SolveRunner(SceneLimits.Default, stalled, SolverTier.Full, StandardTestDispatcher(testScheduler), log)
        failing.beginDrag(horizontalLine()).update(SetLinePoints(id("L1"), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(50.0, 30.0))))

        assertEquals(2L, signals.counts().solves)
        assertEquals(1L, signals.counts().noConvergence)
        assertEquals(listOf(false, true), signals.recentSolves())
    }

    @Test
    fun solveRunner_should_KeepLastGoodGeometry_When_DragNonConverges() = runTest {
        val runner = runner()
        val pinned = docOf(line("L1", 0.0, 0.0, 100.0, 0.0)).with(AddConstraint(c("pin", ConstraintKind.Fixed, "L1")))

        val out = runner.commitEdit(pinned, AddConstraint(c("len", ConstraintKind.Distance, "L1", value = 50.0)))

        val rejected = assertIs<SolveOutcome.Rejected>(out)
        val reason = assertIs<SolveRejection.Unsatisfiable>(rejected.reason)
        assertEquals(UnsatisfiedKind.Conflict, reason.kind)
        assertTrue(reason.ids.containsAll(listOf(ConstraintId("pin"), ConstraintId("len"))))
        assertEquals(pinned, rejected.lastGood)
        assertFinite(rejected.lastGood)
    }

    @Test
    fun transforms_should_SatisfyTheConstraintsOrBeRejected_PerTransformKind() = runTest {
        val runner = runner()
        val base = docOf(line("L1", 0.0, 0.0, 100.0, 0.0)).with(
            AddConstraint(c("h", ConstraintKind.Horizontal, "L1")),
            AddConstraint(c("len", ConstraintKind.Distance, "L1", value = 100.0)),
        )

        val moved = assertIs<SolveOutcome.Done>(runner.commitEdit(base, MoveElements(listOf(id("L1")), 7.0, 3.0)))
        val m = pointsOf(moved.step.after, "L1")
        near(100.0, hypot(m[1].first - m[0].first, m[1].second - m[0].second))
        near(m[0].second, m[1].second)
        near(7.0, m[0].first)

        val resized = runner.commitEdit(base, ResizeElement(id("L1"), 0.0, 0.0, 50.0, 0.0))
        val back = pointsOf(if (resized is SolveOutcome.Done) resized.step.after else base, "L1")
        near(100.0, back[1].first - back[0].first)
        near(0.0, back[1].second - back[0].second)

        val rotated = assertIs<SolveOutcome.Rejected>(runner.commitEdit(base, RotateElement(id("L1"), 0.5)))
        assertIs<SolveRejection.EditFailed>(rotated.reason)
        assertEquals(base, rotated.lastGood)

        val twoLines = base.with(AddElements(listOf(line("L2", 0.0, 20.0, 40.0, 20.0))))
        val group = assertIs<SolveOutcome.Done>(runner.commitEdit(twoLines, MoveElements(listOf(id("L1"), id("L2")), 5.0, 5.0)))
        near(5.0, pointsOf(group.step.after, "L2")[0].first)
        assertEquals(1, HistoryV2.of(twoLines).commit(group.step).right().undoDepth)
    }

    @Test
    fun rotatedRectangle_should_KeepItsDimension_When_ConstrainedAndRotated() = runTest {
        val runner = runner()
        val rect = RectElement(ElementBase(id = id("r")), u(0.0), u(0.0), u(100.0), u(60.0))
        val base = docOf(rect).with(AddConstraint(c("w", ConstraintKind.Distance, "r", value = 100.0)))

        val out = assertIs<SolveOutcome.Done>(runner.commitEdit(base, RotateElement(id("r"), 0.3)))

        val r = out.step.after.scene[id("r")] as RectElement
        near(0.3, r.angle, 1e-6)
        near(100.0, r.width.value, 1e-6)
    }

    @Test
    fun boundArrow_should_FollowSolverGeometry_InTheSameTransaction_And_RedoNeverRecomputes() = runTest {
        val runner = runner()
        val rect = RectElement(ElementBase(id = id("r1")), u(0.0), u(0.0), u(100.0), u(60.0))
        val arrow = ArrowElement(ElementBase(id = id("a1")), listOf(SheetFixtures.pt(150.0, 30.0), SheetFixtures.pt(105.0, 30.0)))
        val base = docOf(rect, arrow).with(BindArrowEnd(id("a1"), BindingEnd.End, id("r1")))

        val done = assertIs<SolveOutcome.Done>(runner.commitEdit(base, AddConstraint(c("w", ConstraintKind.Distance, "r1", value = 180.0))))

        val after = done.step.after
        val r1 = after.scene[id("r1")] as RectElement
        near(180.0, r1.width.value, 1e-6)
        val a1 = after.scene[id("a1")] as ArrowElement
        val expected = BindingGeometry.pointOf(r1, a1.endBinding!!)!!
        val end = a1.points.last()
        near(expected.first, end.x.value, 1e-9)
        near(expected.second, end.y.value, 1e-9)
        assertTrue(id("a1") in done.step.changedIds, "the arrow is in the same patch as the rectangle")
        assertEquals(1, runner.counters.value.bindingRecomputes)

        val history = HistoryV2.of(base).commit(done.step).right()
        val before = runner.counters.value
        history.undo().redo()
        assertEquals(before, runner.counters.value, "solver and binding-recompute counters stay flat on redo")
    }

    @Test
    fun hiddenTier_should_CommitPlainEdits_WithoutSolving() = runTest {
        val runner = runner(tier = SolverTier.Hidden)
        val base = docOf(line("L1", 0.0, 0.0, 100.0, 12.0))

        val done = assertIs<SolveOutcome.Done>(runner.commitEdit(base, AddConstraint(c("h", ConstraintKind.Horizontal, "L1"))))

        assertEquals(0, runner.counters.value.solves)
        assertEquals(pointsOf(base, "L1"), pointsOf(done.step.after, "L1"))
    }

    @Test
    fun editFailure_should_BeReportedWithoutTouchingTheDocument() = runTest {
        val runner = runner()
        val base = horizontalLine()

        val out = assertIs<SolveOutcome.Rejected>(runner.commitEdit(base, SetLinePoints(id("missing"), emptyList())))

        assertIs<SolveRejection.EditFailed>(out.reason)
        assertEquals(base, out.lastGood)
    }

    @Test
    fun aThrowingEngine_should_BecomeCrashedNotAnUncaughtException() = runTest {
        val runner = runner(SolveEngine { _, _ -> error("boom") })
        val base = docOf(line("L1", 0.0, 0.0, 100.0, 12.0))

        val out = assertIs<SolveOutcome.Rejected>(runner.commitEdit(base, AddConstraint(c("h", ConstraintKind.Horizontal, "L1"))))

        assertEquals(SolveRejection.Crashed("unexpected"), out.reason)
        assertEquals(base, out.lastGood)
        assertEquals(SolveState(), runner.state.value)
        runner.close()
    }

    private fun slowEngine(millis: Long) = SolveEngine { problem, start ->
        delay(millis)
        Solver().solve(problem, start)
    }

    @Test
    fun ux36_solveOver100ms_should_ShowProgressAndCancelRestoresLastGood() = runTest {
        val runner = runner(slowEngine(SLOW_SOLVE_MS))
        val base = docOf(line("L1", 0.0, 0.0, 100.0, 12.0))
        val pending = async { runner.commitEdit(base, AddConstraint(c("h", ConstraintKind.Horizontal, "L1"))) }

        runCurrent()
        assertEquals(SolveState(solving = true, slow = false), runner.state.value)
        advanceTimeBy(SceneLimits.Default.slowSolveIndicatorMs - 1)
        runCurrent()
        assertFalse(runner.state.value.slow, "not before the threshold")
        assertFalse(SolveMessages.indicatorVisible(runner.state.value))
        advanceTimeBy(2)
        runCurrent()
        assertTrue(runner.state.value.slow, "once the threshold has passed")
        assertTrue(SolveMessages.indicatorVisible(runner.state.value))

        runner.cancel()
        runCurrent()

        val out = assertIs<SolveOutcome.Cancelled>(pending.await())
        assertEquals(base, out.lastGood)
        assertEquals(SolveState(), runner.state.value, "the indicator disappears")
        assertEquals(0, HistoryV2.of(base).undoDepth, "no history entry was made")
    }

    @Test
    fun cancelledDragUpdate_should_NotBecomeThePose_When_TheRealSolverFinishesAnyway() = runTest {
        lateinit var runner: SolveRunner
        runner = runner(SolveEngine { problem, start ->
            runner.cancel()
            Solver().solve(problem, start)
        })
        val base = horizontalLine()
        val drag = runner.beginDrag(base)

        val out = drag.update(SetLinePoints(id("L1"), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(50.0, 30.0))))

        assertIs<SolveOutcome.Cancelled>(out)
        assertEquals(base, drag.pose, "a cancelled update leaves the pose alone")
        assertIs<SolveOutcome.Unchanged>(drag.release(), "and so the release has nothing to commit")
        val next = drag.update(SetLinePoints(id("L1"), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(60.0, 20.0))))
        assertIs<SolveOutcome.Cancelled>(next, "the engine cancels every call; the pose still never moves")
        assertEquals(base, drag.pose)
    }

    @Test
    fun ux36_solveUnderThreshold_should_NeverShowTheIndicator() = runTest {
        val runner = runner(slowEngine(FAST_SOLVE_MS))
        val seen = mutableListOf<SolveState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { runner.state.collect { seen += it } }
        val base = docOf(line("L1", 0.0, 0.0, 100.0, 12.0))

        val out = runner.commitEdit(base, AddConstraint(c("h", ConstraintKind.Horizontal, "L1")))

        assertIs<SolveOutcome.Done>(out)
        assertTrue(seen.none { it.slow }, "a fast solve never flashes the indicator: $seen")
        assertTrue(seen.any { it.solving })
    }

    @Test
    fun aSlowSolve_should_RunToCompletion_When_NotCancelled() = runTest {
        val runner = runner(slowEngine(SLOW_SOLVE_MS))
        val base = docOf(line("L1", 0.0, 0.0, 100.0, 12.0))

        val done = runner.commitEdit(base, AddConstraint(c("h", ConstraintKind.Horizontal, "L1")))

        assertIs<SolveOutcome.Done>(done)
        assertEquals(SolveState(), runner.state.value)
        assertNotEquals(base, (done as SolveOutcome.Done).step.after)
    }

    @Test
    fun hundredConstraintSketch_should_SolveWithinTheHardBar() = runTest {
        val runner = runner()
        val base = chain(CHAIN_LINES)
        assertTrue(base.constraints.size >= HUNDRED, "a 100-constraint sketch")
        repeat(WARMUPS) { assertIs<SolveOutcome.Done>(runner.commitEdit(base, MoveElements(listOf(id("c0")), 0.5, 0.5))) }

        var best = Double.MAX_VALUE
        repeat(REPEATS) {
            val t0 = TimeSource.Monotonic.markNow()
            val out = runner.commitEdit(base, MoveElements(listOf(id("c0")), 0.5, 0.5))
            best = minOf(best, t0.elapsedNow().inWholeMicroseconds / MICROS_PER_MILLI)
            assertIs<SolveOutcome.Done>(out)
        }

        val onJvm = !1e-9.toString().contains('e')
        val bar = if (onJvm) JVM_BAR_MS else WASM_BAR_MS
        println("SOLVE-RUNNER 100-constraint best of $REPEATS: $best ms (bar $bar ms, ${if (onJvm) "jvm" else "wasm-or-js"})")
        assertTrue(best <= bar, "the runner took $best ms against the $bar ms hard bar")
    }

    /** [n] lines in a chain, each Horizontal with a Distance and a Coincident to the next: 3n - 1 constraints. */
    private fun chain(n: Int): SketchDocumentV2 {
        val lines = (0 until n).map { i -> line("c$i", i * LINK, 0.0, (i + 1) * LINK, 0.0) }
        val constraints = buildList {
            for (i in 0 until n) {
                add(c("h$i", ConstraintKind.Horizontal, "c$i"))
                add(c("d$i", ConstraintKind.Distance, "c$i", value = LINK))
                if (i + 1 < n) {
                    add(Constraint(ConstraintId("k$i"), ConstraintKind.Coincident, listOf(ConstraintTarget(id("c$i"), TargetPart.End), ConstraintTarget(id("c${i + 1}"), TargetPart.Start))))
                }
            }
        }
        return docOf(*lines.toTypedArray()).copy(constraints = constraints)
    }

    private companion object {
        const val TOLERANCE = 1e-6
        const val SLOW_SOLVE_MS = 300L
        const val FAST_SOLVE_MS = 50L
        const val CHAIN_LINES = 34
        const val LINK = 10.0
        const val HUNDRED = 100
        const val WARMUPS = 3
        const val REPEATS = 5
        const val MICROS_PER_MILLI = 1000.0
        const val JVM_BAR_MS = 50.0
        const val WASM_BAR_MS = 150.0
    }
}
