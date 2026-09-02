package dev.stapler.stelekit.coroutines

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Holds the single active [SessionLifecycle] for a per-identity (e.g. per-graph) resource,
 * swapping it wholesale on [switchTo] instead of mutating its fields in place.
 *
 * Mirrors the sequencing `GraphManager.switchGraph()` already proves correct in production — see
 * ADR-019 (`docs/adr/ADR-019-graph-scoped-session-lifecycle.md`) for the full rationale:
 * - A fresh `CoroutineScope(SupervisorJob() + dispatcher)` is built for every new session — never
 *   a scope that shares a parent [kotlinx.coroutines.Job], so cancelling one session's scope can
 *   never cancel anything outside it (see `GraphManager.kt:617-620`'s comment on why).
 * - `switchTo`'s check-current/register-pending/swap bookkeeping is guarded by a [Mutex]. The
 *   factory's own (potentially slow) construction work runs *outside* the lock — only the
 *   bookkeeping is serialized, so a slow session build for one [Id] never blocks unrelated
 *   `switchTo` calls for other [Id]s.
 * - Repeated `switchTo` calls for the already-active [Id] are idempotent (return the same
 *   instance, no reconstruction). A `switchTo` call for an [Id] whose construction is already in
 *   flight awaits that same construction instead of racing a duplicate one.
 * - The previous session's `close()` is called in its own isolated try/catch — a close failure is
 *   logged but never prevents (or is reported as) a failure of the new session opening.
 * - Every `switchTo` call is guaranteed to remove its own bookkeeping entry when it finishes, by
 *   success, by the factory throwing, or by the calling coroutine itself being cancelled — so a
 *   failed or abandoned attempt never wedges a later `switchTo` call for the same [Id].
 *
 * **Factory contract (ADR-019 Consequences / pre-mortem.md P1-2)**: [switchTo]'s `factory`
 * lambda must be safely abandonable without leaking a resource it only acquired but never
 * returned. If the calling coroutine is cancelled after the factory has acquired a resource (a
 * file handle, a `WebLock`, …) but before it returns a fully-constructed [T], the factory itself
 * is responsible for releasing that resource in its own `try/finally` — this class has no
 * reference to a partially-built [T] and therefore cannot call [SessionLifecycle.close] on it.
 *
 * @param Id the identifier type this holder switches on (e.g. `GraphId`, `OpfsGraphSlug`) — this
 *   class makes no assumption about which identifier type a given consumer uses.
 * @param T the [SessionLifecycle] type this holder manages.
 * @param dispatcher the dispatcher every constructed session's [SessionLifecycle.scope] runs on.
 *   Defaults to [Dispatchers.Default]; injectable for tests that need real multi-threaded
 *   dispatch (see `GraphScopedSessionThreadSafetyTest`) or deterministic test dispatchers.
 */
class GraphScopedSession<Id : Any, T : SessionLifecycle>(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val logger = Logger("GraphScopedSession")

    /**
     * Guards only the check-current / register-pending / swap bookkeeping below — never the
     * factory's own (potentially slow, suspending) construction work.
     */
    private val mutex = Mutex()

    private var currentId: Id? = null
    private var current: T? = null
    private val pending = mutableMapOf<Id, CompletableDeferred<T>>()

    /**
     * Returns the active session for [id], constructing one via [factory] if [id] is not already
     * active. See the class KDoc for the full sequencing guarantee and the factory's resource-
     * cleanup contract.
     */
    suspend fun switchTo(id: Id, factory: suspend (CoroutineScope) -> T): T {
        // Step 1 (locked): idempotency check, then either join an in-flight construction for
        // this id or register our own.
        var awaiting: CompletableDeferred<T>? = null
        var deferred: CompletableDeferred<T>? = null

        mutex.withLock {
            val activeSession = current
            if (currentId == id && activeSession != null) {
                return activeSession
            }

            val existing = pending[id]
            if (existing != null) {
                awaiting = existing
            } else {
                val fresh = CompletableDeferred<T>()
                pending[id] = fresh
                deferred = fresh
            }
        }

        awaiting?.let { return it.await() }
        val myDeferred = deferred
            ?: error("GraphScopedSession.switchTo: unreachable — neither joined nor registered a pending construction for $id")

        // Step 2 (unlocked): construct the new session. May suspend arbitrarily long (e.g. a
        // browser permission prompt) — must run outside `mutex`, or a slow construction for one
        // id would block unrelated switchTo calls for other ids.
        try {
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val newSession = factory(scope)

            // Step 3 (locked): swap the new session in.
            val oldSession = mutex.withLock {
                val old = current
                current = newSession
                currentId = id
                old
            }
            myDeferred.complete(newSession)

            // Step 4 (unlocked, isolated): closing the previous session must never prevent (or
            // be reported as) a failure of the new session opening — mirrors
            // GraphManager.switchGraph()'s "close failure can never abort opening the new graph".
            if (oldSession != null) {
                try {
                    oldSession.close()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.error("close() failed for the previous session replaced by id=$id", e)
                }
            }

            return newSession
        } catch (e: Throwable) {
            myDeferred.completeExceptionally(e)
            throw e
        } finally {
            // Guaranteed completion: always remove our own pending entry, even if this coroutine
            // was itself cancelled while suspended inside factory(). NonCancellable is required
            // here — without it, the mutex.withLock suspension below would throw immediately on
            // an already-cancelled coroutine before the entry is actually removed, leaking it and
            // wedging every subsequent switchTo call for this id.
            withContext(NonCancellable) {
                mutex.withLock {
                    if (pending[id] === myDeferred) {
                        pending.remove(id)
                    }
                }
            }
        }
    }
}
