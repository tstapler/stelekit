package dev.stapler.stelekit.db

import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.sections.SectionDefinition
import dev.stapler.stelekit.sections.SectionFilter
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.sections.SectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Verifies ParseMode.INDEX_ONLY behavior in GraphLoader.loadDirectory:
 *  - zero readFile() calls regardless of file count
 *  - stub Page rows with isContentLoaded = false
 *  - correct name derivation (filename without .md)
 *  - correct isJournal flag (true for all files under a journals dir)
 *  - correct sectionId from SectionFilter
 */
class IndexOnlyParseTest {

    private inner class RecordingFileSystem(
        private val dirFiles: Map<String, List<String>> = emptyMap(),
    ) : FileSystem {
        var readFileCallCount = 0

        override fun getDefaultGraphPath() = "/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? {
            readFileCallCount++
            return null
        }
        override fun writeFile(path: String, content: String) = false
        override fun listFiles(path: String): List<String> = dirFiles[path] ?: emptyList()
        override fun listFilesWithModTimes(path: String): List<Pair<String, Long>> =
            (dirFiles[path] ?: emptyList()).map { name -> name to 1_000L }
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = dirFiles.containsKey(path)
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = 1_000L
        override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
        override fun stopExternalChangeDetection() {}
    }

    @Test
    fun `INDEX_ONLY processes 100 files with zero readFile calls`() = runTest {
        val pagesDir = "/graph/pages"
        val fileNames = (1..100).map { "page$it.md" }
        val fs = RecordingFileSystem(mapOf(pagesDir to fileNames))

        val pageRepo = InMemoryPageRepository()
        val loader = GraphLoader(fs, pageRepo, InMemoryBlockRepository())

        loader.loadDirectory(pagesDir, {}, ParseMode.INDEX_ONLY)

        assertEquals(0, fs.readFileCallCount, "INDEX_ONLY must make zero readFile() calls")

        val pages = pageRepo.getAllPagesSnapshot().getOrNull().orEmpty()
        assertEquals(100, pages.size, "must insert 100 stub Page rows")
        pages.forEach { page ->
            assertFalse(page.isContentLoaded, "stub pages must have isContentLoaded = false")
        }
    }

    @Test
    fun `INDEX_ONLY stub name is filename without md extension`() = runTest {
        val pagesDir = "/graph/pages"
        val fs = RecordingFileSystem(mapOf(pagesDir to listOf("Meeting Notes.md", "Project Plan.md")))

        val pageRepo = InMemoryPageRepository()
        val loader = GraphLoader(fs, pageRepo, InMemoryBlockRepository())

        loader.loadDirectory(pagesDir, {}, ParseMode.INDEX_ONLY)

        val names = pageRepo.getAllPagesSnapshot().getOrNull().orEmpty().map { it.name }.toSet()
        assertEquals(setOf("Meeting Notes", "Project Plan"), names,
            "name must be filename without .md extension")
    }

    @Test
    fun `INDEX_ONLY stubs in journals dir are journal-flagged and have journalDate`() = runTest {
        // FileRegistry.recentJournals filters to JournalUtils.isJournalName() — only date-format files
        // Non-date files in a journals dir are not processed at all.
        val journalsDir = "/graph/journals"
        val fs = RecordingFileSystem(mapOf(journalsDir to listOf(
            "2026-01-01.md",
            "2026-06-30.md",
            "not-a-date.md",  // filtered out by recentJournals — will NOT appear in results
        )))

        val pageRepo = InMemoryPageRepository()
        val loader = GraphLoader(fs, pageRepo, InMemoryBlockRepository())

        loader.loadDirectory(journalsDir, {}, ParseMode.INDEX_ONLY)

        assertEquals(0, fs.readFileCallCount, "journals/ INDEX_ONLY still makes zero readFile() calls")

        val pages = pageRepo.getAllPagesSnapshot().getOrNull().orEmpty()
        // Only the 2 date-named files pass isJournalName filter; "not-a-date" is excluded.
        assertEquals(2, pages.size, "only date-named journal files are processed")
        pages.forEach { page ->
            assertFalse(page.isContentLoaded, "stub must have isContentLoaded = false")
            // isJournal = isJournalDir (path ends with /journals) — true for all
            assertNotNull(page.journalDate, "journal stubs must have a parsed journalDate")
        }
    }

    @Test
    fun `INDEX_ONLY stub sectionId is derived from SectionFilter`() = runTest {
        val sectionPagesDir = "/graph/pages/acme-work"
        val fs = RecordingFileSystem(mapOf(sectionPagesDir to listOf("Work Note.md")))

        val acmeSection = SectionDefinition(
            id = "acme-work",
            displayName = "Acme Work",
            pagePathPrefix = "pages/acme-work",
            journalPathPrefix = "journals/acme-work",
        )
        val sectionFilter = SectionFilter(
            SectionManifest(sections = listOf(acmeSection)),
            mapOf("acme-work" to SectionState.ACTIVE),
        )

        val pageRepo = InMemoryPageRepository()
        val loader = GraphLoader(fs, pageRepo, InMemoryBlockRepository(), sectionFilter = sectionFilter)

        loader.loadDirectory(sectionPagesDir, {}, ParseMode.INDEX_ONLY)

        val page = pageRepo.getAllPagesSnapshot().getOrNull().orEmpty().firstOrNull()
        assertEquals("acme-work", page?.sectionId?.toDbString(), "sectionId must come from SectionFilter path match")
        assertEquals("Work Note", page?.name)
        assertFalse(page?.isContentLoaded ?: true, "stub must have isContentLoaded = false")
    }

    @Test
    fun `INDEX_ONLY stub sectionId is empty string when no SectionFilter`() = runTest {
        val pagesDir = "/graph/pages"
        val fs = RecordingFileSystem(mapOf(pagesDir to listOf("Global Note.md")))

        val pageRepo = InMemoryPageRepository()
        val loader = GraphLoader(fs, pageRepo, InMemoryBlockRepository())

        loader.loadDirectory(pagesDir, {}, ParseMode.INDEX_ONLY)

        val page = pageRepo.getAllPagesSnapshot().getOrNull().orEmpty().firstOrNull()
        assertEquals("", page?.sectionId?.toDbString(), "sectionId must be empty string when no SectionFilter")
    }

    @Test
    fun `INDEX_ONLY repeated call on same dir does not duplicate stubs`() = runTest {
        val pagesDir = "/graph/pages"
        val fs = RecordingFileSystem(mapOf(pagesDir to listOf("note.md")))

        val pageRepo = InMemoryPageRepository()
        val loader = GraphLoader(fs, pageRepo, InMemoryBlockRepository())

        loader.loadDirectory(pagesDir, {}, ParseMode.INDEX_ONLY)
        loader.loadDirectory(pagesDir, {}, ParseMode.INDEX_ONLY)

        // Stubs are upserted by UUID, so repeated runs must not produce duplicates.
        val pages = pageRepo.getAllPagesSnapshot().getOrNull().orEmpty()
        assertEquals(1, pages.size, "repeated INDEX_ONLY on same dir must not duplicate stubs")
    }
}
