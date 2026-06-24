package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.util.ContentHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class DiffMergeTest {

    private val now = Clock.System.now()

    private fun makeBlock(uuid: String, content: String, pageUuid: String = "page-1"): Block = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = content,
        level = 0,
        position = "a0",
        createdAt = now,
        updatedAt = now,
        contentHash = ContentHasher.sha256ForContent(content)
    )

    private fun summary(uuid: String, content: String): DiffMerge.ExistingBlockSummary =
        DiffMerge.ExistingBlockSummary(
            uuid = BlockUuid(uuid),
            contentHash = ContentHasher.sha256ForContent(content)
        )

    @Test
    fun `all unchanged when UUIDs and content hashes match`() {
        val existing = listOf(
            summary("block-1", "Hello"),
            summary("block-2", "World"),
        )
        val parsed = listOf(
            makeBlock("block-1", "Hello"),
            makeBlock("block-2", "World"),
        )

        val diff = DiffMerge.diff(existing, parsed)

        assertEquals(emptyList(), diff.toInsert)
        assertEquals(emptyList(), diff.toUpdate)
        assertEquals(emptyList(), diff.toDelete)
        assertEquals(listOf(BlockUuid("block-1"), BlockUuid("block-2")), diff.unchanged)
    }

    @Test
    fun `one update when content changes for matching UUID`() {
        val existing = listOf(summary("block-1", "Old content"))
        val parsed = listOf(makeBlock("block-1", "New content"))

        val diff = DiffMerge.diff(existing, parsed)

        assertEquals(emptyList(), diff.toInsert)
        assertEquals(listOf("block-1"), diff.toUpdate.map { it.uuid.value })
        assertEquals(emptyList(), diff.toDelete)
        assertEquals(emptyList(), diff.unchanged)
    }

    @Test
    fun `one delete when existing block UUID not in parsed`() {
        val existing = listOf(summary("block-1", "Gone"))
        val parsed = emptyList<Block>()

        val diff = DiffMerge.diff(existing, parsed)

        assertEquals(emptyList(), diff.toInsert)
        assertEquals(emptyList(), diff.toUpdate)
        assertEquals(listOf("block-1"), diff.toDelete.map { it.value })
        assertEquals(emptyList(), diff.unchanged)
    }

    @Test
    fun `one insert when parsed block UUID not in existing`() {
        val existing = emptyList<DiffMerge.ExistingBlockSummary>()
        val parsed = listOf(makeBlock("block-1", "New block"))

        val diff = DiffMerge.diff(existing, parsed)

        assertEquals(listOf("block-1"), diff.toInsert.map { it.uuid.value })
        assertEquals(emptyList(), diff.toUpdate)
        assertEquals(emptyList(), diff.toDelete)
        assertEquals(emptyList(), diff.unchanged)
    }

    @Test
    fun `empty existing to full parsed produces all inserts`() {
        val existing = emptyList<DiffMerge.ExistingBlockSummary>()
        val parsed = listOf(
            makeBlock("block-1", "First"),
            makeBlock("block-2", "Second"),
            makeBlock("block-3", "Third"),
        )

        val diff = DiffMerge.diff(existing, parsed)

        assertEquals(listOf("block-1", "block-2", "block-3"), diff.toInsert.map { it.uuid.value })
        assertEquals(emptyList(), diff.toUpdate)
        assertEquals(emptyList(), diff.toDelete)
        assertEquals(emptyList(), diff.unchanged)
    }

    @Test
    fun `full existing to empty parsed produces all deletes`() {
        val existing = listOf(
            summary("block-1", "First"),
            summary("block-2", "Second"),
            summary("block-3", "Third"),
        )
        val parsed = emptyList<Block>()

        val diff = DiffMerge.diff(existing, parsed)

        assertEquals(emptyList(), diff.toInsert)
        assertEquals(emptyList(), diff.toUpdate)
        assertEquals(listOf("block-1", "block-2", "block-3"), diff.toDelete.map { it.value })
        assertEquals(emptyList(), diff.unchanged)
    }

    @Test
    fun `mixed diff produces correct inserts updates deletes and unchanged`() {
        val existing = listOf(
            summary("keep", "Same content"),
            summary("update-me", "Old content"),
            summary("delete-me", "Will be removed"),
        )
        val parsed = listOf(
            makeBlock("keep", "Same content"),
            makeBlock("update-me", "New content"),
            makeBlock("new-block", "Freshly added"),
        )

        val diff = DiffMerge.diff(existing, parsed)

        assertEquals(listOf("new-block"), diff.toInsert.map { it.uuid.value })
        assertEquals(listOf("update-me"), diff.toUpdate.map { it.uuid.value })
        assertEquals(listOf("delete-me"), diff.toDelete.map { it.value })
        assertEquals(listOf("keep"), diff.unchanged.map { it.value })
    }

    @Test
    fun `existing block with null contentHash is treated as update`() {
        val existing = listOf(
            DiffMerge.ExistingBlockSummary(uuid = BlockUuid("block-1"), contentHash = null)
        )
        val parsed = listOf(makeBlock("block-1", "Any content"))

        val diff = DiffMerge.diff(existing, parsed)

        assertTrue(diff.toUpdate.any { it.uuid == BlockUuid("block-1") }, "Expected block-1 to be in toUpdate")
        assertEquals(emptyList(), diff.unchanged)
    }
}
