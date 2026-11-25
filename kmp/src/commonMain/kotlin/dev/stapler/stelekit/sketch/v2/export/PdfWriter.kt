// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.export

import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.PathSegment
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/** The three PDF Base-14 faces the writer uses, so no font is embedded: the document is small and prints the same everywhere. */
enum class PdfFace(val baseFont: String, val resource: String) {
    Helvetica("Helvetica", "F1"),
    HelveticaOblique("Helvetica-Oblique", "F2"),
    Courier("Courier", "F3"),
}

/**
 * Advance widths of the Base-14 faces (Adobe AFM, 1000 units to the em) for printable ASCII; any other character counts as
 * an average-width glyph. The layout (centred text, truncated notes) and the PDF both measure with this table, so what the
 * page was laid out for is what prints.
 */
object PdfFonts {
    private const val FIRST_CHAR = 32
    private const val EM = 1000.0
    private const val AVERAGE = 556
    private const val COURIER_ADVANCE = 600
    private const val FAMILY_HAND = 1
    private const val FAMILY_CODE = 3

    private val HELVETICA = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
    )

    /** The number of ASCII glyphs the table covers (32 to 126). */
    val tableSize: Int get() = HELVETICA.size

    fun faceOf(family: Int): PdfFace = when (family) {
        FAMILY_HAND -> PdfFace.HelveticaOblique
        FAMILY_CODE -> PdfFace.Courier
        else -> PdfFace.Helvetica
    }

    fun advance(c: Char, face: PdfFace): Int = when {
        face == PdfFace.Courier -> COURIER_ADVANCE
        c.code - FIRST_CHAR in HELVETICA.indices && c.code >= FIRST_CHAR -> HELVETICA[c.code - FIRST_CHAR]
        else -> AVERAGE
    }

    /** The width of [text] at [size], in the same unit as [size]. */
    fun width(text: String, size: Double, face: PdfFace = PdfFace.Helvetica): Double = text.sumOf { advance(it, face) } * size / EM

    /** [text] cut to fit [maxWidth] at [size], ending in `...` when something was cut. */
    fun fit(text: String, size: Double, maxWidth: Double, face: PdfFace = PdfFace.Helvetica): String {
        if (width(text, size, face) <= maxWidth) return text
        var end = text.length
        while (end > 0 && width(text.substring(0, end) + ELLIPSIS, size, face) > maxWidth) end--
        return text.substring(0, end).trimEnd() + ELLIPSIS
    }

    private const val ELLIPSIS = "..."
}

/** What the PDF's document information says. No date is written, so the same sheet always gives the same bytes. */
data class PdfInfo(val title: String = "", val subject: String = "")

/**
 * A minimal PDF 1.4 writer over the draw list (E7, ADR-007: no new dependency). Every page is vector: paths with their stroke,
 * fill, dash and opacity, clips, and text in a Base-14 font. Page coordinates are the sheet's page units (1/16 inch, y down);
 * one `cm` maps them to PDF points, so a path is written with the very numbers the SVG has. Images are not embedded (the
 * underlay is a tracing aid): an image is drawn as the dashed placeholder the SVG writes for a missing file. Streams are not
 * compressed (no zlib in common code), the objects are numbered and ordered the same way every time and nothing time-dependent
 * is written, so the output is byte-for-byte deterministic. Text outside Latin-1 and the WinAnsi punctuation prints as `?`.
 */
object PdfWriter {
    private const val POINTS_PER_PAGE_UNIT = 4.5
    private const val FIXED_OBJECTS = 7
    private const val OBJECTS_PER_PAGE = 2
    private const val FONT_FIRST = 4
    private const val RESOURCES = 7
    private const val BASELINE_RATIO = 0.8
    private const val OPACITY_STEPS = 1000.0
    private const val BYTE_MAX = 255
    private const val OCTAL = 8
    private const val HEX_RADIX = 16
    private const val SHORT_HEX = 3
    private const val LONG_HEX = 6
    private const val FULL_HEX = 8
    private const val HEX_DIGIT_MAX = 15.0
    private const val HEX_BYTE_MAX = 255.0
    private const val ASCII_PRINTABLE_FROM = 32
    private const val ASCII_PRINTABLE_TO = 126
    private const val LATIN1_FROM = 160
    private const val PLACEHOLDER_GREY = "0.525 0.557 0.588"
    private const val PLACEHOLDER_DASH = "4 4"

    /** Unicode characters outside Latin-1 that WinAnsi has, mapped to their code. */
    private val WIN_ANSI = mapOf(
        0x20AC to 0x80, 0x2026 to 0x85, 0x2018 to 0x91, 0x2019 to 0x92, 0x201C to 0x93, 0x201D to 0x94,
        0x2022 to 0x95, 0x2013 to 0x96, 0x2014 to 0x97, 0x2032 to 0x27, 0x2033 to 0x22,
    )

