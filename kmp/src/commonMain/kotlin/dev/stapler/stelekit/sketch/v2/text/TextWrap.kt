package dev.stapler.stelekit.sketch.v2.text

/** A measured, wrapped text block. [usedFallback] is true when some glyph is not in the bundled font. */
data class TextLayout(
    val lines: List<String>,
    val width: Double,
    val height: Double,
    val usedFallback: Boolean,
)

/** The size and line breaks persisted in the element; authoritative for rendering on every target. */
data class StoredTextLayout(val lines: List<String>, val width: Double, val height: Double)

sealed interface EditLayoutResult {
    data class Measured(val layout: TextLayout) : EditLayoutResult

    /** Nothing was measured, so nothing may be persisted. */
    data object FontNotLoaded : EditLayoutResult
}

/**
 * Wrap points are a write-time decision: render uses [forRender] (the stored layout, no measuring) and only an
 * edit calls [forEdit] and re-wraps, so another device never re-wraps a sheet it opens.
 */
object TextLayoutPolicy {
    fun forRender(stored: StoredTextLayout): StoredTextLayout = stored

    fun forEdit(text: String, maxWidth: Double?, style: TextStyle, provider: TextMetricsProvider): EditLayoutResult =
        if (provider.fontStatus != FontStatus.Loaded) {
            EditLayoutResult.FontNotLoaded
        } else {
            EditLayoutResult.Measured(TextWrap.layout(text, maxWidth, style, provider))
        }
}

object TextWrap {
    /**
     * Greedy word wrap. `\n` always breaks; [maxWidth] null means no wrapping. A word wider than [maxWidth] is
     * split between code points (never inside a surrogate pair). The space at a wrap point is dropped; every
     * other character is kept in order. Assumes a single glyph fits [maxWidth], else one code point per line.
     */
    fun layout(text: String, maxWidth: Double?, style: TextStyle, provider: TextMetricsProvider): TextLayout {
        val lines = text.split('\n').flatMap { paragraph ->
            if (maxWidth == null) listOf(paragraph) else wrapParagraph(paragraph, maxWidth, style, provider)
        }
        var usedFallback = false
        forEachCodePoint(text) { cp ->
            if (cp != '\n'.code && !provider.hasGlyph(cp, style)) usedFallback = true
        }
        val width = lines.maxOf { provider.advance(it, style) }
        return TextLayout(lines, width, lines.size * provider.lineHeight(style), usedFallback)
    }

    private fun wrapParagraph(paragraph: String, maxWidth: Double, style: TextStyle, provider: TextMetricsProvider): List<String> {
        val out = mutableListOf<String>()
        var current = ""
        for (word in paragraph.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (provider.advance(candidate, style) <= maxWidth) {
                current = candidate
                continue
            }
            if (current.isNotEmpty()) out.add(current)
            current = ""
            if (provider.advance(word, style) <= maxWidth) {
                current = word
            } else {
                val chunks = breakWord(word, maxWidth, style, provider)
                out.addAll(chunks.dropLast(1))
                current = chunks.last()
            }
        }
        out.add(current)
        return out
    }

    private fun breakWord(word: String, maxWidth: Double, style: TextStyle, provider: TextMetricsProvider): List<String> {
        val chunks = mutableListOf<String>()
        val line = StringBuilder()
        var i = 0
        while (i < word.length) {
            val step = if (word[i].isHighSurrogate() && i + 1 < word.length && word[i + 1].isLowSurrogate()) 2 else 1
            val piece = word.substring(i, i + step)
            if (line.isNotEmpty() && provider.advance(line.toString() + piece, style) > maxWidth) {
                chunks.add(line.toString())
                line.clear()
            }
            line.append(piece)
            i += step
        }
        chunks.add(line.toString())
        return chunks
    }
}
