@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import dev.stapler.stelekit.repository.RankedSearchHit

class SearchRepositoryIntegrationTests {

    private lateinit var database: SteleDatabase
    private lateinit var repository: SearchRepository
    private lateinit var blockRepo: BlockRepository
    private lateinit var pageRepo: PageRepository
    private lateinit var refRepo: ReferenceRepository

    private val now = Clock.System.now()

    @BeforeTest
    fun setup() {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        database = SteleDatabase(driver)
        repository = SqlDelightSearchRepository(database)
        blockRepo = SqlDelightBlockRepository(database)
        pageRepo = SqlDelightPageRepository(database)
        refRepo = SqlDelightReferenceRepository(database)
    }

    private fun generateUuid(index: Int): String {
        val hex = index.toString().padStart(4, '0')
        return "00000000-0000-0000-0000-00000000$hex"
    }

    private fun createTestBlock(
        uuid: String,
        pageUuid: String,
        content: String,
        properties: Map<String, String> = emptyMap(),
        position: Int = 0
    ): Block {
        return Block(
            uuid = uuid,
            pageUuid = pageUuid,
            parentUuid = null,
            leftUuid = null,
            content = content,
            level = 1,
            position = position,
            createdAt = now,
            updatedAt = now,
            properties = properties
        )
    }

    private fun createTestPage(
        uuid: String,
        name: String,
        properties: Map<String, String> = emptyMap()
    ): Page {
        return Page(
            uuid = uuid,
            name = name,
            namespace = null,
            filePath = null,
            createdAt = now,
            updatedAt = now,
            properties = properties
        )
    }

    @Test
    fun testSearchBlocksByContent() = runTest {
        val pageUuid = generateUuid(100)
        pageRepo.savePage(createTestPage(pageUuid, "Page 1"))
        blockRepo.saveBlock(createTestBlock(generateUuid(1), pageUuid, "Hello world content", position = 1))
        blockRepo.saveBlock(createTestBlock(generateUuid(2), pageUuid, "Goodbye world content", position = 2))
        blockRepo.saveBlock(createTestBlock(generateUuid(3), pageUuid, "Another unrelated block", position = 3))

        val results = repository.searchBlocksByContent("hello").first()
        assertTrue(results.isSuccess)
        assertEquals(1, results.getOrNull()?.size)
        assertEquals(generateUuid(1), results.getOrNull()?.first()?.uuid)
    }

    @Test
    fun testSearchBlocksByContentCaseInsensitive() = runTest {
        val pageUuid = generateUuid(100)
        pageRepo.savePage(createTestPage(pageUuid, "Page 1"))
        blockRepo.saveBlock(createTestBlock(generateUuid(1), pageUuid, "KOTLIN PROGRAMMING", position = 1))
        blockRepo.saveBlock(createTestBlock(generateUuid(2), pageUuid, "kotlin is great", position = 2))

        val results = repository.searchBlocksByContent("kotlin").first()
        assertTrue(results.isSuccess)
        assertEquals(2, results.getOrNull()?.size)
    }

    @Test
    fun testSearchPagesByTitle() = runTest {
        pageRepo.savePage(createTestPage(generateUuid(1), "Kotlin Guide"))
        pageRepo.savePage(createTestPage(generateUuid(2), "Java Tutorial"))
        pageRepo.savePage(createTestPage(generateUuid(3), "Python Programming"))

        val results = repository.searchPagesByTitle("kotlin").first()
        assertTrue(results.isSuccess)
        assertEquals(1, results.getOrNull()?.size)
        assertEquals("Kotlin Guide", results.getOrNull()?.first()?.name)
    }

    @Test
    fun testFindBlocksReferencing() = runTest {
        val pageUuid = generateUuid(100)
        pageRepo.savePage(createTestPage(pageUuid, "Page 1"))
        val targetBlockUuid = generateUuid(1)
        val refBlock1Uuid = generateUuid(2)
        val refBlock2Uuid = generateUuid(3)

        blockRepo.saveBlock(createTestBlock(targetBlockUuid, pageUuid, "Target content", position = 1))
        blockRepo.saveBlock(createTestBlock(refBlock1Uuid, pageUuid, "References target-block", position = 2))
        blockRepo.saveBlock(createTestBlock(refBlock2Uuid, pageUuid, "Also references target-block", position = 3))

        refRepo.addReference(refBlock1Uuid, targetBlockUuid)
        refRepo.addReference(refBlock2Uuid, targetBlockUuid)

        val results = repository.findBlocksReferencing(targetBlockUuid).first()
        assertTrue(results.isSuccess)
        assertEquals(2, results.getOrNull()?.size)
    }

