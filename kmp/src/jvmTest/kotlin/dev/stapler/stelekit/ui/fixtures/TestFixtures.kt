package dev.stapler.stelekit.ui.fixtures

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlin.time.Clock
import kotlinx.datetime.LocalDate

object TestFixtures {

    private fun uuid(index: Int): String {
        val hex = index.toString(16).padStart(12, '0')
        return "00000000-0000-0000-0000-$hex"
    }

    fun sampleJournalPages(): List<Page> {
        val now = Clock.System.now()
        return (1..3).map { i ->
            Page(
                uuid = PageUuid(uuid(i)),
                name = "2026-03-0$i",
                createdAt = now,
                updatedAt = now,
                isJournal = true,
                journalDate = LocalDate(2026, 3, i)
            )
        }
    }

    fun sampleBlocksForPage(pageUuid: PageUuid, startIdx: Int = 100): List<Block> {
        val now = Clock.System.now()
        val baseId = startIdx
        return listOf(
            Block(
                uuid = BlockUuid(uuid(baseId)),
                pageUuid = pageUuid,
                content = "**Bold text** in journal entry",
                level = 0,
                position = "a0",
                createdAt = now,
                updatedAt = now
            ),
            Block(
                uuid = BlockUuid(uuid(baseId + 1)),
                pageUuid = pageUuid,
                content = "TODO A task to complete",
                level = 0,
                position = "a1",
                createdAt = now,
                updatedAt = now
            ),
            Block(
                uuid = BlockUuid(uuid(baseId + 2)),
                pageUuid = pageUuid,
                content = "See also [[Another Page]]",
                level = 0,
                position = "a2",
                createdAt = now,
                updatedAt = now
            ),
            Block(
                uuid = BlockUuid(uuid(baseId + 3)),
                pageUuid = pageUuid,
                parentUuid = BlockUuid(uuid(baseId)),
                content = "Child block content",
                level = 1,
                position = "a0",
                createdAt = now,
                updatedAt = now
            )
        )
    }

    fun samplePage(): Page {
        val now = Clock.System.now()
        return Page(
            uuid = PageUuid(uuid(999)),
            name = "Test Page",
            createdAt = now,
            updatedAt = now,
            isJournal = false
        )
    }

    fun samplePageBlocks(pageUuid: PageUuid = PageUuid(uuid(999))): List<Block> {
        val now = Clock.System.now()
        return listOf(
            Block(
                uuid = BlockUuid(uuid(9001)),
                pageUuid = pageUuid,
                content = "Introduction paragraph with regular text",
                level = 0,
                position = "a0",
                createdAt = now,
                updatedAt = now
            ),
            Block(
                uuid = BlockUuid(uuid(9002)),
                pageUuid = pageUuid,
                content = "Second block with **bold** and *italic*",
                level = 0,
                position = "a1",
                createdAt = now,
                updatedAt = now
            )
        )
    }

    fun sampleJournalData(): Map<Page, List<Block>> {
        val pages = sampleJournalPages()
        return pages.associateWith { page -> sampleBlocksForPage(page.uuid) }
    }
}
