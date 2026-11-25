// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.export

/**
 * A sheet's print setup (E7): the paper, the drawing scale and the page furniture. It is a sheet property, not model data: the
 * drawing in scene units (1/16 inch of real length) never changes with it. Everything here is exact integer arithmetic until the
 * page is laid out, so a stored setup means the same on every platform.
 */
enum class Orientation { Portrait, Landscape }

/** Paper in portrait, in whole micrometres (an inch is 25 400 um, so 8.5 in is exactly 215 900 um). [imperial] picks the unit its sizes are written in. */
data class PaperSize(val id: String, val widthMicrometers: Long, val heightMicrometers: Long, val imperial: Boolean) {
    init {
        require(widthMicrometers > 0 && heightMicrometers > 0) { "paper is larger than nothing" }
    }

    /** The width and height as placed: swapped for landscape. */
    fun width(orientation: Orientation): Long = if (orientation == Orientation.Portrait) widthMicrometers else heightMicrometers

    fun height(orientation: Orientation): Long = if (orientation == Orientation.Portrait) heightMicrometers else widthMicrometers

    /** `8.5 x 11 in` or `210 x 297 mm`. */
    val label: String
        get() = if (isPreset) id else sizeText()

    private val isPreset: Boolean get() = presets.any { it.id == id }

    /** The size as the stored id spells a custom paper: `300x200mm`. */
    fun sizeText(): String = "${Micrometers.format(widthMicrometers, imperial)}x${Micrometers.format(heightMicrometers, imperial)}${Micrometers.unit(imperial)}"

    companion object {
        val Letter = PaperSize("Letter", 215_900, 279_400, true)
        val A4 = PaperSize("A4", 210_000, 297_000, false)
        val A3 = PaperSize("A3", 297_000, 420_000, false)
        val Tabloid = PaperSize("Tabloid", 279_400, 431_800, true)
        val presets: List<PaperSize> = listOf(Letter, A4, A3, Tabloid)

        fun custom(widthMicrometers: Long, heightMicrometers: Long, imperial: Boolean): PaperSize {
            val spelled = PaperSize("", widthMicrometers, heightMicrometers, imperial).sizeText()
            return PaperSize(spelled, widthMicrometers, heightMicrometers, imperial)
        }

        /** A preset name (any case) or `WxHunit` (`210x297mm`, `8.5x11in`); null when it is neither. */
        fun parse(text: String): PaperSize? {
            val t = text.trim()
            presets.firstOrNull { it.id.equals(t, ignoreCase = true) }?.let { return it }
            val m = CUSTOM.matchEntire(t.lowercase()) ?: return null
            val imperial = m.groupValues[UNIT_GROUP] == "in"
            val w = Micrometers.parse(m.groupValues[WIDTH_GROUP], imperial) ?: return null
            val h = Micrometers.parse(m.groupValues[HEIGHT_GROUP], imperial) ?: return null
            return if (w > 0 && h > 0) custom(w, h, imperial) else null
        }

        private val CUSTOM = Regex("""([0-9]+(?:\.[0-9]+)?)\s*[x*]\s*([0-9]+(?:\.[0-9]+)?)\s*(mm|in)""")
        private const val WIDTH_GROUP = 1
        private const val HEIGHT_GROUP = 2
        private const val UNIT_GROUP = 3
    }
}

/** Lengths in whole micrometres, parsed and printed without floating point. */
object Micrometers {
    const val PER_MM = 1_000L
    const val PER_INCH = 25_400L
    private const val DECIMALS = 3
    private const val TEN = 10L
    private const val THOUSAND = 1_000L

    fun unit(imperial: Boolean): String = if (imperial) "in" else "mm"

    private fun perUnit(imperial: Boolean): Long = if (imperial) PER_INCH else PER_MM

    /** `12.7` in millimetres or `0.5` in inches as micrometres; null when [text] is not a plain decimal. Digits past the third are dropped. */
    fun parse(text: String, imperial: Boolean): Long? {
        val whole = text.substringBefore('.')
        val fraction = if (text.contains('.')) text.substringAfter('.') else ""
        if (whole.isEmpty() || !whole.all { it.isDigit() } || !fraction.all { it.isDigit() }) return null
        val digits = fraction.take(DECIMALS).padEnd(DECIMALS, '0')
        val thousandths = whole.toLongOrNull()?.times(THOUSAND)?.plus(digits.toLong()) ?: return null
        return thousandths * perUnit(imperial) / THOUSAND
    }

