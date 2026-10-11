package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.performance.DebugBuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * Per-[GraphId] write lock that serializes off-graph writers (merge/share) against
 * `GraphManager.switchGraph`'s open + migration and factory-close critical sections.
 *
 * LOCK ORDER (normative): a coroutine never holds `lock(X)` while acquiring `lock(Y)`, and never
 * awaits `GraphManager.awaitPendingMigration()` while holding any graph lock (the init coroutine
 * needs `lock(id)` to complete that deferred). [GraphWriteLockOrderGuard] enforces both in debug
 * builds.
 */
class GraphWriteLock {
    private val mutexes = MutableStateFlow<Map<GraphId, Mutex>>(emptyMap())
    private val holders = MutableStateFlow<Map<GraphId, String>>(emptyMap())

    private fun mutexFor(id: GraphId): Mutex {
        mutexes.value[id]?.let { return it }
        mutexes.update { current -> if (id in current) current else current + (id to Mutex()) }
        return mutexes.value.getValue(id)
    }

    /** Label of the current holder of `lock(id)`, if the holder supplied one (for timeout logs). */
    fun holderLabel(id: GraphId): String? = holders.value[id]

    /** True while some coroutine holds `lock(id)`. */
    fun isLocked(id: GraphId): Boolean = mutexes.value[id]?.isLocked == true

    /** Runs [block] holding `lock(id)`; suspends until it is free. */
    suspend fun <T> withLock(id: GraphId, label: String? = null, block: suspend () -> T): T {
        GraphWriteLockOrderGuard.checkAcquire(coroutineContext, id)
        val mutex = mutexFor(id)
        return withContext(HeldGraphLock(id)) {
            mutex.withLock {
                runHeld(id, label, block)
            }
        }
    }

    /**
     * Like [withLock] but gives up acquiring after [timeout]. On timeout calls [onTimeout] with the
     * holder's label (if known) and returns `null` WITHOUT running [block]; callers that must
     * proceed regardless use [withLockOrDegrade].
     */
    suspend fun <T> withLockTimeout(
        id: GraphId,
        timeout: Duration,
        label: String? = null,
        onTimeout: (holderLabel: String?) -> Unit = {},
        block: suspend () -> T,
    ): T? {
        GraphWriteLockOrderGuard.checkAcquire(coroutineContext, id)
        val mutex = mutexFor(id)
        // The flag is set synchronously right after lock() returns so a timeout racing a successful
        // acquire still unlocks exactly once below.
        var acquired = false
        try {
            withTimeoutOrNull(timeout) {
                mutex.lock()
                acquired = true
            }
        } catch (e: CancellationException) {
            // Parent cancelled after the lock was granted but before the result was delivered.
            if (acquired) mutex.unlock()
            throw e
        }
        if (!acquired) {
            onTimeout(holderLabel(id))
            return null
        }
        return try {
            withContext(HeldGraphLock(id)) { runHeld(id, label, block) }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Bounded acquisition that never blocks the caller forever: runs [block] under `lock(id)`, or,
     * if the lock is not acquired within [timeout], calls [onTimeout] and runs [block] WITHOUT the
     * lock (degrade-open).
     */
    suspend fun <T> withLockOrDegrade(
        id: GraphId,
        timeout: Duration,
        label: String? = null,
        onTimeout: (holderLabel: String?) -> Unit = {},
        block: suspend () -> T,
    ): T {
        var degraded = false
        val result = withLockTimeout(id, timeout, label, { h -> degraded = true; onTimeout(h) }, block)
        @Suppress("UNCHECKED_CAST")
        return if (degraded) block() else result as T
    }

    private suspend fun <T> runHeld(id: GraphId, label: String?, block: suspend () -> T): T {
        if (label != null) holders.update { it + (id to label) }
        try {
            return block()
        } finally {
            if (label != null) holders.update { it - id }
        }
    }
}

/** Coroutine-context marker recording which graph lock the current coroutine holds. */
internal class HeldGraphLock(val id: GraphId) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HeldGraphLock>
}

/**
 * Debug-build assertion of the [GraphWriteLock] lock order. Active only when
 * [DebugBuildConfig.isDebugBuild] (or [forceEnabled], for tests) is true; otherwise a no-op.
 */
object GraphWriteLockOrderGuard {
    /** Test override; when true the guard runs regardless of [DebugBuildConfig]. */
    @kotlin.concurrent.Volatile
    var forceEnabled: Boolean = false

    private val enabled: Boolean get() = forceEnabled || DebugBuildConfig.isDebugBuild

    internal fun checkAcquire(ctx: CoroutineContext, wanted: GraphId) {
        if (!enabled) return
        val held = ctx[HeldGraphLock] ?: return
        error("GraphWriteLock order violation: acquiring lock(${wanted.value}) while holding lock(${held.id.value})")
    }

    /** Call before awaiting `_pendingMigration`; fails if any graph lock is held. */
    fun checkAwaitPendingMigration(ctx: CoroutineContext) {
        if (!enabled) return
        val held = ctx[HeldGraphLock] ?: return
        error("GraphWriteLock order violation: awaitPendingMigration() while holding lock(${held.id.value})")
    }
}
