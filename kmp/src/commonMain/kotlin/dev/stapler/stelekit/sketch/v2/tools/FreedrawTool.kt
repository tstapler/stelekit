// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.render.PointSimplifier
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import kotlin.math.roundToLong

/**
 * Freedraw tool (Story 3.4.2). A stroke collects every move of its pointer; at release the points go through
 * [PointSimplifier] (Ramer-Douglas-Peucker down to `SceneLimits.maxPointsPerElement`) and are then quantized, so the
 * stored element stays under 64 KB (see [QUANTUM]). A stylus gives one pressure per point and `simulatePressure` false;
 * a mouse, or a stroke with any point lacking a reading, simulates pressure. A second pointer, Esc or a platform cancel
 * abandons the stroke with no command (UX-08). One stroke is one element and one undo step.
 */
class FreedrawTool(private val limits: SceneLimits = SceneLimits.Default) : Tool {
    override val id: ToolId = ToolId.Freedraw

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult =
        if (event.type == InputType.Down) ToolResult(next = Drawing(this, event.pointerId).with(event)) else ToolResult.Nothing

    private class Drawing(
        private val owner: FreedrawTool,
        private val pointerId: Int,
        private val points: ArrayList<ScenePoint> = ArrayList(),
        private val pressures: ArrayList<Double?> = ArrayList(),
    ) : Tool {
        override val id: ToolId get() = ToolId.Freedraw

        /** Appends [event]'s sample in place: a stroke is one gesture owned by one pointer, so nothing else holds this state. */
        fun with(event: InputEvent): Drawing {
            val rawCap = owner.limits.maxPointsPerElement * RAW_CAPTURE_FACTOR
            if (points.size < rawCap && points.lastOrNull() != event.point) {
                points += event.point
                pressures += event.pressure
            }
            return this
        }

        override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when {
            event.type == InputType.Escape || event.type == InputType.Cancel -> ToolResult(preview = null, next = owner)
            event.type == InputType.Down && event.pointerId != pointerId -> ToolResult(preview = null, next = owner)
            event.pointerId != pointerId -> ToolResult.Nothing
            event.type == InputType.Move -> with(event).let { ToolResult(preview = ToolPreview(listOf(rawStroke(ctx)))) }
            event.type == InputType.Up -> ToolResult(AddElementCommand(commit(with(event), ctx)), null, owner)
            else -> ToolResult.Nothing
        }

        /** The stroke so far, thinned to at most `maxPointsPerElement` points (the last always kept) so a long stroke's preview stays cheap. */
        private fun rawStroke(ctx: ToolContext): FreedrawElement {
            val cap = owner.limits.maxPointsPerElement
            val shown = if (points.size <= cap) points.toList() else {
                val stride = (points.size + cap - 1) / cap
                points.filterIndexed { i, _ -> i % stride == 0 } + points.last()
            }
            return FreedrawElement(ElementBase(PREVIEW_ID, style = ctx.style), shown, emptyList(), simulatePressure = true)
        }

        private fun commit(self: Drawing, ctx: ToolContext): FreedrawElement {
            val measured = self.pressures.all { it != null }
            val stroke = PointSimplifier.simplify(self.points, if (measured) self.pressures.map { it!! } else emptyList(), owner.limits)
            val quantized = quantize(stroke.points, stroke.pressures)
            return FreedrawElement(ElementBase(ctx.ids.next(), style = ctx.style), quantized.first, quantized.second, stroke.simulatePressure)
        }

        /** Rounds coordinates and pressures to the stored precision and drops a point that rounds onto its predecessor. */
        private fun quantize(pts: List<ScenePoint>, pressure: List<Double>): Pair<List<ScenePoint>, List<Double>> {
            val outPoints = ArrayList<ScenePoint>(pts.size)
            val outPressures = ArrayList<Double>(pressure.size)
            for ((i, p) in pts.withIndex()) {
                val q = ScenePoint(unitOrZero(round(p.x.value)), unitOrZero(round(p.y.value)))
                if (outPoints.lastOrNull() == q && i != pts.lastIndex) continue
                outPoints += q
                if (pressure.isNotEmpty()) outPressures += round(pressure[i])
            }
            return outPoints to outPressures
        }

        /** Divides by the scale (not multiplies by the step) so the result is the double nearest the decimal, e.g. exactly 0.2. */
        private fun round(v: Double): Double = (v * DECIMAL_SCALE).roundToLong() / DECIMAL_SCALE
    }

    companion object {
        /**
         * Stored coordinates and pressures keep two decimals: 0.01 unit (1/1600 inch) is finer than a pen or screen
         * resolves, and it keeps the 2,000 points `SceneLimits.maxPointsPerElement` allows under the 64 KB bound of
         * Story 2.1.4 (`FreedrawToolTest` measures the encoded size).
         */
        const val DECIMAL_SCALE = 100.0

        /** The coordinate step that scale gives, scene units; pressures (0..1) use the same two decimals. */
        const val QUANTUM = 1.0 / DECIMAL_SCALE

        /** A stroke captures this many raw points per point it may keep, so a 20,000-point stroke is read in full. */
        const val RAW_CAPTURE_FACTOR = 10
    }
}
