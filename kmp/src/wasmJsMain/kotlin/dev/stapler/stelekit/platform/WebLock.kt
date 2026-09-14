// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.withContext

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object.

/**
 * Requests `navigator.locks.request(name, ...)` using the "acquire-now, release-later" idiom: the
 * request() callback captures a `resolve` function and returns a Promise it deliberately never
 * resolves itself. The returned handle exposes:
 *   - `acquired` — a Promise that resolves the instant the lock is granted (the moment the
 *     request() callback is invoked), letting Kotlin `await` acquisition without the callback
 *     having returned yet.
 *   - `release` — a zero-arg function; calling it resolves the callback's held Promise, which lets
 *     request()'s callback return and the lock be released.
 *   - `done` — the outer request() Promise itself (resolves once the callback's returned Promise —
 *     i.e. `held` — has settled), useful to await full teardown after calling `release`.
 *
 * This is a well-known Web Locks idiom, and it is only safe here because [WebLock.withLock] below
 * always calls `release` from a `finally` block within a single suspend-function scope. Do NOT
 * reuse this handle to hold a lock open across multiple independently-invoked suspend calls — that
 * is exactly the leak risk this project's own lock naming/scoping decisions are designed to avoid.
 *
 * This is a standalone implementation scoped to `web-local-folder-livesync`, independently typed
 * out from (not shared with, not delegating to) `git/GitWriteLock.kt`'s equivalent machinery — see
 * Epic 1.1 of `project_plans/web-local-folder-livesync/implementation/plan.md` for why the two are
 * intentionally permitted to drift independently rather than being extracted into a shared utility.
 */
private fun jsRequestLockHandle(name: String): JsAny = js(
    """
    (function() {
        var acquiredResolve;
        var releaseResolve;
        var acquired = new Promise(function(resolve) { acquiredResolve = resolve; });
        var held = new Promise(function(resolve) { releaseResolve = resolve; });
        var done = navigator.locks.request(name, function(lock) {
            acquiredResolve(lock);
            return held;
        });
        return { acquired: acquired, release: releaseResolve, done: done };
    })()
    """
)

private fun jsHandleAcquiredPromise(handle: JsAny): kotlin.js.Promise<JsAny> = js("handle.acquired")
private fun jsHandleRelease(handle: JsAny): Unit = js("handle.release()")
private fun jsHandleDonePromise(handle: JsAny): kotlin.js.Promise<JsAny> = js("handle.done")

/**
 * Task 6.2.1a: non-blocking sibling of [jsRequestLockHandle] using `navigator.locks.request(name,
 * { ifAvailable: true }, callback)` — per the Web Locks spec, when `ifAvailable: true` and the
 * lock is already held elsewhere, the callback is invoked synchronously with `lock === null` and
 * whatever it returns settles `done` immediately, with nothing to release. This is a genuinely
 * different control-flow shape from [jsRequestLockHandle] (which always resolves `acquired` to a
 * real lock and always needs [jsHandleRelease] called) — not reused/parameterized from it, per
 * this task's own doc note in `project_plans/web-local-folder-livesync/implementation/plan.md`
 * ("don't try to reuse the blocking `withLock`'s interop function since the semantics differ").
 *
 * `acquired` resolves to the granted lock object on success, or JS `null` when the lock was busy
 * — [WebLock.tryWithLock] surfaces that `null` directly as its own Kotlin `null` return.
 */
private fun jsRequestLockHandleIfAvailable(name: String): JsAny = js(
    """
    (function() {
        var acquiredResolve;
        var releaseResolve;
        var acquired = new Promise(function(resolve) { acquiredResolve = resolve; });
        var held = new Promise(function(resolve) { releaseResolve = resolve; });
        var done = navigator.locks.request(name, { ifAvailable: true }, function(lock) {
            if (lock === null) {
                acquiredResolve(null);
                return Promise.resolve();
            }
            acquiredResolve(lock);
            return held;
        });
        return { acquired: acquired, release: releaseResolve, done: done };
    })()
    """
)

/** Nullable-aware sibling of [jsHandleAcquiredPromise] — [jsRequestLockHandleIfAvailable]'s
 * `acquired` promise can resolve to JS `null` (lock busy under `ifAvailable: true`). */
private fun jsHandleAcquiredPromiseOrNull(handle: JsAny): kotlin.js.Promise<JsAny?> = js("handle.acquired")

/**
 * Shared release step for [WebLock.withLock], [WebLock.tryWithLock], and [WebLock.HeldLock.release]
 * — resolves the callback's held `Promise` and awaits full teardown, under [NonCancellable] so it
 * still completes even when the calling coroutine's own `Job` is already cancelled. Safe to call
 * even if the lock was never actually granted (e.g. a `tryWithLock` busy result) — see those call
 * sites' doc comments.
 */
private suspend fun releaseWebLockHandle(handle: JsAny) {
    withContext(NonCancellable) {
        jsHandleRelease(handle)
        jsHandleDonePromise(handle).await<JsAny>()
    }
}

/**
 * Web-Locks-backed mutual exclusion for `web-local-folder-livesync`'s own lock names (see
 * `FolderSyncLockNaming`, Epic 1.2). This is a standalone implementation — it does not import from
 * or delegate to `git/GitWriteLock.kt`, which is a `web-git-writeback`-owned file this project must
 * not touch. The two implementations share the same acquire-now/release-later idiom by design but
 * are permitted to drift independently.
 */
object WebLock {

