package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.ParsedBlock
import dev.stapler.stelekit.util.ContentHasher

/**
 * Pure, unit-testable helpers for locating the on-disk counterpart of a local block
 * when resolving a disk-conflict, so the conflict dialog can preview just the affected
 * section of the file rather than the entire disk content.
 */
object DiskConflictBlockMatcher {

    /**
     * Walks up the `parentUuid` chain from [targetUuid], and at each level records the
     * target's ordinal index among its siblings (blocks sharing the same `parentUuid`,
     * sorted by `position`). The result reads root-to-leaf, e.g. `[0, 2, 1]` means:
     * "1st child of root, then its 3rd child, then that block's 2nd child."
     *
     * Returns `null` if [targetUuid] is not found in [allBlocks].
     */
    fun buildBlockPath(allBlocks: List<Block>, targetUuid: String): List<Int>? {
        val blocksByUuid = allBlocks.associateBy { it.uuid.value }
        var current = blocksByUuid[targetUuid] ?: return null

        val path = mutableListOf<Int>()
        while (true) {
            val parentUuid = current.parentUuid
            val siblings = allBlocks
                .filter { it.parentUuid == parentUuid }
                .sortedBy { it.position }
            val index = siblings.indexOfFirst { it.uuid.value == current.uuid.value }
            if (index < 0) return null
            path.add(0, index)

            val parent = parentUuid?.let { blocksByUuid[it] } ?: return path
            current = parent
        }
    }

    /**
     * Walks [diskBlocks] (and their `children`) one [path] segment at a time, starting
     * from [diskBlocks] as the root-level list. Returns `null` immediately if any segment
     * index is out of bounds.
     */
    fun findAtPath(diskBlocks: List<ParsedBlock>, path: List<Int>): ParsedBlock? {
        var currentLevel = diskBlocks
        var matched: ParsedBlock? = null
        for (index in path) {
            matched = currentLevel.getOrNull(index) ?: return null
            currentLevel = matched.children
        }
        return matched
    }

    /**
     * Locates the on-disk block content that ordinally corresponds to [targetUuid], for
     * previewing "what changed on disk" in the conflict dialog.
     *
     * This is an ordinal-position heuristic, not an identity match — [ParsedBlock] has no
     * stable UUID, so the only way to line up a local block with its disk counterpart is by
     * walking the same parent-relative sibling index on both sides. The plausibility check
     * below catches a pure same-count sibling reorder (the positionally-matched disk content's
     * hash collides with a *different* known local sibling's last-known-good content) but
     * cannot catch a reorder combined with a content edit at the transposed position — doing
     * so would require wiring [dev.stapler.stelekit.db.SidecarManager]'s content-hash identity
     * recovery into the ViewModel, which is out of proportion to fix here.
     *
     * Known false-positive case (fail-safe, not a safety regression): duplicate-content
     * siblings under the same parent with no actual reorder will trigger a hash collision
     * against each other and needlessly fall back to `null`, even though the positional match
     * was correct. This never shows wrong content — it only over-triggers the existing
     * "no match" fallback.
     */
    fun matchDiskBlockContent(
        localBlocks: List<Block>,
        targetUuid: String,
        diskBlocks: List<ParsedBlock>
    ): String? {
        val path = buildBlockPath(localBlocks, targetUuid) ?: return null
        val matched = findAtPath(diskBlocks, path) ?: return null

        val matchedContent = matched.content
        val matchedHash = ContentHasher.sha256ForContent(matchedContent)
        val targetParentUuid = localBlocks.find { it.uuid.value == targetUuid }?.parentUuid
        val collidesWithADifferentSibling = localBlocks.any { sibling ->
            sibling.uuid.value != targetUuid &&
                sibling.parentUuid == targetParentUuid &&
                sibling.contentHash != null &&
                sibling.contentHash == matchedHash
        }
        if (collidesWithADifferentSibling) {
            // The positionally-matched disk content's hash equals a DIFFERENT local block's
            // last-known-good content — strong evidence of a same-count reorder, not a genuine
            // match for targetUuid. Treat as no-match rather than show misattributed content.
            return null
        }
        return matchedContent
    }
}
