// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.bridge

import dev.stapler.stelekit.sketch.SheetProps
import dev.stapler.stelekit.sketch.SketchLayer
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.units.Inches
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.floor

/** Why a v2 element has no v1 primitive. Used for the "N elements not used for dimensions" notice. */
enum class DropReason {
    /** A rectangle with a non-zero angle: v1 rectangles are axis-aligned. */
    Rotated,

    /** A kind v1 cannot hold (ellipse, diamond, arc, point, text, image, frame, freehand, arrow). */
    UnsupportedKind,

    /** A line that is not exactly two points or is closed. */
    NotTwoPointLine,

    /** The element sits on a layer v1 cannot name (a layer without a valid v1 name, or beyond the v1 layer cap). */
    LayerNotRepresentable,
}

/** `Projected` carries the v1 primitive; `Dropped` says why there is none. Feeds fusion only (ADR-002). */
sealed interface ProjectionResult {
    data class Projected(val primitive: SketchPrimitive) : ProjectionResult

    data class Dropped(val reason: DropReason) : ProjectionResult
}

data class DroppedElement(val id: ElementId, val reason: DropReason)

/** [primitives] in paint order, the v1 [layers] they use, and every element left out with its reason. */
data class Projection(val primitives: List<SketchPrimitive>, val layers: List<SketchLayer>, val dropped: List<DroppedElement>) {
    val droppedCount: Int get() = dropped.size
}

/**
 * Partial projection of a v2 scene onto v1 primitives (ADR-002): axis-aligned rectangles and two-point lines only.
 * Coordinates are rounded to the nearest sixteenth once, here, half away from zero; values that came from [V1ToV2.lift]
 * are already whole so projecting them is the identity. Everything else is `Dropped` with a reason, never snapped.
 */
object V2ToV1 {
    /** [layerNames] maps each v1-representable layer id to its v1 name. */
    fun project(element: SceneElement, layerNames: Map<LayerId, String>): ProjectionResult {
        val layer = layerNames[element.base.layerId] ?: return ProjectionResult.Dropped(DropReason.LayerNotRepresentable)
        return when (element) {
            is RectElement -> projectRect(element, layer)
            is LineElement -> projectLine(element, layer)
            is ArrowElement, is DiamondElement, is EllipseElement, is FrameElement, is FreedrawElement,
            is ImageElement, is TextElement, is PointElement, is ArcElement, is WallElement, is OpeningElement,
            -> ProjectionResult.Dropped(DropReason.UnsupportedKind)
        }
    }

    fun projectScene(scene: SketchSceneV2): Projection {
        val layers = scene.layers.mapNotNull { l -> V1ToV2.layerName(l.id)?.let { name -> l to name } }
            .filter { (_, name) -> name.isEmpty() || SketchLayer.isValidName(name) }
            .distinctBy { it.second }
        val named = layers.filter { it.second.isNotEmpty() }.take(SheetProps.MAX_LAYERS)
        val layerNames = (named + layers.filter { it.second.isEmpty() }).associate { (l, name) -> l.id to name }
        val primitives = mutableListOf<SketchPrimitive>()
        val dropped = mutableListOf<DroppedElement>()
        for (e in scene.elementsInOrder) {
            when (val r = project(e, layerNames)) {
                is ProjectionResult.Projected -> primitives += r.primitive
                is ProjectionResult.Dropped -> dropped += DroppedElement(e.id, r.reason)
            }
        }
        return Projection(primitives, named.map { (l, name) -> SketchLayer(name, l.visible, l.locked) }, dropped)
    }

    private fun projectRect(r: RectElement, layer: String): ProjectionResult {
        if (r.angle != 0.0) return ProjectionResult.Dropped(DropReason.Rotated)
        val flip = (r.base.extras[RECT_FLIP_KEY] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val x0 = sixteenths(r.x)
        val y0 = sixteenths(r.y)
        val x1 = roundHalfAwayFromZero(r.x.value + r.width.value)
        val y1 = roundHalfAwayFromZero(r.y.value + r.height.value)
        val (ax, bx) = if ('x' in flip) x1 to x0 else x0 to x1
        val (ay, by) = if ('y' in flip) y1 to y0 else y0 to y1
        return ProjectionResult.Projected(SketchPrimitive.Rect(r.id.value, SketchPoint(Inches(ax), Inches(ay)), SketchPoint(Inches(bx), Inches(by)), layer))
    }

    private fun projectLine(l: LineElement, layer: String): ProjectionResult {
        if (l.polygon || l.points.size != LINE_POINTS) return ProjectionResult.Dropped(DropReason.NotTwoPointLine)
        val (a, b) = l.points
        return ProjectionResult.Projected(
            SketchPrimitive.Line(
                l.id.value,
                SketchPoint(Inches(sixteenths(a.x)), Inches(sixteenths(a.y))),
                SketchPoint(Inches(sixteenths(b.x)), Inches(sixteenths(b.y))),
                layer,
            ),
        )
    }

    private const val LINE_POINTS = 2

    private fun sixteenths(u: SceneUnit): Long = roundHalfAwayFromZero(u.value)

    private fun roundHalfAwayFromZero(d: Double): Long {
        val magnitude = floor(kotlin.math.abs(d) + ROUND_HALF).toLong()
        return if (d < 0.0) -magnitude else magnitude
    }

    private const val ROUND_HALF = 0.5
}
