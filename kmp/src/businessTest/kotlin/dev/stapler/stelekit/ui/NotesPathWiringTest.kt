// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.ui.screens.copy.conflictPagePersister
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/** The conflict persister writes under the typed notes folder, and nothing when there is no graph. */
class NotesPathWiringTest {
    private val notes = "/graphs/g/logseq"

    private val now = Clock.System.now()
    private val pageUuid = PageUuid("00000000-0000-0000-0000-0000000000a1")

    private fun seededRepos(): Pair<InMemoryPageRepository, InMemoryBlockRepository> = runBlocking {
        val pages = InMemoryPageRepository()
        val blocks = InMemoryBlockRepository()
        pages.savePage(Page(uuid = pageUuid, name = "Persist Me", createdAt = now, updatedAt = now))
        blocks.saveBlock(
            Block(uuid = BlockUuid("00000000-0000-0000-0000-0000000000b1"), pageUuid = pageUuid, content = "hello", position = "a0", createdAt = now, updatedAt = now),
        )
        pages to blocks
    }

    @Test
    fun conflictPagePersister_should_WritePageUnderNotesFolder_When_ProviderHasOne() = runBlocking {
        val fs = FakeRelocationFileSystem()
        val (pages, blocks) = seededRepos()

        conflictPagePersister(pages, blocks, GraphWriter(fs)) { EffectiveNotesPath(notes) }(pageUuid)

        assertTrue(fs.fileExists("$notes/pages/Persist Me.md"), fs.allFilePaths().toString())
        assertTrue(fs.readFile("$notes/pages/Persist Me.md")!!.contains("hello"))
    }

    @Test
    fun conflictPagePersister_should_WriteNothing_When_ProviderHasNoGraph() = runBlocking {
        val fs = FakeRelocationFileSystem()
        val (pages, blocks) = seededRepos()

        conflictPagePersister(pages, blocks, GraphWriter(fs)) { null }(pageUuid)
        conflictPagePersister(pages, blocks, GraphWriter(fs)) { EffectiveNotesPath("") }(pageUuid)

        assertTrue(fs.allFilePaths().isEmpty(), "no graph means no write: ${fs.allFilePaths()}")
    }
}
