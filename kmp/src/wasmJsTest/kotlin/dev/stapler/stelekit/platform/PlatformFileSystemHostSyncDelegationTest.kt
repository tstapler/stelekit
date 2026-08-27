// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object.
// makeWritableHostRoot/writableRoot* fixtures (Epic 4.5) live in HostDirectoryTestFixtures.kt,
// same package, no import needed.

// reconnectHostDirectory's granted branch fires runHostReconciliation via scope.launch (fire-
// and-forget, per its own doc comment) — which walks this handle via listOpfsEntries, i.e.
// `handle.values()`. A bare {queryPermission, requestPermission} object without `values()`
// makes that background walk throw "handle.values is not a function" from inside the launched
// coroutine. This fixture only cares about permission-delegation, so `values()` reports an
// empty directory (an empty JS iterator) — enough for the real reconciliation walk to no-op.
private fun fakeHandleWithGrantedPermission(): JsAny = js(
    """
    ({
        queryPermission: function(opts) { return Promise.resolve('granted'); },
        requestPermission: function(opts) { return Promise.resolve('granted'); },
        values: function() { return { next: function() { return Promise.resolve({ done: true, value: undefined }); } }; }
    })
    """,
)

/**
 * Task 2.2.2b: coverage for [PlatformFileSystem.hostDirectoryAccessState]'s one-line delegate to
 * `HostDirectorySync.hostAccessStateFlow.value` — the [dev.stapler.stelekit.platform.FileSystem]-
 * interface touch point commonMain callers use instead of downcasting to [PlatformFileSystem] to
 * reach `hostDirectorySync` directly.
 *
 * `writeFile`/`writeFileBytes`/`deleteFile` write-through delegation coverage (Story 4.3.1, Epic
 * 4.3) is implemented below, extending this class per
 * `project_plans/web-local-folder-livesync/implementation/validation.md`'s
 * `PlatformFileSystemHostSyncDelegationTest.kt` rows, rather than replacing it. Runs against the
 * real [PlatformFileSystem] actual (mirrors `PlatformFileSystemOpfsWriteDurabilityTest.kt`'s
 * precedent — no dependency-injection seam exists for `writeFile` itself, only for the
 * `HostDirectorySync` collaborator it composes), using `makeWritableHostRoot`
 * (`HostDirectoryTestFixtures.kt`) as a fake `hostDirHandle`.
 */
class PlatformFileSystemHostSyncDelegationTest {

    @Test
    fun hostDirectoryAccessState_should_DelegateToHostDirectorySyncFlowValue_When_Called() = runTest {
        val fs = PlatformFileSystem()
        val handle = fakeHandleWithGrantedPermission()
        fs.hostDirectorySync.lookupPersistedHandle = { handle to "/stelekit/g" }

        val resolved = fs.hostDirectorySync.reconnectHostDirectory("g")
        assertEquals(HostAccessState.Granted, resolved)

        val delegated = fs.hostDirectoryAccessState("/stelekit/g")

        assertEquals(fs.hostDirectorySync.hostAccessStateFlow.value, delegated)
        assertEquals(HostAccessState.Granted, delegated)
    }

    // ── Story 4.3.1 (Task 4.5.1d): writeFile/writeFileBytes/deleteFile write-through delegation ──

    private fun freshGraphId(): String = "it-hostsync-${Random.nextInt(0, Int.MAX_VALUE)}"

    private suspend fun awaitCondition(timeoutMs: Long = 2000, stepMs: Long = 10, block: () -> Boolean) {
        var waited = 0L
        while (!block() && waited < timeoutMs) {
            withContext(Dispatchers.Default) { delay(stepMs) }
            waited += stepMs
        }
    }

