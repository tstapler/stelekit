// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.library

import arrow.core.getOrElse
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import kotlin.math.PI

/**
 * The built-in symbol set of a floor plan: joinery (door swing, window), stairs, bathroom and kitchen fixtures, furniture
 * outlines, a north arrow and a callout. Every symbol is drawn at true size on a sheet whose unit is 1/16 inch (a sink
 * 20 inches wide is 320 units), with its top-left corner at the origin, so it lands the right size next to a measured wall.
 * They are ordinary [LibraryItem]s of a read-only [library] kept apart from the user's own library (it is not stored and
 * does not use the user's item or byte caps); [ElementLibrary.insert] gives fresh ids each time. Symbols are plain shapes
 * with no hosting: a door or window that must sit in a wall is placed with the Door and Window modes of the Line tool.
 */
object StarterLibrary {
    /** Ids start with this, so a host tells a starter symbol from a saved one. */
    const val ID_PREFIX = "starter."

    private const val INCH = 16.0
    private const val CREATED = 0L
    private const val FONT_FAMILY_NORMAL = 2
    private const val PLAN_FONT = 96.0
    private const val CHAR_WIDTH = 0.55
    private const val LINE_HEIGHT = 1.25
    private const val INK = "#495057"
    private const val GUIDE = "#868e96"
    private const val THIN = 16.0

    private class Draw(private val prefix: String) {
        val elements = ArrayList<SceneElement>()
        private var next = 0

        private fun unit(v: Double): SceneUnit = SceneUnit.of(v).getOrElse { SceneUnit.ZERO }

        private fun base(stroke: String = INK, fill: String = "transparent", dashed: Boolean = false) = ElementBase(
            ElementId("$prefix.${next++}"),
            style = ElementStyle(stroke, fill, FillStyle.Solid, THIN, if (dashed) StrokeStyle.Dashed else StrokeStyle.Solid, 0),
        )

        fun rect(x: Double, y: Double, w: Double, h: Double, fill: String = "transparent") {
            elements += RectElement(base(fill = fill), unit(x), unit(y), unit(w), unit(h))
        }

        fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double) {
            elements += EllipseElement(base(), unit(cx), unit(cy), unit(rx), unit(ry))
        }

        fun line(vararg pts: Pair<Double, Double>, closed: Boolean = false, dashed: Boolean = false, stroke: String = INK) {
            elements += LineElement(base(stroke, dashed = dashed), pts.map { ScenePoint(unit(it.first), unit(it.second)) }, polygon = closed)
        }

        fun arc(cx: Double, cy: Double, r: Double, from: Double, to: Double) {
            elements += ArcElement(base(GUIDE, dashed = true), unit(cx), unit(cy), unit(r), from, to)
        }

        fun arrow(vararg pts: Pair<Double, Double>) {
            elements += ArrowElement(base(), pts.map { ScenePoint(unit(it.first), unit(it.second)) }, endArrowhead = Arrowhead.Arrow)
        }

