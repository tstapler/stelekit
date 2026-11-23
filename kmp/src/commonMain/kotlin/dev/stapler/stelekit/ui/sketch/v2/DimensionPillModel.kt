// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.DimensionInput
import dev.stapler.stelekit.sketch.v2.scene.BoxElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.tools.TouchFloor
import dev.stapler.stelekit.ui.sketch.lengthTextOf
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchDimensionStrings
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin

/** What one on-canvas dimension pill measures. */
enum class PillKind { Width, Height, Diameter, Radius, Length }

/** One measurable of the selected element: [sixteenths] is its current size (a scene unit is a sixteenth of an inch). */
data class PillSpec(val kind: PillKind, val sixteenths: Long)

/** A pill after layout: [text] is what it shows, the rest is its rectangle in the canvas's dp space. */
data class PlacedPill(val kind: PillKind, val text: String, val centreX: Double, val centreY: Double, val widthDp: Double, val heightDp: Double) {
    val left: Double get() = centreX - widthDp / 2
    val top: Double get() = centreY - heightDp / 2
    val right: Double get() = centreX + widthDp / 2
    val bottom: Double get() = centreY + heightDp / 2

    fun overlaps(o: PlacedPill): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/**
 * The canvas the pills sit on, in dp: [toScreenDp] maps a scene point through the viewport (px divided by density), [dpPerUnit] is
 * the zoom in dp per scene unit, and [width] by [height] is the visible area.
 */
class PillCanvas(val toScreenDp: (Double, Double) -> Pair<Double, Double>, val dpPerUnit: Double, val width: Double, val height: Double) {
    fun fits(p: PlacedPill): Boolean =
        p.left >= DimensionPillModel.EDGE_DP && p.top >= DimensionPillModel.EDGE_DP && p.right <= width - DimensionPillModel.EDGE_DP && p.bottom <= height - DimensionPillModel.EDGE_DP

    /** [p] moved the least that puts it inside the canvas; a canvas smaller than the pill pins it to the top-left keep-out. */
    fun clamp(p: PlacedPill): PlacedPill {
        val edge = DimensionPillModel.EDGE_DP
        val minX = edge + p.widthDp / 2
        val minY = edge + p.heightDp / 2
        return p.copy(centreX = p.centreX.coerceIn(minX, max(minX, width - minX)), centreY = p.centreY.coerceIn(minY, max(minY, height - minY)))
    }
}

/** The element's box on screen in dp: centre, half sides and rotation, the frame the pills hang off. */
data class PillFrame(val cx: Double, val cy: Double, val halfW: Double, val halfH: Double, val angle: Double)

/**
 * Pure rules for the dimension pills of a technical sheet: which pills an element gets, where they sit (outside the shape,
 * inside the viewport, never on each other) and the command a typed value becomes. Everything is a function of the element,
 * the viewport and the canvas size, so it is tested without Compose.
 */
object DimensionPillModel {
    /** Space between a shape's edge and its pill, and the keep-out from the canvas edge, in dp. */
    const val GAP_DP = 6.0
    const val EDGE_DP = 4.0

    /** A pill is at least as tall as a finger-sized target (UX-46) so a tap always lands. */
    val PILL_HEIGHT_DP: Double = TouchFloor.TOUCH_DP

    private const val CHAR_DP = 9.0
    private const val PAD_DP = 28.0

    /** The pills the element gets, in edit order (Width then Height); none for kinds with no single size to type. */
    fun specs(element: SceneElement): List<PillSpec> = when (element) {
        is RectElement -> boxSpecs(element.width.value, element.height.value)
        is DiamondElement -> boxSpecs(element.width.value, element.height.value)
        is EllipseElement -> if (element.isCircle) listOf(PillSpec(PillKind.Diameter, sixteenths(2 * element.rx.value)), PillSpec(PillKind.Radius, sixteenths(element.rx.value))) else boxSpecs(2 * element.rx.value, 2 * element.ry.value)
        is LineElement -> lineLength(element)?.let { listOf(PillSpec(PillKind.Length, sixteenths(it))) }.orEmpty()
        else -> emptyList()
    }

