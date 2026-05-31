@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Enforces that paginated repository methods push LIMIT/OFFSET to SQL rather than loading
 * all rows into memory and paging in Kotlin.
 *
 * Each test:
 *  1. Inserts MORE rows than [limit] — so the pre-fix code and post-fix code return the same
 *     visible result count, making result-size assertions insufficient.
 *  2. Uses [StatementCapturingDriver] to record the raw SQL that actually executed.
 *  3. Asserts the captured SELECT statement contains "LIMIT" — proving pagination happened at
 *     the SQL layer, not in Kotlin.
 *
 * These tests FAIL against the pre-fix code because the pre-fix queries had no LIMIT clause.
 */
class PaginationEnforcementTest {

    private lateinit var capturingDriver: StatementCapturingDriver
    private lateinit var database: SteleDatabase
    private lateinit var blockRepo: SqlDelightBlockRepository
    private lateinit var pageRepo: SqlDelightPageRepository

    private val now = Clock.System.now()

    @BeforeTest
    fun setup() {
        val base = DriverFactory().createDriver("jdbc:sqlite::memory:")
        capturingDriver = StatementCapturingDriver(base)
        database = SteleDatabase(capturingDriver)
        blockRepo = SqlDelightBlockRepository(database)
        pageRepo = SqlDelightPageRepository(database)
    }

    private fun backlinkCountFor(pageName: String): Long =
        database.steleDatabaseQueries.selectPageBacklinkCount(pageName).executeAsOneOrNull() ?: 0L

    // ── searchBlocksByContent ────────────────────────────────────────────────

    @Test
    fun `searchBlocksByContent uses SQL LIMIT — not in-memory drop-take`() = runTest {
        val page = insertPage("p1")
        repeat(20) { i -> insertBlock(page.uuid, "search_target_content_$i") }

        capturingDriver.clearCapture()
        val results = blockRepo.searchBlocksByContent("search_target_content", limit = 5, offset = 0)
            .first().getOrNull() ?: error("null result")

        assertEquals(5, results.size)
        val selectSql = capturingDriver.capturedSelects.first { it.uppercase().contains("LIKE") }
        assertSqlHasLimit(selectSql)
    }

    @Test
    fun `searchBlocksByContent with offset returns correct window via SQL not Kotlin skip`() = runTest {
        val page = insertPage("p2")
        // Insert blocks with distinct content so we can verify the right window is returned
        val contents = (0 until 10).map { "paginated_block_$it" }
        contents.forEach { insertBlock(page.uuid, it) }

        capturingDriver.clearCapture()
        val page1 = blockRepo.searchBlocksByContent("paginated_block", limit = 4, offset = 0)
            .first().getOrNull()!!
        val page2 = blockRepo.searchBlocksByContent("paginated_block", limit = 4, offset = 4)
            .first().getOrNull()!!

        // Pages must not overlap
        val page1Uuids = page1.map { it.uuid }.toSet()
        val page2Uuids = page2.map { it.uuid }.toSet()
        assertTrue(page1Uuids.intersect(page2Uuids).isEmpty(),
            "Pages must not overlap — offset is not applied at the SQL layer")

        // Both queries must have used LIMIT
        capturingDriver.capturedSelects
            .filter { it.uppercase().contains("LIKE") }
            .forEach { assertSqlHasLimit(it) }
    }

    // ── getLinkedReferences(limit, offset) ───────────────────────────────────

    @Test
    fun `getLinkedReferences paginated overload uses iterative SQL batches not one unbounded scan`() = runTest {
        val page = insertPage("target-page")
        val linkingPage = insertPage("linking-page")
        // 20 blocks each containing [[target-page]]
        repeat(20) { i ->
            insertBlock(linkingPage.uuid, "See [[target-page]] for info $i")
        }

        capturingDriver.clearCapture()
        val results = blockRepo.getLinkedReferences("target-page", limit = 5, offset = 0)
            .first().getOrNull()!!

        assertEquals(5, results.size)
        // Every SELECT that ran during this call must use LIMIT — no unbounded scan allowed
        val linkScans = capturingDriver.capturedSelects.filter { it.uppercase().contains("LIKE") }
        assertTrue(linkScans.isNotEmpty(), "Expected at least one LIKE query to run")
        linkScans.forEach { sql ->
            assertSqlHasLimit(sql)
        }
    }

    // ── updateBlockContentsForRename backlink arithmetic ─────────────────────

    @Test
    fun `updateBlockContentsForRename sets oldName count to 0 and adds to newName count`() = runTest {
        insertPage("old-name")
        insertPage("new-name")
        val linkingPage = insertPage("linking-page")

        // 3 blocks referencing [[old-name]] — these will be renamed
        val blocks = (0 until 3).map { i ->
            insertBlock(linkingPage.uuid, "See [[old-name]] for details $i")
        }

        // 1 block already referencing [[new-name]] — represents a pre-existing backlink
        insertBlock(linkingPage.uuid, "[[new-name]] already had a reference")
        // Seed the initial backlink counts as the rename arithmetic reads the existing count
        database.steleDatabaseQueries.recomputeBacklinkCountForPage("old-name")
        database.steleDatabaseQueries.recomputeBacklinkCountForPage("new-name")
        val existingNewNameCount = backlinkCountFor("new-name") // should be 1

        val updates = blocks.map { b ->
            b.uuid to b.content.replace("[[old-name]]", "[[new-name]]")
        }
        blockRepo.updateBlockContentsForRename(updates, "old-name", "new-name")

        // old-name lost all its wikilink references → count must be 0
        assertEquals(0L, backlinkCountFor("old-name"),
            "oldName backlink count must be 0 after all refs renamed away")

        // new-name gained 3 wikilink refs on top of its pre-existing 1
        assertEquals(existingNewNameCount + 3, backlinkCountFor("new-name"),
            "newName backlink count must be existing ($existingNewNameCount) + 3 gained wikilink refs")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun assertSqlHasLimit(sql: String) {
        assertTrue(sql.uppercase().contains("LIMIT"),
            "Expected SQL LIMIT in query but got:\n$sql")
    }

    private suspend fun insertPage(name: String): Page {
        val page = Page(
            uuid = java.util.UUID.randomUUID().toString(),
            name = name,
            createdAt = now,
            updatedAt = now,
        )
        pageRepo.savePage(page)
        return page
    }

    private val blockCounter = java.util.concurrent.atomic.AtomicInteger(0)

    private suspend fun insertBlock(pageUuid: String, content: String): Block {
        val idx = blockCounter.getAndIncrement()
        val block = Block(
            uuid = java.util.UUID.randomUUID().toString(),
            pageUuid = pageUuid,
            parentUuid = null,
            leftUuid = null,
            content = content,
            level = 0,
            position = idx,
            createdAt = now,
            updatedAt = now,
            properties = emptyMap(),
            version = 0,
            contentHash = null,
            blockType = "bullet",
        )
        blockRepo.saveBlock(block)
        return block
    }
}

/**
 * Wraps a [SqlDriver] and records every SELECT SQL statement executed.
 * Used to assert that paginated repository methods include LIMIT/OFFSET in the SQL
 * rather than loading all rows and paging in Kotlin.
 */
private class StatementCapturingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {

    private val _capturedSelects = mutableListOf<String>()
    val capturedSelects: List<String> get() = _capturedSelects.toList()

    fun clearCapture() = _capturedSelects.clear()

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if (sql.trimStart().uppercase().startsWith("SELECT")) {
            _capturedSelects += sql
        }
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}
