// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.text

import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign

/** Bounds and steps of a user-chosen font size, in scene units. Imported files may hold sizes outside them; only a user change is clamped. */
object TextSizeLimits {
    const val MIN = 8.0
    const val MAX = 256.0

    /** The sizes the A- and A+ steps walk through; every entry is inside [MIN]..[MAX]. */
    val LADDER: List<Double> = listOf(8.0, 10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 24.0, 28.0, 32.0, 36.0, 48.0, 64.0, 96.0, 128.0, 192.0, 256.0)

    enum class Bound { Min, Max }

    /** A size inside [MIN]..[MAX]; [hit] names the bound it was pulled to, if any. */
    data class Clamped(val size: Double, val hit: Bound?)

    fun clamp(size: Double): Clamped = when {
        !size.isFinite() -> Clamped(TextElement.DEFAULT_FONT_SIZE, null)
        size < MIN -> Clamped(MIN, Bound.Min)
        size > MAX -> Clamped(MAX, Bound.Max)
        else -> Clamped(size, null)
    }

    /**
     * [current] changed to [next] by a continuous change (a drag or group scale): clamped only when it crosses a bound from
     * inside. A size already outside stays where it is if [next] goes further out, so an imported size never jumps.
     */
    fun clampChange(current: Double, next: Double): Clamped = when {
        !next.isFinite() -> Clamped(current, null)
        next > MAX && next > current -> if (current > MAX) Clamped(current, Bound.Max) else Clamped(MAX, Bound.Max)
        next < MIN && next < current -> if (current < MIN) Clamped(current, Bound.Min) else Clamped(MIN, Bound.Min)
        else -> Clamped(next, null)
    }

    /** The next ladder size above [current]; at or above the top it stays at [current] (or [MAX]) and reports the bound. */
    fun stepUp(current: Double): Clamped = LADDER.firstOrNull { it > current }?.let { Clamped(it, null) } ?: Clamped(maxOf(current, MAX), Bound.Max)

    /** The next ladder size below [current]; at or below the bottom it stays at [current] (or [MIN]) and reports the bound. */
    fun stepDown(current: Double): Clamped = LADDER.lastOrNull { it < current }?.let { Clamped(it, null) } ?: Clamped(minOf(current, MIN), Bound.Min)
}

/** The wrapped text and box of one fit. [text] has its line breaks in it, like `TextElement.text`. */
data class TextFit(val text: String, val width: Double, val height: Double)

/** A text element's new top-left corner and box after [TextAutoSize.refit]. */
data class TextRefit(val x: Double, val y: Double, val width: Double, val height: Double, val text: String)

/**
 * Sizing rules of a text box. An auto-size box (`autoResize`) is as wide as its longest line and as tall as its lines; a
 * fixed-width box wraps at its width and only its height follows the text. Heights use the element's own
 * `fontSize * lineHeight` per line, the same spacing `ElementOps.text` draws, so the box is the glyph bounds, not a guess.
 */
object TextAutoSize {
    /** [raw] set at [style] wrapped to [wrapWidth] (null: no wrapping); null when the bundled font is not loaded. */
    fun fit(raw: String, style: TextStyle, lineHeightRatio: Double, wrapWidth: Double?, provider: TextMetricsProvider): TextFit? {
        val layout = (TextLayoutPolicy.forEdit(raw, wrapWidth, style, provider) as? EditLayoutResult.Measured)?.layout ?: return null
        return TextFit(layout.lines.joinToString("\n"), wrapWidth ?: layout.width, layout.lines.size * style.fontSize * lineHeightRatio)
    }

    /**
     * [e] re-measured with [fontSize] and [fontFamily] (default: its own), keeping the point its alignment anchors: the left,
     * centre or right edge and the top, middle or bottom. Null when the font is not loaded, so nothing unmeasured is stored.
     * [wrapWidth] overrides the width a fixed-width box wraps at.
     */
    fun refit(
        e: TextElement,
        provider: TextMetricsProvider,
        fontSize: Double = e.fontSize,
        fontFamily: Int = e.fontFamily,
        wrapWidth: Double? = if (e.autoResize) null else e.width.value,
    ): TextRefit? {
        val fit = fit(e.originalText, TextStyle(fontFamily, fontSize), e.lineHeight, wrapWidth, provider) ?: return null
        return anchored(e, fit)
    }

    /** [fit] placed so [e]'s alignment anchor stays where it was. */
    fun anchored(e: TextElement, fit: TextFit): TextRefit {
        val x = when (e.textAlign) {
            TextAlign.Left -> e.x.value
            TextAlign.Center -> e.x.value + (e.width.value - fit.width) / 2.0
            TextAlign.Right -> e.x.value + e.width.value - fit.width
        }
        val y = when (e.verticalAlign) {
            VerticalAlign.Top -> e.y.value
            VerticalAlign.Middle -> e.y.value + (e.height.value - fit.height) / 2.0
            VerticalAlign.Bottom -> e.y.value + e.height.value - fit.height
        }
        return TextRefit(x, y, fit.width, fit.height, fit.text)
    }

    /** The box of [e] scaled with its font from its size to [newSize]: the no-measuring fallback, exact for a linear font. */
    fun scaled(e: TextElement, newSize: Double): TextRefit {
        val k = if (e.fontSize > 0.0) newSize / e.fontSize else 1.0
        return anchored(e, TextFit(e.text, e.width.value * k, e.height.value * k))
    }
}
