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

class LoupeLayoutPropertyTest {
    @Test
    fun panorama_canvas_does_not_throw_and_centres_loupe() {
        // 360 x 90 dp canvas, 120 dp loupe, 16 dp margins: vertical range is empty.
        val y = clampLoupeOffset(value = 70f, extent = 90f, loupeSize = 120f, margin = 16f)
        assertEquals(-15f, y)
        assertEquals(100f, clampLoupeOffset(value = 100f, extent = 360f, loupeSize = 120f, margin = 16f))
    }

    @Test
    fun roomy_canvas_clamps_to_margins() {
        assertEquals(16f, clampLoupeOffset(-50f, 400f, 120f, 16f))
        assertEquals(264f, clampLoupeOffset(999f, 400f, 120f, 16f))
    }

    @Test
    fun never_throws_and_is_finite_for_any_canvas_width_1_to_4000() = runTest {
        checkAll(
            Arb.float(-8000f, 8000f).filter { it.isFinite() },
            Arb.float(1f, 4000f).filter { it.isFinite() },
            Arb.float(1f, 400f).filter { it.isFinite() },
            Arb.float(0f, 64f).filter { it.isFinite() },
        ) { value, extent, loupe, margin ->
            val r = clampLoupeOffset(value, extent, loupe, margin)
            assertTrue(r.isFinite())
            val max = extent - loupe - margin
            if (max >= margin) assertTrue(r >= margin && r <= max)
        }
    }
}
