// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.export

import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.DrawRect
import dev.stapler.stelekit.sketch.v2.render.PathSegment
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import kotlin.math.PI
import kotlin.math.round

/** An image's stored bytes and declared type; what [SvgImageSource] hands the exporter. */
class SvgImage(val mime: String, val bytes: ByteArray)

/** Looks up the stored bytes of an image element's `fileId`; null when the file is missing. */
fun interface SvgImageSource {
    fun load(fileId: String): SvgImage?

    companion object {
        val None = SvgImageSource { null }
    }
}

/** [background] is a CSS colour or null for a transparent export; [bounds] overrides the scene bounds (a frame crop). */
data class SvgExportOptions(
    val background: String? = null,
    val padding: Double = DEFAULT_PADDING,
    val bounds: Bounds? = null,
) {
    companion object {
        const val DEFAULT_PADDING = 16.0
    }
}

/**
 * SVG writer over the draw list (Story 5.2.1, ADR-004): every kind the builder emits is a path, a text run, an image or a
 * clip group, so Clean and Sketchy scenes export the same way, with the geometry the editor shows. Pure: bytes of
 * images come in through [SvgImageSource]; the output depends only on its inputs. Numbers are printed by the engine's
 * own shortest-digit printer (wasmJs `Double.toString` is not shortest), rounded to four decimals.
 *
 * Output is XML-safe: text and attribute values are escaped, characters XML 1.0 forbids are stripped, an image whose
 * mime or leading bytes say SVG is never embedded (it could carry script) and is replaced by a placeholder rectangle,
 * and so is an image whose file is missing. The existing `SketchSvgWriter` stays the exporter of v1 sheets.
 */
object SvgExporter {
    /** Exports [scene] as drawn in [style]; the viewBox is the scene's bounds plus the padding. */
    fun export(
        scene: SketchSceneV2,
        builder: DrawListBuilder,
        style: RenderStyle,
        images: SvgImageSource = SvgImageSource.None,
        options: SvgExportOptions = SvgExportOptions(),
    ): String {
        val ops = builder.build(scene, style)
        val bounds = options.bounds ?: (scene.elementsInOrder.map { ElementBounds.of(it) } + textExtents(ops)).reduceOrNull(::union) ?: Bounds(0.0, 0.0, 0.0, 0.0)
        return write(ops, bounds, images, options)
    }

