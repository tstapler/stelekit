// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.v2.EditorOpening
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorSession
import dev.stapler.stelekit.ui.sketch.v2.V2EditorOpener
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Review findings B1, B6, B9: a solve queued or in flight must not be bypassed by leaving, undo or a second commit. */
@OptIn(ExperimentalCoroutinesApi::class, DirectRepositoryWrite::class)
class SketchEditorSolveRaceTest {
    private fun line() = LineElement(
        ElementBase(ElementId("L1"), zKey = ZKey("aL1")),
        listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(100.0, 30.0)),
    )

    private fun rect(id: String) = RectElement(
        ElementBase(ElementId(id), zKey = ZKey("a$id")),
        SceneUnit.ofInt(200), SceneUnit.ofInt(0), SceneUnit.ofInt(10), SceneUnit.ofInt(10),
    )

    private val horizontal = AddConstraint(Constraint(ConstraintId("h"), ConstraintKind.Horizontal, listOf(ConstraintTarget(ElementId("L1")))))

    private fun constrainedDoc(): SketchDocumentV2 {
        val withLine = AddElements(listOf(line())).apply(SketchDocumentV2(sheetUuid = "s2")).fold({ error("$it") }, { it.doc })
        return horizontal.apply(withLine).fold({ error("$it") }, { it.doc })
    }

    private suspend fun TestScope.openConstrained(): Pair<V2EditorRig, SketchEditorSession> {
        val rig = V2EditorRig().also { it.seed(constrainedDoc()) }
        val opener = V2EditorOpener(rig.deps, StandardTestDispatcher(testScheduler))
        val opening = assertIs<EditorOpening.Editor>(opener.open(rig.project.uuid, FixedAdvanceTextMetricsProvider()))
        advanceUntilIdle()
        return rig to opening.session
    }

    @Test
    fun flush_should_WaitForAQueuedSolve_When_TheUserLeavesRightAfterAnEdit() = runTest {
        val (rig, session) = openConstrained()
        val controller = session.controller

        controller.submit(AddElements(listOf(rect("R1"))))
        assertEquals(1, controller.pendingSolves.value, "the edit is queued behind the solver, not yet in history")
        val state = controller.flush()

        assertEquals(SaveState.Saved, state)
        assertTrue(rig.diskDoc().scene.order.contains(ElementId("R1")), "the flush saved the edit that was still solving")
        session.close()
    }

    @Test
    fun submit_should_NotLeavePendingStuck_When_TheControllerIsClosed() = runTest {
        val (_, session) = openConstrained()
        val controller = session.controller
        session.close()

        controller.submit(AddElements(listOf(rect("R1"))))
        advanceUntilIdle()

        assertEquals(0, controller.pendingSolves.value, "a submit after close counts nothing")
    }

    @Test
    fun undo_should_RunAfterAQueuedSolve_When_TheSolveIsStillInFlight() = runTest {
        val (_, session) = openConstrained()
        val controller = session.controller
        controller.submit(AddElements(listOf(rect("R1"))))
        advanceUntilIdle()
        controller.submit(AddElements(listOf(rect("R2"))))

        controller.undo()
        advanceUntilIdle()

        assertEquals(0, controller.pendingSolves.value)
        val conflict = controller.notices.visible(controller.notifier.now()).any { it.notice.key == SketchEditorStrings.Toasts.KEY_CONFLICT }
        assertFalse(conflict, "undo did not collide with the queued solve")
        assertTrue(ElementId("R1") in controller.doc.scene.order && ElementId("R2") !in controller.doc.scene.order, "undo took back the queued edit, not the one before it")
        assertTrue(controller.canRedo)
        session.close()
    }

    @Test
    fun commitNow_should_Refuse_When_ALabelEditHoldsTheGate() = runTest {
        val (_, session) = openConstrained()
        val controller = session.controller
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val label = launch { controller.runLabelEdit { started.complete(Unit); release.await(); null } }
        started.await()

        val result = controller.commitNow(AddElements(listOf(rect("R9"))))

        assertTrue(result.isLeft(), "a label edit in flight refuses an import, as a solve does")
        release.complete(Unit)
        label.join()
        session.close()
    }
}
