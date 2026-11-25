// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.withoutOrphans
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.NameCounters
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Element-level three-way merge of two versions of one sheet (epic E14, ADR-008). Pure and total: the same three
 * documents give byte-identical output on every platform, in either argument order.
 *
 * - The unit is the element id; an element present on one side only is an add (or, when [base] had it, a delete).
 * - An element on both sides merges field by field against [base]: a field changed on one side takes that change, a field
 *   changed on both sides to different values takes the greater canonical JSON value (a total order, so the merge is
 *   commutative, idempotent and associative on content). Fields that only make sense together (`x`/`y`, `w`/`h`, `text`/`orig`,
 *   the opening's wall position) are one field.
 * - A delete against an edit keeps the edit. Constraints and layers merge by id the same way; paint order is rebuilt from
 *   layer order, z-key and id.
 * - The result is run through `reconcile`, openings of a missing wall are dropped and orphaned constraints removed; each repair
 *   is in the [MergeReport].
 *
 * [MergeRefusal] means the merge is undecidable here and the caller must keep its conflict copy.
 */
object SceneMerge {
    private const val TYPE_KEY = "type"
    private const val STYLE_PREFIX = "style."
    private const val EXTRAS_PREFIX = "extras."
    private const val STYLE_KEY = "style"
    private const val EXTRAS_KEY = "extras"
    private const val ID_KEY = "id"
    private const val VERSION_KEY = "ver"
    private const val FIRST_VERSION = 1
    private const val WHOLE = "*"

    /** Keys that only make sense together; the merge never mixes one from each side. */
    private val FIELD_GROUPS: List<List<String>> = listOf(
        listOf("x", "y"), listOf("cx", "cy"), listOf("rx", "ry"), listOf("w", "h"),
        listOf("text", "orig"), listOf("host", "seg", "off"), listOf("a0", "a1"),
    )
    private val GROUP_OF: Map<String, String> = FIELD_GROUPS.flatMap { g -> g.map { it to g.first() } }.toMap()

    fun merge(
        base: SketchDocumentV2,
        ours: SketchDocumentV2,
        theirs: SketchDocumentV2,
        limitsOverride: SceneLimits? = null,
    ): Either<MergeRefusal, MergeResult> {
        if (ours.sheetUuid != theirs.sheetUuid) return MergeRefusal.SheetMismatch.left()
        // Same limits on both sides keep them; differing ones fall back to the defaults, so the result never depends on argument order.
        val limits = limitsOverride ?: ours.scene.limits.takeIf { it == theirs.scene.limits } ?: SceneLimits.Default
        val whole = when {
            sameDoc(ours, theirs) -> ours
            sameDoc(ours, base) -> theirs
            sameDoc(theirs, base) -> ours
            else -> null
        }
        if (whole != null) return MergeResult(whole, reportAgainst(ours, whole, emptyList(), emptyList())).right()
        return try {
            Run(base, ours, theirs, limits).execute().right()
        } catch (a: Abort) {
            a.refusal.left()
        }
    }

    private class Abort(val refusal: MergeRefusal) : RuntimeException()

    private fun sameDoc(a: SketchDocumentV2, b: SketchDocumentV2): Boolean =
        a.scene == b.scene && a.constraints == b.constraints && a.extras == b.extras && a.nameCounters.issued == b.nameCounters.issued

    private fun SceneElement.content(): SceneElement = withBase(base.copy(version = 0))

    private fun sameContent(a: SceneElement, b: SceneElement): Boolean = a.content() == b.content()

    private fun canon(e: JsonElement): String = CanonicalJson.encode(e).fold({ e.toString() }, { it.decodeToString() })

    private fun bump(v: Int): Int = if (v == Int.MAX_VALUE) v else v + 1

    private fun reportAgainst(ours: SketchDocumentV2, merged: SketchDocumentV2, conflicts: List<MergeConflict>, repairs: List<MergeRepair>): MergeReport {
        val mine = ours.scene.elementsInOrder.associateBy { it.id }
        val theirsNow = merged.scene.elementsInOrder.associateBy { it.id }
        val added = theirsNow.keys.filter { it !in mine }.sortedBy { it.value }
        val removed = mine.keys.filter { it !in theirsNow }.sortedBy { it.value }
        val changed = theirsNow.filter { (id, e) -> mine[id]?.let { !sameContent(it, e) } == true }.keys.sortedBy { it.value }
        return MergeReport(added, changed, removed, conflicts, repairs, docChanged = !sameDoc(ours, merged))
    }

    /** The winner of a three-way field: a change beats the base, equal changes agree, two different changes go to the greater by [order]. */
    private class Pick<T>(val value: T, val conflict: Boolean)

    private fun <T> pick3(b: T, o: T, t: T, order: Comparator<in T>): Pick<T> = when {
        o == t -> Pick(o, false)
        o == b -> Pick(t, false)
        t == b -> Pick(o, false)
        else -> Pick(if (order.compare(o, t) >= 0) o else t, true)
    }

    private val jsonOrder = Comparator<JsonElement?> { x, y ->
        when {
            x == null && y == null -> 0
            x == null -> -1
            y == null -> 1
            else -> canon(x).compareTo(canon(y))
        }
    }

    private val tupleOrder = Comparator<List<JsonElement?>> { x, y ->
        var result = 0
        var i = 0
        while (result == 0 && i < minOf(x.size, y.size)) {
            result = jsonOrder.compare(x[i], y[i])
            i++
        }
        if (result != 0) result else x.size.compareTo(y.size)
    }

    private class Run(
        private val base: SketchDocumentV2,
        private val ours: SketchDocumentV2,
        private val theirs: SketchDocumentV2,
        private val limits: SceneLimits,
    ) {
        private val conflicts = ArrayList<MergeConflict>()
        private val repairs = ArrayList<MergeRepair>()

        fun execute(): MergeResult {
            val layers = mergeLayers()
            val extras = mergeExtras()
            val constraints = mergeConstraints()
            val elements = mergeElements(layers)
            if (constraints.size > limits.maxConstraints) throw Abort(MergeRefusal.CapacityExceeded("constraints"))
            if (layers.size > limits.maxLayers) throw Abort(MergeRefusal.CapacityExceeded("layers"))
            val orderOfLayer = layers.associate { it.id to it.order }
            val sorted = elements.values.sortedWith(
                compareBy<SceneElement>({ orderOfLayer[it.base.layerId] ?: 0 }, { it.base.zKey }, { it.id }),
            )
            var scene = SketchSceneV2.empty(limits).withLayers(layers.sortedWith(compareBy<Layer>({ it.order }, { it.id.value })))
            for (e in sorted) scene = scene.add(e).fold({ throw Abort(MergeRefusal.CapacityExceeded("elements")) }, { it })
            val counters = NameCounters.of((ours.nameCounters.issued.keys + theirs.nameCounters.issued.keys + base.nameCounters.issued.keys).associateWith { k ->
                maxOf(ours.nameCounters.issued[k] ?: 0, theirs.nameCounters.issued[k] ?: 0)
            })
            val repaired = reconcile(scene)
            repaired.reasons.forEach { repairs += MergeRepair.Reconciled(it) }
            val draft = SketchDocumentV2(repaired.scene, ours.sheetUuid, constraints, extras, counters)
            val doc = draft.withoutOrphans()
            val kept = doc.constraints.map { it.id }.toSet()
            draft.constraints.filter { it.id !in kept }.forEach { repairs += MergeRepair.ConstraintDropped(it.id) }
            return MergeResult(doc, reportAgainst(ours, doc, conflicts, repairs))
        }

        private fun mergeKeys(b: Set<String>, o: Set<String>, t: Set<String>): List<String> = (b + o + t).sorted()

        // Elements

        private fun mergeElements(layers: List<Layer>): Map<ElementId, SceneElement> {
            val b = base.scene.elementsInOrder.associateBy { it.id.value }
            val o = ours.scene.elementsInOrder.associateBy { it.id.value }
            val t = theirs.scene.elementsInOrder.associateBy { it.id.value }
            val out = LinkedHashMap<ElementId, SceneElement>()
            for (key in mergeKeys(b.keys, o.keys, t.keys)) {
                val kept = mergeOne(key, b[key], o[key], t[key]) ?: continue
                out[kept.id] = kept
            }
            val layerIds = layers.map { it.id }.toSet() + LayerId.Default
            for ((id, e) in out.entries.toList()) {
                if (e.base.layerId in layerIds) continue
                repairs += MergeRepair.MovedToDefaultLayer(id)
                out[id] = e.withBase(e.base.copy(layerId = LayerId.Default, version = bump(e.base.version)))
            }
            for ((id, e) in out.entries.toList()) {
                if (e is OpeningElement && out[e.hostId] !is WallElement) {
                    repairs += MergeRepair.OpeningDropped(id, e.hostId)
                    out.remove(id)
                }
            }
            return out
        }

        private fun mergeOne(id: String, b: SceneElement?, o: SceneElement?, t: SceneElement?): SceneElement? = when {
            o == null && t == null -> null
            o != null && t == null -> survivor(id, b, o)
            o == null && t != null -> survivor(id, b, t!!)
            else -> if (o == t) o else mergeBoth(id, b, o!!, t!!)
        }

        /** The element only one side still has: an add, a delete by the other side, or an edit that beats the other side's delete. */
        private fun survivor(id: String, b: SceneElement?, kept: SceneElement): SceneElement? = when {
            b == null -> kept
            sameContent(b, kept) -> null
            else -> kept.also { conflicts += MergeConflict(id, ConflictKind.DeleteVsEdit) }
        }

        private fun flat(e: SceneElement): Map<String, JsonElement> {
            val json = SheetCodecV2.elementJson(e).getOrNull() ?: throw Abort(MergeRefusal.Unencodable(e.id.value))
            val out = LinkedHashMap<String, JsonElement>()
            for ((k, v) in json) {
                when {
                    k == ID_KEY || k == VERSION_KEY -> Unit
                    (k == STYLE_KEY || k == EXTRAS_KEY) && v is JsonObject -> v.forEach { (sk, sv) -> out["$k.$sk"] = sv }
                    else -> out[k] = v
                }
            }
            return out
        }

        private fun unflat(id: String, fields: Map<String, JsonElement>): JsonObject {
            val top = LinkedHashMap<String, JsonElement>()
            val style = LinkedHashMap<String, JsonElement>()
            val extras = LinkedHashMap<String, JsonElement>()
            for ((k, v) in fields) {
                when {
                    k.startsWith(STYLE_PREFIX) -> style[k.removePrefix(STYLE_PREFIX)] = v
                    k.startsWith(EXTRAS_PREFIX) -> extras[k.removePrefix(EXTRAS_PREFIX)] = v
                    else -> top[k] = v
                }
            }
            top[ID_KEY] = JsonPrimitive(id)
            top[VERSION_KEY] = JsonPrimitive(FIRST_VERSION)
            if (style.isNotEmpty()) top[STYLE_KEY] = JsonObject(style)
            if (extras.isNotEmpty()) top[EXTRAS_KEY] = JsonObject(extras)
            return JsonObject(top)
        }

        private fun mergeBoth(id: String, b: SceneElement?, o: SceneElement, t: SceneElement): SceneElement {
            val fb = b?.let(::flat).orEmpty()
            val fo = flat(o)
            val ft = flat(t)
            val typeClash = fo[TYPE_KEY] != ft[TYPE_KEY]
            val fields = if (typeClash) wholeElement(id, fb, fo, ft) else fieldWise(id, if (b == null) ConflictKind.AddAdd else ConflictKind.SameField, fb, fo, ft)
            val merged = SheetCodecV2.elementFromJson(unflat(id, fields), limits).getOrNull()
            if (merged == null) {
                conflicts += MergeConflict(id, ConflictKind.Unbuildable)
                return if (jsonOrder.compare(JsonObject(fo), JsonObject(ft)) >= 0) o else t
            }
            val version = when {
                fields == fo && fields == ft -> maxOf(o.base.version, t.base.version)
                fields == fo -> if (o.base.version != t.base.version) o.base.version else bump(o.base.version)
                fields == ft -> if (t.base.version != o.base.version) t.base.version else bump(t.base.version)
                else -> bump(maxOf(o.base.version, t.base.version))
            }
            return merged.withBase(merged.base.copy(version = version))
        }

        /** A kind change is never merged field by field: the element is taken whole from the side that changed it, the greater when both did. */
        private fun wholeElement(id: String, fb: Map<String, JsonElement>, fo: Map<String, JsonElement>, ft: Map<String, JsonElement>): Map<String, JsonElement> {
            val pick = pick3(JsonObject(fb), JsonObject(fo), JsonObject(ft), jsonOrder)
            if (pick.conflict) conflicts += MergeConflict(id, ConflictKind.TypeChange, listOf(WHOLE))
            return pick.value
        }

        private fun fieldWise(id: String, clash: ConflictKind, fb: Map<String, JsonElement>, fo: Map<String, JsonElement>, ft: Map<String, JsonElement>): Map<String, JsonElement> {
            val groups = (fb.keys + fo.keys + ft.keys).groupBy { GROUP_OF[it] ?: it }
            val out = LinkedHashMap<String, JsonElement>()
            val clashed = ArrayList<String>()
            for (name in groups.keys.sorted()) {
                val keys = groups.getValue(name).sorted()
                val pick = pick3(keys.map { fb[it] }, keys.map { fo[it] }, keys.map { ft[it] }, tupleOrder)
                if (pick.conflict) clashed += name
                keys.forEachIndexed { i, k -> pick.value[i]?.let { out[k] = it } }
            }
            if (clashed.isNotEmpty()) conflicts += MergeConflict(id, clash, clashed)
            return out
        }

        // Layers

        private fun mergeLayers(): List<Layer> {
            val b = base.scene.layers.associateBy { it.id.value }
            val o = ours.scene.layers.associateBy { it.id.value }
            val t = theirs.scene.layers.associateBy { it.id.value }
            val out = ArrayList<Layer>()
            for (key in mergeKeys(b.keys, o.keys, t.keys)) {
                val bl = b[key]
                val ol = o[key]
                val tl = t[key]
                val kept = when {
                    ol == null && tl == null -> null
                    ol != null && tl != null -> mergeLayer(bl, ol, tl)
                    else -> {
                        val only = (ol ?: tl)!!
                        if (bl == null) only else if (bl == only) null else only.also { conflicts += MergeConflict(key, ConflictKind.Other) }
                    }
                }
                if (kept != null) out += kept
            }
            return out
        }

        private fun mergeLayer(b: Layer?, o: Layer, t: Layer): Layer {
            if (o == t) return o
            val bl = b ?: Layer(o.id, "", visible = false, locked = false, opacity = 0.0, order = 0)
            val name = pick3(bl.name, o.name, t.name, naturalOrder())
            val visible = pick3(bl.visible, o.visible, t.visible, naturalOrder())
            val locked = pick3(bl.locked, o.locked, t.locked, naturalOrder())
            val opacity = pick3(bl.opacity + 0.0, o.opacity + 0.0, t.opacity + 0.0, naturalOrder())
            val order = pick3(bl.order, o.order, t.order, naturalOrder())
            if (listOf(name.conflict, visible.conflict, locked.conflict, opacity.conflict, order.conflict).any { it }) {
                conflicts += MergeConflict(o.id.value, ConflictKind.Other)
            }
            return Layer(o.id, name.value, visible.value, locked.value, opacity.value, order.value)
        }

        // Constraints and sheet extras

        private fun mergeConstraints(): List<Constraint> {
            val b = base.constraints.associateBy { it.id.value }
            val o = ours.constraints.associateBy { it.id.value }
            val t = theirs.constraints.associateBy { it.id.value }
            val out = ArrayList<Constraint>()
            for (key in mergeKeys(b.keys, o.keys, t.keys)) {
                val bc = b[key]
                val oc = o[key]
                val tc = t[key]
                val kept = when {
                    oc == null && tc == null -> null
                    oc != null && tc != null -> pick3(bc, oc, tc, constraintOrder).also { if (it.conflict) conflicts += MergeConflict(key, ConflictKind.Other) }.value
                    else -> {
                        val only = (oc ?: tc)!!
                        if (bc == null) only else if (bc == only) null else only.also { conflicts += MergeConflict(key, ConflictKind.DeleteVsEdit) }
                    }
                }
                if (kept != null) out += kept
            }
            return out
        }

        private val constraintOrder = Comparator<Constraint?> { x, y -> constraintKey(x).compareTo(constraintKey(y)) }

        private fun constraintKey(c: Constraint?): String {
            if (c == null) return ""
            val targets = c.targets.joinToString(",") { "${it.elementId.value}/${it.part.label}" }
            val value = c.value?.let { CanonicalJson.formatDouble(it).getOrNull() }.orEmpty()
            return "${c.id.value.length}:${c.id.value}|${c.kind.name}|$targets|$value|${c.reference}"
        }

        private fun mergeExtras(): JsonObject {
            val out = LinkedHashMap<String, JsonElement>()
            for (key in mergeKeys(base.extras.keys, ours.extras.keys, theirs.extras.keys)) {
                val pick = pick3(base.extras[key], ours.extras[key], theirs.extras[key], jsonOrder)
                if (pick.conflict) conflicts += MergeConflict(key, ConflictKind.Other)
                pick.value?.let { out[key] = it }
            }
            return JsonObject(out)
        }
    }
}
