// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.importer

import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawElement
import dev.stapler.stelekit.sketch.excalidraw.ImportReason
import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BindingMode
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.cos
import kotlin.math.sin

/** Line, arrow and freedraw: points are relative to the element origin in Excalidraw and absolute in the scene. */
internal class LinearMapper(private val ctx: MapContext) {
    fun map(x: ExcalidrawElement, zKey: ZKey): SceneElement? {
        val points = points(x)
        val minimum = if (x.type == "freedraw") 1 else 2
        if (points == null || points.size < minimum) {
            ctx.report.drop(x.id, if (points == null) ImportReason.CoordinateOutOfRange.name else DEGENERATE)
            return null
        }
        return when (x.type) {
            "line" -> arcOrLine(x, zKey, points)
            "arrow" -> arrow(x, zKey, points)
            else -> freedraw(x, zKey, points)
        }
    }

    private fun points(x: ExcalidrawElement): List<ScenePoint>? {
        val raw = x.raw.array("points") ?: return emptyList()
        return raw.indices.map { i ->
            val (px, py) = pairAt(raw, i) ?: return null
            ScenePoint(ctx.unit(x.x + px) ?: return null, ctx.unit(x.y + py) ?: return null)
        }
    }

    private fun arcOrLine(x: ExcalidrawElement, zKey: ZKey, points: List<ScenePoint>): SceneElement {
        val base = ctx.base.base(x, zKey, LINE_MEMBERS)
        return arc(x, base) ?: LineElement(base, points, polygon = x.raw.flag("polygon") ?: false)
    }

    /** OD-8: a sampled line tagged `customData.stelekit.kind = arc` is restored from the exact arc parameters it carries. */
    private fun arc(x: ExcalidrawElement, base: ElementBase): ArcElement? {
        val tag = x.raw.obj("customData")?.obj(StelekitCustomData.KEY) ?: return null
        if (tag.text(StelekitCustomData.KIND) != StelekitCustomData.KIND_ARC) return null
        val cx = tag.number(StelekitCustomData.ARC_CX)?.let { ctx.unit(it) } ?: return null
        val cy = tag.number(StelekitCustomData.ARC_CY)?.let { ctx.unit(it) } ?: return null
        val radius = tag.number(StelekitCustomData.ARC_RADIUS)?.let { ctx.unit(it) } ?: return null
        val start = tag.number(StelekitCustomData.ARC_START) ?: return null
        val end = tag.number(StelekitCustomData.ARC_END) ?: return null
        return ArcElement.create(base, cx, cy, radius, start, end).getOrNull()
    }

    private fun arrow(x: ExcalidrawElement, zKey: ZKey, points: List<ScenePoint>): SceneElement {
        val raw = x.raw
        return ArrowElement(
            base = ctx.base.base(x, zKey, ARROW_MEMBERS),
            points = points,
            startBinding = binding(raw.obj("startBinding"), points.first()),
            endBinding = binding(raw.obj("endBinding"), points.last()),
            startArrowhead = if (raw.containsKey("startArrowhead")) arrowhead(raw.text("startArrowhead")) else Arrowhead.None,
            endArrowhead = if (raw.containsKey("endArrowhead")) arrowhead(raw.text("endArrowhead")) else Arrowhead.Arrow,
            elbowed = raw.flag("elbowed") ?: false,
        )
    }

    private fun freedraw(x: ExcalidrawElement, zKey: ZKey, points: List<ScenePoint>): SceneElement {
        val pressures = x.raw.array("pressures").orEmpty().mapNotNull { e ->
            (e as? JsonPrimitive)?.let { CanonicalJson.doubleOf(it) }?.takeIf { it.isFinite() }
        }
        return FreedrawElement(
            base = ctx.base.base(x, zKey, FREEDRAW_MEMBERS),
            points = points,
            pressures = if (pressures.size == points.size) pressures else emptyList(),
            simulatePressure = x.raw.flag("simulatePressure") ?: true,
        )
    }

    private fun arrowhead(name: String?): Arrowhead = when (name) {
        null -> Arrowhead.None
        "arrow" -> Arrowhead.Arrow
        "bar" -> Arrowhead.Bar
        "dot", "circle" -> Arrowhead.Circle
        "circle_outline" -> Arrowhead.CircleOutline
        "triangle" -> Arrowhead.Triangle
        "triangle_outline" -> Arrowhead.TriangleOutline
        "diamond" -> Arrowhead.Diamond
        "diamond_outline" -> Arrowhead.DiamondOutline
        else -> Arrowhead.None.also { ctx.report.unsupportedArrowheads++ }
    }

    /** Both binding generations: `fixedPoint` (current) or `focus`/`gap` (legacy, converted from the endpoint's place on the target). */
    private fun binding(raw: JsonObject?, end: ScenePoint): Binding? {
        raw ?: return null
        val target = raw.text("elementId") ?: return null
        val mode = when (raw.text("mode")) {
            "inside" -> BindingMode.Inside
            "skip" -> BindingMode.Skip
            else -> BindingMode.Orbit
        }
        val fixed = raw.array("fixedPoint")?.let { pairOf(it) } ?: legacyFixedPoint(target, end) ?: return null
        return Binding(ElementId(target), fixed.first, fixed.second, mode)
    }

    private fun legacyFixedPoint(target: String, end: ScenePoint): Pair<Double, Double>? {
        val t = ctx.byId[target] ?: return null
        ctx.report.legacyBindings++
        if (t.width == 0.0 || t.height == 0.0) return HALF to HALF
        val cx = t.x + t.width / 2.0
        val cy = t.y + t.height / 2.0
        val dx = end.x.value - cx
        val dy = end.y.value - cy
        val ux = cx + dx * cos(t.angle) + dy * sin(t.angle)
        val uy = cy - dx * sin(t.angle) + dy * cos(t.angle)
        return (ux - t.x) / t.width to (uy - t.y) / t.height
    }

    private companion object {
        const val DEGENERATE = "Degenerate"
        const val HALF = 0.5
        val LINE_MEMBERS = setOf("points", "lastCommittedPoint", "startBinding", "endBinding", "startArrowhead", "endArrowhead", "polygon")
        val ARROW_MEMBERS = setOf("points", "lastCommittedPoint", "startBinding", "endBinding", "startArrowhead", "endArrowhead", "elbowed")
        val FREEDRAW_MEMBERS = setOf("points", "pressures", "simulatePressure", "lastCommittedPoint")
    }
}
