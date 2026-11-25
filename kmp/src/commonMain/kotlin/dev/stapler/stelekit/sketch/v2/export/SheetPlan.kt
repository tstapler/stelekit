// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.export

import dev.stapler.stelekit.sketch.v2.index.Bounds
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.round

/** A rectangle in page units (1/16 inch, y down). */
data class PageRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val right: Double get() = x + width
    val bottom: Double get() = y + height
}

/**
 * One printed page of a tiled sheet. [window] is the part of the scaled drawing it shows, in page units from the drawing's
 * top-left corner. Windows are disjoint and together cover the whole drawing, so every point is printed exactly once.
 */
data class PageTile(val index: Int, val column: Int, val row: Int, val window: PageRect)

/**
 * Where the drawing goes on the paper (E7): the page, its margins, the bands the title block and the scale bar reserve, and how
 * many pages the drawing needs at the chosen scale. All lengths are page units, a sixteenth of an inch, which is also the scene
 * unit, so a drawing at 1:1 is the identity. [content] is the drawing's extent in scene units.
 */
class SheetPlan(
    val setup: SheetSetup,
    val metric: Boolean,
    val content: Bounds,
    val pageWidth: Double,
    val pageHeight: Double,
    val printable: PageRect,
    val area: PageRect,
    val titleBand: PageRect?,
    val strip: PageRect?,
    val columns: Int,
    val rows: Int,
) {
    val factor: Double get() = setup.scale.factor
    val scaledWidth: Double get() = (content.maxX - content.minX) * factor
    val scaledHeight: Double get() = (content.maxY - content.minY) * factor
    val pageCount: Int get() = (columns.toLong() * rows).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** True when one page holds the whole drawing at this scale. */
    val fits: Boolean get() = columns == 1 && rows == 1 && area.width > 0 && area.height > 0

    /** True when the drawing needs more pages than a tiled export writes ([SheetPlanner.PAGE_BUDGET]). */
    val tooManyPages: Boolean get() = columns.toLong() * rows > SheetPlanner.PAGE_BUDGET

    /** The windows in reading order (row by row); a single tile covering the drawing when it fits or when the plan has too many pages to tile. */
    val tiles: List<PageTile>
        get() {
            if (tooManyPages || area.width <= 0 || area.height <= 0) return listOf(PageTile(0, 0, 0, PageRect(0.0, 0.0, scaledWidth, scaledHeight)))
            return (0 until rows).flatMap { r ->
                (0 until columns).map { c ->
                    val x = c * area.width
                    val y = r * area.height
                    PageTile(r * columns + c, c, r, PageRect(x, y, minOf(area.width, scaledWidth - x), minOf(area.height, scaledHeight - y)))
                }
            }
        }

    /** Where the drawing's top-left corner lands on [tile]'s page: centred in the area when it fits, else the area's corner shifted by the tile's window. */
    fun origin(tile: PageTile): Pair<Double, Double> {
        val x = if (columns == 1 && scaledWidth <= area.width) area.x + (area.width - scaledWidth) / 2 else area.x - tile.window.x
        val y = if (rows == 1 && scaledHeight <= area.height) area.y + (area.height - scaledHeight) / 2 else area.y - tile.window.y
        return x to y
    }
}

object SheetPlanner {
    /** Pages a tiled export writes at most; a drawing that needs more is told to use a smaller scale. */
    const val PAGE_BUDGET = 64

    /** Row heights of the title block, in page units (a sixteenth of an inch). */
    const val TITLE_MAIN_ROW = 7.0
    const val TITLE_META_ROW = 5.0
    const val TITLE_NOTES_ROW = 5.0

    /** Height of the band above the title block that holds the scale bar and the north arrow. */
    const val STRIP_HEIGHT = 9.0

    private const val FIT_SLACK = 1e-6

    fun plan(setup: SheetSetup, content: Bounds, metric: Boolean): SheetPlan {
        val pageW = setup.widthUnits
        val pageH = setup.heightUnits
        val margin = Micrometers.toPageUnits(setup.margin)
        val printable = PageRect(margin, margin, max(0.0, pageW - 2 * margin), max(0.0, pageH - 2 * margin))
        val titleHeight = setup.titleBlock?.let { titleBandHeight(it) } ?: 0.0
        val title = setup.titleBlock?.let { PageRect(printable.x, printable.bottom - titleHeight, printable.width, titleHeight) }
        val furniture = setup.scaleBar || setup.northDegrees != null
        val strip = if (furniture) PageRect(printable.x, printable.bottom - titleHeight - STRIP_HEIGHT, printable.width, STRIP_HEIGHT) else null
        val reserved = titleHeight + (if (furniture) STRIP_HEIGHT else 0.0)
        val area = PageRect(printable.x, printable.y, printable.width, max(0.0, printable.height - reserved))
        val scaledW = (content.maxX - content.minX) * setup.scale.factor
        val scaledH = (content.maxY - content.minY) * setup.scale.factor
        val usable = area.width > 0 && area.height > 0
        val columns = if (usable) pagesFor(scaledW, area.width) else 1
        val rows = if (usable) pagesFor(scaledH, area.height) else 1
        return SheetPlan(setup, metric, content, pageW, pageH, printable, area, title, strip, columns, rows)
    }

    /** Pages along one axis, held just over [PAGE_BUDGET] so a huge or infinite drawing is "too many pages" without a product that overflows. */
    private fun pagesFor(length: Double, per: Double): Int {
        val pages = ceil((length - FIT_SLACK) / per)
        return if (pages.isNaN() || pages > PAGE_BUDGET) PAGE_BUDGET + 1 else max(1, pages.toInt())
    }

    fun titleBandHeight(block: TitleBlock): Double = TITLE_MAIN_ROW + TITLE_META_ROW + (if (block.notes.isBlank()) 0.0 else TITLE_NOTES_ROW)

    /** The largest standard scale of the family whose drawing fits on one page of [setup]'s paper, or null when even the smallest does not. */
    fun bestFit(setup: SheetSetup, content: Bounds, metric: Boolean): DrawingScale? =
        ScalePresets.family(metric).map { it.scale }.firstOrNull { plan(setup.copy(scale = it), content, metric).fits }

    /** A page length for the fit check: `7.5 in` to a tenth of an inch, or `190 mm` to the millimetre. */
    fun lengthText(units: Double, imperial: Boolean): String {
        if (!imperial) return "${round(units * MM_PER_UNIT).toLong()} mm"
        val tenths = round(units * TENTHS_PER_INCH / Micrometers.SIXTEENTHS_PER_INCH).toLong()
        return "${tenths / TENTHS_PER_INCH.toLong()}.${tenths % TENTHS_PER_INCH.toLong()} in"
    }

    private const val MM_PER_UNIT = 1.5875
    private const val TENTHS_PER_INCH = 10.0

    /**
     * The orientation that suits [content]: landscape when the drawing is wider than tall. A sheet with no extent stays portrait.
     */
    fun orientationFor(content: Bounds): Orientation =
        if (content.maxX - content.minX > content.maxY - content.minY) Orientation.Landscape else Orientation.Portrait
}
