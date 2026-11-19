// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** What became of one raw element. */
internal sealed interface ElementOutcome {
    class Kept(val element: ExcalidrawElement, val issues: List<ImportIssue>) : ElementOutcome
    class Dropped(val issue: ImportIssue?) : ElementOutcome
    class Deleted : ElementOutcome
    class UnknownType(val type: String) : ElementOutcome
}

/**
 * Field-level sanitising of one Excalidraw element (Story 5.1.1b): numbers must be finite and within
 * `maxCoord`, point lists within `maxPointsPerElement`, text within `maxTextChars`. Only the raw JSON is
 * inspected; no scene type is imported. Numbers are read through [CanonicalJson.doubleOf], never `.double`,
 * because `String.toDouble` does not round-trip on wasm.
 */
internal class ElementSanitizer(private val limits: SceneLimits) {
    private sealed interface Num {
        data class Value(val v: Double) : Num
        data object Absent : Num
        data class Bad(val reason: ImportReason, val field: String) : Num
    }

    fun sanitize(obj: JsonObject, index: Int): ElementOutcome {
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return ElementOutcome.UnknownType(MISSING_TYPE)
        if ((obj["isDeleted"] as? JsonPrimitive)?.booleanOrNull == true) return ElementOutcome.Deleted()
        val unsupported = type in UNSUPPORTED_TYPES
        if (type !in KNOWN_TYPES && !unsupported) return ElementOutcome.UnknownType(type)

        val issues = ArrayList<ImportIssue>()
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
            ?: "$GENERATED_ID_PREFIX$index".also { issues += ImportIssue(ImportReason.MissingId, "elements[$index]") }
        val target = id

        val geometry = DoubleArray(GEOMETRY_FIELDS.size)
        for ((i, field) in GEOMETRY_FIELDS.withIndex()) {
            when (val n = coordinate(obj, field)) {
                is Num.Value -> geometry[i] = n.v
                Num.Absent -> geometry[i] = 0.0
                is Num.Bad -> return ElementOutcome.Dropped(ImportIssue(n.reason, target, n.field))
            }
        }
        val angle = when (val n = finite(obj, "angle")) {
            is Num.Value -> n.v
            Num.Absent -> 0.0
            is Num.Bad -> return ElementOutcome.Dropped(ImportIssue(n.reason, target, n.field))
        }
        for (field in STYLE_NUMBERS) {
            val n = finite(obj, field)
            if (n is Num.Bad) return ElementOutcome.Dropped(ImportIssue(n.reason, target, n.field))
        }
        if (type in POINT_TYPES) checkPoints(obj, "points", target)?.let { return ElementOutcome.Dropped(it) }
        if (type == "image") checkPair(obj, "scale", target)?.let { return ElementOutcome.Dropped(it) }

        var raw: JsonObject = obj
        for (field in TEXT_FIELDS) {
            val text = (obj[field] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (text.length > limits.maxTextChars) {
                raw = JsonObject(raw + (field to JsonPrimitive(text.take(limits.maxTextChars))))
                if (issues.none { it.reason == ImportReason.TextTruncated }) issues += ImportIssue(ImportReason.TextTruncated, target, field)
            }
        }
        if (id != (obj["id"] as? JsonPrimitive)?.content) raw = JsonObject(raw + ("id" to JsonPrimitive(id)))

        val element = ExcalidrawElement(
            type = type,
            id = id,
            x = geometry[GEO_X], y = geometry[GEO_Y], width = geometry[GEO_WIDTH], height = geometry[GEO_HEIGHT],
            angle = angle,
            groupIds = (obj["groupIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty(),
            frameId = (obj["frameId"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            index = (obj["index"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            locked = (obj["locked"] as? JsonPrimitive)?.booleanOrNull ?: false,
            link = (obj["link"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            unsupported = unsupported,
            raw = raw,
        )
        return ElementOutcome.Kept(element, issues)
    }

    private fun read(obj: JsonObject, field: String): Num {
        val e = obj[field]
        if (e == null || e is JsonNull) return Num.Absent
        if (e !is JsonPrimitive) return Num.Bad(ImportReason.InvalidNumber, field)
        if (e.isString) return Num.Bad(if (e.content in NON_FINITE_TEXT) ImportReason.NonFiniteCoordinate else ImportReason.InvalidNumber, field)
        val d = CanonicalJson.doubleOf(e) ?: return Num.Bad(if (e.content in NON_FINITE_TEXT) ImportReason.NonFiniteCoordinate else ImportReason.InvalidNumber, field)
        return Num.Value(d)
    }

    private fun coordinate(obj: JsonObject, field: String): Num = when (val n = read(obj, field)) {
        is Num.Value -> when {
            !n.v.isFinite() -> Num.Bad(ImportReason.NonFiniteCoordinate, field)
            !limits.isValidCoord(n.v) -> Num.Bad(ImportReason.CoordinateOutOfRange, field)
            else -> n
        }
        else -> n
    }

    private fun finite(obj: JsonObject, field: String): Num = when (val n = read(obj, field)) {
        is Num.Value -> if (n.v.isFinite()) n else Num.Bad(ImportReason.NonFiniteCoordinate, field)
        else -> n
    }

    private fun pointOf(e: JsonElement): Num? {
        val pair = e as? JsonArray ?: return Num.Bad(ImportReason.InvalidNumber, "points")
        if (pair.size < PAIR_SIZE) return Num.Bad(ImportReason.InvalidNumber, "points")
        for (i in 0 until PAIR_SIZE) {
            val p = pair[i] as? JsonPrimitive ?: return Num.Bad(ImportReason.InvalidNumber, "points")
            val d = if (p.isString) null else CanonicalJson.doubleOf(p)
            if (d == null) return Num.Bad(if (p.content in NON_FINITE_TEXT) ImportReason.NonFiniteCoordinate else ImportReason.InvalidNumber, "points")
            if (!d.isFinite()) return Num.Bad(ImportReason.NonFiniteCoordinate, "points")
            if (!limits.isValidCoord(d)) return Num.Bad(ImportReason.CoordinateOutOfRange, "points")
        }
        return null
    }

    private fun checkPoints(obj: JsonObject, field: String, target: String): ImportIssue? {
        val e = obj[field] ?: return null
        if (e is JsonNull) return null
        val arr = e as? JsonArray ?: return ImportIssue(ImportReason.InvalidNumber, target, field)
        if (arr.size > limits.maxPointsPerElement) return ImportIssue(ImportReason.TooManyPoints, target, "$field has ${arr.size} points")
        for (p in arr) {
            val bad = pointOf(p) as? Num.Bad ?: continue
            return ImportIssue(bad.reason, target, field)
        }
        return null
    }

    private fun checkPair(obj: JsonObject, field: String, target: String): ImportIssue? {
        val e = obj[field] ?: return null
        if (e is JsonNull) return null
        val bad = pointOf(e) as? Num.Bad ?: return null
        return ImportIssue(bad.reason, target, field)
    }

    companion object {
        const val MISSING_TYPE = "(missing)"
        private const val GENERATED_ID_PREFIX = "import-"
        private const val PAIR_SIZE = 2
        private const val GEO_X = 0
        private const val GEO_Y = 1
        private const val GEO_WIDTH = 2
        private const val GEO_HEIGHT = 3

        val KNOWN_TYPES = setOf("rectangle", "diamond", "ellipse", "text", "line", "arrow", "freedraw", "image", "frame")

        /** OD-6: kept as grey placeholders with a count. `embeddable` and `iframe` URLs stay inert text. */
        val UNSUPPORTED_TYPES = setOf("embeddable", "iframe", "magicframe")
        private val POINT_TYPES = setOf("line", "arrow", "freedraw")
        private val GEOMETRY_FIELDS = listOf("x", "y", "width", "height")
        private val STYLE_NUMBERS = listOf("strokeWidth", "opacity", "roughness", "fontSize", "lineHeight")
        private val TEXT_FIELDS = listOf("text", "originalText")
        private val NON_FINITE_TEXT = setOf("NaN", "Infinity", "-Infinity", "+Infinity")
    }
}

/** Whole-list repairs that need every element: duplicate ids and cyclic `frameId` chains, each linear in the element count. */
object ElementSetRepair {
    suspend fun repair(elements: List<ExcalidrawElement>, issues: MutableList<ImportIssue>, budget: ParseBudget): List<ExcalidrawElement> =
        clearCycles(dedupe(elements, issues, budget), issues, budget)

    private suspend fun dedupe(elements: List<ExcalidrawElement>, issues: MutableList<ImportIssue>, budget: ParseBudget): List<ExcalidrawElement> {
        val seen = HashSet<String>()
        val firstIds = elements.map { it.id }.toHashSet()
        // Next suffix to try per id: suffixes below it are already taken, so repeated ids do not rescan them.
        val nextSuffix = HashMap<String, Int>()
        val out = ArrayList<ExcalidrawElement>(elements.size)
        for (e in elements) {
            budget.tick(0, 1)
            if (seen.add(e.id)) {
                out += e
                continue
            }
            var n = nextSuffix[e.id] ?: FIRST_SUFFIX
            while ("${e.id}-$n" in seen || "${e.id}-$n" in firstIds) n++
            nextSuffix[e.id] = n + 1
            val fresh = "${e.id}-$n"
            seen += fresh
            issues += ImportIssue(ImportReason.DuplicateId, e.id, "renamed to $fresh")
            out += e.copy(id = fresh, raw = JsonObject(e.raw + ("id" to JsonPrimitive(fresh))))
        }
        return out
    }

    /** Three-colour walk of the `frameId` chains: each id is visited once, so N elements cost O(N). */
    private suspend fun clearCycles(elements: List<ExcalidrawElement>, issues: MutableList<ImportIssue>, budget: ParseBudget): List<ExcalidrawElement> {
        val frameOf = elements.associate { it.id to it.frameId }
        val done = HashSet<String>()
        val cyclic = HashSet<String>()
        for (e in elements) {
            budget.tick(0, 1)
            if (e.id in done) continue
            val path = ArrayList<String>()
            val onPath = HashSet<String>()
            var cur: String? = e.id
            while (cur != null && cur !in done && onPath.add(cur)) {
                path += cur
                cur = frameOf[cur]
            }
            if (cur != null && cur in onPath) cyclic += path.subList(path.indexOf(cur), path.size)
            done += path
            budget.tick(0, path.size / WALK_TICK_DIVISOR)
        }
        if (cyclic.isEmpty()) return elements
        return elements.map { e ->
            if (e.id !in cyclic) return@map e
            issues += ImportIssue(ImportReason.CyclicFrame, e.id, "frameId cleared")
            e.copy(frameId = null, raw = JsonObject(e.raw - "frameId"))
        }
    }

    private const val FIRST_SUFFIX = 2
    private const val WALK_TICK_DIVISOR = 8
}
