// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningKind
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.ValueError
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import dev.stapler.stelekit.sketch.v2.scene.WallJustification

private fun SketchSceneV2.wall(id: ElementId, operation: String): Either<CommandError, WallElement> = either {
    this@wall[id] as? WallElement ?: raise(CommandError.Unsupported(id.value, operation))
}

private fun SketchSceneV2.opening(id: ElementId, operation: String): Either<CommandError, OpeningElement> = either {
    this@opening[id] as? OpeningElement ?: raise(CommandError.Unsupported(id.value, operation))
}

private fun positive(value: Double, limits: dev.stapler.stelekit.sketch.v2.scene.SceneLimits): Either<CommandError, SceneUnit> = either {
    if (!(value > 0.0)) raise(CommandError.ValueRejected(ValueError.NotPositive(value)))
    sceneUnit(value, limits).bind()
}

/** Replaces the vertices of one wall (a vertex dragged, added or removed is one undo step). A wall keeps its closed flag, its vertex minimum, and no zero-length segment. */
data class SetWallPoints(val id: ElementId, val points: List<ScenePoint>) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        val wall = scene.wall(id, "set wall points").bind()
        val minimum = if (wall.closed) WallElement.MIN_CLOSED_POINTS else WallElement.MIN_POINTS
        if (points.size < minimum || points.size > scene.limits.maxPointsPerElement || !WallGeometry.hasDistinctVertices(points, wall.closed)) {
            raise(CommandError.Unsupported(id.value, "wall vertices"))
        }
        for (p in points) {
            sceneUnit(p.x.value, scene.limits).bind()
            sceneUnit(p.y.value, scene.limits).bind()
        }
        val next = wall.copy(points = points)
        finish(doc, if (next == wall) scene else scene.replace(next.touched()).bind(), setOf(id))
    }
}

/** Sets a wall's thickness, justification and whether it is a closed room; a null field is left as it is. */
data class SetWallProperties(
    val id: ElementId,
    val thickness: Double? = null,
    val justification: WallJustification? = null,
    val closed: Boolean? = null,
) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        val wall = scene.wall(id, "set wall properties").bind()
        val closedNow = closed ?: wall.closed
        if (closedNow && (wall.points.size < WallElement.MIN_CLOSED_POINTS || !WallGeometry.hasDistinctVertices(wall.points, true))) {
            raise(CommandError.Unsupported(id.value, "close wall"))
        }
        val next = wall.copy(
            thickness = thickness?.let { positive(it, scene.limits).bind() } ?: wall.thickness,
            justification = justification ?: wall.justification,
            closed = closedNow,
        )
        finish(doc, if (next == wall) scene else scene.replace(next.touched()).bind(), setOf(id))
    }
}

/**
 * Sets the length of segment [segment] by moving its end vertex along the segment's direction. On an open wall the later
 * vertices move with it, so the rest of the chain keeps its shape; on a closed wall only that vertex moves (the room stays closed).
 */
data class SetWallSegmentLength(val id: ElementId, val segment: Int, val length: Double) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        val wall = scene.wall(id, "set wall segment length").bind()
        val seg = WallGeometry.segments(wall).getOrNull(segment) ?: raise(CommandError.Unsupported(id.value, "wall segment"))
        if (!(length > 0.0)) raise(CommandError.ValueRejected(ValueError.NotPositive(length)))
        val delta = length - seg.length
        val dx = seg.u.x * delta
        val dy = seg.u.y * delta
        val first = (segment + 1) % wall.points.size
        val last = if (wall.closed) first else wall.points.lastIndex
        val moved = wall.points.mapIndexed { i, p ->
            if (i in first..last) ScenePoint(sceneUnit(p.x.value + dx, scene.limits).bind(), sceneUnit(p.y.value + dy, scene.limits).bind()) else p
        }
        if (!WallGeometry.hasDistinctVertices(moved, wall.closed)) raise(CommandError.Unsupported(id.value, "wall segment length"))
        val next = wall.copy(points = moved)
        finish(doc, if (next == wall) scene else scene.replace(next.touched()).bind(), setOf(id))
    }
}

/**
 * Edits an opening: its [offset] along its wall segment, [width], [kind], and which side it swings or hinges on. Null leaves
 * a field alone. `reconcile` then fits the opening inside the wall (a typed offset past the segment end stops at the jamb).
 */
data class SetOpening(
    val id: ElementId,
    val offset: Double? = null,
    val width: Double? = null,
    val kind: OpeningKind? = null,
    val flipSide: Boolean? = null,
    val flipHinge: Boolean? = null,
    val segment: Int? = null,
) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        val opening = scene.opening(id, "set opening").bind()
        val host = scene[opening.hostId] as? WallElement ?: raise(CommandError.Unsupported(id.value, "opening host"))
        val next = opening.copy(
            segment = segment ?: opening.segment,
            offset = offset?.let { o ->
                if (o < 0.0) raise(CommandError.ValueRejected(ValueError.NotPositive(o)))
                sceneUnit(o, scene.limits).bind()
            } ?: opening.offset,
            width = width?.let { positive(it, scene.limits).bind() } ?: opening.width,
            kind = kind ?: opening.kind,
            flipSide = flipSide ?: opening.flipSide,
            flipHinge = flipHinge ?: opening.flipHinge,
        )
        if (WallGeometry.fit(host, next.segment, next.offset.value, next.width.value) == null) raise(CommandError.Unsupported(id.value, "opening does not fit the wall"))
        finish(doc, if (next == opening) scene else scene.replace(next.touched()).bind(), setOf(id))
    }
}

/** Turns an opening over: [swing] flips the side a door swings to, [hinge] moves its hinge to the other jamb. */
data class FlipOpening(val id: ElementId, val swing: Boolean = true, val hinge: Boolean = false) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val opening = doc.scene.opening(id, "flip opening").bind()
        SetOpening(id, flipSide = if (swing) !opening.flipSide else opening.flipSide, flipHinge = if (hinge) !opening.flipHinge else opening.flipHinge).apply(doc).bind()
    }
}

/**
 * An opening dragged on its own slides along its wall by the part of ([dx], [dy]) that lies along the wall segment (a drag
 * across the wall does nothing); `reconcile` stops it at the jambs. An opening moved together with its wall is not slid: the
 * wall's move already carries it.
 */
internal fun OpeningElement.slidAlong(scene: SketchSceneV2, dx: Double, dy: Double): Either<CommandError, OpeningElement> = either {
    val wall = scene[hostId] as? WallElement ?: return@either this@slidAlong
    val seg = WallGeometry.segments(wall).getOrNull(segment) ?: return@either this@slidAlong
    val along = dx * seg.u.x + dy * seg.u.y
    copy(offset = sceneUnit(maxOf(0.0, offset.value + along), scene.limits).bind())
}
