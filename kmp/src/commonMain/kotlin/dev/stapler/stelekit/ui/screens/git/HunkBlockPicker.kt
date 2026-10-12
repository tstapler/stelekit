// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

/**
 * Groups one side of a conflict hunk into outline blocks so the user can keep or drop each block
 * with one tap instead of editing raw text. A block starts at a `- ` bullet line (any indent) and
 * owns the lines that follow until the next bullet — its continuation lines and `key:: value`
 * properties. Lines before the first bullet form a leading block of their own.
 */
fun splitIntoBlocks(lines: List<String>): List<List<String>> {
    val blocks = mutableListOf<MutableList<String>>()
    for (line in lines) {
        val startsBlock = line.trimStart().let { it.startsWith("- ") || it == "-" }
        if (startsBlock || blocks.isEmpty()) blocks.add(mutableListOf())
        blocks.last().add(line)
    }
    return blocks
}

/** Which blocks of each side are kept; indices refer to [splitIntoBlocks] output. */
data class BlockPicks(val local: Set<Int>, val remote: Set<Int>) {
    companion object {
        val NONE = BlockPicks(emptySet(), emptySet())
        fun allLocal(localCount: Int) = BlockPicks(List(localCount) { it }.toSet(), emptySet())
        fun allRemote(remoteCount: Int) = BlockPicks(emptySet(), List(remoteCount) { it }.toSet())
        fun both(localCount: Int, remoteCount: Int) =
            BlockPicks(List(localCount) { it }.toSet(), List(remoteCount) { it }.toSet())
    }

    fun toggleLocal(index: Int) = copy(local = local.toggled(index))
    fun toggleRemote(index: Int) = copy(remote = remote.toggled(index))

    private fun Set<Int>.toggled(index: Int) = if (index in this) this - index else this + index
}

/**
 * The resolved text for [picks]: kept local blocks first, then kept remote blocks, each in
 * original order. A remote block identical to a kept local block is emitted once — "keep both"
 * on a hunk where both sides share a line must not duplicate it.
 */
fun composeResult(localBlocks: List<List<String>>, remoteBlocks: List<List<String>>, picks: BlockPicks): String {
    val kept = localBlocks.filterIndexed { i, _ -> i in picks.local }.toMutableList()
    remoteBlocks.filterIndexed { i, _ -> i in picks.remote }.forEach { block ->
        if (block !in kept) kept.add(block)
    }
    return kept.flatten().joinToString("\n")
}
