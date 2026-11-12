// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TiltAssessmentTest {
    @Test
    fun tiersFollowTheAdr002Thresholds() {
        assertEquals(TiltTier.UNKNOWN, TiltAssessment.tierOf(null, null))
        assertEquals(TiltTier.UNKNOWN, TiltAssessment.tierOf(Double.NaN, null))
        assertEquals(TiltTier.FLAT, TiltAssessment.tierOf(0.0, 0.0))
        assertEquals(TiltTier.FLAT, TiltAssessment.tierOf(3.0, -3.0))
        assertEquals(TiltTier.MILD, TiltAssessment.tierOf(3.1, 0.0))
        assertEquals(TiltTier.MILD, TiltAssessment.tierOf(15.0, 10.0))
        assertEquals(TiltTier.EXCESSIVE, TiltAssessment.tierOf(15.1, 0.0))
        assertEquals(TiltTier.EXCESSIVE, TiltAssessment.tierOf(0.0, -10.1))
    }

    @Test
    fun oneKnownAngleIsEnough() {
        assertEquals(TiltTier.FLAT, TiltAssessment.tierOf(2.0, null))
        assertEquals(TiltTier.MILD, TiltAssessment.tierOf(null, 5.0))
        assertEquals(5.0, TiltAssessment.worstDegrees(null, -5.0))
        assertNull(TiltAssessment.worstDegrees(null, null))
    }

    @Test
    fun fiveDegreesMatchesTheDocumentedErrorBudget() {
        // design.md: 5 degrees tilt, object 25 cm sideways at 60 cm range is about 3.6%.
        assertEquals(3.5, TiltAssessment.acrossFramePercent(5.0), 0.3)
    }

    /** The banner predicate that used to live in AnnotationEditorScreen. */
    private fun legacyExcessive(pitch: Double?, roll: Double?) =
        (pitch != null && abs(pitch) > 15.0) || (roll != null && abs(roll) > 10.0)

    @Test
    fun excessiveTierMatchesTheLegacyBannerPredicate() = runTest {
        checkAll(Arb.int(-400, 400), Arb.int(-400, 400), Arb.int(0, 3)) { p, r, mask ->
            val pitch = (p / 10.0).takeIf { mask and 1 == 0 }
            val roll = (r / 10.0).takeIf { mask and 2 == 0 }
            assertEquals(legacyExcessive(pitch, roll), TiltAssessment.tierOf(pitch, roll) == TiltTier.EXCESSIVE)
        }
    }

    @Test
    fun errorGrowsWithTilt() = runTest {
        checkAll(Arb.int(0, 80), Arb.int(0, 80)) { a, b ->
            val lo = minOf(a, b) / 4.0
            val hi = maxOf(a, b) / 4.0
            assertTrue(TiltAssessment.acrossFramePercent(lo) <= TiltAssessment.acrossFramePercent(hi))
        }
    }
}
