// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.ui.placement.PxOffset
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.float
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnotationPointClampTest {
    private val size = IntSize(800, 600)

    @Test
    fun placePoint_should_StoreExactly0Or1_When_FingerOutsideImageOnPasteboard() = runTest {
        checkAll(Arb.float(-5000f, 5000f).filter { it.isFinite() }, Arb.float(-5000f, 5000f).filter { it.isFinite() }) { x, y ->
            val p = Offset(x, y).toNormalized(size)!!
            assertTrue(p.x in 0.0..1.0 && p.y in 0.0..1.0)
            if (x <= 0f) assertTrue(p.x == 0.0)
            if (x >= size.width) assertTrue(p.x == 1.0)
            if (y <= 0f) assertTrue(p.y == 0.0)
            if (y >= size.height) assertTrue(p.y == 1.0)
        }
    }

    @Test
    fun touchOnPasteboardAtQuarterScale_should_PlaceOnTheNearestEdge() {
        val view = AnnotationViewTransform(0.25f, 300f, 225f)
        val canvas = view.toCanvas(Offset(10f, 10f))
        assertTrue(canvas.x < 0f && canvas.y < 0f)
        assertEquals(NormalizedPoint(0.0, 0.0), canvas.toNormalized(size))
    }

    @Test
    fun toCanvas_should_NotClampTheRawTouch() {
        val view = AnnotationViewTransform(0.25f, 300f, 225f)
        val raw = view.toCanvas(Offset(10f, 10f))
        assertEquals(10f, view.toView(raw).x, 1e-3f)
        assertEquals(10f, view.toView(raw).y, 1e-3f)
    }

    @Test
    fun placementSpace_should_PinOutOfRangeToExactEdges() {
        val space = AnnotationPlacementSpace(800, 600)
        assertEquals(NormalizedPoint(0.0, 1.0), space.shift(NormalizedPoint(0.01, 0.99), PxOffset(-9999, 9999)))
    }
}
