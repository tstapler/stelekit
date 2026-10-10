package dev.stapler.stelekit.merge

import dev.stapler.stelekit.model.BlockPropertyKeys

/**
 * Pure block-level merge (ADR-002). Additive only: no target block is removed, edited or reordered.
 * Incoming uuids are SOURCE uuids; inserted blocks get uuid' and `src-id`.
 */
fun mergePage(existing: MergePage?, incoming: MergePage, policy: MergePolicy): MergeOutcome {
    val remap = UuidRemap.compute(policy.sourceGraphId, incoming.blocks)
    val merger = SiblingMerger(policy, remap, incoming.name)
    if (existing == null) {
        return MergeOutcome.New(incoming.copy(blocks = incoming.blocks.map { merger.remapSubtree(it, it.uuid?.let(remap::get)) }))
    }
    val blocks = merger.mergeSiblings(existing.blocks, incoming.blocks)
    val (props, clashes) = unionProperties(existing.properties, incoming.properties)
    if (merger.added == 0 && merger.conflicts.isEmpty() && props == existing.properties) return MergeOutcome.Unchanged
    return MergeOutcome.Merged(existing.copy(properties = props, blocks = blocks), merger.added, merger.conflicts.toList(), clashes)
}

private val UNION_KEYS = setOf(BlockPropertyKeys.ALIAS, BlockPropertyKeys.TAGS)
private val NEVER_MERGED = setOf(BlockPropertyKeys.ID, BlockPropertyKeys.COLLAPSED)

private fun unionProperties(target: Map<String, String>, incoming: Map<String, String>): Pair<Map<String, String>, List<String>> {
    val out = LinkedHashMap(target)
    val clashes = mutableListOf<String>()
    for ((k, v) in incoming) {
        if (k in NEVER_MERGED) continue
        val t = target[k]
        when {
            t == null -> out[k] = v
            k in UNION_KEYS -> {
                val have = t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val extra = v.split(',').map { it.trim() }.filter { it.isNotEmpty() && it !in have }.distinct()
                if (extra.isNotEmpty()) out[k] = (have + extra).joinToString(", ")
            }
            t != v -> clashes += k
        }
    }
    return out to clashes
}

private class Entry(var block: MergeBlock, val existing: Boolean)

private class SiblingMerger(
    private val policy: MergePolicy,
    private val remap: Map<String, String>,
    private val pageName: String,
) {
    var added = 0
        private set
    val conflicts = mutableListOf<BlockConflict>()

    private fun key(content: String) = policy.blockKey(content)

    private fun srcRef(uuid: String) = SourceBlockRef.of(policy.sourceGraphId, uuid).value

    fun mergeSiblings(target: List<MergeBlock>, incoming: List<MergeBlock>): List<MergeBlock> {
        val out = target.map { Entry(it, existing = true) }.toMutableList()
        val taken = HashSet<Entry>()
        val matched = arrayOfNulls<Entry>(incoming.size)

        // Identity first so a content match can't steal a block that a later uuid/src-id match needs.
        val byUuid = out.filter { it.block.uuid != null }.groupBy { it.block.uuid }
        val bySrc = out.filter { MergePropertyKeys.SRC_ID in it.block.properties }.groupBy { it.block.properties[MergePropertyKeys.SRC_ID] }
        incoming.forEachIndexed { i, inc ->
            val s = inc.uuid ?: return@forEachIndexed
            val cands = ((byUuid[s] ?: emptyList()) + (bySrc[srcRef(s)] ?: emptyList())).distinct().filter { it !in taken }
            // Equal content wins so a prior conflict sibling absorbs the repeat copy (R4).
            val pick = cands.firstOrNull { key(it.block.content) == key(inc.content) }
                ?: cands.firstOrNull { it.block.properties[MergePropertyKeys.CONFLICT] != "true" }
                ?: cands.firstOrNull()
            if (pick != null) {
                matched[i] = pick
                taken += pick
            }
        }
        val byKey = out.groupBy { key(it.block.content) }
        incoming.forEachIndexed { i, inc ->
            if (matched[i] != null) return@forEachIndexed
            val pick = byKey[key(inc.content)]?.firstOrNull { it !in taken } ?: return@forEachIndexed
            matched[i] = pick
            taken += pick
        }

        var anchor = -1
        incoming.forEachIndexed { i, inc ->
            val m = matched[i]
            if (m == null) {
                val at = insertionIndex(out, anchor)
                val sub = remapSubtree(inc, inc.uuid?.let(remap::get))
                out.add(at, Entry(sub, existing = false))
                added += size(sub)
                anchor = at
                return@forEachIndexed
            }
            val idx = out.indexOf(m)
            if (key(m.block.content) == key(inc.content)) {
                m.block = m.block.copy(children = mergeSiblings(m.block.children, inc.children))
                anchor = idx
            } else {
                anchor = idx
                addConflict(out, m, inc)?.let { anchor = it }
            }
        }
        return out.map { it.block }
    }

    /** Returns the insertion index, or null when an equal-content `src-id` sibling already exists. */
    private fun addConflict(out: MutableList<Entry>, matchedEntry: Entry, inc: MergeBlock): Int? {
        val s = inc.uuid ?: return null
        val ref = srcRef(s)
        val incKey = key(inc.content)
        if (out.any { it.block.properties[MergePropertyKeys.SRC_ID] == ref && key(it.block.content) == incKey }) return null
        val uuid = UuidRemap.conflictUuid(policy.sourceGraphId, s, incKey)
        val sub = remapSubtree(inc, uuid).let {
            it.copy(
                properties = it.properties + mapOf(
                    MergePropertyKeys.CONFLICT to "true",
                    MergePropertyKeys.CONFLICT_SOURCE to policy.sourceGraphName,
                ),
            )
        }
        val at = insertionIndex(out, out.indexOf(matchedEntry))
        out.add(at, Entry(sub, existing = false))
        conflicts += BlockConflict(matchedEntry.block.uuid, uuid, pageName)
        return at
    }

    /**
     * R3: append by default. Positional uuids are index-seeded, so inserting before an unlabeled
     * block would shift its uuid on re-parse; insert after the anchor only if everything after it is labeled.
     */
    private fun insertionIndex(out: List<Entry>, anchor: Int): Int {
        if (anchor < 0) return out.size
        val candidate = anchor + 1
        return if (out.subList(candidate, out.size).all { fullyLabeled(it.block) }) candidate else out.size
    }

    private fun fullyLabeled(b: MergeBlock): Boolean = b.uuid != null && b.children.all(::fullyLabeled)

    private fun size(b: MergeBlock): Int = 1 + b.children.sumOf(::size)

    /** [newUuid] is uuid' (or the conflict uuid) for [b]; descendants always use plain uuid'. */
    fun remapSubtree(b: MergeBlock, newUuid: String?): MergeBlock {
        val props = LinkedHashMap(b.properties)
        props.remove(BlockPropertyKeys.ID)
        if (b.uuid != null) props[MergePropertyKeys.SRC_ID] = srcRef(b.uuid)
        return MergeBlock(
            uuid = newUuid,
            content = UuidRemap.rewriteRefs(b.content, remap),
            properties = props,
            children = b.children.map { remapSubtree(it, it.uuid?.let(remap::get)) },
        )
    }
}
