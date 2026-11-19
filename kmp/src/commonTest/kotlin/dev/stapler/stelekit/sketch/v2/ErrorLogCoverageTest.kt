// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchErrorState
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLogSink
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchErrorStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 6.2.7, Task 6.2.7a (UX-71): every user-visible error state logs exactly one `sketch.*` line, with ids and tokens
 * only. The enum is the closed list: a state a later story adds shows up here without a test edit.
 */
class ErrorLogCoverageTest {
    private val covered = SketchErrorState.entries.filter { it.owner !in SketchErrorState.parkedOwners }

    /** The smallest fake host: raising a state logs it, as the editor does when the state reaches the screen. */
    private class FakeHost(private val log: SketchLog) {
        fun raise(state: SketchErrorState, constraintIds: List<String> = emptyList()) = log.errorState(state, constraintIds)
    }

    private fun linesFor(state: SketchErrorState, ids: List<String> = emptyList()): List<String> {
        val lines = mutableListOf<String>()
        FakeHost(SketchLog(SketchLogSink { lines += it })).raise(state, ids)
        return lines
    }

    @Test
    fun ux71_everyUserVisibleErrorState_should_EmitOneSketchLogLine() {
        for (state in covered) {
            val lines = linesFor(state)
            assertEquals(1, lines.size, "$state must emit exactly one line, got $lines")
            assertTrue(lines.single().startsWith(state.logName + " "), "$state: ${lines.single()}")
            assertTrue(state.logName.startsWith("sketch."), "$state: ${state.logName}")
            assertTrue("reason=${state.reasonCode}" in lines.single(), "$state: ${lines.single()}")
        }
    }

    @Test
    fun theLine_should_CarryNoElementText_When_ConstraintIdsAreNotTokens() {
        val secret = "Wall A, 6 in"
        val line = linesFor(SketchErrorState.ConstraintConflict, listOf("c1", secret)).single()
        assertFalse("Wall" in line || "6 in" in line, line)
        assertEquals("sketch.solve.failed reason=conflict ids=c1,?", line)
    }

    @Test
    fun statesSharingALine_should_BeToldApartByTheirReasonCode() {
        val keys = covered.map { it.logName to it.reasonCode }
        assertEquals(keys.size, keys.toSet().size, "two states log the same line and reason: $keys")
    }

    @Test
    fun everyState_should_HaveUserText_And_EveryActionALabel() {
        for (state in SketchErrorState.entries) {
            assertTrue(SketchErrorStrings.message(state).isNotBlank(), "$state has no message")
            for (action in state.actions) assertTrue(SketchErrorStrings.label(action).isNotBlank(), "$state: $action has no label")
        }
    }

    @Test
    fun theClosedList_should_BeCovered_And_ParkedStatesListed() {
        val listed = setOf(
            "LoadCorrupt", "SheetBusy", "NewerSchema", "SaveFailed", "SaveConflict", "LowMemory", "ImportInvalid", "ImportTooLarge",
            "ImportOverCap", "ImportCancelled", "ImportCrashed", "ExportTooLarge", "ExportEncodeFailed", "ExportWriteFailed",
            "ConversionFailed", "HasTechnicalData", "ConstraintConflict", "ConstraintNoConvergence", "ConstraintCapReached",
            "ElementCapReached", "LayerLocked", "CopyRefused",
        )
        assertTrue(listed.all { name -> SketchErrorState.entries.any { it.name == name } }, "a state of the plan's list is missing")
        assertEquals(SketchErrorState.entries.filter { it.owner in SketchErrorState.parkedOwners }, SketchErrorState.entries - covered.toSet())
    }
}
