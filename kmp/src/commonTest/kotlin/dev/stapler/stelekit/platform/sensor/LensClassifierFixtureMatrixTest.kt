// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** U-E4-04: hand-built OEM CameraCharacteristics shapes (the owner-phone fixture replaces them after Task 4.1b). */
class LensClassifierFixtureMatrixTest {
    private fun info(
        id: String, focal: Double?, w: Double? = 6.4, h: Double? = 4.8, physical: List<String> = emptyList(),
        min: Double? = null, max: Double? = null,
    ) = CameraLensInfo(id, LensFacing.BACK, focal, w, h, physical, minZoomRatio = min, maxZoomRatio = max)

    @Test
    fun classify_should_ProduceExpectedChips_For_EachOemFixtureShape() {
        // (a) Pixel-style logical camera, physical ids not bind-tested: zoom stops only.
        val pixel = info("0", 6.9, 7.4, 5.6, listOf("2", "3", "4"), 0.5, 8.0)
        assertEquals(listOf(0.5, 1.0, 2.0, 5.0), LensClassifier.classify(listOf(pixel)).map { it.zoomRatio })

        // (b) Samsung-style separate back ids.
        val samsung = listOf(info("0", 5.4, 6.4, 4.8), info("2", 2.2, 4.8, 3.6), info("3", 7.0, 4.8, 3.6))
        assertEquals(
            listOf(LensKind.ULTRA_WIDE, LensKind.WIDE, LensKind.TELE),
            LensClassifier.classify(samsung).map { it.kind },
        )

        // (c) hidden ultra-wide behind one logical camera with only a zoom range.
        val hidden = info("0", 5.6, physical = listOf("1", "2"), min = 0.6, max = 4.0)
        assertTrue(LensClassifier.classify(listOf(hidden)).all { it.kind == LensKind.ZOOM_STOP })

        // (d) single lens, no zoom range: no chips.
        assertTrue(LensClassifier.classify(listOf(info("0", 5.6))).isEmpty())

        // (e) missing focal list or sensor size never throws.
        LensClassifier.classify(listOf(info("0", null, null, null), info("1", 4.0, null, null), info("2", 8.0, null, null)))
        LensClassifier.classify(listOf(info("0", null, null, null, min = 1.0, max = 4.0)))

        // (f) two back cameras with the same focal (depth or macro duplicates) collapse.
        val dup = listOf(info("0", 5.6, 6.4, 4.8), info("5", 5.6, 6.4, 4.8), info("2", 2.2, 4.8, 3.6))
        assertEquals(1, LensClassifier.classify(dup).count { it.kind == LensKind.WIDE })

        // (g) 35 mm equivalent from sensor size, within 5 percent of 43.2666 / diag * focal.
        val eq = LensClassifier.focal35mmEq(5.6, 6.4, 4.8)
        assertNotNull(eq)
        val expected = 5.6 * 43.2666 / 8.0
        assertTrue(abs(eq - expected) / expected < 0.05)
    }
}
