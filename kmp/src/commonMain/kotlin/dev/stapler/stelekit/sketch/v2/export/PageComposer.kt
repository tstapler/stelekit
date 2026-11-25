// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.export

import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.DrawRect
import dev.stapler.stelekit.sketch.v2.render.PathSegment
import dev.stapler.stelekit.sketch.v2.render.StrokePaint
import dev.stapler.stelekit.sketch.v2.render.TextLine
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Moves and scales a draw list from scene units to page units: a point goes to `p * factor + offset`, a stroke width and a
 * font size scale with it, and a stroke never gets thinner than [MIN_LINE] (0.18 mm, the thinnest line drafting standards
 * print), so a thin line of a drawing at 1:48 is still on the paper.
 */
object PageOps {
    /** 0.18 mm in page units (a sixteenth of an inch is 1.5875 mm). */
    const val MIN_LINE = 0.1134

    fun place(ops: List<DrawOp>, factor: Double, dx: Double, dy: Double): List<DrawOp> = ops.map { place(it, factor, dx, dy) }

    private fun place(op: DrawOp, f: Double, dx: Double, dy: Double): DrawOp = when (op) {
        is DrawOp.PathOp -> op.copy(
            segments = op.segments.map { s ->
                when (s) {
                    is PathSegment.MoveTo -> PathSegment.MoveTo(s.x * f + dx, s.y * f + dy)
                    is PathSegment.LineTo -> PathSegment.LineTo(s.x * f + dx, s.y * f + dy)
                    is PathSegment.CubicTo -> PathSegment.CubicTo(s.c1x * f + dx, s.c1y * f + dy, s.c2x * f + dx, s.c2y * f + dy, s.x * f + dx, s.y * f + dy)
                    PathSegment.Close -> s
                }
            },
            stroke = op.stroke?.let { StrokePaint(it.color, max(it.width * f, MIN_LINE), it.dash.map { d -> d * f }) },
        )
        is DrawOp.TextRun -> op.copy(
            lines = op.lines.map { TextLine(it.text, it.x * f + dx, it.y * f + dy) },
            fontSize = op.fontSize * f,
            pivotX = op.pivotX * f + dx,
            pivotY = op.pivotY * f + dy,
        )
        is DrawOp.ImageOp -> op.copy(rect = rect(op.rect, f, dx, dy))
        is DrawOp.ClipOp -> DrawOp.ClipOp(rect(op.rect, f, dx, dy), place(op.ops, f, dx, dy))
    }

    private fun rect(r: DrawRect, f: Double, dx: Double, dy: Double) = DrawRect(r.x * f + dx, r.y * f + dy, r.width * f, r.height * f)
}

/** What a page says about itself beyond the sheet setup: nothing is read from a clock, so a page is a pure function of its inputs. */
object PageText {
    const val TITLE = "TITLE"
    const val SHEET = "SHEET"
    const val AUTHOR = "AUTHOR"
    const val DATE = "DATE"
    const val SCALE = "SCALE"
    const val NOTES = "NOTES"
    const val NORTH = "N"

    fun tilePosition(tile: PageTile, plan: SheetPlan): String =
        "Page ${tile.index + 1} of ${plan.pageCount} (column ${tile.column + 1}, row ${tile.row + 1})"
}

/**
 * The page of a sheet (E7) as a draw list in page units: the drawing placed at the sheet's scale, the border, the title block,
 * the scale bar and the north arrow. The furniture is export-only (see `docs/reference/sketch-format-v2.md`): it belongs to the
 * page, not to the model, so it is sized in paper inches whatever the drawing scale, and the editor's canvas never shows it.
 * The scale bar is the one furniture element whose length is a real length: it is drawn at the sheet's scale, so a ruler
 * laid on the printed bar measures what the bar says.
 */
object PageComposer {
    private const val FAMILY_SANS = 2
    private const val BORDER_WEIGHT = 0.3
    private const val CELL_WEIGHT = 0.15
    private const val LABEL_SIZE = 1.1
    private const val VALUE_SIZE = 2.4
    private const val TITLE_SIZE = 3.4
    private const val NOTES_SIZE = 1.8
    private const val CELL_PAD = 0.5
    private const val LABEL_OPACITY = 0.6
    private const val TITLE_SHARE = 0.7
    private const val AUTHOR_SHARE = 0.4
    private const val DATE_SHARE = 0.3
    private const val VALUE_DROP = 1.4
    private const val BAR_TARGET = 40.0
    private const val BAR_SHARE = 0.55
    private const val BAR_INSET = 2.0
    private const val BAR_TOP = 3.2
    private const val BAR_HEIGHT = 1.2
    private const val BAR_DIVISIONS = 4
    private const val BAR_LABEL_SIZE = 1.8
    private const val BAR_LABEL_TOP = 0.6
    private const val CAPTION_TOP = 4.8
    private const val NORTH_CENTRE_Y = 5.6
    private const val NORTH_INSET = 5.0
    private const val NORTH_TIP = 3.0
    private const val NORTH_BASE = 2.4
    private const val NORTH_NOTCH = 1.2
    private const val NORTH_HALF = 1.5
    private const val NORTH_LABEL = 4.2
    private const val DEGREES_PER_HALF_TURN = 180.0
    private const val HALF = 2.0
    private const val MM_PER_UNIT = 1.5875
    private const val MM_PER_METRE = 1000
    private const val FILL_NONE = "#ffffff"

