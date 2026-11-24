// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.SetLinePoints
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.ui.sketch.lengthTextOfUnits
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.MetricLength
import dev.stapler.stelekit.units.MetricUnit
import kotlin.math.hypot

/** What a submitted segment does: show the parser's message, move to the next segment, or finish with one command (null when nothing changed). */
sealed interface SegmentStep {
    data class Invalid(val message: String) : SegmentStep

    data class Next(val index: Int) : SegmentStep

    data class Done(val command: SetLinePoints?) : SegmentStep
}

/**
 * Chain entry for a line of several segments (a polyline of three or more vertices): the lengths are typed one segment after the
 * next, Next going from segment 1 to 2 and so on, and the whole edit is ONE [SetLinePoints] on Done. Each segment keeps its own
 * direction and the first vertex stays where it is; a later vertex moves by the change of every segment before it, so the chain
 * stays connected. Like the pill editor it keeps a unit written in one field for the plain numbers of the next ([stickyUnit]).
 */
class SegmentChainEdit(private val line: LineElement, var format: LengthFormat = LengthFormat.FEET_INCHES) {
    private val original: List<Double> = lengthsOf(line)
    private val entered = mutableMapOf<Int, Double>()

    var index: Int = 0
        private set

    var stickyUnit: MetricUnit? = null
        private set

    val count: Int get() = original.size
    val isLast: Boolean get() = index == original.lastIndex

    /** The text segment [i] starts with: what was typed there, else its current length. */
    fun textOf(i: Int = index): String = lengthTextOfUnits(entered[i] ?: original[i], format)

    /** The open segment's text is submitted: Next goes on to the following segment, Done on the last. Invalid keeps every value as it was. */
    fun submit(text: String, untouched: Boolean = false): SegmentStep =
        when (val parsed = DimensionPillModel.parse(text, format, stickyUnit)) {
            is DimensionPillModel.ParsedLength.Invalid -> SegmentStep.Invalid(parsed.message)
            is DimensionPillModel.ParsedLength.Valid -> {
                if (!(untouched && index !in entered)) {
                    entered[index] = parsed.units
                    MetricLength.explicitUnitOf(text)?.let { stickyUnit = it }
                }
                if (isLast) SegmentStep.Done(finish()) else SegmentStep.Next(++index)
            }
        }

    private fun finish(): SetLinePoints? {
        val changed = entered.filter { (i, v) -> original[i] != v }
        if (changed.isEmpty()) return null
        val points = line.points
        val out = ArrayList<ScenePoint>(points.size)
        out += points.first()
        for (i in original.indices) {
            val a = points[i]
            val b = points[i + 1]
            val length = original[i]
            val scale = (changed[i] ?: length) / length
            val prev = out.last()
            val x = SceneUnit.of(prev.x.value + (b.x.value - a.x.value) * scale).getOrNull() ?: return null
            val y = SceneUnit.of(prev.y.value + (b.y.value - a.y.value) * scale).getOrNull() ?: return null
            out += ScenePoint(x, y)
        }
        return SetLinePoints(line.id, out)
    }

    companion object {
        /** True for a line this editor handles: an open line of at least two segments, none of zero length. */
        fun handles(line: LineElement): Boolean = !line.polygon && line.points.size > TWO_VERTICES && lengthsOf(line).all { it > 0.0 }

        private const val TWO_VERTICES = 2

        private fun lengthsOf(line: LineElement): List<Double> =
            line.points.zipWithNext { a, b -> hypot(b.x.value - a.x.value, b.y.value - a.y.value) }
    }
}
