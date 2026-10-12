package dev.stapler.stelekit.git.merge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MergeMarkdownBlocksPolicyTest {
    private val base = "- A\n"
    private val local = "- A\n- mine\n"
    private val remote = "- A\n- theirs\n"

    @Test
    fun `strict conflicts on same-spot appends and reports it`() {
        val chunks = mergeMarkdownBlocks(base, local, remote)
        assertTrue(chunks.hasConflicts())
        assertTrue(chunks.toTwoWayConflictMarkerText().contains("<<<<<<<"))
    }

    @Test
    fun `insertions policy merges same-spot appends in local then remote order`() {
        val chunks = mergeMarkdownBlocks(base, local, remote, MergePolicy.Insertions)
        assertFalse(chunks.hasConflicts())
        assertEquals("- A\n- mine\n- theirs", chunks.toTwoWayConflictMarkerText())
    }

    @Test
    fun `identical changes on both sides are not duplicated`() {
        val chunks = mergeMarkdownBlocks(base, local, local, MergePolicy.Union)
        assertEquals("- A\n- mine", chunks.toTwoWayConflictMarkerText())
    }

    @Test
    fun `duplicate id is flagged only when two distinct blocks claim it`() {
        val a = mergeMarkdownBlocks("- A\n  id:: x\n", "- A\n  id:: x\n- B\n  id:: x\n", "- A\n  id:: x\n")
        assertEquals(listOf(DuplicateBlockId("x", 2)), a.findDuplicateBlockIds())
        val same = mergeMarkdownBlocks("- A\n  id:: x\n", "- A2\n  id:: x\n", "- A3\n  id:: x\n")
        assertTrue(same.findDuplicateBlockIds().isEmpty())
    }
}
