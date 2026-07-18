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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Epic 1.6 (Task 1.6.1c): regression guard for architecture-review.md Blocker 1 — proves
 * [HostDirectorySync] is constructible and operable using *only* an injected
 * [HostDirectorySync.CacheAccess] fake and a [CoroutineScope], with no real [PlatformFileSystem]
 * instance involved anywhere. If a future change makes [HostDirectorySync] require a live
 * `PlatformFileSystem` reference (a hidden dependency creeping back in), this test's fake-only
 * construction fails to compile or throws at runtime.
 *
 * See `project_plans/web-local-folder-livesync/implementation/validation.md`'s
 * `hostDirectorySync_should_ConstructAndOperateStandalone_When_GivenOnlyAFakeCacheAccessAndNoPlatformFileSystem`
 * row.
 */
class HostDirectorySyncConstructionTest {

    /** Minimal in-memory fake — no OPFS, no browser APIs, no [PlatformFileSystem]. */
    private class FakeCacheAccess : HostDirectorySync.CacheAccess {
        val textStore = mutableMapOf<String, String>()
        val bytesStore = mutableMapOf<String, ByteArray>()
        var mirrorWriteCount = 0
        var mirrorBytesWriteCount = 0
        private val deferredStore = mutableMapOf<String, Deferred<Unit>>()

        override fun get(path: String): String? = textStore[path]
        override fun set(path: String, content: String) {
            textStore[path] = content
        }
        override fun remove(path: String) {
            textStore.remove(path)
        }
        override fun getBytes(path: String): ByteArray? = bytesStore[path]
        override fun setBytes(path: String, data: ByteArray) {
            bytesStore[path] = data
        }
        override fun removeBytes(path: String) {
            bytesStore.remove(path)
        }
        override fun keysUnder(opfsPath: String): Set<String> =
            (textStore.keys + bytesStore.keys).filter { it.startsWith("$opfsPath/") }.toSet()
        override fun writeOpfsMirror(path: String, content: String) {
            mirrorWriteCount++
        }
        override fun writeOpfsMirrorBytes(path: String, data: ByteArray) {
            mirrorBytesWriteCount++
        }
        override fun opfsWriteDeferredFor(path: String): Deferred<Unit>? = deferredStore[path]
    }

    @Test
    fun hostDirectorySync_should_ConstructAndOperateStandalone_When_GivenOnlyAFakeCacheAccessAndNoPlatformFileSystem() = runTest {
        val fakeCacheAccess = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Given: constructed directly, no PlatformFileSystem instance involved anywhere.
        val sync = HostDirectorySync(
            graphId = "g",
            cacheAccess = fakeCacheAccess,
            scope = testScope,
        )

        // Then: construction itself succeeds standalone.
        assertNotNull(sync)

        // And: the injected CacheAccess seam it was given operates correctly on its own — proving
        // HostDirectorySync's only channel to "cache-like" state is this constructor-injected
        // interface, not a hidden PlatformFileSystem reference.
        fakeCacheAccess.set("/stelekit/g/pages/Foo.md", "# Foo")
        assertEquals("# Foo", fakeCacheAccess.get("/stelekit/g/pages/Foo.md"))
        fakeCacheAccess.remove("/stelekit/g/pages/Foo.md")
        assertNull(fakeCacheAccess.get("/stelekit/g/pages/Foo.md"))

        fakeCacheAccess.setBytes("/stelekit/g/pages/Secret.md.stek", byteArrayOf(1, 2, 3))
        assertTrue(fakeCacheAccess.getBytes("/stelekit/g/pages/Secret.md.stek").contentEquals(byteArrayOf(1, 2, 3)))

        fakeCacheAccess.set("/stelekit/g/pages/A.md", "a")
        fakeCacheAccess.set("/stelekit/g/pages/B.md", "b")
        fakeCacheAccess.set("/stelekit/g/other/C.md", "c")
        assertEquals(
            setOf("/stelekit/g/pages/A.md", "/stelekit/g/pages/B.md"),
            fakeCacheAccess.keysUnder("/stelekit/g/pages"),
        )

        fakeCacheAccess.writeOpfsMirror("/stelekit/g/pages/A.md", "a")
        fakeCacheAccess.writeOpfsMirrorBytes("/stelekit/g/pages/Secret.md.stek", byteArrayOf(1, 2, 3))
        assertEquals(1, fakeCacheAccess.mirrorWriteCount)
        assertEquals(1, fakeCacheAccess.mirrorBytesWriteCount)

        assertNull(fakeCacheAccess.opfsWriteDeferredFor("/stelekit/g/pages/Unwritten.md"))

        testScope.cancel()
    }
}
