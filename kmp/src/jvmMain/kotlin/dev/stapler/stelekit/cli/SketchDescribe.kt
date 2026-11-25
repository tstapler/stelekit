// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:Suppress("MagicNumber", "ReturnCount", "TooManyFunctions", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")

package dev.stapler.stelekit.cli

import dev.stapler.stelekit.sketch.v2.annotation.RoomArea
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.CalibrationNote
import dev.stapler.stelekit.sketch.v2.scene.UnderlayCalibration
import dev.stapler.stelekit.sketch.v2.scene.UnderlayIds
import dev.stapler.stelekit.sketch.v2.scene.underlay
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningKind
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.provenance
import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.units.LengthFormat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A text description of a v2 sheet for a reader with no pixels: what each element is, how big, where, what it is labelled,
 * which shapes contain, touch or connect to which, and a coarse character map. Deterministic: the same document gives the
 * same text, so it can be diffed and used as a golden.
 */
internal object SketchDescribe {
    private const val MAP_COLUMNS = 64
    private const val MAP_MIN_ROWS = 6
    private const val MAP_MAX_ROWS = 32
    private const val CHAR_ASPECT = 2.0
    private const val MAX_POINTS_SHOWN = 12
    private const val MISMATCH_INCHES = 0.5

    fun describe(doc: SketchDocumentV2, withMap: Boolean = true): String {
        val scene = doc.scene
        val all = scene.elementsInOrder
        val agent = doc.extras["agent"] as? JsonObject
        val diagram = (agent?.get("mode") as? JsonPrimitive)?.contentOrNull == "diagram"
        val f = Fmt(diagram)
        val labels = all.filterIsInstance<TextElement>().filter { it.containerId != null }.groupBy { it.containerId!! }
        val special = setOf(SketchLayers.OPENINGS, SketchLayers.DIMENSIONS, UnderlayIds.LAYER.value)
        val main = all.filter { it.base.layerId.value !in special && !(it is TextElement && it.containerId != null) }
        val out = StringBuilder()
        out.appendLine("SKETCH SHEET ${doc.sheetUuid} (schema 2)" + ((agent?.get("title") as? JsonPrimitive)?.contentOrNull?.let { ", title \"$it\"" } ?: ""))
        out.appendLine(
            if (diagram) "Units: scene units (abstract pixels). x grows right, y grows down."
            else "Units: 1 scene unit = 1/16 inch (192 units per foot). x grows east (right), y grows south (down).",
        )
        val extent = (main.ifEmpty { all }).map { ElementBounds.of(it) }.reduceOrNull(::union)
        if (extent == null) {
            out.appendLine("The sheet is empty.")
            return out.toString()
        }
        out.appendLine("Extent (shapes, lines and text; not openings or dimension marks): ${f.len(extent.maxX - extent.minX)} wide x ${f.len(extent.maxY - extent.minY)} tall, from ${f.pt(extent.minX, extent.minY)} to ${f.pt(extent.maxX, extent.maxY)}.")
        out.appendLine("Layers: " + scene.layers.sortedBy { it.order }.joinToString("; ") { l ->
            "${l.id.value} \"${l.name}\" (${if (l.visible) "visible" else "hidden"}${if (l.locked) ", locked" else ""}, ${all.count { it.base.layerId == l.id }} elements)"
        })
        out.appendLine()
        out.appendLine("ELEMENTS (${main.size}, in paint order)")
        for (e in main) out.appendLine("- " + elementLine(e, labels[e.id].orEmpty(), f, all) + provenanceNote(e))
        describeUnderlay(doc, f, out)
        describeOpenings(all, f, out)
        describeDimensions(all, f, out)
        describeRelations(main, f, out)
        describeConstraints(doc, out)
        if (withMap) describeMap(main, labels, f, out)
        return out.toString().trimEnd() + "\n"
    }

