package dev.stapler.stelekit.merge

import dev.stapler.stelekit.model.BlockPropertyKeys

/**
 * Pure block-level merge (ADR-002). Additive only: no target block is removed, edited or reordered.
 * Incoming uuids are SOURCE uuids; inserted blocks get uuid' and `src-id`.
 */
fun mergePage(existing: MergePage?, incoming: MergePage, policy: MergePolicy): MergeOutcome {
    val remap = UuidRemap.compute(policy.sourceGraphId, incoming.blocks)
    if (existing == null) {
        val merger = SiblingMerger(policy, remap, remap, incoming.name, HashSet())
        return MergeOutcome.New(incoming.copy(blocks = incoming.blocks.map { merger.remapSubtree(it, it.uuid?.let(remap::get)) }))
    }
    // Ref targets depend on matching and matching compares ref-rewritten content, so iterate to a fixpoint.
    val onPage = pageUuids(existing.blocks)
    val aligned = alignedRefs(existing.blocks, incoming.blocks, policy)
    // An earlier copy of S already at uuid' stays S's ref target even if a later copy took another uuid,
    // unless S itself (same uuid) is on the page.
    val fixed = aligned + remap.filterValues { it in onPage } + aligned.filter { (s, t) -> s == t }
    var refs = remap + fixed
    var merger = SiblingMerger(policy, remap, refs, incoming.name, HashSet(onPage))
    var blocks = merger.mergeSiblings(existing.blocks, incoming.blocks)
    var passes = 0
    while (passes++ < MAX_REF_PASSES) {
        val next = remap + merger.resolved + fixed
        if (next == refs) break
        refs = next
        merger = SiblingMerger(policy, remap, refs, incoming.name, HashSet(onPage))
        blocks = merger.mergeSiblings(existing.blocks, incoming.blocks)
    }
    val (props, clashes) = unionProperties(existing.properties, incoming.properties)
    if (merger.added == 0 && merger.conflicts.isEmpty() && props == existing.properties) return MergeOutcome.Unchanged
    return MergeOutcome.Merged(existing.copy(properties = props, blocks = blocks), merger.added, merger.conflicts.toList(), clashes)
}

private const val MAX_REF_PASSES = 8

private fun pageUuids(blocks: List<MergeBlock>): MutableSet<String> {
    val out = HashSet<String>()
    fun walk(bs: List<MergeBlock>) {
        bs.forEach { b ->
            b.uuid?.let(out::add)
            walk(b.children)
        }
    }
    walk(blocks)
    return out
}

/**
 * Source uuid -> uuid of the target block it aligns with: same uuid, else `src-id` copy; an unlabeled incoming
 * block aligns by ref-masked content (labeled ones must not: masked text is not identity). Walks both trees in parallel and descends even when contents differ, which sibling matching cannot:
 * it never enters a conflicted parent, and compares a parent's refs before its children have resolved.
 */
