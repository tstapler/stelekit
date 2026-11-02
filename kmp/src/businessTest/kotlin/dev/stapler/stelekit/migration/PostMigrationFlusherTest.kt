// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class PostMigrationFlusherTest {
    private val now = Clock.System.now()
    private val graph = EffectiveNotesPath("/graph")

    private fun page(uuid: String, name: String) = Page(
        uuid = PageUuid(uuid), name = name, createdAt = now, updatedAt = now, filePath = "/graph/pages/$name.md",
    )

    private fun block(uuid: String, pageUuid: String, content: String) = Block(
        uuid = BlockUuid(uuid), pageUuid = PageUuid(pageUuid), content = content, position = "a0",
        createdAt = now, updatedAt = now,
    )

    private fun repoSet(pages: List<Page>, blocks: List<Block>): RepositorySet = runBlocking {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        pages.forEach { pageRepo.savePage(it) }
        blocks.forEach { blockRepo.saveBlock(it) }
        RepositorySet(
            blockRepository = blockRepo,
            pageRepository = pageRepo,
            propertyRepository = InMemoryPropertyRepository(),
            referenceRepository = InMemoryReferenceRepository(),
            searchRepository = InMemorySearchRepository(pageRepo, blockRepo),
            journalService = JournalService(pageRepo, blockRepo),
        )
    }

    @Test
    fun flush_writesEachTouchedPageWithItsBlocksToDisk() = runBlocking {
        val fs = FakeFileSystem()
        val repos = repoSet(
            pages = listOf(page("p1", "Alpha"), page("p2", "Beta")),
            blocks = listOf(block("b1", "p1", "alpha body"), block("b2", "p2", "beta body")),
        )

        PostMigrationFlusher(GraphWriter(fileSystem = fs)).flush(repos, setOf("p1", "p2"), graph)

        assertTrue(assertNotNull(fs.readFile("/graph/pages/Alpha.md")).contains("alpha body"))
        assertTrue(assertNotNull(fs.readFile("/graph/pages/Beta.md")).contains("beta body"))
    }

    @Test
    fun flush_leavesUntouchedPagesAlone() = runBlocking {
        val fs = FakeFileSystem()
        val repos = repoSet(
            pages = listOf(page("p1", "Alpha"), page("p2", "Beta")),
            blocks = listOf(block("b1", "p1", "alpha body"), block("b2", "p2", "beta body")),
        )

        PostMigrationFlusher(GraphWriter(fileSystem = fs)).flush(repos, setOf("p1"), graph)

        assertTrue(fs.fileExists("/graph/pages/Alpha.md"))
        assertFalse(fs.fileExists("/graph/pages/Beta.md"))
    }

    @Test
    fun flush_skipsUuidsWithNoPageAndKeepsGoing() = runBlocking {
        val fs = FakeFileSystem()
        val repos = repoSet(pages = listOf(page("p1", "Alpha")), blocks = listOf(block("b1", "p1", "alpha body")))

        PostMigrationFlusher(GraphWriter(fileSystem = fs)).flush(repos, setOf("ghost", "p1"), graph)

        assertEquals(listOf("Alpha.md"), fs.listFiles("/graph/pages"))
    }

    @Test
    fun flush_withNoTouchedPagesWritesNothing() = runBlocking {
        val fs = FakeFileSystem()
        val repos = repoSet(pages = listOf(page("p1", "Alpha")), blocks = emptyList())

        PostMigrationFlusher(GraphWriter(fileSystem = fs)).flush(repos, emptySet(), graph)

        assertTrue(fs.listFiles("/graph/pages").isEmpty())
    }
}
