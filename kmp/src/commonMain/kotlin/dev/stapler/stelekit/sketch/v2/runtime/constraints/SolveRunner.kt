// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.constraints

import arrow.core.right
import dev.stapler.stelekit.sketch.constraints.ConstraintProblem
import dev.stapler.stelekit.sketch.constraints.SolveResult
import dev.stapler.stelekit.sketch.constraints.Solver
import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.commands.Applied
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.Committed
import dev.stapler.stelekit.sketch.v2.commands.DocPatch
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/** The seam between the runner and the numeric solver, so a test can stand in a solver that takes as long as it likes. */
fun interface SolveEngine {
    suspend fun solve(problem: ConstraintProblem, start: DoubleArray): SolveResult
}

/** The real solver as an engine. It computes on the thread the runner gives it and never touches a UI thread. */
class SolverEngine(private val solver: Solver = Solver()) : SolveEngine {
    override suspend fun solve(problem: ConstraintProblem, start: DoubleArray): SolveResult = solver.solve(problem, start)
}

/** How many solver runs and bound-arrow recomputations the runner has done; a redo must leave both unchanged. */
data class SolveCounters(val solves: Int = 0, val bindingRecomputes: Int = 0)

/** What the editor shows while a solve runs: [slow] turns on once a solve has run past `SceneLimits.slowSolveIndicatorMs`. */
data class SolveState(val solving: Boolean = false, val slow: Boolean = false)

enum class UnsatisfiedKind { Conflict, Degenerate, NoConvergence }

/** Why an edit was refused. The document is always left at its last good pose. */
sealed interface SolveRejection {
    /** The edit itself failed (an unknown element, a value out of range, an unbuildable constraint). */
    data class EditFailed(val error: CommandError) : SolveRejection

    /** The solver could not satisfy the constraints after the edit; [ids] are the constraints it names, possibly none. UX-35: "Can't satisfy constraints here". */
    data class Unsatisfiable(val ids: List<ConstraintId>, val kind: UnsatisfiedKind) : SolveRejection

    /** The solve threw (for example out of memory); nothing was changed. [reason] is a fixed token, never user text. */
    data class Crashed(val reason: String) : SolveRejection
}

/** The result of one runner call. Every case but [Done] and [Posed] carries the document to keep showing. */
sealed interface SolveOutcome {
    /** A finished edit: [step] is the ONE history entry holding the edit and the solver's geometry. */
    data class Done(val step: Committed, val summary: SolveSummary?) : SolveOutcome

    /** A drag update solved on the transient scene; nothing is in history yet. */
    data class Posed(val doc: SketchDocumentV2, val summary: SolveSummary?) : SolveOutcome

    /** The edit changed nothing, so there is nothing to record. */
    data class Unchanged(val doc: SketchDocumentV2) : SolveOutcome

    data class Rejected(val reason: SolveRejection, val lastGood: SketchDocumentV2) : SolveOutcome

    /** The user cancelled a slow solve; no history entry exists and [lastGood] is the pose before it. */
    data class Cancelled(val lastGood: SketchDocumentV2) : SolveOutcome
}

/**
 * Runs the constraint solver for the editor (Story 4.1.4). Every call is one edit plus the solve it triggers: [commitEdit] for a
 * discrete edit (add a constraint, resize, move), and [beginDrag] for a gesture whose steps solve on a transient scene.
 *
 * Never on the UI thread: the work runs in the runner's own scope on [context]. The scope has a [CoroutineExceptionHandler] and
 * every solve a `Throwable` guard, so an `OutOfMemoryError` becomes [SolveRejection.Crashed] and never reaches the platform's
 * uncaught-exception handler, which kills an Android process. A solve past `SceneLimits.slowSolveIndicatorMs` turns [state]'s
 * `slow` on (the "Solving..." indicator); [cancel] abandons it and the caller keeps the last good document. A cancelled solve's
 * computation finishes in the background and its result is dropped (the solver's own wall-time cap bounds it).
 *
 * [tier] is the per-target `SolverTier`: `Hidden` never solves, so a sheet's edits commit as plain edits.
 */