    /** [micrometres] as a decimal with at most three places and no trailing zeros (`0.5`, `12.7`, `210`). */
    fun format(micrometres: Long, imperial: Boolean): String {
        val thousandths = (micrometres * THOUSAND + perUnit(imperial) / 2) / perUnit(imperial)
        val whole = thousandths / THOUSAND
        val fraction = (thousandths % THOUSAND).toString().padStart(DECIMALS, '0').trimEnd('0')
        return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
    }

    /** `0.5in`, `12mm`: the spelling a stored margin uses. */
    fun withUnit(micrometres: Long, imperial: Boolean): String = format(micrometres, imperial) + unit(imperial)

    /** The reverse of [withUnit]; null when it is not a length in `in` or `mm`. */
    fun parseWithUnit(text: String): Long? {
        val t = text.trim().lowercase()
        return when {
            t.endsWith("mm") -> parse(t.removeSuffix("mm").trim(), false)
            t.endsWith("in") -> parse(t.removeSuffix("in").trim(), true)
            else -> null
        }
    }

    /** One inch in sixteenths: the sheet's page unit and the scene unit are both a sixteenth of an inch. */
    const val SIXTEENTHS_PER_INCH = 16L

    /** Page units (sixteenths of an inch) in [micrometres]; exact to a double's precision. */
    fun toPageUnits(micrometres: Long): Double = micrometres.toDouble() * SIXTEENTHS_PER_INCH / PER_INCH
}

/**
 * Drawing scale as paper length per real length: `1:48` draws a real foot as a quarter inch. [paper] and [real] are in lowest
 * terms, so equal scales are equal values. `2:1` enlarges.
 */
data class DrawingScale(val paper: Long, val real: Long) {
    init {
        require(paper > 0 && real > 0) { "a scale is a ratio of positive numbers" }
    }

    /** Page units per scene unit. */
    val factor: Double get() = paper.toDouble() / real

    /** The ratio as stored and as the SVG's `data-sketch-scale` writes it. */
    val ratio: String get() = "$paper:$real"

    /** What the dialog and the title block call it: `1/4" = 1'` for the architectural scales, the ratio otherwise. */
    val label: String get() = ScalePresets.architectural.firstOrNull { it.scale == this }?.label ?: ratio

    companion object {
        fun of(paper: Long, real: Long): DrawingScale {
            val g = gcd(paper, real)
            return DrawingScale(paper / g, real / g)
        }

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

        /** `1:50`, `2:1`, `1/4" = 1'` and `1/4in=1ft`; null when [text] is none of them. */
        fun parse(text: String): DrawingScale? {
            val t = text.trim().lowercase().replace("″", "\"").replace("′", "'")
            RATIO.matchEntire(t)?.let { m ->
                val a = m.groupValues[1].toLongOrNull() ?: return null
                val b = m.groupValues[2].toLongOrNull() ?: return null
                return if (a > 0 && b > 0) of(a, b) else null
            }
            val m = INCH_FOOT.matchEntire(t) ?: return null
            val inches = fraction(m.groupValues[1]) ?: return null
            val feet = m.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return if (inches.first > 0) of(inches.first, inches.second * feet * INCHES_PER_FOOT) else null
        }

        /** `1/4`, `1-1/2`, `3`, `0.25` as a numerator and a denominator. */
        private fun fraction(text: String): Pair<Long, Long>? {
            val parts = text.split('-', ' ').filter { it.isNotEmpty() }
            if (parts.size > 1) {
                val whole = parts[0].toLongOrNull() ?: return null
                val f = simple(parts[1]) ?: return null
                return (whole * f.second + f.first) to f.second
            }
            return parts.singleOrNull()?.let(::simple)
        }

        private fun simple(text: String): Pair<Long, Long>? {
            if (text.contains('/')) {
                val n = text.substringBefore('/').toLongOrNull() ?: return null
                val d = text.substringAfter('/').toLongOrNull()?.takeIf { it > 0 } ?: return null
                return n to d
            }
            val whole = text.substringBefore('.')
            val decimals = if (text.contains('.')) text.substringAfter('.') else ""
            if (whole.isEmpty() || !(whole + decimals).all { it.isDigit() }) return null
            var denominator = 1L
            repeat(decimals.length) { denominator *= TEN }
            return (whole + decimals).toLongOrNull()?.let { it to denominator }
        }

        private val RATIO = Regex("""([0-9]+)\s*:\s*([0-9]+)""")
        private val INCH_FOOT = Regex("""([0-9][0-9./ \-]*?)\s*(?:"|in|inch|inches)\s*=\s*([0-9]+)\s*(?:'|ft|foot|feet)""")
        private const val INCHES_PER_FOOT = 12L
        private const val TEN = 10L
    }
}

