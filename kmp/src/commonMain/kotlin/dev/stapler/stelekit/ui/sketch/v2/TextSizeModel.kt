// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.StyleText
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextSizeLimits
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchTextSizeStrings as S
import kotlin.math.roundToLong

/** What a text-size control asks for: apply [size] (with a [notice] when it was pulled to a limit) or refuse with [message]. */
sealed interface TextSizeChange {
    data class Apply(val size: Double, val notice: String?) : TextSizeChange

    data class Reject(val message: String) : TextSizeChange
}

/**
 * The text-size controls (A-, A+, the size field) as pure rules. Sizes are scene units; a technical sheet's unit conversion is
 * left to a later change, so the number shown is the number stored. A selection of texts of different sizes steps from the first.
 */
object TextSizeModel {
    private const val TENTH = 10.0

    /** The text elements the controls act on: stand-alone text, not a label that its shape or arrow sizes itself, and not locked text. */
    fun targets(selection: List<SceneElement>): List<TextElement> = selection.filterIsInstance<TextElement>().filter { it.containerId == null && !it.base.locked }

    /** The size the controls show and step from, or null when no text is selected. */
    fun current(selection: List<SceneElement>): Double? = targets(selection).firstOrNull()?.fontSize

    /** "28", or "27.5": a whole size without a decimal point, anything else to one decimal. */
    fun format(size: Double): String {
        val tenths = (size * TENTH).roundToLong()
        return if (tenths % TENTH.toLong() == 0L) (tenths / TENTH.toLong()).toString() else (tenths / TENTH).toString()
    }

    fun smaller(current: Double): TextSizeChange = limited(TextSizeLimits.stepDown(current))

    fun larger(current: Double): TextSizeChange = limited(TextSizeLimits.stepUp(current))

    /** A size typed in the field: out of range is pulled to the limit and says so; not a number is refused. */
    fun typed(text: String): TextSizeChange {
        val value = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            ?: return TextSizeChange.Reject(S.invalid(format(TextSizeLimits.MIN), format(TextSizeLimits.MAX)))
        return limited(TextSizeLimits.clamp(value))
    }

    fun command(ids: List<ElementId>, size: Double, metrics: TextMetricsProvider?): Command = StyleText(ids, fontSize = size, metrics = metrics)

    private fun limited(c: TextSizeLimits.Clamped): TextSizeChange.Apply = TextSizeChange.Apply(
        c.size,
        when (c.hit) {
            TextSizeLimits.Bound.Min -> S.tooSmall(format(TextSizeLimits.MIN))
            TextSizeLimits.Bound.Max -> S.tooLarge(format(TextSizeLimits.MAX))
            null -> null
        },
    )
}
