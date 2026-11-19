// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetPayload
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

    fun sheetPath(sheet: SheetUuid) = SheetPaths.pathFor(sheet, SheetPaths.SCHEMA_V1)

    fun encode(bundle: ProjectBundle): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        val sheets = bundle.sheets.sortedWith(compareBy({ it.position }, { it.uuid.value }))
        val byteSheets = sheets.associateWith { s -> sheetBytes(bundle, s) }
        byteSheets.forEach { (s, bytes) -> files[SheetPaths.pathFor(s.uuid, s.schemaVersion)] = bytes }
        files[MODEL_PATH] = render(modelJson(bundle, byteSheets))
        return files
    }

    /**
     * The sheet file of [s]: a v1 sheet is encoded from its primitives, a v2 sheet's opaque bytes are written
     * byte-for-byte. A v2 sheet without a [SheetPayload.V2Opaque] is a caller bug (the database holds no v2 content).
     */
    private fun sheetBytes(bundle: ProjectBundle, s: SketchSheet): ByteArray = when (s.schemaVersion) {
        SheetPaths.SCHEMA_V1 -> {
            val prims = (bundle.sheetPayloads[s.uuid] as? SheetPayload.V1)?.primitives
                ?: bundle.primitives.filter { it.sheetUuid == s.uuid }
            SheetCodecV1.encode(s.uuid, prims.sortedWith(compareBy({ it.z }, { it.uuid })))
        }
        SheetPaths.SCHEMA_V2 -> requireNotNull(bundle.sheetPayloads[s.uuid] as? SheetPayload.V2Opaque) {
            "v2 sheet ${s.uuid.value} has no opaque payload"
        }.bytes
        else -> throw IllegalArgumentException("sheet ${s.uuid.value} has unsupported schema ${s.schemaVersion}")
    }

    /**
     * The v2 sheet paths a model references (`schema` 2 refs) with the sha it names for each, or null when
     * [modelBytes] is not a model. Lets a reader check v2 bytes against the model without decoding the bundle.
     */
    fun v2SheetShas(modelBytes: ByteArray): Map<String, String>? = try {
        Json.parseToJsonElement(modelBytes.decodeToString()).jsonObject["sheets"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["schema"]?.takeIf { s -> s !is JsonNull }?.jsonPrimitive?.long == SheetPaths.SCHEMA_V2 }
            .associate { o -> SheetPaths.pathFor(SheetUuid(o.str("uuid")), SheetPaths.SCHEMA_V2) to o.str("fileSha256") }
    } catch (_: Exception) {
        null
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
        val listing = files.entries.sortedBy { it.key }.joinToString("\n") { (path, bytes) ->
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
        if (s.schemaVersion != SheetPaths.SCHEMA_V1) {
            put("schema", s.schemaVersion)
            put("file", SheetPaths.pathFor(s.uuid, s.schemaVersion))
        }
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
        val payloads = linkedMapOf<SheetUuid, SheetPayload>()
        for (ref in model["sheets"]?.jsonArray.orEmpty()) {
            val o = ref.jsonObject
            val uuid = SheetUuid(o.str("uuid"))
            val schema = o["schema"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long ?: SheetPaths.SCHEMA_V1
            if (schema > SheetPaths.SCHEMA_V2) return CorruptReason.NewerSchema.left()
            if (!SheetPaths.isSupportedSchema(schema)) return CorruptReason.InvalidJson.left()
            val path = SheetPaths.pathFor(uuid, schema)
            // No arbitrary path from git-synced input: a `file` that is not the rule's path is corrupt.
            val named = o["file"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            if (named != null && named != path) return CorruptReason.InvalidJson.left()
            val bytes = files[path] ?: return CorruptReason.Truncated.left()
            if (ContentHasher.sha256(bytes) != o.str("fileSha256")) return CorruptReason.Truncated.left()
            sheets += SketchSheet(
                uuid = uuid, projectUuid = projectUuid, kind = o.str("kind"), viewDirection = o.str("viewDirection"),
                unit = o.str("unit"), scaleSixteenthsPerUnit = o.num("scaleSixteenthsPerUnit"),
                sourceAssetUuid = o["sourceAssetUuid"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull,
                layers = o["layers"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
                zones = o["zones"]?.jsonArray ?: JsonArray(emptyList()), position = o.num("position"),
                schemaVersion = schema,
            )
            if (schema == SheetPaths.SCHEMA_V2) {
                // v2 is carried, never interpreted here; the file must really be schema 2.
                val fileSchema = SidecarClassifier.schemaOf(bytes)
                when {
                    fileSchema == SheetPaths.SCHEMA_V2 -> payloads[uuid] = SheetPayload.V2Opaque(bytes, ContentHasher.sha256(bytes))
                    fileSchema != null && fileSchema > SheetPaths.SCHEMA_V2 -> return CorruptReason.NewerSchema.left()
                    else -> return CorruptReason.InvalidJson.left()
                }
            } else {
                // A v1-referenced file of any other schema is never read as an empty v1 sheet.
                val codec = SheetCodecs.forBytes(bytes) ?: return CorruptReason.NewerSchema.left()
                primitives += codec.decode(uuid, bytes)
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
        return ProjectBundle(project, sheets, primitives, dimensions, payloads).right()
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.num(key: String): Long = getValue(key).jsonPrimitive.long
}
