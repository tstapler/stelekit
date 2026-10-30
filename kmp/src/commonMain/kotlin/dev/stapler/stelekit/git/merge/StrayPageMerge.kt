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
 * Merges a page that a bug wrote to the wrong folder ("stray") into the copy that already exists at its
 * correct location ("notes"), keeping both sides' blocks.
 *
 * The stray copy was never committed, so the two copies have no recorded common ancestor. Both are read as
 * block trees: a block with the same text on both sides is one block, and its children are merged the same way,
 * so two journals that each have a `Created Pages` block end up with one holding both sets of children. A block
 * only the stray copy has is added after its siblings, under the same parent. The notes copy's own lines are
 * kept as written; only the added blocks are re-indented to match it.
 *
 * The result is re-parsed and every input block must appear in it unchanged and under the same parent; anything
 * less is [StrayPageMergeResult.Refused] rather than a silent loss or a block moved under the wrong parent.
 */
object StrayPageMerge {
    private const val TAB_WIDTH = 2
    private const val NL = "\n"
    private val BULLET = Regex("""^(\s*)-(\s.*|)$""")
    private val BLOCK_ID = Regex("""^\s*id::\s*(\S+)\s*$""")

    private class Node(
        val indent: String,
        val header: String,
        val body: List<String>,
        val fromStray: Boolean,
        val children: MutableList<Node> = mutableListOf(),
    ) {
        /** Equal keys mean the same block; `collapsed::` is view state, not content, so it never splits a block in two. */
        val key: String =
            (listOf(header.trimEnd()) + body.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("collapsed::") }).joinToString(NL)
    }

    fun merge(notesText: String, strayText: String): StrayPageMergeResult {
        if (strayText.isBlank()) return StrayPageMergeResult.Merged(notesText, 0)
        listOf("notes" to notesText, "stray" to strayText).firstOrNull { (_, text) -> hasPreamble(text) }?.let { (name, _) ->
            return StrayPageMergeResult.Refused("the $name copy has content outside its block list")
        }
        val notes = parseBlocks(notesText)
        val stray = parseBlocks(strayText)
        if (notes.isEmpty()) return StrayPageMergeResult.Merged(strayText, stray.size)

        val notesTree = parseTree(notesText, fromStray = false)
        val unit = indentUnit(notesTree)
        val added = mergeInto(notesTree, parseTree(strayText, fromStray = true))
        if (hasDuplicateBlockId(notesTree)) return StrayPageMergeResult.Refused("a block id appears on two different blocks")

        val rendered = render(notesTree, parentIndent = "", unit = unit).joinToString(NL)
        val text = if (notesText.endsWith(NL)) "$rendered$NL" else rendered
        val mergedPaths = blockPaths(MarkdownParser().parsePage(text).blocks).toSet()
        val lost = (blockPaths(MarkdownParser().parsePage(notesText).blocks) + blockPaths(MarkdownParser().parsePage(strayText).blocks))
            .firstOrNull { it !in mergedPaths }
        if (lost != null) return StrayPageMergeResult.Refused("merging would alter or drop the block \"${lost.second.content.take(40)}\"")
        return StrayPageMergeResult.Merged(text, added)
    }

    /**
     * The fallback for pages [merge] refuses: puts every block of [strayText] after the last block of [notesText]
     * for the user to tidy by hand. The notes copy is untouched; any properties above the stray copy's first block
     * become a block of their own, and an `id::` the notes copy already uses is left off the appended block (two
     * blocks sharing an id would collide) — the only text this drops.
     */
    fun appendAll(notesText: String, strayText: String): StrayPageMergeResult.Merged {
        if (strayText.isBlank()) return StrayPageMergeResult.Merged(notesText, 0)
        val firstBullet = strayText.lines().indexOfFirst { BULLET.matches(it) }.let { if (it < 0) strayText.lines().size else it }
        val lines = strayText.lines()
        val preamble = lines.take(firstBullet).filter { it.isNotBlank() }
        val usedIds = notesText.lines().mapNotNull { BLOCK_ID.matchEntire(it)?.groupValues?.get(1) }.toMutableSet()
        val blockLines = lines.drop(firstBullet).dropLastWhile { it.isBlank() }.filter { line ->
            val id = BLOCK_ID.matchEntire(line)?.groupValues?.get(1)
            id == null || usedIds.add(id)
        }
        val appended = buildList {
            if (preamble.isNotEmpty()) {
                add("- Properties from the misplaced copy")
                preamble.forEach { add("  ${it.trim()}") }
            }
            addAll(blockLines)
        }
        val added = appended.count { BULLET.matches(it) }
        val base = notesText.trimEnd('\n')
        val text = (if (base.isEmpty()) "" else base + NL) + appended.joinToString(NL) + NL
        return StrayPageMergeResult.Merged(text, added)
    }

    /** Folds [incoming] into [target] in place and returns how many blocks (at any depth) were added. */
    private fun mergeInto(target: MutableList<Node>, incoming: List<Node>): Int {
        var added = 0
        val unmatched = target.toMutableList()
        for (node in incoming) {
            val twin = unmatched.firstOrNull { it.key == node.key }
            if (twin != null) {
                unmatched.remove(twin)
                added += mergeInto(twin.children, node.children)
            } else {
                target.add(node)
                added += 1 + countDescendants(node)
            }
        }
        return added
    }

    private fun countDescendants(node: Node): Int = node.children.sumOf { 1 + countDescendants(it) }

    /** Blank lines between blocks carry no content; they are dropped here and not re-emitted. */
    private fun parseTree(text: String, fromStray: Boolean): MutableList<Node> {
        class Entry(val indent: String, val header: String, val body: MutableList<String> = mutableListOf())
        val entries = mutableListOf<Entry>()
        for (line in text.lines()) {
            val match = BULLET.matchEntire(line)
            when {
                match != null -> entries += Entry(match.groupValues[1], match.groupValues[2].removePrefix(" "))
                entries.isNotEmpty() && line.isNotBlank() -> entries.last().body += line
            }
        }
        val roots = mutableListOf<Node>()
        val ancestors = mutableListOf<Pair<Int, Node>>()
        for (entry in entries) {
            val node = Node(entry.indent, entry.header, entry.body.toList(), fromStray)
            val depth = width(entry.indent)
            while (ancestors.isNotEmpty() && ancestors.last().first >= depth) ancestors.removeAt(ancestors.lastIndex)
            if (ancestors.isEmpty()) roots += node else ancestors.last().second.children += node
            ancestors += depth to node
        }
        return roots
    }

    /** The whitespace one nesting level adds in the notes copy (its first nested block's extra indent), else a tab. */
    private fun indentUnit(tree: List<Node>): String {
        for (node in tree) {
            val child = node.children.firstOrNull() ?: continue
            if (child.indent.startsWith(node.indent) && child.indent.length > node.indent.length) {
                return child.indent.removePrefix(node.indent)
            }
        }
        return "\t"
    }

    private fun render(nodes: List<Node>, parentIndent: String, unit: String): List<String> =
        nodes.flatMap { node ->
            val indent = if (node.fromStray) parentIndent else node.indent
            val header = indent + "- " + node.header
            val body = node.body.map { line -> indent + if (line.startsWith(node.indent)) line.removePrefix(node.indent) else line.trimStart() }
            val childIndent = node.children.firstOrNull { !it.fromStray }?.indent ?: (indent + unit)
            listOf(header) + body + render(node.children, childIndent, unit)
        }

    private fun width(indent: String): Int = indent.sumOf { if (it == '\t') TAB_WIDTH else 1 }

    private fun hasDuplicateBlockId(tree: List<Node>): Boolean {
        val seen = mutableSetOf<String>()
        fun walk(nodes: List<Node>): Boolean = nodes.any { node ->
            val id = node.body.firstNotNullOfOrNull { BLOCK_ID.matchEntire(it)?.groupValues?.get(1) }
            (id != null && !seen.add(id)) || walk(node.children)
        }
        return walk(tree)
    }

    private fun parseBlocks(text: String): List<ParsedBlock> = flatten(MarkdownParser().parsePage(text).blocks)

    private fun flatten(blocks: List<ParsedBlock>): List<ParsedBlock> =
        blocks.flatMap { listOf(it.copy(children = emptyList())) + flatten(it.children) }

    /**
     * Each block with the text of every ancestor, so a block under a different parent is a different path.
     * Trailing whitespace and `collapsed::` are ignored, matching how blocks are paired in [merge].
     */
    private fun blockPaths(blocks: List<ParsedBlock>, ancestors: List<String> = emptyList()): List<Pair<List<String>, ParsedBlock>> =
        blocks.flatMap { block ->
            val content = block.content.trimEnd()
            val comparable = block.copy(content = content, properties = block.properties - "collapsed", children = emptyList())
            listOf(ancestors to comparable) + blockPaths(block.children, ancestors + content)
        }

    /** True when the first non-blank line is not a block bullet (page properties, front matter or loose text). */
    private fun hasPreamble(text: String): Boolean =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart()?.startsWith("-") == false
}
