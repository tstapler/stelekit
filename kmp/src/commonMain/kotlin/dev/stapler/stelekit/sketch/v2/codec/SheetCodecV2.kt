// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.codec

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.constraints.withoutOrphans
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningKind
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import dev.stapler.stelekit.sketch.v2.scene.WallJustification
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BindingMode
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.ColorText
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.scene.ImageCrop
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.NameCounters
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.Roundness
import dev.stapler.stelekit.sketch.v2.scene.RoundnessKind
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.floor

/** Why a v2 sheet could not be written or read. `where` is a fixed path label such as `elements[3].x`, never user text. */
sealed interface SheetError {
    data class TooLarge(val bytes: Long, val limit: Long) : SheetError
    data class TooDeep(val depth: Int) : SheetError
    data class Malformed(val where: String) : SheetError

    /** The file is JSON but not a schema-2 sheet; [schema] is what it declared, if anything. */
    data class UnsupportedSchema(val schema: Long?) : SheetError
    data class NonFinite(val where: String) : SheetError
    data class OutOfRange(val where: String) : SheetError
    data class TooManyElements(val count: Int, val limit: Int) : SheetError
    data class TooManyConstraints(val count: Int, val limit: Int) : SheetError
    data class TooManyLayers(val count: Int, val limit: Int) : SheetError
    data class TooManyPoints(val index: Int, val count: Int, val limit: Int) : SheetError
    data class TextTooLong(val index: Int, val limit: Int) : SheetError
    data class NameTooLong(val where: String, val limit: Int) : SheetError
    data class InvalidElement(val index: Int, val field: String) : SheetError
    data class DuplicateElement(val index: Int) : SheetError
}

/**
 * The schema-2 sheet file (Story 1.1.5, ADR-001): pure, no I/O. Output is canonical JSON ([CanonicalJson]) with two
 * layout rules: `schema` then `sheetUuid` lead (so the 64-byte header probe sees the schema, D-105), and `elements`
 * hold one element per line so a git diff or merge touches one line per edited element. Equal documents give equal
 * bytes on every target. Doubles go in and out through [CanonicalJson] only (wasmJs `String.toDouble` does not round-trip).
 *
 * Decode treats the bytes as untrusted: size, nesting, element, constraint, layer, point and text bounds come from
 * [SceneLimits], every failure is a [SheetError] and nothing throws. A valid sheet is run through `reconcile` before
 * it is returned. Unknown keys inside an element are dropped; unknown fields an importer wants kept live in `extras`.
 */
object SheetCodecV2 {
    const val SCHEMA = 2

    private const val POINT_ARITY = 2

    private const val SCHEMA_KEY = "schema"
    private const val UUID_KEY = "sheetUuid"

    fun encode(doc: SketchDocumentV2, limits: SceneLimits = SceneLimits.Default): Either<SheetError, ByteArray> =
        try {
            encodeChecked(doc, limits)
        } catch (e: SheetWriteFailure) {
            SheetError.NonFinite(e.where).left()
        }

    private fun encodeChecked(doc: SketchDocumentV2, limits: SceneLimits): Either<SheetError, ByteArray> = either {
        val scene = doc.scene
        val elementLines = scene.elementsInOrder.map { canonical(ElementWriter().element(it).bind(), limits).bind() }
        val sb = StringBuilder()
        sb.append("{\"schema\":").append(SCHEMA).append(",\"sheetUuid\":").append(canonical(JsonPrimitive(doc.sheetUuid), limits).bind())
        sb.append(",\"constraints\":").append(canonical(JsonArray(doc.constraints.map { ElementWriter().constraint(it).bind() }), limits).bind())
        sb.append(",\"elements\":")
        if (elementLines.isEmpty()) sb.append("[]") else sb.append("[\n").append(elementLines.joinToString(",\n")).append("\n]")
        sb.append(",\"extras\":").append(canonical(doc.extras, limits).bind())
        sb.append(",\"layers\":").append(canonical(JsonArray(scene.layers.map { ElementWriter().layer(it).bind() }), limits).bind())
        if (!doc.nameCounters.isEmpty()) {
            val counters = JsonObject(doc.nameCounters.issued.entries.sortedBy { it.key }.associate { it.key to JsonPrimitive(it.value) })
            sb.append(",\"nameCounters\":").append(canonical(counters, limits).bind())
        }
        sb.append('}')
        val out = sb.toString().encodeToByteArray()
        if (out.size > limits.maxSheetBytes) raise(SheetError.TooLarge(out.size.toLong(), limits.maxSheetBytes))
        out
    }

