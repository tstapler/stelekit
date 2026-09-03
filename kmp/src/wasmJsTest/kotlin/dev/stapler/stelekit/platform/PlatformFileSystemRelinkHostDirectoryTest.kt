// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object
// (mirrors HostDirectorySyncFallbackRegressionTest.kt / HostDirectorySyncReconciliationTest.kt's
// established idiom for this codebase).

// The fake FileSystemDirectoryHandle/FileSystemFileHandle tree builders (fakeTextFileEntry,
// buildEntry, TextFile, Dir, toJsArray) live in HostDirectoryTestFixtures.kt, same package, no
// import needed — shared with HostDirectorySyncReconciliationTest.kt.

private fun captureDirectoryPicker(): JsAny? = js("(window.showDirectoryPicker || null)")
private fun hideDirectoryPicker(): Unit = js("window.showDirectoryPicker = undefined")
private fun restoreDirectoryPicker(original: JsAny?): Unit = js("window.showDirectoryPicker = original")

/** Mirrors HostDirectorySyncReconciliationTest.kt's stubShowDirectoryPickerToResolve/Reject. */
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
 * Coverage for [PlatformFileSystem.relinkHostDirectoryAsync] (Epic 4.4's stale-cache-prune bug
 * fix) — previously untested. Runs against the real wasmJs [PlatformFileSystem] actual in headless
 * Chrome via `wasmJsBrowserTest`, matching this source set's established real-interop testing
 * convention (`HostDirectorySyncReconciliationTest.kt`,
 * `HostDirectorySyncFallbackRegressionTest.kt`).
 */
class PlatformFileSystemRelinkHostDirectoryTest {

    private fun freshGraphId(): String = "it-relink-${Random.nextInt(0, Int.MAX_VALUE)}"

    @Test
    fun `relinkHostDirectoryAsync returns null when the picker is unsupported`() = runTest {
        val original = captureDirectoryPicker()
        hideDirectoryPicker()
        try {
            assertFalse(showDirectoryPickerSupported())
            val graphId = freshGraphId()
            val fs = PlatformFileSystem()
            fs.preload("/stelekit/$graphId")

            val result = fs.relinkHostDirectoryAsync("/stelekit/$graphId")

            assertNull(result)
        } finally {
            restoreDirectoryPicker(original)
        }
    }

    @Test
    fun `relinkHostDirectoryAsync returns null on picker abort without setting lastPickerError`() = runTest {
        val graphId = freshGraphId()
        val fs = PlatformFileSystem()
        fs.preload("/stelekit/$graphId")

        val original = stubShowDirectoryPickerToReject()
        val result = try {
            fs.relinkHostDirectoryAsync("/stelekit/$graphId")
        } finally {
            restoreShowDirectoryPicker(original)
        }

        assertNull(result)
        // Abort is a user-initiated cancel, not a real failure — must not surface as a picker error.
        assertNull(fs.consumeLastPickerError())
    }

    @Test
    fun `relinkHostDirectoryAsync imports the new folder and prunes stale cache entries not present in it`() = runTest {
        val graphId = freshGraphId()
        val existingPath = "/stelekit/$graphId"
        val fs = PlatformFileSystem()
        fs.preload(existingPath)

        // Seed the cache as if an earlier-linked folder (or the graph's own pre-relink content)
        // left a file behind that the newly-picked folder below does not contain. Covers all
        // three maps relinkHostDirectoryAsync's doc comment claims to prune: plaintext cache,
        // paranoid-mode bytesCache (bug-fix regression — an earlier version only scanned `cache`
        // and missed this), and blobUrlCache-only imported images.
        fs.writeFile("$existingPath/journal/2024_01_01.md", "# stale journal entry")
        assertEquals("# stale journal entry", fs.readFile("$existingPath/journal/2024_01_01.md"))
        fs.writeFileBytes("$existingPath/vault/secret.md.stek", byteArrayOf(1, 2, 3))
        assertNotNull(fs.getContentBytes("$existingPath/vault/secret.md.stek"))
        fs.registerBlobUrl("$existingPath/assets/old.png", "blob:stale-image")
        assertEquals("blob:stale-image", fs.resolveAssetUri(existingPath, "assets/old.png"))

        val newFolderName = "notes-b"
        val host = fakeDirEntry(
            newFolderName,
            toJsArray(
                listOf(
                    fakeTextFileEntry("Root.md", "# root"),
                    buildEntry(Dir("notes", listOf(TextFile("Foo.md", "# foo")))),
                ),
            ),
        )

        val original = stubShowDirectoryPickerToResolve(host)
        val result = try {
            fs.relinkHostDirectoryAsync(existingPath)
        } finally {
            restoreShowDirectoryPicker(original)
        }

        assertEquals(newFolderName, result, "should return the picked folder's own display name")
        assertEquals("# root", fs.readFile("$existingPath/Root.md"))
        assertEquals("# foo", fs.readFile("$existingPath/notes/Foo.md"))
        // Finding 1 regression: the stale journal/ entry from the previous folder must not survive
        // the relink — otherwise a later edit to it would write old-folder content into the newly
        // linked folder via scheduleHostWriteThrough.
        assertNull(
            fs.readFile("$existingPath/journal/2024_01_01.md"),
            "stale cache entry from the previously-linked folder must be pruned after relink",
        )
        assertNull(
            fs.getContentBytes("$existingPath/vault/secret.md.stek"),
            "stale bytesCache-only (paranoid-mode) entry must be pruned after relink",
        )
        assertNull(
            fs.resolveAssetUri(existingPath, "assets/old.png"),
            "stale blobUrlCache-only (image) entry must be pruned after relink",
        )
        assertTrue(fs.supportsHostDirectoryLink)
    }
}
