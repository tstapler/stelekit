// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** The codec of one sheet file schema. A sheet file's schema is read from its bytes, never assumed. */
interface SheetCodec {
    val schema: Long
    fun encode(sheet: SheetUuid, primitives: List<SketchPrimitive>): ByteArray
    fun decode(sheet: SheetUuid, bytes: ByteArray): List<SketchPrimitive>
}

/** The schema-1 sheet file: pretty-printed, one primitive field per line. Byte-for-byte what `SidecarCodec` wrote before the split. */
object SheetCodecV1 : SheetCodec {
    override val schema = 1L
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    override fun encode(sheet: SheetUuid, primitives: List<SketchPrimitive>): ByteArray {
        val obj = buildJsonObject {
            put("schema", schema.toInt())
            put("sheetUuid", sheet.value)
            put("primitives", JsonArray(primitives.map { p ->
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
        return (pretty.encodeToString(JsonElement.serializer(), obj) + "\n").encodeToByteArray()
    }

    override fun decode(sheet: SheetUuid, bytes: ByteArray): List<SketchPrimitive> {
        val sheetObj = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        return sheetObj["primitives"]?.jsonArray.orEmpty().map { p ->
            val po = p.jsonObject
            SketchPrimitive(
                uuid = po.str("uuid"), sheetUuid = sheet, kind = po.str("kind"), x1 = po.num("x1"), y1 = po.num("y1"),
                x2 = po.num("x2"), y2 = po.num("y2"), name = po.str("name"), layer = po.str("layer"), z = po.num("z"),
            )
        }
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.num(key: String): Long = getValue(key).jsonPrimitive.long
}

/** Picks the [SheetCodec] for a sheet file's schema. A schema with no codec here (2 until Story 1.1.3/1.1.5) yields null. */
object SheetCodecs {
    fun forSchema(schema: Long?): SheetCodec? = when (schema) {
        SheetCodecV1.schema -> SheetCodecV1
        else -> null
    }

    fun forBytes(bytes: ByteArray): SheetCodec? = forSchema(SidecarClassifier.schemaOf(bytes))
}