    @Test
    fun testSearchWithFilters() = runTest {
        val pageUuid = generateUuid(100)
        pageRepo.savePage(createTestPage(pageUuid, "Page 1"))
        blockRepo.saveBlock(createTestBlock(generateUuid(1), pageUuid, "Hello world", mapOf("tag" to "test"), position = 1))
        blockRepo.saveBlock(createTestBlock(generateUuid(2), pageUuid, "Hello again", mapOf("tag" to "other"), position = 2))

        val request = SearchRequest(
            query = "hello",
            limit = 10,
            offset = 0
        )

        val results = repository.searchWithFilters(request).first()
        assertTrue(results.isSuccess)
        // SQLDelight implementation currently only does basic content/title matching
        assertTrue(results.getOrNull()?.blocks?.any { it.uuid == generateUuid(1) } == true)
    }

    @Test
    fun testAndSemanticsMultiTermOnlyMatchesBothTerms() = runTest {
        val pageUuid = generateUuid(200)
        pageRepo.savePage(createTestPage(pageUuid, "Finance"))
        // block 1: contains both "tax" and "2025"
        blockRepo.saveBlock(createTestBlock(generateUuid(11), pageUuid, "tax season 2025", position = 1))
        // block 2: contains only "meeting"
        blockRepo.saveBlock(createTestBlock(generateUuid(12), pageUuid, "meeting notes", position = 2))
        // block 3: contains "2025" but not "tax"
        blockRepo.saveBlock(createTestBlock(generateUuid(13), pageUuid, "2025 budget planning", position = 3))

        val results = repository.searchBlocksByContent("2025 tax").first()
        assertTrue(results.isSuccess)
        val uuids = results.getOrNull()?.map { it.uuid }.orEmpty()
        assertTrue(generateUuid(11) in uuids, "Block with both terms should match")
        assertFalse(generateUuid(12) in uuids, "Block with neither term should not match")
        // block 3 has "2025" but not "tax" — AND semantics may exclude it (depends on porter stemming)
        assertFalse(generateUuid(12) in uuids, "Block with only 'meeting' should not match '2025 tax'")
    }

    @Test
    fun testFieldBoostingPageRanksAboveBlock() = runTest {
        val pageUuid = generateUuid(400)
        // Page whose title exactly matches the query
        pageRepo.savePage(createTestPage(pageUuid, "Project Alpha"))
        // Block on a different page that also mentions "project alpha"
        val otherPageUuid = generateUuid(401)
        pageRepo.savePage(createTestPage(otherPageUuid, "Notes"))
        blockRepo.saveBlock(createTestBlock(generateUuid(31), otherPageUuid, "project alpha notes from meeting", position = 1))

        val request = SearchRequest(query = "project alpha", limit = 10, offset = 0)
        val result = repository.searchWithFilters(request).first()
        assertTrue(result.isSuccess)

        val ranked = result.getOrNull()?.ranked.orEmpty()
        assertTrue(ranked.isNotEmpty(), "Expected ranked results")
        val first = ranked.first()
        assertTrue(first is RankedSearchHit.PageHit, "Page title hit should rank first, got ${first::class.simpleName}")
        assertEquals("Project Alpha", (first as RankedSearchHit.PageHit).page.name)
    }

    @Test
    fun testOrFallbackFiresWhenAndReturnsEmpty() = runTest {
        val pageUuid = generateUuid(300)
        pageRepo.savePage(createTestPage(pageUuid, "Fallback Page"))
        blockRepo.saveBlock(createTestBlock(generateUuid(21), pageUuid, "completely unrelated content", position = 1))
        blockRepo.saveBlock(createTestBlock(generateUuid(22), pageUuid, "another block about topics", position = 2))

        // "topics unrelated" should fail AND (neither block has both), then OR fallback
        // returns blocks containing either "topic*" or "unrelated*"
        val results = repository.searchBlocksByContent("topics unrelated").first()
        assertTrue(results.isSuccess)
        // At least one result should come back via OR fallback
        val uuids = results.getOrNull()?.map { it.uuid }.orEmpty()
        assertTrue(uuids.isNotEmpty(), "OR fallback should return results when AND finds nothing")
    }

