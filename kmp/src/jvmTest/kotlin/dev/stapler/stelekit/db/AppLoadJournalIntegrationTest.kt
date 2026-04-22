package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.JournalService
import kotlinx.coroutines.async
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

/**
 * Integration tests for the app-load sequence:
 *
 *   GraphLoader.loadGraphProgressive() → JournalService.ensureTodayJournal()
 *
 * Covers permutations of what exists on disk and in the DB to verify that journal
 * auto-creation never loses data and never creates duplicates.
 */
class AppLoadJournalIntegrationTest {

    private fun today(): LocalDate =
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    /** Minimal in-memory FileSystem backed by a path → content map. */
    private fun fakeFs(files: MutableMap<String, String> = mutableMapOf()): FileSystem =
        object : FileSystem {
            override fun getDefaultGraphPath() = "/graph"
            override fun expandTilde(path: String) = path
            override fun readFile(path: String) = files[path]
            override fun writeFile(path: String, content: String) = true
            override fun listFiles(path: String) =
                files.keys.filter { it.startsWith(path) }.map { it.substringAfterLast("/") }
            override fun listDirectories(path: String) = emptyList<String>()
            override fun fileExists(path: String) = files.containsKey(path)
            override fun directoryExists(path: String) = true
            override fun createDirectory(path: String) = true
            override fun deleteFile(path: String) = true
            override fun pickDirectory() = null
            override fun getLastModifiedTime(path: String): Long? = null
        }

    private data class Harness(
        val pageRepo: InMemoryPageRepository,
        val blockRepo: InMemoryBlockRepository,
        val graphLoader: GraphLoader,
        val journalService: JournalService
    )

    private fun harness(files: MutableMap<String, String> = mutableMapOf()): Harness {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val journalService = JournalService(pageRepo, blockRepo)
        val graphLoader = GraphLoader(fakeFs(files), pageRepo, blockRepo, journalService)
        return Harness(pageRepo, blockRepo, graphLoader, journalService)
    }