    @Test
    fun writeFile_should_ProduceFourIndependentEffects_When_HostDirHandleIsSet() = runTest {
        val graphId = freshGraphId()
        val graphPath = "/stelekit/$graphId"
        val fs = PlatformFileSystem()
        fs.preload(graphPath)
        val root = makeWritableHostRoot()
        fs.hostDirectorySync.hostDirHandle = root
        fs.hostDirectorySync.hostGraphOpfsPath = graphPath
        val path = "$graphPath/Foo.md"

        val result = fs.writeFile(path, "new content")

        assertTrue(result)
        // Effect 1: cache.
        assertEquals("new content", fs.readFile(path))
        // Effect 2: git dirtySet.
        assertTrue(fs.getDirtySnapshot().containsKey("Foo.md"))
        // Effect 3: OPFS mirror write scheduled (Task 1.7.1a's tracked Deferred).
        assertNotNull(fs.opfsWriteDeferredFor(path))
        // Effect 4: HostDirectorySync.hostWritePending — scheduleHostWriteThrough enqueues it
        // asynchronously inside its own scope.launch, so hostWritePending is empty both *before*
        // that launch has run and *after* the flush succeeds — checking "not in map" first is
        // racy and can spuriously pass at t=0 (see HostDirectorySyncWriteThroughTest's identical
        // fix). Anchor on the write-side-effect counter instead, which starts at 0 and cannot be
        // trivially satisfied before the real host write happens.
        awaitCondition { writableRootCreateWritableCallCount(root) >= 1 }
        assertTrue(writableRootCreateWritableCallCount(root) >= 1, "the host write must actually have been attempted")
        assertEquals("new content", writableRootGetContent(root, "Foo.md"))
    }

    @Test
    fun writeFile_should_LeaveHostWritePendingUntouched_When_HostDirHandleIsNull() = runTest {
        val graphId = freshGraphId()
        val graphPath = "/stelekit/$graphId"
        val fs = PlatformFileSystem()
        fs.preload(graphPath)
        // hostDirHandle deliberately left null — no live sync connected for this graph.
        val path = "$graphPath/Foo.md"

        val result = fs.writeFile(path, "new content")

        assertTrue(result)
        assertEquals("new content", fs.readFile(path))
        assertEquals(0, fs.hostDirectorySync.hostWritePending.size, "no host directory connected — queue must stay empty")
    }

    @Test
    fun deleteFile_should_RemoveHostEntry_When_HostDirHandleIsSetAndFileExistsOnHost() = runTest {
        val graphId = freshGraphId()
        val graphPath = "/stelekit/$graphId"
        val fs = PlatformFileSystem()
        fs.preload(graphPath)
        val root = makeWritableHostRoot()
        writableRootSetContent(root, "Old.md", "stale content")
        fs.hostDirectorySync.hostDirHandle = root
        fs.hostDirectorySync.hostGraphOpfsPath = graphPath
        val path = "$graphPath/Old.md"

        val result = fs.deleteFile(path)

        assertTrue(result)
        awaitCondition { !writableRootHasFile(root, "Old.md") }
        assertTrue(!writableRootHasFile(root, "Old.md"), "host-side file must be removed")
    }

    @Test
    fun applyRemoteContent_should_NeverCallScheduleHostWriteThrough_When_MergingRemoteGitContent() = runTest {
        val graphId = freshGraphId()
        val graphPath = "/stelekit/$graphId"
        val fs = PlatformFileSystem()
        fs.preload(graphPath)
        val root = makeWritableHostRoot()
        fs.hostDirectorySync.hostDirHandle = root
        fs.hostDirectorySync.hostGraphOpfsPath = graphPath
        val path = "$graphPath/Merged.md"

        val result = fs.applyRemoteContent(path, "remote content")

        assertTrue(result)
        assertEquals("remote content", fs.readFile(path))
        // Give any (incorrect) async delegation a chance to have run before asserting its absence.
        withContext(Dispatchers.Default) { delay(50) }
        assertEquals(0, fs.hostDirectorySync.hostWritePending.size, "applyRemoteContent must never write-through")
        assertEquals(0, writableRootCreateWritableCallCount(root))
    }
}
