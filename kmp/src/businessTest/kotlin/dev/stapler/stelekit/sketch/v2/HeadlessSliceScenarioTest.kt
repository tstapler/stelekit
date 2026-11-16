// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.projectbridge.ReconcileOutcome
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task 6.2.4d, the headless slice at W7: commands, `SheetSaver`, `SheetCodecV2` and a reload, with no flag, router, painter
 * or UI. The bound arrow is built as data through `AddElement` with `Binding` fields, because the binding engine
 * (Story 3.3.2) arrives in W9. The store is the project's real `FileProjectSidecarStore` over the test file system.
 */
class HeadlessSliceScenarioTest {
    private fun u(v: Int) = SceneUnit.ofInt(v)

    private val boxA = RectElement(ElementBase(ElementId("box-a")), u(0), u(0), u(120), u(80))
    private val boxB = RectElement(ElementBase(ElementId("box-b")), u(300), u(0), u(120), u(80))
    private val label = TextElement(ElementBase(ElementId("label")), u(10), u(30), u(100), u(25), text = "Auth", containerId = ElementId("box-a"))
    private val arrow = ArrowElement(
        base = ElementBase(ElementId("link")),
        points = listOf(ScenePoint(u(120), u(40)), ScenePoint(u(300), u(40))),
        startBinding = Binding(ElementId("box-a"), 1.0, 0.5),
        endBinding = Binding(ElementId("box-b"), 0.0, 0.5),
    )

    /** Each command is its own transaction, as a gesture would be. */
    private fun scenario(vararg commands: Command): SketchDocumentV2 =
        commands.fold(SketchDocumentV2(sheetUuid = MixedProjectFixtures.SHEET_A)) { doc, command ->
            Transaction.begin(doc).apply(command).getOrNull()!!.commit().getOrNull()!!.after
        }

    @Test
    fun scenario_should_SaveReloadAndReconcileUnchanged() = runBlocking<Unit> {
        val rig = SaverRig(MixedProjectFixtures.device(convertA = true), coroutineContext)
        assertIs<ReconcileOutcome.Unchanged>(rig.reconciler.reconcileAll().outcomes.single(), "the seeded project is consistent before the edit")
        val doc = scenario(AddElement(boxA), AddElement(boxB), AddElement(label), AddElement(arrow))

        rig.saver.submit(doc)
        assertEquals(SaveState.Saved, rig.saver.flush())

        val reloaded = SheetCodecV2.decode(rig.diskBytes()).getOrNull()!!
        assertEquals(doc.scene, reloaded.scene, "the reloaded scene equals the in-memory scene")
        assertEquals(doc, reloaded, "so does the whole document (counters, constraints, extras)")
        assertContentEquals(SheetCodecV2.encode(doc).getOrNull(), rig.diskBytes())

        val link = reloaded.scene[ElementId("link")] as ArrowElement
        assertEquals(ElementId("box-a"), link.startBinding?.elementId)
        assertEquals(ElementId("box-b"), link.endBinding?.elementId)
        assertEquals(1.0 to 0.5, link.startBinding?.let { it.fixedX to it.fixedY })
        assertTrue(reconcile(reloaded.scene).isClean, "reconcile has nothing to repair after the reload")

        val row = rig.device.repos.sheets.getSheets(SketchTestSupport.P).first().getOrNull()!!.items.first { it.uuid == rig.sheet }
        assertEquals(2L, row.schemaVersion)
        val writes = rig.device.fs.writeCount
        assertIs<ReconcileOutcome.Unchanged>(rig.reconciler.reconcileAll().outcomes.single(), "the sidecar reconciler agrees with the saved file")
        assertEquals(writes, rig.device.fs.writeCount, "and writes nothing")
        rig.saver.close()
    }

    @Test
    fun scenario_should_BeDeterministic_When_RunTwice() = runBlocking<Unit> {
        val first = SheetCodecV2.encode(scenario(AddElement(boxA), AddElement(boxB), AddElement(label), AddElement(arrow))).getOrNull()!!
        val second = SheetCodecV2.encode(scenario(AddElement(boxA), AddElement(boxB), AddElement(label), AddElement(arrow))).getOrNull()!!
        assertContentEquals(first, second)
    }
}
