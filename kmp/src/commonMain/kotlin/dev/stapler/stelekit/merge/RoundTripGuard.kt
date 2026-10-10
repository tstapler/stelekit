package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.Block

/**
 * Why a page cannot be safely rewritten off-graph. A `Left` of this type means the writer must
 * write nothing: share queues to the inbox, copy fails that page and keeps it staged for Retry.
 */
sealed interface NotRoundTrippable {
    val message: String

    /** The existing file does not parse. */
    data class ParseFailed(val detail: String) : NotRoundTrippable {
        override val message get() = "Existing file does not parse: $detail"
    }

    /** A fence, heading or prose continuation absorbed the appended bullet (known non-outline pages). */
    data class AppendAbsorbed(val detail: String) : NotRoundTrippable {
        override val message get() = "Appended block would be absorbed by existing content: $detail"
    }

    /** Adding the block would change a pre-existing block or the page properties. */
    data class ExistingContentChanged(val detail: String) : NotRoundTrippable {
        override val message get() = "Splice would change existing content: $detail"
    }

    /** The new block landed somewhere other than the last child of the intended parent. */
    data class Misplaced(val detail: String) : NotRoundTrippable {
        override val message get() = "Inserted block misplaced: $detail"
    }

    /** The splice altered original bytes instead of only adding lines (a splicer bug). */
    data class BytesChanged(val detail: String) : NotRoundTrippable {
        override val message get() = "Splice altered untouched bytes: $detail"
    }

    data class UnresolvableParent(val path: List<Int>) : NotRoundTrippable {
        override val message get() = "No block at path $path"
    }
}

/**
 * Splice-based guard (Spike 0.1.4: measured 99.68% on a real 11k-file graph; exact
 * `serialize(parse(f)) == f` only 12%). Instead of asking whether the file re-serializes
 * identically, it asks the question the writer cares about: after inserting the new block,
 * is every untouched line byte-identical and does every pre-existing block parse unchanged?
 */
object RoundTripGuard {
    private const val ROOT_PROBE = "merge-guard-probe-root"
    private const val CHILD_PROBE = "merge-guard-probe-child"

    /** Pre-flight: splices a probe last root block and a probe last child of the first bullet. */
    fun probe(original: String, pagePath: String, isJournal: Boolean): Either<NotRoundTrippable, Unit> {
        if (original.isEmpty()) return Unit.right()
        val root = SpliceRequest(listOf(BlockInsertion(emptyList(), MergeBlock(null, ROOT_PROBE))))
        splice(original, root, pagePath, isJournal).onLeft { return it.left() }
        val firstBullet = MarkdownSplicer.firstBulletPath(original) ?: return Unit.right() // nothing to nest under
        val child = SpliceRequest(listOf(BlockInsertion(firstBullet, MergeBlock(null, CHILD_PROBE))))
        // Parent identity is not probed: on prose-style pages the line scan and the parser disagree
        // about the tree, and real nested inserts are parent-checked by [splice] at write time anyway.
        return verifiedSplice(original, child, pagePath, isJournal, checkParents = false).map { }
    }

    /** Splices [request] into [original] and verifies the result by re-parsing it. */
    fun splice(
        original: String,
        request: SpliceRequest,
        pagePath: String,
        isJournal: Boolean,
    ): Either<NotRoundTrippable, SpliceResult> = verifiedSplice(original, request, pagePath, isJournal, checkParents = true)

    private fun verifiedSplice(
        original: String,
        request: SpliceRequest,
        pagePath: String,
        isJournal: Boolean,
        checkParents: Boolean,
    ): Either<NotRoundTrippable, SpliceResult> {
        val result = when (val r = MarkdownSplicer.splice(original, request)) {
            is Either.Left -> return NotRoundTrippable.UnresolvableParent(r.value.pathOrEmpty()).left()
            is Either.Right -> r.value
        }
        if (original.isEmpty()) return result.right()
        verifyBytes(original, result.text)?.let { return it.left() }
        val before = parse(original, pagePath, isJournal) { NotRoundTrippable.ParseFailed(it) }
        val after = parse(result.text, pagePath, isJournal) { NotRoundTrippable.ExistingContentChanged("spliced text no longer parses: $it") }
        if (before is Either.Left) return before
        if (after is Either.Left) return after
        check(before is Either.Right && after is Either.Right)
        verifyStructure(before.value, after.value, request.takeIf { checkParents }, result)?.let { return it.left() }
        return result.right()
    }

    private fun SpliceError.pathOrEmpty(): List<Int> = (this as SpliceError.ParentNotFound).path

