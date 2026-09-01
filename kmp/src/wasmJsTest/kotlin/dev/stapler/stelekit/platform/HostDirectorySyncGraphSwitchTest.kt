// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.git.model.DirtyEntry
import dev.stapler.stelekit.git.model.DirtyOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

// js() calls must be top-level functions in Kotlin/Wasm — see HostDirectoryTestFixtures.kt for
// makeWritableHostRoot/writableRoot* accessors this file uses.

/**
 * Regression coverage for the two confirmed cross-graph data-corruption bugs an adversarial
 * review surfaced in `disconnectForGraphSwitch`/`flushHostWrite` (PR #293, round 2): switching
 * `PlatformFileSystem`'s active graph re-targets a single long-lived `HostDirectorySync` instance
 * at a different graph's directory, and neither the write-queue nor an already-suspended write
 * cleanly separated old-graph state from new-graph state.
 */
class HostDirectorySyncGraphSwitchTest {

    private fun freshOpfsPath(prefix: String): String = "/stelekit/$prefix-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun newSync(
        opfsPath: String,
        cacheAccess: FakeCacheAccess,
        scope: CoroutineScope,
        rootHandle: JsAny,
    ): HostDirectorySync {
        val graphId = opfsPath.substringAfterLast("/")
        val sync = HostDirectorySync(
            graphIdProvider = { graphId },
            cacheAccess = cacheAccess,
            scope = scope,
        )
        sync.hostDirHandle = rootHandle
        sync.hostGraphOpfsPath = opfsPath
        return sync
    }

    private suspend fun awaitCondition(timeoutMs: Long = 2000, stepMs: Long = 10, block: () -> Boolean) {
        var waited = 0L
        while (!block() && waited < timeoutMs) {
            withContext(Dispatchers.Default) { delay(stepMs) }
            waited += stepMs
        }
    }

    @Test
    fun disconnectForGraphSwitch_should_ClearRepoRelativeWriteQueues_When_SwitchingAwayFromAConnectedGraph() = runTest {
        val opfsPathA = freshOpfsPath("a")
        val rootA = makeWritableHostRoot()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(opfsPathA, FakeCacheAccess(), testScope, rootA)

        // Given: graph A has a write still queued (never reached the front of the flush loop).
        sync.hostWritePending["Foo.md"] = DirtyEntry(DirtyOp.WRITE, 0L)
        sync.hostWriteLatestPayload["Foo.md"] = HostWritePayload.Text("stale-A-content")
        sync.hostWriteDirtyDuringFlush.add("Foo.md")

        // When: the user switches to a different graph B (no host connection of its own).
        sync.disconnectForGraphSwitch()

        // Then: A's repo-relative-keyed write-queue state is gone — otherwise, once B's own poller
        // starts, retryStuckHostWrites() would iterate hostWritePending and write A's stale
        // hostWriteLatestPayload content into B's real host directory under the same relative path
        // ("Foo.md" easily collides across two unrelated graphs).
        assertFalse("Foo.md" in sync.hostWritePending)
        assertNull(sync.hostWriteLatestPayload["Foo.md"])
        assertFalse("Foo.md" in sync.hostWriteDirtyDuringFlush)
        assertEquals(0, sync.hostWritePendingCountFlow.value)

        testScope.cancel()
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
