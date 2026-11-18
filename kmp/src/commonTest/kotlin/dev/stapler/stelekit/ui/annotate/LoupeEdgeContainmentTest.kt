// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.float
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoupeEdgeContainmentTest {
    @Test
    fun loupeZoomFactor_should_StayWithin3xTo16x_AcrossViewScaleRange() = runTest {
        checkAll(Arb.float(MIN_VIEW_SCALE, MAX_VIEW_SCALE).filter { it.isFinite() }) { s ->
            val z = loupeZoomFactor(s)
            assertTrue(z >= 3f && z <= 16f, "zoom $z at scale $s")
        }
        assertEquals(3f, loupeZoomFactor(MIN_VIEW_SCALE))
    }

    @Test
    fun loupeOffset_should_StayInsideViewport_When_TouchIsAtOrBeyondEdges() = runTest {
        val loupe = 120f
        val margin = 16f
        checkAll(Arb.float(-200f, 1200f).filter { it.isFinite() }, Arb.float(400f, 1000f).filter { it.isFinite() }) { touch, extent ->
            val x = clampLoupeOffset(touch - loupe / 2f, extent, loupe, margin)
            assertTrue(x >= margin && x + loupe <= extent - margin + 1e-3f, "x=$x extent=$extent")
        }
    }
}
