// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.platform.PlatformFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object
// (mirrors PlatformFileSystemRelinkHostDirectoryTest.kt's established idiom for this codebase).
private fun stubShowDirectoryPickerToRecordCalls(): JsAny? = js(
    """
    (function() {
        var original = window.showDirectoryPicker;
        window.__stelekitPickerCalls = 0;
        window.showDirectoryPicker = function() {
            window.__stelekitPickerCalls++;
            return Promise.reject(new Error('showDirectoryPicker must not be called for an AppOwned clone destination'));
        };
        return original || null;
    })()
    """,
)
private fun pickerCallCount(): Int = js("(window.__stelekitPickerCalls || 0)")
private fun restoreDirectoryPicker(original: JsAny?): Unit = js(
    """
    (function() { window.showDirectoryPicker = original; delete window.__stelekitPickerCalls; })()
    """,
)

/**
 * Story 2.3.2 (Task 2.3.2c): cloning a git repository into "App storage" on Web must never call
 * `window.showDirectoryPicker()` — `GitSetupScreen`'s `showCloneLocationPicker` branch resolves the
 * `AppOwned` destination via `fileSystem.newAppOwnedGraphPath()` alone (`GitSetupScreen.kt`'s
 * `onBrowseRepoRoot` wiring, Task 2.3.2a), with zero File-System-Access-API involvement. This
 * `wasmJsTest` source set has no Compose UI test harness (no `ui-test`-equivalent dependency wired
 * for the web target — see `FolderSyncStatusBadgeTest.kt`'s doc comment for the same limitation),
 * so this exercises the real [PlatformFileSystem] primitive `Step2RepoPath`'s
 * `onBrowseRepoRoot`/`UnifiedLocationPicker` wiring is built on, rather than mounting
 * `GitSetupScreen` itself.
 */
class GitSetupScreenAppOwnedCloneTest {

    @Test
    fun newAppOwnedGraphPath_should_NeverCallShowDirectoryPicker_When_UsedAsACloneDestination() {
        val original = stubShowDirectoryPickerToRecordCalls()
        try {
            val fileSystem = PlatformFileSystem()

            val path = fileSystem.newAppOwnedGraphPath()

            assertTrue(
                path.startsWith("/stelekit/"),
                "an AppOwned clone destination must be an OPFS path rooted at /stelekit, was: $path",
            )
            assertEquals(
                0,
                pickerCallCount(),
                "selecting App storage for a clone must never invoke showDirectoryPicker",
            )
        } finally {
            restoreDirectoryPicker(original)
        }
    }

    @Test
    fun newAppOwnedGraphPath_should_ProduceADistinctPath_When_CalledAgainForAnotherClone() {
        val fileSystem = PlatformFileSystem()

        val first = fileSystem.newAppOwnedGraphPath()
        val second = fileSystem.newAppOwnedGraphPath()

        assertNotEquals(first, second, "each clone destination must resolve to a distinct graph id")
    }
}