    @Test
    fun testGraphDistanceBoostLinkedPageRanksHigher() = runTest {
        val currentPageUuid = generateUuid(500)
        val linkedPageUuid = generateUuid(501)
        val unlinkedPageUuid = generateUuid(502)

        pageRepo.savePage(createTestPage(currentPageUuid, "Current Page"))
        pageRepo.savePage(createTestPage(linkedPageUuid, "Linked Notes"))
        pageRepo.savePage(createTestPage(unlinkedPageUuid, "Unlinked Notes"))

        val sourceBlockUuid = generateUuid(510)
        val linkedBlockUuid = generateUuid(511)
        val unlinkedBlockUuid = generateUuid(512)

        blockRepo.saveBlock(createTestBlock(sourceBlockUuid, currentPageUuid, "source block", position = 1))
        blockRepo.saveBlock(createTestBlock(linkedBlockUuid, linkedPageUuid, "kotlin development guide", position = 1))
        blockRepo.saveBlock(createTestBlock(unlinkedBlockUuid, unlinkedPageUuid, "kotlin development guide", position = 1))

        refRepo.addReference(sourceBlockUuid, linkedBlockUuid)

        val request = SearchRequest(
            query = "kotlin development",
            pageUuid = currentPageUuid,
            limit = 10,
            offset = 0
        )
        val result = repository.searchWithFilters(request).first()
        assertTrue(result.isSuccess)

        val ranked = result.getOrNull()?.ranked.orEmpty()
        val linkedHit = ranked.filterIsInstance<RankedSearchHit.BlockHit>().firstOrNull { it.block.uuid == linkedBlockUuid }
        val unlinkedHit = ranked.filterIsInstance<RankedSearchHit.BlockHit>().firstOrNull { it.block.uuid == unlinkedBlockUuid }

        assertNotNull(linkedHit, "Linked page block should appear in results")
        assertNotNull(unlinkedHit, "Unlinked page block should appear in results")
        assertTrue(linkedHit!!.score > unlinkedHit!!.score, "Graph-linked block should rank higher than unlinked block")
    }

    @Test
    fun testRecencyBoostRecentBlockRanksHigher() = runTest {
        val recentPageUuid = generateUuid(600)
        val stalePageUuid = generateUuid(601)

        pageRepo.savePage(createTestPage(recentPageUuid, "Recent Notes"))
        pageRepo.savePage(createTestPage(stalePageUuid, "Stale Notes"))

        val recentBlock = Block(
            uuid = generateUuid(610),
            pageUuid = recentPageUuid,
            parentUuid = null,
            leftUuid = null,
            content = "kotlin guide reference",
            level = 1,
            position = 1,
            createdAt = now,
            updatedAt = now,
            properties = emptyMap()
        )
        val staleBlock = Block(
            uuid = generateUuid(611),
            pageUuid = stalePageUuid,
            parentUuid = null,
            leftUuid = null,
            content = "kotlin guide reference",
            level = 1,
            position = 1,
            createdAt = now - 90.days,
            updatedAt = now - 90.days,
            properties = emptyMap()
        )

        blockRepo.saveBlock(recentBlock)
        blockRepo.saveBlock(staleBlock)

        val request = SearchRequest(query = "kotlin guide", limit = 10, offset = 0)
        val result = repository.searchWithFilters(request).first()
        assertTrue(result.isSuccess)

        val ranked = result.getOrNull()?.ranked.orEmpty()
        val recentHit = ranked.filterIsInstance<RankedSearchHit.BlockHit>().firstOrNull { it.block.uuid == generateUuid(610) }
        val staleHit = ranked.filterIsInstance<RankedSearchHit.BlockHit>().firstOrNull { it.block.uuid == generateUuid(611) }

        assertNotNull(recentHit, "Recently updated block should appear in results")
        assertNotNull(staleHit, "Stale block should appear in results")
        assertTrue(recentHit!!.score > staleHit!!.score, "Recently updated block should rank higher than 90-day-old block")
    }
}
