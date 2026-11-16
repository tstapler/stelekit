package dev.stapler.stelekit.sketch.v2.text

/**
 * Deterministic provider for tests: every code point advances [advancePerCodePoint] units, or
 * [fallbackAdvancePerCodePoint] when [supportsGlyph] is false. Counts calls so tests can assert that a
 * render path never measures.
 */
class FixedAdvanceTextMetricsProvider(
    private val advancePerCodePoint: Double = 8.0,
    private val lineHeightUnits: Double = 20.0,
    override val fontStatus: FontStatus = FontStatus.Loaded,
    private val fallbackAdvancePerCodePoint: Double = advancePerCodePoint,
    private val supportsGlyph: (Int) -> Boolean = { true },
) : TextMetricsProvider {
    var measureCalls: Int = 0
        private set
    var fallbackMeasureCalls: Int = 0
        private set

    override fun lineHeight(style: TextStyle): Double = lineHeightUnits

    override fun advance(text: String, style: TextStyle): Double {
        measureCalls++
        var total = 0.0
        forEachScalar(text) { cp ->
            if (supportsGlyph(cp)) {
                total += advancePerCodePoint
            } else {
                fallbackMeasureCalls++
                total += fallbackAdvancePerCodePoint
            }
        }
        return total
    }

    override fun hasGlyph(codePoint: Int, style: TextStyle): Boolean = supportsGlyph(codePoint)
}

private inline fun forEachScalar(text: String, action: (Int) -> Unit) {
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
