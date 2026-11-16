// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraControlsStateTest {
    private fun stop(ratio: Double, optical: Boolean) = CameraLens(
        id = "0@$ratio", cameraId = "0", physicalId = null, facing = LensFacing.BACK, focalLengthMm = 5.6, focal35mmEq = null,
        zoomRatio = ratio, labelRatio = ratio, isLogicalMulti = true, kind = LensKind.ZOOM_STOP, optical = optical,
        label = "${ratio}x", secondaryLabel = null, hint = null,
    )

    @Test
    fun flashCycle_should_StepOffAutoOnPerPress_AndSkipWhenNoFlashUnit() {
        assertEquals(FlashMode.AUTO, FlashMode.OFF.next(hasFlash = true))
        assertEquals(FlashMode.ON, FlashMode.AUTO.next(hasFlash = true))
        assertEquals(FlashMode.OFF, FlashMode.ON.next(hasFlash = true))
        assertEquals(FlashMode.AUTO, FlashMode.AUTO.next(hasFlash = false))
    }

    @Test
    fun pinch_should_HighlightNearestOpticalChipOnly() {
        val tracker = PinchChipTracker(listOf(stop(0.6, true), stop(1.0, true), stop(2.0, false), stop(5.0, false)))
        assertEquals(1.0, tracker.onZoom(1.0)?.zoomRatio)
        assertEquals(0.6, tracker.onZoom(0.6)?.zoomRatio)
        assertEquals(1.0, tracker.onZoom(4.0)?.zoomRatio, "digital stops never claim the highlight")
    }

    @Test
    fun pinchHovering_should_ChangeTheHighlightAtMostOnce() {
        val tracker = PinchChipTracker(listOf(stop(1.0, true), stop(2.0, true)))
        tracker.onZoom(1.0)
        var changes = 0
        var last = tracker.highlighted
        listOf(1.47, 1.53, 1.48, 1.52, 1.5).forEach {
            val now = tracker.onZoom(it)
            if (now != last) changes++
            last = now
        }
        assertEquals(0, changes)
        assertEquals(2.0, tracker.onZoom(1.9)?.zoomRatio)
    }
}