    /** ` [agent, needs review (70%): note]` for an element an agent wrote; nothing for one a person drew, so old sheets read as before. */
    private fun provenanceNote(e: SceneElement): String {
        val p = e.base.provenance ?: return ""
        val state = if (p.accepted) "accepted" else "needs review"
        val confidence = p.confidence?.let { " ($it%)" }.orEmpty()
        val note = p.note?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        return " [${p.source}, $state$confidence$note]"
    }

    /** The photo underlay and its calibration, in words an agent can act on; nothing when the sheet has none. */
    private fun describeUnderlay(doc: SketchDocumentV2, f: Fmt, out: StringBuilder) {
        val state = doc.scene.underlay ?: return
        out.appendLine()
        out.appendLine("UNDERLAY (a photo to trace over; left out of exports and renders unless asked)")
        val p = state.placement
        val note = CalibrationNote.of(state)
        val noteText = when (note) {
            CalibrationNote.None -> "not calibrated (its size on the sheet is arbitrary)"
            is CalibrationNote.Calibrated -> "calibrated: ${f.len(note.lengthUnits.toDouble())} ref" + if (note.perspective) ", but the references disagree by ${(UnderlayCalibration.residual(state.meta.refs) * PERCENT).roundToInt()}% (perspective: the scale is an average)" else ""
            is CalibrationNote.OutOfDate -> "calibrated to ${f.len(note.lengthUnits.toDouble())}, then resized: recalibrate"
        }
        out.appendLine("- photo ${state.meta.widthPx} x ${state.meta.heightPx} px, sha256 ${state.element.fileId}, drawn ${f.len(p.width)} x ${f.len(p.height)} at ${f.pt(p.x, p.y)}, ${if (state.locked) "locked" else "unlocked"}, ${(state.layer.opacity * PERCENT).roundToInt()}% opacity${if (state.layer.visible) "" else ", hidden"}")
        out.appendLine("- $noteText")
        for (ref in state.meta.refs) out.appendLine("- reference: photo pixels (${ref.a.u.roundToInt()}, ${ref.a.v.roundToInt()}) to (${ref.b.u.roundToInt()}, ${ref.b.v.roundToInt()}) is ${f.len(ref.lengthUnits.toDouble())}")
    }

    private const val PERCENT = 100.0

    private class Fmt(val diagram: Boolean) {
        fun len(v: Double): String = if (diagram) SketchLengths.tidy(v).toString().removeSuffix(".0") else SketchLengths.feetInches(v)

        fun pt(x: Double, y: Double) = "(${len(x)}, ${len(y)})"

        fun area(v: Double): String = if (diagram) "${SketchLengths.tidy(v).toString().removeSuffix(".0")} square units" else RoomArea.text(v, LengthFormat.FEET_INCHES)
    }

    private fun union(a: Bounds, b: Bounds) = Bounds(min(a.minX, b.minX), min(a.minY, b.minY), max(a.maxX, b.maxX), max(a.maxY, b.maxY))

    private fun nameOf(e: SceneElement): String {
        val name = ElementNames.nameOf(e)
        return if (name == e.id.value) name else "$name [id ${e.id.value}]"
    }

    private fun styleBits(e: SceneElement, f: Fmt): String {
        val s = e.base.style
        val bits = ArrayList<String>()
        bits += "line ${f.len(s.strokeWidth)}"
        if (s.backgroundColor != "transparent") bits += "fill ${s.backgroundColor}"
        if (s.strokeStyle.name != "Solid") bits += s.strokeStyle.name.lowercase()
        return bits.joinToString(", ")
    }

