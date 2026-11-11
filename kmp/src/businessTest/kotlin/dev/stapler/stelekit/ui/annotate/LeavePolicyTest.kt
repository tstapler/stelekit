// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import kotlin.test.Test
import kotlin.test.assertEquals

class LeavePolicyTest {
    @Test
    fun back_should_CloseWithoutDialog_When_OnlyCommitted() {
        assertEquals(BackOutcome.Close, backOutcome(unfinishedPoints = 0, saveFailed = false))
    }

    @Test
    fun back_should_ConfirmOnlyUnfinishedPoints_When_PointsInProgress() {
        assertEquals(BackOutcome.ConfirmDiscard(1), backOutcome(1, false))
        assertEquals("Discard 1 unfinished point?", discardDialogTitle(1))
        assertEquals("Discard 3 unfinished points?", discardDialogTitle(3))
    }

    @Test
    fun back_should_OfferRetry_When_LastSaveFailed() {
        assertEquals(BackOutcome.SaveFailed, backOutcome(2, true))
    }
}
