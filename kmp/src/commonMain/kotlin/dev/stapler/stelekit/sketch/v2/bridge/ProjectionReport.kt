// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.bridge

import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import kotlin.math.abs
import kotlin.math.floor

/** Which extent of a projected primitive a dimension reads: a rectangle's or line's horizontal or vertical span. */
enum class Extent { Width, Height }

/** One measured piece of a named dimension: [element]'s [extent] as v1 sees it. */
data class MeasuredPart(val element: ElementId, val extent: Extent)

/**
 * A named dimension a converted sheet's geometry has to keep (Story 4.2.4, metric 6). [parts] are summed, so a `Sum`
 * dimension lists every part and a plain one lists one; [requiredSixteenths] is the value the dimension must have.
 */
data class DimensionExpectation(val name: String, val parts: List<MeasuredPart>, val requiredSixteenths: Long)

/** A dimension whose projected geometry measures [measured] sixteenths where [required] were needed; [offBy] is never negative. */
data class DimensionMismatch(val name: String, val required: Long, val measured: Long) {
    val offBy: Long get() = abs(measured - required)
}

/**
 * What projecting a solved sheet onto v1 primitives cost: the [mismatches] against the named dimensions, how many elements
 * were [rounded] to sixteenths (their solved value was not whole) and how many were [dropped] because v1 cannot hold them.
 * Mismatches are reported, never snapped: nothing here changes a primitive.
 */
data class ProjectionReport(val mismatches: List<DimensionMismatch>, val rounded: Int, val dropped: Int)

/** Rounding-once projection reports (Task 4.2.4a): rounding happens in `V2ToV1` only; this reads its result and says what it cost. */
object ProjectionReports {
    fun of(projection: Projection, elements: List<SceneElement>, expectations: List<DimensionExpectation>): ProjectionReport =
        ProjectionReport(mismatches(projection.primitives, expectations), roundedCount(elements, projection), projection.droppedCount)

    /** The dimensions whose summed projected extents differ from the value they need; one that cannot be measured (a missing or dropped element) is left out. */
    fun mismatches(primitives: List<SketchPrimitive>, expectations: List<DimensionExpectation>): List<DimensionMismatch> {
        val byId = primitives.associateBy { it.id }
        return expectations.mapNotNull { e ->
            val measured = e.parts.sumOf { part -> extentOf(byId[part.element.value], part.extent) ?: return@mapNotNull null }
            if (measured == e.requiredSixteenths) null else DimensionMismatch(e.name, e.requiredSixteenths, measured)
        }
    }

    private fun extentOf(primitive: SketchPrimitive?, extent: Extent): Long? {
        val (a, b) = when (primitive) {
            is SketchPrimitive.Rect -> primitive.a to primitive.b
            is SketchPrimitive.Line -> primitive.a to primitive.b
            else -> return null
        }
        return when (extent) {
            Extent.Width -> abs(b.x.sixteenths - a.x.sixteenths)
            Extent.Height -> abs(b.y.sixteenths - a.y.sixteenths)
        }
    }

    /** Projected elements that had a coordinate between two sixteenths, so rounding changed them. */
    private fun roundedCount(elements: List<SceneElement>, projection: Projection): Int {
        val dropped = projection.dropped.mapTo(HashSet()) { it.id }
        return elements.count { it.id !in dropped && hasFraction(it) }
    }

    private fun hasFraction(e: SceneElement): Boolean = when (e) {
        is RectElement -> listOf(e.x.value, e.y.value, e.x.value + e.width.value, e.y.value + e.height.value).any(::fractional)
        is LineElement -> e.points.any { fractional(it.x.value) || fractional(it.y.value) }
        else -> false
    }

    private fun fractional(v: Double): Boolean = v != floor(v)
}
