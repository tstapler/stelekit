package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [FileRegistry] change-detection and own-write suppression logic.
 *
 * This is the core mechanism that should have prevented the 16:55 overwrite incident:
 * GraphWriter saves the file → marks it via markWrittenByUs → next watcher poll should
 * be a no-op. Tests here verify every condition where that guard can fail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileRegistryTest {

    // ── Fake filesystem ───────────────────────────────────────────────────────

    private data class FakeFile(var content: String, var modTime: Long)

    private class FakeFs(val files: MutableMap<String, FakeFile> = mutableMapOf()) : FileSystem {
        override fun getDefaultGraphPath() = "/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String) = files[path]?.content
        override fun writeFile(path: String, content: String): Boolean {
            val existing = files[path]
            val newMod = (existing?.modTime ?: 0L) + 1000L
            files[path] = FakeFile(content, newMod)
            return true
        }
        override fun listFiles(path: String) =
            files.keys.filter { it.startsWith("$path/") }.map { it.substringAfterLast("/") }
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = files.containsKey(path)
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String): Boolean { files.remove(path); return true }
        override fun pickDirectory() = null
        override fun getLastModifiedTime(path: String) = files[path]?.modTime

        /** Simulate an external write (bumps modtime like a real FS would). */
        fun externalWrite(path: String, content: String) {
            val existing = files[path]
            val newMod = (existing?.modTime ?: 0L) + 1000L
            files[path] = FakeFile(content, newMod)
        }
    }

    // ── Scenario 1: Empty directory ───────────────────────────────────────────

    @Test
    fun `detectChanges on empty directory returns empty ChangeSet`() = runTest {
        val fs = FakeFs()
        val registry = FileRegistry(fs)

        val changes = registry.detectChanges("/graph/journals")

        assertTrue(changes.newFiles.isEmpty())
        assertTrue(changes.changedFiles.isEmpty())
        assertTrue(changes.deletedPaths.isEmpty())
    }

    // ── Scenario 2: New file appears ──────────────────────────────────────────

    @Test
    fun `new file appears in newFiles on first detectChanges`() = runTest {
        val fs = FakeFs()
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Journal entry")
        val registry = FileRegistry(fs)

        val changes = registry.detectChanges("/graph/journals")

        assertEquals(1, changes.newFiles.size)
        assertEquals("2026_04_13.md", changes.newFiles[0].entry.fileName)
        assertEquals("- Journal entry", changes.newFiles[0].content)
    }

    @Test
    fun `file seen in first scan is not reported again on second scan when unchanged`() = runTest {
        val fs = FakeFs()
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Journal entry")
        val registry = FileRegistry(fs)

        registry.detectChanges("/graph/journals") // first scan registers it
        val second = registry.detectChanges("/graph/journals")

        assertTrue(second.newFiles.isEmpty(), "Already-registered file should not appear as new again")
        assertTrue(second.changedFiles.isEmpty(), "Unchanged file should not appear as changed")
    }

    // ── Scenario 3: Own-write suppression (the core guard) ───────────────────

    @Test
    fun `markWrittenByUs prevents own write being detected as external change`() = runTest {
        val fs = FakeFs()
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Original content")
        val registry = FileRegistry(fs)
        registry.detectChanges("/graph/journals") // register initial state

        // Simulate GraphWriter saving the file (same content, different modtime)
        fs.externalWrite("/graph/journals/2026_04_13.md", "- User typed content")
        registry.markWrittenByUs("/graph/journals/2026_04_13.md")

        // Watcher poll should see no changes
        val changes = registry.detectChanges("/graph/journals")
        assertTrue(changes.changedFiles.isEmpty(), "Own write must NOT be reported as external change")
    }

    @Test
    fun `markWrittenByUs after multiple rapid saves only needs the last mark`() = runTest {
        val fs = FakeFs()
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Draft 1")
        val registry = FileRegistry(fs)
        registry.detectChanges("/graph/journals")

        // Simulate 3 quick saves (each overwrites the previous)
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Draft 2")
        registry.markWrittenByUs("/graph/journals/2026_04_13.md")

        fs.externalWrite("/graph/journals/2026_04_13.md", "- Draft 3")
        registry.markWrittenByUs("/graph/journals/2026_04_13.md")

        fs.externalWrite("/graph/journals/2026_04_13.md", "- Final content")
        registry.markWrittenByUs("/graph/journals/2026_04_13.md")

        val changes = registry.detectChanges("/graph/journals")
        assertTrue(changes.changedFiles.isEmpty(), "Final own write must be suppressed after multiple saves")
    }

    // ── Scenario 4: Genuine external change IS detected ───────────────────────

    @Test
    fun `external write with different content IS detected as changed`() = runTest {
        val fs = FakeFs()
        fs.externalWrite("/graph/journals/2026_04_13.md", "- My notes")
        val registry = FileRegistry(fs)
        registry.detectChanges("/graph/journals") // register

        // External process writes different content
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Remote sync content")

        val changes = registry.detectChanges("/graph/journals")
        assertEquals(1, changes.changedFiles.size, "External change with new content must be detected")
        assertEquals("- Remote sync content", changes.changedFiles[0].content)
    }

    @Test
    fun `external blank overwrite IS detected as a change`() = runTest {
        val fs = FakeFs()
        fs.externalWrite("/graph/journals/2026_04_13.md", "- My journal notes\n- Second item")
        val registry = FileRegistry(fs)
        registry.detectChanges("/graph/journals")

        // External process blanks the file (the exact incident from the bug report)
        fs.externalWrite("/graph/journals/2026_04_13.md", "")

        val changes = registry.detectChanges("/graph/journals")
        assertEquals(1, changes.changedFiles.size, "Blank overwrite must be detected as external change")
        assertEquals("", changes.changedFiles[0].content)
    }

    // ── Scenario 5: Content hash guard is content-based, not mtime-based ─────

    @Test
    fun `same content with bumped modtime is NOT reported as changed`() = runTest {
        val fs = FakeFs()
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Same content")
        val registry = FileRegistry(fs)
        registry.detectChanges("/graph/journals")

        // Bump modtime only, content unchanged (e.g. touch command, or FS metadata write)
        val file = fs.files["/graph/journals/2026_04_13.md"]!!
        fs.files["/graph/journals/2026_04_13.md"] = file.copy(modTime = file.modTime + 5000L)

        val changes = registry.detectChanges("/graph/journals")
        assertTrue(changes.changedFiles.isEmpty(), "Same content with bumped mtime must NOT trigger re-parse")
    }

    // ── Scenario 6: markWrittenByUs without prior scan ────────────────────────

    @Test
    fun `markWrittenByUs on unregistered file pre-registers it so detectChanges skips it`() = runTest {
        val fs = FakeFs()
        fs.externalWrite("/graph/journals/new.md", "- Content")
        val registry = FileRegistry(fs)

        // markWrittenByUs before detectChanges registers the file as "already seen by us"
        registry.markWrittenByUs("/graph/journals/new.md")

        // File is pre-registered — it should NOT appear as new or changed
        val changes = registry.detectChanges("/graph/journals")
        assertTrue(changes.newFiles.isEmpty(),
            "File pre-registered via markWrittenByUs must not appear as new in detectChanges")
        assertTrue(changes.changedFiles.isEmpty(),
            "File pre-registered via markWrittenByUs must not appear as changed")
    }

    /**
     * scanDirectory no longer reads file content (avoids O(N) SAF IPC calls on Android).
     * Content hashes are initialised lazily on the first detectChanges that sees a mtime bump.
     *
     * Consequence: an mtime-only bump on the FIRST detectChanges after startup is reported
     * as a change (no baseline hash yet). On the SECOND detectChanges the hash is already
     * stored and the same-content bump is correctly suppressed.
     */
    @Test
    fun `mtime-only bump is reported once then suppressed after hash baseline is established`() = runTest {
        val fs = FakeFs()
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Content")
        val registry = FileRegistry(fs)

        registry.scanDirectory("/graph/journals")

        // Bump only the modtime (content unchanged — e.g. git sync, Dropbox, Logseq touch)
        val f = fs.files["/graph/journals/2026_04_13.md"]!!
        fs.files["/graph/journals/2026_04_13.md"] = f.copy(modTime = f.modTime + 5000L)

        // First detection: no hash baseline → reported as changed, hash stored as side-effect
        val changes1 = registry.detectChanges("/graph/journals")
        assertEquals(1, changes1.changedFiles.size,
            "First mtime bump with no hash baseline must be reported (lazy init)")

        // Bump mtime again without changing content
        val f2 = fs.files["/graph/journals/2026_04_13.md"]!!
        fs.files["/graph/journals/2026_04_13.md"] = f2.copy(modTime = f2.modTime + 5000L)

        // Second detection: hash is now stored → same-content bump correctly suppressed
        val changes2 = registry.detectChanges("/graph/journals")
        assertTrue(changes2.changedFiles.isEmpty(),
            "Second mtime-only bump with stored hash baseline must NOT trigger re-parse")
    }

    // ── Scenario 7: Own write followed immediately by external write ──────────

    @Test
    fun `external write after own-write mark IS detected (mark only covers the moment of write)`() = runTest {
        val fs = FakeFs()
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Original")
        val registry = FileRegistry(fs)
        registry.detectChanges("/graph/journals")

        // GraphWriter saves user content
        fs.externalWrite("/graph/journals/2026_04_13.md", "- User content")
        registry.markWrittenByUs("/graph/journals/2026_04_13.md")

        // External process then modifies the file AFTER our mark
        fs.externalWrite("/graph/journals/2026_04_13.md", "- External content")

        // Must detect the subsequent external change
        val changes = registry.detectChanges("/graph/journals")
        assertEquals(1, changes.changedFiles.size, "Post-mark external write must still be detected")
        assertEquals("- External content", changes.changedFiles[0].content)
    }

    // ── Scenario 8: File deleted ──────────────────────────────────────────────

    @Test
    fun `deleted file appears in deletedPaths`() = runTest {
        val fs = FakeFs()
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Entry")
        val registry = FileRegistry(fs)
        registry.detectChanges("/graph/journals") // register

        fs.deleteFile("/graph/journals/2026_04_13.md")

        val changes = registry.detectChanges("/graph/journals")
        assertTrue(changes.deletedPaths.contains("/graph/journals/2026_04_13.md"),
            "Deleted file must appear in deletedPaths")
    }

    @Test
    fun `deleted file is removed from registry so re-creation is treated as new`() = runTest {
        val fs = FakeFs()
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Entry")
        val registry = FileRegistry(fs)
        registry.detectChanges("/graph/journals")

        fs.deleteFile("/graph/journals/2026_04_13.md")
        registry.detectChanges("/graph/journals") // processes the deletion

        // Re-create the file
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Restored")
        val changes = registry.detectChanges("/graph/journals")

        assertEquals(1, changes.newFiles.size, "Re-created file should appear as new, not changed")
        assertTrue(changes.changedFiles.isEmpty())
    }

    // ── Scenario 9: Multiple files, only one changes ──────────────────────────

    @Test
    fun `only the actually changed file appears in changedFiles`() = runTest {
        val fs = FakeFs()
        fs.externalWrite("/graph/journals/2026_04_11.md", "- Day 11")
        fs.externalWrite("/graph/journals/2026_04_12.md", "- Day 12")
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Day 13")
        val registry = FileRegistry(fs)
        registry.detectChanges("/graph/journals")

        // Only today's journal gets an external write
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Day 13 MODIFIED BY REMOTE")

        val changes = registry.detectChanges("/graph/journals")
        assertEquals(1, changes.changedFiles.size, "Only the modified file should appear")
        assertEquals("2026_04_13.md", changes.changedFiles[0].entry.fileName)
    }

    // ── Scenario 10: Mutex prevents double-emit on concurrent calls ───────────

    @Test
    fun `concurrent detectChanges calls for same changed file report it exactly once`() = runTest {
        val fs = FakeFs()
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Original")
        val registry = FileRegistry(fs)
        registry.detectChanges("/graph/journals") // register baseline

        // Simulate git sync arriving on disk
        fs.externalWrite("/graph/journals/2026_04_13.md", "- Synced content")

        // Concurrent calls — one from 5-second polling, one from ContentObserver callback
        val results = (1..2).map {
            async { registry.detectChanges("/graph/journals") }
        }.awaitAll()

        // The Mutex serializes the two calls. The first sees the change and updates
        // contentHashes; the second sees no change (same hash now stored).
        val totalChanges = results.sumOf { it.changedFiles.size }
        assertEquals(1, totalChanges,
            "Concurrent calls must not double-report the same external change")
    }

    @Test
    fun `concurrent detectChanges calls for new file report it exactly once`() = runTest {
        val fs = FakeFs()
        val registry = FileRegistry(fs)

        // New file lands on disk (e.g. git pull brings in a journal from another device)
        fs.externalWrite("/graph/journals/2026_04_13.md", "- New entry from other device")

        val results = (1..2).map {
            async { registry.detectChanges("/graph/journals") }
        }.awaitAll()

        val totalNew = results.sumOf { it.newFiles.size }
        assertEquals(1, totalNew,
            "Concurrent calls must not report the same new file twice")
    }
}
