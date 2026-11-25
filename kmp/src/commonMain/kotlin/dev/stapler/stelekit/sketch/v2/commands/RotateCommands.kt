// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.binding.BindingEngine
import dev.stapler.stelekit.sketch.v2.binding.followBoundArrows
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.ValueError
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rotates [ids] rigidly by [delta] radians (clockwise on screen, y grows downward) about ([pivotX], [pivotY]) in ONE step: boxes,
 * text, images and frames turn about their own centre and move round the pivot, ellipses likewise, lines, arrows, freehand strokes
 * and walls turn their vertices, arcs their centre and sweep, points their position. A frame takes its members and a container its
 * bound labels with it; an opening follows its wall (it is re-derived from the host, so it is never turned on its own). Arrow ends
 * bound to a turned shape follow it; an arrow turned without a shape it is bound to is unbound from it there.
 */
data class RotateSelection(val ids: List<ElementId>, val delta: Double, val pivotX: Double, val pivotY: Double) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        if (!delta.isFinite()) raise(CommandError.ValueRejected(ValueError.NonFinite(delta)))
        if (!pivotX.isFinite() || !pivotY.isFinite()) raise(CommandError.ValueRejected(ValueError.NonFinite(if (pivotX.isFinite()) pivotY else pivotX)))
        val scene = doc.scene
        val moved = moveSet(scene, ids).bind()
        var out = scene
        val rot = Rot(pivotX, pivotY, delta, scene.limits)
        for (id in moved) {
            val e = scene.element(id).bind()
            val next = rot.turn(e).bind()
            if (next != e) out = out.replace(next.touched()).bind()
        }
        val freed = BindingEngine.unbindStranded(out, moved).bind()
        val followed = freed.scene.followBoundArrows(moved).bind()
        finish(doc, followed.scene, moved + freed.changed + followed.arrows)
    }
}

/** The angle [a] folded into (-PI, PI]. */
internal fun foldAngle(a: Double): Double {
    val full = 2.0 * PI
    var r = a % full
    if (r > PI) r -= full
    if (r <= -PI) r += full
    return r + 0.0
}

/** One rigid rotation about a pivot, applied to any element kind. */
internal class Rot(private val px: Double, private val py: Double, private val delta: Double, private val limits: SceneLimits) {
    private val c = cos(delta)
    private val s = sin(delta)

    private fun xOf(x: Double, y: Double) = px + (x - px) * c - (y - py) * s
    private fun yOf(x: Double, y: Double) = py + (x - px) * s + (y - py) * c

    private fun point(p: ScenePoint): Either<CommandError, ScenePoint> = either {
        ScenePoint(sceneUnit(xOf(p.x.value, p.y.value), limits).bind(), sceneUnit(yOf(p.x.value, p.y.value), limits).bind())
    }

    private fun points(ps: List<ScenePoint>): Either<CommandError, List<ScenePoint>> = either { ps.map { point(it).bind() } }

    /** Top-left of a box of [w] by [h] whose centre ([cx], [cy]) has been turned about the pivot. */
    private fun boxCorner(cx: Double, cy: Double, w: Double, h: Double) = (xOf(cx, cy) - w / HALF) to (yOf(cx, cy) - h / HALF)

    @Suppress("CyclomaticComplexMethod")
    fun turn(e: SceneElement): Either<CommandError, SceneElement> = either {
        when (e) {
            is RectElement -> boxCorner(e.x.value + e.width.value / HALF, e.y.value + e.height.value / HALF, e.width.value, e.height.value).let { (x, y) ->
                e.copy(x = sceneUnit(x, limits).bind(), y = sceneUnit(y, limits).bind(), angle = foldAngle(e.angle + delta))
            }
            is DiamondElement -> boxCorner(e.x.value + e.width.value / HALF, e.y.value + e.height.value / HALF, e.width.value, e.height.value).let { (x, y) ->
                e.copy(x = sceneUnit(x, limits).bind(), y = sceneUnit(y, limits).bind(), angle = foldAngle(e.angle + delta))
            }
            is TextElement -> boxCorner(e.x.value + e.width.value / HALF, e.y.value + e.height.value / HALF, e.width.value, e.height.value).let { (x, y) ->
                e.copy(x = sceneUnit(x, limits).bind(), y = sceneUnit(y, limits).bind(), angle = foldAngle(e.angle + delta))
            }
            is ImageElement -> boxCorner(e.x.value + e.width.value / HALF, e.y.value + e.height.value / HALF, e.width.value, e.height.value).let { (x, y) ->
                e.copy(x = sceneUnit(x, limits).bind(), y = sceneUnit(y, limits).bind(), angle = foldAngle(e.angle + delta))
            }
            is FrameElement -> boxCorner(e.x.value + e.width.value / HALF, e.y.value + e.height.value / HALF, e.width.value, e.height.value).let { (x, y) ->
                e.copy(x = sceneUnit(x, limits).bind(), y = sceneUnit(y, limits).bind(), angle = foldAngle(e.angle + delta))
            }
            is EllipseElement -> e.copy(
                cx = sceneUnit(xOf(e.cx.value, e.cy.value), limits).bind(), cy = sceneUnit(yOf(e.cx.value, e.cy.value), limits).bind(),
                angle = foldAngle(e.angle + delta),
            )
            is PointElement -> e.copy(x = sceneUnit(xOf(e.x.value, e.y.value), limits).bind(), y = sceneUnit(yOf(e.x.value, e.y.value), limits).bind())
            is ArcElement -> e.copy(
                cx = sceneUnit(xOf(e.cx.value, e.cy.value), limits).bind(), cy = sceneUnit(yOf(e.cx.value, e.cy.value), limits).bind(),
                startAngle = e.startAngle + delta, endAngle = e.endAngle + delta,
            )
            is LineElement -> e.copy(points = points(e.points).bind())
            is ArrowElement -> e.copy(points = points(e.points).bind())
            is FreedrawElement -> e.copy(points = points(e.points).bind())
            is WallElement -> e.copy(points = points(e.points).bind())
            is OpeningElement -> e
        }
    }

    private companion object {
        const val HALF = 2.0
    }
}
