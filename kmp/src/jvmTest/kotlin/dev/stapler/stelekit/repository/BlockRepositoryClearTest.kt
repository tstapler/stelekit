// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

@OptIn(DirectRepositoryWrite::class)
class BlockRepositoryClearTest {
    private val now = Clock.System.now()

    private fun block(i: Int, parent: Int?, content: String) = Block(
        uuid = BlockUuid("blk-$i"), pageUuid = PageUuid("p1"), parentUuid = parent?.let { BlockUuid("blk-$it") },
        content = content, position = i.toString().padStart(11, '0'), createdAt = now, updatedAt = now,
    )

    private fun SqlDriver.count(sql: String): Long =
        executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) }, 0).value

    private fun fixture(): Triple<SqlDriver, SqlDelightBlockRepository, SqlDelightPageRepository> {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val db = SteleDatabase(driver)
        return Triple(driver, SqlDelightBlockRepository(db), SqlDelightPageRepository(db))
    }

    private suspend fun SqlDelightPageRepository.seedPage() =
        savePage(Page(uuid = PageUuid("p1"), name = "P", createdAt = now, updatedAt = now))

    @Test
    fun clear_should_RemoveEveryBlockAndKeepFtsConsistent_When_BlocksAreNested() = runBlocking {
        val (driver, blocks, pages) = fixture()
        pages.seedPage()
        blocks.saveBlocks(listOf(block(0, null, "alpha"), block(1, 0, "alpha child"), block(2, 1, "alpha grandchild"), block(3, null, "alpha sibling")))
        assertEquals(4L, driver.count("SELECT count(*) FROM blocks_fts WHERE blocks_fts MATCH 'alpha'"))

        blocks.clear()

        assertEquals(0L, driver.count("SELECT count(*) FROM blocks"))
        assertEquals(0L, driver.count("SELECT count(*) FROM blocks_fts WHERE blocks_fts MATCH 'alpha'"))
        blocks.saveBlocks(listOf(block(10, null, "alpha again")))
        assertEquals(1L, driver.count("SELECT count(*) FROM blocks_fts WHERE blocks_fts MATCH 'alpha'"))
    }

    @Test
    fun clear_should_RemoveAChainDeeperThanSqliteTriggerRecursionLimit_When_BlocksAreCleared() = runBlocking {
        val (driver, blocks, pages) = fixture()
        assertEquals(1L, driver.count("PRAGMA foreign_keys"), "cascade path is only exercised with foreign keys on")
        pages.seedPage()
        blocks.saveBlocks(deepChain())

        blocks.clear()

        assertEquals(0L, driver.count("SELECT count(*) FROM blocks"))
        assertEquals(0L, driver.count("SELECT count(*) FROM blocks_fts WHERE blocks_fts MATCH 'chain'"))
    }

    @Test
    fun clear_should_RemoveBlocksAndPages_When_PagesAreClearedFirstOnADeepChain() = runBlocking {
        val (driver, blocks, pages) = fixture()
        assertEquals(1L, driver.count("PRAGMA foreign_keys"), "cascade path is only exercised with foreign keys on")
        pages.seedPage()
        blocks.saveBlocks(deepChain())

        pages.clear()

        assertEquals(0L, driver.count("SELECT count(*) FROM blocks"))
        assertEquals(0L, driver.count("SELECT count(*) FROM pages"))
    }

    // SQLite refuses to cascade deeper than 1000 nested trigger levels.
    private fun deepChain() = (0 until 1500).map { block(it, if (it == 0) null else it - 1, "chain $it") }
}