    private fun elementLine(e: SceneElement, labels: List<TextElement>, f: Fmt, all: List<SceneElement>): String {
        val label = labels.joinToString(" / ") { "\"${it.originalText.replace("\n", " ")}\"" }.let { if (it.isEmpty()) "" else ", label $it" }
        return when (e) {
            is RectElement -> "${nameOf(e)}: rectangle ${f.len(e.width.value)} x ${f.len(e.height.value)}, top-left ${f.pt(e.x.value, e.y.value)}, centre ${f.pt(e.x.value + e.width.value / 2, e.y.value + e.height.value / 2)}$label, ${styleBits(e, f)}"
            is DiamondElement -> "${nameOf(e)}: diamond ${f.len(e.width.value)} x ${f.len(e.height.value)}, top-left ${f.pt(e.x.value, e.y.value)}$label, ${styleBits(e, f)}"
            is EllipseElement -> "${nameOf(e)}: ${if (e.isCircle) "circle diameter ${f.len(e.rx.value * 2)}" else "ellipse ${f.len(e.rx.value * 2)} x ${f.len(e.ry.value * 2)}"}, centre ${f.pt(e.cx.value, e.cy.value)}$label, ${styleBits(e, f)}"
            is FrameElement -> "${nameOf(e)}: frame ${f.len(e.width.value)} x ${f.len(e.height.value)}, top-left ${f.pt(e.x.value, e.y.value)}"
            is LineElement -> "${nameOf(e)}: ${if (e.polygon) "closed polygon" else "line"} through ${pointsText(e.points, f)}${if (e.polygon) "" else ", length ${f.len(pathLength(e.points, false))}"}, ${styleBits(e, f)}"
            is ArrowElement -> "${nameOf(e)}: arrow from ${endText(e, true, f, all)} to ${endText(e, false, f, all)}$label, ${styleBits(e, f)}"
            is TextElement -> "${nameOf(e)}: text \"${e.originalText.replace("\n", " ")}\" centred at ${f.pt(e.x.value + e.width.value / 2, e.y.value + e.height.value / 2)}, font size ${f.len(e.fontSize)}"
            is PointElement -> "${nameOf(e)}: point at ${f.pt(e.x.value, e.y.value)}"
            is ArcElement -> "${nameOf(e)}: arc radius ${f.len(e.radius.value)} centred ${f.pt(e.cx.value, e.cy.value)}"
            is FreedrawElement -> "${nameOf(e)}: freehand stroke of ${e.points.size} points"
            is WallElement -> "${nameOf(e)}: wall${if (e.closed) " (closed room)" else ""} through ${pointsText(e.points, f)}, length ${f.len(WallGeometry.length(e))}, thickness ${f.len(e.thickness.value)}, ${e.justification.name.lowercase()} justified${WallGeometry.interiorArea(e)?.let { ", clear area ${f.area(it)}" } ?: ""}, ${styleBits(e, f)}"
            is OpeningElement -> "${nameOf(e)}: ${e.kind.name.lowercase()} ${f.len(e.width.value)} wide on ${e.hostId.value} segment ${e.segment + 1}, ${f.len(e.offset.value)} from the segment start${if (e.kind == OpeningKind.Door) ", swings ${if (e.flipSide) "right" else "left"} of the wall direction, hinge at the ${if (e.flipHinge) "far" else "near"} jamb" else ""}"
            is ImageElement -> "${nameOf(e)}: image ${f.len(e.width.value)} x ${f.len(e.height.value)} at ${f.pt(e.x.value, e.y.value)}"
        }
    }

    private fun pointsText(pts: List<ScenePoint>, f: Fmt): String {
        val shown = pts.take(MAX_POINTS_SHOWN).joinToString(" -> ") { f.pt(it.x.value, it.y.value) }
        return if (pts.size > MAX_POINTS_SHOWN) "$shown -> ... (${pts.size} points)" else shown
    }

    private fun pathLength(pts: List<ScenePoint>, closed: Boolean): Double {
        val segs = pts.zipWithNext().sumOf { (a, b) -> hypot(b.x.value - a.x.value, b.y.value - a.y.value) }
        return if (closed && pts.size > 1) segs + hypot(pts.first().x.value - pts.last().x.value, pts.first().y.value - pts.last().y.value) else segs
    }

