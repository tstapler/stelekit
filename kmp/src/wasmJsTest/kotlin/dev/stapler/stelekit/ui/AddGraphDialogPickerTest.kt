// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.platform.PlatformFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object
// (mirrors PlatformFileSystemRelinkHostDirectoryTest.kt's established idiom for this codebase).
private fun captureDirectoryPicker(): JsAny? = js("(window.showDirectoryPicker || null)")
private fun hideDirectoryPicker(): Unit = js("window.showDirectoryPicker = undefined")
private fun restoreDirectoryPicker(original: JsAny?): Unit = js("window.showDirectoryPicker = original")

/**
 * Story 2.3.1 (Task 2.3.1d): the new-graph flow's routing decision must always land on
 * [AddGraphFlowMode.ShowLocationPicker] on Web — i.e., [dev.stapler.stelekit.ui.components.UnifiedLocationPicker]
 * is shown instead of the old OPFS-fallback-only [AddGraphDialog] — regardless of whether the
 * browser supports the File System Access API (`stack.md` §3's two-tier Chromium-vs-Firefox/Safari
 * split). This `wasmJsTest` source set has no Compose UI test harness (no `ui-test`-equivalent
 * dependency wired for the web target — see `FolderSyncStatusBadgeTest.kt`'s doc comment for the
 * same limitation), so this exercises the real [PlatformFileSystem] capability flags that
 * `platformCapabilities`/`UnifiedLocationPicker`'s "Browse…" row visibility are wired 1:1 from at
 * every call site (`App.kt`, `GitSetupScreen.kt`) rather than mounting the composable itself —
 * `LocationPickerRows` only renders "Browse…" `if (platformCapabilities)`, so a false
 * `supportsNativeDirectoryPicker` here is exactly the "no Browse… row at all" outcome the
 * Firefox/Safari acceptance criterion describes.
 */
class AddGraphDialogPickerTest {

    @Test
    fun addGraphFlowMode_should_ShowLocationPicker_When_BrowserLacksFileSystemAccessApi() {
        val original = captureDirectoryPicker()
        hideDirectoryPicker()
        try {
            val fileSystem = PlatformFileSystem()

            assertFalse(
                fileSystem.supportsNativeDirectoryPicker,
                "simulated Firefox/Safari must report no native directory picker",
            )
            assertTrue(
                fileSystem.supportsAppOwnedStorage,
                "App storage must remain offered even with no native picker (stack.md §3)",
            )
            assertEquals(AddGraphFlowMode.ShowLocationPicker, addGraphFlowMode(fileSystem))
        } finally {
            restoreDirectoryPicker(original)
        }
    }

    @Test
    fun addGraphFlowMode_should_ShowLocationPicker_When_BrowserHasFileSystemAccessApi() {
        // This suite runs in real headless Chrome (wasmJsBrowserTest/Karma), the Chromium half of
        // stack.md §3's two-tier split — showDirectoryPicker is natively supported here.
        val fileSystem = PlatformFileSystem()

        assertTrue(fileSystem.supportsNativeDirectoryPicker, "headless Chrome supports showDirectoryPicker")
        assertTrue(fileSystem.supportsAppOwnedStorage)
        assertEquals(AddGraphFlowMode.ShowLocationPicker, addGraphFlowMode(fileSystem))
    }

    @Test
    fun realWasmJsPlatformFileSystem_supportsAppOwnedStorage_should_BeTrue() {
        val fileSystem = PlatformFileSystem()

        assertTrue(
            fileSystem.supportsAppOwnedStorage,
            "Web must offer the App storage option in every browser, with zero File-System-Access grant",
        )
        val path = fileSystem.newAppOwnedGraphPath()
        assertTrue(path.startsWith("/stelekit/"), "OPFS paths are rooted at /stelekit per getDefaultGraphPath()'s convention, was: $path")
    }
}
