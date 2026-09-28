// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object
// (mirrors HostDirectoryTestFixtures.kt's established idiom for this codebase).

/** Current value of `window.showDirectoryPicker`, or `null` if unset — saved so the real headless
 * Chrome capability can be restored after a test forces it away. */
private fun captureDirectoryPicker(): JsAny? = js("(window.showDirectoryPicker || null)")

/** Deletes `window.showDirectoryPicker` so [showDirectoryPickerSupported] reads `false` — the only
 * way to simulate an unsupported browser (Firefox/Safari at the time this project shipped) against
 * the real wasmJs actual, since `showDirectoryPickerSupported()` (OpfsInterop.kt) is a hardcoded
 * feature-detect with no dependency-injection seam. */
private fun hideDirectoryPicker(): Unit = js("window.showDirectoryPicker = undefined")

/** Restores whatever [captureDirectoryPicker] observed before [hideDirectoryPicker] ran. */
private fun restoreDirectoryPicker(original: JsAny?): Unit = js("window.showDirectoryPicker = original")

/**
 * Epic 8.2 (Story 8.2.1, Task 8.2.1a): proves the entire web-local-folder-livesync surface is
 * completely inert on a browser without the File System Access API — the explicit "this project
 * introduces zero risk for browsers outside its scope" requirement (requirements.md, design/ux.md
 * Surface 3's `NotApplicable` row). Forces [showDirectoryPickerSupported] to `false` for the
 * duration of each test via a real interop-level monkey-patch (see [hideDirectoryPicker]'s doc
 * comment), restored in a `finally` so later tests in the same Karma page session are unaffected.
 *
 * Runs against the real wasmJs `PlatformFileSystem`/[HostDirectorySync] actuals in headless
 * Chrome via `wasmJsBrowserTest`, matching this source set's established real-interop testing
 * convention (`PlatformFileSystemDirtyTrackingIntegrationTest.kt`,
 * `HostDirectorySyncHandleRetentionTest.kt`) rather than injecting a mock traversal function.
 */
class HostDirectorySyncFallbackRegressionTest {

    private fun freshGraphId(): String = "it-fallback-${Random.nextInt(0, Int.MAX_VALUE)}"

    private suspend fun withDirectoryPickerHidden(block: suspend () -> Unit) {
        val original = captureDirectoryPicker()
        hideDirectoryPicker()
        try {
            assertFalse(showDirectoryPickerSupported(), "test setup: showDirectoryPickerSupported() should read false once hidden")
            block()
        } finally {
            restoreDirectoryPicker(original)
        }
    }

    /** Asserts every field/flow this whole project added is still at its untouched default. */
    private fun assertHostDirectorySyncInert(hostDirectorySync: HostDirectorySync) {
        assertNull(hostDirectorySync.hostDirHandle)
        assertNull(hostDirectorySync.hostGraphOpfsPath)
        assertEquals(HostAccessState.NotApplicable, hostDirectorySync.hostAccessStateFlow.value)
        assertTrue(hostDirectorySync.hostWritePending.isEmpty())
        assertTrue(hostDirectorySync.hostModTimes.isEmpty())
        assertTrue(hostDirectorySync.hostFileSizes.isEmpty())
        assertEquals(0, hostDirectorySync.hostWritePendingCountFlow.value)
        assertFalse(hostDirectorySync.hostWriteStuckFlow.value)
    }

    @Test
    fun `reconnectHostDirectory resolves NotApplicable and touches no project state when unsupported`() = runTest {
        withDirectoryPickerHidden {
            val graphId = freshGraphId()
            val fs = PlatformFileSystem()
            fs.preload("/stelekit/$graphId")

            // No queryPermission() call is possible here — reconnectHostDirectory's IndexedDB
            // lookup is browser-API-agnostic, but a browser that never supported the picker also
            // never persisted a handle for pickDirectoryAsync/connectHostDirectory to find, so
            // lookupPersistedHandle naturally returns null regardless of this test's monkey-patch.
            val result = fs.hostDirectorySync.reconnectHostDirectory(graphId)

            assertEquals(HostAccessState.NotApplicable, result)
            assertHostDirectorySyncInert(fs.hostDirectorySync)
        }
    }

    @Test
    fun `pickDirectoryAsync returns null and never attaches a handle when unsupported`() = runTest {
        withDirectoryPickerHidden {
            val graphId = freshGraphId()
            val fs = PlatformFileSystem()
            fs.preload("/stelekit/$graphId")

            assertFalse(fs.supportsNativeDirectoryPicker)
            val picked = fs.pickDirectoryAsync()

            assertNull(picked)
            assertHostDirectorySyncInert(fs.hostDirectorySync)
        }
    }

    @Test
    fun `a normal writeFile-readFile cycle never engages HostDirectorySync when unsupported`() = runTest {
        withDirectoryPickerHidden {
            val graphId = freshGraphId()
            val fs = PlatformFileSystem()
            fs.preload("/stelekit/$graphId")

            fs.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo")
            val readBack = fs.readFile("/stelekit/$graphId/pages/Foo.md")

            assertEquals("# Foo", readBack)
            assertHostDirectorySyncInert(fs.hostDirectorySync)

            fs.deleteFile("/stelekit/$graphId/pages/Foo.md")
            assertHostDirectorySyncInert(fs.hostDirectorySync)
        }
    }
}