    /** Exports an already-built draw list inside [bounds]. */
    fun write(ops: List<DrawOp>, bounds: Bounds, images: SvgImageSource = SvgImageSource.None, options: SvgExportOptions = SvgExportOptions()): String {
        val w = Writer(images)
        w.ops(ops)
        val pad = options.padding
        val minX = bounds.minX - pad
        val minY = bounds.minY - pad
        val width = bounds.maxX - bounds.minX + pad * 2
        val height = bounds.maxY - bounds.minY + pad * 2
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" version=\"1.1\"")
        sb.append(" width=\"").append(num(width)).append("\" height=\"").append(num(height)).append('"')
        sb.append(" viewBox=\"").append(num(minX)).append(' ').append(num(minY)).append(' ').append(num(width)).append(' ').append(num(height)).append("\">\n")
        if (w.clips.isNotEmpty()) {
            sb.append("<defs>")
            for ((rect, id) in w.clips) {
                sb.append("<clipPath id=\"").append(id).append("\"><rect x=\"").append(num(rect.x)).append("\" y=\"").append(num(rect.y))
                    .append("\" width=\"").append(num(rect.width)).append("\" height=\"").append(num(rect.height)).append("\"/></clipPath>")
            }
            sb.append("</defs>\n")
        }
        options.background?.let {
            sb.append("<rect x=\"").append(num(minX)).append("\" y=\"").append(num(minY)).append("\" width=\"").append(num(width))
                .append("\" height=\"").append(num(height)).append("\" fill=\"").append(attr(it)).append("\"/>\n")
        }
        sb.append(w.body).append("</svg>\n")
        return sb.toString()
    }

    /** Where text lines start: a frame's title sits outside the frame's own box, so the scene bounds alone would clip it. */
    private fun textExtents(ops: List<DrawOp>): List<Bounds> = ops.flatMap { op ->
        when (op) {
            is DrawOp.TextRun -> op.lines.map { Bounds(it.x, it.y, it.x, it.y + op.fontSize * LINE_HEIGHT_RATIO) }
            is DrawOp.ClipOp -> textExtents(op.ops)
            else -> emptyList()
        }
    }

    private fun union(a: Bounds, b: Bounds) = Bounds(minOf(a.minX, b.minX), minOf(a.minY, b.minY), maxOf(a.maxX, b.maxX), maxOf(a.maxY, b.maxY))

    private class Writer(private val images: SvgImageSource) {
        val body = StringBuilder()
        val clips = LinkedHashMap<DrawRect, String>()

        fun ops(list: List<DrawOp>) {
            for (op in list) when (op) {
                is DrawOp.PathOp -> path(op)
                is DrawOp.TextRun -> text(op)
                is DrawOp.ImageOp -> image(op)
                is DrawOp.ClipOp -> {
                    val id = clips.getOrPut(op.rect) { "clip${clips.size}" }
                    body.append("<g clip-path=\"url(#").append(id).append(")\">\n")
                    ops(op.ops)
                    body.append("</g>\n")
                }
            }
        }

        private fun path(op: DrawOp.PathOp) {
            body.append("<path d=\"").append(d(op.segments)).append('"')
            body.append(" fill=\"").append(op.fill?.let(::attr) ?: NONE).append('"')
            val stroke = op.stroke
            if (stroke == null) {
                body.append(" stroke=\"none\"")
            } else {
                body.append(" stroke=\"").append(attr(stroke.color)).append("\" stroke-width=\"").append(num(stroke.width)).append('"')
                if (stroke.dash.isNotEmpty()) body.append(" stroke-dasharray=\"").append(stroke.dash.joinToString(" ") { num(it) }).append('"')
                body.append(" stroke-linecap=\"round\" stroke-linejoin=\"round\"")
            }
            opacity(op.opacity)
            body.append("/>\n")
        }

        private fun d(segments: List<PathSegment>): String = segments.joinToString(" ") { s ->
            when (s) {
                is PathSegment.MoveTo -> "M${num(s.x)} ${num(s.y)}"
                is PathSegment.LineTo -> "L${num(s.x)} ${num(s.y)}"
                is PathSegment.CubicTo -> "C${num(s.c1x)} ${num(s.c1y)} ${num(s.c2x)} ${num(s.c2y)} ${num(s.x)} ${num(s.y)}"
                PathSegment.Close -> "Z"
            }
        }

        private fun opacity(value: Double) {
            if (value != 1.0) body.append(" opacity=\"").append(num(value)).append('"')
        }

        private fun text(op: DrawOp.TextRun) {
            val anchor = when (op.align) {
                TextAlign.Left -> "start"
                TextAlign.Center -> "middle"
                TextAlign.Right -> "end"
            }
            body.append("<text font-family=\"").append(attr(fontFamily(op.fontFamily, op.usesFallbackFont))).append("\" font-size=\"").append(num(op.fontSize))
                .append("\" fill=\"").append(attr(op.color)).append("\" text-anchor=\"").append(anchor).append("\" xml:space=\"preserve\"")
            if (op.angle != 0.0) {
                body.append(" transform=\"rotate(").append(num(op.angle * DEGREES_PER_RADIAN / PI)).append(' ').append(num(op.pivotX)).append(' ').append(num(op.pivotY)).append(")\"")
            }
            opacity(op.opacity)
            body.append(">")
            for (line in op.lines) {
                body.append("<tspan x=\"").append(num(line.x)).append("\" y=\"").append(num(line.y + op.fontSize * BASELINE_RATIO)).append("\">")
                    .append(escape(line.text)).append("</tspan>")
            }
            body.append("</text>\n")
        }

        private fun image(op: DrawOp.ImageOp) {
            val file = op.fileId?.let { images.load(it) }
            val r = op.rect
            if (file == null || isSvg(file)) {
                body.append("<rect x=\"").append(num(r.x)).append("\" y=\"").append(num(r.y)).append("\" width=\"").append(num(r.width))
                    .append("\" height=\"").append(num(r.height)).append("\" fill=\"none\" stroke=\"#868e96\" stroke-dasharray=\"4 4\"")
                op.fileId?.let { body.append(" data-file-id=\"").append(attr(it)).append('"') }
                body.append("/>\n")
                return
            }
            val href = "data:${attr(file.mime)};base64,${Base64Text.encode(file.bytes)}"
            body.append("<g")
            val transform = transformOf(op)
            if (transform.isNotEmpty()) body.append(" transform=\"").append(transform).append('"')
            opacity(op.opacity)
            body.append(">")
            val crop = op.crop
            if (crop == null) {
                body.append("<image x=\"").append(num(r.x)).append("\" y=\"").append(num(r.y)).append("\" width=\"").append(num(r.width))
                    .append("\" height=\"").append(num(r.height)).append("\" preserveAspectRatio=\"none\" href=\"").append(href).append("\"/>")
            } else {
                body.append("<svg x=\"").append(num(r.x)).append("\" y=\"").append(num(r.y)).append("\" width=\"").append(num(r.width))
                    .append("\" height=\"").append(num(r.height)).append("\" viewBox=\"").append(num(crop.x)).append(' ').append(num(crop.y)).append(' ')
                    .append(num(crop.width)).append(' ').append(num(crop.height)).append("\" preserveAspectRatio=\"none\"><image width=\"")
                    .append(num(crop.naturalWidth)).append("\" height=\"").append(num(crop.naturalHeight)).append("\" href=\"").append(href).append("\"/></svg>")
            }
            body.append("</g>\n")
        }

        private fun transformOf(op: DrawOp.ImageOp): String {
            val cx = op.rect.x + op.rect.width / 2
            val cy = op.rect.y + op.rect.height / 2
            val parts = ArrayList<String>()
            if (op.angle != 0.0) parts += "rotate(${num(op.angle * DEGREES_PER_RADIAN / PI)} ${num(cx)} ${num(cy)})"
            if (op.flipX || op.flipY) {
                val sx = if (op.flipX) -1 else 1
                val sy = if (op.flipY) -1 else 1
                parts += "translate(${num(cx)} ${num(cy)}) scale($sx $sy) translate(${num(-cx)} ${num(-cy)})"
            }
            return parts.joinToString(" ")
        }

        private fun isSvg(file: SvgImage): Boolean {
            if (file.mime.lowercase().contains("svg")) return true
            val head = file.bytes.take(SNIFF_BYTES).map { (it.toInt() and BYTE_MASK).toChar() }.joinToString("").trimStart().lowercase()
            return head.startsWith("<svg") || head.startsWith("<?xml") || head.startsWith("<!doctype svg")
        }

        private fun fontFamily(family: Int, fallback: Boolean): String {
            val named = when (family) {
                FAMILY_HAND -> "Virgil, Segoe UI Emoji"
                FAMILY_CODE -> "Cascadia, Segoe UI Emoji"
                else -> "Helvetica, Segoe UI Emoji"
            }
            return if (fallback) "$named, sans-serif" else named
        }
    }

    private const val FAMILY_HAND = 1
    private const val FAMILY_CODE = 3
    private const val NONE = "none"
    private const val DEGREES_PER_RADIAN = 180.0
    private const val BASELINE_RATIO = 0.8
    private const val LINE_HEIGHT_RATIO = 1.25
    private const val DECIMAL_SCALE = 10_000.0
    private const val SNIFF_BYTES = 64
    private const val BYTE_MASK = 0xFF

    /** A number as the shortest round-trip digits after rounding to four decimals; never `NaN`, never `-0`. */
    internal fun num(v: Double): String {
        val rounded = round(v * DECIMAL_SCALE) / DECIMAL_SCALE + 0.0
        return CanonicalJson.formatDouble(if (rounded.isFinite()) rounded else 0.0).fold({ "0" }, { it })
    }

    /** XML attribute escape, with the characters XML 1.0 forbids removed. */
    internal fun attr(raw: String): String = escape(raw).replace("\"", "&quot;").replace("'", "&apos;")

    /** XML text escape: `&`, `<`, `>`; control characters other than tab, newline and return and lone surrogates are dropped. */
    internal fun escape(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            val paired = c.isHighSurrogate() && i + 1 < raw.length && raw[i + 1].isLowSurrogate()
            when {
                paired -> { sb.append(c).append(raw[i + 1]); i++ }
                c.isSurrogate() -> Unit
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                isXmlChar(c) -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun isXmlChar(c: Char): Boolean = c == '\t' || c == '\n' || c == '\r' || (c.code >= SPACE && c.code != NOT_A_CHAR_FFFE && c.code != NOT_A_CHAR_FFFF)

    private const val SPACE = 0x20
    private const val NOT_A_CHAR_FFFE = 0xFFFE
    private const val NOT_A_CHAR_FFFF = 0xFFFF
}

/** Standard base64 without line breaks; hand-written so the exporter has no platform or experimental-API dependency. */
internal object Base64Text {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val BYTE_MASK = 0xFF
    private const val SIX_BITS = 0x3F

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and BYTE_MASK
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and BYTE_MASK else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and BYTE_MASK else -1
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            sb.append(if (b1 >= 0) ALPHABET[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)] else '=')
            sb.append(if (b2 >= 0) ALPHABET[b2 and SIX_BITS] else '=')
            i += 3
        }
        return sb.toString()
    }
}