    private fun boxSpecs(w: Double, h: Double) = listOf(PillSpec(PillKind.Width, sixteenths(w)), PillSpec(PillKind.Height, sixteenths(h)))

    private fun sixteenths(v: Double): Long = max(1L, round(v).toLong())

    internal fun lineLength(e: LineElement): Double? {
        if (e.polygon || e.points.size != 2) return null
        val (a, b) = e.points
        return hypot(b.x.value - a.x.value, b.y.value - a.y.value).takeIf { it > 0.0 }
    }

    /** Whether pills show at all: a technical sheet that can be edited, pills switched on, one shape selected and the Select tool (a drawing tool would lose its drag to a pill). */
    fun shown(technical: Boolean, readOnly: Boolean, visible: Boolean, selected: Int, tool: ToolId): Boolean =
        technical && !readOnly && visible && selected == 1 && tool == ToolId.Select

    /**
     * The fields one edit walks through, starting at [start]: Width then Height for a box or ellipse. A circle's Diameter and
     * Radius are the same size, so each is its own one-field edit.
     */
    fun editKinds(element: SceneElement, start: PillKind): List<PillKind> {
        val all = specs(element).map { it.kind }
        return if (PillKind.Diameter in all) listOf(if (start in all) start else PillKind.Diameter) else all
    }

    fun label(kind: PillKind): String = when (kind) {
        PillKind.Width -> SketchDimensionStrings.Pill.WIDTH
        PillKind.Height -> SketchDimensionStrings.Pill.HEIGHT
        PillKind.Diameter -> SketchDimensionStrings.Pill.DIAMETER
        PillKind.Length -> SketchDimensionStrings.Pill.LENGTH
        PillKind.Radius -> SketchDimensionStrings.Pill.RADIUS
    }

    private fun symbol(kind: PillKind): String = when (kind) {
        PillKind.Width -> SketchDimensionStrings.Pill.WIDTH_SYMBOL
        PillKind.Height -> SketchDimensionStrings.Pill.HEIGHT_SYMBOL
        PillKind.Diameter -> SketchDimensionStrings.Pill.DIAMETER_SYMBOL
        PillKind.Length -> SketchDimensionStrings.Pill.LENGTH_SYMBOL
        PillKind.Radius -> SketchDimensionStrings.Pill.RADIUS_SYMBOL
    }

    /** The text on a pill: a one-letter name and the value in feet, inches and sixteenths. */
    fun pillText(spec: PillSpec): String = symbol(spec.kind) + " " + lengthTextOf(spec.sixteenths)

    fun pillWidthDp(text: String): Double = max(TouchFloor.TOUCH_DP, PAD_DP + CHAR_DP * text.length)

    /**
     * Where each pill of [element] goes on [canvas]. Width and Diameter go outside the bottom edge (the top edge carries the
     * rotate handle), Height and Radius outside the right edge; a pill that would leave the canvas flips to the opposite side, and one with no room on either side is clamped inside. A pill that
     * lands on another is moved along its own edge.
     */
    fun layout(element: SceneElement, canvas: PillCanvas): List<PlacedPill> {
        val specs = specs(element)
        if (specs.isEmpty() || canvas.width <= 0.0 || canvas.height <= 0.0) return emptyList()
        val placed = ArrayList<PlacedPill>(specs.size)
        for (spec in specs) {
            val text = pillText(spec)
            val w = pillWidthDp(text)
            val raw = if (element is LineElement) linePill(element, spec, text, w, canvas) else boxPill(element, spec, text, w, canvas)
            placed += separate(raw, placed, canvas)
        }
        return placed
    }

    private fun frameOf(element: SceneElement, canvas: PillCanvas): PillFrame? {
        val s = ElementStats.of(element) ?: return null
        val (cx, cy) = canvas.toScreenDp(s.x + s.width / 2, s.y + s.height / 2)
        val angle = (element as? BoxElement)?.angle ?: (element as? EllipseElement)?.angle ?: 0.0
        return PillFrame(cx, cy, s.width / 2 * canvas.dpPerUnit, s.height / 2 * canvas.dpPerUnit, angle)
    }

