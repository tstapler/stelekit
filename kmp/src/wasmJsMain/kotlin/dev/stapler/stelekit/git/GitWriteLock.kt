package dev.stapler.stelekit.git

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
 * This is a well-known Web Locks idiom (see Pattern Decision "Web Lock scope" in
 * project_plans/web-git-writeback/implementation/plan.md), and it is only safe here because
 * [withLock] below always calls `release` from a `finally` block within a single suspend-function
 * scope. Do NOT reuse this handle to hold a lock open across multiple independently-invoked
 * suspend calls (e.g. spanning `commit()` -> `push()`) — that is exactly the leak risk the plan
 * rejected; see the Epic 2.3 "Web Lock — guarantees and known gaps" note.
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
 * Web-Locks-backed cross-tab mutual exclusion, scoped to `push()`'s write-critical-section only
 * (the final ref-update PATCH on GitHub, or the single commits POST on GitLab) plus the
 * `clearDirtySet` checkpoint write immediately following a successful push. It is NOT held across
 * `commit()`/`fetch()`/`merge()`.
 *
 * See Story 2.3.1 and the Epic 2.3 "Web Lock — guarantees and known gaps" note in
 * project_plans/web-git-writeback/implementation/plan.md for the exact race coverage — in
 * particular, what this deliberately does NOT close (the fetch/merge read-modify-write window
 * across tabs, and same-tab double-invocation reentrancy).
 */
object GitWriteLock {

    /**
     * Derives a deterministic, URL-safe `navigator.locks.request()` name from a git remote URL.
     * Pure logic — delegates to [GitWriteLockNaming] (commonMain) so it is directly unit-testable
     * from `commonTest` without requiring a wasmJs target.
     */
    fun lockNameFor(remoteUrl: String): String = GitWriteLockNaming.lockNameFor(remoteUrl)

    /**
     * Acquires the named lock, runs [block] exclusively, and releases the lock whether [block]
     * returns normally or throws. Scope this ONLY around a single push() attempt's
     * write-critical-section — never across multiple independent suspend calls.
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
}
