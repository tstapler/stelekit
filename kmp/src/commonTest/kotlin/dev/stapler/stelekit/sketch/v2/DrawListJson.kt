// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.PathSegment
import dev.stapler.stelekit.sketch.v2.render.StrokePaint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.round

/**
 * Canonical JSON of a draw list for goldens. Numbers are rounded to 6 decimals first, so a one-ulp difference in a
 * platform `sin`/`cos` cannot change the bytes, then printed by the repo's own shortest-digit printer (never the
 * platform `toString`, which differs on wasmJs).
 */
object DrawListJson {
    private const val SCALE = 1_000_000.0

    fun encode(ops: List<DrawOp>): String =
        CanonicalJson.encode(JsonArray(ops.map { op(it) })).getOrNull()!!.decodeToString()

    private fun num(d: Double): JsonElement = CanonicalJson.number(round(d * SCALE) / SCALE).getOrNull()!!

    private fun str(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)

    private fun stroke(s: StrokePaint?): JsonElement =
        if (s == null) JsonNull else JsonObject(mapOf("color" to str(s.color), "dash" to JsonArray(s.dash.map { num(it) }), "width" to num(s.width)))

    private fun seg(s: PathSegment): JsonElement = when (s) {
        is PathSegment.MoveTo -> JsonArray(listOf(JsonPrimitive("M"), num(s.x), num(s.y)))
        is PathSegment.LineTo -> JsonArray(listOf(JsonPrimitive("L"), num(s.x), num(s.y)))
        is PathSegment.CubicTo -> JsonArray(listOf(JsonPrimitive("C"), num(s.c1x), num(s.c1y), num(s.c2x), num(s.c2y), num(s.x), num(s.y)))
        PathSegment.Close -> JsonArray(listOf(JsonPrimitive("Z")))
    }

    private fun op(o: DrawOp): JsonElement = when (o) {
        is DrawOp.PathOp -> JsonObject(
            mapOf(
                "op" to JsonPrimitive("path"), "id" to JsonPrimitive(o.elementId.value), "fill" to str(o.fill),
                "opacity" to num(o.opacity), "stroke" to stroke(o.stroke), "segments" to JsonArray(o.segments.map { seg(it) }),
            ),
        )
        is DrawOp.TextRun -> JsonObject(
            mapOf(
                "op" to JsonPrimitive("text"), "id" to JsonPrimitive(o.elementId.value), "align" to JsonPrimitive(o.align.name),
                "angle" to num(o.angle), "color" to JsonPrimitive(o.color), "fallback" to JsonPrimitive(o.usesFallbackFont),
                "family" to JsonPrimitive(o.fontFamily), "size" to num(o.fontSize), "opacity" to num(o.opacity),
                "lines" to JsonArray(o.lines.map { JsonArray(listOf(JsonPrimitive(it.text), num(it.x), num(it.y))) }),
            ),
        )
        is DrawOp.ImageOp -> JsonObject(
            mapOf(
                "op" to JsonPrimitive("image"), "id" to JsonPrimitive(o.elementId.value), "file" to str(o.fileId),
                "rect" to JsonArray(listOf(num(o.rect.x), num(o.rect.y), num(o.rect.width), num(o.rect.height))),
                "angle" to num(o.angle), "flipX" to JsonPrimitive(o.flipX), "flipY" to JsonPrimitive(o.flipY), "opacity" to num(o.opacity),
            ),
        )
        is DrawOp.ClipOp -> JsonObject(
            mapOf(
                "op" to JsonPrimitive("clip"),
                "rect" to JsonArray(listOf(num(o.rect.x), num(o.rect.y), num(o.rect.width), num(o.rect.height))),
                "ops" to JsonArray(o.ops.map { op(it) }),
            ),
        )
    }
}
