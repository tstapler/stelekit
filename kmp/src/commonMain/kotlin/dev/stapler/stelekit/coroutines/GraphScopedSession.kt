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
    private var _current: T? = null
    private val pending = mutableMapOf<Id, CompletableDeferred<T>>()

    /**
     * The active session, or `null` if [switchTo] has never successfully completed. Read-only —
     * every mutation goes through [switchTo]'s own mutex-guarded swap. Not itself mutex-guarded
     * (a plain volatile-free read of a `var` set only inside the lock): callers that need a
     * value guaranteed consistent with a specific `switchTo` call already hold a reference to
     * that call's return value instead of re-reading this property.
     */
    val currentOrNull: T? get() = _current

    /**
     * Same as [currentOrNull], but throws [IllegalStateException] instead of returning `null`.
     * Use only at call sites whose contract already requires an active session to exist (a
     * `null` here is a real programming error at that point, not a state to tolerate).
     */
    val current: T get() = checkNotNull(_current) { "GraphScopedSession.current read before any switchTo call completed" }

    /**
     * Returns the active session for [id], constructing one via [factory] if [id] is not already
     * active. See the class KDoc for the full sequencing guarantee and the factory's resource-
     * cleanup contract.
     *
     * **Precondition — callers must serialize `switchTo` calls for *different* [id]s.** The
     * idempotency/joining guarantee above is scoped to *repeated calls for the same [id]*; it does
     * not order two concurrent calls for two different ids against each other. Each call's factory
     * runs outside the lock, so if a caller races `switchTo(A)` against `switchTo(B)` concurrently,
     * whichever factory happens to finish last wins the swap into [_current] — even if that call
     * was issued first. This class's own only current caller, `PlatformFileSystem.preload`/
     * `switchActiveGraph`, is safe today because every post-boot call to it comes from
     * `Main.kt`'s single `graphManager.graphRegistry.collect { ... }` coroutine — `Flow` collection
     * is inherently sequential, so no two switches for different graphs ever race there. A future
     * caller without that same single-collector discipline would need its own external ordering (a
     * sequence number, a `Mutex`, or a single-consumer channel) — this class provides none itself.
     * (`GraphManager.switchGraph` is *not* a caller of this class — it's the analogous,
     * independently-implemented pattern this generic holder was extracted to mirror, not a
     * consumer of it.)
     */
    suspend fun switchTo(id: Id, factory: suspend (CoroutineScope) -> T): T {
        // Step 1 (locked): idempotency check, then either join an in-flight construction for
        // this id or register our own.
        var awaiting: CompletableDeferred<T>? = null
        var deferred: CompletableDeferred<T>? = null

        mutex.withLock {
            val activeSession = _current
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
                val old = _current
                _current = newSession
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