    private fun parse(
        text: String,
        pagePath: String,
        isJournal: Boolean,
        onFail: (String) -> NotRoundTrippable,
    ): Either<NotRoundTrippable, ParsedMarkdown> = try {
        MergeConverters.parseMarkdown(text, pagePath, name = "guard", isJournal = isJournal).right()
    } catch (e: Exception) {
        onFail("${e::class.simpleName}: ${e.message?.take(120)}").left()
    }

    /** Insert-only means the original is a subsequence of the output, both by character and by whole line. */
    private fun verifyBytes(original: String, spliced: String): NotRoundTrippable? {
        var i = 0
        for (c in spliced) if (i < original.length && c == original[i]) i++
        if (i < original.length) return NotRoundTrippable.BytesChanged("original byte ${i + 1} of ${original.length} not found in order")
        val origLines = original.split("\n").map { it.trimEnd('\r') }
        val newLines = spliced.split("\n").map { it.trimEnd('\r') }
        var j = 0
        for (l in newLines) if (j < origLines.size && l == origLines[j]) j++
        return if (j < origLines.size) NotRoundTrippable.BytesChanged("line ${j + 1} altered") else null
    }

    private fun verifyStructure(before: ParsedMarkdown, after: ParsedMarkdown, request: SpliceRequest?, result: SpliceResult): NotRoundTrippable? {
        val expected = before.blocks.size + result.insertedBlocks
        if (after.blocks.size < expected) {
            return NotRoundTrippable.AppendAbsorbed("expected $expected blocks, parsed ${after.blocks.size}; ${tail(before)}")
        }
        for ((k, v) in before.page.properties) {
            if (after.page.properties[k] != v) return NotRoundTrippable.ExistingContentChanged("page property '$k'")
        }
        if (after.page.properties.size - before.page.properties.size != result.insertedPropertyKeys.size) {
            return NotRoundTrippable.ExistingContentChanged("page property count")
        }
        val isNew = BooleanArray(after.blocks.size)
        var i = 0
        after.blocks.forEachIndexed { j, b ->
            if (i < before.blocks.size && sameBlock(before.blocks[i], b)) i++ else isNew[j] = true
        }
        if (i < before.blocks.size) {
            val lost = before.blocks[i]
            return NotRoundTrippable.ExistingContentChanged("block #$i '${lost.content.take(40).replace("\n", "\\n")}'")
        }
        if (isNew.count { it } != result.insertedBlocks) {
            return NotRoundTrippable.ExistingContentChanged("block count ${before.blocks.size} -> ${after.blocks.size}")
        }
        return misplacedSibling(after.blocks, isNew) ?: request?.let { wrongParent(before, after, isNew, it) }
    }

    /**
     * The splicer places by its own line scan; the parser may disagree (e.g. dashes inside
     * fences). The parents the parser gives the new subtrees must be the ones the caller asked for.
     */
    private fun wrongParent(before: ParsedMarkdown, after: ParsedMarkdown, isNew: BooleanArray, request: SpliceRequest): NotRoundTrippable? {
        val byParent = before.blocks.groupBy { it.parentUuid }.mapValues { (_, v) -> v.sortedBy { it.position } }
        val expected = request.insertions.map { ins ->
            var node: Block? = null
            for (i in ins.parentPath) node = byParent[node?.uuid]?.getOrNull(i) ?: return NotRoundTrippable.UnresolvableParent(ins.parentPath)
            node?.uuid
        }
        val newIds = after.blocks.filterIndexed { j, _ -> isNew[j] }.map { it.uuid }.toSet()
        val actual = after.blocks.filterIndexed { j, b -> isNew[j] && b.parentUuid !in newIds }.map { it.parentUuid }
        return if (expected.groupingBy { it }.eachCount() == actual.groupingBy { it }.eachCount()) null
        else NotRoundTrippable.Misplaced("parser attaches the new block to a different parent than requested")
    }

    private fun tail(p: ParsedMarkdown) =
        "last block '${p.blocks.lastOrNull()?.content?.take(40)?.replace("\n", "\\n").orEmpty()}'"

    private fun sameBlock(x: Block, y: Block) =
        x.uuid == y.uuid && x.content == y.content && x.properties == y.properties &&
            x.level == y.level && x.parentUuid == y.parentUuid && x.position == y.position

    /** Among each parent's children every new block must follow every pre-existing one. */
    private fun misplacedSibling(blocks: List<Block>, isNew: BooleanArray): NotRoundTrippable? {
        val seenNew = HashSet<dev.stapler.stelekit.model.BlockUuid?>()
        blocks.forEachIndexed { j, b ->
            if (isNew[j]) seenNew += b.parentUuid
            else if (b.parentUuid in seenNew) return NotRoundTrippable.Misplaced("new block precedes sibling '${b.content.take(40)}'")
        }
        return null
    }
}
