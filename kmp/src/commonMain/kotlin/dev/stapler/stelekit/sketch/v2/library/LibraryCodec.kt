// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.library

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawElement
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawParser
import dev.stapler.stelekit.sketch.excalidraw.ImportError
import dev.stapler.stelekit.sketch.excalidraw.ImportReport
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.excalidraw.SpanDecoder
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What a library read produced: the items that mapped, and the importer's report (skipped, truncated, image placeholders). */
data class DecodedLibrary(val items: List<LibraryItem>, val report: ImportReport)

/**
 * `.excalidrawlib` exchange (Task 3.5.6b). Reading goes through `ExcalidrawParser.parseLibrary`, which uses the scanner's
 * library mode so each item is one span of at most `maxImportSpanBytes` and an over-cap item is skipped alone.
 * Writing produces `{type, version, source, libraryItems}` with items `{id, status, elements, created}`.
 *
 * The element mapping covers the kinds an Excalidraw library holds (rectangle, diamond, ellipse, line, arrow, freedraw,
 * text) at 1 Excalidraw pixel to 1 scene unit; the full importer mapping (Story 5.1.2) replaces it for scenes. Kinds
 * Excalidraw has no equivalent for (frame, image, point, arc) are not exported.
 */
object LibraryCodec {
    const val TYPE = "excalidrawlib"
    const val VERSION = 2
    const val SOURCE = "stelekit"
    private const val POINT_PAIR = 2
    private const val STATUS = "unpublished"
    private const val DEFAULT_ROUGHNESS = 1
    private const val OPACITY_FULL = 100

    suspend fun decode(source: ImportSource, limits: SceneLimits = SceneLimits.Default, decoder: SpanDecoder = SpanDecoder.Default): Either<ImportError, DecodedLibrary> =
        ExcalidrawParser(limits, decoder).parseLibrary(source).map { imported ->
            val items = imported.items.mapIndexed { i, item ->
                val elements = item.elements.mapNotNull { map(it, limits) }
                LibraryItem(item.id, item.name, created = i.toLong(), elements = elements, bytes = 0L)
            }.filter { it.elements.isNotEmpty() }
            DecodedLibrary(items, imported.report)
        }

    fun encode(items: List<LibraryItem>): String = buildJsonObject {
        put("type", TYPE)
        put("version", VERSION)
        put("source", SOURCE)
        put(
            "libraryItems",
            buildJsonArray {
                for (item in items) {
                    add(
                        buildJsonObject {
                            put("id", item.id)
                            put("status", STATUS)
                            put("created", item.created)
                            item.name?.let { put("name", it) }
                            put("elements", JsonArray(item.elements.mapNotNull { element(it) }))
                        },
                    )
                }
            },
        )
    }.toString()

    private fun common(e: SceneElement, type: String, x: Double, y: Double, w: Double, h: Double, angle: Double) = buildJsonObject {
        put("type", type)
        put("id", e.id.value)
        put("x", x)
        put("y", y)
        put("width", w)
        put("height", h)
        put("angle", angle)
        put("strokeColor", e.base.style.strokeColor)
        put("backgroundColor", e.base.style.backgroundColor)
        put("strokeWidth", e.base.style.strokeWidth)
        put("roughness", e.base.style.roughness)
        put("opacity", e.base.opacity)
        put("groupIds", JsonArray(e.base.groupIds.map { JsonPrimitive(it.value) }))
        put("locked", e.base.locked)
    }

    private fun relative(points: List<ScenePoint>): JsonArray {
        val ox = points.firstOrNull()?.x?.value ?: 0.0
        val oy = points.firstOrNull()?.y?.value ?: 0.0
        return JsonArray(points.map { JsonArray(listOf(JsonPrimitive(it.x.value - ox), JsonPrimitive(it.y.value - oy))) })
    }

