package dev.stapler.stelekit.ui.components

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Jacobson/Karels smoothing factors for [MermaidEngineActor]'s adaptive render timeout — same constants TCP's RTO estimator uses. */
private const val RTT_ALPHA = 0.125
private const val RTT_BETA = 0.25

/**
 * Serializes all access to a shared [MermaidJvmEngine]'s GraalJS `Context` through a dedicated
 * single-threaded dispatcher, mirroring `db/DatabaseWriteActor.kt`'s "one stateful resource, many
 * concurrent coroutine callers" pattern.
 *
 * GraalJS `Context` rejects concurrent multi-thread access by default (throws
 * `IllegalStateException: Multi threaded access requested` if two threads ever call into it at
 * once) — a multi-threaded pool like [dev.stapler.stelekit.coroutines.PlatformDispatcher.IO]
 * would eventually trigger that under two mermaid blocks visible on screen at the same time. A
 * dedicated single-threaded dispatcher makes concurrent access structurally impossible: only one
 * coroutine at a time ever runs on it, so calls are naturally queued rather than racing.
 *
 * Owns its own [CoroutineScope] — never a caller-supplied `rememberCoroutineScope()`, per this
 * repo's coroutine-scope-ownership rule — so it survives Compose recomposition/cancellation.
 * Callers must call [close] to release the dispatcher's thread and the underlying GraalJS
 * `Context` when the actor is no longer needed.
 */
class MermaidEngineActor(
    initialEngine: MermaidJvmEngine = MermaidJvmEngine(),
    // Injectable so tests can verify the timeout→swap behavior with a fast fake, without depending
    // on real GraalJS cold-start latency (which can itself exceed MERMAID_RENDER_TIMEOUT_MS under
    // load — a separate, already-documented risk, not something this seam is meant to paper over).
    private val newEngine: () -> MermaidJvmEngine = ::MermaidJvmEngine,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MermaidEngineActor").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // renderMermaidWith's own withTimeoutOrNull hops onto its own per-call single-thread dispatcher
    // (see that function's docs) so a genuine hang can be abandoned — which means dispatcher-thread-
    // affinity alone can't guarantee serialization: the actor's own dedicated dispatcher's one
    // thread would otherwise sit free to start a second call's render() while the first is still
    // in flight on its own dispatcher. The Mutex closes that gap by serializing at the call level,
    // independent of which thread each call's work happens to run on.
    private val mutex = Mutex()

    // A timed-out call's engine has a thread permanently wedged inside its GraalJS Context (see
    // renderMermaidWith's docs) — that Context can never safely serve another call. Replacing it
    // with a fresh engine, rather than reusing the poisoned one, is what makes a single hang a
    // one-time cost instead of a permanent app-wide degradation. Read/written only under `mutex`.
    private var engine: MermaidJvmEngine = initialEngine

    // Adaptive per-call timeout budget, same shape as TCP's RTO estimator (Jacobson/Karels): a
    // smoothed render duration (SRTT) and mean deviation (RTTVAR), combined as SRTT + 4*RTTVAR.
    // MERMAID_RENDER_TIMEOUT_MS was calibrated once against one uncontended dev machine (ADR-001's
    // spike measured 1.3-5.6s even for a *cold* call there); a real CI runner can be far more
    // contended, and a static deadline can't tell a genuinely-wedged Context apart from a render
    // that's merely slow right now because the CPU is starved — this lets the budget track actual
    // recent conditions instead of a constant guessed on different hardware. Floors at
    // MERMAID_RENDER_TIMEOUT_MS (still abandons a fast machine's genuine hang quickly) and
    // ceilings at MERMAID_RENDER_TIMEOUT_CEILING_MS (a truly wedged Context must still eventually
    // be abandoned and swapped out, never waited on forever). Read/written only under `mutex`.
    private var smoothedDurationMs: Double? = null
    private var durationDeviationMs: Double = 0.0

    private fun nextTimeoutMs(): Long {
        val srtt = smoothedDurationMs ?: return MERMAID_RENDER_TIMEOUT_MS
        return (srtt + 4 * durationDeviationMs).toLong()
            .coerceIn(MERMAID_RENDER_TIMEOUT_MS, MERMAID_RENDER_TIMEOUT_CEILING_MS)
    }

    private fun recordDurationMs(elapsedMs: Long) {
        val prev = smoothedDurationMs
        if (prev == null) {
            smoothedDurationMs = elapsedMs.toDouble()
            durationDeviationMs = elapsedMs / 2.0
        } else {
            val error = elapsedMs - prev
            smoothedDurationMs = prev + RTT_ALPHA * error
            durationDeviationMs += RTT_BETA * (kotlin.math.abs(error) - durationDeviationMs)
        }
    }

    /** Renders [key] on the actor's dedicated thread, one call at a time; delegates to [renderMermaidWith]. */
    suspend fun render(key: MermaidRenderKey): MermaidRenderResult =
        mutex.withLock {
            withContext(scope.coroutineContext) {
                val startedAtNanos = System.nanoTime()
                val result = renderMermaidWith(engine, key, nextTimeoutMs())
                if (result is MermaidRenderResult.Failed && result.reason == MERMAID_TIMEOUT_REASON) {
                    // The wedged Context must be force-closed before it's dropped, or its native
                    // GraalJS/Truffle state leaks for the rest of the process's life (CRITICAL —
                    // see MermaidEngineActor.kt:59-62 in the PR review).
                    engine.forceClose()
                    engine = newEngine()
                    // A timed-out call's elapsed time isn't a real duration measurement (it was
                    // abandoned at the timeout boundary, not completed) — don't let it feed the
                    // estimator. Reset it too: the fresh engine's next call is effectively a new
                    // cold start, and carrying forward an inflated estimate from a wedged-Context
                    // episode would keep every later render on this fresh engine artificially slow.
                    smoothedDurationMs = null
                    durationDeviationMs = 0.0
                } else if (result is MermaidRenderResult.Rendered) {
                    recordDurationMs((System.nanoTime() - startedAtNanos) / 1_000_000)
                }
                result
            }
        }

    fun close() {
        dispatcher.close()
    }
}
