// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

/**
 * Which side of a wall's drawn centreline (its [WallElement.points]) the wall body grows to.
 * [Inside] puts the drawn line on the room-side face (the body grows outward, so the drawn lengths are the clear room
 * dimensions), [Centre] splits the thickness, [Outside] puts the drawn line on the outer face. For a closed wall "inside"
 * is the polygon interior; for an open wall it is the left of the direction of travel (y grows downward on screen).
 */
enum class WallJustification { Inside, Centre, Outside }

/**
 * A wall: a centreline polyline with a thickness, drawn as a double line with mitred joints. [closed] joins the last
 * vertex back to the first (a room). The drawn points are the reference line; [justification] says where the body sits
 * relative to them. Openings (doors, windows) are separate [OpeningElement]s that name this wall as their host.
 */
data class WallElement(
    override val base: ElementBase,
    val points: List<ScenePoint>,
    val thickness: SceneUnit,
    val justification: WallJustification = WallJustification.Centre,
    val closed: Boolean = false,
) : SceneElement {
    init {
        require(points.size >= MIN_POINTS) { "a wall needs two points" }
        require(!closed || points.size >= MIN_CLOSED_POINTS) { "a closed wall needs three points" }
        require(thickness.value > 0.0) { "wall thickness must be positive" }
    }

    override fun withBase(base: ElementBase) = copy(base = base)

    /** Segment count: one fewer than the points, or equal to them when closed. */
    val segmentCount: Int get() = if (closed) points.size else points.size - 1

    companion object {
        const val MIN_POINTS = 2
        const val MIN_CLOSED_POINTS = 3

        /** A new wall is 4.5 inches thick (a framed interior wall); 1 scene unit is 1/16 inch. */
        const val DEFAULT_THICKNESS_UNITS = 72
    }
}

enum class OpeningKind { Door, Window, Opening }

/**
 * A door, window or plain opening hosted on a wall: it sits on segment [segment] of [hostId], [offset] scene units from
 * that segment's start vertex, [width] wide (measured along the wall). [flipSide] swings a door to the other side of the
 * wall; [flipHinge] puts its hinge at the far jamb.
 *
 * The box fields ([x], [y], [height], [angle]) are derived: they are the opening's footprint on the wall (centre-aligned
 * box of the opening's width by the wall's thickness, turned to the wall's angle) and exist so selection, bounds and
 * spatial indexing work like any box. `reconcile` rewrites them from the host after every command, so a wall that moves
 * or is edited carries its openings along; editing them directly has no lasting effect.
 */
data class OpeningElement(
    override val base: ElementBase,
    val hostId: ElementId,
    val segment: Int,
    val offset: SceneUnit,
    override val width: SceneUnit,
    val kind: OpeningKind = OpeningKind.Door,
    val flipSide: Boolean = false,
    val flipHinge: Boolean = false,
    override val x: SceneUnit = SceneUnit.ZERO,
    override val y: SceneUnit = SceneUnit.ZERO,
    override val height: SceneUnit = SceneUnit.ZERO,
    override val angle: Double = 0.0,
) : BoxElement {
    init {
        require(segment >= 0) { "segment index is not negative" }
        require(width.value > 0.0) { "opening width must be positive" }
        require(offset.value >= 0.0) { "opening offset is not negative" }
        require(angle.isFinite()) { "angle must be finite" }
    }

    override fun withBase(base: ElementBase) = copy(base = base)

    companion object {
        /** A new door is 3 feet wide. */
        const val DEFAULT_DOOR_UNITS = 576

        /** A new window is 3 feet wide. */
        const val DEFAULT_WINDOW_UNITS = 576
    }
}
