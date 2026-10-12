// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.merge

/**
 * A three-way markdown merge ready for the sync/conflict flow: [markerText] is what to write
 * back, [hasConflicts] says whether it embeds conflict markers needing a user decision.
 */
data class MarkdownMergeOutcome(
    val markerText: String,
    val hasConflicts: Boolean,
    val duplicateBlockIds: List<DuplicateBlockId>,
)

/**
 * Single entry point for merging markdown under a [MergePolicy]: [MergePolicy.LogseqUnion] runs
 * the [LogseqUnionMergeDriver] port; every other policy runs the block-aware [mergeMarkdownBlocks].
 *
 * The port's conflict markers (`<<<<<<< LOCAL` … `>>>>>>> REMOTE`, same-line edits) are returned
 * as [hasConflicts] so callers route them to the normal conflict UI, never commit them. The
 * python driver exits 0 there and git would commit the markers verbatim; we deliberately don't.
 */
fun mergeMarkdownWithPolicy(
    baseText: String,
    localText: String,
    remoteText: String,
    policy: MergePolicy,
): MarkdownMergeOutcome {
    if (policy == MergePolicy.LogseqUnion) {
        val out = LogseqUnionMergeDriver.merge(baseText, localText, remoteText)
        return MarkdownMergeOutcome(out.text, out.hasConflictMarkers, emptyList())
    }
    val chunks = mergeMarkdownBlocks(baseText, localText, remoteText, policy)
    return MarkdownMergeOutcome(
        markerText = chunks.toTwoWayConflictMarkerText(),
        hasConflicts = chunks.hasConflicts(),
        duplicateBlockIds = chunks.findDuplicateBlockIds(),
    )
}
