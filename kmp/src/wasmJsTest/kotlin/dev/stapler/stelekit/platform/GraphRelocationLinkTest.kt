// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import arrow.core.Either
import dev.stapler.stelekit.db.createWasmJsHostLinkStep
import dev.stapler.stelekit.git.model.DirtyOp
import dev.stapler.stelekit.model.StorageLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// window.showDirectoryPicker stubbing — file-private js() top-level functions can't share a name
// with another file's (this codebase's established per-file-suffix convention, e.g.
// HostDirectorySyncSessionResumeTest.kt's *ForSessionResumeTest variants), so this file defines
// its own rather than reusing HostDirectorySyncReconciliationTest.kt's identically-shaped ones.

private fun stubShowDirectoryPickerToResolve(handle: JsAny): JsAny? = js(
    """
    (function() {
        var original = window.showDirectoryPicker;
        window.showDirectoryPicker = function() { return Promise.resolve(handle); };
        return original || null;
    })()
    """,
)

private fun stubShowDirectoryPickerToReject(): JsAny? = js(
    """
    (function() {
        var original = window.showDirectoryPicker;
        window.showDirectoryPicker = function() { return Promise.reject(new Error('user cancelled')); };
        return original || null;
    })()
    """,
)

private fun restoreShowDirectoryPicker(original: JsAny?): Unit = js(
    """
    (function() { window.showDirectoryPicker = original; })()
    """,
)

/**
 * Epic 4.1 (Story 4.1.1): confirms [createWasmJsHostLinkStep] — the `HostLinkStep` production
 * wiring `GraphRelocationCoordinator.link` calls after resolving a Link operation's source root —
 * forwards straight to [HostDirectorySync.connectHostDirectory] rather than reimplementing a
 * parallel connect/reconcile flow (validation.md REQ-5/REQ-12). Proven by observing side effects
 * only [HostDirectorySync.runHostReconciliation]'s real walk produces (browser-only cache edits
 * preserved and queued for push) — a hand-rolled duplicate implementation would not reproduce
 * these exactly.
 */
class GraphRelocationLinkTest {

    private fun newSync(graphId: String, cacheAccess: FakeCacheAccess, scope: CoroutineScope): HostDirectorySync =
        disconnectedSync(OpfsGraphSlug(graphId), cacheAccess, scope)

    @Test
    fun linkOperation_should_InvokeConnectHostDirectory_When_VerificationPasses() = runTest {
        val opfsPath = "/stelekit/link-notes"
        val cache = FakeCacheAccess()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("link-notes", cache, testScope)
        val step = createWasmJsHostLinkStep(sync)
        val destination = StorageLocation.HostFolder("link-notes", "Documents")

        val original = stubShowDirectoryPickerToResolve(emptyRootDir())
        val result = try {
            step.link(opfsPath, destination)
        } finally {
            restoreShowDirectoryPicker(original)
        }

        assertIs<Either.Right<Unit>>(result)
        // Only connectHostDirectory's real success path sets these two fields together.
        assertNotNull(sync.hostDirHandle)
        assertEquals(opfsPath, sync.hostGraphOpfsPath)
        assertEquals(HostAccessState.Granted, sync.hostAccessStateFlow.value)
        testScope.cancel()
    }

    @Test
    fun linkOperation_should_ReuseRunHostReconciliationUnchanged_When_Invoked() = runTest {
        val opfsPath = "/stelekit/link-notes"
        val cache = FakeCacheAccess()
        cache.textStore["$opfsPath/pages/BrowserOnly.md"] = "browser edit"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("link-notes", cache, testScope)
        val step = createWasmJsHostLinkStep(sync)

        val original = stubShowDirectoryPickerToResolve(emptyRootDir())
        val result = try {
            step.link(opfsPath, StorageLocation.HostFolder("link-notes", "Documents"))
        } finally {
            restoreShowDirectoryPicker(original)
        }

        assertIs<Either.Right<Unit>>(result)
        // Preserved (not overwritten) and queued for push — only runHostReconciliation's real walk
        // produces this; a parallel/duplicate connect implementation would not.
        assertEquals("browser edit", cache.textStore["$opfsPath/pages/BrowserOnly.md"])
        assertTrue(sync.hostWritePending.containsKey("pages/BrowserOnly.md"))
        assertEquals(DirtyOp.WRITE, sync.hostWritePending.getValue("pages/BrowserOnly.md").op)
        testScope.cancel()
    }

    @Test
    fun linkOperation_should_ReturnLeft_When_ConnectHostDirectoryFails() = runTest {
        val opfsPath = "/stelekit/link-notes"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newSync("link-notes", FakeCacheAccess(), testScope)
        val step = createWasmJsHostLinkStep(sync)

        val original = stubShowDirectoryPickerToReject()
        val result = try {
            step.link(opfsPath, StorageLocation.HostFolder("link-notes", "Documents"))
        } finally {
            restoreShowDirectoryPicker(original)
        }

        assertIs<Either.Left<*>>(result)
        assertNull(sync.hostDirHandle)
        testScope.cancel()
    }
}
