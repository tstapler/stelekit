// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.git.model.HostHandleEnvelope
import dev.stapler.stelekit.git.model.gitApiJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object.

/** Mirrors [HostDirectoryInteropTest.kt]'s `fakeStorableHandle` — a plain structured-clonable stand-in. */
private fun fakeDirHandle(name: String): JsAny = js("({ kind: 'directory', name: name })")

/** Mirrors [HostDirectoryInterop.kt]'s `jsStringValue` idiom for converting an opaque JsAny back to a Kotlin String. */
private fun jsAnyToKotlinString(v: JsAny): String = js("String(v)")

/**
 * Temporarily replaces `indexedDB.open` with a function that throws synchronously, so any
 * `idbOpenHandleDb()` call made while stubbed rejects — used to exercise
 * `HostDirectorySync.persistHostHandle`'s failure-tolerant catch path without needing to inject a
 * fake collaborator (there is none; `persistHostHandle` talks to the real global `indexedDB`
 * directly, matching `HostDirectoryInterop.kt`'s hand-rolled `js()` idiom). Returns whatever was
 * there so it can be restored.
 */
private fun stubIndexedDbOpenToThrow(): JsAny? = js(
    """
    (function() {
        var original = self.indexedDB;
        var throwing = { open: function() { throw new Error('boom'); } };
        try {
            Object.defineProperty(self, 'indexedDB', { value: throwing, configurable: true, writable: true });
        } catch (e) {
            self.indexedDB = throwing;
        }
        return original || null;
    })()
    """,
)

private fun restoreIndexedDb(original: JsAny?): Unit = js(
    """
    (function() {
        try {
            Object.defineProperty(self, 'indexedDB', { value: original, configurable: true, writable: true });
        } catch (e) {
            self.indexedDB = original;
        }
    })()
    """,
)

/**
 * Epic 2.1 (Story 2.1.1): coverage for [HostDirectorySync.attachFreshHandle]/`persistHostHandle`
 * per `project_plans/web-local-folder-livesync/implementation/validation.md`'s three
 * `HostDirectorySyncHandleRetentionTest.kt` rows. Runs in the real (headless Chrome,
 * `wasmJsBrowserTest`) browser test environment this codebase uses for `wasmJsTest`
 * (`WebLockTest.kt`/`HostDirectoryInteropTest.kt` precedent).
 *
 * `hostDirHandle`/`hostGraphOpfsPath` are `internal` (not `private`) on [HostDirectorySync]
 * specifically so this friend-source-set test can assert on them directly, per the acceptance
 * criteria in plan.md's Story 2.1.1 ("`hostDirectorySync.hostDirHandle` is set to...").
 */
class HostDirectorySyncHandleRetentionTest {

    /** No-op fake — Epic 2.1's `attachFreshHandle`/`persistHostHandle` never touch `CacheAccess`. */
    private class NoOpCacheAccess : HostDirectorySync.CacheAccess {
        override fun get(path: String): String? = null
        override fun set(path: String, content: String) = Unit
        override fun remove(path: String) = Unit
        override fun getBytes(path: String): ByteArray? = null
        override fun setBytes(path: String, data: ByteArray) = Unit
        override fun removeBytes(path: String) = Unit
        override fun keysUnder(opfsPath: String): Set<String> = emptySet()
        override fun writeOpfsMirror(path: String, content: String) = Unit
        override fun writeOpfsMirrorBytes(path: String, data: ByteArray) = Unit
        override fun opfsWriteDeferredFor(path: String): Deferred<Unit>? = null
    }

    private fun newSync(graphId: String, scope: CoroutineScope): HostDirectorySync = HostDirectorySync(
        graphIdProvider = { graphId },
        cacheAccess = NoOpCacheAccess(),
        scope = scope,
    )

    @Test
    fun attachFreshHandle_should_SetHostDirHandleAndOpfsPath_When_PickDirectoryAsyncSucceeds() = runTest {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(graphId = "a1b2c3d4", scope = testScope)
        val dirHandle = fakeDirHandle("my-notes")
        val opfsPath = "/stelekit/my-notes"

        sync.attachFreshHandle(dirHandle, opfsPath)

        assertNotNull(sync.hostDirHandle)
        assertEquals(opfsPath, sync.hostGraphOpfsPath)
        testScope.cancel()
    }

    @Test
    fun attachFreshHandle_should_LeaveHostDirHandleNull_When_PersistHostHandleThrows() = runTest {
        // Test name is verbatim from validation.md for traceability. The scenario column there
        // ("IndexedDB put throws — pick itself must not fail, handle stays attached in-memory but
        // persistence failure is logged, not propagated") is the actual acceptance contract this
        // test verifies: attachFreshHandle must neither throw nor lose the in-memory handle when
        // IndexedDB persistence fails underneath it.
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync(graphId = "err-graph", scope = testScope)
        val dirHandle = fakeDirHandle("my-notes")
        val opfsPath = "/stelekit/my-notes"

        val original = stubIndexedDbOpenToThrow()
        try {
            // Must not throw — persistHostHandle's failure is caught and logged, not propagated.
            sync.attachFreshHandle(dirHandle, opfsPath)
        } finally {
            restoreIndexedDb(original)
        }

        assertNotNull(sync.hostDirHandle)
        assertEquals(opfsPath, sync.hostGraphOpfsPath)
        testScope.cancel()
    }

    @Test
    fun persistHostHandle_should_StoreHostHandleEnvelopeKeyedByGraphId_When_AttachFreshHandleCompletes() = runTest {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val graphId = "live-${Random.nextInt(0, Int.MAX_VALUE)}"
        val sync = newSync(graphId = graphId, scope = testScope)
        val dirHandle = fakeDirHandle("my-notes")
        val opfsPath = "/stelekit/my-notes"

        sync.attachFreshHandle(dirHandle, opfsPath)

        // Independently verified against the real IndexedDB global, via a fresh connection —
        // mirroring a new tab/session reading back what this call persisted.
        val readDb = idbOpenHandleDb()
        val stored = idbGetHandle(readDb, graphId)

        assertNotNull(stored)
        val decoded = gitApiJson.decodeFromString<HostHandleEnvelope>(jsAnyToKotlinString(stored))
        assertEquals(graphId, decoded.graphId)
        assertEquals("my-notes", decoded.dirName)
        testScope.cancel()
    }
}
