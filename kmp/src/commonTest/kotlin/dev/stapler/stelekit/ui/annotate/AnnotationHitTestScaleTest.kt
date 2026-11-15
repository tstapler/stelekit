// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.float
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnotationHitTestScaleTest {
    private val size = IntSize(1000, 1000)
    private val uuid = "00000000-0000-0000-0000-000000000001"

    // Mirrors the dp values AnnotationGestureLayer converts with the screen density.
    private val vertexRadiusDp = 28f
    private val edgeRadiusDp = 20f
    private val minGrabDp = 24f
    private val density = 2.75f

    /** The image centred in a 1000 px viewport at [scale], as the transform clamp leaves it below Fit. */
    private fun centred(scale: Float): AnnotationViewTransform {
        val off = (1000f - 1000f * scale) / 2f
        return AnnotationViewTransform(scale, off, off)
    }

    private fun annotation(vararg pts: NormalizedPoint) = MeasurementAnnotation(
        uuid = uuid,
        imageUuid = "00000000-0000-0000-0000-000000000002",
        annotationType = AnnotationType.DISTANCE,
        normalizedPoints = pts.toList(),
    )

    private fun hit(a: MeasurementAnnotation, at: Offset, view: AnnotationViewTransform) =
        hitTestAnnotations(listOf(a), at, size, vertexRadiusDp * density, edgeRadiusDp * density, view)

    @Test
    fun hitTest_should_UseSame24dpScreenRadius_At025And1_AndGrabEdgeHandle() = runTest {
        val a = annotation(NormalizedPoint(0.5, 0.5), NormalizedPoint(1.0, 1.0))
        checkAll(Arb.element(0.25f, 1f), Arb.double(0.0, 6.283).filter { it.isFinite() }) { scale, angle ->
            val view = centred(scale)
            val vertex = view.toView(NormalizedPoint(0.5, 0.5).toScreen(size))
            val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            assertEquals(AnnotationHit.Vertex(uuid, 0), hit(a, vertex + dir * (minGrabDp * density), view), "24 dp grabs at scale $scale")
            val far = hit(a, vertex + dir * ((vertexRadiusDp + 10f) * density), view)
            assertTrue(far != AnnotationHit.Vertex(uuid, 0), "38 dp must not grab the vertex at scale $scale")
        }
    }

    @Test
    fun hitRadius_should_Be24dpOrMore_At025And100Percent() {
        assertTrue(vertexRadiusDp >= minGrabDp)
        for (scale in listOf(0.25f, 1f)) {
            val a = annotation(NormalizedPoint(0.5, 0.5), NormalizedPoint(1.0, 1.0))
            val view = centred(scale)
            val v = view.toView(NormalizedPoint(0.5, 0.5).toScreen(size))
            assertEquals(AnnotationHit.Vertex(uuid, 0), hit(a, v + Offset(0f, minGrabDp * density), view))
        }
    }

    @Test
    fun handleAtImageEdge_should_BeGrabbable_From_Pasteboard_At025() = runTest {
        val a = annotation(NormalizedPoint(0.0, 0.5), NormalizedPoint(0.5, 0.5))
        val view = centred(0.25f)
        val edge = view.toView(NormalizedPoint(0.0, 0.5).toScreen(size))
        checkAll(Arb.float(0f, minGrabDp * density).filter { it.isFinite() }) { outwardPx ->
            assertEquals(AnnotationHit.Vertex(uuid, 0), hit(a, edge - Offset(outwardPx, 0f), view))
        }
        assertNull(hit(a, edge - Offset(vertexRadiusDp * density + 40f, 300f), view))
    }
}
