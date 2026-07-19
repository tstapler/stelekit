// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// js() calls must be top-level functions in Kotlin/Wasm — TextFile/Dir/rootDir/FakeCacheAccess/
// newJsArray/makeWritableHostRoot/writableRoot*/etc. live in HostDirectoryTestFixtures.kt, same
// package (Task 3.4.3a's shared-fixture convention). This file's own small fixtures below are not
// reused from HostDirectoryPollerBenchmarkTest.kt (its newTickCounter/tickCounterValue/
// countingRootDir are `private` to that file) or from HostDirectoryTestFixtures.kt (whose
// TextFile/Dir/rootDir builders are immutable snapshots — this file needs a *mutable* fixture, see
// makeMutableSingleFileRoot below).

/** Epic 6.3 (Task 6.3.2a/6.3.3a): counts how many times a poll tick's directory walk begins —
 * mirrors HostDirectoryPollerBenchmarkTest.kt's private tick-counter fixture, independently
 * defined here since that one is `private` to its own file. */
private fun newTickCounter(): JsAny = js("({ count: 0 })")
private fun tickCounterValue(counter: JsAny): Int = js("counter.count | 0")
private fun countingRootDir(children: JsAny, tickCounter: JsAny): JsAny = js(
    """
    ({
        kind: 'directory',
        name: 'root',
        values: function() {
            tickCounter.count = tickCounter.count + 1;
            var idx = 0;
            return {
                next: function() {
                    if (idx < children.length) {
                        return Promise.resolve({ done: false, value: children[idx++] });
                    }
                    return Promise.resolve({ done: true, value: undefined });
                }
            };
        }
    })
    """,
)

/**
 * Epic 6.3 (Story 6.3.3): a single-file host root whose file content/mtime can be mutated after
 * construction via [mutateSingleFileRoot] — simulates an external host-side edit landing between
 * poll ticks. [HostDirectoryTestFixtures.kt]'s `TextFile`/`Dir`/`rootDir` builders are immutable
 * snapshots (their JS closures capture fixed values at build time), so this is a dedicated
 * fixture rather than a reuse of that builder.
 */
private fun makeMutableSingleFileRoot(name: String, initialContent: String, initialMtime: Long): JsAny = js(
    """
    (function() {
        var state = { content: initialContent, mtime: initialMtime };
        var fileEntry = {
            kind: 'file',
            name: name,
            getFile: function() {
                return Promise.resolve({
                    lastModified: state.mtime,
                    size: state.content.length,
                    text: function() { return Promise.resolve(state.content); }
                });
            }
        };
        return {
            kind: 'directory',
            name: 'root',
            values: function() {
                var idx = 0;
                var items = [fileEntry];
                return {
                    next: function() {
                        if (idx < items.length) {
                            return Promise.resolve({ done: false, value: items[idx++] });
                        }
                        return Promise.resolve({ done: true, value: undefined });
                    }
                };
            },
            _mutate: function(content, mtime) { state.content = content; state.mtime = mtime; }
        };
    })()
    """,
)

private fun mutateSingleFileRoot(root: JsAny, content: String, mtime: Long): Unit = js("root._mutate(content, mtime)")

/**
 * Epic 6.3 (Stories 6.3.2/6.3.3): two-"tab" simulation — two real [HostDirectorySync] instances,
 * each with its own fake [HostDirectorySync.CacheAccess], both using real Web Locks
 * (`WebLock`/`FolderSyncLockNaming`) keyed by the same `graphId` so they genuinely contend on the
 * same lock names, same-origin/same-page (no literal second browser tab needed — the real
 * `navigator.locks` registry is shared within one page, which is what makes this simulation
 * valid). No [PlatformFileSystem] instance is involved anywhere in this file, per Task 1.6.1c's
 * independence guarantee.
 */
class HostDirectorySyncCrossTabTest {

