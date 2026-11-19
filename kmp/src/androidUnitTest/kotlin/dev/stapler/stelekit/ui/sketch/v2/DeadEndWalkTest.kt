// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.runtime.telemetry.EditorAfter
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.ErrorAction
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchErrorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 6.2.7, Task 6.2.7b (UX-75): no error state is a dead end. The fake host shows each state and activates each of
 * its actions; the editor is left usable (no modal, tools enabled), read-only with its banner, or closed. The walk of
 * ux.md sections 1-16 by a person is the owner checklist row in `owner-verification.md`.
 */
class DeadEndWalkTest {
    private class FakeHost {
        var shown: SketchErrorState? = null
        var editor: EditorAfter? = null

        fun show(state: SketchErrorState) {
            shown = state
            editor = null
        }

        fun activate(action: ErrorAction) {
            val state = checkNotNull(shown) { "no error state is showing" }
            check(action in state.actions) { "$action is not offered for $state" }
            shown = null
            editor = action.after
        }
    }

    private val covered = SketchErrorState.entries.filter { it.owner !in SketchErrorState.parkedOwners }

    @Test
    fun ux75_everyErrorState_should_ExposeActionOrDismissThatRestoresEditor() {
        val host = FakeHost()
        for (state in covered) {
            assertTrue(state.actions.isNotEmpty(), "$state is a dead end: no action and no dismissal")
            for (action in state.actions) {
                host.show(state)
                host.activate(action)
                assertNull(host.shown, "$state / $action left a modal showing")
                assertTrue(host.editor != null, "$state / $action did not settle the editor")
            }
        }
    }

    @Test
    fun everyState_should_OfferAWayToUsableOrLeave_NotOnlyAReadOnlyDetour() {
        for (state in covered) {
            val ends = state.actions.map { it.after }
            assertTrue(EditorAfter.UsableEditor in ends || EditorAfter.LeavesEditor in ends, "$state only ever leads to read-only")
        }
    }

    @Test
    fun aStateThatKeepsUnsavedWork_should_NeverOfferDiscardAsItsOnlyExit() {
        assertEquals(ErrorAction.Retry, SketchErrorState.SaveFailed.actions.first(), "Retry is the default and focused first")
        assertTrue(ErrorAction.ExportCopy in SketchErrorState.SaveFailed.actions)
    }
}