private fun alignedRefs(
    target: List<MergeBlock>,
    incoming: List<MergeBlock>,
    policy: MergePolicy,
    out: MutableMap<String, String> = HashMap(),
): Map<String, String> {
    val used = HashSet<MergeBlock>()
    fun sameMasked(a: MergeBlock, b: MergeBlock) = policy.blockKey(UuidRemap.maskRefs(a.content)) == policy.blockKey(UuidRemap.maskRefs(b.content))
    val hits = incoming.map { inc ->
        val s = inc.uuid ?: return@map null
        val ref = SourceBlockRef.of(policy.sourceGraphId, s).value
        val cands = target.filter { (it.uuid == s || it.properties[MergePropertyKeys.SRC_ID] == ref) && it !in used }
        (cands.firstOrNull { it.properties[MergePropertyKeys.CONFLICT] != "true" } ?: cands.firstOrNull())?.also { used += it }
    }
    incoming.forEachIndexed { i, inc ->
        val hit = hits[i]
            ?: inc.takeIf { it.uuid == null }?.let { u -> target.firstOrNull { it !in used && sameMasked(it, u) }?.also { used += it } }
            ?: return@forEachIndexed
        inc.uuid?.let { s -> hit.uuid?.let { out[s] = it } }
        alignedRefs(hit.children, inc.children, policy, out)
    }
    return out
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
    /** source uuid -> uuid that `((ref))`s should point at; differs from [remap] where a block deduped onto a target block. */
    private val refs: Map<String, String>,
    private val pageName: String,
    /** Every uuid on the page so far; an inserted block never reuses one. */
    private val usedUuids: MutableSet<String>,
) {
    var added = 0
        private set
    val conflicts = mutableListOf<BlockConflict>()

    /** Source uuid -> uuid of the existing target block it matched (dedup or conflict); inserted blocks are absent. */
    val resolved = HashMap<String, String>()

    private fun key(content: String) = policy.blockKey(content)

    private fun srcRef(uuid: String) = SourceBlockRef.of(policy.sourceGraphId, uuid).value

    /**
     * [record] is off under a conflict sibling: the first copy never entered it, so refs must keep resolving
     * to the original blocks on a repeat too.
     */
    fun mergeSiblings(target: List<MergeBlock>, incoming: List<MergeBlock>, record: Boolean = true): List<MergeBlock> {
        val out = target.map { Entry(it, existing = true) }.toMutableList()
        val taken = HashSet<Entry>()
        val matched = arrayOfNulls<Entry>(incoming.size)
        // The block a ref to S should hit: the original, not a conflict sibling, so a repeat copy resolves like the first.
        val stand = arrayOfNulls<Entry>(incoming.size)

        // Identity first so a content match can't steal a block that a later uuid/src-id match needs.
        val byUuid = out.filter { it.block.uuid != null }.groupBy { it.block.uuid }
        val bySrc = out.filter { MergePropertyKeys.SRC_ID in it.block.properties }.groupBy { it.block.properties[MergePropertyKeys.SRC_ID] }
        incoming.forEachIndexed { i, inc ->
            val s = inc.uuid ?: return@forEachIndexed
            val cands = ((byUuid[s] ?: emptyList()) + (bySrc[srcRef(s)] ?: emptyList())).distinct().filter { it !in taken }
            // Equal content wins so a prior conflict sibling absorbs the repeat copy (R4).
            val incKey = key(UuidRemap.rewriteRefs(inc.content, refs))
            val pick = cands.firstOrNull { key(it.block.content) == incKey }
                ?: cands.firstOrNull { it.block.properties[MergePropertyKeys.CONFLICT] != "true" }
                ?: cands.firstOrNull()
            if (pick != null) {
                matched[i] = pick
                val original = cands.firstOrNull { it.block.properties[MergePropertyKeys.CONFLICT] != "true" } ?: pick
                stand[i] = original
                // Reserve the original too, or a repeat copy (which picks its conflict sibling) frees it for content matching to steal.
                taken += pick
                taken += original
            }
        }
        val byKey = out.groupBy { key(it.block.content) }
        incoming.forEachIndexed { i, inc ->
            if (matched[i] != null) return@forEachIndexed
            val pick = byKey[key(UuidRemap.rewriteRefs(inc.content, refs))]?.firstOrNull { it !in taken } ?: return@forEachIndexed
            matched[i] = pick
            stand[i] = pick
            taken += pick
        }

        var anchor = -1
        incoming.forEachIndexed { i, inc ->
            val m = matched[i]
            if (record && m != null) inc.uuid?.let { s -> (stand[i] ?: m).block.uuid?.let { resolved[s] = it } }
            if (m == null) {
                val at = insertionIndex(out, anchor)
                val sub = remapSubtree(inc, claim(inc.uuid))
                out.add(at, Entry(sub, existing = false))
                added += size(sub)
                anchor = at
                return@forEachIndexed
            }
            val idx = out.indexOf(m)
            if (key(m.block.content) == key(UuidRemap.rewriteRefs(inc.content, refs))) {
                m.block = m.block.copy(
                    children = mergeSiblings(m.block.children, inc.children, record && m.block.properties[MergePropertyKeys.CONFLICT] != "true"),
                )
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
        val incKey = key(UuidRemap.rewriteRefs(inc.content, refs))
        if (out.any { it.block.properties[MergePropertyKeys.SRC_ID] == ref && key(it.block.content) == incKey }) return null
        val uuid = UuidRemap.conflictUuid(policy.sourceGraphId, s, incKey)
        usedUuids += uuid
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

    /**
     * uuid' for a source uuid, unless the page already has it. That happens when a conflict sibling re-copies a
     * subtree whose first copy is still there, and a duplicate uuid would be INSERT OR REPLACEd over it.
     */
    private fun claim(sourceUuid: String?): String? {
        val plain = sourceUuid?.let(remap::get) ?: return null
        if (usedUuids.add(plain)) return plain
        val alt = UuidRemap.conflictUuid(policy.sourceGraphId, sourceUuid, "collision:$plain")
        usedUuids += alt
        return alt
    }

    /** [newUuid] is the uuid for [b] (uuid', or the conflict uuid); descendants get a [claim]ed uuid'. */
    fun remapSubtree(b: MergeBlock, newUuid: String?): MergeBlock {
        val props = LinkedHashMap(b.properties)
        props.remove(BlockPropertyKeys.ID)
        if (b.uuid != null) props[MergePropertyKeys.SRC_ID] = srcRef(b.uuid)
        return MergeBlock(
            uuid = newUuid,
            content = UuidRemap.rewriteRefs(b.content, refs),
            properties = props,
            children = b.children.map { remapSubtree(it, claim(it.uuid)) },
        )
    }
}