    /**
     * Acquires the named lock, runs [block] exclusively, and releases the lock whether [block]
     * returns normally or throws. Do NOT hold this across multiple independently-invoked suspend
     * calls — scope it tightly around a single critical section.
     *
     * Bug fix (code-review repair loop): `navigator.locks.request(...)` (inside
     * [jsRequestLockHandle]) fires synchronously — the *browser* acquires the lock the instant its
     * callback runs, independent of whether the Kotlin coroutine awaiting
     * [jsHandleAcquiredPromise] is still suspended or has since been cancelled. Previously the
     * acquire-await sat *outside* the `try`, so a cancellation delivered while suspended there (or
     * anywhere else in this function) skipped the `finally` release entirely — the lock then stays
     * held until the browser tab closes. The `try` now wraps the acquire-await itself, and the
     * `finally`'s release is run under [NonCancellable] so it can still suspend
     * (`jsHandleDonePromise(handle).await()`) even though this coroutine's own `Job` is already
     * cancelled by the time `finally` runs. Calling [jsHandleRelease] here is safe even if the
     * lock was never actually granted (e.g. cancelled before [jsHandleAcquiredPromise] resolved) —
     * it only resolves the callback's held `Promise`, a harmless no-op if that callback hasn't
     * fired yet, and the lock is then released the instant it eventually is.
     */
    suspend fun <T> withLock(lockName: String, block: suspend () -> T): T {
        val handle = jsRequestLockHandle(lockName)
        try {
            jsHandleAcquiredPromise(handle).await<JsAny>()
            return block()
        } finally {
            releaseWebLockHandle(handle)
        }
    }

    /**
     * Task 6.2.1a: non-blocking variant of [withLock] — attempts to acquire [lockName] via
     * `navigator.locks.request(name, { ifAvailable: true }, ...)`. If another [withLock]/
     * [tryWithLock] call already holds [lockName], returns `null` immediately (the callback fires
     * synchronously with a busy `null` lock — see [jsRequestLockHandleIfAvailable]'s doc comment)
     * rather than blocking until the lock is released. If the lock is free, acquires it, runs
     * [block] exclusively, and releases the lock whether [block] returns normally or throws — same
     * release discipline as [withLock]. Do NOT hold this across multiple independently-invoked
     * suspend calls — scope it tightly around a single tick's work, same as [withLock].
     */
    suspend fun <T> tryWithLock(lockName: String, block: suspend () -> T): T? {
        val handle = jsRequestLockHandleIfAvailable(lockName)
        // Bug fix (code-review repair loop): same leak as `withLock` above — the acquire-await
        // (and the "was it actually granted?" branch below) now live inside the `try`, and release
        // runs under `NonCancellable` in `finally`, so a cancellation delivered anywhere in this
        // function — including before we've even learned whether the lock was granted or busy —
        // still releases it if it was (or is about to be) granted. Calling [jsHandleRelease]
        // unconditionally in `finally` is safe even for the "busy" (`lock == null`) case: the
        // callback there already resolved its own held `Promise` itself (`Promise.resolve()`), so
        // this is a harmless no-op resolve-of-an-already-settled-promise, not a real release.
        try {
            val lock = jsHandleAcquiredPromiseOrNull(handle).await<JsAny?>()
            if (lock == null) {
                return null
            }
            return block()
        } finally {
            releaseWebLockHandle(handle)
        }
    }

    /**
     * Non-blocking tab-lifetime leader election: attempts to acquire [lockName] via
     * `navigator.locks.request(name, { ifAvailable: true }, ...)` and, unlike [tryWithLock],
     * deliberately never releases it. Returns `true` if this tab is now the lock's sole holder,
     * `false` if another tab/context already holds it. The lock is held until the browser
     * discards this document (tab close/navigate/reload) — per the Web Locks spec, locks are
     * automatically released when their requesting context goes away, so no explicit release is
     * needed for a "hold for the lifetime of this tab" use case like single-writer SQLite/OPFS
     * driver ownership. Do not call this for short-lived critical sections — use [tryWithLock].
     */
    suspend fun tryAcquireLeader(lockName: String): Boolean {
        val handle = jsRequestLockHandleIfAvailable(lockName)
        val lock = jsHandleAcquiredPromiseOrNull(handle).await<JsAny?>()
        if (lock == null) {
            // Busy: the callback already resolved its own held Promise (see
            // jsRequestLockHandleIfAvailable), so this release is a harmless no-op cleanup.
            jsHandleRelease(handle)
            return false
        }
        return true
    }

    /**
     * Task 3.3.1a: opaque handle for a lock acquired via [acquireHeld]. Unlike [withLock]/
     * [tryWithLock] — which always acquire and release within one suspend-function scope — a
     * relocate's quiesce/release are two separate coordinator calls
     * ([dev.stapler.stelekit.db.GraphMoveQuiesceStrategy.quiesce]/`release`), so the lock must be
     * acquirable in one call and released in a later, independent one. Callers must call [release]
     * exactly once; an un-released handle stays held until the tab is discarded, same as
     * [tryAcquireLeader].
     */
    class HeldLock internal constructor(private val handle: JsAny) {
        suspend fun release() = releaseWebLockHandle(handle)
    }

    /**
     * Acquires [lockName], suspending until granted, and returns a [HeldLock] for the caller to
     * [HeldLock.release] explicitly later — see that class's doc comment for why this exists
     * alongside [withLock]. Do not use this for a short-lived critical section that fits in a
     * single suspend call — use [withLock] there instead, so a thrown/cancelled block can never
     * leave the lock held past its scope.
     */
    suspend fun acquireHeld(lockName: String): HeldLock {
        val handle = jsRequestLockHandle(lockName)
        jsHandleAcquiredPromise(handle).await<JsAny>()
        return HeldLock(handle)
    }
}