    /** The shape an arrow end is bound to, else the shape its tip lies in or touches, else the bare point. */
    private fun endText(a: ArrowElement, start: Boolean, f: Fmt, all: List<SceneElement>): String {
        val p = if (start) a.points.first() else a.points.last()
        val bound = (if (start) a.startBinding else a.endBinding)?.elementId?.let { id -> all.firstOrNull { it.id == id } }
        val near = bound ?: all.filter { it !is ArrowElement && it !is TextElement && it.base.layerId.value !in setOf(SketchLayers.OPENINGS, SketchLayers.DIMENSIONS) }
            .firstOrNull { ElementBounds.geometry(it).inflated(a.base.style.strokeWidth + 2.0).let { b -> p.x.value in b.minX..b.maxX && p.y.value in b.minY..b.maxY } }
        val where = f.pt(p.x.value, p.y.value)
        return near?.let { "${nameOf(it)} (end at $where)" } ?: where
    }

    private fun describeOpenings(all: List<SceneElement>, f: Fmt, out: StringBuilder) {
        val openings = all.filter { it.base.layerId.value == SketchLayers.OPENINGS }
        val gaps = openings.filterIsInstance<RectElement>()
        if (gaps.isEmpty()) return
        val rooms = all.filterIsInstance<RectElement>().filter { it.base.layerId.value !in setOf(SketchLayers.OPENINGS, SketchLayers.DIMENSIONS) }
        out.appendLine()
        out.appendLine("OPENINGS (doors and windows; drawn as gaps in walls)")
        for (g in gaps) {
            val stem = g.id.value.substringBeforeLast('.')
            val isDoor = openings.any { it.id.value == "$stem.leaf" }
            val horizontal = g.width.value >= g.height.value
            val along = if (horizontal) g.width.value else g.height.value
            val cx = g.x.value + g.width.value / 2
            val cy = g.y.value + g.height.value / 2
            val tolerance = (if (horizontal) g.height.value else g.width.value) / 2 + 2.0
            var where = "at ${f.pt(cx, cy)}"
            for (r in rooms) {
                val side = when {
                    horizontal && abs(cy - r.y.value) <= tolerance && cx in r.x.value..(r.x.value + r.width.value) -> "north" to (g.x.value - r.x.value)
                    horizontal && abs(cy - (r.y.value + r.height.value)) <= tolerance && cx in r.x.value..(r.x.value + r.width.value) -> "south" to (g.x.value - r.x.value)
                    !horizontal && abs(cx - r.x.value) <= tolerance && cy in r.y.value..(r.y.value + r.height.value) -> "west" to (g.y.value - r.y.value)
                    !horizontal && abs(cx - (r.x.value + r.width.value)) <= tolerance && cy in r.y.value..(r.y.value + r.height.value) -> "east" to (g.y.value - r.y.value)
                    else -> null
                }
                if (side != null) {
                    where = "on the ${side.first} wall of ${ElementNames.nameOf(r)}, starting ${f.len(side.second)} from the ${if (side.first == "north" || side.first == "south") "west" else "north"} end"
                    break
                }
            }
            out.appendLine("- $stem: ${if (isDoor) "door" else "window"} ${f.len(along)} wide, $where")
        }
    }

    private fun describeDimensions(all: List<SceneElement>, f: Fmt, out: StringBuilder) {
        val dims = all.filter { it.base.layerId.value == SketchLayers.DIMENSIONS }
        val lines = dims.filterIsInstance<ArrowElement>()
        if (lines.isEmpty()) return
        out.appendLine()
        out.appendLine("DIMENSIONS (authored annotations)")
        for (l in lines) {
            val stem = l.id.value.substringBeforeLast('.')
            val measured = pathLength(l.points, false)
            val shown = (dims.firstOrNull { it.id.value == "$stem.text" } as? TextElement)?.originalText
            val agrees = shown?.let { SketchLengths.parseInches(it) }?.let { abs(it * (if (f.diagram) 1.0 else SketchLengths.UNITS_PER_INCH) - measured) <= MISMATCH_INCHES * SketchLengths.UNITS_PER_INCH }
            val note = when (agrees) {
                false -> " MISMATCH: the label says \"$shown\" but the line measures ${f.len(measured)}"
                else -> ""
            }
            out.appendLine("- $stem: \"${shown ?: "(no text)"}\" between ${f.pt(l.points.first().x.value, l.points.first().y.value)} and ${f.pt(l.points.last().x.value, l.points.last().y.value)}, measures ${f.len(measured)}$note")
        }
    }

