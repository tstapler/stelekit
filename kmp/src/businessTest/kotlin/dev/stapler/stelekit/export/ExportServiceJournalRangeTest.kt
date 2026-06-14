package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Regression tests for [ExportService.exportJournalRange].
 *
 * U-EJR-01: pages within range appear in output, pages outside are excluded
 * U-EJR-02: empty range returns Left
 */
class ExportServiceJournalRangeTest {

    private val now = Clock.System.now()

    private val noOpClipboard = object : ClipboardProvider {
        override fun writeText(text: String) {}
        override fun writeHtml(html: String, plainFallback: String) {}
    }

    private fun makeService() = ExportService(
        exporters = listOf(MarkdownExporter()),
        clipboard = noOpClipboard,
        blockRepository = InMemoryBlockRepository(),
    )

    private fun block(uuid: String, content: String, pageUuid: String) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = content,
        level = 0,
        position = 0,
        createdAt = now,
        updatedAt = now,
    )

    private fun journalPage(uuid: String, date: LocalDate) = Page(
        uuid = PageUuid(uuid),
        name = date.toString(),
        isJournal = true,
        journalDate = date,
        createdAt = now,
        updatedAt = now,
    )

    // ── U-EJR-01: pages in range appear; page outside range is excluded ───────

    @Test
    fun uEJR01_pagesInRange_appear_pageOutsideRange_excluded() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        val jan1 = journalPage("p-jan1", LocalDate(2026, 1, 1))
        val jan2 = journalPage("p-jan2", LocalDate(2026, 1, 2))
        val jan3 = journalPage("p-jan3", LocalDate(2026, 1, 3)) // outside range

        pageRepo.savePage(jan1)
        pageRepo.savePage(jan2)
        pageRepo.savePage(jan3)

        blockRepo.saveBlock(block("b1", "Jan 1 entry", "p-jan1"))
        blockRepo.saveBlock(block("b2", "Jan 2 entry", "p-jan2"))
        blockRepo.saveBlock(block("b3", "Jan 3 entry", "p-jan3"))

        val service = makeService()
        val result = service.exportJournalRange(
            from = LocalDate(2026, 1, 1),
            to = LocalDate(2026, 1, 2),
            formatId = "markdown",
            pageRepo = pageRepo,
            blockRepo = blockRepo,
        )

        assertTrue(result.isRight(), "Expected Right but got $result")
        val output = result.getOrNull()!!
        assertContains(output, "Jan 1 entry")
        assertContains(output, "Jan 2 entry")
        assertFalse(output.contains("Jan 3 entry"), "Jan 3 should be excluded from the output")
    }

    // ── U-EJR-02: empty range returns Left ───────────────────────────────────

    @Test
    fun uEJR02_emptyRange_returnsLeft() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        // No journal pages added

        val service = makeService()
        val result = service.exportJournalRange(
            from = LocalDate(2026, 6, 1),
            to = LocalDate(2026, 6, 7),
            formatId = "markdown",
            pageRepo = pageRepo,
            blockRepo = blockRepo,
        )

        assertTrue(result.isLeft(), "Expected Left for empty range but got Right")
    }

    // ── U-EJR-04: batch with null journal date terminates without hanging ────

    @Test
    fun uEJR04_batchWithNullJournalDate_terminatesWithoutHanging() = runTest {
        // A journal page with is_journal=true but no journal_date (corrupted/migrated data).
        // Before the fix, the drain loop would never break on oldest==null — infinite scan.
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        val badPage = Page(
            uuid = PageUuid("p-null-date"),
            name = "corrupted-journal",
            isJournal = true,
            journalDate = null, // triggers oldest==null early-exit guard
            createdAt = now,
            updatedAt = now,
        )
        pageRepo.savePage(badPage)
        blockRepo.saveBlock(block("b-null", "some content", "p-null-date"))

        val service = makeService()
        // The call must terminate (not hang) even when the page has no journal_date.
        // The result is Left because no pages fall within a valid date range.
        val result = service.exportJournalRange(
            from = LocalDate(2026, 1, 1),
            to = LocalDate(2026, 1, 7),
            formatId = "markdown",
            pageRepo = pageRepo,
            blockRepo = blockRepo,
        )
        assertTrue(result.isLeft(), "Expected Left when only null-date journal pages exist, got Right")
    }

    // ── U-EJR-03: pages in range with no blocks → second Left path ───────────

    @Test
    fun uEJR03_pagesInRangeWithNoContent_returnsLeft() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        // Journal page exists in range but has zero blocks saved
        val jan5 = journalPage("p-jan5", LocalDate(2026, 1, 5))
        pageRepo.savePage(jan5)
        // no blocks saved for jan5

        val service = makeService()
        val result = service.exportJournalRange(
            from = LocalDate(2026, 1, 1),
            to = LocalDate(2026, 1, 7),
            formatId = "markdown",
            pageRepo = pageRepo,
            blockRepo = blockRepo,
        )

        assertTrue(result.isLeft(), "Expected Left when all in-range pages have no blocks but got Right")
    }
}
