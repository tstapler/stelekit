package dev.stapler.stelekit.git.merge

import dev.stapler.stelekit.model.ParsedBlock
import dev.stapler.stelekit.parser.MarkdownParser
import dev.stapler.stelekit.util.ContentHasher
import dev.stapler.stelekit.util.indentContinuationLines

/** One chunk of a three-way block merge: either agreed blocks, or a genuine conflict. */
sealed class BlockDiff3Chunk {
    data class Stable(val blocks: List<ParsedBlock>) : BlockDiff3Chunk()
    data class Conflict(val base: List<ParsedBlock>, val local: List<ParsedBlock>, val remote: List<ParsedBlock>) : BlockDiff3Chunk()
}

/**
 * Three-way merge at block granularity instead of [Diff3]'s line granularity — the "semantic
 * merge" this project's block-tree model (`Block.uuid`/`parentUuid`, `id::` properties) makes
 * possible.
 *
 * Reuses the exact same LCS-alignment technique as [Diff3] (align base-vs-local and base-vs-
 * remote, walk both alignments together, auto-resolve non-overlapping regions) — see [Diff3]'s
 * doc for the general approach. The only real difference is the *key* two blocks are compared
 * by: [Diff3] compares raw text lines; this compares [blockKey], which is content-derived (an
 * explicit `id::` property when present, else normalized content + nesting level) rather than
 * the sequential-sibling-index-derived UUID `MarkdownPageParser.generateUuid` assigns at parse
 * time. That UUID is NOT stable across independently-parsed base/local/remote copies whenever an
 * earlier sibling was inserted or deleted — the common edit case — so it cannot be used as a
 * free alignment key the way [dev.stapler.stelekit.db.DiffMerge] uses it for single-parse DB
 * reconciliation. Content is stable across independent parses, so it's used here instead.
 *
 * Including [ParsedBlock.level] in the fallback key (rather than content alone) is deliberate:
 * a block reparented to a different level, even with unchanged text, must NOT silently anchor-
 * match — that's exactly the "hierarchy corruption invisible to line-diffing" failure mode a
 * semantic merge exists to catch. A whole reparented subtree still auto-resolves cleanly when
 * only one side touched it (every descendant's key shifts together, so the whole region falls
 * into a single non-overlapping gap); it only becomes a [BlockDiff3Chunk.Conflict] when both
 * sides moved/edited the same region differently.
 *
 * ponytail: level-based reparenting detection is a heuristic, not a full parent-chain identity —
 * two blocks with identical content and the same absolute level in unrelated parts of a large
 * document could in principle cross-match. [diff]'s LCS is order-preserving, so it prefers the
 * closest positional alignment (the same protection line-level [Diff3] already relies on), which
 * makes this rare in practice. Upgrade path if it ever bites: key on a recursive parent-chain
 * content hash instead of raw `level`.
 */
object BlockDiff3 {
    fun merge(base: List<ParsedBlock>, local: List<ParsedBlock>, remote: List<ParsedBlock>): List<BlockDiff3Chunk> {
        val baseFlat = flatten(base)
        val localFlat = flatten(local)
        val remoteFlat = flatten(remote)

        val localMatch = matchArray(baseFlat.map(::blockKey), localFlat.map(::blockKey))
        val remoteMatch = matchArray(baseFlat.map(::blockKey), remoteFlat.map(::blockKey))

        val chunks = mutableListOf<BlockDiff3Chunk>()
        var prevBase = -1
        var prevLocal = -1
        var prevRemote = -1

        fun addGap(baseGap: List<ParsedBlock>, localGap: List<ParsedBlock>, remoteGap: List<ParsedBlock>) {
            if (baseGap.isEmpty() && localGap.isEmpty() && remoteGap.isEmpty()) return
            when {
                localGap == remoteGap -> chunks.add(BlockDiff3Chunk.Stable(localGap))
                localGap == baseGap -> chunks.add(BlockDiff3Chunk.Stable(remoteGap))
                remoteGap == baseGap -> chunks.add(BlockDiff3Chunk.Stable(localGap))
                else -> chunks.add(BlockDiff3Chunk.Conflict(baseGap, localGap, remoteGap))
            }
        }

        var s = 0
        while (s <= baseFlat.size) {
            val isEnd = s == baseFlat.size
            val isAnchor = !isEnd && localMatch[s] >= 0 && remoteMatch[s] >= 0
            if (isEnd || isAnchor) {
                val thisLocal = if (isEnd) localFlat.size else localMatch[s]
                val thisRemote = if (isEnd) remoteFlat.size else remoteMatch[s]
                addGap(
                    baseFlat.subList(prevBase + 1, s),
                    localFlat.subList(prevLocal + 1, thisLocal),
                    remoteFlat.subList(prevRemote + 1, thisRemote),
                )
                // A matched position means base/local/remote agree on block IDENTITY (blockKey),
                // not necessarily identical content — true for the id:: key, which intentionally
                // matches across a content edit (that's the point of a stable identity key). Run
                // the anchor position through the same three-way compare as a gap, rather than
                // assuming the match implies equality — otherwise a content-only edit to an
                // id::-tagged block would silently be discarded in favor of base's stale content.
                if (!isEnd) addGap(listOf(baseFlat[s]), listOf(localFlat[thisLocal]), listOf(remoteFlat[thisRemote]))
                prevBase = s
                prevLocal = thisLocal
                prevRemote = thisRemote
            }
            s++
        }

        return mergeAdjacentStable(chunks)
    }