    fun write(pages: List<List<DrawOp>>, widthUnits: Double, heightUnits: Double, background: String?, info: PdfInfo = PdfInfo()): ByteArray {
        val opacities = LinkedHashMap<Int, Int>()
        val streams = pages.map { ops ->
            val s = Stream(opacities, widthUnits, heightUnits)
            background?.let { s.background(it) }
            s.ops(ops)
            s.text()
        }
        val out = StringBuilder()
        val offsets = ArrayList<Int>()
        fun obj(body: String) {
            offsets += out.length
            out.append(offsets.size).append(" 0 obj\n").append(body).append("\nendobj\n")
        }
        out.append("%PDF-1.4\n%âãÏÓ\n")
        val firstPage = FIXED_OBJECTS + opacities.size + 1
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [" + pages.indices.joinToString(" ") { "${firstPage + it * OBJECTS_PER_PAGE} 0 R" } + "] /Count ${pages.size} >>")
        obj("<< /Title (${escape(info.title)}) /Subject (${escape(info.subject)}) /Producer (SteleKit) >>")
        for (face in PdfFace.entries) obj("<< /Type /Font /Subtype /Type1 /BaseFont /${face.baseFont} /Encoding /WinAnsiEncoding >>")
        val fonts = PdfFace.entries.joinToString(" ") { "/${it.resource} ${FONT_FIRST + it.ordinal} 0 R" }
        val states = opacities.entries.joinToString(" ") { (_, n) -> "/GS$n ${FIXED_OBJECTS + n} 0 R" }
        obj("<< /Font << $fonts >> /ExtGState << $states >> >>")
        for ((step, _) in opacities) {
            val a = num(step / OPACITY_STEPS)
            obj("<< /Type /ExtGState /ca $a /CA $a >>")
        }
        val w = num(widthUnits * POINTS_PER_PAGE_UNIT)
        val h = num(heightUnits * POINTS_PER_PAGE_UNIT)
        streams.forEachIndexed { i, content ->
            val contentObject = firstPage + i * OBJECTS_PER_PAGE + 1
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] /Resources $RESOURCES 0 R /Contents $contentObject 0 R >>")
            obj("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        }
        val xref = out.length
        out.append("xref\n0 ").append(offsets.size + 1).append("\n0000000000 65535 f \n")
        for (o in offsets) out.append(o.toString().padStart(XREF_WIDTH, '0')).append(" 00000 n \n")
        out.append("trailer\n<< /Size ").append(offsets.size + 1).append(" /Root 1 0 R /Info 3 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n")
        return ByteArray(out.length) { out[it].code.toByte() }
    }

    private const val XREF_WIDTH = 10

    private fun num(v: Double): String = SvgExporter.num(v)

    /** A PDF literal string: ASCII as is, brackets and backslash escaped, everything else as an octal byte in WinAnsi or `?`. */
    private fun escape(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            val code = c.code
            when {
                c == '(' || c == ')' || c == '\\' -> sb.append('\\').append(c)
                code in ASCII_PRINTABLE_FROM..ASCII_PRINTABLE_TO -> sb.append(c)
                code in LATIN1_FROM..BYTE_MAX -> sb.append('\\').append(code.toString(OCTAL).padStart(OCTAL_WIDTH, '0'))
                WIN_ANSI.containsKey(code) -> escapeCode(WIN_ANSI.getValue(code), sb)
                else -> sb.append('?')
            }
        }
        return sb.toString()
    }

    private const val OCTAL_WIDTH = 3

    private fun escapeCode(code: Int, sb: StringBuilder) {
        if (code in ASCII_PRINTABLE_FROM..ASCII_PRINTABLE_TO) {
            val c = code.toChar()
            if (c == '(' || c == ')' || c == '\\') sb.append('\\')
            sb.append(c)
        } else {
            sb.append('\\').append(code.toString(OCTAL).padStart(OCTAL_WIDTH, '0'))
        }
    }

    /** `r g b` in 0..1 and an alpha, from a CSS `#rgb`, `#rrggbb` or `#rrggbbaa`; null for `none`, `transparent` or anything unreadable. */
    private fun colour(css: String): Rgba? {
        val t = css.trim().lowercase()
        if (!t.startsWith("#")) return null
        val hex = t.substring(1)
        if (!hex.all { it.isDigit() || it in 'a'..'f' }) return null
        return when (hex.length) {
            SHORT_HEX -> Rgba(hex.map { it.digitToInt(HEX_RADIX) / HEX_DIGIT_MAX }, 1.0)
            LONG_HEX -> Rgba(bytes(hex), 1.0)
            FULL_HEX -> Rgba(bytes(hex.substring(0, LONG_HEX)), hex.substring(LONG_HEX).toInt(HEX_RADIX) / HEX_BYTE_MAX)
            else -> null
        }
    }

    private fun bytes(hex: String): List<Double> = hex.chunked(2).map { it.toInt(HEX_RADIX) / HEX_BYTE_MAX }

    private class Rgba(val rgb: List<Double>, val alpha: Double) {
        fun text(): String = rgb.joinToString(" ") { num(it) }
    }

    private class Stream(private val opacities: MutableMap<Int, Int>, private val width: Double, private val height: Double) {
        private val sb = StringBuilder()

