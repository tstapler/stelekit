// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object.

private fun fakeStorableHandle(): JsAny = js("({ kind: 'directory', name: 'fake' })")

private fun fakeHandleWithPermissionResult(result: String): JsAny = js(
    """
    ({
        queryPermission: function(opts) { return Promise.resolve(result); },
        requestPermission: function(opts) { return Promise.resolve(result); }
    })
    """,
)

private fun fakeHandleThatThrowsOnRequestPermission(): JsAny = js(
    """
    ({
        queryPermission: function(opts) { return Promise.reject(new Error('boom')); },
        requestPermission: function(opts) { return Promise.reject(new Error('boom')); }
    })
    """,
)

/** Temporarily removes `self.FileSystemObserver`; returns whatever was there so it can be restored. */
private fun stubFileSystemObserverAbsent(): JsAny? = js(
    """
    (function() {
        var original = self.FileSystemObserver;
        self.FileSystemObserver = undefined;
        return original || null;
    })()
    """,
)

private fun restoreFileSystemObserver(original: JsAny?): Unit = js("self.FileSystemObserver = original")

/** Temporarily removes `navigator.storage.persist`; returns whatever was there so it can be restored. */
private fun stubStoragePersistAbsent(): JsAny? = js(
    """
    (function() {
        var original = navigator.storage.persist;
        navigator.storage.persist = undefined;
        return original || null;
    })()
    """,
)

private fun restoreStoragePersist(original: JsAny?): Unit = js("navigator.storage.persist = original")

/**
 * Epic 1.5: unit-mocked coverage for `HostDirectoryInterop.kt`'s browser interop primitives.
 * Runs in the real (headless Chrome, `wasmJsBrowserTest`) browser test environment this codebase
 * uses for `wasmJsTest` (see `WebLockTest.kt`/`PlatformFileSystemDirtyTrackingIntegrationTest.kt`
 * for precedent), but keeps each test fast/isolated by exercising fake/stub browser objects
 * (fake permission-result handles, a temporarily-removed `FileSystemObserver` global, a
 * temporarily-removed `navigator.storage.persist`) rather than driving full native picker/gesture
 * flows. The one exception is IndexedDB open/put/get, which is deterministic and side-effect-safe
 * enough to exercise directly against the real `indexedDB` global here — the dedicated
 * cross-session round-trip lives in `HostDirectoryInteropIndexedDbLiveTest.kt`.
 */
class HostDirectoryInteropTest {

    // --- IndexedDB open/put/get (Story 1.5.1) -----------------------------------------------

    @Test
    fun idbOpenHandleDb_should_CreateDatabaseAndObjectStore_When_NoStelekitHostHandlesDbExists() = runTest {
        val db = idbOpenHandleDb()
        assertNotNull(db)

        // Object store creation is proven indirectly: a put/get round trip against the 'handles'
        // store must succeed without throwing — an absent store would reject with NotFoundError.
        val key = "smoke-${Random.nextInt(0, Int.MAX_VALUE)}"
        idbPutHandle(db, key, fakeStorableHandle())
        val result = idbGetHandle(db, key)
        assertNotNull(result)
    }

    @Test
    fun idbGetHandle_should_ReturnNull_When_KeyNotFound() = runTest {
        val db = idbOpenHandleDb()
        val missingKey = "missing-${Random.nextInt(0, Int.MAX_VALUE)}"

        val result = idbGetHandle(db, missingKey)

        assertEquals(null, result)
    }

    // --- Permission query/request (Story 1.5.3) ---------------------------------------------

    @Test
    fun queryHandlePermission_should_ReturnPrompt_When_HandleFreshlyRehydratedFromIndexedDb() = runTest {
        val handle = fakeHandleWithPermissionResult("prompt")

        val result = queryHandlePermission(handle)

        assertEquals("prompt", result)
    }

    @Test
    fun requestHandlePermission_should_ReturnDenied_When_UnderlyingCallThrows() = runTest {
        val handle = fakeHandleThatThrowsOnRequestPermission()

        val result = requestHandlePermission(handle)

        assertEquals("denied", result)
    }

    // --- FileSystemObserver (Story 1.5.4) ----------------------------------------------------

    @Test
    fun fileSystemObserverSupported_should_ReturnTrue_When_RunningOnChrome133OrNewer() {
        // The headless Chrome target this suite runs against is >= 133 (per
        // research/stack.md's "shipped, not experimental" verification), so the real global is
        // expected to already be present without any stubbing.
        assertTrue(fileSystemObserverSupported())
    }

    @Test
    fun fileSystemObserverSupported_should_ReturnFalse_When_ConstructorNotPresentOnSelf() {
        val original = stubFileSystemObserverAbsent()
        try {
            assertFalse(fileSystemObserverSupported())
        } finally {
            restoreFileSystemObserver(original)
        }
    }

    // --- navigator.storage.persist() (Story 1.5.6) -------------------------------------------

    @Test
    fun requestStoragePersistence_should_ReturnGrantResult_When_StorageApiSupported() = runTest {
        // Real browser Storage API — the result reflects the actual grant decision (true or
        // false in a headless test profile), the contract under test is that it resolves at all
        // and never throws.
        val result = requestStoragePersistence()

        assertTrue(result == true || result == false)
    }

    @Test
    fun requestStoragePersistence_should_ReturnFalse_When_NavigatorStoragePersistNotAFunction() = runTest {
        val original = stubStoragePersistAbsent()
        try {
            val result = requestStoragePersistence()
            assertEquals(false, result)
        } finally {
            restoreStoragePersist(original)
        }
    }
}
