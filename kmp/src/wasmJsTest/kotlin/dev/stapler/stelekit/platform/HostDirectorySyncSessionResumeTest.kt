// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object,
// and (unlike Kotlin/JS) cannot close over a surrounding function's local variables — only their
// own parameters. Call-count assertions therefore route through a JS global, mirroring
// `Main.kt`'s `setShouldWarnMirror`/`window.__stelekit_should_warn` idiom, reset before each use.

/** Mirrors [HostDirectoryInteropTest.kt]'s private `fakeHandleWithPermissionResult` — a fake handle
 * whose `queryPermission`/`requestPermission` both resolve to [result]. */
private fun fakeHandleWithPermissionResult(result: String): JsAny = js(
    """
    ({
        queryPermission: function(opts) { return Promise.resolve(result); },
        requestPermission: function(opts) { return Promise.resolve(result); }
    })
    """,
)

private fun resetRequestPermissionCallCount(): Unit = js("window.__stelekit_test_request_permission_calls = 0")
private fun requestPermissionCallCount(): Int = js("(window.__stelekit_test_request_permission_calls | 0)")

/** `queryPermission` always resolves `"granted"`; `requestPermission` also resolves `"granted"` but
 * increments the shared JS-global call counter — used to prove silent resume never calls it. */
private fun fakeHandleGrantedCountingRequestPermission(): JsAny = js(
    """
    ({
        queryPermission: function(opts) { return Promise.resolve('granted'); },
        requestPermission: function(opts) {
            window.__stelekit_test_request_permission_calls = (window.__stelekit_test_request_permission_calls | 0) + 1;
            return Promise.resolve('granted');
        }
    })
    """,
)

/** `queryPermission` resolves `"prompt"`; `requestPermission` resolves `"denied"` and increments
 * the shared JS-global call counter — used to prove exactly one prompt is shown, no retry loop. */
private fun fakeHandlePromptThenCountingDeniedRequestPermission(): JsAny = js(
    """
    ({
        queryPermission: function(opts) { return Promise.resolve('prompt'); },
        requestPermission: function(opts) {
            window.__stelekit_test_request_permission_calls = (window.__stelekit_test_request_permission_calls | 0) + 1;
            return Promise.resolve('denied');
        }
    })
    """,
)

/** Temporarily replaces `navigator.storage.persist` with a counting stub; returns whatever was
 * there so it can be restored. Call count is read via [storagePersistCallCount]. */
private fun stubStoragePersistCounting(): JsAny? = js(
    """
    (function() {
        var original = navigator.storage.persist;
        window.__stelekit_test_persist_calls = 0;
        navigator.storage.persist = function() {
            window.__stelekit_test_persist_calls = (window.__stelekit_test_persist_calls | 0) + 1;
            return Promise.resolve(true);
        };
        return original || null;
    })()
    """,
)

private fun storagePersistCallCount(): Int = js("(window.__stelekit_test_persist_calls | 0)")

/** Stubs `navigator.storage.persist` to return a Promise that never resolves — proves a caller
 * that doesn't await it (fire-and-forget) is never blocked by it. */
private fun stubStoragePersistToHangForever(): JsAny? = js(
    """
    (function() {
        var original = navigator.storage.persist;
        navigator.storage.persist = function() { return new Promise(function() {}); };
        return original || null;
    })()
    """,
)

private fun restoreStoragePersist(original: JsAny?): Unit = js("navigator.storage.persist = original")

// ── Local showDirectoryPicker stubbing for connectHostDirectory (mirrors
// HostDirectorySyncReconciliationTest.kt's idiom, duplicated per-file per this suite's convention) ─

private fun stubShowDirectoryPickerToResolveForSessionResumeTest(handle: JsAny): JsAny? = js(
    """
    (function() {
        var original = window.showDirectoryPicker;
        window.showDirectoryPicker = function() { return Promise.resolve(handle); };
        return original || null;
    })()
    """,
)

private fun restoreShowDirectoryPickerForSessionResumeTest(original: JsAny?): Unit = js(
    """
    (function() { window.showDirectoryPicker = original; })()
    """,
)