        init {
            sb.append("1 J 1 j\nq ").append(num(POINTS_PER_PAGE_UNIT)).append(" 0 0 ").append(num(-POINTS_PER_PAGE_UNIT)).append(" 0 ")
                .append(num(height * POINTS_PER_PAGE_UNIT)).append(" cm\n")
        }

        fun text(): String = sb.toString() + "Q"

        fun background(css: String) {
            val c = colour(css) ?: return
            sb.append(c.text()).append(" rg 0 0 ").append(num(width)).append(' ').append(num(height)).append(" re f\n")
        }

        private fun state(opacity: Double): String? {
            val step = round(opacity.coerceIn(0.0, 1.0) * OPACITY_STEPS).toInt()
            if (step == OPACITY_STEPS.toInt()) return null
            return "/GS${opacities.getOrPut(step) { opacities.size + 1 }} gs"
        }

        private inline fun withOpacity(opacity: Double, body: () -> Unit) {
            val gs = state(opacity)
            if (gs == null) return body()
            sb.append("q ").append(gs).append('\n')
            body()
            sb.append("Q\n")
        }

        fun ops(list: List<DrawOp>) {
            for (op in list) when (op) {
                is DrawOp.PathOp -> path(op)
                is DrawOp.TextRun -> text(op)
                is DrawOp.ImageOp -> placeholder(op)
                is DrawOp.ClipOp -> {
                    sb.append("q ").append(rect(op.rect.x, op.rect.y, op.rect.width, op.rect.height)).append(" W n\n")
                    ops(op.ops)
                    sb.append("Q\n")
                }
            }
        }

        private fun rect(x: Double, y: Double, w: Double, h: Double) = "${num(x)} ${num(y)} ${num(w)} ${num(h)} re"

        private fun path(op: DrawOp.PathOp) {
            if (op.segments.isEmpty()) return
            val fill = op.fill?.let(::colour)
            val stroke = op.stroke
            val strokeColour = stroke?.let { colour(it.color) }
            if (fill == null && strokeColour == null) return
            withOpacity(op.opacity * (fill?.alpha ?: strokeColour?.alpha ?: 1.0)) {
                fill?.let { sb.append(it.text()).append(" rg\n") }
                if (stroke != null && strokeColour != null) {
                    sb.append(strokeColour.text()).append(" RG ").append(num(stroke.width)).append(" w ")
                    sb.append(if (stroke.dash.isEmpty()) "[] 0 d\n" else "[" + stroke.dash.joinToString(" ") { num(it) } + "] 0 d\n")
                }
                for (s in op.segments) {
                    when (s) {
                        is PathSegment.MoveTo -> sb.append(num(s.x)).append(' ').append(num(s.y)).append(" m\n")
                        is PathSegment.LineTo -> sb.append(num(s.x)).append(' ').append(num(s.y)).append(" l\n")
                        is PathSegment.CubicTo -> sb.append(num(s.c1x)).append(' ').append(num(s.c1y)).append(' ').append(num(s.c2x)).append(' ')
                            .append(num(s.c2y)).append(' ').append(num(s.x)).append(' ').append(num(s.y)).append(" c\n")
                        PathSegment.Close -> sb.append("h\n")
                    }
                }
                sb.append(
                    when {
                        fill != null && strokeColour != null -> "B\n"
                        fill != null -> "f\n"
                        else -> "S\n"
                    },
                )
            }
        }

        private fun text(op: DrawOp.TextRun) {
            val colour = colour(op.color) ?: return
            val face = PdfFonts.faceOf(op.fontFamily)
            withOpacity(op.opacity * colour.alpha) {
                for (line in op.lines) {
                    val w = PdfFonts.width(line.text, op.fontSize, face)
                    val x = when (op.align) {
                        TextAlign.Left -> line.x
                        TextAlign.Center -> line.x - w / 2
                        TextAlign.Right -> line.x - w
                    }
                    val y = line.y + op.fontSize * BASELINE_RATIO
                    val turn = op.angle
                    val cosA = if (turn == 0.0) 1.0 else cos(turn)
                    val sinA = if (turn == 0.0) 0.0 else sin(turn)
                    val dx = x - op.pivotX
                    val dy = y - op.pivotY
                    val px = op.pivotX + dx * cosA - dy * sinA
                    val py = op.pivotY + dx * sinA + dy * cosA
                    sb.append("BT /").append(face.resource).append(' ').append(num(op.fontSize)).append(" Tf ").append(colour.text()).append(" rg ")
                        .append(num(cosA)).append(' ').append(num(sinA)).append(' ').append(num(sinA)).append(' ').append(num(-cosA)).append(' ')
                        .append(num(px)).append(' ').append(num(py)).append(" Tm (").append(escape(line.text)).append(") Tj ET\n")
                }
            }
        }

        private fun placeholder(op: DrawOp.ImageOp) {
            val r = op.rect
            sb.append("q ").append(PLACEHOLDER_GREY).append(" RG 1 w [").append(PLACEHOLDER_DASH).append("] 0 d ").append(rect(r.x, r.y, r.width, r.height)).append(" S Q\n")
        }
    }

}
