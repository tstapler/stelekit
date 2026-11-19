// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import dev.stapler.stelekit.ui.sketch.v2.migration.InteractionState
import dev.stapler.stelekit.ui.sketch.v2.migration.UpgradeConsentGate
import dev.stapler.stelekit.ui.sketch.v2.migration.UpgradeRowFit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 1.4.6i (UX-104, UX-123, UX-126): row versus sheet is the computed matrix, and the sheet opens by the open rule. */
class UpgradeChipConsentSheetTest {
    private val scales = listOf(1.0, 1.15, 1.3, 1.5, 2.0)

    @Test
    fun theModel_should_MatchTheComputedLineMatrixOfUx() {
        // ux.md section 1 computed block: 320 dp 2 / 3 / 4 / 4 / 5+, 360 dp 2 / 2 / 2 / 2 / 4 (null = a word is wider than the line).
        assertEquals(listOf<Int?>(2, 3, 4, 4, null), scales.map { UpgradeRowFit.modelLines(320, it) })
        assertEquals(listOf<Int?>(2, 2, 2, 2, 4), scales.map { UpgradeRowFit.modelLines(360, it) })
    }

    @Test
    fun theSheetReplacesTheRow_ExactlyFromTheDocumentedScales() {
        assertEquals(listOf(false, true, true, true, true), scales.map { UpgradeRowFit.modelSheetReplacesRow(320, it) })
        assertEquals(listOf(false, false, false, false, true), scales.map { UpgradeRowFit.modelSheetReplacesRow(360, it) })
        assertFalse(UpgradeRowFit.modelSheetReplacesRow(320, 1.10))
        assertTrue(UpgradeRowFit.modelSheetReplacesRow(320, 1.11))
        assertFalse(UpgradeRowFit.modelSheetReplacesRow(360, 1.51))
        assertTrue(UpgradeRowFit.modelSheetReplacesRow(360, 1.52))
    }

    @Test
    fun aMeasuredWrap_should_DecideAtRuntime() {
        assertFalse(UpgradeRowFit.sheetReplacesRow(2))
        assertTrue(UpgradeRowFit.sheetReplacesRow(3))
        assertTrue(UpgradeRowFit.sheetReplacesRow(null))
    }

    private var day = 100L
    private val acks = ProjectAckStore(MapSettings()) { "g1" }
    private val gate = UpgradeConsentGate(acks) { day }

    @Test
    fun theSheet_should_NotOpenWhileTheUserIsBusy_AndWaitsInsteadOfBeingLost() {
        assertFalse(gate.mayOpen("kitchen", InteractionState(midGesture = true)))
        assertFalse(gate.mayOpen("kitchen", InteractionState(textEntry = true)))
        assertFalse(gate.mayOpen("kitchen", InteractionState(modalOpen = true)))
        assertTrue(gate.mayOpen("kitchen", InteractionState()), "nothing was recorded while it waited")
    }

    @Test
    fun theSheet_should_OpenOncePerProjectPerDay() {
        assertNull(acks.consentSheetDay("kitchen"))
        gate.opened("kitchen")
        assertFalse(gate.mayOpen("kitchen", InteractionState()))
        assertTrue(gate.mayOpen("pantry", InteractionState()), "another project has its own day")
        day += 1
        assertTrue(gate.mayOpen("kitchen", InteractionState()), "a later day may open it once more")
    }

    @Test
    fun aDismissal_should_KeepTheSheetClosedForTheProjectThisSession() {
        gate.dismissed("kitchen")
        day += 1
        assertFalse(gate.mayOpen("kitchen", InteractionState()))
        gate.newSession()
        assertTrue(gate.mayOpen("kitchen", InteractionState()), "a new app process may show it again")
    }
}
