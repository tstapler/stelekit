// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — see HostDirectoryTestFixtures.kt for
// makeWritableHostRoot/writableRoot* accessors this file uses.

/**
 * Epic 1.1 (Story 1.1.2): regression coverage for `HostDirectorySync` becoming a per-graph
 * `SessionLifecycle` — a graph switch now discards the whole instance via `close()` and constructs
 * a fresh one, rather than resetting one long-lived instance in place. This is the structural fix
 * for the two confirmed cross-graph data-corruption bugs an adversarial review originally surfaced
 * in `disconnectForGraphSwitch`/`flushHostWrite` (PR #293, round 2) — with no shared mutable state
 * left between two `HostDirectorySync` instances, neither the write-queue nor an already-suspended
 * write can reach across a switch to begin with.
 */
class HostDirectorySyncGraphSwitchTest {

    private fun freshOpfsPath(prefix: String): String = "/stelekit/$prefix-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun newSync(
        opfsPath: String,
        cacheAccess: FakeCacheAccess,
        scope: CoroutineScope,
        rootHandle: JsAny,
    ): HostDirectorySync = connectedSync(
        graphId = OpfsGraphSlug(opfsPath.substringAfterLast("/")),
        opfsPath = opfsPath,
        rootHandle = rootHandle,
        cacheAccess = cacheAccess,
        scope = scope,
    )

    private suspend fun awaitCondition(timeoutMs: Long = 2000, stepMs: Long = 10, block: () -> Boolean) {
        var waited = 0L
        while (!block() && waited < timeoutMs) {
            withContext(Dispatchers.Default) { delay(stepMs) }
            waited += stepMs
        }
    }

    @Test
    fun close_should_StopThePollerAndCancelTheScope_When_CalledOnAConnectedInstance() = runTest {
        val opfsPathA = freshOpfsPath("a")
        val rootA = makeWritableHostRoot()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPathA, FakeCacheAccess(), testScope, rootA)
        sync.startHostDirectoryPolling()

        sync.close()

        assertFalse(testScope.isActive, "close() must cancel this instance's own scope")
    }

    @Test
    fun close_should_LetAnInFlightHostWriteCompletionSettleHarmlessly_When_TheOpfsWriteWasAlreadyDispatchedBeforeClose() = runTest {
        // Task 1.1.2c: gate-based (never delay()) reproduction of "a browser-side OPFS write was
        // already dispatched before close() is called, and its completion callback settles
        // afterward" — proves flushHostWrite's existing entry-time handle/opfsPath snapshot still
        // resolves hostWriteCompletion correctly against journal-a's own state once the instance
        // has already been discarded, and never touches a subsequently-constructed journal-b
        // instance (there is no shared mutable state left for it to reach across instances).
        val opfsPathA = freshOpfsPath("journal-a")
        val gatedRoot = makeWritableEnumerableHostRoot()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val syncA = newSync(opfsPathA, FakeCacheAccess(), testScope, gatedRoot)

        val completionDeferred = syncA.scheduleHostWriteThrough("$opfsPathA/Foo.md", HostWritePayload.Text("A-content"))
        // Wait until flushHostWrite has captured handle/opfsPath at entry and is suspended on the
        // fixture's gate inside getFileHandle — simulating a dispatched-but-not-yet-settled write.
        awaitCondition { writableEnumerableRootAttemptCount(gatedRoot) >= 1 }
        assertFalse(completionDeferred.isCompleted, "the write must still be in flight at this point")

        // When: close() is called on syncA (simulating a graph switch to journal-b) while the
        // OPFS write is still suspended, then the gate is released, letting the write settle.
        syncA.close()
        val opfsPathB = freshOpfsPath("journal-b")
        val syncB = connectedSync(
            graphId = OpfsGraphSlug(opfsPathB.substringAfterLast("/")),
            opfsPath = opfsPathB,
            rootHandle = makeWritableHostRoot(),
            cacheAccess = FakeCacheAccess(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        openWritableEnumerableRootGate(gatedRoot)

        // Then: the flush settles against syncA's own entry-captured state — never throwing into
        // an unhandled context — and syncB (constructed after close()) is never touched by it.
        awaitCondition { completionDeferred.isCompleted }
        assertTrue(completionDeferred.isCompleted)
        assertEquals("A-content".hashCode(), syncA.hostContentHashes["$opfsPathA/Foo.md"])
        assertNull(syncB.hostContentHashes["$opfsPathA/Foo.md"])

        testScope.cancel()
        syncB.close()
    }

    @Test
    fun flushHostWrite_should_TagBookkeepingUnderTheGraphActiveWhenScheduled_When_HostGraphOpfsPathChangesWhileTheFlushIsSuspended() = runTest {
        // Deterministic reproduction (via makeWritableEnumerableHostRoot's gate — see
        // HostDirectorySyncWriteThroughTest's suppression test for the same pattern) of the window
        // an adversarial review found: flushHostWrite's entry snapshots `handle`, but the OLD code
        // re-read the live hostGraphOpfsPath field *after* that entry — including after this
        // getFileHandle call's own suspension point below. Gating getFileHandle lets this test pin
        // the flush exactly inside that window before mutating hostGraphOpfsPath, instead of
        // racing real timers.
        val opfsPathA = freshOpfsPath("a")
        val opfsPathB = freshOpfsPath("b")
        val root = makeWritableEnumerableHostRoot()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPathA, FakeCacheAccess(), testScope, root)

        sync.scheduleHostWriteThrough("$opfsPathA/Foo.md", HostWritePayload.Text("A-content"))
        // Waits for flushHostWrite to have already: captured `handle`/`opfsPath` at entry, resolved
        // the permission check, acquired the write lock, and reached getFileHandle — which is now
        // blocked on the fixture's gate. hostGraphOpfsPath is still A at this exact point.
        awaitCondition { writableEnumerableRootAttemptCount(root) >= 1 }

        // Simulates PlatformFileSystem.switchActiveGraph's disconnect-then-reconnect flipping the
        // live field to a different graph while this flush is still suspended mid-write.
        sync.hostGraphOpfsPath = opfsPathB

        openWritableEnumerableRootGate(root)
        awaitCondition { "Foo.md" !in sync.hostWritePending }

        // Then: the write's bookkeeping must stay tagged under A — the graph active when this
        // flush actually started — never split onto B's path just because hostGraphOpfsPath moved
        // on underneath it while suspended.
        assertEquals("A-content".hashCode(), sync.hostContentHashes["$opfsPathA/Foo.md"])
        assertNull(sync.hostContentHashes["$opfsPathB/Foo.md"])

        testScope.cancel()
    }
}
