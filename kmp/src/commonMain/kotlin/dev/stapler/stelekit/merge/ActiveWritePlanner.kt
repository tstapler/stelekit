package dev.stapler.stelekit.merge

import dev.stapler.stelekit.db.MarkdownPageParser
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockPropertyKeys
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.ParsedBlock
import dev.stapler.stelekit.outliner.JournalUtils
import dev.stapler.stelekit.util.FractionalIndexing
import dev.stapler.stelekit.util.UuidGenerator

/** Pure planning for [ActiveTargetWriter]: aligns a merged page with the DB tree and builds the blocks to insert. */
internal object ActiveWritePlanner {
    class Node(val block: Block, val merge: MergeBlock, val children: List<Node>)

    class Ctx(val pageUuid: PageUuid, val pagePath: String, val now: kotlin.time.Instant) {
        /** Set when an inserted block cannot be stored as merged (see [MergeConverters.storedShape]); the plan then fails. */
        var unrenderable: String? = null
    }

    fun buildTree(blocks: List<Block>): List<Node> {
        val byParent = blocks.groupBy { it.parentUuid }
        fun build(parent: BlockUuid?): List<Node> = byParent[parent].orEmpty().sortedBy { it.position }.map { b ->
            val kids = build(b.uuid)
            Node(b, MergeConverters.mergeBlockOf(b, kids.map { it.merge }), kids)
        }
        return build(null)
    }

    fun flatten(n: Node): List<Block> = listOf(n.block) + n.children.flatMap(::flatten)

    private fun sameBlock(e: MergeBlock, m: MergeBlock) = e.uuid == m.uuid && e.content == m.content && e.properties == m.properties

    /**
     * Aligns merged siblings with existing ones in order (same rule as the markdown writer); the rest
     * become [out] inserts positioned between their neighbours. False when an existing block has no counterpart.
     */
    fun plan(existing: List<Node>, merged: List<MergeBlock>, parent: Block?, ctx: Ctx, out: MutableList<Block>): Boolean {
        val matches = arrayOfNulls<Node>(merged.size)
        var j = 0
        merged.forEachIndexed { i, m ->
            val e = existing.getOrNull(j)
            if (e != null && sameBlock(e.merge, m)) matches[i] = e.also { j++ }
        }
        if (j != existing.size) return false
        var prev: Block? = null
        merged.forEachIndexed { i, m ->
            val e = matches[i]
            if (e != null) {
                if (!plan(e.children, m.children, e.block, ctx, out)) return false
                prev = e.block
                return@forEachIndexed
            }
            val next = (i + 1 until merged.size).firstNotNullOfOrNull { matches[it]?.block?.position }
            prev = insert(m, parent, i, prev, FractionalIndexing.generateKeyBetween(prev?.position, next), ctx, out) ?: return false
        }
        return true
    }

    private fun insert(m: MergeBlock, parent: Block?, index: Int, left: Block?, position: String, ctx: Ctx, out: MutableList<Block>): Block? {
        val shape = MergeConverters.storedShape(m, ctx.pagePath)
        if (shape == null) {
            ctx.unrenderable = m.content.take(40).replace("\n", "\\n")
            return null
        }
        val level = (parent?.level ?: -1) + 1
        val uuid = m.uuid ?: MarkdownPageParser.generateUuid(
            ParsedBlock(m.content, m.properties, level), ctx.pagePath, index, parent?.uuid?.value,
        )
        val props = LinkedHashMap<String, String>()
        if (m.uuid != null) props[BlockPropertyKeys.ID] = uuid
        props.putAll(shape.properties.filterKeys { it != BlockPropertyKeys.ID })
        val block = Block(
            uuid = BlockUuid(uuid),
            pageUuid = ctx.pageUuid,
            parentUuid = parent?.uuid,
            leftUuid = left?.uuid,
            content = shape.content,
            level = level,
            position = position,
            createdAt = ctx.now,
            updatedAt = ctx.now,
            properties = props,
            blockType = shape.blockType,
        )
        out += block
        var prev: Block? = null
        m.children.forEachIndexed { i, c ->
            prev = insert(c, block, i, prev, FractionalIndexing.generateKeyBetween(prev?.position, null), ctx, out) ?: return null
        }
        return block
    }

    fun newPageRow(key: PageKey, merged: MergePage, path: String, now: kotlin.time.Instant) = Page(
        uuid = PageUuid(UuidGenerator.generateV7()),
        name = key.name,
        filePath = path,
        createdAt = now,
        updatedAt = now,
        properties = merged.properties,
        isJournal = key.isJournal,
        journalDate = merged.journalDate ?: if (key.isJournal) JournalUtils.parseJournalDate(key.name) else null,
    )

    class Removal {
        val doomed = mutableSetOf<BlockUuid>()
        val removed = mutableSetOf<String>()
        val edited = mutableSetOf<String>()
        val found = mutableSetOf<String>()
    }

    /** A block edited since the copy stays, but its children are still considered. */
    fun prune(nodes: List<Node>, uuids: Set<String>, hashes: Map<String, String>, acc: Removal) {
        for (n in nodes) {
            val u = n.merge.uuid
            if (u != null && u in uuids) {
                acc.found += u
                if (hashes[u] == BlockContentHash.of(n.merge)) {
                    markRemoved(n, uuids, acc)
                    continue
                }
                acc.edited += u
            }
            prune(n.children, uuids, hashes, acc)
        }
    }

    private fun markRemoved(n: Node, uuids: Set<String>, acc: Removal) {
        acc.doomed += n.block.uuid
        n.merge.uuid?.takeIf { it in uuids }?.let { acc.removed += it; acc.found += it }
        n.children.forEach { markRemoved(it, uuids, acc) }
    }
}
