package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.ParsedBlock
import dev.stapler.stelekit.util.ContentHasher
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        parentUuid = parentUuid?.let { BlockUuid(it) },
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

    // ─── Property-based regression coverage for the class of bug fixed in
    // StelekitViewModel.checkAndShowPendingConflict: comparing one block's content against
    // the whole disk file (rather than its own matched excerpt) produces false conflicts on
    // any multi-block page whenever an unrelated sibling changes. These properties assert the
    // invariant holds for arbitrary sibling counts/content, not just the one hand-picked case
    // covered by the example-based regression test in DiskConflictResolutionTest. ───────────

    /** Builds `n` root-level local blocks with guaranteed-unique, non-colliding content. */
    private fun uniqueSiblings(n: Int): List<Block> =
        (0 until n).map { i -> block("b$i", "unique-content-$i", "a$i") }

    @Test
    fun `a block unchanged on disk is never flagged as conflicting, regardless of how many siblings changed`() = runTest {
        checkAll(
            Arb.int(1, 8),                 // sibling count
            Arb.list(Arb.string(0, 24), 0..8), // arbitrary replacement content for OTHER siblings
        ) { siblingCount, replacementContents ->
            val localBlocks = uniqueSiblings(siblingCount)
            val targetIndex = 0
            val target = localBlocks[targetIndex]

            // Disk mirrors local structurally, but every non-target sibling's content is
            // replaced by an arbitrary generated string (including possibly-identical or
            // possibly-colliding text) — only the target's own disk content is held fixed
            // equal to its local content.
            val diskBlocks = localBlocks.mapIndexed { i, b ->
                val content = if (i == targetIndex) b.content else replacementContents.getOrElse(i) { "changed-$i" }
                ParsedBlock(content = content, properties = emptyMap(), level = 0, children = emptyList())
            }

            val matched = DiskConflictBlockMatcher.matchDiskBlockContent(localBlocks, target.uuid.value, diskBlocks)

            assertEquals(target.content, matched, "siblingCount=$siblingCount replacements=$replacementContents")
            assertFalse(
                DiskConflictBlockMatcher.hasRealConflict(target.content, matched),
                "an unchanged block must never be flagged as conflicting just because siblings changed"
            )
        }
    }

    @Test
    fun `a block that genuinely changed on disk is always flagged as conflicting, regardless of sibling count`() = runTest {
        checkAll(
            Arb.int(1, 8),
            Arb.string(1, 24),
        ) { siblingCount, diskReplacement ->
            val localBlocks = uniqueSiblings(siblingCount)
            val targetIndex = 0
            val target = localBlocks[targetIndex]
            if (diskReplacement == target.content) return@checkAll // not a genuine change, skip

            val diskBlocks = localBlocks.mapIndexed { i, b ->
                val content = if (i == targetIndex) diskReplacement else b.content
                ParsedBlock(content = content, properties = emptyMap(), level = 0, children = emptyList())
            }

            val matched = DiskConflictBlockMatcher.matchDiskBlockContent(localBlocks, target.uuid.value, diskBlocks)

            assertTrue(
                DiskConflictBlockMatcher.hasRealConflict(target.content, matched),
                "a block whose own disk content genuinely changed must be flagged as conflicting " +
                    "(siblingCount=$siblingCount matched=$matched)"
            )
        }
    }

    @Test
    fun `hasRealConflict fails safe to true when no structural match is found`() {
        assertTrue(
            DiskConflictBlockMatcher.hasRealConflict("anything", null),
            "an unresolvable structural match must default to 'assume a conflict', not silently clear one"
        )
    }
}
