// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.merge

import dev.stapler.stelekit.model.ParsedBlock
import dev.stapler.stelekit.parser.MarkdownParser

/** Outcome of folding a page written to the wrong folder into the copy at its right place. */
sealed interface StrayPageMergeResult {
    /** [text] holds every block of both copies; [addedFromStray] counts blocks the notes copy did not already have. */
    data class Merged(val text: String, val addedFromStray: Int) : StrayPageMergeResult

    /** The two copies cannot be combined without risking a lost block; the caller must leave both untouched. */
    data class Refused(val reason: String) : StrayPageMergeResult
}

/**
 * Three-way merges a page that a bug wrote to the wrong folder ("stray") into the copy that already exists
 * at its correct location ("notes"), keeping both sides' blocks.
 *
 * The stray copy was never committed, so the two copies have no recorded common ancestor. An empty base with
 * [MergePolicy.Union] is the right ancestor for that case: each side's blocks count as additions, and a block
 * present on both sides is kept once.
 *
 * The block serializer writes content and properties only, so the merged text is re-parsed and every input
 * block must appear in it unchanged; anything less is [StrayPageMergeResult.Refused] rather than a silent loss.
 */
object StrayPageMerge {
    fun merge(notesText: String, strayText: String): StrayPageMergeResult {
        if (strayText.isBlank()) return StrayPageMergeResult.Merged(notesText, 0)
        listOf("notes" to notesText, "stray" to strayText).firstOrNull { (_, text) -> hasPreamble(text) }?.let { (name, _) ->
            return StrayPageMergeResult.Refused("the $name copy has content outside its block list")
        }
        val notes = parseBlocks(notesText)
        val stray = parseBlocks(strayText)
        if (notes.isEmpty()) return StrayPageMergeResult.Merged(strayText, stray.size)

        val outcome = mergeMarkdownWithPolicy(baseText = "", localText = notesText, remoteText = strayText, policy = MergePolicy.Union)
        if (outcome.hasConflicts) return StrayPageMergeResult.Refused("the copies conflict")
        if (outcome.duplicateBlockIds.isNotEmpty()) return StrayPageMergeResult.Refused("a block id appears on two different blocks")

        val text = if (notesText.endsWith("\n")) outcome.markerText.trimEnd('\n') + "\n" else outcome.markerText
        val merged = parseBlocks(text)
        val lost = (notes + stray).firstOrNull { it !in merged }
        if (lost != null) return StrayPageMergeResult.Refused("merging would alter or drop the block \"${lost.content.take(40)}\"")
        return StrayPageMergeResult.Merged(text, stray.count { it !in notes })
    }

    private fun parseBlocks(text: String): List<ParsedBlock> = flatten(MarkdownParser().parsePage(text).blocks)

    private fun flatten(blocks: List<ParsedBlock>): List<ParsedBlock> =
        blocks.flatMap { listOf(it.copy(children = emptyList())) + flatten(it.children) }

    /** True when the first non-blank line is not a block bullet (page properties, front matter or loose text). */
    private fun hasPreamble(text: String): Boolean =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart()?.startsWith("-") == false
}
