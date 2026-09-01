package dev.stapler.stelekit.git.merge

import dev.stapler.stelekit.git.ConflictResolver
import dev.stapler.stelekit.model.ParsedBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockDiff3Test {

    private fun blk(content: String, level: Int = 0, id: String? = null): ParsedBlock =
        ParsedBlock(content = content, properties = id?.let { mapOf("id" to it) } ?: emptyMap(), level = level)

    private fun List<BlockDiff3Chunk>.stableContents(): List<String> =
        filterIsInstance<BlockDiff3Chunk.Stable>().flatMap { it.blocks }.map { it.content }

    @Test
    fun `non-overlapping edits to different blocks merge without conflict`() {
        val base = listOf(blk("A"), blk("B"), blk("C"))
        val local = listOf(blk("A"), blk("X"), blk("C")) // edited B -> X
        val remote = listOf(blk("A"), blk("B"), blk("C"), blk("D")) // appended D

        val chunks = BlockDiff3.merge(base, local, remote)

        assertFalse(chunks.hasConflicts())
        assertEquals(listOf("A", "X", "C", "D"), chunks.stableContents())
    }

    @Test
    fun `editing the same block's content on both sides conflicts scoped to that block`() {
        val base = listOf(blk("A"), blk("B"), blk("C"))
        val local = listOf(blk("A"), blk("X"), blk("C"))
        val remote = listOf(blk("A"), blk("Y"), blk("C"))

        val chunks = BlockDiff3.merge(base, local, remote)

        assertTrue(chunks.hasConflicts())
        val conflict = chunks.filterIsInstance<BlockDiff3Chunk.Conflict>().single()
        assertEquals(listOf("X"), conflict.local.map { it.content })
        assertEquals(listOf("Y"), conflict.remote.map { it.content })
        // surrounding blocks stay stable — conflict never leaks past the one edited block
        assertEquals(listOf("A"), chunks.first().let { (it as BlockDiff3Chunk.Stable).blocks.map { b -> b.content } })
    }

    @Test
    fun `a whole subtree reparented on only one side auto-resolves cleanly`() {
        // "B" (and its child "B1") moved one level deeper on local; remote left the tree as-is.
        val base = listOf(blk("A"), blk("B", level = 0), blk("B1", level = 1), blk("C"))
        val local = listOf(blk("A"), blk("B", level = 1), blk("B1", level = 2), blk("C"))
        val remote = base

        val chunks = BlockDiff3.merge(base, local, remote)

        assertFalse(chunks.hasConflicts())
        val merged = chunks.filterIsInstance<BlockDiff3Chunk.Stable>().flatMap { it.blocks }
        assertEquals(listOf(0, 1, 2, 0), merged.map { it.level })
    }

    @Test
    fun `reparenting the same block differently on both sides conflicts instead of silently picking one`() {
        val base = listOf(blk("A"), blk("B", level = 0))
        val local = listOf(blk("A"), blk("B", level = 1)) // indented under A
        val remote = listOf(blk("B", level = 0), blk("A")) // moved above A, same level

        val chunks = BlockDiff3.merge(base, local, remote)

        assertTrue(chunks.hasConflicts(), "diverging reparents of the same block must conflict, not silently pick a side")
    }

    @Test
    fun `an explicit id property tracks the same block across a content edit on one side`() {
        val base = listOf(blk("original text", id = "b1"))
        val local = listOf(blk("edited text", id = "b1")) // content changed, id unchanged
        val remote = listOf(blk("original text", id = "b1")) // untouched

        val chunks = BlockDiff3.merge(base, local, remote)

        assertFalse(chunks.hasConflicts())
        assertEquals(listOf("edited text"), chunks.stableContents())
    }

    @Test
    fun `an explicit id edited differently on both sides conflicts instead of silently keeping base`() {
        val base = listOf(blk("original text", id = "b1"))
        val local = listOf(blk("local edit", id = "b1"))
        val remote = listOf(blk("remote edit", id = "b1"))

        val chunks = BlockDiff3.merge(base, local, remote)

        assertTrue(chunks.hasConflicts(), "same id:: edited differently on both sides must conflict, not resolve to stale base content")
        val conflict = chunks.filterIsInstance<BlockDiff3Chunk.Conflict>().single()
        assertEquals(listOf("local edit"), conflict.local.map { it.content })
        assertEquals(listOf("remote edit"), conflict.remote.map { it.content })
    }

    @Test
    fun `identical content moved to a new position on both sides never conflicts`() {
        val base = listOf(blk("A"), blk("B"), blk("C"))
        val local = listOf(blk("B"), blk("A"), blk("C")) // B moved to front
        val remote = listOf(blk("B"), blk("A"), blk("C")) // same move on both sides

        val chunks = BlockDiff3.merge(base, local, remote)

        assertFalse(chunks.hasConflicts())
    }

    @Test
    fun `findDuplicateBlockIds flags an id property reused by two distinct blocks`() {
        val base = listOf(blk("first", id = "dup"), blk("second", id = "other"))
        val local = base
        // remote independently introduces a second block that happens to reuse "dup"
        val remote = listOf(blk("first", id = "dup"), blk("second", id = "other"), blk("third", id = "dup"))

        val chunks = BlockDiff3.merge(base, local, remote)

        val duplicates = chunks.findDuplicateBlockIds()
        assertTrue(duplicates.any { it.id == "dup" && it.occurrences >= 2 })
    }

    @Test
    fun `toTwoWayConflictMarkerText round trips through ConflictResolver parseConflictFile`() {
        val base = listOf(blk("A"), blk("B"), blk("C"))
        val local = listOf(blk("A"), blk("X"), blk("C"))
        val remote = listOf(blk("A"), blk("Y"), blk("C"))

        val chunks = BlockDiff3.merge(base, local, remote)
        val text = chunks.toTwoWayConflictMarkerText()

        val parsed = ConflictResolver().parseConflictFile("/repo/f.md", text, "/repo")
        val file = (parsed as arrow.core.Either.Right).value
        assertEquals(1, file.hunks.size)
        assertTrue(file.hunks.single().localLines.any { it.contains("X") })
        assertTrue(file.hunks.single().remoteLines.any { it.contains("Y") })
    }

    @Test
    fun `mergeMarkdownBlocks parses raw markdown and merges at block granularity`() {
        val base = "- A\n- B\n\t- B1\n- C\n"
        val local = "- A\n\t- B\n\t\t- B1\n- C\n" // B reparented under A on local only
        val remote = base

        val chunks = mergeMarkdownBlocks(base, local, remote)

        assertFalse(chunks.hasConflicts())
    }
}
