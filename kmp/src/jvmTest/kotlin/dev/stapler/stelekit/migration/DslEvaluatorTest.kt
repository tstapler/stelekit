// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemoryPropertyRepository
import dev.stapler.stelekit.repository.InMemoryReferenceRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock

class DslEvaluatorTest {

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

    private fun buildRepoSet(
        pages: List<Page> = emptyList(),
        blocks: List<Block> = emptyList(),
    ): RepositorySet {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        runBlocking {
            pages.forEach { pageRepo.savePage(it) }
            blocks.forEach { blockRepo.saveBlock(it) }
        }
        return RepositorySet(
            blockRepository = blockRepo,
            pageRepository = pageRepo,
            propertyRepository = InMemoryPropertyRepository(),
            referenceRepository = InMemoryReferenceRepository(),
            searchRepository = InMemorySearchRepository(pageRepo, blockRepo),
            journalService = JournalService(pageRepo, blockRepo),
        )
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    fun evaluator_applies_property_to_matching_blocks(): Unit = runBlocking {
        val page = makePage("page-1", "TestPage")
        val matchingBlock = makeBlock("block-match", "page-1", "has tag:kotlin")
        val otherBlock   = makeBlock("block-other", "page-1", "no tag here", position = 1)

        val repoSet = buildRepoSet(pages = listOf(page), blocks = listOf(matchingBlock, otherBlock))
        val evaluator = DslEvaluator(repoSet)

        val migration = migration("V001") {
            apply {
                forBlocks(where = { it.content.contains("tag:kotlin") }) {
                    setProperty("language", "kotlin")
                }
            }
        }

        val changes = evaluator.evaluate(migration)

        // Only the matching block should receive an UpsertProperty change.
        assertEquals(1, changes.size)
        val change = changes.single() as BlockChange.UpsertProperty
        assertEquals("block-match", change.blockUuid)
        assertEquals("language", change.key)
        assertEquals("kotlin", change.value)
    }

    @Test
    fun evaluator_is_idempotent(): Unit = runBlocking {
        val page = makePage("page-1", "TestPage")
        // Block already has the property the migration wants to set.
        val alreadyMigratedBlock = makeBlock(
            uuid = "block-1",
            pageUuid = "page-1",
            content = "some content",
            properties = mapOf("language" to "kotlin"),
        )

        val repoSet = buildRepoSet(pages = listOf(page), blocks = listOf(alreadyMigratedBlock))
        val evaluator = DslEvaluator(repoSet)

        val migration = migration("V001") {
            apply {
                forBlocks(where = { true }) {
                    setProperty("language", "kotlin")
                }
            }
        }

        val changes = evaluator.evaluate(migration)

        // Already satisfied — evaluator should produce zero changes.
        assertTrue(changes.isEmpty(), "Expected empty changes list but got: $changes")
    }

    @Test
    fun evaluator_throws_on_destructive_without_flag(): Unit = runBlocking {
        val page = makePage("page-1", "TestPage")
        val block = makeBlock("block-del", "page-1", "delete me")

        val repoSet = buildRepoSet(pages = listOf(page), blocks = listOf(block))
        val evaluator = DslEvaluator(repoSet)

        val migration = migration("V001") {
            allowDestructive = false
            apply {
                forBlocks(where = { true }) {
                    deleteBlock()
                }
            }
        }

        assertFailsWith<DestructiveOperationException> {
            evaluator.evaluate(migration)
        }
    }

    @Test
    fun evaluator_allows_destructive_with_flag(): Unit = runBlocking {
        val page = makePage("page-1", "TestPage")
        val block = makeBlock("block-del", "page-1", "delete me")

        val repoSet = buildRepoSet(pages = listOf(page), blocks = listOf(block))
        val evaluator = DslEvaluator(repoSet)

        val migration = migration("V001") {
            allowDestructive = true
            apply {
                forBlocks(where = { true }) {
                    deleteBlock()
                }
            }
        }

        val changes = evaluator.evaluate(migration)

        assertEquals(1, changes.size)
        val change = changes.single() as BlockChange.DeleteBlock
        assertEquals("block-del", change.blockUuid)
    }
}