    private val IMPERIAL_BARS = listOf(
        8.0 to "1/2\"", 16.0 to "1\"", 32.0 to "2\"", 96.0 to "6\"", 192.0 to "1'", 384.0 to "2'", 960.0 to "5'", 1920.0 to "10'",
        3840.0 to "20'", 9600.0 to "50'", 19200.0 to "100'", 38400.0 to "200'", 96000.0 to "500'",
    )
    private val METRIC_BAR_MM = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000, 500000)

    /** One bar length: the real length in scene units and its label. */
    private class BarLength(val units: Double, val label: String)

    private fun barLengths(metric: Boolean): List<BarLength> =
        if (metric) METRIC_BAR_MM.map { mm -> BarLength(mm / MM_PER_UNIT, if (mm >= MM_PER_METRE) "${mm / MM_PER_METRE} m" else "$mm mm") }
        else IMPERIAL_BARS.map { BarLength(it.first, it.second) }

    /** The longest bar (a round real length) that fits [strip] at [factor], or null when even the shortest does not. */
    private fun barFor(metric: Boolean, factor: Double, strip: PageRect): BarLength? {
        val target = minOf(BAR_TARGET, strip.width * BAR_SHARE)
        return barLengths(metric).lastOrNull { it.units * factor <= target }
    }

    /**
     * The page of [tile]: [content] (scene units) placed at the sheet's scale, then the furniture. [clip] clips the drawing to the
     * drawing area; it is set by the caller for a tiled page, an overflowing page and a frame crop. [ink] colours the furniture.
     */
    fun compose(plan: SheetPlan, tile: PageTile, content: List<DrawOp>, clip: Boolean, ink: String): List<DrawOp> {
        val f = plan.factor
        val (ox, oy) = plan.origin(tile)
        val placed = PageOps.place(content, f, ox - plan.content.minX * f, oy - plan.content.minY * f)
        val out = ArrayList<DrawOp>()
        out += if (clip) listOf(DrawOp.ClipOp(DrawRect(plan.area.x, plan.area.y, plan.area.width, plan.area.height), placed)) else placed
        out += rectangle("page.border", plan.printable, StrokePaint(ink, BORDER_WEIGHT))
        plan.titleBand?.let { band -> plan.setup.titleBlock?.let { out += titleBlock(plan, band, it, ink) } }
        plan.strip?.let { strip ->
            if (plan.setup.scaleBar) out += scaleBar(plan, strip, ink)
            plan.setup.northDegrees?.let { out += northArrow(strip, it, ink) }
            if (plan.pageCount > 1) out += label("page.tile", PageText.tilePosition(tile, plan), strip.x + strip.width / HALF, strip.y + BAR_LABEL_TOP, BAR_LABEL_SIZE, TextAlign.Center, ink, 1.0)
        }
        return out
    }

    private fun rectangle(id: String, r: PageRect, stroke: StrokePaint): DrawOp.PathOp = DrawOp.PathOp(
        ElementId(id),
        listOf(PathSegment.MoveTo(r.x, r.y), PathSegment.LineTo(r.right, r.y), PathSegment.LineTo(r.right, r.bottom), PathSegment.LineTo(r.x, r.bottom), PathSegment.Close),
        stroke, null, 1.0,
    )

    private fun label(id: String, text: String, x: Double, top: Double, size: Double, align: TextAlign, color: String, opacity: Double): DrawOp.TextRun =
        DrawOp.TextRun(ElementId(id), listOf(TextLine(text, x, top)), FAMILY_SANS, size, color, align, 0.0, x, top, false, opacity)

    private fun titleBlock(plan: SheetPlan, band: PageRect, block: TitleBlock, ink: String): List<DrawOp> {
        val out = ArrayList<DrawOp>()
        val main = SheetPlanner.TITLE_MAIN_ROW
        val meta = SheetPlanner.TITLE_META_ROW
        val w = band.width
        out += cell("title", PageRect(band.x, band.y, w * TITLE_SHARE, main), PageText.TITLE, block.title, TITLE_SIZE, ink)
        out += cell("sheet", PageRect(band.x + w * TITLE_SHARE, band.y, w * (1 - TITLE_SHARE), main), PageText.SHEET, block.sheet, VALUE_SIZE, ink)
        val y = band.y + main
        out += cell("author", PageRect(band.x, y, w * AUTHOR_SHARE, meta), PageText.AUTHOR, block.author, VALUE_SIZE, ink)
        out += cell("date", PageRect(band.x + w * AUTHOR_SHARE, y, w * DATE_SHARE, meta), PageText.DATE, block.date, VALUE_SIZE, ink)
        val scaleX = band.x + w * (AUTHOR_SHARE + DATE_SHARE)
        out += cell("scale", PageRect(scaleX, y, band.right - scaleX, meta), PageText.SCALE, plan.setup.scale.label, VALUE_SIZE, ink)
        if (block.notes.isNotBlank()) out += cell("notes", PageRect(band.x, y + meta, w, SheetPlanner.TITLE_NOTES_ROW), PageText.NOTES, block.notes.replace('\n', ' '), NOTES_SIZE, ink)
        return out
    }

    private fun cell(id: String, r: PageRect, caption: String, value: String, size: Double, ink: String): List<DrawOp> {
        val text = PdfFonts.fit(value, size, r.width - 2 * CELL_PAD)
        return listOf(
            rectangle("page.$id", r, StrokePaint(ink, CELL_WEIGHT)),
            label("page.$id.caption", caption, r.x + CELL_PAD, r.y + CELL_PAD, LABEL_SIZE, TextAlign.Left, ink, LABEL_OPACITY),
            label("page.$id.value", text, r.x + CELL_PAD, r.y + CELL_PAD + VALUE_DROP, size, TextAlign.Left, ink, 1.0),
        )
    }

    private fun scaleBar(plan: SheetPlan, strip: PageRect, ink: String): List<DrawOp> {
        val bar = barFor(plan.metric, plan.factor, strip) ?: return emptyList()
        val length = bar.units * plan.factor
        val x0 = strip.x + BAR_INSET
        val top = strip.y + BAR_TOP
        val step = length / BAR_DIVISIONS
        val out = ArrayList<DrawOp>()
        for (i in 0 until BAR_DIVISIONS) {
            val segment = PageRect(x0 + i * step, top, step, BAR_HEIGHT)
            val filled = i % 2 == 0
            out += (rectangle("page.bar.$i", segment, StrokePaint(ink, CELL_WEIGHT)) as DrawOp.PathOp).copy(fill = if (filled) ink else FILL_NONE)
        }
        out += label("page.bar.zero", "0", x0, strip.y + BAR_LABEL_TOP, BAR_LABEL_SIZE, TextAlign.Center, ink, 1.0)
        out += label("page.bar.end", bar.label, x0 + length, strip.y + BAR_LABEL_TOP, BAR_LABEL_SIZE, TextAlign.Center, ink, 1.0)
        out += label("page.bar.caption", plan.setup.scale.label, x0, strip.y + CAPTION_TOP, BAR_LABEL_SIZE, TextAlign.Left, ink, 1.0)
        return out
    }

    private fun northArrow(strip: PageRect, degrees: Int, ink: String): List<DrawOp> {
        val cx = strip.right - NORTH_INSET
        val cy = strip.y + NORTH_CENTRE_Y
        val turn = degrees * PI / DEGREES_PER_HALF_TURN
        val c = cos(turn)
        val s = sin(turn)
        fun at(x: Double, y: Double) = (cx + x * c - y * s) to (cy + x * s + y * c)
        val tip = at(0.0, -NORTH_TIP)
        val right = at(NORTH_HALF, NORTH_BASE)
        val notch = at(0.0, NORTH_NOTCH)
        val left = at(-NORTH_HALF, NORTH_BASE)
        val arrow = DrawOp.PathOp(
            ElementId("page.north"),
            listOf(PathSegment.MoveTo(tip.first, tip.second), PathSegment.LineTo(right.first, right.second), PathSegment.LineTo(notch.first, notch.second), PathSegment.LineTo(left.first, left.second), PathSegment.Close),
            StrokePaint(ink, CELL_WEIGHT), ink, 1.0,
        )
        val n = at(0.0, -NORTH_LABEL)
        return listOf(arrow, label("page.north.label", PageText.NORTH, n.first, n.second - BAR_LABEL_SIZE / HALF, BAR_LABEL_SIZE, TextAlign.Center, ink, 1.0))
    }
}