    /** One element as the JSON object the sheet file holds for it (E14 merges per field on this form; the keys are the file's). */
    fun elementJson(element: SceneElement): Either<SheetError, JsonObject> =
        try {
            ElementWriter().element(element)
        } catch (e: SheetWriteFailure) {
            SheetError.NonFinite(e.where).left()
        }

    /** The inverse of [elementJson], with the same bounds and validation as [decode]; never throws. */
    fun elementFromJson(json: JsonObject, limits: SceneLimits = SceneLimits.Default): Either<SheetError, SceneElement> =
        try {
            either { with(ElementReader(limits)) { element(0, json) } }
        } catch (e: RuntimeException) {
            SheetError.Malformed(e::class.simpleName ?: "element").left()
        }

    fun decode(bytes: ByteArray, limits: SceneLimits = SceneLimits.Default): Either<SheetError, SketchDocumentV2> {
        if (bytes.size > limits.maxSheetBytes) return SheetError.TooLarge(bytes.size.toLong(), limits.maxSheetBytes).left()
        return try {
            decodeChecked(bytes, limits)
        } catch (e: RuntimeException) {
            // A model constructor refusing a value must surface as an error, never a throw out of untrusted input.
            SheetError.Malformed(e::class.simpleName ?: "document").left()
        }
    }

