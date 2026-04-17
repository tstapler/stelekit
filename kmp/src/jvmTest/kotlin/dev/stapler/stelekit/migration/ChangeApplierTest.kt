// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.OperationLogger
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemoryPropertyRepository
import dev.stapler.stelekit.repository.InMemoryReferenceRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class ChangeApplierTest {

    private val now = Clock.System.now()

    private fun makePage(uuid: String, name: String) = Page(
        uuid = uuid,
        name = name,
        createdAt = now,
        updatedAt = now,
    )

    private fun makeBlock(
        uuid: String,
        pageUuid: String,
        content: String,
        position: Int = 0,
        properties: Map<String, String> = emptyMap(),
    ) = Block(
        uuid = uuid,
        pageUuid = pageUuid,
        content = content,
        position = position,
        createdAt = now,
        updatedAt = now,
        properties = properties,
    )

    private fun buildInMemoryRepoSet(
        pages: List<Page> = emptyList(),
        blocks: List<Block> = emptyList(),
    ): Pair<RepositorySet, DatabaseWriteActor> {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        runBlocking {
            pages.forEach { pageRepo.savePage(it) }
            blocks.forEach { blockRepo.saveBlock(it) }
        }
        val actor = DatabaseWriteActor(blockRepo, pageRepo, CoroutineScope(SupervisorJob()))
        val repoSet = RepositorySet(
            blockRepository = blockRepo,
            pageRepository = pageRepo,
            propertyRepository = InMemoryPropertyRepository(),
            referenceRepository = InMemoryReferenceRepository(),
            searchRepository = InMemorySearchRepository(pageRepo, blockRepo),
            journalService = JournalService(pageRepo, blockRepo),
            writeActor = actor,
        )
        return repoSet to actor
    }

    // ── applies_upsert_property_to_block ──────────────────────────────────────

    @Test
    fun applies_upsert_property_to_block() = runBlocking {
        val page = makePage("page-1", "TestPage")
        val block = makeBlock("block-1", "page-1", "some content")
        val (repoSet, actor) = buildInMemoryRepoSet(pages = listOf(page), blocks = listOf(block))

        val applier = ChangeApplier(actor, opLogger = null)
        val changes = listOf(BlockChange.UpsertProperty("block-1", "status", "done"))
        val summary = applier.apply(changes, repoSet)

        assertEquals(1, summary.applied)
        assertEquals(0, summary.skipped)
        assertTrue(summary.failed.isEmpty())

        val updated = repoSet.blockRepository.getBlockByUuid("block-1").first().getOrNull()
        assertNotNull(updated)
        assertEquals("done", updated.properties["status"])

        actor.close()
    }

    // ── operation_logger_receives_update_entry ────────────────────────────────

    @Test
    fun operation_logger_receives_update_entry() = runBlocking {
        // Use a real in-memory SQLite DB to exercise OperationLogger.
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)

        val blockRepo = SqlDelightBlockRepository(database)
        val pageRepo = SqlDelightPageRepository(database)

        val page = makePage("page-1", "TestPage")
        val block = makeBlock("block-1", "page-1", "original content")
        pageRepo.savePage(page)
        blockRepo.saveBlock(block)

        val sessionId = "test-session"
        val opLogger = OperationLogger(database, sessionId)
        val actor = DatabaseWriteActor(blockRepo, pageRepo, CoroutineScope(SupervisorJob()), opLogger)

        val inMemBlockRepo = InMemoryBlockRepository()
        val inMemPageRepo = InMemoryPageRepository()
        runBlocking {
            inMemPageRepo.savePage(page)
            inMemBlockRepo.saveBlock(block)
        }

        // We drive the ChangeApplier against the SqlDelight repos so opLogger sees writes.
        val repoSet = RepositorySet(
            blockRepository = blockRepo,
            pageRepository = pageRepo,
            propertyRepository = InMemoryPropertyRepository(),
            referenceRepository = InMemoryReferenceRepository(),
            searchRepository = InMemorySearchRepository(inMemPageRepo, inMemBlockRepo),
            journalService = JournalService(pageRepo, blockRepo),
            writeActor = actor,
        )

        val applier = ChangeApplier(actor, opLogger)
        val changes = listOf(BlockChange.UpsertProperty("block-1", "reviewed", "true"))
        val summary = applier.apply(changes, repoSet)

        assertEquals(1, summary.applied)
        assertTrue(summary.failed.isEmpty())

        // Verify at least one UPDATE_BLOCK operation was logged for block-1.
        val ops = database.steleDatabaseQueries
            .selectOperationsByPageUuid("page-1")
            .executeAsList()
        assertTrue(
            ops.any { it.op_type == OperationLogger.OpType.UPDATE_BLOCK.name && it.entity_uuid == "block-1" },
            "Expected UPDATE_BLOCK entry for block-1 in op log, found: ${ops.map { it.op_type }}"
        )

        actor.close()
        driver.close()
    }

    // ── rename_page_rewrites_wikilinks_in_other_blocks ────────────────────────

    @Test
    fun rename_page_rewrites_wikilinks_in_other_blocks() = runBlocking {
        val targetPage  = makePage("page-alpha", "Alpha")
        val referrerPage = makePage("page-ref",  "Referrer")
        val linkBlock   = makeBlock("block-link", "page-ref", "See [[Alpha]] for details")

        val (repoSet, actor) = buildInMemoryRepoSet(
            pages  = listOf(targetPage, referrerPage),
            blocks = listOf(linkBlock),
        )

        val applier = ChangeApplier(actor, opLogger = null)
        val changes = listOf(BlockChange.RenamePage("page-alpha", "Alpha", "Beta"))
        val summary = applier.apply(changes, repoSet)

        assertEquals(1, summary.applied)
        assertTrue(summary.failed.isEmpty())

        // Page must be renamed.
        val renamedPage = repoSet.pageRepository.getPageByUuid("page-alpha").first().getOrNull()
        assertNotNull(renamedPage)
        assertEquals("Beta", renamedPage.name)

        // Block that referenced [[Alpha]] must now reference [[Beta]].
        val updatedBlock = repoSet.blockRepository.getBlockByUuid("block-link").first().getOrNull()
        assertNotNull(updatedBlock)
        assertEquals("See [[Beta]] for details", updatedBlock.content)

        actor.close()
    }
}
