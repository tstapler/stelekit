package dev.stapler.stelekit.asset.pipeline

/** Stored `ocr_text` is capped here so one 5 MB PDF cannot bloat the row or the search index. */
const val MAX_OCR_TEXT_BYTES: Int = 64 * 1024

private const val UTF8_1_BYTE_MAX = 0x7F
private const val UTF8_2_BYTE_MAX = 0x7FF
private const val UTF8_3_BYTES = 3
private const val UTF8_4_BYTES = 4
private const val SUPPLEMENTARY_START = 0x10000

/**
 * [text] cut to at most [maxBytes] of UTF-8, never inside a code point (a surrogate pair stays
 * whole or goes entirely). Returns [text] itself when it already fits.
 */
internal fun truncateUtf8(text: String, maxBytes: Int = MAX_OCR_TEXT_BYTES): String {
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAtCompat(i)
        val width = when {
            cp <= UTF8_1_BYTE_MAX -> 1
            cp <= UTF8_2_BYTE_MAX -> 2
            cp < SUPPLEMENTARY_START -> UTF8_3_BYTES
            else -> UTF8_4_BYTES
        }
        if (bytes + width > maxBytes) return text.substring(0, i)
        bytes += width
        i += if (cp >= SUPPLEMENTARY_START) 2 else 1
    }
    return text
}

private fun String.codePointAtCompat(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        return SUPPLEMENTARY_START + ((high.code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00)
    }
    return high.code
}
