// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.await

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
     */
    suspend fun <T> withLock(lockName: String, block: suspend () -> T): T {
        val handle = jsRequestLockHandle(lockName)
        jsHandleAcquiredPromise(handle).await<JsAny>()
        try {
            return block()
        } finally {
            jsHandleRelease(handle)
            jsHandleDonePromise(handle).await<JsAny>()
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
        val lock = jsHandleAcquiredPromiseOrNull(handle).await<JsAny?>()
        if (lock == null) {
            // Not granted — the callback already returned, settling `done` on its own. Nothing
            // was acquired, so there is nothing to release.
            return null
        }
        try {
            return block()
        } finally {
            jsHandleRelease(handle)
            jsHandleDonePromise(handle).await<JsAny>()
        }
    }
}
