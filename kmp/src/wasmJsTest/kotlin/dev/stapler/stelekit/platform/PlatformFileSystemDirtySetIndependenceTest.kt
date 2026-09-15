// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.git.model.DirtySetMarker
import dev.stapler.stelekit.git.model.gitApiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Epic 8.2 (Story 8.2.2, Task 8.2.2a): proves `web-git-writeback`'s `dirtySet`/
 * `.stele-dirty-set.json` marker is completely untouched by whether a host directory is connected
 * (`HostDirectorySync.hostDirHandle`) — the explicit "must not regress web-git-writeback's
 * dirty-file tracking" constraint from requirements.md, enforced here by a test rather than only a
 * code-review promise. Deliberately targets [PlatformFileSystem] directly (its own `dirtySet`
 * field, untouched by the Epic 1.6 [HostDirectorySync] extraction) — this test asserts the
 * extraction changed only where host-sync state lives, not `PlatformFileSystem`'s own pre-existing
 * git-write-back behavior.
 *
 * Runs against the real wasmJs `PlatformFileSystem` actual, mirroring
 * `PlatformFileSystemDirtyTrackingIntegrationTest.kt`'s real-OPFS-backed pattern — this source set
 * has no OPFS-write-interception seam that avoids a real backend (`opfsWriteFile` is a hardcoded
 * real-OPFS call with no injectable double), so the marker's actual on-disk bytes are captured the
 * same way that file already does: a fresh `PlatformFileSystem`/direct `opfsReadFileAtPath` read,
 * polled until the async marker write lands. Each instance gets its own fresh, randomly-suffixed
 * `graphId` (mirroring every other test in this source set — sharing one would let the second
 * instance's `preload()` restore the first instance's already-written marker, contaminating the
 * "two independent instances" comparison), so the marker comparison normalizes `graphId` alongside
 * [DirtySetMarker.checkpointedAtMillis]/`DirtyEntry.updatedAtMillis` — the latter two are real
 * `Clock.System.now()` values with no clock-injection seam on this actual. Every other field
 * (`version`, `baseSha`, `pendingCommit`, `dirtyFiles` keys/ops) is compared as-is.
 */
class PlatformFileSystemDirtySetIndependenceTest {

    private suspend fun awaitCondition(timeoutMs: Long = 2000, stepMs: Long = 25, block: () -> Boolean) {
        var waited = 0L
        while (!block() && waited < timeoutMs) {
            withContext(Dispatchers.Default) { delay(stepMs) }
            waited += stepMs
        }
    }

    /** Polls for the marker to land on disk, returning its raw JSON once present (or on timeout). */
    private suspend fun awaitMarkerJson(graphId: String, timeoutMs: Long = 3000, stepMs: Long = 50): String? {
        var waited = 0L
        var raw = opfsReadFileAtPath("/stelekit/$graphId/.stele-dirty-set.json")
        while (raw == null && waited < timeoutMs) {
            withContext(Dispatchers.Default) { delay(stepMs) }
            waited += stepMs
            raw = opfsReadFileAtPath("/stelekit/$graphId/.stele-dirty-set.json")
        }
        return raw
    }

    private fun freshGraphId(): String = "it-independence-${Random.nextInt(0, Int.MAX_VALUE)}"

    /** Strips instance-identity and wall-clock-derived fields — see this class's doc comment. */
    private fun DirtySetMarker.normalized(): DirtySetMarker = copy(
        graphId = "",
        checkpointedAtMillis = 0,
        dirtyFiles = dirtyFiles.mapValues { it.value.copy(updatedAtMillis = 0) },
    )

    @Test
    fun `writeFile with a connected host directory produces identical dirtySet and marker content to writeFile with none`() = runTest {
        val plainGraphId = freshGraphId()
        val hostedGraphId = freshGraphId()

        // Instance 1: no host directory ever connected — today's pre-existing behavior.
        val plainFs = PlatformFileSystem()
        plainFs.preload("/stelekit/$plainGraphId")
        plainFs.writeFile("/stelekit/$plainGraphId/Foo.md", "# Foo")
        awaitCondition { plainFs.getDirtySnapshot().containsKey("Foo.md") }
        val plainMarkerRaw = awaitMarkerJson(plainGraphId)

        // Instance 2: identical call, but with hostDirectorySync.hostDirHandle set (mocked writable
        // root) — this is what Task 4.3.1a's writeFile delegation additionally fires, and Epic 1.6
        // requires that it never reaches back into PlatformFileSystem's own dirtySet/marker state.
        val hostedFs = PlatformFileSystem()
        hostedFs.preload("/stelekit/$hostedGraphId")
        val hostRoot = makeWritableHostRoot()
        hostedFs.hostDirectorySync.hostDirHandle = hostRoot
        hostedFs.hostDirectorySync.hostGraphOpfsPath = "/stelekit/$hostedGraphId"
        hostedFs.writeFile("/stelekit/$hostedGraphId/Foo.md", "# Foo")
        awaitCondition { hostedFs.getDirtySnapshot().containsKey("Foo.md") }
        val hostedMarkerRaw = awaitMarkerJson(hostedGraphId)

        // getDirtySnapshot(): identical keys/ops (timestamp-normalized — see class doc comment).
        val plainSnapshot = plainFs.getDirtySnapshot().mapValues { it.value.copy(updatedAtMillis = 0) }
        val hostedSnapshot = hostedFs.getDirtySnapshot().mapValues { it.value.copy(updatedAtMillis = 0) }
        assertEquals(plainSnapshot, hostedSnapshot, "getDirtySnapshot() must be identical regardless of a connected host directory")
        assertEquals(1, plainFs.dirtyFileCountFlow.value)
        assertEquals(1, hostedFs.dirtyFileCountFlow.value)

        // .stele-dirty-set.json on-disk marker: identical shape once wall-clock fields are
        // normalized (no Clock-injection seam exists on this actual — see class doc comment).
        assertTrue(plainMarkerRaw != null, "expected plainFs's marker to land on disk")
        assertTrue(hostedMarkerRaw != null, "expected hostedFs's marker to land on disk")
        val plainMarker = gitApiJson.decodeFromString<DirtySetMarker>(plainMarkerRaw!!).normalized()
        val hostedMarker = gitApiJson.decodeFromString<DirtySetMarker>(hostedMarkerRaw!!).normalized()
        assertEquals(plainMarker, hostedMarker, "on-disk .stele-dirty-set.json marker must be identical regardless of a connected host directory")

        // The only observable difference: hostedFs actually pushed the write through to the host
        // directory (proves HostDirectorySync's write-through queue is the sole differing effect,
        // never PlatformFileSystem.dirtySet) — plainFs has no host root to compare against at all.
        awaitCondition { writableRootCreateWritableCallCount(hostRoot) >= 1 }
        assertEquals(1, writableRootCreateWritableCallCount(hostRoot))
        assertEquals("# Foo", writableRootGetContent(hostRoot, "Foo.md"))
    }
}
