package dev.stapler.stelekit.repository

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JournalServiceTest {

    private fun today(): LocalDate =
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    // ---- ensureTodayJournal: creation ----

    @Test
    fun ensureTodayJournal_creates_page_when_none_exists() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val service = JournalService(pageRepo, blockRepo)

        val page = service.ensureTodayJournal()

        assertEquals(today(), page.journalDate)
        assertTrue(page.isJournal)
        assertNotNull(page.uuid)

        // Verify persisted
        val fromDb = pageRepo.getJournalPageByDate(today()).first().getOrNull()
        assertNotNull(fromDb)
        assertEquals(page.uuid, fromDb.uuid)
    }

    @Test
    fun ensureTodayJournal_creates_initial_empty_block() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val service = JournalService(pageRepo, blockRepo)

        val page = service.ensureTodayJournal()

        val blocks = blockRepo.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
        assertEquals(1, blocks.size)
        assertEquals("", blocks[0].content)
        assertEquals(page.uuid, blocks[0].pageUuid)
    }

    // ---- ensureTodayJournal: idempotency ----

    @Test
    fun ensureTodayJournal_returns_existing_page_when_found_by_date() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val service = JournalService(pageRepo, blockRepo)

        val existing = Page(
            uuid = "existing-uuid",
            name = today().toString(),
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
            isJournal = true,
            journalDate = today()
        )
        pageRepo.savePage(existing)

        val result = service.ensureTodayJournal()
        assertEquals("existing-uuid", result.uuid)

        // Should NOT create additional pages
        val allPages = pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        assertEquals(1, allPages.size)
    }

    @Test
    fun ensureTodayJournal_returns_existing_page_when_found_by_underscore_name() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val service = JournalService(pageRepo, blockRepo)

        // Simulate a page loaded from disk with underscore format
        val underscoreName = today().toString().replace('-', '_')
        val existing = Page(
            uuid = "disk-page-uuid",
            name = underscoreName,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
            isJournal = true,
            journalDate = today()
        )
        pageRepo.savePage(existing)

        val result = service.ensureTodayJournal()
        assertEquals("disk-page-uuid", result.uuid)
    }

    // ---- ensureTodayJournal: dedup/merge ----

    @Test
    fun ensureTodayJournal_merges_duplicates_keeping_page_with_content() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val service = JournalService(pageRepo, blockRepo)
        val now = Clock.System.now()

        // Page A: empty (created by earlier ensureTodayJournal)
        val pageA = Page(
            uuid = "page-a",
            name = today().toString(),
            createdAt = now, updatedAt = now,
            isJournal = true, journalDate = today()
        )
        pageRepo.savePage(pageA)
        blockRepo.saveBlock(Block(
            uuid = "block-a",
            pageUuid = "page-a",
            content = "",
            position = 0,
            createdAt = now, updatedAt = now
        ))

        // Page B: has real content (loaded from disk)
        val underscoreName = today().toString().replace('-', '_')
        val pageB = Page(
            uuid = "page-b",
            name = underscoreName,
            createdAt = now, updatedAt = now,
            isJournal = true, journalDate = today()
        )
        pageRepo.savePage(pageB)
        blockRepo.saveBlock(Block(
            uuid = "block-b",
            pageUuid = "page-b",
            content = "Real journal content from disk",
            position = 0,
            createdAt = now, updatedAt = now
        ))

        val result = service.ensureTodayJournal()

        // Should keep page B (has content)
        assertEquals("page-b", result.uuid)

        // Page A should be deleted
        val pageAStill = pageRepo.getPageByUuid("page-a").first().getOrNull()
        assertEquals(null, pageAStill)

        // Only one page should remain
        val allPages = pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        val todayPages = allPages.filter { it.journalDate == today() }
        assertEquals(1, todayPages.size)
    }

    @Test
    fun ensureTodayJournal_merge_reparents_non_empty_blocks() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val service = JournalService(pageRepo, blockRepo)
        val now = Clock.System.now()

        // Page A has a non-empty block
        val pageA = Page(
            uuid = "page-a", name = today().toString(),
            createdAt = now, updatedAt = now,
            isJournal = true, journalDate = today()
        )
        pageRepo.savePage(pageA)
        blockRepo.saveBlock(Block(
            uuid = "block-a-content", pageUuid = "page-a",
            content = "Important note", position = 0,
            createdAt = now, updatedAt = now
        ))

        // Page B also has content (loaded from disk) — will be the keeper
        val underscoreName = today().toString().replace('-', '_')
        val pageB = Page(
            uuid = "page-b", name = underscoreName,
            createdAt = now, updatedAt = now,
            isJournal = true, journalDate = today()
        )
        pageRepo.savePage(pageB)
        blockRepo.saveBlock(Block(
            uuid = "block-b-content", pageUuid = "page-b",
            content = "Disk content", position = 0,
            createdAt = now, updatedAt = now
        ))

        val result = service.ensureTodayJournal()

        // Block from page A should be re-parented to keeper
        val keeperBlocks = blockRepo.getBlocksForPage(result.uuid).first().getOrNull() ?: emptyList()
        val reparented = keeperBlocks.find { it.uuid == "block-a-content" }
        assertNotNull(reparented, "Non-empty block from loser page should be re-parented")
        assertEquals(result.uuid, reparented.pageUuid)
    }

    // ---- JournalDateResolver ----

    @Test
    fun getPageByJournalDate_finds_by_date_column() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val service = JournalService(pageRepo, blockRepo)

        val date = LocalDate(2026, 3, 15)
        val page = Page(
            uuid = "test-uuid", name = "2026_03_15",
            createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
            isJournal = true, journalDate = date
        )
        pageRepo.savePage(page)

        val result = service.getPageByJournalDate(date)
        assertNotNull(result)
        assertEquals("test-uuid", result.uuid)
    }

    @Test
    fun getPageByJournalDate_falls_back_to_name_lookup() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val service = JournalService(pageRepo, blockRepo)

        // Page exists by name only (no journalDate set — edge case)
        val page = Page(
            uuid = "name-only", name = "2026-03-15",
            createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
            isJournal = false, journalDate = null
        )
        pageRepo.savePage(page)

        val result = service.getPageByJournalDate(LocalDate(2026, 3, 15))
        assertNotNull(result)
        assertEquals("name-only", result.uuid)
    }
}
