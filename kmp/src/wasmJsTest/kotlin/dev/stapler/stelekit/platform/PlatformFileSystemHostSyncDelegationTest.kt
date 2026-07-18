// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object.

private fun fakeHandleWithGrantedPermission(): JsAny = js(
    """
    ({
        queryPermission: function(opts) { return Promise.resolve('granted'); },
        requestPermission: function(opts) { return Promise.resolve('granted'); }
    })
    """,
)

/**
 * Task 2.2.2b: coverage for [PlatformFileSystem.hostDirectoryAccessState]'s one-line delegate to
 * `HostDirectorySync.hostAccessStateFlow.value` — the [dev.stapler.stelekit.platform.FileSystem]-
 * interface touch point commonMain callers use instead of downcasting to [PlatformFileSystem] to
 * reach `hostDirectorySync` directly.
 *
 * `writeFile`/`writeFileBytes`/`deleteFile` write-through delegation coverage (Story 4.3.1, Phase
 * 4 — not yet implemented on this branch) belongs in this same file per
 * `project_plans/web-local-folder-livesync/implementation/validation.md`'s
 * `PlatformFileSystemHostSyncDelegationTest.kt` rows; a future epic extends this class rather than
 * replacing it.
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
}
