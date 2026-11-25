// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

import arrow.core.getOrElse

/** Scene-level wall helpers: which openings sit on a wall, the cuts they make, and how a wall lowers to plain lines for formats that have no walls. */
object WallScene {
    /** Every opening of [scene] grouped by the wall it names; an opening whose host is missing or is not a wall is left out. */
    fun openingsByHost(scene: SketchSceneV2): Map<ElementId, List<OpeningElement>> =
        scene.elementsInOrder.filterIsInstance<OpeningElement>()
            .filter { scene[it.hostId] is WallElement }
            .groupBy { it.hostId }

    fun openingsOf(scene: SketchSceneV2, wall: ElementId): List<OpeningElement> = openingsByHost(scene)[wall].orEmpty()

    /** Openings whose host is missing or is not a wall: they draw nothing and `validate` reports them. */
    fun orphans(scene: SketchSceneV2): List<OpeningElement> =
        scene.elementsInOrder.filterIsInstance<OpeningElement>().filter { scene[it.hostId] !is WallElement }

    /** The body cuts of [openings] on [wall]: doors and plain openings cut the body, windows leave it. */
    fun cuts(wall: WallElement, openings: List<OpeningElement>): List<WallCut> = openings
        .filter { it.kind != OpeningKind.Window }
        .mapNotNull { o ->
            WallGeometry.fit(wall, o.segment, o.offset.value, o.width.value)?.let { (offset, width) -> WallCut(o.segment, offset, offset + width) }
        }

    fun layout(wall: WallElement, openings: List<OpeningElement>): WallLayout = WallGeometry.layout(wall, cuts(wall, openings))

    /**
     * [scene] with every wall and opening replaced by plain lines in the same layer and order: the body's quads as filled
     * polygons (when the wall has a background colour), faces, caps and jambs as lines, and each opening's leaf and arc or
     * window lines. The element cap stops the lowering; what did not fit is left out. Walls are a one-way export (no importer
     * rebuilds them).
     */
    fun lowered(scene: SketchSceneV2): SketchSceneV2 {
        if (scene.elementsInOrder.none { it is WallElement || it is OpeningElement }) return scene
        val byHost = openingsByHost(scene)
        var out = SketchSceneV2.empty(scene.limits).withLayers(scene.layers)
        for (e in scene.elementsInOrder) {
            val parts = when (e) {
                is WallElement -> loweredWall(e, byHost[e.id].orEmpty())
                is OpeningElement -> loweredOpening(e, scene[e.hostId] as? WallElement)
                else -> listOf(e)
            }
            for (p in parts) out = out.add(p).getOrElse { out }
        }
        return out
    }

    private fun loweredWall(wall: WallElement, openings: List<OpeningElement>): List<SceneElement> {
        val layout = layout(wall, openings)
        val lineStyle = wall.base.style.copy(backgroundColor = TRANSPARENT, roughness = 0, roundness = null)
        val fillStyle = wall.base.style.copy(strokeColor = TRANSPARENT, strokeWidth = 0.0, fillStyle = FillStyle.Solid, roughness = 0, roundness = null)
        val fills = if (wall.base.style.backgroundColor == TRANSPARENT) emptyList() else layout.fills.mapIndexed { i, q -> polygon(wall, "f$i", q, fillStyle, closed = true) }
        val lines = layout.strokes.mapIndexed { i, s -> polygon(wall, "s$i", s.points, lineStyle, s.closed) }
        return fills + lines
    }

    private fun loweredOpening(opening: OpeningElement, host: WallElement?): List<SceneElement> =
        if (host == null) emptyList() else linesOf(opening, WallGeometry.symbol(host, opening))

    /** The opening's symbol and jamb lines as plain line elements, for a tool preview (the wall it sits on is not part of the preview scene). */
    fun openingLines(host: WallElement, opening: OpeningElement): List<SceneElement> =
        linesOf(opening, WallGeometry.symbol(host, opening) + WallGeometry.jambs(host, opening))

    private fun linesOf(opening: OpeningElement, strokes: List<WallStroke>): List<SceneElement> {
        val style = opening.base.style.copy(backgroundColor = TRANSPARENT, roughness = 0, roundness = null)
        return strokes.mapIndexed { i, s -> polygon(opening, "k$i", s.points, style, s.closed) }
    }

    private fun polygon(from: SceneElement, suffix: String, points: List<WallVec>, style: ElementStyle, closed: Boolean): LineElement {
        val unit = { v: Double -> SceneUnit.of(v).getOrElse { SceneUnit.ZERO } }
        val base = from.base.copy(id = ElementId("${from.id.value}.$suffix"), style = style, extras = EMPTY_EXTRAS)
        return LineElement(base, points.map { ScenePoint(unit(it.x), unit(it.y)) }, polygon = closed)
    }

    private const val TRANSPARENT = "transparent"
    private val EMPTY_EXTRAS = kotlinx.serialization.json.JsonObject(emptyMap())
}
