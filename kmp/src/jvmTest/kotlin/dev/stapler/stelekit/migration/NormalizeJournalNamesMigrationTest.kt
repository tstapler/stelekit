// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(DirectRepositoryWrite::class)
class NormalizeJournalNamesMigrationTest {

    private val now = Clock.System.now()
    private val journalDate = LocalDate(2026, 4, 18)
    private val hyphenName = "2026-04-18"
    private val underscoreName = "2026_04_18"

    private lateinit var harness: MigrationTestHarness

    @BeforeTest
    fun setup() {
        harness = MigrationTestHarness()
        MigrationRegistry.clear()
        MigrationRegistry.registerAll(V20260414001_baseline, V20260418001_normalizeJournalNames)
    }

    @AfterTest
    fun teardown() {
        harness.close()
        MigrationRegistry.clear()
    }

    private fun makePage(uuid: String, name: String, isJournal: Boolean = true) = Page(
        uuid = uuid,
        name = name,
        createdAt = journalDate.atStartOfDayIn(TimeZone.UTC),
        updatedAt = now,
        isJournal = isJournal,
        journalDate = if (isJournal) journalDate else null,
    )

    private fun makeBlock(uuid: String, pageUuid: String, content: String, position: Int = 0) = Block(
        uuid = uuid,
        pageUuid = pageUuid,
        content = content,
        position = position,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun renames_hyphen_page_when_no_underscore_exists(): Unit = runBlocking {
        val hyphenPage = makePage("page-hyp", hyphenName)
        val block = makeBlock("block-1", "page-hyp", "Today's entry")
        harness.repoSet.pageRepository.savePage(hyphenPage)
        harness.repoSet.blockRepository.saveBlock(block)

        harness.buildRunner().runPending("graph-1", harness.repoSet, "/tmp/test")

        val renamed = harness.repoSet.pageRepository.getPageByName(underscoreName).first().getOrNull()
        val old = harness.repoSet.pageRepository.getPageByName(hyphenName).first().getOrNull()

        assertEquals(underscoreName, renamed?.name)
        assertNull(old)
    }

    @Test
    fun merges_hyphen_into_underscore_non_empty_blocks_moved(): Unit = runBlocking {
        val hyphenPage = makePage("page-hyp", hyphenName)
        val underscorePage = makePage("page-und", underscoreName)
        val contentBlock = makeBlock("block-content", "page-hyp", "Important note", position = 0)
        val emptyBlock = makeBlock("block-empty", "page-hyp", "", position = 1)
        val existingBlock = makeBlock("block-existing", "page-und", "Existing entry", position = 0)

        harness.repoSet.pageRepository.savePage(hyphenPage)
        harness.repoSet.pageRepository.savePage(underscorePage)
        harness.repoSet.blockRepository.saveBlock(contentBlock)
        harness.repoSet.blockRepository.saveBlock(emptyBlock)
        harness.repoSet.blockRepository.saveBlock(existingBlock)

        harness.buildRunner().runPending("graph-1", harness.repoSet, "/tmp/test")

        val undBlocks = harness.repoSet.blockRepository.getBlocksForPage("page-und").first().getOrDefault(emptyList())
        val hypBlocks = harness.repoSet.blockRepository.getBlocksForPage("page-hyp").first().getOrDefault(emptyList())
        val deletedHypPage = harness.repoSet.pageRepository.getPageByName(hyphenName).first().getOrNull()

        val undContents = undBlocks.map { it.content }.toSet()
        assertTrue("Important note" in undContents, "Non-empty block should be moved to underscore page")
        assertTrue(hypBlocks.isEmpty(), "Hyphen page should have no blocks after merge")
        assertNull(deletedHypPage, "Hyphen page should be deleted after merge")

        // UUID preserved — block-ref wikilinks remain valid after merge
        val movedBlock = undBlocks.firstOrNull { it.content == "Important note" }
        assertEquals("block-content", movedBlock?.uuid, "Block UUID must be preserved during merge")

        // Root position offset — moved block should be placed after existing block (position 0)
        val existingPos = undBlocks.first { it.uuid == "block-existing" }.position
        val movedPos = movedBlock!!.position
        assertTrue(movedPos > existingPos, "Moved block position ($movedPos) should be > existing block position ($existingPos)")
    }

    @Test
    fun idempotent_when_already_normalized(): Unit = runBlocking {
        val underscorePage = makePage("page-und", underscoreName)
        val block = makeBlock("block-1", "page-und", "Already correct")
        harness.repoSet.pageRepository.savePage(underscorePage)
        harness.repoSet.blockRepository.saveBlock(block)

        harness.buildRunner().runPending("graph-1", harness.repoSet, "/tmp/test")

        val page = harness.repoSet.pageRepository.getPageByName(underscoreName).first().getOrNull()
        assertEquals(underscoreName, page?.name)
    }

    @Test
    fun non_journal_hyphen_pages_are_not_renamed(): Unit = runBlocking {
        val nonJournalPage = makePage("page-nj", "some-page-with-hyphens", isJournal = false)
        harness.repoSet.pageRepository.savePage(nonJournalPage)

        harness.buildRunner().runPending("graph-1", harness.repoSet, "/tmp/test")

        val page = harness.repoSet.pageRepository.getPageByName("some-page-with-hyphens").first().getOrNull()
        assertEquals("some-page-with-hyphens", page?.name, "Non-journal pages with hyphens must not be renamed")
    }
}