    private fun boxPill(element: SceneElement, spec: PillSpec, text: String, w: Double, canvas: PillCanvas): PlacedPill {
        val f = frameOf(element, canvas) ?: return PlacedPill(spec.kind, text, canvas.width / 2, canvas.height / 2, w, PILL_HEIGHT_DP)
        val h = PILL_HEIGHT_DP
        val horizontalEdge = spec.kind != PillKind.Height && spec.kind != PillKind.Radius
        fun at(sign: Double): PlacedPill {
            val c = cos(f.angle)
            val s = sin(f.angle)
            // The outward normal in screen space; the pill is an upright rectangle, so it clears the outline by its support distance along that normal.
            val nx = if (horizontalEdge) -sign * s else sign * c
            val ny = if (horizontalEdge) sign * c else sign * s
            val reach = (if (horizontalEdge) f.halfH else f.halfW) + GAP_DP + abs(nx) * w / 2 + abs(ny) * h / 2
            return PlacedPill(spec.kind, text, f.cx + nx * reach, f.cy + ny * reach, w, h)
        }
        // Bottom for Width and Right for Height are the primary sides; the other side is the flip.
        return firstFitting(at(1.0), at(-1.0), canvas)
    }

    private fun linePill(line: LineElement, spec: PillSpec, text: String, w: Double, canvas: PillCanvas): PlacedPill {
        val (a, b) = line.points.map { canvas.toScreenDp(it.x.value, it.y.value) }
        val mx = (a.first + b.first) / 2
        val my = (a.second + b.second) / 2
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len = hypot(dx, dy).takeIf { it > 0.0 } ?: 1.0
        // The normal that points up the screen, so the pill reads above the line; flipped below when there is no room.
        val flip = if (dx / len > 0.0) 1.0 else -1.0
        val nx = dy / len * flip
        val ny = -dx / len * flip
        val h = PILL_HEIGHT_DP
        // Far enough along the normal that the pill's own rectangle clears the line, whatever the line's angle.
        val reach = abs(nx) * w / 2 + abs(ny) * h / 2 + GAP_DP
        fun at(sign: Double) = PlacedPill(spec.kind, text, mx + sign * nx * reach, my + sign * ny * reach, w, h)
        return firstFitting(at(1.0), at(-1.0), canvas)
    }

    private fun firstFitting(primary: PlacedPill, flipped: PlacedPill, canvas: PillCanvas): PlacedPill = when {
        canvas.fits(primary) -> primary
        canvas.fits(flipped) -> flipped
        else -> canvas.clamp(primary)
    }

    /** Moves [p] to the nearest spot that clears every pill in [others]: along its own edge first (down for Height and Radius, left otherwise), then the other ways. */
    private fun separate(p: PlacedPill, others: List<PlacedPill>, canvas: PillCanvas): PlacedPill {
        val start = canvas.clamp(p)
        val blockers = others.filter { start.overlaps(it) }
        if (blockers.isEmpty()) return start
        val vertical = p.kind == PillKind.Height || p.kind == PillKind.Radius
        val o = blockers.first()
        val down = start.copy(centreY = o.bottom + GAP_DP + start.heightDp / 2)
        val up = start.copy(centreY = o.top - GAP_DP - start.heightDp / 2)
        val left = start.copy(centreX = o.left - GAP_DP - start.widthDp / 2)
        val right = start.copy(centreX = o.right + GAP_DP + start.widthDp / 2)
        val moves = if (vertical) listOf(down, up, left, right) else listOf(left, right, up, down)
        return moves.map(canvas::clamp).firstOrNull { c -> others.none { c.overlaps(it) } } ?: start
    }

    /** The sixteenths of a typed length, or the parser's message; the same grammar and messages as the Style tab's size fields. */
    fun parse(text: String): ParsedLength = DimensionInput.parse(text).fold({ ParsedLength.Invalid(it.message) }, { ParsedLength.Valid(it.sixteenths) })

    sealed interface ParsedLength {
        data class Valid(val sixteenths: Long) : ParsedLength

        data class Invalid(val message: String) : ParsedLength
    }
}
