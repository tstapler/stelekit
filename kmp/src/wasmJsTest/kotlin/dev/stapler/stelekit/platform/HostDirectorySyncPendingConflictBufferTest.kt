// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for the host-conflict startup-ordering race: conflicts detected by
 * [HostDirectorySync] before `App.kt` wires the real [HostDirectorySync.onHostConflict] callback
 * (via `PlatformFileSystem.setOnHostConflict`) used to be silently dropped. The fix buffers them
 * into `pendingHostConflicts` and replays them once [HostDirectorySync.flushPendingHostConflicts]
 * is called with the real callback.
 */
class HostDirectorySyncPendingConflictBufferTest {

    private class FakeCacheAccess : HostDirectorySync.CacheAccess {
        override fun get(path: String): String? = null
        override fun set(path: String, content: String) {}
        override fun remove(path: String) {}
        override fun getBytes(path: String): ByteArray? = null
        override fun setBytes(path: String, data: ByteArray) {}
        override fun removeBytes(path: String) {}
        override fun keysUnder(opfsPath: String): Set<String> = emptySet()
        override fun writeOpfsMirror(path: String, content: String) {}
        override fun writeOpfsMirrorBytes(path: String, data: ByteArray) {}
        override fun opfsWriteDeferredFor(path: String): Deferred<Unit>? = null
    }

    private fun newSync(scope: CoroutineScope) = HostDirectorySync(
        graphIdProvider = { "g" },
        cacheAccess = FakeCacheAccess(),
        scope = scope,
    )

    @Test
    fun onHostConflict_should_BufferConflicts_When_NoRealCallbackWiredYet() = runTest {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(testScope)

        assertEquals(0, sync.pendingHostConflictCount)

        sync.onHostConflict(GraphRootedPath.of("pages/Foo.md", null), "host content")
        assertEquals(1, sync.pendingHostConflictCount)

        sync.onHostConflict(GraphRootedPath.of("pages/Bar.md", null), "other host content")
        assertEquals(2, sync.pendingHostConflictCount)

        testScope.cancel()
    }

    @Test
    fun flushPendingHostConflicts_should_ReplayBufferedConflictsInOrderThenClear_When_RealCallbackWired() = runTest {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(testScope)

        sync.onHostConflict(GraphRootedPath.of("pages/Foo.md", null), "foo content")
        sync.onHostConflict(GraphRootedPath.of("pages/Bar.md", null), "bar content")
        assertEquals(2, sync.pendingHostConflictCount)

        val replayed = mutableListOf<Pair<String, String>>()
        sync.flushPendingHostConflicts { path, hostContent -> replayed += path.value to hostContent }

        assertEquals(listOf("pages/Foo.md" to "foo content", "pages/Bar.md" to "bar content"), replayed)
        assertEquals(0, sync.pendingHostConflictCount)

        testScope.cancel()
    }

    @Test
    fun flushPendingHostConflicts_should_BeANoOp_When_BufferIsEmpty() = runTest {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(testScope)

        var callbackInvoked = false
        sync.flushPendingHostConflicts { _, _ -> callbackInvoked = true }

        assertTrue(!callbackInvoked)
        assertEquals(0, sync.pendingHostConflictCount)

        testScope.cancel()
    }

    @Test
    fun onHostConflict_should_NotBufferAnymore_When_RealCallbackAlreadyAssigned() = runTest {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(testScope)

        val delivered = mutableListOf<Pair<String, String>>()
        sync.onHostConflict = { path, hostContent -> delivered += path.value to hostContent }

        sync.onHostConflict(GraphRootedPath.of("pages/Foo.md", null), "foo content")

        assertEquals(listOf("pages/Foo.md" to "foo content"), delivered)
        assertEquals(0, sync.pendingHostConflictCount)

        testScope.cancel()
    }
}
