package dev.stapler.stelekit.table

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableBackLadderTest {
    private fun next(s: TableSurface, st: LadderState, k: LadderKey) = TableBackLadder.next(s, st, k)

    private val edit = LadderState(mode = GridMode.Edit)
    private val nav = LadderState(mode = GridMode.Navigate)

    @Test
    fun ladder_should_ReturnFirstMatchingRung_When_EverySurfaceStateAndKey() {
        for (surface in TableSurface.entries) {
            for (key in LadderKey.entries) {
                assertEquals(LadderAction.DismissBanner, next(surface, edit.copy(conflictBannerOpen = true, menuOpen = true, imeVisible = true), key))
                assertEquals(LadderAction.CloseMenu, next(surface, edit.copy(menuOpen = true, imeVisible = true), key))
            }
            assertEquals(LadderAction.HideIme, next(surface, edit.copy(imeVisible = true), LadderKey.Back))
            assertEquals(LadderAction.Done, next(surface, edit, LadderKey.Back))
            assertEquals(LadderAction.CancelEdit, next(surface, edit, LadderKey.Esc))
            assertEquals(LadderAction.CancelEdit, next(surface, edit.copy(imeVisible = true), LadderKey.Esc))
            for (key in LadderKey.entries) {
                assertEquals(LadderAction.CollapseRange, next(surface, nav.copy(selectionSize = 4), key))
            }
        }
        // Inline single-cell Navigate and idle.
        assertEquals(LadderAction.CloseStrip, next(TableSurface.Inline, nav.copy(stripVisible = true), LadderKey.Back))
        assertEquals(LadderAction.CloseStrip, next(TableSurface.Inline, nav.copy(stripVisible = true), LadderKey.Esc))
        assertEquals(LadderAction.NotHandled, next(TableSurface.Inline, nav, LadderKey.Back))
        assertEquals(LadderAction.NotHandled, next(TableSurface.Inline, LadderState(), LadderKey.Back))
        assertEquals(LadderAction.NotHandled, next(TableSurface.Inline, LadderState(), LadderKey.Esc))
        // Sheet single-cell Navigate: Back closes, Esc never does (UX-93).
        assertEquals(LadderAction.CloseSheet, next(TableSurface.Sheet, nav, LadderKey.Back))
        assertEquals(LadderAction.NotHandled, next(TableSurface.Sheet, nav, LadderKey.Esc))
    }

    @Test
    fun ladder_should_TerminateWithinSixSteps_When_AppliedRepeatedly() = runTest {
        val modes = Arb.element<GridMode?>(null, GridMode.Navigate, GridMode.Edit)
        checkAll(
            Arb.boolean(), Arb.boolean(), Arb.boolean(), modes, Arb.int(1..9), Arb.boolean(),
            Arb.element(TableSurface.entries), Arb.element(LadderKey.entries),
        ) { banner, menu, ime, mode, size, strip, surface, key ->
            var state = LadderState(banner, menu, ime, mode, size, strip)
            var steps = 0
            while (true) {
                val action = TableBackLadder.next(surface, state, key)
                if (action == LadderAction.NotHandled || action == LadderAction.CloseSheet) {
                    val expectSheetBack = surface == TableSurface.Sheet && key == LadderKey.Back
                    assertEquals(expectSheetBack, action == LadderAction.CloseSheet, "terminal action for $surface $key")
                    break
                }
                state = TableBackLadder.after(state, action)
                steps++
                assertTrue(steps <= 6, "ladder did not terminate: $surface $key from $state")
            }
        }
    }
}
