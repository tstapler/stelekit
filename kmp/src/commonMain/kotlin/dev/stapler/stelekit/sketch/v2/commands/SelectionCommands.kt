// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.BoxElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import kotlin.math.PI

// Commands the Select tool (Story 3.2.2) emits through the tool-command bridge: a sequence so a multi-element gesture is
// one history step, and a mirror about the selection's own centre.

/** Applies [commands] in order to the running document; the first failure fails the whole sequence and changes nothing. */
data class SequenceCommand(val commands: List<Command>) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        var current = doc
        val changed = LinkedHashSet<ElementId>()
        for (c in commands) {
            val applied = c.apply(current).bind()
            current = applied.doc
            changed += applied.changedIds
        }
        Applied(current, changed)
    }
}

/**
 * Mirrors [ids] about the vertical ([horizontal] true, left-right) or horizontal axis through the centre of their
 * joint box. A rectangle, diamond, ellipse, frame, image or text keeps its size and gets the mirrored angle; text and
 * the image bitmap are repositioned and an image's flip flag toggles, so text stays readable. An arc's sweep is
 * mirrored and re-ordered so the end angle stays after the start angle.
 */
data class FlipElements(val ids: List<ElementId>, val horizontal: Boolean) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        val targets = ids.distinct().map { scene[it] ?: raise(CommandError.UnknownElement(it.value)) }
        if (targets.isEmpty()) return@either Applied(doc, emptySet())
        val boxes = targets.map { ElementBounds.geometry(it) }
        val axis = if (horizontal) (boxes.minOf { it.minX } + boxes.maxOf { it.maxX }) / HALF else (boxes.minOf { it.minY } + boxes.maxOf { it.maxY }) / HALF
        val limits = scene.limits
        fun mirror(v: Double) = 2.0 * axis - v
        fun unit(v: Double) = sceneUnit(v, limits).bind()
        fun point(p: ScenePoint) = if (horizontal) ScenePoint(unit(mirror(p.x.value)), p.y) else ScenePoint(p.x, unit(mirror(p.y.value)))
        var out = scene
        for (e in targets) {
            val flipped: SceneElement = when (e) {
                is RectElement -> e.copy(x = boxX(e, horizontal, ::mirror, ::unit), y = boxY(e, horizontal, ::mirror, ::unit), angle = -e.angle)
                is DiamondElement -> e.copy(x = boxX(e, horizontal, ::mirror, ::unit), y = boxY(e, horizontal, ::mirror, ::unit), angle = -e.angle)
                is FrameElement -> e.copy(x = boxX(e, horizontal, ::mirror, ::unit), y = boxY(e, horizontal, ::mirror, ::unit), angle = -e.angle)
                is TextElement -> e.copy(x = boxX(e, horizontal, ::mirror, ::unit), y = boxY(e, horizontal, ::mirror, ::unit))
                is ImageElement -> e.copy(
                    x = boxX(e, horizontal, ::mirror, ::unit), y = boxY(e, horizontal, ::mirror, ::unit), angle = -e.angle,
                    flipX = if (horizontal) !e.flipX else e.flipX, flipY = if (horizontal) e.flipY else !e.flipY,
                )
                is EllipseElement -> if (horizontal) e.copy(cx = unit(mirror(e.cx.value)), angle = -e.angle) else e.copy(cy = unit(mirror(e.cy.value)), angle = -e.angle)
                is PointElement -> if (horizontal) e.copy(x = unit(mirror(e.x.value))) else e.copy(y = unit(mirror(e.y.value)))
                is LineElement -> e.copy(points = e.points.map(::point))
                is ArrowElement -> e.copy(points = e.points.map(::point))
                is FreedrawElement -> e.copy(points = e.points.map(::point))
                is ArcElement -> flippedArc(e, horizontal, ::mirror, ::unit)
                is WallElement -> e.copy(points = e.points.map(::point))
                is OpeningElement -> e
            }
            if (flipped != e) out = out.replace(flipped.touched()).bind()
        }
        finish(doc, out, targets.mapTo(LinkedHashSet()) { it.id })
    }

    private fun boxX(e: BoxElement, horizontal: Boolean, mirror: (Double) -> Double, unit: (Double) -> dev.stapler.stelekit.sketch.v2.scene.SceneUnit) =
        if (horizontal) unit(mirror(e.x.value + e.width.value)) else e.x

    private fun boxY(e: BoxElement, horizontal: Boolean, mirror: (Double) -> Double, unit: (Double) -> dev.stapler.stelekit.sketch.v2.scene.SceneUnit) =
        if (horizontal) e.y else unit(mirror(e.y.value + e.height.value))

    private fun flippedArc(e: ArcElement, horizontal: Boolean, mirror: (Double) -> Double, unit: (Double) -> dev.stapler.stelekit.sketch.v2.scene.SceneUnit): ArcElement =
        if (horizontal) {
            e.copy(cx = unit(mirror(e.cx.value)), startAngle = PI - e.endAngle, endAngle = PI - e.startAngle)
        } else {
            e.copy(cy = unit(mirror(e.cy.value)), startAngle = -e.endAngle, endAngle = -e.startAngle)
        }

    private companion object {
        const val HALF = 2.0
    }
}
