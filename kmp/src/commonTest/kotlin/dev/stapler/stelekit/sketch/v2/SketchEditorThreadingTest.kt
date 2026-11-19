// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import androidx.compose.runtime.snapshots.Snapshot
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.StandardToolRegistry
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.element
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.EditorExtras
import dev.stapler.stelekit.ui.sketch.v2.EditorPorts
import dev.stapler.stelekit.ui.sketch.v2.HostServices
import dev.stapler.stelekit.ui.sketch.v2.PainterResources
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reports whether code is running inside a block this dispatcher dispatched, standing in for "on the UI thread". */
@OptIn(ExperimentalCoroutinesApi::class)
private class MarkedDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
    var inside = false
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        inner.dispatch(context, Runnable {
            inside = true
            try {
                block.run()
            } finally {
                inside = false
            }
        })
    }
}

/**
 * Review finding 2: a constrained edit is solved off the UI thread, but adopting its result (history, revision, selection, the
 * spatial index, the notice queue) must happen on the controller's adoption context, never on the solver's. A reader on the
 * UI thread iterates those structures unsynchronised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SketchEditorThreadingTest {
    private fun line() = LineElement(
        ElementBase(ElementId("L1"), zKey = ZKey("aL1")),
        listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(100.0, 30.0)),
    )

    private fun doc(): SketchDocumentV2 = AddElements(listOf(line())).apply(SketchDocumentV2(sheetUuid = "s")).fold({ error("$it") }, { it.doc })

    private val horizontal = AddConstraint(Constraint(ConstraintId("h"), ConstraintKind.Horizontal, listOf(ConstraintTarget(ElementId("L1")))))

    /** The threads (as flags) the notice queue was written from while the solved commit was adopted. */
    private fun TestScope.adoptionWrites(adoptionOf: (solving: CoroutineDispatcher, marked: CoroutineDispatcher) -> CoroutineDispatcher): List<Boolean> {
        val solving = StandardTestDispatcher(testScheduler)
        val marked = MarkedDispatcher(StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<Boolean>()
        var n = 0
        val controller = SketchEditorController(
            doc(),
            EditorPorts(
                StandardToolRegistry.create(FixedAdvanceTextMetricsProvider()),
                saver = null,
                solver = SolveRunner(context = solving),
                ids = ElementIdSource { ElementId("n${n++}") },
                clockMillis = { seen += marked.inside; 0L },
            ),
            solving,
            adoption = adoptionOf(solving, marked),
        )
        seen.clear()
        controller.submit(horizontal, solve = true)
        advanceUntilIdle()
        assertEquals(1, controller.history.undoDepth, "the solved commit was adopted")
        controller.close()
        return seen
    }

    @Test
    fun solvedCommit_should_BeAdoptedOnTheAdoptionContext_When_ItIsGiven() = runTest {
        val writes = adoptionWrites { _, marked -> marked }

        assertTrue(writes.isNotEmpty(), "the commit posted a notice")
        assertTrue(writes.all { it }, "every notice-queue write ran on the adoption context: $writes")
    }

    @Test
    fun solvedCommit_should_BeObservedOffTheAdoptionContext_When_NoneIsGiven() = runTest {
        val writes = adoptionWrites { solving, _ -> solving }

        assertTrue(writes.isNotEmpty() && writes.none { it }, "control: without an adoption context the same write is observed off it, as before the fix")
    }

    @Test
    fun clipboardImport_should_CommitOnTheAdoptionContext_When_TheImporterReadsOffIt() = runTest {
        val solving = StandardTestDispatcher(testScheduler)
        val marked = MarkedDispatcher(StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<Boolean>()
        var n = 0
        val metrics = FixedAdvanceTextMetricsProvider()
        val controller = SketchEditorController(
            SketchDocumentV2(sheetUuid = "s"),
            EditorPorts(
                StandardToolRegistry.create(metrics),
                saver = null,
                solver = SolveRunner(context = solving),
                ids = ElementIdSource { ElementId("n${n++}") },
                clockMillis = { seen += marked.inside; 0L },
            ),
            solving,
            adoption = marked,
        )
        val extras = EditorExtras(controller, HostServices(), ModernPreviewEnvironment(metrics, PainterResources()))

        seen.clear()
        val observer = Snapshot.registerGlobalWriteObserver { seen += marked.inside }
        extras.pasteText(ExcalidrawTestData.scene(listOf(element("rectangle", "e1", 3, 3, 10, 10)), type = "excalidraw/clipboard"))
        var waited = 0
        while (controller.doc.scene.order.isEmpty() && waited++ < WAIT_STEPS) {
            testScheduler.advanceUntilIdle()
            withContext(Dispatchers.Default) { delay(REAL_STEP_MS) }
        }

        observer.dispose()
        assertEquals(1, controller.doc.scene.order.size, "the pasted element was committed")
        assertTrue(seen.isNotEmpty() && seen.all { it }, "every state write of the import commit ran on the adoption context, not the importer's: $seen")
        extras.close()
        controller.close()
    }

    private companion object {
        const val WAIT_STEPS = 500
        const val REAL_STEP_MS = 10L
    }
}
