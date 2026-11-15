// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FocalSourceResolverTest {
    private val w = 4000.0
    private val h = 3000.0

    @Test
    fun resolver_should_RankSourcesAndNeverEnableRefinement_When_ExifOnlyOrProfile() {
        val record = LensRecordFocal(focal35mmEq = 26.0, zoomRatio = 1.0)

        val user = FocalSourceResolver.resolve(30.0, record, true, 24.0)
        assertEquals(FocalResolution(FocalSource.USER_CONFIRMED, 30.0), user)

        val sidecar = FocalSourceResolver.resolve(null, record, true, 24.0)
        assertEquals(FocalResolution(FocalSource.SIDECAR_LENS_RECORD, 26.0), sidecar)

        val profile = FocalSourceResolver.resolve(null, null, true, 24.0)
        assertEquals(FocalResolution(FocalSource.PROFILE, 24.0), profile)

        val exif = FocalSourceResolver.resolve(null, null, false, 24.0)
        assertEquals(FocalResolution(FocalSource.EXIF_ONLY, 24.0), exif)
        assertEquals(FocalResolution(FocalSource.EXIF_ONLY, null), FocalSourceResolver.resolve(null, null, false, null))

        listOf(profile, exif).forEach { assertNull(FocalSourceResolver.focalPixelsOrNull(it, true, w, h)) }
        listOf(user, sidecar).forEach { assertNotNull(FocalSourceResolver.focalPixelsOrNull(it, true, w, h)) }
    }

    @Test
    fun refinement_should_ShipOff_AndUseTheHeightOffsetFocalPixels_WhenEnabled() {
        val user = FocalSourceResolver.resolve(30.0, null, false, null)
        assertNull(FocalSourceResolver.focalPixelsOrNull(user, false, w, h))
        assertEquals(HeightOffset.focalPixels(30.0, w, h), FocalSourceResolver.focalPixelsOrNull(user, true, w, h))
    }

    @Test
    fun lensRecordAtZoomOtherThanOne_should_BeIgnored_AsACroppedFrame() {
        val zoomed = LensRecordFocal(26.0, 2.0)
        assertEquals(FocalSource.EXIF_ONLY, FocalSourceResolver.resolve(null, zoomed, false, 24.0).source)
        assertEquals(FocalSource.PROFILE, FocalSourceResolver.resolve(null, zoomed, true, 24.0).source)
    }

    @Test
    fun invalidFocalValues_should_NotWinTheRanking() {
        assertEquals(FocalSource.EXIF_ONLY, FocalSourceResolver.resolve(0.0, null, false, 24.0).source)
        assertEquals(FocalSource.EXIF_ONLY, FocalSourceResolver.resolve(Double.NaN, LensRecordFocal(-5.0, 1.0), false, null).source)
    }
}
