package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.ParsedBlock
import dev.stapler.stelekit.util.ContentHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

/**
 * Unit tests for [DiskConflictBlockMatcher.matchDiskBlockContent], covering:
 *   - the happy path (stable position on both sides),
 *   - the explicit structural "no match" fallback (ordinal index no longer valid on disk),
 *   - nested (non-root) block paths,
 *   - the content-hash plausibility check that catches a pure sibling reorder, and
 *   - that the plausibility check degrades gracefully when no sibling has a known hash.
 */
class DiskConflictBlockMatcherTest {

    private fun now() = Clock.System.now()

    private fun block(
        uuid: String,
        content: String,
        position: String,
        parentUuid: String? = null,
        contentHash: String? = null,
    ) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid("page-1"),
        parentUuid = parentUuid,
        content = content,
        position = position,
        createdAt = now(),
        updatedAt = now(),
        contentHash = contentHash,
    )

    private fun parsedBlock(content: String, children: List<ParsedBlock> = emptyList()) = ParsedBlock(
        content = content,
        properties = emptyMap(),
        level = 0,
        children = children,
    )

    @Test
    fun `matches disk content at a stable root-level position`() {
        val localBlocks = listOf(
            block("b0", "First", "a0"),
            block("b1", "Second", "a1"),
            block("b2", "Third", "a2"),
        )
        val diskBlocks = listOf(
            parsedBlock("First (disk)"),
            parsedBlock("Second (disk)"),
            parsedBlock("Third (disk)"),
        )

        val result = DiskConflictBlockMatcher.matchDiskBlockContent(localBlocks, "b1", diskBlocks)

        assertEquals("Second (disk)", result)
    }

    @Test
    fun `returns null when a block was deleted above the target, breaking ordinal alignment`() {
        // Local: 3 root-level siblings, target is the LAST one (path index 2).
        val localBlocks = listOf(
            block("b0", "First", "a0"),
            block("b1", "Second", "a1"),
            block("b2", "Third", "a2"),
        )
        // Disk: one fewer block before the target (the first sibling was deleted on disk),
        // so the disk-side list only has 2 entries — index 2 (the target's local ordinal)
        // no longer exists. This must fall back to the explicit "no match" case, not pick
        // an arbitrary neighboring block.
        val diskBlocks = listOf(
            parsedBlock("Second (disk)"),
            parsedBlock("Third (disk)"),
        )

        val result = DiskConflictBlockMatcher.matchDiskBlockContent(localBlocks, "b2", diskBlocks)

        assertNull(result)
    }

    @Test
    fun `matches nested block content via a two-level path`() {
        val parent = block("parent", "Parent content", "a0")
        val child = block("child", "Child content", "b0", parentUuid = "parent")
        val localBlocks = listOf(parent, child)

        val diskChild = parsedBlock("Child content (disk)")
        val diskParent = parsedBlock("Parent content (disk)", children = listOf(diskChild))
        val diskBlocks = listOf(diskParent)

        val result = DiskConflictBlockMatcher.matchDiskBlockContent(localBlocks, "child", diskBlocks)

        assertEquals("Child content (disk)", result)
    }

    @Test
    fun `content-hash plausibility check detects a pure sibling reorder and returns null`() {
        val contentA = "Alpha content"
        val contentB = "Beta content"
        val localBlocks = listOf(
            block("a", contentA, "a0", contentHash = ContentHasher.sha256ForContent(contentA)),
            block("b", contentB, "a1", contentHash = ContentHasher.sha256ForContent(contentB)),
        )
        // Disk-side: same count, but the first two are transposed (no addition/removal) —
        // the positional match for "b" (path index 1) now lands on Alpha's content, whose
        // hash equals sibling "a"'s known last-saved contentHash.
        val diskBlocks = listOf(
            parsedBlock(contentB),
            parsedBlock(contentA),
        )

        val result = DiskConflictBlockMatcher.matchDiskBlockContent(localBlocks, "b", diskBlocks)

        assertNull(result)
    }

    @Test
    fun `never-saved siblings with null contentHash do not trigger a false-positive collision`() {
        val contentA = "Alpha content"
        val contentB = "Beta content"
        val localBlocks = listOf(
            block("a", contentA, "a0", contentHash = null),
            block("b", contentB, "a1", contentHash = null),
        )
        // Same transposed shape as the reorder test above, but neither sibling has ever been
        // saved (contentHash == null), so there is no historical hash to compare against.
        val diskBlocks = listOf(
            parsedBlock(contentB),
            parsedBlock(contentA),
        )

        val result = DiskConflictBlockMatcher.matchDiskBlockContent(localBlocks, "b", diskBlocks)

        // Degrades gracefully: no crash, and the plausibility check has nothing to compare
        // against, so the plain positional match (Alpha's content) is returned unchanged.
        assertEquals(contentA, result)
    }
}
