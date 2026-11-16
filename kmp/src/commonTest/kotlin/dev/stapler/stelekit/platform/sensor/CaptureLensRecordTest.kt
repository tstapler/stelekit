// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.model.LensRecordSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureLensRecordTest {
    private val selected = CameraLens(
        id = "0", cameraId = "0", physicalId = null, facing = LensFacing.BACK, focalLengthMm = 5.6, focal35mmEq = 26.0,
        zoomRatio = 1.0, labelRatio = 1.0, isLogicalMulti = true, kind = LensKind.WIDE, optical = true,
        label = "1x", secondaryLabel = null, hint = null,
    )
    private val shutterNs = 10_000_000_000L

    private fun snapshot(atNs: Long = shutterNs - 16_000_000L, focal: Double? = 5.6, physical: String? = "2", zoom: Double? = 1.0) =
        CaptureResultSnapshot(atNs, focal, physical, zoom)

    @Test
    fun record_should_UseCaptureResultAtShutter_NotUiChip_When_PreviewResultAvailable_AndSelectedLensWhen_Not() {
        val fromResult = CaptureLensRecordBuilder.build(selected, 1.0, snapshot(), shutterNs, exifFocalMm = 5.6)
        assertEquals("0", fromResult.cameraId)
        assertEquals("2", fromResult.physicalId)
        assertEquals(5.6, fromResult.focalLengthMm)
        assertEquals(1.0, fromResult.zoomRatio)
        assertEquals(LensRecordSource.PREVIEW_RESULT_AT_SHUTTER, fromResult.source)
        assertEquals(5.6, fromResult.exifFocalMm)

        val none = CaptureLensRecordBuilder.build(selected, 1.0, null, shutterNs, exifFocalMm = null)
        assertEquals(LensRecordSource.SELECTED_LENS, none.source)
        assertNull(none.physicalId)
        assertEquals(5.6, none.focalLengthMm)
        assertEquals(26.0, none.focal35mmEq)
    }

    @Test
    fun record_should_FallBackToSelectedLens_When_SnapshotIsStaleOrCarriesNoLensKeys() {
        val stale = snapshot(atNs = shutterNs - 500_000_000L)
        assertEquals(LensRecordSource.SELECTED_LENS, CaptureLensRecordBuilder.build(selected, 1.0, stale, shutterNs, null).source)
        val keyless = snapshot(focal = null, physical = null, zoom = 1.0)
        assertEquals(LensRecordSource.SELECTED_LENS, CaptureLensRecordBuilder.build(selected, 1.0, keyless, shutterNs, null).source)
    }

    @Test
    fun record_should_TakeZoomFromTheSnapshot_AndKeepTheEquivalentOnlyWhenTheFocalMatches() {
        val zoomed = CaptureLensRecordBuilder.build(selected, 2.0, snapshot(zoom = 2.0), shutterNs, null)
        assertEquals(2.0, zoomed.zoomRatio)
        assertEquals(26.0, zoomed.focal35mmEq)
        val otherLens = CaptureLensRecordBuilder.build(selected, 1.0, snapshot(focal = 2.2), shutterNs, null)
        assertNull(otherLens.focal35mmEq)
        assertEquals(LensRecordSource.SELECTED_LENS, otherLens.source)
    }

    @Test
    fun record_should_BeUntrusted_When_ActivePhysicalIdDiffersFromSelectedOrIsUnknown() {
        val ultraSelected = selected.copy(physicalId = "3")
        val switched = CaptureLensRecordBuilder.build(ultraSelected, 1.0, snapshot(focal = null, physical = "2"), shutterNs, null)
        assertEquals(LensRecordSource.SELECTED_LENS, switched.source)
        assertNull(switched.focal35mmEq)
        val unknown = CaptureLensRecordBuilder.build(ultraSelected, 1.0, snapshot(physical = null), shutterNs, null)
        assertEquals(LensRecordSource.SELECTED_LENS, unknown.source)
        assertNull(unknown.focal35mmEq)
        val noFocal = CaptureLensRecordBuilder.build(selected, 1.0, snapshot(focal = null, physical = "2"), shutterNs, null)
        assertEquals(LensRecordSource.SELECTED_LENS, noFocal.source)
        val match = CaptureLensRecordBuilder.build(ultraSelected, 1.0, snapshot(physical = "3"), shutterNs, 5.6)
        assertEquals(LensRecordSource.PREVIEW_RESULT_AT_SHUTTER, match.source)
        assertEquals(26.0, match.focal35mmEq)
    }

    @Test
    fun holder_should_ReturnTheLatestSnapshot() {
        val holder = CaptureResultHolder()
        assertNull(holder.latest())
        holder.update(snapshot(atNs = 1))
        holder.update(snapshot(atNs = 2))
        assertEquals(2L, holder.latest()?.timestampNs)
        holder.clear()
        assertNull(holder.latest())
    }
}