    private fun decodeChecked(bytes: ByteArray, limits: SceneLimits): Either<SheetError, SketchDocumentV2> = either {
        val parsed = CanonicalJson.decode(bytes, limits).mapLeft { it.toSheetError() }.bind()
        if (!allNumbersFinite(parsed)) raise(SheetError.NonFinite("document"))
        val root = parsed as? JsonObject ?: raise(SheetError.Malformed("document"))
        val reader = ElementReader(limits)
        val schema = (root[SCHEMA_KEY] as? JsonPrimitive)?.let { CanonicalJson.doubleOf(it) }?.toLong()
        if (schema != SCHEMA.toLong()) raise(SheetError.UnsupportedSchema(schema))
        val sheetUuid = (root[UUID_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: raise(SheetError.Malformed(UUID_KEY))
        val constraints = (root["constraints"] ?: JsonArray(emptyList())).let { it as? JsonArray ?: raise(SheetError.Malformed("constraints")) }
        if (constraints.size > limits.maxConstraints) raise(SheetError.TooManyConstraints(constraints.size, limits.maxConstraints))
        val typedConstraints = with(reader) { constraints(constraints) }
        val extras = (root["extras"] ?: JsonObject(emptyMap())) as? JsonObject ?: raise(SheetError.Malformed("extras"))
        val layers = with(reader) { layers(root["layers"]) }
        val elements = (root["elements"] ?: JsonArray(emptyList())) as? JsonArray ?: raise(SheetError.Malformed("elements"))
        if (elements.size > limits.maxElements) raise(SheetError.TooManyElements(elements.size, limits.maxElements))

        var scene = SketchSceneV2.empty(limits).withLayers(layers)
        elements.forEachIndexed { i, json ->
            val parsed = with(reader) { element(i, json) }
            // An element on a layer the sheet does not define is moved to the default layer (the same repair `reconcile` makes for other dangling references).
            val element = if (scene.layer(parsed.base.layerId) == null) parsed.withBase(parsed.base.copy(layerId = LayerId.Default)) else parsed
            scene = scene.add(element).mapLeft { SheetError.DuplicateElement(i) }.bind()
        }
        // A constraint whose operand is not in the sheet is dropped, the same repair `reconcile` makes for other dangling references.
        val counters = (root["nameCounters"] as? JsonObject)?.let { o ->
            NameCounters.of(o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { CanonicalJson.doubleOf(it) }?.toInt()?.let { k to it } }.toMap())
        } ?: NameCounters.Empty
        SketchDocumentV2(reconcile(scene).scene, sheetUuid, typedConstraints, extras, counters).withoutOrphans()
    }

    /** True when every non-string literal is a boolean, null or a finite number (so `extras` and constraints re-encode). */
    private fun allNumbersFinite(el: JsonElement): Boolean = when (el) {
        is JsonNull -> true
        is JsonPrimitive -> el.isString || el.content == "true" || el.content == "false" || CanonicalJson.doubleOf(el) != null
        is JsonArray -> el.all { allNumbersFinite(it) }
        is JsonObject -> el.values.all { allNumbersFinite(it) }
    }

    private fun CanonicalJsonError.toSheetError(): SheetError = when (this) {
        is CanonicalJsonError.TooDeep -> SheetError.TooDeep(depth)
        is CanonicalJsonError.TooMany -> SheetError.TooManyElements(count, limit)
        is CanonicalJsonError.NonFinite -> SheetError.NonFinite("document")
        is CanonicalJsonError.Malformed -> SheetError.Malformed("document")
    }

    private fun canonical(el: JsonElement, limits: SceneLimits): Either<SheetError, String> =
        CanonicalJson.encode(el, limits = limits).map { it.decodeToString() }.mapLeft { it.toSheetError() }

    // Writing

    private class ElementWriter {
        private fun Raise<SheetError>.n(d: Double, where: String): JsonPrimitive = CanonicalJson.number(d).mapLeft { SheetError.NonFinite(where) }.bind()
        private fun Raise<SheetError>.u(v: SceneUnit, where: String): JsonPrimitive = n(v.value, where)

        private fun Raise<SheetError>.points(points: List<ScenePoint>, where: String): JsonArray =
            JsonArray(points.map { JsonArray(listOf(u(it.x, where), u(it.y, where))) })

        fun constraint(c: Constraint): Either<SheetError, JsonObject> = either {
            buildJsonObject {
                put("id", c.id.value)
                put("kind", c.kind.name)
                put("targets", JsonArray(c.targets.map { t -> buildJsonObject { put("e", t.elementId.value); put("part", t.part.label) } }))
                c.value?.let { put("value", n(it, "constraints.value")) }
                if (c.reference) put("ref", true)
            }
        }

        fun layer(l: Layer): Either<SheetError, JsonObject> = either {
            buildJsonObject {
                put("id", l.id.value)
                put("name", l.name)
                put("visible", l.visible)
                put("locked", l.locked)
                put("opacity", n(l.opacity, "layers.opacity"))
                put("order", l.order)
            }
        }

        fun element(e: SceneElement): Either<SheetError, JsonObject> = either {
            val where = "element"
            buildJsonObject {
                writeBase(e.base, where)
                when (e) {
                    is RectElement -> { put("type", "rect"); box(e.x, e.y, e.width, e.height, e.angle, where); bound(e.boundElements) }
                    is DiamondElement -> { put("type", "diamond"); box(e.x, e.y, e.width, e.height, e.angle, where); bound(e.boundElements) }
                    is FrameElement -> { put("type", "frame"); box(e.x, e.y, e.width, e.height, e.angle, where) }
                    is EllipseElement -> {
                        put("type", "ellipse")
                        put("cx", u(e.cx, where)); put("cy", u(e.cy, where)); put("rx", u(e.rx, where)); put("ry", u(e.ry, where))
                        put("angle", n(e.angle, where))
                        bound(e.boundElements)
                    }
                    is LineElement -> { put("type", "line"); put("pts", points(e.points, where)); put("poly", e.polygon) }
                    is ArrowElement -> {
                        put("type", "arrow")
                        put("pts", points(e.points, where))
                        e.startBinding?.let { put("sb", binding(it, where)) }
                        e.endBinding?.let { put("eb", binding(it, where)) }
                        put("sah", e.startArrowhead.name)
                        put("eah", e.endArrowhead.name)
                        put("elbow", e.elbowed)
                    }
                    is FreedrawElement -> {
                        put("type", "freedraw")
                        put("pts", points(e.points, where))
                        put("pressures", JsonArray(e.pressures.map { n(it, where) }))
                        put("sim", e.simulatePressure)
                    }
                    is TextElement -> {
                        put("type", "text")
                        box(e.x, e.y, e.width, e.height, e.angle, where)
                        put("text", e.text)
                        put("orig", e.originalText)
                        put("fs", n(e.fontSize, where))
                        put("ff", e.fontFamily)
                        put("ta", e.textAlign.name)
                        put("va", e.verticalAlign.name)
                        e.containerId?.let { put("container", it.value) }
                        put("lh", n(e.lineHeight, where))
                        put("auto", e.autoResize)
                    }
                    is ImageElement -> {
                        put("type", "image")
                        box(e.x, e.y, e.width, e.height, e.angle, where)
                        e.fileId?.let { put("fileId", it) }
                        put("fx", e.flipX)
                        put("fy", e.flipY)
                        e.crop?.let { c ->
                            put("crop", buildJsonObject {
                                put("x", n(c.x, where)); put("y", n(c.y, where)); put("w", n(c.width, where)); put("h", n(c.height, where))
                                put("nw", n(c.naturalWidth, where)); put("nh", n(c.naturalHeight, where))
                            })
                        }
                    }
                    is PointElement -> { put("type", "point"); put("x", u(e.x, where)); put("y", u(e.y, where)) }
                    is WallElement -> {
                        put("type", "wall")
                        put("pts", points(e.points, where))
                        put("t", u(e.thickness, where))
                        put("just", e.justification.name)
                        put("closed", e.closed)
                    }
                    is OpeningElement -> {
                        put("type", "opening")
                        put("host", e.hostId.value)
                        put("seg", e.segment)
                        put("off", u(e.offset, where))
                        put("w", u(e.width, where))
                        put("kind", e.kind.name)
                        put("flipSide", e.flipSide)
                        put("flipHinge", e.flipHinge)
                        // Derived from the host on every edit and load; written so a load-and-save does not change the bytes.
                        put("x", u(e.x, where)); put("y", u(e.y, where)); put("h", u(e.height, where)); put("angle", n(e.angle, where))
                    }
                    is ArcElement -> {
                        put("type", "arc")
                        put("cx", u(e.cx, where)); put("cy", u(e.cy, where)); put("r", u(e.radius, where))
                        put("a0", n(e.startAngle, where)); put("a1", n(e.endAngle, where))
                    }
                }
            }
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.box(x: SceneUnit, y: SceneUnit, w: SceneUnit, h: SceneUnit, angle: Double, where: String) {
            // `either` is inlined by the caller, so the Raise receiver is the caller's; these are plain numbers here.
            put("x", CanonicalJson.number(x.value).getOrFail(where)); put("y", CanonicalJson.number(y.value).getOrFail(where))
            put("w", CanonicalJson.number(w.value).getOrFail(where)); put("h", CanonicalJson.number(h.value).getOrFail(where))
            put("angle", CanonicalJson.number(angle).getOrFail(where))
        }

        private fun Either<CanonicalJsonError, JsonPrimitive>.getOrFail(where: String): JsonPrimitive =
            fold({ throw SheetWriteFailure(where) }, { it })

        private fun kotlinx.serialization.json.JsonObjectBuilder.bound(refs: List<BoundRef>) {
            if (refs.isEmpty()) return
            put("bound", JsonArray(refs.map { r -> buildJsonObject { put("id", r.id.value); put("kind", r.kind.name) } }))
        }

        private fun Raise<SheetError>.binding(b: Binding, where: String): JsonObject = buildJsonObject {
            put("id", b.elementId.value)
            put("fx", n(b.fixedX, where))
            put("fy", n(b.fixedY, where))
            put("mode", b.mode.name)
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.writeBase(b: ElementBase, where: String) {
            put("id", b.id.value)
            put("layer", b.layerId.value)
            if (b.groupIds.isNotEmpty()) put("groups", JsonArray(b.groupIds.map { JsonPrimitive(it.value) }))
            b.frameId?.let { put("frame", it.value) }
            put("z", b.zKey.value)
            put("locked", b.locked)
            put("opacity", b.opacity)
            put("style", style(b.style, where))
            put("seed", b.seed)
            put("ver", b.version)
            put("ord", b.ordinal)
            b.name?.let { put("name", it) }
            if (b.extras.isNotEmpty()) put("extras", b.extras)
        }

        private fun style(s: ElementStyle, where: String): JsonObject = buildJsonObject {
            put("stroke", s.strokeColor)
            put("bg", s.backgroundColor)
            put("fill", s.fillStyle.name)
            put("sw", CanonicalJson.number(s.strokeWidth).getOrFail(where))
            put("ss", s.strokeStyle.name)
            put("rough", s.roughness)
            s.roundness?.let { r ->
                put("round", buildJsonObject {
                    put("kind", r.kind.name)
                    r.value?.let { put("value", CanonicalJson.number(it).getOrFail(where)) }
                })
            }
        }
    }

    private class SheetWriteFailure(val where: String) : RuntimeException(where)

    // Reading

    // One reader function per element kind and field type; splitting it would only scatter the shared limits.
    @Suppress("TooManyFunctions")
    private class ElementReader(private val limits: SceneLimits) {
        private fun Raise<SheetError>.obj(el: JsonElement?, where: String): JsonObject = el as? JsonObject ?: raise(SheetError.Malformed(where))

        private fun Raise<SheetError>.arr(el: JsonElement?, where: String): JsonArray = el as? JsonArray ?: raise(SheetError.Malformed(where))

        private fun Raise<SheetError>.str(o: JsonObject, key: String, where: String): String =
            (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: raise(SheetError.Malformed("$where.$key"))

        private fun strOr(o: JsonObject, key: String, default: String): String = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default

        private fun strOrNull(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun Raise<SheetError>.bool(o: JsonObject, key: String, default: Boolean, where: String): Boolean {
            val p = o[key] ?: return default
            return (p as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toBooleanStrictOrNull() ?: raise(SheetError.Malformed("$where.$key"))
        }

        private fun Raise<SheetError>.dbl(o: JsonObject, key: String, where: String): Double {
            val p = o[key] as? JsonPrimitive ?: raise(SheetError.Malformed("$where.$key"))
            return CanonicalJson.doubleOf(p) ?: raise(SheetError.NonFinite("$where.$key"))
        }

        private fun Raise<SheetError>.dblOr(o: JsonObject, key: String, default: Double, where: String): Double =
            if (o[key] == null) default else dbl(o, key, where)

        private fun Raise<SheetError>.int(o: JsonObject, key: String, default: Int, where: String): Int {
            if (o[key] == null) return default
            val d = dbl(o, key, where)
            if (d != floor(d) || abs(d) > Int.MAX_VALUE) raise(SheetError.OutOfRange("$where.$key"))
            return d.toInt()
        }

        private fun Raise<SheetError>.unit(o: JsonObject, key: String, where: String): SceneUnit = unitOf(dbl(o, key, where), "$where.$key")

        private fun Raise<SheetError>.unitOf(d: Double, where: String): SceneUnit = SceneUnit.of(d, limits).mapLeft { SheetError.OutOfRange(where) }.bind()

        private fun Raise<SheetError>.points(el: JsonElement?, index: Int, where: String): List<ScenePoint> {
            val list = arr(el ?: JsonArray(emptyList()), "$where.pts")
            if (list.size > limits.maxPointsPerElement) raise(SheetError.TooManyPoints(index, list.size, limits.maxPointsPerElement))
            return list.map { p ->
                val pair = arr(p, "$where.pts")
                val x = pair.getOrNull(0) as? JsonPrimitive
                val y = pair.getOrNull(1) as? JsonPrimitive
                if (pair.size != POINT_ARITY || x == null || y == null) raise(SheetError.Malformed("$where.pts"))
                ScenePoint(
                    unitOf(CanonicalJson.doubleOf(x) ?: raise(SheetError.NonFinite("$where.pts")), "$where.pts"),
                    unitOf(CanonicalJson.doubleOf(y) ?: raise(SheetError.NonFinite("$where.pts")), "$where.pts"),
                )
            }
        }

        private inline fun <reified E : Enum<E>> Raise<SheetError>.enum(o: JsonObject, key: String, default: E, where: String): E {
            val name = strOrNull(o, key) ?: return default
            return enumValues<E>().firstOrNull { it.name == name } ?: raise(SheetError.Malformed("$where.$key"))
        }

        fun Raise<SheetError>.constraints(list: JsonArray): List<Constraint> {
            val seen = HashSet<String>()
            return list.mapIndexed { i, json ->
                val where = "constraints[$i]"
                val o = obj(json, where)
                val id = str(o, "id", where)
                val kind = enum(o, "kind", ConstraintKind.Coincident, where).also { if (strOrNull(o, "kind") == null) raise(SheetError.Malformed("$where.kind")) }
                val targets = arr(o["targets"], "$where.targets").map { t ->
                    val to = obj(t, "$where.targets")
                    val part = TargetPart.parse(str(to, "part", where), limits.maxPointsPerElement) ?: raise(SheetError.Malformed("$where.targets"))
                    ConstraintTarget(ElementId(str(to, "e", where)), part)
                }
                val value = if (o["value"] == null) null else dbl(o, "value", where)
                val c = Constraint(ConstraintId(id), kind, targets, value, bool(o, "ref", false, where))
                if (c.shapeProblem() != null || !seen.add(id)) raise(SheetError.Malformed(where))
                c
            }
        }

        fun Raise<SheetError>.layers(el: JsonElement?): List<Layer> {
            val list = arr(el ?: JsonArray(emptyList()), "layers")
            if (list.size > limits.maxLayers) raise(SheetError.TooManyLayers(list.size, limits.maxLayers))
            val seen = HashSet<String>()
            return list.mapIndexed { i, json ->
                val where = "layers[$i]"
                val o = obj(json, where)
                val id = str(o, "id", where)
                if (id.isBlank() || id.length > limits.maxLayerIdChars || !seen.add(id)) raise(SheetError.InvalidElement(i, "layer.id"))
                val name = strOr(o, "name", id)
                if (name.length > limits.maxNameChars) raise(SheetError.NameTooLong(where, limits.maxNameChars))
                val opacity = dblOr(o, "opacity", 1.0, where)
                if (opacity < 0.0 || opacity > 1.0) raise(SheetError.OutOfRange("$where.opacity"))
                Layer(LayerId(id), name, bool(o, "visible", true, where), bool(o, "locked", false, where), opacity, int(o, "order", 0, where))
            }
        }

        fun Raise<SheetError>.element(index: Int, json: JsonElement): SceneElement {
            val where = "elements[$index]"
            val o = obj(json, where)
            val base = base(o, index, where)
            return when (str(o, "type", where)) {
                "rect" -> RectElement(base, unit(o, "x", where), unit(o, "y", where), unit(o, "w", where), unit(o, "h", where), dblOr(o, "angle", 0.0, where), bound(o, where))
                "diamond" -> DiamondElement(base, unit(o, "x", where), unit(o, "y", where), unit(o, "w", where), unit(o, "h", where), dblOr(o, "angle", 0.0, where), bound(o, where))
                "frame" -> FrameElement(base, unit(o, "x", where), unit(o, "y", where), unit(o, "w", where), unit(o, "h", where), dblOr(o, "angle", 0.0, where))
                "ellipse" -> ellipse(o, base, index, where)
                "line" -> LineElement(base, points(o["pts"], index, where), bool(o, "poly", false, where))
                "arrow" -> ArrowElement(
                    base, points(o["pts"], index, where), binding(o["sb"], where), binding(o["eb"], where),
                    enum(o, "sah", Arrowhead.None, where), enum(o, "eah", Arrowhead.Arrow, where), bool(o, "elbow", false, where),
                )
                "freedraw" -> freedraw(o, base, index, where)
                "text" -> text(o, base, index, where)
                "image" -> image(o, base, where)
                "point" -> PointElement(base, unit(o, "x", where), unit(o, "y", where))
                "arc" -> arc(o, base, index, where)
                "wall" -> wall(o, base, index, where)
                "opening" -> opening(o, base, index, where)
                else -> raise(SheetError.InvalidElement(index, "type"))
            }
        }

        private fun Raise<SheetError>.ellipse(o: JsonObject, base: ElementBase, index: Int, where: String): SceneElement {
            val rx = unit(o, "rx", where)
            val ry = unit(o, "ry", where)
            if (rx.value <= 0.0 || ry.value <= 0.0) raise(SheetError.InvalidElement(index, "radius"))
            return EllipseElement(base, unit(o, "cx", where), unit(o, "cy", where), rx, ry, dblOr(o, "angle", 0.0, where), bound(o, where))
        }

        private fun Raise<SheetError>.wall(o: JsonObject, base: ElementBase, index: Int, where: String): SceneElement {
            val pts = points(o["pts"], index, where)
            val closed = bool(o, "closed", false, where)
            val thickness = unit(o, "t", where)
            val minPoints = if (closed) WallElement.MIN_CLOSED_POINTS else WallElement.MIN_POINTS
            if (pts.size < minPoints || thickness.value <= 0.0 || !WallGeometry.hasDistinctVertices(pts, closed)) raise(SheetError.InvalidElement(index, "wall"))
            return WallElement(base, pts, thickness, enum(o, "just", WallJustification.Centre, where), closed)
        }

        private fun Raise<SheetError>.opening(o: JsonObject, base: ElementBase, index: Int, where: String): SceneElement {
            val width = unit(o, "w", where)
            val offset = unit(o, "off", where)
            val segment = int(o, "seg", 0, where)
            if (width.value <= 0.0 || offset.value < 0.0 || segment < 0) raise(SheetError.InvalidElement(index, "opening"))
            return OpeningElement(
                base, ElementId(str(o, "host", where)), segment, offset, width, enum(o, "kind", OpeningKind.Door, where),
                bool(o, "flipSide", false, where), bool(o, "flipHinge", false, where),
                unitOf(dblOr(o, "x", 0.0, where), "$where.x"), unitOf(dblOr(o, "y", 0.0, where), "$where.y"),
                unitOf(dblOr(o, "h", 0.0, where), "$where.h"), dblOr(o, "angle", 0.0, where),
            )
        }

        private fun Raise<SheetError>.arc(o: JsonObject, base: ElementBase, index: Int, where: String): SceneElement {
            val radius = unit(o, "r", where)
            if (radius.value <= 0.0) raise(SheetError.InvalidElement(index, "radius"))
            return ArcElement(base, unit(o, "cx", where), unit(o, "cy", where), radius, dbl(o, "a0", where), dbl(o, "a1", where))
        }

        private fun Raise<SheetError>.freedraw(o: JsonObject, base: ElementBase, index: Int, where: String): SceneElement {
            val pressures = arr(o["pressures"] ?: JsonArray(emptyList()), "$where.pressures")
            if (pressures.size > limits.maxPointsPerElement) raise(SheetError.TooManyPoints(index, pressures.size, limits.maxPointsPerElement))
            val values = pressures.map { CanonicalJson.doubleOf(it as? JsonPrimitive ?: raise(SheetError.Malformed("$where.pressures"))) ?: raise(SheetError.NonFinite("$where.pressures")) }
            return FreedrawElement(base, points(o["pts"], index, where), values, bool(o, "sim", true, where))
        }

        private fun Raise<SheetError>.text(o: JsonObject, base: ElementBase, index: Int, where: String): SceneElement {
            val text = strOr(o, "text", "")
            val original = strOr(o, "orig", text)
            if (text.length > limits.maxTextChars || original.length > limits.maxTextChars) raise(SheetError.TextTooLong(index, limits.maxTextChars))
            return TextElement(
                base, unit(o, "x", where), unit(o, "y", where), unit(o, "w", where), unit(o, "h", where), dblOr(o, "angle", 0.0, where),
                text, original, dblOr(o, "fs", TextElement.DEFAULT_FONT_SIZE, where), int(o, "ff", 1, where),
                enum(o, "ta", TextAlign.Left, where), enum(o, "va", VerticalAlign.Top, where),
                strOrNull(o, "container")?.let { ElementId(it) }, dblOr(o, "lh", TextElement.DEFAULT_LINE_HEIGHT, where), bool(o, "auto", true, where),
            )
        }

        private fun Raise<SheetError>.image(o: JsonObject, base: ElementBase, where: String): SceneElement {
            val crop = o["crop"]?.let { c ->
                val co = obj(c, "$where.crop")
                ImageCrop(dbl(co, "x", where), dbl(co, "y", where), dbl(co, "w", where), dbl(co, "h", where), dbl(co, "nw", where), dbl(co, "nh", where))
            }
            return ImageElement(
                base, unit(o, "x", where), unit(o, "y", where), unit(o, "w", where), unit(o, "h", where), dblOr(o, "angle", 0.0, where),
                strOrNull(o, "fileId"), bool(o, "fx", false, where), bool(o, "fy", false, where), crop,
            )
        }

        private fun Raise<SheetError>.binding(el: JsonElement?, where: String): Binding? {
            if (el == null) return null
            val o = obj(el, "$where.binding")
            return Binding(ElementId(str(o, "id", where)), dbl(o, "fx", where), dbl(o, "fy", where), enum(o, "mode", BindingMode.Orbit, where))
        }

        private fun Raise<SheetError>.bound(o: JsonObject, where: String): List<BoundRef> =
            arr(o["bound"] ?: JsonArray(emptyList()), "$where.bound").map { r ->
                val ro = obj(r, "$where.bound")
                BoundRef(ElementId(str(ro, "id", where)), enum(ro, "kind", BoundKind.Arrow, where))
            }

        private fun Raise<SheetError>.base(o: JsonObject, index: Int, where: String): ElementBase {
            val id = str(o, "id", where)
            if (id.isBlank()) raise(SheetError.InvalidElement(index, "id"))
            val layer = strOr(o, "layer", LayerId.Default.value)
            if (layer.length > limits.maxLayerIdChars) raise(SheetError.InvalidElement(index, "layer"))
            val name = strOrNull(o, "name")
            if (name != null && name.length > limits.maxNameChars) raise(SheetError.NameTooLong(where, limits.maxNameChars))
            val opacity = int(o, "opacity", ElementBase.FULL_OPACITY, where)
            if (opacity !in 0..ElementBase.FULL_OPACITY) raise(SheetError.OutOfRange("$where.opacity"))
            return ElementBase(
                id = ElementId(id),
                layerId = LayerId(layer),
                groupIds = arr(o["groups"] ?: JsonArray(emptyList()), "$where.groups").map { g -> GroupId((g as? JsonPrimitive)?.takeIf { it.isString }?.content ?: raise(SheetError.Malformed("$where.groups"))) },
                frameId = strOrNull(o, "frame")?.let { ElementId(it) },
                zKey = ZKey(strOr(o, "z", ZKey("a0").value)),
                locked = bool(o, "locked", false, where),
                opacity = opacity,
                style = style(o["style"], where),
                seed = int(o, "seed", 0, where),
                version = int(o, "ver", 1, where),
                ordinal = int(o, "ord", 0, where),
                name = name,
                extras = (o["extras"] ?: JsonObject(emptyMap())) as? JsonObject ?: raise(SheetError.Malformed("$where.extras")),
            )
        }

        private fun Raise<SheetError>.style(el: JsonElement?, where: String): ElementStyle {
            val defaults = ElementStyle()
            val o = obj(el ?: JsonObject(emptyMap()), "$where.style")
            val strokeWidth = dblOr(o, "sw", defaults.strokeWidth, where)
            if (strokeWidth < 0.0) raise(SheetError.OutOfRange("$where.style.sw"))
            val round = o["round"]?.let { r ->
                val ro = obj(r, "$where.style.round")
                Roundness(enum(ro, "kind", RoundnessKind.Legacy, where), if (ro["value"] == null) null else dbl(ro, "value", where))
            }
            return ElementStyle(
                strokeColor = ColorText.clean(strOr(o, "stroke", defaults.strokeColor), defaults.strokeColor),
                backgroundColor = ColorText.clean(strOr(o, "bg", defaults.backgroundColor), defaults.backgroundColor),
                fillStyle = enum(o, "fill", FillStyle.Solid, where),
                strokeWidth = strokeWidth,
                strokeStyle = enum(o, "ss", StrokeStyle.Solid, where),
                roughness = int(o, "rough", defaults.roughness, where),
                roundness = round,
            )
        }
    }
}
