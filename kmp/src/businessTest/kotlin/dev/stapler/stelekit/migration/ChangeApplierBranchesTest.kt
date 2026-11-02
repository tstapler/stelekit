// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemoryPropertyRepository
import dev.stapler.stelekit.repository.InMemoryReferenceRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Per-change-type behaviour of [ChangeApplier] that `ChangeApplierTest` (upsert + rename only) leaves out. */
class ChangeApplierBranchesTest {
    private val now = Clock.System.now()

    private fun page(uuid: String, name: String, properties: Map<String, String> = emptyMap()) =
        Page(uuid = PageUuid(uuid), name = name, createdAt = now, updatedAt = now, properties = properties)

    private fun block(uuid: String, pageUuid: String, content: String, properties: Map<String, String> = emptyMap()) =
        Block(
            uuid = BlockUuid(uuid), pageUuid = PageUuid(pageUuid), content = content, position = "a0",
            createdAt = now, updatedAt = now, properties = properties,
        )

    private class Fixture(val repoSet: RepositorySet, val actor: DatabaseWriteActor) {
        val applier = ChangeApplier(actor, opLogger = null)
        suspend fun blockOf(uuid: String) = repoSet.blockRepository.getBlockByUuid(BlockUuid(uuid)).first().getOrNull()
        suspend fun pageOf(uuid: String) = repoSet.pageRepository.getPageByUuid(PageUuid(uuid)).first().getOrNull()
    }

    private fun withFixture(pages: List<Page> = emptyList(), blocks: List<Block> = emptyList(), body: suspend Fixture.() -> Unit) =
        runBlocking {
            val blockRepo = InMemoryBlockRepository()
            val pageRepo = InMemoryPageRepository()
            pages.forEach { pageRepo.savePage(it) }
            blocks.forEach { blockRepo.saveBlock(it) }
            val actor = DatabaseWriteActor(blockRepo, pageRepo)
            val repoSet = RepositorySet(
                blockRepository = blockRepo,
                pageRepository = pageRepo,
                propertyRepository = InMemoryPropertyRepository(),
                referenceRepository = InMemoryReferenceRepository(),
                searchRepository = InMemorySearchRepository(pageRepo, blockRepo),
                journalService = JournalService(pageRepo, blockRepo),
                writeActor = actor,
            )
            try {
                Fixture(repoSet, actor).body()
            } finally {
                actor.close()
            }
        }

    @Test
    fun deleteProperty_removesOnlyThatKey() = withFixture(
        pages = listOf(page("p", "P")),
        blocks = listOf(block("b", "p", "x", mapOf("keep" to "1", "drop" to "2"))),
    ) {
        val summary = applier.apply(listOf(BlockChange.DeleteProperty("b", "drop")), repoSet)

        assertEquals(ChangeSummary(applied = 1, skipped = 0, failed = emptyList()), summary)
        assertEquals(mapOf("keep" to "1"), blockOf("b")?.properties)
    }

    @Test
    fun setContent_replacesContentAndLeavesPropertiesAlone() = withFixture(
        pages = listOf(page("p", "P")),
        blocks = listOf(block("b", "p", "old", mapOf("k" to "v"))),
    ) {
        val summary = applier.apply(listOf(BlockChange.SetContent("b", "new")), repoSet)

        assertEquals(1, summary.applied)
        assertEquals("new", blockOf("b")?.content)
        assertEquals(mapOf("k" to "v"), blockOf("b")?.properties)
    }

    @Test
    fun deleteBlock_removesTheBlock() = withFixture(
        pages = listOf(page("p", "P")),
        blocks = listOf(block("b", "p", "x"), block("other", "p", "y")),
    ) {
        val summary = applier.apply(listOf(BlockChange.DeleteBlock("b")), repoSet)

        assertEquals(1, summary.applied)
        assertNull(blockOf("b"))
        assertNotNull(blockOf("other"))
    }

    @Test
    fun insertBlock_persistsTheSuppliedBlock() = withFixture(pages = listOf(page("p", "P"))) {
        val summary = applier.apply(listOf(BlockChange.InsertBlock(block("fresh", "p", "hello"))), repoSet)

        assertEquals(1, summary.applied)
        assertEquals("hello", blockOf("fresh")?.content)
    }

    @Test
    fun pageProperties_upsertThenDelete() = withFixture(pages = listOf(page("p", "P", mapOf("a" to "1")))) {
        applier.apply(listOf(BlockChange.UpsertPageProperty("p", "b", "2")), repoSet)
        assertEquals(mapOf("a" to "1", "b" to "2"), pageOf("p")?.properties)

        val summary = applier.apply(listOf(BlockChange.DeletePageProperty("p", "a")), repoSet)

        assertEquals(1, summary.applied)
        assertEquals(mapOf("b" to "2"), pageOf("p")?.properties)
    }

    @Test
    fun deletePage_removesThePage() = withFixture(pages = listOf(page("p", "P"), page("q", "Q"))) {
        val summary = applier.apply(listOf(BlockChange.DeletePage("p")), repoSet)

        assertEquals(1, summary.applied)
        assertNull(pageOf("p"))
        assertNotNull(pageOf("q"))
    }

    @Test
    fun changesTargetingMissingEntitiesAreSkippedNotFailed() = withFixture(pages = listOf(page("p", "P"))) {
        val changes = listOf(
            BlockChange.UpsertProperty("ghost", "k", "v"),
            BlockChange.DeleteProperty("ghost", "k"),
            BlockChange.SetContent("ghost", "x"),
            BlockChange.DeleteBlock("ghost"),
            BlockChange.UpsertPageProperty("ghost-page", "k", "v"),
            BlockChange.DeletePageProperty("ghost-page", "k"),
        )

        val summary = applier.apply(changes, repoSet)

        assertEquals(ChangeSummary(applied = 0, skipped = 6, failed = emptyList()), summary)
    }

    @Test
    fun failedChangeIsReportedAndLaterChangesStillApply() = withFixture(
        pages = listOf(page("p", "P")),
        blocks = listOf(block("b", "p", "x")),
    ) {
        val changes = listOf(
            BlockChange.RenamePage("missing-page", "Old", "New"),
            BlockChange.SetContent("b", "after"),
        )

        val summary = applier.apply(changes, repoSet)

        assertEquals(1, summary.applied)
        assertEquals(0, summary.skipped)
        assertEquals(1, summary.failed.size)
        assertTrue(summary.failed.single().startsWith("RenamePage failed"), summary.failed.single())
        assertEquals("after", blockOf("b")?.content)
    }

    @Test
    fun emptyChangeListIsANoOp() = withFixture {
        assertEquals(ChangeSummary(0, 0, emptyList()), applier.apply(emptyList(), repoSet))
    }
}
