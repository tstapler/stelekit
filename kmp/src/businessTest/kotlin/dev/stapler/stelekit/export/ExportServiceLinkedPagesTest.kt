package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Regression tests for [ExportService.exportPageWithLinks].
 *
 * U-EPL-01: linked page content appears in output
 * U-EPL-02: cycle (A→B→A) does not cause infinite loop; B appears once
 * U-EPL-03: empty linked pages are silently skipped
 */
class ExportServiceLinkedPagesTest {

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

    private fun block(
        uuid: String,
        content: String,
        pageUuid: String,
        level: Int = 0,
        position: Int = 0,
    ) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = content,
        level = level,
        position = position,
        createdAt = now,
        updatedAt = now,
    )

    private fun page(uuid: String, name: String) = Page(
        uuid = PageUuid(uuid),
        name = name,
        createdAt = now,
        updatedAt = now,
    )

    // ── U-EPL-01: linked page content appears in output ───────────────────────

    @Test
    fun uEPL01_linkedPage_contentAppearsInOutput() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        val pageA = page("page-a", "Page A")
        val pageB = page("page-b", "Page B")

        pageRepo.savePage(pageA)
        pageRepo.savePage(pageB)

        val blocksA = listOf(block("ba-1", "Hello from A. See [[Page B]]", "page-a"))
        val blocksB = listOf(block("bb-1", "Hello from B", "page-b"))

        blockRepo.saveBlock(blocksB.first())

        val service = makeService()
        val result = service.exportPageWithLinks(
            page = pageA,
            blocks = blocksA,
            formatId = "markdown",
            pageRepo = pageRepo,
            blockRepo = blockRepo,
        )

        assertTrue(result.isRight(), "Expected Right but got $result")
        val output = result.getOrNull()!!
        assertContains(output, "Hello from A")
        assertContains(output, "Hello from B")
    }

    // ── U-EPL-02: cycle (A→B→A) does not loop; returns Right with finite output ──

    @Test
    fun uEPL02_cycle_doesNotLoop_returnsFiniteOutput() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        val pageA = page("page-a", "Page A")
        val pageB = page("page-b", "Page B")

        pageRepo.savePage(pageA)
        pageRepo.savePage(pageB)

        // A links to B; B links back to A (cycle)
        val blocksA = listOf(block("ba-1", "A content [[Page B]]", "page-a"))
        val blocksB = listOf(block("bb-1", "B content [[Page A]]", "page-b"))

        blockRepo.saveBlock(blocksB.first())

        val service = makeService()
        val result = service.exportPageWithLinks(
            page = pageA,
            blocks = blocksA,
            formatId = "markdown",
            pageRepo = pageRepo,
            blockRepo = blockRepo,
        )

        // Should complete without infinite loop and return a finite Right value
        assertTrue(result.isRight(), "Expected Right but got $result")
        val output = result.getOrNull()!!
        assertContains(output, "A content")
        assertContains(output, "B content")
        // B is visited once (not recursed back to A), so output is finite
        assertTrue(output.length < 10_000, "Output should be finite (< 10KB), got ${output.length} chars")
        // B must appear exactly once — a second visit would indicate the cycle guard is broken
        val bOccurrences = output.split("B content").size - 1
        assertEquals(1, bOccurrences, "B content should appear exactly once; got $bOccurrences occurrences")
    }

    // ── U-EPL-03: empty linked pages are silently skipped ────────────────────

    @Test
    fun uEPL03_emptyLinkedPage_isSilentlySkipped() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        val pageA = page("page-a", "Page A")
        val pageC = page("page-c", "Page C") // exists in repo but has no blocks

        pageRepo.savePage(pageA)
        pageRepo.savePage(pageC)

        val blocksA = listOf(block("ba-1", "A links to [[Page C]]", "page-a"))
        // Page C has no blocks saved

        val service = makeService()
        val result = service.exportPageWithLinks(
            page = pageA,
            blocks = blocksA,
            formatId = "markdown",
            pageRepo = pageRepo,
            blockRepo = blockRepo,
        )

        assertTrue(result.isRight(), "Expected Right but got $result")
        val output = result.getOrNull()!!
        assertContains(output, "A links to")
        // Page C has no content — its heading should not appear
        assertFalse(output.contains("## Page C"), "Empty linked page heading should not appear in output")
    }
}
