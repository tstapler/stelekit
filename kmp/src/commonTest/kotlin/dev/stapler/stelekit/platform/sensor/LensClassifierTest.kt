// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** U-E4-01..03 and U-E4-05: classification from recorded-shape fixtures; thresholds are provisional until Task 4.1b. */
class LensClassifierTest {
    private fun back(id: String, focal: Double?, sensorW: Double? = 6.4, sensorH: Double? = 4.8, physical: List<String> = emptyList()) =
        CameraLensInfo(
            cameraId = id, facing = LensFacing.BACK, focalLengthMm = focal,
            sensorWidthMm = sensorW, sensorHeightMm = sensorH, physicalIds = physical,
        )

    private val ownerPhone = listOf(
        back("2", 2.2, 4.8, 3.6),
        back("0", 5.6, 6.4, 4.8),
        back("3", 9.0, 5.6, 4.2),
        CameraLensInfo("1", LensFacing.FRONT, 3.0, 4.0, 3.0),
    )

    @Test
    fun classify_should_ReturnUltraWideWideTeleOrderedByFocal_When_OwnerPhoneFixture() {
        val lenses = LensClassifier.classify(ownerPhone)
        assertEquals(listOf(LensKind.ULTRA_WIDE, LensKind.WIDE, LensKind.TELE), lenses.map { it.kind })
        assertEquals(listOf("2", "0", "3"), lenses.map { it.cameraId })
        assertEquals("1x", lenses[1].label)
        assertTrue(lenses.all { it.optical })
        assertTrue(lenses[0].secondaryLabel!!.endsWith("mm eq."), lenses[0].secondaryLabel)
        assertTrue(lenses[0].labelRatio < 1.0 && lenses[2].labelRatio > 1.0)
    }

    @Test
    fun classify_should_ListZoomStops_And_FlagDigitalAboveLongestFocal_When_OneLogicalCamera06To8() {
        val logical = back("0", 5.6, physical = listOf("2", "3")).copy(minZoomRatio = 0.6, maxZoomRatio = 8.0)
        val lenses = LensClassifier.classify(listOf(logical))
        assertEquals(listOf(0.6, 1.0, 2.0, 5.0), lenses.map { it.zoomRatio })
        assertTrue(lenses.all { it.kind == LensKind.ZOOM_STOP })
        assertEquals(listOf(true, true, false, false), lenses.map { it.optical })
        assertTrue(lenses.filter { !it.optical }.all { it.hint == "Digital" })
        assertTrue(lenses.none { it.label == "0" })
    }

    @Test
    fun classify_should_ExcludeFrontAndCollapseDuplicates() {
        val lenses = LensClassifier.classify(ownerPhone + back("4", 2.2, 4.8, 3.6))
        assertTrue(lenses.none { it.facing == LensFacing.FRONT })
        assertEquals(3, lenses.size)
        assertEquals(1, lenses.count { it.kind == LensKind.ULTRA_WIDE })
    }

    @Test
    fun classify_should_UsePhysicalIdLens_Only_When_BindTested() {
        val logical = back("0", 5.6, physical = listOf("2", "3")).copy(
            physicalFocalsMm = mapOf("2" to 2.2, "3" to 9.0), minZoomRatio = 0.6, maxZoomRatio = 8.0,
        )
        assertTrue(LensClassifier.classify(listOf(logical)).all { it.kind == LensKind.ZOOM_STOP })
        val bound = LensClassifier.classify(listOf(logical.copy(boundPhysicalIds = setOf("2", "3"))))
        assertEquals(listOf("2", null, "3"), bound.map { it.physicalId })
        assertEquals(listOf(LensKind.ULTRA_WIDE, LensKind.WIDE, LensKind.TELE), bound.map { it.kind })
    }

    @Test
    fun classify_should_ReturnNothing_When_SingleLensWithoutZoomRange() {
        assertTrue(LensClassifier.classify(listOf(back("0", 5.6))).isEmpty())
    }

    @Test
    fun classify_should_BeOrderIndependentAndNeverContainFront_ForAnyShuffle() = runTest {
        val all = ownerPhone + back("4", 2.2, 4.8, 3.6) + back("5", null)
        val expected = LensClassifier.classify(all)
        checkAll(Arb.int(0, 10_000)) { seed ->
            val out = LensClassifier.classify(all.shuffled(Random(seed)))
            assertEquals(expected, out)
            assertFalse(out.any { it.facing == LensFacing.FRONT })
        }
    }
}
