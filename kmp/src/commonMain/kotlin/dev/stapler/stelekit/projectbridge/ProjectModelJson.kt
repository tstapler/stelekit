// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchDimension
import dev.stapler.stelekit.model.SketchPrimitive
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The sidecar files of one project (`project-model.json` plus `sheets/<sheetuuid>.json`), as relative path
 * to bytes. Encoding is canonical (sorted, pretty-printed, no timestamps) so equal data gives equal bytes and
 * git can merge per-sheet files.
 */
object SidecarCodec {
    const val MODEL_PATH = "project-model.json"
    const val SHEETS_DIR = "sheets"
    private const val SCHEMA = 1
    private const val MODEL_REVISION = "modelRevision"
    private const val SOURCES = "sources"

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun sheetPath(sheet: SheetUuid) = "$SHEETS_DIR/${sheet.value}.json"

    fun encode(bundle: ProjectBundle): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        val sheets = bundle.sheets.sortedWith(compareBy({ it.position }, { it.uuid.value }))
        val byteSheets = sheets.associate { s ->
            val prims = bundle.primitives.filter { it.sheetUuid == s.uuid }.sortedWith(compareBy({ it.z }, { it.uuid }))
            s to render(sheetJson(s.uuid, prims))
        }
        byteSheets.forEach { (s, bytes) -> files[sheetPath(s.uuid)] = bytes }
        files[MODEL_PATH] = render(modelJson(bundle, byteSheets))
        return files
    }

    /**
     * Rebuilds the project from classified files. [Either.Left] carries why the bundle is unusable; a missing or
     * mismatching sheet file is [CorruptReason.Truncated] (a torn or half-pulled bundle).
     */
    fun decode(files: Map<String, ByteArray>): Either<CorruptReason, ProjectBundle> = try {
        decodeUnsafe(files)
    } catch (_: Exception) {
        CorruptReason.InvalidJson.left()
    }

    /** SHA-256 over the sorted `(path, sha256(bytes))` list. `modelRevision` is informational and excluded. */
    fun bundleHash(files: Map<String, ByteArray>): String {
        val listing = files.toSortedMap().entries.joinToString("\n") { (path, bytes) ->
            "$path\u0000${ContentHasher.sha256(if (path == MODEL_PATH) withoutRevision(bytes) else bytes)}"
        }
        return ContentHasher.sha256(listing)
    }

    private fun withoutRevision(bytes: ByteArray): ByteArray = try {
        val obj = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        render(JsonObject(obj - MODEL_REVISION))
    } catch (_: Exception) {
        bytes
    }

    private fun render(obj: JsonObject): ByteArray = (pretty.encodeToString(JsonElement.serializer(), obj) + "\n").encodeToByteArray()

    private fun modelJson(bundle: ProjectBundle, sheetBytes: Map<SketchSheet, ByteArray>): JsonObject {
        val p = bundle.project
        return buildJsonObject {
            put("schema", SCHEMA)
            put("uuid", p.uuid.value)
            put("slug", p.slug)
            put("name", p.name)
            put("createdAt", p.createdAt)
            put(MODEL_REVISION, p.modelRevision)
            put("settings", JsonObject(p.settings - SOURCES))
            put(SOURCES, p.settings[SOURCES] ?: JsonArray(emptyList()))
            put("dimensions", JsonArray(bundle.dimensions.sortedWith(compareBy({ it.key }, { it.uuid })).map(::dimensionJson)))
            put("sheets", JsonArray(sheetBytes.map { (s, bytes) -> sheetRef(s, ContentHasher.sha256(bytes)) }))
        }
    }

    private fun sheetRef(s: SketchSheet, fileSha256: String) = buildJsonObject {
        put("uuid", s.uuid.value)
        put("kind", s.kind)
        put("viewDirection", s.viewDirection)
        put("unit", s.unit)
        put("scaleSixteenthsPerUnit", s.scaleSixteenthsPerUnit)
        put("layers", JsonArray(s.layers.map(::JsonPrimitive)))
        put("zones", s.zones)
        put("position", s.position)
        put("sourceAssetUuid", s.sourceAssetUuid?.let(::JsonPrimitive) ?: JsonNull)
        put("fileSha256", fileSha256)
    }

    private fun dimensionJson(d: SketchDimension) = buildJsonObject {
        put("uuid", d.uuid)
        put("key", d.key)
        put("kind", d.kind)
        put("display", display(d.value))
        put("num", d.value.num)
        put("den", d.value.den)
        put("precisionNum", d.precision?.num?.let(::JsonPrimitive) ?: JsonNull)
        put("precisionDen", d.precision?.den?.let(::JsonPrimitive) ?: JsonNull)
        put("toleranceNum", d.tolerance.num)
        put("toleranceDen", d.tolerance.den)
        put("sourceKind", d.sourceKind)
        put("sourceRef", d.sourceRef)
        put("locked", d.locked)
    }

    /** Exact, lossless text of the stored value (`379/10`); the numerator and denominator are authoritative. */
    private fun display(r: Rational) = if (r.den == 1L) "${r.num}" else "${r.num}/${r.den}"

    private fun sheetJson(sheet: SheetUuid, prims: List<SketchPrimitive>) = buildJsonObject {
        put("schema", SCHEMA)
        put("sheetUuid", sheet.value)
        put("primitives", JsonArray(prims.map { p ->
            buildJsonObject {
                put("uuid", p.uuid)
                put("kind", p.kind)
                put("x1", p.x1)
                put("y1", p.y1)
                put("x2", p.x2)
                put("y2", p.y2)
                put("name", p.name)
                put("layer", p.layer)
                put("z", p.z)
            }
        }))
    }

    private fun decodeUnsafe(files: Map<String, ByteArray>): Either<CorruptReason, ProjectBundle> {
        val modelBytes = files[MODEL_PATH] ?: return CorruptReason.Truncated.left()
        val model = Json.parseToJsonElement(modelBytes.decodeToString()).jsonObject
        val projectUuid = ProjectUuid(model.str("uuid"))
        val settings = model["settings"]?.jsonObject ?: JsonObject(emptyMap())
        val sources = model[SOURCES]?.jsonArray ?: JsonArray(emptyList())
        val project = SketchProject(
            uuid = projectUuid,
            slug = model.str("slug"),
            name = model.str("name"),
            createdAt = model.num("createdAt"),
            settings = if (sources.isEmpty()) settings else JsonObject(settings + (SOURCES to sources)),
            modelRevision = model.num(MODEL_REVISION),
        )
        val sheets = mutableListOf<SketchSheet>()
        val primitives = mutableListOf<SketchPrimitive>()
        for (ref in model["sheets"]?.jsonArray.orEmpty()) {
            val o = ref.jsonObject
            val uuid = SheetUuid(o.str("uuid"))
            val bytes = files[sheetPath(uuid)] ?: return CorruptReason.Truncated.left()
            if (ContentHasher.sha256(bytes) != o.str("fileSha256")) return CorruptReason.Truncated.left()
            sheets += SketchSheet(
                uuid = uuid, projectUuid = projectUuid, kind = o.str("kind"), viewDirection = o.str("viewDirection"),
                unit = o.str("unit"), scaleSixteenthsPerUnit = o.num("scaleSixteenthsPerUnit"),
                sourceAssetUuid = o["sourceAssetUuid"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull,
                layers = o["layers"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
                zones = o["zones"]?.jsonArray ?: JsonArray(emptyList()), position = o.num("position"),
            )
            val sheetObj = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            for (p in sheetObj["primitives"]?.jsonArray.orEmpty()) {
                val po = p.jsonObject
                primitives += SketchPrimitive(
                    uuid = po.str("uuid"), sheetUuid = uuid, kind = po.str("kind"), x1 = po.num("x1"), y1 = po.num("y1"),
                    x2 = po.num("x2"), y2 = po.num("y2"), name = po.str("name"), layer = po.str("layer"), z = po.num("z"),
                )
            }
        }
        val dimensions = model["dimensions"]?.jsonArray.orEmpty().map { d ->
            val o = d.jsonObject
            val pn = o["precisionNum"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long
            val pd = o["precisionDen"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long
            SketchDimension(
                uuid = o.str("uuid"), projectUuid = projectUuid, key = o.str("key"), kind = o.str("kind"),
                value = Rational(o.num("num"), o.num("den")),
                precision = if (pn != null && pd != null) Rational(pn, pd) else null,
                sourceKind = o.str("sourceKind"), sourceRef = o.str("sourceRef"),
                tolerance = Rational(o.num("toleranceNum"), o.num("toleranceDen")), locked = o["locked"]!!.jsonPrimitive.boolean,
            )
        }
        return ProjectBundle(project, sheets, primitives, dimensions).right()
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.num(key: String): Long = getValue(key).jsonPrimitive.long
}