/** A named scale in the dialog's list. */
data class ScalePreset(val label: String, val scale: DrawingScale)

object ScalePresets {
    /** Imperial architectural scales, largest drawing first (the `3" = 1'` detail down to `1/8" = 1'`). */
    val architectural: List<ScalePreset> = listOf(
        ScalePreset("3\" = 1'", DrawingScale.of(3, 12)),
        ScalePreset("1 1/2\" = 1'", DrawingScale.of(3, 24)),
        ScalePreset("1\" = 1'", DrawingScale.of(1, 12)),
        ScalePreset("3/4\" = 1'", DrawingScale.of(3, 48)),
        ScalePreset("1/2\" = 1'", DrawingScale.of(1, 24)),
        ScalePreset("3/8\" = 1'", DrawingScale.of(3, 96)),
        ScalePreset("1/4\" = 1'", DrawingScale.of(1, 48)),
        ScalePreset("3/16\" = 1'", DrawingScale.of(3, 192)),
        ScalePreset("1/8\" = 1'", DrawingScale.of(1, 96)),
    )

    /** Metric ratios, largest drawing first. */
    val metric: List<ScalePreset> = listOf(
        ScalePreset("2:1", DrawingScale.of(2, 1)),
        ScalePreset("1:1", DrawingScale.of(1, 1)),
        ScalePreset("1:2", DrawingScale.of(1, 2)),
        ScalePreset("1:5", DrawingScale.of(1, 5)),
        ScalePreset("1:10", DrawingScale.of(1, 10)),
        ScalePreset("1:20", DrawingScale.of(1, 20)),
        ScalePreset("1:25", DrawingScale.of(1, 25)),
        ScalePreset("1:50", DrawingScale.of(1, 50)),
        ScalePreset("1:100", DrawingScale.of(1, 100)),
        ScalePreset("1:200", DrawingScale.of(1, 200)),
    )

    fun family(metric: Boolean): List<ScalePreset> = if (metric) ScalePresets.metric else architectural
}

/** The fields of the title block; any may be empty. */
data class TitleBlock(
    val title: String = "",
    val author: String = "",
    val date: String = "",
    val sheet: String = "",
    val notes: String = "",
)

/**
 * The print setup of one sheet. [margin] is micrometres on every side. [northDegrees] is the clockwise turn of the north arrow
 * from "up" (null: no north arrow). A null [titleBlock] draws no title block.
 */
data class SheetSetup(
    val paper: PaperSize = PaperSize.Letter,
    val orientation: Orientation = Orientation.Portrait,
    val margin: Long = DEFAULT_MARGIN_IMPERIAL,
    val scale: DrawingScale = DrawingScale.of(1, DEFAULT_SCALE_DENOMINATOR),
    val scaleBar: Boolean = true,
    val northDegrees: Int? = null,
    val titleBlock: TitleBlock? = null,
) {
    val widthUnits: Double get() = Micrometers.toPageUnits(paper.width(orientation))
    val heightUnits: Double get() = Micrometers.toPageUnits(paper.height(orientation))

    companion object {
        /** A half inch, or 10 mm for metric paper. */
        const val DEFAULT_MARGIN_IMPERIAL = 12_700L
        const val DEFAULT_MARGIN_METRIC = 10_000L
        const val DEFAULT_SCALE_DENOMINATOR = 48L

        /** The margins the dialog offers for [paper]: narrow, normal, wide. */
        fun margins(paper: PaperSize): List<Long> = if (paper.imperial) listOf(6_350L, 12_700L, 25_400L) else listOf(5_000L, 10_000L, 20_000L)

        fun defaultMargin(paper: PaperSize): Long = if (paper.imperial) DEFAULT_MARGIN_IMPERIAL else DEFAULT_MARGIN_METRIC
    }
}
