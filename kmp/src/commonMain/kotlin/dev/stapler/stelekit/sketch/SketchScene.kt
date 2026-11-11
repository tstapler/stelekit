package dev.stapler.stelekit.sketch

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.units.Inches
import kotlin.math.abs

/** A sheet-space point in whole sixteenths of an inch (ADR-002). Sheet y grows downward like screen y. */
data class SketchPoint(val x: Inches, val y: Inches)

/** Axis-aligned bounding box, inclusive on both corners, with `min <= max` on each axis. */
data class Aabb(val minX: Long, val minY: Long, val maxX: Long, val maxY: Long) {
    fun intersects(o: Aabb): Boolean = minX <= o.maxX && o.minX <= maxX && minY <= o.maxY && o.minY <= maxY
}

/** A straight piece of a primitive's outline; endpoints are in sixteenths. */
data class SketchSegment(val a: SketchPoint, val b: SketchPoint)

sealed interface SketchPrimitive {
    val id: String

    /** Layer name; `""` is the default layer. */
    val layer: String

    /** Vertices that the snap engine treats as endpoints. */
    val vertices: List<SketchPoint>

    /** Outline pieces used for midpoints, intersections and hit testing. */
    val segments: List<SketchSegment>

    val aabb: Aabb
        get() {
            val xs = vertices.map { it.x.sixteenths }
            val ys = vertices.map { it.y.sixteenths }
            return Aabb(xs.min(), ys.min(), xs.max(), ys.max())
        }

    data class Line(override val id: String, val a: SketchPoint, val b: SketchPoint, override val layer: String = "") : SketchPrimitive {
        override val vertices: List<SketchPoint> get() = listOf(a, b)
        override val segments: List<SketchSegment> get() = listOf(SketchSegment(a, b))
    }

    /** Axis-aligned rectangle given by two opposite corners. */
    data class Rect(override val id: String, val a: SketchPoint, val b: SketchPoint, override val layer: String = "") : SketchPrimitive {
        private val corners: List<SketchPoint>
            get() = listOf(a, SketchPoint(b.x, a.y), b, SketchPoint(a.x, b.y))
        override val vertices: List<SketchPoint> get() = corners
        override val segments: List<SketchSegment>
            get() = corners.let { c -> c.indices.map { SketchSegment(c[it], c[(it + 1) % c.size]) } }
    }
}

fun SketchPrimitive.withLayer(layer: String): SketchPrimitive = when (this) {
    is SketchPrimitive.Line -> copy(layer = layer)
    is SketchPrimitive.Rect -> copy(layer = layer)
}

sealed interface SceneError {
    data class CapacityExceeded(val max: Int) : SceneError
    data class DuplicateId(val id: String) : SceneError

    /** A vertex lies beyond [SketchScene.MAX_COORD_SIXTEENTHS], which keeps snap arithmetic overflow-free. */
    data class CoordinateOutOfRange(val id: String) : SceneError
}

/** Immutable set of at most [MAX_PRIMITIVES] primitives with unique ids. */
class SketchScene private constructor(val primitives: List<SketchPrimitive>) {

    val size: Int get() = primitives.size

    fun add(primitive: SketchPrimitive): Either<SceneError, SketchScene> = when {
        primitives.size >= MAX_PRIMITIVES -> SceneError.CapacityExceeded(MAX_PRIMITIVES).left()
        primitive.vertices.any { abs(it.x.sixteenths) > MAX_COORD_SIXTEENTHS || abs(it.y.sixteenths) > MAX_COORD_SIXTEENTHS } ->
            SceneError.CoordinateOutOfRange(primitive.id).left()
        primitives.any { it.id == primitive.id } -> SceneError.DuplicateId(primitive.id).left()
        else -> SketchScene(primitives + primitive).right()
    }

    /** Swaps the primitive with [primitive]'s id for it, keeping draw order. */
    fun replace(primitive: SketchPrimitive): Either<SceneError, SketchScene> = when {
        primitive.vertices.any { abs(it.x.sixteenths) > MAX_COORD_SIXTEENTHS || abs(it.y.sixteenths) > MAX_COORD_SIXTEENTHS } ->
            SceneError.CoordinateOutOfRange(primitive.id).left()
        else -> SketchScene(primitives.map { if (it.id == primitive.id) primitive else it }).right()
    }

    /** The primitives for which [keep] is true; ids stay unique because they come from this scene. */
    fun filtered(keep: (SketchPrimitive) -> Boolean): SketchScene = SketchScene(primitives.filter(keep))

    fun remove(id: String): SketchScene = SketchScene(primitives.filterNot { it.id == id })

    /** Primitives whose bounding box meets [box]; only these get exact geometry tests. */
    fun candidatesIn(box: Aabb): List<SketchPrimitive> = primitives.filter { it.aabb.intersects(box) }

    companion object {
        const val MAX_PRIMITIVES = 10_000
        const val MAX_COORD_SIXTEENTHS: Long = 1L shl 28
        val EMPTY = SketchScene(emptyList())
    }
}
