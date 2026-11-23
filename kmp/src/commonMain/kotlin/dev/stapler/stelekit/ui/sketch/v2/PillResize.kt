// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.scene.BoxElement
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import kotlin.math.cos
import kotlin.math.sin

/** Turns typed pill sizes into the one [ResizeElement] that applies them. */
object PillResize {
    /**
     * The command that applies typed sizes (sixteenths by kind) to [element]. The top-left of the box stays put, so the shape
     * grows right and down and does not jump. A line keeps its direction and is scaled to the new length. Null when the
     * element has no pills or [values] names none of its kinds.
     */
    fun command(element: SceneElement, values: Map<PillKind, Long>): ResizeElement? {
        val stats = ElementStats.of(element) ?: return null
        val specs = DimensionPillModel.specs(element)
        if (specs.none { it.kind in values }) return null
        fun size(kind: PillKind, current: Double): Double = values[kind]?.toDouble() ?: current
        return when (element) {
            is LineElement -> {
                val len = DimensionPillModel.lineLength(element) ?: return null
                val k = size(PillKind.Length, len) / len
                // Scaled about the start point, so the end the user drew from stays where it is.
                val p0 = element.points.first()
                ResizeElement(element.id, p0.x.value + k * (stats.x - p0.x.value), p0.y.value + k * (stats.y - p0.y.value), stats.width * k, stats.height * k)
            }
            else -> {
                val (ax, ay) = anchorKeepingTopLeft(element, stats, values)
                val diameter = diameterOf(values)
                ResizeElement(element.id, ax, ay, diameter ?: size(PillKind.Width, stats.width), diameter ?: size(PillKind.Height, stats.height))
            }
        }
    }

    private fun diameterOf(values: Map<PillKind, Long>): Double? = values[PillKind.Diameter]?.toDouble() ?: values[PillKind.Radius]?.let { 2.0 * it }

    /** The unrotated origin that keeps the visible top-left corner of a rotated box where it is (the unrotated one when the angle is 0). */
    private fun anchorKeepingTopLeft(element: SceneElement, stats: ElementStats, values: Map<PillKind, Long>): Pair<Double, Double> {
        val angle = (element as? BoxElement)?.angle ?: (element as? EllipseElement)?.angle ?: 0.0
        if (angle == 0.0) return stats.x to stats.y
        val diameter = diameterOf(values)
        val w = diameter ?: values[PillKind.Width]?.toDouble() ?: stats.width
        val h = diameter ?: values[PillKind.Height]?.toDouble() ?: stats.height
        val c = cos(angle)
        val s = sin(angle)
        val cx = stats.x + stats.width / 2
        val cy = stats.y + stats.height / 2
        val tlx = cx + (-stats.width / 2) * c - (-stats.height / 2) * s
        val tly = cy + (-stats.width / 2) * s + (-stats.height / 2) * c
        val ncx = tlx + (w / 2) * c - (h / 2) * s
        val ncy = tly + (w / 2) * s + (h / 2) * c
        return (ncx - w / 2) to (ncy - h / 2)
    }
}