        fun text(x: Double, y: Double, text: String, size: Double = PLAN_FONT) {
            val w = text.length * CHAR_WIDTH * size
            val h = size * LINE_HEIGHT
            elements += TextElement(
                base(), unit(x), unit(y), unit(w), unit(h), text = text, fontSize = size, fontFamily = FONT_FAMILY_NORMAL,
                textAlign = TextAlign.Center, verticalAlign = VerticalAlign.Middle,
            )
        }
    }

    private class Symbol(val slug: String, val name: String, val draw: Draw.() -> Unit)

    private val symbols: List<Symbol> = listOf(
        Symbol("door", "Door swing, 3 ft") {
            val w = 36 * INCH
            line(0.0 to 0.0, w to 0.0, dashed = true, stroke = GUIDE)
            line(0.0 to 0.0, 0.0 to -w)
            arc(0.0, 0.0, w, -PI / 2, 0.0)
        },
        Symbol("window", "Window, 3 ft") {
            val w = 36 * INCH
            val t = 4.5 * INCH
            rect(0.0, 0.0, w, t)
            line(0.0 to t / 2, w to t / 2)
        },
        Symbol("stairs", "Stairs, 3 ft wide, 10 treads") {
            val w = 36 * INCH
            val tread = 10 * INCH
            rect(0.0, 0.0, w, 10 * tread)
            for (i in 1 until 10) line(0.0 to i * tread, w to i * tread)
            arrow(w / 2 to 10 * tread - tread / 2, w / 2 to tread / 2)
        },
        Symbol("toilet", "Toilet") {
            rect(0.0, 0.0, 20 * INCH, 8 * INCH)
            ellipse(10 * INCH, 8 * INCH + 10 * INCH, 7.5 * INCH, 10 * INCH)
        },
        Symbol("sink", "Sink, 20 in vanity") {
            rect(0.0, 0.0, 20 * INCH, 18 * INCH)
            ellipse(10 * INCH, 9.5 * INCH, 7 * INCH, 5.5 * INCH)
            ellipse(10 * INCH, 2.5 * INCH, 1 * INCH, 1 * INCH)
        },
        Symbol("range", "Range, 30 in") {
            val w = 30 * INCH
            rect(0.0, 0.0, w, 26 * INCH)
            for (cx in listOf(w / 4, 3 * w / 4)) for (cy in listOf(6.5 * INCH, 19.5 * INCH)) ellipse(cx, cy, 4 * INCH, 4 * INCH)
        },
        Symbol("refrigerator", "Refrigerator, 36 in") {
            rect(0.0, 0.0, 36 * INCH, 30 * INCH)
            line(0.0 to 8 * INCH, 36 * INCH to 8 * INCH)
        },
        Symbol("bed", "Bed, queen") {
            val w = 60 * INCH
            val l = 80 * INCH
            rect(0.0, 0.0, w, l)
            rect(4 * INCH, 4 * INCH, 24 * INCH, 14 * INCH)
            rect(32 * INCH, 4 * INCH, 24 * INCH, 14 * INCH)
            line(0.0 to 24 * INCH, w to 24 * INCH)
        },
        Symbol("table", "Table, 5 ft by 3 ft") {
            rect(0.0, 0.0, 60 * INCH, 36 * INCH)
        },
        Symbol("table-round", "Table, round 4 ft") {
            ellipse(24 * INCH, 24 * INCH, 24 * INCH, 24 * INCH)
        },
        Symbol("chair", "Chair, 18 in") {
            rect(0.0, 0.0, 18 * INCH, 18 * INCH)
            line(0.0 to 2 * INCH, 18 * INCH to 2 * INCH)
        },
        Symbol("north", "North arrow") {
            val r = 12 * INCH
            ellipse(r, r, r, r)
            line(r to 0.0, r + 5 * INCH to r + 6 * INCH, r to r + 3 * INCH, r - 5 * INCH to r + 6 * INCH, closed = true)
            text(r - 3 * INCH, -PLAN_FONT * LINE_HEIGHT - INCH, "N")
        },
        Symbol("callout", "Callout") {
            arrow(0.0 to 0.0, 14 * INCH to -14 * INCH)
            text(14 * INCH, -14 * INCH - PLAN_FONT * LINE_HEIGHT, "Note")
        },
    )

    /** The symbol items, ids `starter.<slug>`, names as shown in the library. */
    val items: List<LibraryItem> by lazy {
        symbols.map { s ->
            val draw = Draw("$ID_PREFIX${s.slug}").apply(s.draw)
            LibraryItem("$ID_PREFIX${s.slug}", s.name, CREATED, draw.elements, 0L)
        }
    }

    /** The symbols as a library (sizes measured through the sheet encoding, like any item); ids are the item ids, so [ElementLibrary.insert] takes them. */
    val library: ElementLibrary by lazy { ElementLibrary.empty().merge(items).library }

    fun isStarter(itemId: String): Boolean = itemId.startsWith(ID_PREFIX)
}