    private fun mergeAdjacentStable(chunks: List<BlockDiff3Chunk>): List<BlockDiff3Chunk> {
        val result = mutableListOf<BlockDiff3Chunk>()
        for (chunk in chunks) {
            val last = result.lastOrNull()
            if (chunk is BlockDiff3Chunk.Stable && last is BlockDiff3Chunk.Stable) {
                result[result.size - 1] = BlockDiff3Chunk.Stable(last.blocks + chunk.blocks)
            } else {
                result.add(chunk)
            }
        }
        return result
    }

    /** Pre-order flatten; children are dropped from each node since [level] already encodes depth. */
    private fun flatten(blocks: List<ParsedBlock>): List<ParsedBlock> =
        blocks.flatMap { listOf(it.copy(children = emptyList())) + flatten(it.children) }

    private fun blockKey(block: ParsedBlock): String {
        val id = block.properties["id"]
        return if (!id.isNullOrBlank()) "id:$id" else "content:${block.level}:${ContentHasher.normalizeForHash(block.content)}"
    }

    private fun matchArray(a: List<String>, b: List<String>): IntArray {
        val result = IntArray(a.size) { -1 }
        for (op in diff(a, b)) {
            if (op is SequenceOp.Equal) {
                for (offset in 0 until (op.i2 - op.i1)) {
                    result[op.i1 + offset] = op.j1 + offset
                }
            }
        }
        return result
    }
}

/** Parses [baseText]/[localText]/[remoteText] and runs [BlockDiff3.merge] over the resulting block trees. */
fun mergeMarkdownBlocks(baseText: String, localText: String, remoteText: String): List<BlockDiff3Chunk> {
    val parser = MarkdownParser()
    return BlockDiff3.merge(
        parser.parsePage(baseText).blocks,
        parser.parsePage(localText).blocks,
        parser.parsePage(remoteText).blocks,
    )
}

fun List<BlockDiff3Chunk>.hasConflicts(): Boolean = any { it is BlockDiff3Chunk.Conflict }

/** A single `id::` value carried by more than one distinct block across the merge inputs. */
data class DuplicateBlockId(val id: String, val occurrences: Int)

/**
 * Flags `id::` values reused by more than one block. A duplicate `id::` on two DIFFERENT logical
 * blocks (as opposed to the same block matched across sides) is a data-integrity problem this
 * merge can't fix on its own — block-ref lookups elsewhere in the app resolve by `id::`, so a
 * collision would make one of the two blocks unreachable by reference. Surfaced as a warning for
 * the caller to show, not auto-resolved.
 */
fun List<BlockDiff3Chunk>.findDuplicateBlockIds(): List<DuplicateBlockId> {
    val allBlocks = flatMap { chunk ->
        when (chunk) {
            is BlockDiff3Chunk.Stable -> chunk.blocks
            is BlockDiff3Chunk.Conflict -> chunk.local + chunk.remote
        }
    }
    return allBlocks.mapNotNull { it.properties["id"]?.takeIf(String::isNotBlank) }
        .groupingBy { it }.eachCount()
        .filterValues { it > 1 }
        .map { (id, count) -> DuplicateBlockId(id, count) }
}

/**
 * Formats [BlockDiff3Chunk]s into the same plain two-way marker format
 * [Diff3.toTwoWayConflictMarkerText] produces, so the result is a drop-in for
 * [dev.stapler.stelekit.git.ConflictResolver.parseConflictFile] and the existing hunk-resolution
 * UI — a block merge never needs its own conflict-marker format or its own UI. A conflict block
 * is never split mid-block since chunk boundaries are computed at block granularity.
 */
fun List<BlockDiff3Chunk>.toTwoWayConflictMarkerText(localLabel: String = "HEAD", remoteLabel: String = "origin"): String {
    val result = mutableListOf<String>()
    for (chunk in this) {
        when (chunk) {
            is BlockDiff3Chunk.Stable -> chunk.blocks.forEach { result.addAll(serializeFlatBlock(it)) }
            is BlockDiff3Chunk.Conflict -> {
                result.add("<<<<<<< $localLabel")
                chunk.local.forEach { result.addAll(serializeFlatBlock(it)) }
                result.add("=======")
                chunk.remote.forEach { result.addAll(serializeFlatBlock(it)) }
                result.add(">>>>>>> $remoteLabel")
            }
        }
    }
    return result.joinToString("\n")
}

/** Mirrors [dev.stapler.stelekit.db.LogseqPageSerializer]'s per-block indent/property format. */
private fun serializeFlatBlock(block: ParsedBlock): List<String> {
    val indent = "\t".repeat(block.level)
    val lines = mutableListOf("$indent- ${block.content.indentContinuationLines("$indent\t")}")
    block.properties.forEach { (key, value) -> lines.add("$indent\t$key:: $value") }
    return lines
}