// emptyRootDir() (an empty fake FileSystemDirectoryHandle) is shared from HostDirectoryTestFixtures.kt.

/**
 * Epic 2.5 (Story 2.5.2): coverage for `HostDirectorySync.reconnectHostDirectory`'s dispatch
 * branches (Story 2.2.1's non-reconciliation-focused half — the reconciliation-parity and
 * non-blocking-launch assertions live in `HostDirectorySyncReconciliationTest.kt`, per that file's
 * "Deferred tests" doc comment, which this dispatch also fills in), `requestHostDirectoryAccess`'s
 * one-click resume (Story 2.2.2), and `storage.persist()`'s fire-and-forget wiring (Story 2.4.1).
 *
 * Every test overrides [HostDirectorySync.lookupPersistedHandle] directly instead of routing a
 * fake handle through a real IndexedDB round trip — see that field's doc comment for why (fake
 * handle objects here carry function-valued `queryPermission`/`requestPermission` own properties,
 * which fail IndexedDB's structured clone algorithm; real `FileSystemDirectoryHandle` instances do
 * not have this problem, so this is purely a test-double limitation, not a production concern).
 * `idbGetHandle`/`idbPutHandle`/`persistHostHandle` round-trip coverage itself lives in
 * `HostDirectorySyncHandleRetentionTest.kt`/`HostDirectoryInteropTest.kt`.
 */
class HostDirectorySyncSessionResumeTest {

    private fun newSync(
        graphId: String,
        cacheAccess: FakeCacheAccess,
        scope: CoroutineScope,
    ): HostDirectorySync = HostDirectorySync(
        graphIdProvider = { graphId },
        cacheAccess = cacheAccess,
        scope = scope,
    )

    // ── reconnectHostDirectory (Story 2.2.1) ────────────────────────────────────────────────────

    @Test
    fun reconnectHostDirectory_should_ResolveNotApplicable_When_NoHandlePersistedInIndexedDb() = runTest {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("no-handle-graph", FakeCacheAccess(), testScope)
        sync.lookupPersistedHandle = { null }

        val state = sync.reconnectHostDirectory("no-handle-graph")

        assertEquals(HostAccessState.NotApplicable, state)
        assertEquals(HostAccessState.NotApplicable, sync.hostAccessStateFlow.value)
        assertNull(sync.hostDirHandle)
        testScope.cancel()
    }

    @Test
    fun reconnectHostDirectory_should_ResolvePromptNeeded_When_QueryHandlePermissionReturnsPrompt() = runTest {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", FakeCacheAccess(), testScope)
        val handle = fakeHandleWithPermissionResult("prompt")
        sync.lookupPersistedHandle = { handle to "/stelekit/g" }

        val state = sync.reconnectHostDirectory("g")

        assertEquals(HostAccessState.PromptNeeded, state)
        assertEquals(HostAccessState.PromptNeeded, sync.hostAccessStateFlow.value)
        // No handle set yet — nothing to reconcile against (Story 2.2.1's AC).
        assertNull(sync.hostDirHandle)
        testScope.cancel()
    }

    @Test
    fun reconnectHostDirectory_should_ResolveDenied_When_QueryHandlePermissionReturnsDenied() = runTest {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", FakeCacheAccess(), testScope)
        val handle = fakeHandleWithPermissionResult("denied")
        sync.lookupPersistedHandle = { handle to "/stelekit/g" }

        val state = sync.reconnectHostDirectory("g")

        assertEquals(HostAccessState.Denied, state)
        assertEquals(HostAccessState.Denied, sync.hostAccessStateFlow.value)
        assertNull(sync.hostDirHandle)
        testScope.cancel()
    }

