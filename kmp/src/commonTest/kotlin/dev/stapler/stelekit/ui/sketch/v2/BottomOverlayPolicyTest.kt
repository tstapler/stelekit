// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import kotlin.test.Test
import kotlin.test.assertEquals

/** The chip row goes beside the view cluster only when all of it fits there; one pixel short raises it. */
class BottomOverlayPolicyTest {
    @Test
    fun placement_should_GoBeside_OnlyWhenTheWholeRowFits() {
        assertEquals(ChipRowPlacement.Beside, BottomOverlayPolicy.placement(chipsNaturalPx = 300, besideRoomPx = 300))
        assertEquals(ChipRowPlacement.Raised, BottomOverlayPolicy.placement(chipsNaturalPx = 301, besideRoomPx = 300))
        assertEquals(ChipRowPlacement.Beside, BottomOverlayPolicy.placement(chipsNaturalPx = 0, besideRoomPx = 0), "no chips never raise")
    }
}
