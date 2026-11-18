// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.platform.AlwaysHeldSketchWriterLease
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.runtime.session.SheetLeaseRegistry
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualitySignals
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLogSink
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.StandardToolRegistry
import dev.stapler.stelekit.ui.sketch.v2.EditorOpening
import dev.stapler.stelekit.ui.sketch.v2.EditorPorts
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorController
import dev.stapler.stelekit.ui.sketch.v2.V2EditorOpener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Review findings B3 and B8: leaving a sheet releases its lease only after the flush, and a reopen waits for that instead of opening busy or stale. */
@OptIn(ExperimentalCoroutinesApi::class, DirectRepositoryWrite::class)
class SketchEditorCloseRaceTest {
    private fun rect(id: String) = RectElement(
        ElementBase(ElementId(id), zKey = ZKey("a$id")),
        SceneUnit.ofInt(0), SceneUnit.ofInt(0), SceneUnit.ofInt(10), SceneUnit.ofInt(10),
    )

    /**
     * Real dispatchers: the saver encodes on `Dispatchers.Default`, which virtual time would outrun (the registry's bounded wait is a virtual delay).
     * Repeated, because without the lease handshake the reopen only loses the race when it beats the flush: one pass can slip through.
     */
    @Test
    fun reopen_should_SeeTheLeftSheetsEditsAndBeEditable_When_ItRacesTheDeferredClose() = runTest {
        withContext(Dispatchers.Default) {
            repeat(REPEATS) { round ->
                val rig = V2EditorRig(leases = SheetLeaseRegistry(AlwaysHeldSketchWriterLease())).also { it.seed() }
                val opener = V2EditorOpener(rig.deps, Dispatchers.Default)
                val first = assertIs<EditorOpening.Editor>(opener.open(rig.project.uuid, FixedAdvanceTextMetricsProvider())).session
                first.controller.submit(AddElements(listOf(rect("R1"))))
                val leaveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

                first.closeAfterSave(discard = false, scope = leaveScope)
                val second = assertIs<EditorOpening.Editor>(opener.open(rig.project.uuid, FixedAdvanceTextMetricsProvider())).session

                assertFalse(second.controller.readOnly, "round $round: the user's own leave must not leave the sheet busy")
                assertTrue(ElementId("R1") in second.controller.doc.scene.order, "round $round: the reopen read the file after the old session's flush")
                second.close()
                leaveScope.cancel()
            }
        }
    }

    @Test
    fun close_should_EndTheQualitySessionOnce_When_CalledTwice() = runTest {
        val lines = mutableListOf<String>()
        val controller = SketchEditorController(
            SketchDocumentV2(sheetUuid = "s"),
            EditorPorts(
                StandardToolRegistry.create(FixedAdvanceTextMetricsProvider()),
                ids = ElementIdSource { ElementId("n") },
                log = SketchLog(SketchLogSink { lines += it }),
                quality = QualitySignals(RigSettings()),
            ),
            StandardTestDispatcher(testScheduler),
        )

        controller.close()
        val afterFirst = lines.size
        controller.close()

        assertTrue(afterFirst > 0, "control: the first close logs the session's adoption line")
        assertEquals(afterFirst, lines.size, "the second close logged nothing")
    }

    private companion object {
        const val REPEATS = 25
    }
}
