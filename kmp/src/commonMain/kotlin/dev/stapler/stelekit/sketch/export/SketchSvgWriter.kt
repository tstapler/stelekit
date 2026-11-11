// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.export

import dev.stapler.stelekit.sketch.SketchDocument
import dev.stapler.stelekit.sketch.SketchPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.min

/**
 * Writes a sheet as SVG. One user unit is one sixteenth of an inch, so `viewBox` and every coordinate
 * are exact integers. The `<metadata>` element comes first and starts with [MARKER], so a sniffer
 * that reads only the head of the file finds it. Each dimension is a `<g data-dim-id data-value
 * data-source>` holding its label text. The metadata is an export convenience, not a sync path.
 * The output carries no path, filename or project title.
 */
object SketchSvgWriter {
    const val MARKER = "stelekit-sketch-export"
    const val VERSION = 1

    /** Sixteenths: label text is 6 inches tall and rows are 9 inches apart. */
    private const val FONT_SIZE = 96L
    private const val ROW_STEP = 144L
    private const val MARGIN = 96L
    private const val EMPTY_SIDE = 1536L
    private const val PX_PER_SIXTEENTH = 6L
    private const val STROKE_WIDTH = 4L

    fun write(doc: SketchDocument): String {
        val dims = doc.exportDimensions()
        val box = viewBox(doc, dims.size)
        return buildString {
            append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="${box.attr()}" width="${box.w * PX_PER_SIXTEENTH}" height="${box.h * PX_PER_SIXTEENTH}">""")
            append('\n')
            append("<metadata>").append(escapeText(metadataJson(doc, dims))).append("</metadata>\n")
            doc.visibleScene.primitives.forEach { append(shape(it)).append('\n') }
            dims.forEachIndexed { i, d -> append(dimensionGroup(d, box.x + MARGIN, box.y + box.h - MARGIN - (dims.size - 1 - i) * ROW_STEP)).append('\n') }
            append("</svg>\n")
        }
    }

    private class Box(val x: Long, val y: Long, val w: Long, val h: Long) {
        fun attr() = "$x $y $w $h"
    }

    /** Bounds of the visible drawing plus a margin, grown downward to hold one text row per dimension. */
    private fun viewBox(doc: SketchDocument, dimensionCount: Int): Box {
        val boxes = doc.visibleScene.primitives.map { it.aabb }
        val minX = boxes.minOfOrNull { it.minX } ?: 0L
        val minY = boxes.minOfOrNull { it.minY } ?: 0L
        val maxX = boxes.maxOfOrNull { it.maxX } ?: EMPTY_SIDE
        val maxY = boxes.maxOfOrNull { it.maxY } ?: EMPTY_SIDE
        val rows = dimensionCount * ROW_STEP
        return Box(minX - MARGIN, minY - MARGIN, max(maxX - minX, 1L) + 2 * MARGIN, max(maxY - minY, 1L) + 2 * MARGIN + rows)
    }

    private fun shape(p: SketchPrimitive): String {
        val common = """ data-id="${escapeAttr(p.id)}" data-layer="${escapeAttr(p.layer)}" fill="none" stroke="#000" stroke-width="$STROKE_WIDTH""""
        return when (p) {
            is SketchPrimitive.Line -> """<line x1="${p.a.x.sixteenths}" y1="${p.a.y.sixteenths}" x2="${p.b.x.sixteenths}" y2="${p.b.y.sixteenths}"$common/>"""
            is SketchPrimitive.Rect -> {
                val x = min(p.a.x.sixteenths, p.b.x.sixteenths)
                val y = min(p.a.y.sixteenths, p.b.y.sixteenths)
                val w = max(p.a.x.sixteenths, p.b.x.sixteenths) - x
                val h = max(p.a.y.sixteenths, p.b.y.sixteenths) - y
                """<rect x="$x" y="$y" width="$w" height="$h"$common/>"""
            }
        }
    }

    private fun dimensionGroup(d: ExportDimension, x: Long, y: Long): String {
        val source = if (d.status == "under-constrained" || d.status == "conflict") "none" else if (d.status == "derived") "derived" else "typed"
        return """<g data-dim-id="${escapeAttr(d.id)}" data-value="${escapeAttr(d.value)}" data-source="$source">""" +
            """<text x="$x" y="$y" font-size="$FONT_SIZE" text-anchor="start">${escapeText(d.labelText)}</text></g>"""
    }

    private fun metadataJson(doc: SketchDocument, dims: List<ExportDimension>): String {
        val sheet = JsonObject(
            mapOf(
                "kind" to JsonPrimitive(doc.sheet.kind.wire),
                "viewDirection" to JsonPrimitive(doc.sheet.viewDirection.wire),
                "scaleSixteenths" to JsonPrimitive(doc.sheet.scaleSixteenths),
            ),
        )
        val dimensions = JsonArray(
            dims.map {
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(it.id),
                        "value" to JsonPrimitive(it.value),
                        "sixteenths" to (it.sixteenths?.let { v -> JsonPrimitive(v) } ?: JsonNull),
                        "status" to JsonPrimitive(it.status),
                    ),
                )
            },
        )
        return JsonObject(
            mapOf(
                "marker" to JsonPrimitive(MARKER),
                "version" to JsonPrimitive(VERSION),
                "sheet" to sheet,
                "dimensions" to dimensions,
            ),
        ).toString()
    }

    internal fun escapeText(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    internal fun escapeAttr(s: String): String = escapeText(s).replace("\"", "&quot;").replace("'", "&apos;")
}
