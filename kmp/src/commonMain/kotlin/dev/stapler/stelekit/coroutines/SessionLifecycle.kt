package dev.stapler.stelekit.coroutines

import kotlinx.coroutines.CoroutineScope

/**
 * Contract for a per-graph (or otherwise per-identity) unit of mutable state whose lifetime is
 * bounded by a single [CoroutineScope].
 *
 * See ADR-019 (`docs/adr/ADR-019-graph-scoped-session-lifecycle.md`) for the rationale: this
 * mirrors the sequencing `GraphManager.switchGraph()` already proves correct in production
 * (fresh scope per session, cancelled wholesale on switch, close failures isolated) so future
 * per-graph state doesn't have to re-derive that sequencing per class. [GraphScopedSession]
 * manages instances of this interface generically.
 *
 * Implementations must ensure [close] cancels [scope] (directly or transitively) so that no
 * coroutine launched on it can touch a subsequently-active session's state.
 */
interface SessionLifecycle {
    /** The scope every coroutine owned by this session runs on. Cancelled by [close]. */
    val scope: CoroutineScope

    /**
     * Tears the session down. Must be safe to call at most once per instance. May throw —
     * [GraphScopedSession] catches and logs any [Throwable] from a call it makes to this method
     * so a failing teardown never blocks the next session from opening.
     */
    fun close()
}
