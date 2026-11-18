// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveFailure
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.v2.CapacityAction
import dev.stapler.stelekit.ui.sketch.v2.CapacityMessages
import dev.stapler.stelekit.ui.sketch.v2.EditorOpening
import dev.stapler.stelekit.ui.sketch.v2.LeaveGuard
import dev.stapler.stelekit.ui.sketch.v2.V2EditorOpener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Review findings 1 and 4: leaving a sheet saves first and asks only when the latest edits could not be written (UX-72), and the
 * editor's "new sketch" paths create a real sheet or are not offered.
 */
@OptIn(ExperimentalCoroutinesApi::class, DirectRepositoryWrite::class)
class SketchEditorLeaveTest {
    private fun TestScope.guard(states: ArrayDeque<SaveState>, left: MutableList<Unit>): LeaveGuard =
        LeaveGuard(flush = { states.removeFirst() }, scope = this, leave = { left += Unit })

    @Test
    fun back_should_LeaveWithoutAsking_When_TheFlushLeftTheSheetSaved() = runTest {
        val left = mutableListOf<Unit>()
        val guard = guard(ArrayDeque(listOf(SaveState.Saved)), left)

        guard.requestLeave()
        advanceUntilIdle()

        assertEquals(1, left.size)
        assertFalse(guard.prompting)
    }

    @Test
    fun back_should_AskInsteadOfLeaving_When_TheFlushFailed() = runTest {
        val left = mutableListOf<Unit>()
        val guard = guard(ArrayDeque(listOf(SaveState.Failed(SaveFailure.Unexpected))), left)

        guard.requestLeave()
        advanceUntilIdle()

        assertTrue(guard.prompting, "UX-72: a failed save prompts Retry, Export copy or Discard")
        assertTrue(left.isEmpty(), "the editor stays open until the user chooses")
    }

    @Test
    fun retry_should_LeaveAndCloseThePrompt_When_TheRetryWroteTheSheet() = runTest {
        val left = mutableListOf<Unit>()
        val guard = guard(ArrayDeque(listOf(SaveState.Failed(SaveFailure.Unexpected), SaveState.Saved)), left)
        guard.requestLeave()
        advanceUntilIdle()

        guard.retry()
        advanceUntilIdle()

        assertEquals(1, left.size)
        assertFalse(guard.prompting)
    }

    @Test
    fun discard_should_LeaveAtOnceAndSayItDiscarded() = runTest {
        val left = mutableListOf<Unit>()
        val guard = guard(ArrayDeque(listOf(SaveState.Pending)), left)
        guard.requestLeave()
        advanceUntilIdle()

        guard.discard()

        assertEquals(1, left.size)
        assertTrue(guard.discarded)
        assertFalse(guard.prompting)
    }

    @Test
    fun keepEditing_should_CloseThePromptAndStay() = runTest {
        val left = mutableListOf<Unit>()
        val guard = guard(ArrayDeque(listOf(SaveState.Pending)), left)
        guard.requestLeave()
        advanceUntilIdle()

        guard.keepEditing()

        assertFalse(guard.prompting)
        assertTrue(left.isEmpty())
    }

    private fun rect() = RectElement(ElementBase(ElementId("r1"), zKey = ZKey("ar1")), SceneUnit.ofInt(0), SceneUnit.ofInt(0), SceneUnit.ofInt(10), SceneUnit.ofInt(10))

    @Test
    fun closeAfterSave_should_WriteTheLatestEditsBeforeClosing() = runTest {
        val rig = V2EditorRig().also { it.seed() }
        val opening = assertIs<EditorOpening.Editor>(V2EditorOpener(rig.deps, StandardTestDispatcher(testScheduler)).open(rig.project.uuid, FixedAdvanceTextMetricsProvider()))
        val controller = opening.session.controller
        controller.commitNow(AddElements(listOf(rect())))
        assertTrue(rig.diskDoc().scene.order.isEmpty(), "the debounce has not written yet")

        opening.session.closeAfterSave(discard = false)
        testScheduler.advanceUntilIdle()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(SETTLE_MS) }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(ElementId("r1")), rig.diskDoc().scene.order, "leaving flushed the pending edit instead of dropping it")
    }

    @Test
    fun closeAfterSave_should_LeaveTheSheetAsLastSaved_When_TheUserDiscards() = runTest {
        val rig = V2EditorRig().also { it.seed() }
        val opening = assertIs<EditorOpening.Editor>(V2EditorOpener(rig.deps, StandardTestDispatcher(testScheduler)).open(rig.project.uuid, FixedAdvanceTextMetricsProvider()))
        opening.session.controller.commitNow(AddElements(listOf(rect())))

        opening.session.closeAfterSave(discard = true)
        testScheduler.advanceUntilIdle()

        assertTrue(rig.diskDoc().scene.order.isEmpty(), "Discard writes nothing")
    }

    @Test
    fun newSheet_should_CreateASheetBesideTheOpenOne_When_TheEditorIsEditable() = runTest {
        val rig = V2EditorRig().also { it.seed() }
        val opening = assertIs<EditorOpening.Editor>(V2EditorOpener(rig.deps, StandardTestDispatcher(testScheduler)).open(rig.project.uuid, FixedAdvanceTextMetricsProvider()))
        val moved = AddElements(listOf(rect())).apply(SketchDocumentV2()).fold({ error("$it") }, { it.doc })

        val created = opening.session.services.newSheet(moved)
        testScheduler.advanceUntilIdle()

        assertTrue(created.isRight(), "Split into new sketch is wired to a real creator: $created")
        val sheets = rig.repos.sheets.getSheets(rig.project.uuid, MANY).first().getOrNull()!!.items
        assertEquals(2, sheets.size, "the project now has the open sheet and the new one")
        opening.session.close()
    }

    @Test
    fun hardCap_should_NotOfferSplit_When_TheHostCannotAddASheet() {
        val error = dev.stapler.stelekit.sketch.v2.commands.CommandError.CapacityExceeded(1)

        val message = CapacityMessages.hardCapFor(error, selectionCount = 3, canSplit = false)

        assertFalse(CapacityAction.SplitIntoNewSketch in message!!.actions)
        assertTrue(CapacityAction.SplitIntoNewSketch in CapacityMessages.hardCapFor(error, 3)!!.actions)
    }

    private companion object {
        const val SETTLE_MS = 50L
        const val MANY = 10
    }
}