    private fun freshId(prefix: String): String = "$prefix-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun newSync(graphId: String, opfsPath: String, cacheAccess: HostDirectorySync.CacheAccess, scope: CoroutineScope): HostDirectorySync {
        val sync = HostDirectorySync(graphIdProvider = { graphId }, cacheAccess = cacheAccess, scope = scope)
        sync.hostGraphOpfsPath = opfsPath
        return sync
    }

    /** Polls [block] on a real (non-test-scheduler) dispatcher until true or the timeout elapses —
     * mirrors HostDirectorySyncWriteThroughTest.kt's helper, needed here for the same reason: real
     * work (scheduleHostWriteThrough's launch, real Web Locks Promise settlement) doesn't complete
     * synchronously within a single test-dispatcher tick. */
    private suspend fun awaitCondition(timeoutMs: Long = 3000, stepMs: Long = 10, block: () -> Boolean) {
        var waited = 0L
        while (!block() && waited < timeoutMs) {
            withContext(Dispatchers.Default) { delay(stepMs) }
            waited += stepMs
        }
    }

    // ── Story 6.3.2 (Task 6.3.2a): per-write lock — two real instances contending ───────────────

    @Test
    fun flushHostWrite_should_SerializeAcrossTwoHostDirectorySyncInstances_When_BothScheduleWriteThroughForSamePathConcurrently() = runTest {
        val graphId = freshId("crosstab-write")
        val opfsPath = "/stelekit/${freshId("write")}"
        val root = makeWritableHostRoot()
        writableRootSetContent(root, "Foo.md", "original")
        val fullPath = "$opfsPath/Foo.md"

        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val syncA = newSync(graphId, opfsPath, FakeCacheAccess(), scopeA)
        val syncB = newSync(graphId, opfsPath, FakeCacheAccess(), scopeB)
        syncA.hostDirHandle = root
        syncB.hostDirHandle = root

        // Both instances share the same pre-existing baseline hash for this path — whichever
        // instance's flush attempt acquires the write lock SECOND re-reads the (by-then
        // already-updated) host content under the lock and finds it no longer matches this stale
        // baseline, routing to onHostConflict instead of a second createWritable() call. This is
        // what makes "exactly one write" a deterministic outcome regardless of which instance
        // wins the lock race — Story 6.1.1's own acceptance criteria: "the second tab's write,
        // once it acquires the lock, re-checks freshness against the now-updated host state... the
        // lock alone prevents interleaved createWritable() calls."
        syncA.hostContentHashes[fullPath] = "original".hashCode()
        syncB.hostContentHashes[fullPath] = "original".hashCode()

        val conflicts = mutableListOf<String>()
        syncA.onHostConflict = { path, _ -> conflicts += "A:$path" }
        syncB.onHostConflict = { path, _ -> conflicts += "B:$path" }

        syncA.scheduleHostWriteThrough(fullPath, HostWritePayload.Text("edit-from-A"))
        syncB.scheduleHostWriteThrough(fullPath, HostWritePayload.Text("edit-from-B"))

        awaitCondition { writableRootCreateWritableCallCount(root) >= 1 && conflicts.isNotEmpty() }

        assertEquals(1, writableRootCreateWritableCallCount(root), "exactly one createWritable() invocation — not two interleaved writes")
        assertEquals(1, conflicts.size, "exactly one loser must detect its now-stale baseline via the freshness check")

        scopeA.cancel()
        scopeB.cancel()
    }

    // ── Story 6.2.1 (Task 6.2.1b integration): per-poll-tick lock — two real instances contending ─

    @Test
    fun pollHostDirectoryOnce_should_HaveExactlyOneWinningTabPerTick_When_TwoTabsAreBothDueForAPollAtTheSameInstant() = runTest {
        val graphId = freshId("crosstab-poll")
        val opfsPath = "/stelekit/${freshId("poll")}"
        val dispatcher = StandardTestDispatcher()
        val sharedScope = TestScope(dispatcher)

        val tickCounter = newTickCounter()
        val root = countingRootDir(newJsArray(), tickCounter)

        val syncA = newSync(graphId, opfsPath, FakeCacheAccess(), sharedScope)
        val syncB = newSync(graphId, opfsPath, FakeCacheAccess(), sharedScope)
        syncA.hostDirHandle = root
        syncB.hostDirHandle = root

        syncA.startHostDirectoryPolling()
        syncB.startHostDirectoryPolling()

        sharedScope.advanceTimeBy(10_000)
        sharedScope.runCurrent()
        // Real Web Locks settlement is Promise-driven, not governed by the virtual clock — give
        // any pending real microtask/macrotask a moment to actually resume the losing side's
        // coroutine before asserting the final count.
        awaitCondition(timeoutMs = 1000) { tickCounterValue(tickCounter) >= 1 }

        assertEquals(
            1,
            tickCounterValue(tickCounter),
            "exactly one tab must win the poll lock and perform the directory walk for a contended tick",
        )

        syncA.stopHostDirectoryPolling()
        syncB.stopHostDirectoryPolling()
        sharedScope.cancel()
    }

    // ── Story 6.3.3 (Task 6.3.3a/b): losing-tab cache convergence — bounded, not immediate ───────

    @Test
    fun pollHostDirectoryOnce_should_ConvergeOnLosingTabsOwnNextTick_NotImmediately_When_WinningTabAppliesChangeWhileLosingTabIsLockedOut() = runTest {
        val graphId = freshId("crosstab-converge")
        val opfsPath = "/stelekit/${freshId("converge")}"
        val fullPath = "$opfsPath/Foo.md"
        val root = makeMutableSingleFileRoot("Foo.md", "original", 1_000L)

        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cacheA = FakeCacheAccess()
        val syncA = newSync(graphId, opfsPath, cacheA, scopeA)
        syncA.hostDirHandle = root

        // B runs its own real timer loop on its own virtual-time TestScope (Task 5.5.2a's idiom),
        // so "B's own next un-contended tick" is a genuine effectivePollIntervalMs()-later event,
        // not something asserted purely via direct method calls.
        val dispatcherB = StandardTestDispatcher()
        val scopeB = TestScope(dispatcherB)
        val cacheB = FakeCacheAccess()
        val syncB = newSync(graphId, opfsPath, cacheB, scopeB)
        syncB.hostDirHandle = root

        // Warm both instances' baselines identically before the contended tick — mirrors a real
        // session-start reconciliation having already run for both tabs.
        syncA.pollHostDirectoryOnce(root, opfsPath)
        syncB.pollHostDirectoryOnce(root, opfsPath)
        assertEquals("original", cacheB.get(fullPath))
        assertEquals(1_000L, syncB.hostModTimes[fullPath])

        val lockName = FolderSyncLockNaming.pollLockNameFor(graphId)

        // ── Tick N: instance A wins the poll lock and applies a host-side change; instance B's
        // own tick N attempt (tryWithLock) is deterministically locked out — per Task 6.3.3a's own
        // guidance, A acquires the lock first in test setup rather than relying on real Web Locks'
        // actual contention timing (which would make the "B is locked out" branch flaky).
        val holderAcquired = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.Default) {
            WebLock.withLock(lockName) {
                // Apply A's change to the shared host state, and let A observe it, BEFORE
                // signalling the outer test — so by the time holderAcquired resolves, the change
                // is guaranteed to already be in place while the lock is still held.
                mutateSingleFileRoot(root, "changed-by-A", 2_000L)
                syncA.pollHostDirectoryOnce(root, opfsPath)
                holderAcquired.complete(Unit)
                releaseHolder.await()
            }
        }
        holderAcquired.await()

        // Drive B's timer loop through tick N while the holder still owns the lock — B's own
        // tryWithLock call inside startHostDirectoryPolling must return null this tick.
        syncB.startHostDirectoryPolling()
        scopeB.advanceTimeBy(10_000)
        scopeB.runCurrent()
        // Real Web Locks settlement is Promise-driven, not governed by scopeB's virtual clock —
        // give any pending real microtask a moment to actually resume B's coroutine before
        // asserting the "not yet converged" state below.
        withContext(Dispatchers.Default) { delay(150) }

        // (a) Immediately after tick N, B's own state must NOT yet reflect A's change — proving
        // convergence genuinely depends on B's own next tick running, not a coincidence of shared
        // virtual-time scheduling.
        assertEquals("original", cacheB.get(fullPath), "B must not observe A's change on a tick it was locked out of")
        assertEquals(1_000L, syncB.hostModTimes[fullPath], "B's own hostModTimes baseline must be untouched by A's change on a locked-out tick")

        releaseHolder.complete(Unit)
        holder.await()

        // ── Tick N+1: B's own next, now-uncontended, tick — at most one effectivePollIntervalMs()
        // later than tick N.
        scopeB.advanceTimeBy(10_000)
        scopeB.runCurrent()
        awaitCondition(timeoutMs = 2000) { cacheB.get(fullPath) == "changed-by-A" }

        // (b) By B's own next un-contended tick, B's state now matches A's post-change result.
        assertEquals("changed-by-A", cacheB.get(fullPath), "B must converge to A's change on its own next un-contended tick")
        assertEquals(2_000L, syncB.hostModTimes[fullPath], "B's hostModTimes baseline must be updated by its own next tick")

        syncB.stopHostDirectoryPolling()
        scopeA.cancel()
        scopeB.cancel()
    }
}
