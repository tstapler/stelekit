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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

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
}