class SolveRunner(
    private val limits: SceneLimits = SceneLimits.Default,
    engine: SolveEngine = SolverEngine(),
    tier: SolverTier = SolverTier.Full,
    context: CoroutineContext = Dispatchers.Default,
    private val log: SketchLog? = null,
) {
    private val scope = CoroutineScope(context + SupervisorJob() + CoroutineExceptionHandler { _, t -> uncaught(t) })
    private val gate = Mutex()
    private val mutableState = MutableStateFlow(SolveState())
    private val mutableCounters = MutableStateFlow(SolveCounters())
    private val pipeline = SolvePipeline(engine, tier, mutableCounters)
    private var inFlight: Job? = null
    private var closed = false

    val state: StateFlow<SolveState> = mutableState
    val counters: StateFlow<SolveCounters> = mutableCounters

    /** Applies [edit] to [base], solves, and returns the one history step; [base] and the caller's history are never touched. */
    suspend fun commitEdit(base: SketchDocumentV2, edit: Command): SolveOutcome = execute(base) {
        pipeline.run(base, edit).fold({ SolveOutcome.Rejected(it, base) }, { finished(base, it) })
    }

    /**
     * Solves [doc] as it stands, with no edit, for the status line: [SolveOutcome.Posed] carries the degrees of freedom and the
     * redundant constraints, [SolveOutcome.Rejected] the constraints that disagree (a sheet loaded with conflicts). Nothing is
     * recorded in history and the document is never changed.
     */
    suspend fun analyze(doc: SketchDocumentV2): SolveOutcome = execute(doc) {
        pipeline.run(doc, Command { Applied(it, emptySet()).right() }).fold({ SolveOutcome.Rejected(it, doc) }, { SolveOutcome.Posed(doc, it.summary) })
    }

    /** Opens a drag whose updates solve from the previous pose and which commits once, on [DragSession.release]. */
    fun beginDrag(base: SketchDocumentV2): DragSession = DragSession(base)

    /** Abandons the solve in flight, if any. The caller's pending call returns [SolveOutcome.Cancelled]. */
    fun cancel() {
        inFlight?.cancel(CancellationException("solve cancelled"))
    }

    /** Stops the runner's scope. Call when the editor leaves. */
    fun close() {
        closed = true
        scope.cancel()
    }

    /** A pointer gesture over constrained geometry (ADR-003 item 2: warm start from the last solution during drag). */
    inner class DragSession internal constructor(private val base: SketchDocumentV2) {
        private var lastGood: SketchDocumentV2 = base

        /** The pose currently shown. */
        val pose: SketchDocumentV2 get() = lastGood

        /** Applies [edit] to the current pose and solves from it. On a refusal the pose is unchanged and the outcome says why. */
        suspend fun update(edit: Command): SolveOutcome = execute(lastGood) {
            pipeline.run(lastGood, edit).fold(
                { SolveOutcome.Rejected(it, lastGood) },
                { piped ->
                    lastGood = piped.tx.doc
                    SolveOutcome.Posed(piped.tx.doc, piped.summary)
                },
            )
        }

        /** Commits the whole gesture as one history step from the pose before it to the current pose. */
        fun release(): SolveOutcome {
            val patch = DocPatch.diff(base, lastGood)
            return if (patch.isEmpty) SolveOutcome.Unchanged(base) else SolveOutcome.Done(Committed(base, lastGood, patch), null)
        }

        /** Gives up the gesture and returns the pose it started from. */
        fun abort(): SketchDocumentV2 {
            lastGood = base
            return base
        }
    }

    private fun finished(base: SketchDocumentV2, piped: Piped): SolveOutcome =
        piped.tx.commit().fold(
            { if (it == CommandError.NoChange) SolveOutcome.Unchanged(base) else SolveOutcome.Rejected(SolveRejection.EditFailed(it), base) },
            { SolveOutcome.Done(it, piped.summary) },
        )

    /** Runs [block] on the runner's scope, one at a time, with the slow-solve timer, cancel support and the `Throwable` guard. */
    private suspend fun execute(lastGood: SketchDocumentV2, block: suspend () -> SolveOutcome): SolveOutcome {
        if (closed) return SolveOutcome.Cancelled(lastGood)
        return gate.withLock {
            val work: Deferred<SolveOutcome> = scope.async { guarded(lastGood, block) }
            inFlight = work
            mutableState.value = SolveState(solving = true)
            val timer = scope.launch {
                delay(limits.slowSolveIndicatorMs)
                mutableState.update { it.copy(slow = true) }
            }
            try {
                awaitOrCancelled(work, lastGood)
            } finally {
                timer.cancel()
                inFlight = null
                mutableState.value = SolveState()
            }
        }
    }

    private suspend fun awaitOrCancelled(work: Deferred<SolveOutcome>, lastGood: SketchDocumentV2): SolveOutcome =
        try {
            work.await()
        } catch (e: CancellationException) {
            work.cancel()
            if (!currentCoroutineContext().isActive) throw e
            SolveOutcome.Cancelled(lastGood)
        }

    private suspend fun guarded(lastGood: SketchDocumentV2, block: suspend () -> SolveOutcome): SolveOutcome =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            SolveOutcome.Rejected(SolveRejection.Crashed(tokenOf(t)), lastGood)
        }.also { outcome ->
            if (outcome is SolveOutcome.Rejected) logRejection(outcome.reason)
            countSolve(outcome)
        }

    /** Feeds the local quality counters (Task 6.2.3b): every solver run that ended solved (a drag pose included) or unsatisfiable is one solve. */
    private fun countSolve(outcome: SolveOutcome) {
        when {
            outcome is SolveOutcome.Done && outcome.summary != null -> log?.solveCommitted()
            outcome is SolveOutcome.Posed && outcome.summary != null -> log?.solveCommitted()
            outcome is SolveOutcome.Rejected && outcome.reason is SolveRejection.Unsatisfiable ->
                if (outcome.reason.kind == UnsatisfiedKind.NoConvergence) log?.solveNoConvergence() else log?.solveCommitted()
        }
    }

    private fun logRejection(reason: SolveRejection) {
        when (reason) {
            is SolveRejection.Unsatisfiable -> log?.solveFailed(reason.kind.name, reason.ids.map { it.value })
            is SolveRejection.Crashed -> log?.solveFailed(reason.reason, emptyList())
            is SolveRejection.EditFailed -> Unit
        }
    }

    /** `OutOfMemoryError` has no common-stdlib type, so it is told apart by name (it only picks the logged token). */
    private fun tokenOf(t: Throwable): String = if (t::class.simpleName == OOM_NAME) "out-of-memory" else "unexpected"

    private fun uncaught(t: Throwable) {
        if (t is CancellationException) return
        mutableState.value = SolveState()
        logRejection(SolveRejection.Crashed(tokenOf(t)))
    }

    private companion object {
        const val OOM_NAME = "OutOfMemoryError"
    }
}
