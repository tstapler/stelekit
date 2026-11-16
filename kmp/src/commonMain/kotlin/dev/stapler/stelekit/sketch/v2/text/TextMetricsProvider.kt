package dev.stapler.stelekit.sketch.v2.text

/** Font family id and size of a text run; the family ids follow Excalidraw's integers. */
data class TextStyle(val fontFamily: Int, val fontSize: Double)

enum class FontStatus { Loading, Loaded, Failed }

/**
 * The seam between the pure engine and a platform text measurer (Task 2.1.2b supplies the Compose adapter).
 * Layout must only ever use a bundled font: platform default fonts differ by up to 5 px across targets
 * (research/determinism-spike.md, 0.1.3b). Glyphs the bundled font lacks are measured with the platform
 * fallback, reported through [hasGlyph] so a layout can flag them.
 */
interface TextMetricsProvider {
    val fontStatus: FontStatus

    fun lineHeight(style: TextStyle): Double

    /** Width of [text], which contains no line break. */
    fun advance(text: String, style: TextStyle): Double

    /** Whether the bundled font has a glyph for [codePoint]. */
    fun hasGlyph(codePoint: Int, style: TextStyle): Boolean
}

internal inline fun forEachCodePoint(text: String, action: (Int) -> Unit) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            action(0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00))
            i += 2
        } else {
            action(c.code)
            i++
        }
    }
}