    private class Box(val e: SceneElement, val b: Bounds, val name: String)

    private fun describeRelations(main: List<SceneElement>, f: Fmt, out: StringBuilder) {
        val boxes = main.filter { it is RectElement || it is EllipseElement || it is DiamondElement }.map { Box(it, ElementBounds.geometry(it), nameOf(it)) }
        val lines = ArrayList<String>()
        for (i in boxes.indices) for (j in boxes.indices) {
            if (i >= j) continue
            val a = boxes[i]
            val b = boxes[j]
            val tol = max(a.e.base.style.strokeWidth, b.e.base.style.strokeWidth) + 1.0
            when {
                contains(a.b, b.b, tol) -> lines += "${a.name} contains ${b.name}"
                contains(b.b, a.b, tol) -> lines += "${b.name} contains ${a.name}"
                else -> {
                    val touching = shared(a, b, tol, f)
                    if (touching != null) lines += touching else if (overlap(a.b, b.b, tol)) lines += "${a.name} overlaps ${b.name}"
                }
            }
        }
        val arrows = main.filterIsInstance<ArrowElement>().map { a ->
            val all = main
            "${endName(a, true, all)} -> ${endName(a, false, all)}" + (a.id.let { id -> main.filterIsInstance<TextElement>().firstOrNull { it.containerId == id }?.let { " (\"${it.originalText}\")" } } ?: "")
        }
        if (lines.isEmpty() && arrows.isEmpty()) return
        out.appendLine()
        out.appendLine("RELATIONSHIPS")
        lines.forEach { out.appendLine("- $it") }
        arrows.forEach { out.appendLine("- connects $it") }
    }

    private fun endName(a: ArrowElement, start: Boolean, all: List<SceneElement>): String {
        val p = if (start) a.points.first() else a.points.last()
        val bound = (if (start) a.startBinding else a.endBinding)?.elementId?.let { id -> all.firstOrNull { it.id == id } }
        val found = bound ?: all.filter { it is RectElement || it is EllipseElement || it is DiamondElement }
            .firstOrNull { ElementBounds.geometry(it).inflated(a.base.style.strokeWidth + 2.0).let { b -> p.x.value in b.minX..b.maxX && p.y.value in b.minY..b.maxY } }
        return found?.let { nameOf(it) } ?: "(${p.x.value.roundToInt()}, ${p.y.value.roundToInt()})"
    }

    private fun contains(outer: Bounds, inner: Bounds, tol: Double) =
        outer.minX - tol <= inner.minX && outer.minY - tol <= inner.minY && outer.maxX + tol >= inner.maxX && outer.maxY + tol >= inner.maxY &&
            (outer.maxX - outer.minX) * (outer.maxY - outer.minY) > (inner.maxX - inner.minX) * (inner.maxY - inner.minY)

    private fun overlap(a: Bounds, b: Bounds, tol: Double) =
        min(a.maxX, b.maxX) - max(a.minX, b.minX) > tol && min(a.maxY, b.maxY) - max(a.minY, b.minY) > tol

