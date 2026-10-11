// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.createWasmJsHostLinkStep
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.platform.FakeCacheAccess
import dev.stapler.stelekit.platform.HostAccessState
import dev.stapler.stelekit.platform.HostDirectorySync
import dev.stapler.stelekit.platform.OpfsGraphSlug
import dev.stapler.stelekit.platform.disconnectedSync
import dev.stapler.stelekit.platform.emptyRootDir
import dev.stapler.stelekit.ui.components.folderSyncBadgeContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// window.showDirectoryPicker stubbing — this file's own copy, per this codebase's established
// per-file convention (see GraphRelocationLinkTest.kt's identical comment).
private fun stubShowDirectoryPickerToResolve(handle: JsAny): JsAny? = js(
    """
    (function() {
        var original = window.showDirectoryPicker;
        window.showDirectoryPicker = function() { return Promise.resolve(handle); };
        return original || null;
    })()
    """,
)

private fun restoreShowDirectoryPicker(original: JsAny?): Unit = js(
    """
    (function() { window.showDirectoryPicker = original; })()
    """,
)

/** A persisted-handle stand-in whose `queryPermission`/`requestPermission` resolve [permission]. */
private fun fakeHandleWithPermission(permission: String): JsAny = js(
    """
    ({
        queryPermission: function() { return Promise.resolve(permission); },
        requestPermission: function() { return Promise.resolve(permission); }
    })
    """,
)

/**
 * Epic 4.1 (Task 4.1.3a): confirms `FolderSyncStatusBadge` needs no new component and no
 * divergent rendering path for a Link established via [GraphRelocationCoordinator]'s new
 * `HostLinkStep` wiring ([createWasmJsHostLinkStep]) — the same
 * [dev.stapler.stelekit.platform.HostAccessState] values, fed through the same
 * [folderSyncBadgeContent] pure function `FolderSyncStatusBadgeTest.kt` already covers, come out
 * identically regardless of which entry point established the link (AC38). This project's
 * `wasmJsTest` source set has no Compose UI test harness (see `FolderSyncStatusBadgeTest.kt`'s own
 * doc comment), so — matching that file's established precedent — this test scopes to
 * [folderSyncBadgeContent]'s state→copy contract rather than real keyboard/focus interaction
 * (AC39/AC40), which aren't mechanically testable at this layer on this target.
 */
class FolderSyncStatusBadgeLinkReuseTest {

    private fun newDisconnectedSync(graphId: String, scope: CoroutineScope): HostDirectorySync =
        disconnectedSync(OpfsGraphSlug(graphId), FakeCacheAccess(), scope)

    @Test
    fun folderSyncStatusBadge_should_RenderIdenticalCopy_When_LinkEstablishedViaCoordinatorVsFolderSyncSettings() = runTest {
        val opfsPath = "/stelekit/reuse-notes"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Entry point 1: GraphRelocationCoordinator's Link wiring.
        val viaCoordinator = newDisconnectedSync("reuse-a", testScope)
        val originalA = stubShowDirectoryPickerToResolve(emptyRootDir())
        try {
            createWasmJsHostLinkStep(viaCoordinator).link(opfsPath, StorageLocation.HostFolder("reuse-a", "Documents"))
        } finally {
            restoreShowDirectoryPicker(originalA)
        }

        // Entry point 2: FolderSyncSettings's original "Enable live folder sync" wiring — Main.kt
        // calls this exact same HostDirectorySync.connectHostDirectory function directly.
        val viaSettings = newDisconnectedSync("reuse-b", testScope)
        val originalB = stubShowDirectoryPickerToResolve(emptyRootDir())
        try {
            viaSettings.connectHostDirectory(opfsPath)
        } finally {
            restoreShowDirectoryPicker(originalB)
        }

        assertEquals(viaSettings.hostAccessStateFlow.value, viaCoordinator.hostAccessStateFlow.value)

        val contentViaCoordinator = folderSyncBadgeContent(viaCoordinator.hostAccessStateFlow.value, "Documents", 0)
        val contentViaSettings = folderSyncBadgeContent(viaSettings.hostAccessStateFlow.value, "Documents", 0)
        assertEquals(contentViaSettings, contentViaCoordinator)

        testScope.cancel()
    }

    @Test
    fun folderSyncStatusBadge_should_RenderExistingGrantAccessCopy_When_LinkEstablishedViaCoordinatorLaterDeniesPermission() = runTest {
        val opfsPath = "/stelekit/reuse-notes"
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = newDisconnectedSync("reuse-c", testScope)

        val original = stubShowDirectoryPickerToResolve(emptyRootDir())
        try {
            createWasmJsHostLinkStep(sync).link(opfsPath, StorageLocation.HostFolder("reuse-c", "Documents"))
        } finally {
            restoreShowDirectoryPicker(original)
        }
        assertEquals(HostAccessState.Granted, sync.hostAccessStateFlow.value)

        // Simulate the browser later demoting permission — the exact same
        // reconnectHostDirectory/mapPermissionResultToAccessState mechanism a FolderSyncSettings-
        // established link would go through, since both converge on this one HostDirectorySync
        // instance. Overriding the test-only lookupPersistedHandle seam stands in for a real
        // IndexedDB round trip (mirrors HostDirectorySyncSessionResumeTest.kt's own precedent).
        sync.lookupPersistedHandle = { _ -> fakeHandleWithPermission("denied") to opfsPath }
        val resumedState = sync.reconnectHostDirectory("reuse-c")

        assertEquals(HostAccessState.Denied, resumedState)
        val content = folderSyncBadgeContent(resumedState, dirName = null, pendingWriteCount = 0)
        // Byte-identical to FolderSyncStatusBadgeTest.kt's own Denied-state assertion — no
        // parallel/divergent copy for a Link established via this new entry point.
        assertEquals("Folder access declined — Grant access", content?.text)
        assertTrue(content?.clickable == true)

        testScope.cancel()
    }
}