    @Test
    fun reconnectHostDirectory_should_ResolveGrantedWithZeroPrompts_When_QueryHandlePermissionReturnsGranted() = runTest {
        resetRequestPermissionCallCount()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", FakeCacheAccess(), testScope)
        val handle = fakeHandleGrantedCountingRequestPermission()
        sync.lookupPersistedHandle = { handle to "/stelekit/g" }

        val state = sync.reconnectHostDirectory("g")

        assertEquals(HostAccessState.Granted, state)
        assertEquals(HostAccessState.Granted, sync.hostAccessStateFlow.value)
        assertNotNull(sync.hostDirHandle)
        assertEquals("/stelekit/g", sync.hostGraphOpfsPath)
        // Silent resume: queryPermission only, never the prompt-showing requestPermission.
        assertEquals(0, requestPermissionCallCount())
        testScope.cancel()
    }

    // ── requestHostDirectoryAccess (Story 2.2.2) ───────────────────────────────────────────────

    @Test
    fun requestHostDirectoryAccess_should_SetGrantedAndStartSyncLoops_When_UserAllowsNativePrompt() = runTest {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", FakeCacheAccess(), testScope)
        val handle = fakeHandleWithPermissionResult("granted")
        sync.lookupPersistedHandle = { handle to "/stelekit/g" }

        val state = sync.requestHostDirectoryAccess("g")

        assertEquals(HostAccessState.Granted, state)
        assertEquals(HostAccessState.Granted, sync.hostAccessStateFlow.value)
        assertNotNull(sync.hostDirHandle)
        assertEquals("/stelekit/g", sync.hostGraphOpfsPath)
        testScope.cancel()
    }

    @Test
    fun requestHostDirectoryAccess_should_SetDeniedWithoutRetryLoop_When_UserDeclinesNativePrompt() = runTest {
        resetRequestPermissionCallCount()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", FakeCacheAccess(), testScope)
        val handle = fakeHandlePromptThenCountingDeniedRequestPermission()
        sync.lookupPersistedHandle = { handle to "/stelekit/g" }

        val state = sync.requestHostDirectoryAccess("g")

        assertEquals(HostAccessState.Denied, state)
        assertEquals(HostAccessState.Denied, sync.hostAccessStateFlow.value)
        assertNull(sync.hostDirHandle)
        // Exactly one prompt shown by this call — retrying is the user's job (clicking again),
        // never an automatic internal retry loop.
        assertEquals(1, requestPermissionCallCount())
        testScope.cancel()
    }

    // ── storage.persist() fire-and-forget (Story 2.4.1) ────────────────────────────────────────

    @Test
    fun connectHostDirectory_should_CallRequestStoragePersistenceExactlyOnce_When_ConnectSucceeds() = runTest {
        val opfsPath = "/stelekit/g"
        val cache = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g", cache, testScope)

        val originalPersist = stubStoragePersistCounting()
        val originalPicker = stubShowDirectoryPickerToResolveForSessionResumeTest(emptyRootDir())
        try {
            val state = sync.connectHostDirectory(opfsPath)
            assertEquals(HostAccessState.Granted, state)
            // The persist() call is fire-and-forget (scope.launch, real Dispatchers.Default) —
            // give it real wall-clock time to run rather than asserting immediately.
            withContext(Dispatchers.Default) { delay(300) }
            assertEquals(1, storagePersistCallCount())
        } finally {
            restoreStoragePersist(originalPersist)
            restoreShowDirectoryPickerForSessionResumeTest(originalPicker)
        }
        testScope.cancel()
    }

    @Test
    fun connectHostDirectory_should_ResolveGrantedWithoutWaitingOnStoragePersist_When_StoragePersistIsSlowOrDenied() = runTest {
        val opfsPath = "/stelekit/g2"
        val cache = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("g2", cache, testScope)

        val originalPersist = stubStoragePersistToHangForever()
        val originalPicker = stubShowDirectoryPickerToResolveForSessionResumeTest(emptyRootDir())
        try {
            // Reaching this assertion at all (without the test hanging/timing out) is the proof:
            // storage.persist()'s never-resolving promise cannot be on connectHostDirectory's
            // await chain.
            val state = sync.connectHostDirectory(opfsPath)
            assertEquals(HostAccessState.Granted, state)
        } finally {
            restoreStoragePersist(originalPersist)
            restoreShowDirectoryPickerForSessionResumeTest(originalPicker)
        }
        testScope.cancel()
    }
}
