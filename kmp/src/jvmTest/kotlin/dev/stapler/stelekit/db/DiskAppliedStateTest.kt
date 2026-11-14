// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Invariant under test: the registry's hash/mtime say "last OBSERVED on disk", so the loader must
 * separately track "disk content the DB has not applied yet". An observed-but-unapplied file must
 * (a) be retried, (b) be reported by the pre-write guard so a stale page cannot overwrite it.
 *
 * Origin: a watcher-detected journal edit whose DB write failed (`SQLITE_BUSY_SNAPSHOT`) advanced
 * the baseline anyway; the guard then saw "disk == registry" and allowed a stale write-back.
 */
class DiskAppliedStateTest {

    private class MapFs(val files: MutableMap<String, String>, val mtimes: MutableMap<String, Long> = mutableMapOf()) : FileSystem {
        override fun getDefaultGraphPath() = "/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = files[path]
        override fun writeFile(path: String, content: String): Boolean { files[path] = content; mtimes[path] = (mtimes[path] ?: 0L) + 1000L; return true }
        override fun listFiles(path: String) = files.keys.filter { it.startsWith("$path/") }.map { it.substringAfterLast("/") }
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = files.containsKey(path)
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String): Boolean { files.remove(path); return true }
        override fun pickDirectory() = null
        override fun getLastModifiedTime(path: String): Long? = if (path in files) mtimes[path] ?: 1L else null
        fun externalWrite(path: String, content: String) { writeFile(path, content) }
    }

    @OptIn(DirectRepositoryWrite::class)
    private class FlakyPageRepository(private val delegate: InMemoryPageRepository, var failing: Boolean) : PageRepository by delegate {
        @OptIn(DirectRepositoryWrite::class)
        override suspend fun savePage(page: Page): Either<DomainError, Unit> =
            if (failing) DomainError.DatabaseError.WriteFailed("SQLITE_BUSY_SNAPSHOT").left() else delegate.savePage(page)
    }

    private val dir = "/graph/journals"
    private val path = "$dir/2026_11_13.md"

    // ── FileRegistry ─────────────────────────────────────────────────────────

    @Test
    fun `a detected change stays unapplied until the DB confirms it`() = runTest {
        val fs = MapFs(mutableMapOf(path to "- old"))
        val registry = FileRegistry(fs)
        registry.scanDirectory(dir)
        fs.externalWrite(path, "- new")

        val changes = registry.detectChanges(dir)

        assertEquals(1, changes.changedFiles.size)
        assertTrue(registry.isUnapplied(FilePath(path)), "observed-on-disk is not the same as applied-to-DB")
        registry.markApplied(FilePath(path))
        assertFalse(registry.isUnapplied(FilePath(path)))
    }

    @Test
    fun `a failed apply is re-reported on the next poll even though the baseline advanced`() = runTest {
        val fs = MapFs(mutableMapOf(path to "- old"))
        val registry = FileRegistry(fs)
        registry.scanDirectory(dir)
        fs.externalWrite(path, "- new")
        registry.detectChanges(dir)                      // baseline now == "- new"
        registry.scheduleRetry(FilePath(path))           // the apply failed

        val retried = registry.detectChanges(dir)

        assertEquals(listOf(path), retried.changedFiles.map { it.entry.filePath })
        assertEquals("- new", retried.changedFiles.single().content)
    }

    @Test
    fun `retries are bounded so a permanently failing file cannot spin the watcher`() = runTest {
        val fs = MapFs(mutableMapOf(path to "- old"))
        val registry = FileRegistry(fs)
        registry.scanDirectory(dir)
        fs.externalWrite(path, "- new")
        registry.detectChanges(dir)

        var reports = 0
        repeat(FileRegistry.MAX_APPLY_RETRIES + 5) {
            registry.scheduleRetry(FilePath(path))
            reports += registry.detectChanges(dir).changedFiles.size
        }

        assertEquals(FileRegistry.MAX_APPLY_RETRIES, reports)
        assertTrue(registry.isUnapplied(FilePath(path)), "after giving up the guard must still protect the file")
    }

    // ── GraphLoader: apply bookkeeping + guard ───────────────────────────────

    private fun loader(fs: FileSystem, pageRepo: PageRepository) =
        GraphLoader(fileSystem = fs, pageRepository = pageRepo, blockRepository = InMemoryBlockRepository())

    @Test
    fun `a failed DB write leaves the file flagged so a stale page cannot overwrite it`() = runTest {
        val fs = MapFs(mutableMapOf(path to "- merged from remote"))
        val pageRepo = FlakyPageRepository(InMemoryPageRepository(), failing = true)
        val loader = loader(fs, pageRepo)
        loader.fileRegistry.updateContentHash(path, "- merged from remote".hashCode())  // baseline == disk

        loader.reloadFiles(listOf(FilePath(path)))

        assertTrue(loader.isDiskAheadOfDb(path, "- merged from remote"),
            "hash matches the registry, but the DB never applied it: the guard must still fire")
    }

    @Test
    fun `a successful reload clears the flag`() = runTest {
        val fs = MapFs(mutableMapOf(path to "- merged from remote"))
        val pageRepo = FlakyPageRepository(InMemoryPageRepository(), failing = false)
        val loader = loader(fs, pageRepo)
        loader.fileRegistry.updateContentHash(path, "- merged from remote".hashCode())
        loader.beginGitMerge(listOf(path))
        assertTrue(loader.isDiskAheadOfDb(path, "- merged from remote"), "a git-touched file is unapplied until reloaded")

        loader.reloadFiles(listOf(FilePath(path)))
        loader.endGitMerge()

        assertFalse(loader.isDiskAheadOfDb(path, "- merged from remote"))
    }

    @Test
    fun `acknowledging disk content is the explicit way to resolve the guard`() = runTest {
        val fs = MapFs(mutableMapOf(path to "- disk"))
        val loader = loader(fs, FlakyPageRepository(InMemoryPageRepository(), failing = true))
        loader.fileRegistry.updateContentHash(path, "- disk".hashCode())
        loader.reloadFiles(listOf(FilePath(path)))
        assertTrue(loader.isDiskAheadOfDb(path, "- disk"))

        loader.acknowledgeDiskContent(path)

        assertFalse(loader.isDiskAheadOfDb(path, "- disk"))
    }

    @Test
    fun `the watcher retry applies a change whose first DB write failed`() = runTest {
        val fs = MapFs(mutableMapOf(path to "- old"))
        val pageRepo = FlakyPageRepository(InMemoryPageRepository(), failing = true)
        val loader = loader(fs, pageRepo)
        loader.fileRegistry.scanDirectory(dir)
        fs.externalWrite(path, "- new")

        val first = loader.fileRegistry.detectChanges(dir).changedFiles.single()
        loader.applyExternalFileChange(FilePath(first.entry.filePath), first.content)   // fails: DB busy
        pageRepo.failing = false
        val retry = loader.fileRegistry.detectChanges(dir).changedFiles.single()
        loader.applyExternalFileChange(FilePath(retry.entry.filePath), retry.content)

        assertFalse(loader.isDiskAheadOfDb(path, "- new"))
        assertTrue(loader.fileRegistry.detectChanges(dir).changedFiles.isEmpty(), "applied: nothing left to report")
    }
}
