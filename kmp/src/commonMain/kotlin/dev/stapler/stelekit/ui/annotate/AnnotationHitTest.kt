package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.math.hypot

/** What a SELECT-tool touch landed on. */
sealed interface AnnotationHit {
    val uuid: String

    /** A vertex handle — dragging moves just that point. */
    data class Vertex(override val uuid: String, val index: Int) : AnnotationHit

    /** The line/outline between vertices — dragging moves the whole annotation. */
    data class Body(override val uuid: String) : AnnotationHit
}

/**
 * Finds the annotation under [position]. Vertices win over edges so a point at the end of a line is
 * grabbable on its own; among candidates the nearest one wins. [position] and the radii are screen
 * pixels in the canvas's local space; annotation points are mapped through [view] first, so grab
 * targets keep a constant on-screen size at any zoom.
 */
fun hitTestAnnotations(
    annotations: List<MeasurementAnnotation>,
    position: Offset,
    canvasSize: IntSize,
    vertexRadiusPx: Float,
    edgeRadiusPx: Float,
    view: AnnotationViewTransform = AnnotationViewTransform.Fit,
): AnnotationHit? {
    var bestVertex: AnnotationHit.Vertex? = null
    var bestVertexDist = vertexRadiusPx
    var bestEdge: AnnotationHit.Body? = null
    var bestEdgeDist = edgeRadiusPx

    for (annotation in annotations) {
        val pts = annotation.normalizedPoints.map { view.toView(it.toScreen(canvasSize)) }
        pts.forEachIndexed { i, p ->
            val d = (p - position).getDistance()
            if (d <= bestVertexDist) {
                bestVertexDist = d
                bestVertex = AnnotationHit.Vertex(annotation.uuid, i)
            }
        }
        val closed = annotation.annotationType == AnnotationType.AREA
        val segmentCount = if (closed) pts.size else pts.size - 1
        for (i in 0 until segmentCount) {
            val d = distanceToSegment(position, pts[i], pts[(i + 1) % pts.size])
            if (d <= bestEdgeDist) {
                bestEdgeDist = d
                bestEdge = AnnotationHit.Body(annotation.uuid)
            }
        }
    }
    return bestVertex ?: bestEdge
}

/** Shifts every point by ([dx], [dy]) in normalized space, clamped so none leaves [0,1]. */
internal fun translateClamped(points: List<NormalizedPoint>, dx: Double, dy: Double): List<NormalizedPoint> {
    if (points.isEmpty()) return points
    val clampedDx = dx.coerceIn(-points.minOf { it.x }, 1.0 - points.maxOf { it.x })
    val clampedDy = dy.coerceIn(-points.minOf { it.y }, 1.0 - points.maxOf { it.y })
    return points.map { NormalizedPoint(it.x + clampedDx, it.y + clampedDy) }
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val lenSq = abx * abx + aby * aby
    if (lenSq == 0f) return hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
    return hypot(p.x - (a.x + t * abx), p.y - (a.y + t * aby))
}
