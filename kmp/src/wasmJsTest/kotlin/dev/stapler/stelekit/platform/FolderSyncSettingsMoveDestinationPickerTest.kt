// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.model.StorageLocation
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object
// (mirrors PlatformFileSystemRelinkHostDirectoryTest.kt's established idiom for this codebase).
// These helpers are file-private (Kotlin top-level `private` is file-scoped), so redeclared here
// rather than shared, matching that file's own precedent.

private fun captureDirectoryPicker(): JsAny? = js("(window.showDirectoryPicker || null)")
private fun hideDirectoryPicker(): Unit = js("window.showDirectoryPicker = undefined")
private fun restoreDirectoryPicker(original: JsAny?): Unit = js("window.showDirectoryPicker = original")

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
        window.showDirectoryPicker = function() { return Promise.reject(new Error('user aborted the request')); };
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
 * Story 3.3.3 (AppOwned→HostFolder move direction): closes the spec-compliance gap where
 * `FolderSyncSettings.kt`'s "Move storage location…" button did nothing but log a warning once the
 * resolved source was [StorageLocation.AppOwned] — no picker, no dialog, ever opened. The fix wires
 * [PlatformFileSystem.pickHostFolderNamePreview] (new — a name-only, side-effect-free
 * `showDirectoryPicker()` call, deliberately distinct from [PlatformFileSystem.pickDirectoryAsync]/
 * [PlatformFileSystem.relinkHostDirectoryAsync], which both import content and attach a live host
 * handle) into `FolderSyncSettings`'s `onBrowseRequestedForMove`, opening
 * [dev.stapler.stelekit.ui.components.UnifiedLocationPicker] for that direction instead of the old
 * no-op.
 *
 * `wasmJsTest` has no Compose UI test harness (see `AddGraphDialogPickerTest.kt`'s doc comment for
 * the same limitation), so this exercises the real interop surface the fix is built on — the same
 * "test the real capability the UI is wired 1:1 from, not the UI" convention that file follows —
 * plus a literal reproduction of `App.kt`'s `onBrowseRequestedForMove` wiring lambda, proving the
 * exact callback `FolderSyncSettings` receives now performs a real `showDirectoryPicker()` call and
 * returns a genuine [StorageLocation.HostFolder] destination, rather than never picking anything.
 */
class FolderSyncSettingsMoveDestinationPickerTest {

    private fun freshGraphId(): String = "it-move-dest-${Random.nextInt(0, Int.MAX_VALUE)}"

    @Test
    fun `pickHostFolderNamePreview returns null when the picker is unsupported`() = runTest {
        val original = captureDirectoryPicker()
        hideDirectoryPicker()
        try {
            assertFalse(showDirectoryPickerSupported())
            val fs = PlatformFileSystem()

            assertNull(fs.pickHostFolderNamePreview())
        } finally {
            restoreDirectoryPicker(original)
        }
    }

    @Test
    fun `pickHostFolderNamePreview returns null on picker abort without setting lastPickerError`() = runTest {
        val fs = PlatformFileSystem()
        val original = stubShowDirectoryPickerToReject()
        fs.requestDirectoryPickerNow()
        val result = try {
            fs.pickHostFolderNamePreview()
        } finally {
            restoreShowDirectoryPicker(original)
        }

        assertNull(result)
        assertNull(fs.consumeLastPickerError(), "user-initiated cancel must not surface as a picker error")
    }

    @Test
    fun `pickHostFolderNamePreview opens the real picker and returns just the folder name, importing nothing`() = runTest {
        val folderName = "my-notes"
        val host = fakeDirEntry(
            folderName,
            toJsArray(listOf(fakeTextFileEntry("Root.md", "# should never be imported"))),
        )

        val fs = PlatformFileSystem()
        val original = stubShowDirectoryPickerToResolve(host)
        fs.requestDirectoryPickerNow()
        val result = try {
            fs.pickHostFolderNamePreview()
        } finally {
            restoreShowDirectoryPicker(original)
        }

        assertEquals(folderName, result, "should return the picked folder's own display name")
        // The whole point of this preview method (vs. pickDirectoryAsync/relinkHostDirectoryAsync)
        // is that it does NOT import content or attach a live host handle — confirm no file from
        // the picked folder ended up in the cache under any derived path.
        assertNull(fs.readFile("$folderName/Root.md"))
        assertNull(fs.readFile("${fs.getDefaultGraphPath()}/$folderName/Root.md"))
    }

    /**
     * Reproduces `App.kt`'s `onBrowseRequestedForMove` lambda verbatim (wrap
     * [PlatformFileSystem.pickHostFolderNamePreview]'s result in a [StorageLocation.HostFolder] for
     * the active graph) — the exact callback `FolderSyncSettings`'s
     * `MoveStorageLocationSection` passes to `UnifiedLocationPicker.onBrowseRequested` for the
     * AppOwned→HostFolder direction, where the button click previously only ever reached a
     * `logger.warn` call, never a picker.
     */
    @Test
    fun `wired onBrowseRequestedForMove callback resolves a real picked folder to a HostFolder destination`() = runTest {
        val graphId = freshGraphId()
        val folderName = "backup-folder"
        val host = fakeDirEntry(folderName, newJsArray())

        val fs = PlatformFileSystem()
        val onBrowseRequestedForMove: suspend () -> StorageLocation? = {
            fs.pickHostFolderNamePreview()?.let { name -> StorageLocation.HostFolder(graphId, name) }
        }

        val original = stubShowDirectoryPickerToResolve(host)
        fs.requestDirectoryPickerNow()
        val destination = try {
            onBrowseRequestedForMove()
        } finally {
            restoreShowDirectoryPicker(original)
        }

        val hostFolder = assertIs<StorageLocation.HostFolder>(
            destination,
            "AppOwned→HostFolder move must now resolve a real destination instead of staying null/no-op",
        )
        assertEquals(graphId, hostFolder.graphId)
        assertEquals(folderName, hostFolder.displayName)
    }

    @Test
    fun `wired onBrowseRequestedForMove callback returns null on cancel, matching UnifiedLocationPicker's revert-to-unselected contract`() = runTest {
        val graphId = freshGraphId()
        val fs = PlatformFileSystem()
        val onBrowseRequestedForMove: suspend () -> StorageLocation? = {
            fs.pickHostFolderNamePreview()?.let { name -> StorageLocation.HostFolder(graphId, name) }
        }

        val original = stubShowDirectoryPickerToReject()
        fs.requestDirectoryPickerNow()
        val destination = try {
            onBrowseRequestedForMove()
        } finally {
            restoreShowDirectoryPicker(original)
        }

        assertNull(destination)
    }

    @Test
    fun `PlatformFileSystem reports host-directory-link support, so the AppOwned-source Browse row is offered on this platform`() {
        assertTrue(PlatformFileSystem().supportsHostDirectoryLink)
    }
}