    private fun lineLike(e: SceneElement, type: String, points: List<ScenePoint>): JsonObject {
        val minX = points.minOfOrNull { it.x.value } ?: 0.0
        val maxX = points.maxOfOrNull { it.x.value } ?: 0.0
        val minY = points.minOfOrNull { it.y.value } ?: 0.0
        val maxY = points.maxOfOrNull { it.y.value } ?: 0.0
        val first = points.firstOrNull()
        val base = common(e, type, first?.x?.value ?: 0.0, first?.y?.value ?: 0.0, maxX - minX, maxY - minY, 0.0)
        return JsonObject(base + ("points" to relative(points)))
    }

    private fun element(e: SceneElement): JsonObject? = when (e) {
        is RectElement -> common(e, "rectangle", e.x.value, e.y.value, e.width.value, e.height.value, e.angle)
        is DiamondElement -> common(e, "diamond", e.x.value, e.y.value, e.width.value, e.height.value, e.angle)
        is EllipseElement -> common(e, "ellipse", e.cx.value - e.rx.value, e.cy.value - e.ry.value, e.rx.value * 2, e.ry.value * 2, e.angle)
        is LineElement -> lineLike(e, "line", e.points)
        is ArrowElement -> lineLike(e, "arrow", e.points)
        is FreedrawElement -> lineLike(e, "freedraw", e.points)
        is TextElement -> JsonObject(
            common(e, "text", e.x.value, e.y.value, e.width.value, e.height.value, e.angle) +
                mapOf("text" to JsonPrimitive(e.text), "fontSize" to JsonPrimitive(e.fontSize), "fontFamily" to JsonPrimitive(e.fontFamily)),
        )
        else -> null
    }

    private fun JsonObject.num(key: String): Double = (this[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0

    private fun JsonObject.str(key: String, default: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default

    private fun map(x: ExcalidrawElement, limits: SceneLimits): SceneElement? {
        val raw = x.raw
        fun u(v: Double): SceneUnit = SceneUnit.of(v, limits).getOrNull() ?: SceneUnit.ZERO
        val style = ElementStyle(
            strokeColor = raw.str("strokeColor", "#1e1e1e"),
            backgroundColor = raw.str("backgroundColor", "transparent"),
            strokeWidth = raw.num("strokeWidth").takeIf { it >= 0.0 && it.isFinite() } ?: 2.0,
            roughness = (raw["roughness"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: DEFAULT_ROUGHNESS,
        )
        val opacity = ((raw["opacity"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: OPACITY_FULL).coerceIn(0, ElementBase.FULL_OPACITY)
        val base = ElementBase(id = ElementId(x.id), style = style, opacity = opacity, locked = x.locked)
        val points = (raw["points"] as? JsonArray)?.mapNotNull { p ->
            val pair = p as? JsonArray ?: return@mapNotNull null
            if (pair.size < POINT_PAIR) return@mapNotNull null
            ScenePoint(u(x.x + (pair[0] as JsonElement).jsonPrimitive.doubleOrNull.orZero()), u(x.y + (pair[1] as JsonElement).jsonPrimitive.doubleOrNull.orZero()))
        }.orEmpty()
        return when (x.type) {
            "rectangle" -> RectElement(base, u(x.x), u(x.y), u(x.width), u(x.height), x.angle)
            "diamond" -> DiamondElement(base, u(x.x), u(x.y), u(x.width), u(x.height), x.angle)
            "ellipse" -> {
                val rx = x.width / 2
                val ry = x.height / 2
                if (rx > 0.0 && ry > 0.0) EllipseElement(base, u(x.x + rx), u(x.y + ry), u(rx), u(ry), x.angle) else null
            }
            "line" -> LineElement(base, points)
            "arrow" -> ArrowElement(base, points)
            "freedraw" -> FreedrawElement(base, points)
            "text" -> TextElement(base, u(x.x), u(x.y), u(x.width), u(x.height), x.angle, text = raw.str("text", ""))
            else -> null
        }
    }

    private fun Double?.orZero(): Double = this ?: 0.0
}