    /** Mirrors what StelekitViewModel.loadGraph() does: progressive load then ensureTodayJournal. */
    private suspend fun Harness.appLoad() {
        graphLoader.loadGraphProgressive(
            graphPath = "/graph",
            immediateJournalCount = 10,
            onProgress = {},
            onPhase1Complete = {},
            onFullyLoaded = {}
        )
        journalService.ensureTodayJournal()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 1: Empty graph (first launch)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `empty graph on first launch creates today journal`() = runTest {
        val h = harness()

        h.appLoad()

        val journal = h.pageRepo.getJournalPageByDate(today()).first().getOrNull()
        assertNotNull(journal, "Today's journal should be created on first launch")
        assertTrue(journal.isJournal)
        assertEquals(today(), journal.journalDate)
    }

    @Test
    fun `empty graph on first launch creates exactly one journal page`() = runTest {
        val h = harness()

        h.appLoad()

        val allPages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        val todayPages = allPages.filter { it.journalDate == today() }
        assertEquals(1, todayPages.size, "Exactly one today journal, got: ${todayPages.map { it.name }}")
    }

    @Test
    fun `empty graph on first launch creates one empty block in journal`() = runTest {
        val h = harness()

        h.appLoad()

        val journal = h.pageRepo.getJournalPageByDate(today()).first().getOrNull()!!
        val blocks = h.blockRepo.getBlocksForPage(journal.uuid).first().getOrNull() ?: emptyList()
        assertEquals(1, blocks.size, "Should create exactly one empty initial block")
        assertEquals("", blocks[0].content)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 2: Today's journal on disk (hyphen format YYYY-MM-DD)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `today journal on disk in hyphen format - no duplicate page created`() = runTest {
        val todayHyphen = today().toString()
        val h = harness(mutableMapOf("/graph/journals/$todayHyphen.md" to "- Morning note"))

        h.appLoad()

        val allPages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        val todayPages = allPages.filter { it.journalDate == today() || it.name == todayHyphen }
        assertEquals(1, todayPages.size, "No duplicate, got: ${todayPages.map { it.name }}")
    }

    @Test
    fun `today journal on disk in hyphen format - content not wiped by ensureTodayJournal`() = runTest {
        val todayHyphen = today().toString()
        val h = harness(mutableMapOf("/graph/journals/$todayHyphen.md" to "- Morning note"))

        h.appLoad()

        val allPages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        val journal = allPages.first { it.journalDate == today() || it.name == todayHyphen }
        val blocks = h.blockRepo.getBlocksForPage(journal.uuid).first().getOrNull() ?: emptyList()
        assertTrue(blocks.isNotEmpty(), "Disk content should not be wiped — blocks expected")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 3: Today's journal on disk (underscore format YYYY_MM_DD)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `today journal on disk in underscore format - no duplicate page created`() = runTest {
        val todayUnderscore = today().toString().replace('-', '_')
        val h = harness(mutableMapOf("/graph/journals/$todayUnderscore.md" to "- Morning note"))

        h.appLoad()

        val allPages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        val todayPages = allPages.filter { it.journalDate == today() || it.name == todayUnderscore }
        assertEquals(1, todayPages.size, "No duplicate, got: ${todayPages.map { it.name }}")
    }

    @Test
    fun `today journal on disk in underscore format - content not wiped by ensureTodayJournal`() = runTest {
        val todayUnderscore = today().toString().replace('-', '_')
        val h = harness(mutableMapOf("/graph/journals/$todayUnderscore.md" to "- Underscore entry"))

        h.appLoad()

        val allPages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        val journal = allPages.first { it.journalDate == today() || it.name == todayUnderscore }
        val blocks = h.blockRepo.getBlocksForPage(journal.uuid).first().getOrNull() ?: emptyList()
        assertTrue(blocks.isNotEmpty(), "Disk content should not be wiped — blocks expected")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 4: Today's journal in DB with real content (already loaded)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `today journal already loaded with content - ensureTodayJournal returns same page`() = runTest {
        val now = Clock.System.now()
        val h = harness()

        val existingPage = Page(
            uuid = "existing-uuid",
            name = today().toString().replace('-', '_'),
            createdAt = now, updatedAt = now,
            isJournal = true, journalDate = today()
        )
        h.pageRepo.savePage(existingPage)
        h.blockRepo.saveBlock(Block(uuid = "b1", pageUuid = "existing-uuid", content = "Important note", position = 0, createdAt = now, updatedAt = now))
        h.blockRepo.saveBlock(Block(uuid = "b2", pageUuid = "existing-uuid", content = "Follow-up item", position = 1, createdAt = now, updatedAt = now))

        val result = h.journalService.ensureTodayJournal()

        assertEquals("existing-uuid", result.uuid, "Should return the existing page")
    }

    @Test
    fun `today journal already loaded with content - content blocks not deleted`() = runTest {
        val now = Clock.System.now()
        val h = harness()

        h.pageRepo.savePage(Page(uuid = "existing-uuid", name = today().toString().replace('-', '_'), createdAt = now, updatedAt = now, isJournal = true, journalDate = today()))
        h.blockRepo.saveBlock(Block(uuid = "b1", pageUuid = "existing-uuid", content = "Important note", position = 0, createdAt = now, updatedAt = now))
        h.blockRepo.saveBlock(Block(uuid = "b2", pageUuid = "existing-uuid", content = "Follow-up item", position = 1, createdAt = now, updatedAt = now))

        h.journalService.ensureTodayJournal()

        val blocks = h.blockRepo.getBlocksForPage("existing-uuid").first().getOrNull() ?: emptyList()
        assertEquals(2, blocks.size, "Both content blocks must survive")
        assertTrue(blocks.any { it.content == "Important note" })
        assertTrue(blocks.any { it.content == "Follow-up item" })
    }

    @Test
    fun `today journal with deeply nested blocks - all nested blocks preserved`() = runTest {
        val now = Clock.System.now()
        val h = harness()

        h.pageRepo.savePage(Page(uuid = "nested-uuid", name = today().toString(), createdAt = now, updatedAt = now, isJournal = true, journalDate = today()))
        h.blockRepo.saveBlock(Block(uuid = "root", pageUuid = "nested-uuid", content = "Root block", position = 0, level = 0, createdAt = now, updatedAt = now))
        h.blockRepo.saveBlock(Block(uuid = "child1", pageUuid = "nested-uuid", parentUuid = "root", content = "Child 1", position = 0, level = 1, createdAt = now, updatedAt = now))
        h.blockRepo.saveBlock(Block(uuid = "child2", pageUuid = "nested-uuid", parentUuid = "root", content = "Child 2", position = 1, level = 1, createdAt = now, updatedAt = now))
        h.blockRepo.saveBlock(Block(uuid = "grandchild", pageUuid = "nested-uuid", parentUuid = "child1", content = "Grandchild", position = 0, level = 2, createdAt = now, updatedAt = now))

        h.journalService.ensureTodayJournal()

        val blocks = h.blockRepo.getBlocksForPage("nested-uuid").first().getOrNull() ?: emptyList()
        assertEquals(4, blocks.size, "All nested blocks must survive ensureTodayJournal")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 5: Only past journals on disk — today does not exist yet
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `only past journals on disk - today journal created, past journals unaffected`() = runTest {
        val h = harness(mutableMapOf(
            "/graph/journals/2026_01_01.md" to "- New year",
            "/graph/journals/2026_02_14.md" to "- Valentine",
            "/graph/journals/2026_03_15.md" to "- March notes"
        ))

        h.appLoad()

        val allPages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()

        // Today must be created
        val todayJournal = allPages.firstOrNull { it.journalDate == today() }
        assertNotNull(todayJournal, "Today's journal should be auto-created")

        // Past journals must be preserved
        val pastDates = listOf(LocalDate(2026, 1, 1), LocalDate(2026, 2, 14), LocalDate(2026, 3, 15))
        for (date in pastDates) {
            val found = allPages.any { it.journalDate == date || it.name == date.toString().replace('-', '_') }
            assertTrue(found, "Past journal for $date must not be deleted")
        }
    }

    @Test
    fun `past journal page count is unaffected by today journal creation`() = runTest {
        val h = harness(mutableMapOf(
            "/graph/journals/2026_01_01.md" to "- Entry 1",
            "/graph/journals/2026_02_01.md" to "- Entry 2"
        ))

        h.appLoad()

        val allPages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        // 2 past + 1 today = 3 total
        assertEquals(3, allPages.size, "Should have 2 past journals + 1 today, got: ${allPages.map { it.name }}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 6: Both today formats on disk (YYYY-MM-DD and YYYY_MM_DD)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `both today formats on disk - merged to exactly one page`() = runTest {
        val todayHyphen = today().toString()
        val todayUnderscore = todayHyphen.replace('-', '_')
        val h = harness(mutableMapOf(
            "/graph/journals/$todayHyphen.md" to "- Hyphen entry",
            "/graph/journals/$todayUnderscore.md" to "- Underscore entry"
        ))

        h.appLoad()

        val allPages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        val todayPages = allPages.filter { it.journalDate == today() }
        assertEquals(1, todayPages.size, "Duplicates should be merged, got: ${todayPages.map { it.name }}")
    }

    @Test
    fun `both today formats on disk - merged page retains content`() = runTest {
        val todayHyphen = today().toString()
        val todayUnderscore = todayHyphen.replace('-', '_')
        val h = harness(mutableMapOf(
            "/graph/journals/$todayHyphen.md" to "- Hyphen entry",
            "/graph/journals/$todayUnderscore.md" to "- Underscore entry"
        ))

        h.appLoad()

        val allPages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        val todayPage = allPages.first { it.journalDate == today() }
        val blocks = h.blockRepo.getBlocksForPage(todayPage.uuid).first().getOrNull() ?: emptyList()
        assertTrue(blocks.isNotEmpty(), "Merged page should retain blocks from surviving format")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 7: Duplicate pages — merge preserves all non-empty blocks
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `merge keeps page with content, deletes empty page`() = runTest {
        val now = Clock.System.now()
        val h = harness()

        // Simulate: ensureTodayJournal created an empty placeholder earlier
        h.pageRepo.savePage(Page(uuid = "empty-page", name = today().toString(), createdAt = now, updatedAt = now, isJournal = true, journalDate = today()))
        h.blockRepo.saveBlock(Block(uuid = "empty-block", pageUuid = "empty-page", content = "", position = 0, createdAt = now, updatedAt = now))

        // Then GraphLoader loaded the disk file (second page for same date)
        h.pageRepo.savePage(Page(uuid = "content-page", name = today().toString().replace('-', '_'), createdAt = now, updatedAt = now, isJournal = true, journalDate = today()))
        h.blockRepo.saveBlock(Block(uuid = "real-block", pageUuid = "content-page", content = "Real disk content", position = 0, createdAt = now, updatedAt = now))

        val result = h.journalService.ensureTodayJournal()

        assertEquals("content-page", result.uuid, "Should keep the page with real content")
        val deletedPage = h.pageRepo.getPageByUuid("empty-page").first().getOrNull()
        assertEquals(null, deletedPage, "Empty duplicate page should be deleted")
    }

    @Test
    fun `merge reparents non-empty blocks from loser page to keeper`() = runTest {
        val now = Clock.System.now()
        val h = harness()

        // Both pages have content
        h.pageRepo.savePage(Page(uuid = "page-a", name = today().toString(), createdAt = now, updatedAt = now, isJournal = true, journalDate = today()))
        h.blockRepo.saveBlock(Block(uuid = "block-a", pageUuid = "page-a", content = "Note from page A", position = 0, createdAt = now, updatedAt = now))

        h.pageRepo.savePage(Page(uuid = "page-b", name = today().toString().replace('-', '_'), createdAt = now, updatedAt = now, isJournal = true, journalDate = today()))
        h.blockRepo.saveBlock(Block(uuid = "block-b", pageUuid = "page-b", content = "Note from page B", position = 0, createdAt = now, updatedAt = now))

        val keeper = h.journalService.ensureTodayJournal()

        // The losing page's non-empty block should be re-parented to keeper
        val keeperBlocks = h.blockRepo.getBlocksForPage(keeper.uuid).first().getOrNull() ?: emptyList()
        val loserBlockUuid = if (keeper.uuid == "page-a") "block-b" else "block-a"
        val reparented = keeperBlocks.find { it.uuid == loserBlockUuid }
        assertNotNull(reparented, "Non-empty block from losing page should be re-parented to keeper")
        assertEquals(keeper.uuid, reparented.pageUuid)
    }

    @Test
    fun `merge deletes empty blocks from loser page`() = runTest {
        val now = Clock.System.now()
        val h = harness()

        // Page A: only empty block
        h.pageRepo.savePage(Page(uuid = "page-a", name = today().toString(), createdAt = now, updatedAt = now, isJournal = true, journalDate = today()))
        h.blockRepo.saveBlock(Block(uuid = "empty-block", pageUuid = "page-a", content = "", position = 0, createdAt = now, updatedAt = now))

        // Page B: real content
        h.pageRepo.savePage(Page(uuid = "page-b", name = today().toString().replace('-', '_'), createdAt = now, updatedAt = now, isJournal = true, journalDate = today()))
        h.blockRepo.saveBlock(Block(uuid = "real-block", pageUuid = "page-b", content = "Real note", position = 0, createdAt = now, updatedAt = now))

        h.journalService.ensureTodayJournal()

        // Empty block from losing page should be gone
        val deletedBlock = h.blockRepo.getBlockByUuid("empty-block").first().getOrNull()
        assertEquals(null, deletedBlock, "Empty block from losing page should be deleted")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 8: Metadata-only stub page (isContentLoaded = false)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `stub page for today in DB - ensureTodayJournal returns it without creating duplicate`() = runTest {
        val now = Clock.System.now()
        val h = harness()

        val stub = Page(
            uuid = "stub-uuid",
            name = today().toString().replace('-', '_'),
            createdAt = now, updatedAt = now,
            isJournal = true, journalDate = today(),
            isContentLoaded = false
        )
        h.pageRepo.savePage(stub)

        val result = h.journalService.ensureTodayJournal()

        assertEquals("stub-uuid", result.uuid, "Should return the existing stub, not create a second page")

        val allPages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        assertEquals(1, allPages.filter { it.journalDate == today() }.size, "No duplicate for stub page")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 9: Concurrent ensureTodayJournal calls (Mutex protection)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `concurrent ensureTodayJournal calls produce exactly one page`() = runTest {
        val h = harness()

        val results = (1..5).map { async { h.journalService.ensureTodayJournal() } }
            .map { it.await() }

        val distinctUuids = results.map { it.uuid }.toSet()
        assertEquals(1, distinctUuids.size, "All concurrent calls must return the same page UUID")

        val allPages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        assertEquals(1, allPages.filter { it.journalDate == today() }.size, "Exactly one page after concurrent calls")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 10: Idempotency — multiple app loads of the same graph
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `reloading same graph does not duplicate today journal`() = runTest {
        val todayUnderscore = today().toString().replace('-', '_')
        val h = harness(mutableMapOf("/graph/journals/$todayUnderscore.md" to "- Persistent note"))

        h.appLoad()
        h.appLoad()
        h.appLoad()

        val allPages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        val todayPages = allPages.filter { it.journalDate == today() }
        assertEquals(1, todayPages.size, "Repeated loads should not duplicate today's journal")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 11: Correct journal creation for non-existent directory paths
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `ensureTodayJournal on empty repo creates page with correct isJournal flag`() = runTest {
        val h = harness()

        val page = h.journalService.ensureTodayJournal()

        assertTrue(page.isJournal, "Created page must have isJournal = true")
        assertNotNull(page.journalDate, "Created page must have journalDate set")
        assertEquals(today(), page.journalDate)
    }

    @Test
    fun `ensureTodayJournal called twice returns same UUID both times`() = runTest {
        val h = harness()

        val first = h.journalService.ensureTodayJournal()
        val second = h.journalService.ensureTodayJournal()

        assertEquals(first.uuid, second.uuid, "Repeated calls must return the same page")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 12: Past journal content never touched by ensureTodayJournal
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `past journal blocks are not modified when today journal is created`() = runTest {
        val now = Clock.System.now()
        val h = harness()

        val yesterday = LocalDate(2026, 4, 12) // Fixed past date
        h.pageRepo.savePage(Page(uuid = "yesterday-uuid", name = "2026_04_12", createdAt = now, updatedAt = now, isJournal = true, journalDate = yesterday))
        h.blockRepo.saveBlock(Block(uuid = "yesterday-block", pageUuid = "yesterday-uuid", content = "Yesterday's thought", position = 0, createdAt = now, updatedAt = now))

        h.journalService.ensureTodayJournal()

        val block = h.blockRepo.getBlockByUuid("yesterday-block").first().getOrNull()
        assertNotNull(block, "Past journal block should not be deleted")
        assertEquals("Yesterday's thought", block.content, "Past journal block content must be unchanged")
        assertEquals("yesterday-uuid", block.pageUuid, "Past journal block must remain on its original page")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 13: Warm-cache path — ensureTodayJournal concurrent with loadGraphProgressive
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `warm cache - ensureTodayJournal concurrent with loadGraphProgressive produces one page`() = runTest {
        val todayUnderscore = today().toString().replace('-', '_')
        val h = harness(mutableMapOf("/graph/journals/$todayUnderscore.md" to "- Existing note"))

        // Simulate warm cache: pre-load the graph so DB already has today's journal
        h.appLoad()
        val pageAfterFirstLoad = h.pageRepo.getJournalPageByDate(today()).first().getOrNull()
        assertNotNull(pageAfterFirstLoad, "First load should create today's journal")

        // Simulate the warm-cache code path: ensureTodayJournal runs concurrently with
        // a second loadGraphProgressive (as happens when the app restarts with a cached DB).
        val ensureDeferred = async { h.journalService.ensureTodayJournal() }
        val loadDeferred = async {
            h.graphLoader.loadGraphProgressive(
                graphPath = "/graph",
                immediateJournalCount = 10,
                onProgress = {},
                onPhase1Complete = {},
                onFullyLoaded = {}
            )
        }
        ensureDeferred.await()
        loadDeferred.await()

        val allPages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        val todayPages = allPages.filter { it.journalDate == today() }
        assertEquals(1, todayPages.size, "Concurrent warm-cache load must not duplicate today's journal")
    }

    @Test
    fun `warm cache - getPageByJournalDate returns existing page without creating duplicate`() = runTest {
        val todayUnderscore = today().toString().replace('-', '_')
        val h = harness(mutableMapOf("/graph/journals/$todayUnderscore.md" to "- My note"))

        h.appLoad()
        val existingPage = h.pageRepo.getJournalPageByDate(today()).first().getOrNull()
        assertNotNull(existingPage)

        // Warm-cache lookup: should find the page, not create a new one
        val lookedUp = h.journalService.getPageByJournalDate(today())
        assertEquals(existingPage.uuid, lookedUp?.uuid, "Lookup should return the same page, not create a new one")

        val allPages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        assertEquals(1, allPages.filter { it.journalDate == today() }.size, "No duplicate created by lookup")
    }
}