    private fun shared(a: Box, b: Box, tol: Double, f: Fmt): String? {
        val yOverlap = min(a.b.maxY, b.b.maxY) - max(a.b.minY, b.b.minY)
        val xOverlap = min(a.b.maxX, b.b.maxX) - max(a.b.minX, b.b.minX)
        return when {
            abs(a.b.maxX - b.b.minX) <= tol && yOverlap > tol -> "${a.name} touches ${b.name} (${a.name}'s east side meets ${b.name}'s west side, shared edge ${f.len(yOverlap)})"
            abs(b.b.maxX - a.b.minX) <= tol && yOverlap > tol -> "${a.name} touches ${b.name} (${a.name}'s west side meets ${b.name}'s east side, shared edge ${f.len(yOverlap)})"
            abs(a.b.maxY - b.b.minY) <= tol && xOverlap > tol -> "${a.name} touches ${b.name} (${a.name}'s south side meets ${b.name}'s north side, shared edge ${f.len(xOverlap)})"
            abs(b.b.maxY - a.b.minY) <= tol && xOverlap > tol -> "${a.name} touches ${b.name} (${a.name}'s north side meets ${b.name}'s south side, shared edge ${f.len(xOverlap)})"
            else -> null
        }
    }

    private fun describeConstraints(doc: SketchDocumentV2, out: StringBuilder) {
        if (doc.constraints.isEmpty()) return
        out.appendLine()
        out.appendLine("CONSTRAINTS (${doc.constraints.size}; the app's solver keeps these true when shapes move)")
        for (c in doc.constraints) {
            val targets = c.targets.joinToString(", ") { t -> t.elementId.value + (if (t.part.label == "whole") "" else ".${t.part.label}") }
            out.appendLine("- ${c.kind.name} on $targets" + (c.value?.let { " = ${SketchLengths.tidy(it)}" } ?: "") + if (c.reference) " (reference, not driving)" else "")
        }
    }

    private fun describeMap(main: List<SceneElement>, labels: Map<dev.stapler.stelekit.sketch.v2.scene.ElementId, List<TextElement>>, f: Fmt, out: StringBuilder) {
        val boxes = main.filter { it is RectElement || it is EllipseElement || it is DiamondElement }
        if (boxes.isEmpty()) return
        val bounds = boxes.map { ElementBounds.geometry(it) }.reduce(::union)
        val w = bounds.maxX - bounds.minX
        val h = bounds.maxY - bounds.minY
        if (w <= 0.0 || h <= 0.0) return
        val rows = (MAP_COLUMNS * h / w / CHAR_ASPECT).roundToInt().coerceIn(MAP_MIN_ROWS, MAP_MAX_ROWS)
        val grid = Array(rows) { CharArray(MAP_COLUMNS) { ' ' } }
        val sorted = boxes.sortedByDescending { ElementBounds.geometry(it).let { b -> (b.maxX - b.minX) * (b.maxY - b.minY) } }
        for (e in sorted) {
            val b = ElementBounds.geometry(e)
            val c0 = ((b.minX - bounds.minX) / w * (MAP_COLUMNS - 1)).roundToInt()
            val c1 = ((b.maxX - bounds.minX) / w * (MAP_COLUMNS - 1)).roundToInt()
            val r0 = ((b.minY - bounds.minY) / h * (rows - 1)).roundToInt()
            val r1 = ((b.maxY - bounds.minY) / h * (rows - 1)).roundToInt()
            for (c in c0..c1) { grid[r0][c] = '-'; grid[r1][c] = '-' }
            for (r in r0..r1) { grid[r][c0] = '|'; grid[r][c1] = '|' }
            grid[r0][c0] = '+'; grid[r0][c1] = '+'; grid[r1][c0] = '+'; grid[r1][c1] = '+'
            val text = labels[e.id]?.firstOrNull()?.originalText?.substringBefore('\n') ?: ElementNames.nameOf(e)
            val inner = c1 - c0 - 1
            if (inner >= 1 && r1 - r0 >= 2) {
                val shown = text.take(inner)
                val start = c0 + 1 + (inner - shown.length) / 2
                val row = (r0 + r1) / 2
                shown.forEachIndexed { i, ch -> grid[row][start + i] = ch }
            }
        }
        out.appendLine()
        out.appendLine("MAP (coarse; north is up, each cell is about ${f.len(w / MAP_COLUMNS)} wide; labels are truncated to fit)")
        grid.forEach { out.appendLine(it.concatToString().trimEnd()) }
    }
}
