// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.repository

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
class CountAllBlocksTest {
    private val now = Clock.System.now()

    private fun blocks(count: Int) = (0 until count).map { i ->
        Block(
            uuid = BlockUuid("blk-$i"), pageUuid = PageUuid("p1"), content = "c$i",
            position = i.toString().padStart(11, '0'), createdAt = now, updatedAt = now,
        )
    }

    @Test
    fun countAllBlocks_should_CountEveryStoredBlock_When_UsingSqlDelight() = runBlocking {
        val db = SteleDatabase(DriverFactory().createDriver("jdbc:sqlite::memory:"))
        val blockRepo = SqlDelightBlockRepository(db)
        SqlDelightPageRepository(db).savePage(Page(uuid = PageUuid("p1"), name = "P", createdAt = now, updatedAt = now))
        assertEquals(0L, blockRepo.countAllBlocks().getOrNull())

        blockRepo.saveBlocks(blocks(3))

        assertEquals(3L, blockRepo.countAllBlocks().getOrNull())
    }

    @Test
    fun countAllBlocks_should_CountEveryStoredBlock_When_InMemory() = runBlocking {
        val blockRepo = InMemoryBlockRepository()
        blockRepo.saveBlocks(blocks(2))

        assertEquals(2L, blockRepo.countAllBlocks().getOrNull())
    }
}
