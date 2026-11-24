// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.TextAutoSize
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextSizeLimits
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * How a handle drag resizes a text element. A text box is not a free rectangle: its height follows its lines and its glyphs
 * follow its font, so the drag scales the font (corner and top/bottom handles keep the aspect) or sets the wrap width (left and
 * right handles) instead of stretching a box the text does not fill. The font size is kept inside [TextSizeLimits].
 */
internal object TextResize {
    /**
     * The transform for dragging [handle] of [e] (whose own box is [box]) by ([dx], [dy]). A side handle re-wraps with [metrics]
     * (without it, or while the font is loading, the side handle scales like a corner so the box never stops matching the glyphs).
     */
    fun handleDrag(e: TextElement, box: Box, handle: Handle, dx: Double, dy: Double, fromCentre: Boolean, metrics: TextMetricsProvider?): ElementTransform.TextBox {
        val side = handle.sx != 0 && handle.sy == 0
        if (side && metrics != null) wrapDrag(e, box, handle, dx, dy, fromCentre, metrics)?.let { return it }
        val raw = TransformMath.resize(box, e.angle, handle, dx, dy, keepAspect = true, fromCentre = fromCentre)
        val k = if (handle.sx == 0) ratio(raw.height, box.height) else ratio(raw.width, box.width)
        return scaled(e, box, handle, k, fromCentre)
    }

    /** [e], whose box [from] is one member of a group box that scaled to [to], with its font scaled by the smaller axis factor. */
    fun groupScale(e: TextElement, from: Box, mapped: Box): ElementTransform.TextBox {
        val kx = ratio(mapped.width, from.width)
        val ky = ratio(mapped.height, from.height)
        val size = TextSizeLimits.clamp(e.fontSize * min(kx, ky)).size
        val k = ratio(size, e.fontSize)
        return ElementTransform.TextBox(e.id, mapped.x, mapped.y, from.width * k, from.height * k, size, e.text, e.autoResize)
    }

    private fun scaled(e: TextElement, box: Box, handle: Handle, k: Double, fromCentre: Boolean): ElementTransform.TextBox {
        val size = TextSizeLimits.clamp(e.fontSize * k).size
        val b = TransformMath.scaleFromHandle(box, e.angle, handle, ratio(size, e.fontSize), fromCentre)
        return ElementTransform.TextBox(e.id, b.x, b.y, b.width, b.height, size, e.text, e.autoResize)
    }

    private fun wrapDrag(e: TextElement, box: Box, handle: Handle, dx: Double, dy: Double, fromCentre: Boolean, metrics: TextMetricsProvider): ElementTransform.TextBox? {
        val dragged = TransformMath.resize(box, e.angle, handle, dx, dy, keepAspect = false, fromCentre = fromCentre)
        val wrap = maxOf(dragged.width, e.fontSize)
        val fit = TextAutoSize.fit(e.originalText, TextStyle(e.fontFamily, e.fontSize), e.lineHeight, wrap, metrics) ?: return null
        // Place the wrap width with the opposite edge fixed, then move the centre along the text's own down axis so the top stays put.
        val grow = if (fromCentre) HALF else 1.0
        val along = (wrap - box.width) / (handle.sx * grow)
        val widened = TransformMath.resize(box, e.angle, handle, along * cos(e.angle), along * sin(e.angle), keepAspect = false, fromCentre = fromCentre)
        val shift = (fit.height - box.height) / HALF
        val cx = widened.cx - shift * sin(e.angle)
        val cy = widened.cy + shift * cos(e.angle)
        return ElementTransform.TextBox(e.id, cx - wrap / HALF, cy - fit.height / HALF, wrap, fit.height, e.fontSize, fit.text, autoResize = false)
    }

    private fun ratio(now: Double, before: Double): Double = if (before > 0.0) now / before else 1.0

    private const val HALF = 2.0
}
