@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Regression guard for the FTS merge-on-hot-path bugs.
 *
 * Background: `INSERT INTO blocks_fts(blocks_fts) VALUES('merge=-200')` triggers a full FTS5
 * index compaction pass. On a graph with 5 000+ blocks this takes 100 ms+ per call. Placing it
 * inside [SqlDelightBlockRepository.saveBlocks], [deleteBlocksForPage], or
 * [deleteBlocksForPages] — which fire on every incremental user edit — makes the app feel
 * sluggish on slow hardware.
 *
 * The correct contract:
 * - [SqlDelightBlockRepository.compactFtsIndex] is the ONLY method that may call `merge=-200`.
 * - Incremental-write methods ([saveBlocks], [saveBlocksDiff], [deleteBlocksForPage],
 *   [deleteBlocksForPages]) must never call it.
 *
 * Each test below uses [FtsMergeSpy] to intercept the `execute()` path where FTS special
 * commands land. The spy is passed as the second `driver` constructor argument, which
 * [SqlDelightBlockRepository] uses exclusively for FTS management commands.
 */
class FtsMergeHotPathRegressionTest {

    private lateinit var realDriver: SqlDriver
    private lateinit var spy: FtsMergeSpy
    private lateinit var blockRepo: SqlDelightBlockRepository
    private lateinit var pageRepo: SqlDelightPageRepository

    private val now = Clock.System.now()
    private val pageUuid = PageUuid("page-fts-test")

    @BeforeTest
    fun setup() {
        runBlocking {
            realDriver = DriverFactory().createDriver("jdbc:sqlite::memory:")
            spy = FtsMergeSpy(realDriver)
            val database = SteleDatabase(realDriver)
            blockRepo = SqlDelightBlockRepository(database, spy)
            pageRepo = SqlDelightPageRepository(database)
            pageRepo.savePage(
                Page(uuid = pageUuid, name = "FTS Test Page", createdAt = now, updatedAt = now)
            )
        }
    }

    @Test
    fun `saveBlocks does not call FTS merge for an incremental edit`() = runBlocking {
        spy.clearCapture()
        blockRepo.saveBlocks(listOf(block("b1")))
        assertFalse(
            spy.mergeCallCount > 0,
            "saveBlocks must not call FTS merge — it scans the full index and takes 100ms+ " +
            "on large graphs. Only compactFtsIndex() should call merge=-200."
        )
    }

    @Test
    fun `deleteBlocksForPage does not call FTS merge`() = runBlocking {
        blockRepo.saveBlocks(listOf(block("b-del")))
        spy.clearCapture()

        blockRepo.deleteBlocksForPage(pageUuid)

        assertFalse(
            spy.mergeCallCount > 0,
            "deleteBlocksForPage must not call FTS merge — every parseAndSavePage calls this " +
            "and each merge=-200 scans the full index."
        )
    }

    @Test
    fun `deleteBlocksForPages does not call FTS merge`() = runBlocking {
        blockRepo.saveBlocks(listOf(block("b-del-bulk")))
        spy.clearCapture()

        blockRepo.deleteBlocksForPages(listOf(pageUuid))

        assertFalse(
            spy.mergeCallCount > 0,
            "deleteBlocksForPages must not call FTS merge for the same reason as deleteBlocksForPage."
        )
    }

    @Test
    fun `compactFtsIndex calls FTS merge exactly once`() = runBlocking {
        spy.clearCapture()
        blockRepo.compactFtsIndex()
        assertTrue(
            spy.mergeCallCount == 1,
            "compactFtsIndex must call merge=-200 exactly once. Got ${spy.mergeCallCount} calls."
        )
    }

    private fun block(id: String) = Block(
        uuid = BlockUuid(id),
        pageUuid = pageUuid,
        content = "Block $id",
        position = "a0",
        createdAt = now,
        updatedAt = now,
    )
}

/**
 * Spy driver that wraps the FTS special-command path.
 *
 * [SqlDelightBlockRepository] takes an optional `driver: SqlDriver?` used exclusively for
 * FTS management commands (`INSERT INTO blocks_fts(blocks_fts) VALUES(...)`). Passing a
 * [FtsMergeSpy] as that second argument intercepts all FTS commands while letting normal
 * SQL queries run through the delegate unchanged.
 */
private class FtsMergeSpy(private val delegate: SqlDriver) : SqlDriver by delegate {

    private var _mergeCallCount = 0
    val mergeCallCount: Int get() = _mergeCallCount

    fun clearCapture() { _mergeCallCount = 0 }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (sql.contains("merge=-200", ignoreCase = true)) _mergeCallCount++
        return delegate.execute(identifier, sql, parameters, binders)
    }
}
