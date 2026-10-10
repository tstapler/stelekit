// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Epic 2.1 (web-host-sync-session-lifecycle, Task 2.1.2f): coverage for
 * [PlatformFileSystem.switchActiveGraph] now routing through
 * [dev.stapler.stelekit.coroutines.GraphScopedSession] instead of the old hand-rolled
 * `close()`-then-reconstruct sequence. Runs against the real [PlatformFileSystem] actual —
 * mirrors `PlatformFileSystemHostSyncDelegationTest.kt`'s precedent (no dependency-injection seam
 * exists for this class).
 */
class PlatformFileSystemSwitchActiveGraphTest {

    private fun freshGraphId(prefix: String): String = "$prefix-${Random.nextInt(0, Int.MAX_VALUE)}"

    @Test
    fun switchActiveGraph_should_IsolateGitWriteStateFromThePreviousGraph_When_SwitchingToADifferentGraph() = runTest {
        val fs = PlatformFileSystem()
        val graphAId = freshGraphId("switch-a")
        val graphBId = freshGraphId("switch-b")
        val graphAPath = "/stelekit/$graphAId"
        val graphBPath = "/stelekit/$graphBId"

        fs.preload(graphAPath)
        fs.writeFile("$graphAPath/Foo.md", "graph A content")
        assertTrue(fs.getDirtySnapshot().containsKey("Foo.md"), "graph A's own write must be dirty-tracked")

        fs.switchActiveGraph(graphBPath)

        assertEquals(graphBId, fs.currentGraphId(), "switchActiveGraph must re-point currentGraphId at the new graph")
        assertTrue(
            fs.getDirtySnapshot().isEmpty(),
            "graph B's GitWriteState must be a fresh bundle, never graph A's leftover dirty set",
        )
    }

    @Test
    fun switchActiveGraph_should_ConstructExactlyOneSessionForTheSameGraph_When_CalledConcurrentlyAndRapidly() = runTest {
        val fs = PlatformFileSystem()
        val bootGraphPath = "/stelekit/${freshGraphId("switch-boot")}"
        fs.preload(bootGraphPath)

        val targetGraphPath = "/stelekit/${freshGraphId("switch-target")}"

        // Two concurrent calls for the identical target graph — GraphScopedSession.switchTo's own
        // idempotency (either joins the in-flight construction or returns the already-active
        // instance) must guarantee exactly one HostDirectorySync is ever live for this graph, never
        // two racing instances.
        val results = listOf(
            async { fs.switchActiveGraph(targetGraphPath); fs.hostDirectorySync },
            async { fs.switchActiveGraph(targetGraphPath); fs.hostDirectorySync },
        ).awaitAll()

        assertTrue(results[0] === results[1], "a rapid double-switch to the same graph must never construct two live sessions")
        assertEquals(targetGraphPath.substringAfterLast("/"), fs.currentGraphId())
    }
}
