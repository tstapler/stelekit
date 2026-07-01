// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import dev.stapler.stelekit.sections.SectionDefinition
import dev.stapler.stelekit.sections.SectionFilter
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.sections.SectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Story 6.3 — Split-journal tests.
 *
 * Verifies that parsing `journals/2026-06-29.md` and `journals/acme-work/2026-06-29.md`
 * produces **two distinct Page rows** with `sectionId = ""` and `"acme-work"` respectively,
 * and that [SqlDelightPageRepository.getJournalPageByDateAndSection] queries each correctly.
 *
 * Also verifies [GraphLoader.createSectionJournalPage] creates the section journal with the
 * correct sectionId field.
 */
class SplitJournalTest {

    private val date = LocalDate(2026, 6, 29)

    private val acmeDef = SectionDefinition(
        id = "acme-work",
        displayName = "Acme Work",
        pagePathPrefix = "pages/acme-work",
        journalPathPrefix = "journals/acme-work",
    )
    private val sectionFilter = SectionFilter(
        manifest = SectionManifest(sections = listOf(acmeDef)),
        sectionStates = mapOf("acme-work" to SectionState.ACTIVE),
    )

    /** In-memory FileSystem backed by a map. Used by createSectionJournalPage to write/read files. */
    private class MapFileSystem : FileSystem {
        private val files = mutableMapOf<String, String>()
        private val dirs = mutableSetOf<String>()

        override fun getDefaultGraphPath() = ""
        override fun expandTilde(path: String) = path
        override fun readFile(path: String) = files[path]
        override fun writeFile(path: String, content: String): Boolean { files[path] = content; return true }
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = files.containsKey(path)
        override fun directoryExists(path: String) = dirs.contains(path)
        override fun createDirectory(path: String): Boolean { dirs.add(path); return true }
        override fun deleteFile(path: String): Boolean = files.remove(path) != null
        override fun pickDirectory() = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private data class Repos(
        val pageRepo: SqlDelightPageRepository,
        val blockRepo: SqlDelightBlockRepository,
        val loader: GraphLoader,
    )

    private fun build(): Repos {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        val pageRepo = SqlDelightPageRepository(database)
        val blockRepo = SqlDelightBlockRepository(database)
        val journalService = JournalService(pageRepo, blockRepo)
        val loader = GraphLoader(
            fileSystem = MapFileSystem(),
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            journalDateResolver = journalService,
            sectionFilter = sectionFilter,
        )
        loader.setGraphPath("/graph")
        return Repos(pageRepo, blockRepo, loader)
    }

    // ── TC-6.3-A: two distinct rows for same date, different sections ──────────

    @Test
    fun `global and section journals for same date are stored as distinct rows`() = runBlocking {
        val (pageRepo, _, loader) = build()

        loader.parseAndSavePage(FilePath("/graph/journals/2026-06-29.md"), "", ParseMode.FULL)
        loader.parseAndSavePage(FilePath("/graph/journals/acme-work/2026-06-29.md"), "", ParseMode.FULL)

        val global = pageRepo.getJournalPageByDateAndSection(date, "").first().getOrNull()
        val section = pageRepo.getJournalPageByDateAndSection(date, "acme-work").first().getOrNull()

        assertNotNull(global, "Global journal must exist")
        assertNotNull(section, "Section journal must exist")
        assert(global.uuid != section.uuid) { "Global and section journals must have distinct UUIDs" }
    }

    // ── TC-6.3-B: global journal sectionId is empty string ────────────────────

    @Test
    fun `global journal page has empty sectionId`() = runBlocking {
        val (pageRepo, _, loader) = build()

        loader.parseAndSavePage(FilePath("/graph/journals/2026-06-29.md"), "", ParseMode.FULL)

        val page = pageRepo.getJournalPageByDateAndSection(date, "").first().getOrNull()
        assertNotNull(page)
        assertEquals("", page.sectionId, "Global journal must have sectionId = \"\"")
    }

    // ── TC-6.3-C: section journal sectionId matches section id ────────────────

    @Test
    fun `section journal page has sectionId equal to section id`() = runBlocking {
        val (pageRepo, _, loader) = build()

        loader.parseAndSavePage(FilePath("/graph/journals/acme-work/2026-06-29.md"), "", ParseMode.FULL)

        val page = pageRepo.getJournalPageByDateAndSection(date, "acme-work").first().getOrNull()
        assertNotNull(page)
        assertEquals("acme-work", page.sectionId, "Section journal must have sectionId = \"acme-work\"")
    }

    // ── TC-6.3-D: section-aware lookup returns null for wrong section ──────────

    @Test
    fun `getJournalPageByDateAndSection returns null for unknown section`() = runBlocking {
        val (pageRepo, _, loader) = build()

        loader.parseAndSavePage(FilePath("/graph/journals/2026-06-29.md"), "", ParseMode.FULL)
        loader.parseAndSavePage(FilePath("/graph/journals/acme-work/2026-06-29.md"), "", ParseMode.FULL)

        assertNull(
            pageRepo.getJournalPageByDateAndSection(date, "no-such-section").first().getOrNull(),
            "Query for a section that doesn't exist must return null",
        )
    }

    // ── TC-6.3-E: createSectionJournalPage creates correct page ──────────────

    @Test
    fun `createSectionJournalPage stores section journal with correct sectionId and journalDate`() = runBlocking {
        val (_, _, loader) = build()

        val result = loader.createSectionJournalPage("acme-work", date)

        assert(result.isRight()) { "createSectionJournalPage must succeed, got: $result" }
        val page = result.getOrNull()!!
        assertEquals("acme-work", page.sectionId, "Created page must have sectionId = \"acme-work\"")
        assertEquals(date, page.journalDate, "Created page must have correct journalDate")
        assert(page.isJournal) { "Created page must be flagged as a journal" }
    }
}
