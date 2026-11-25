// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.v2.EditorOpening
import dev.stapler.stelekit.ui.sketch.v2.V2EditorOpener
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * E14 end to end through the editor: the file under an open sheet is replaced by another device's version, the editor saves its own
 * edit, and the editor ends up holding both, with a non-blocking notice, with the file written once and no conflict copy.
 */
@OptIn(DirectRepositoryWrite::class)
class SketchEditorMergeAdoptionTest {
    private fun rect(id: String, z: String) = RectElement(
        ElementBase(ElementId(id), zKey = ZKey(z)),
        SceneUnit.ofInt(0), SceneUnit.ofInt(0), SceneUnit.ofInt(10), SceneUnit.ofInt(10),
    )

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        repeat(ATTEMPTS) {
            if (condition()) return
            delay(STEP_MS)
        }
        error("timed out waiting for $what")
    }

    @Test
    fun editor_should_HoldBothDevicesWorkAndShowANotice_When_TheFileChangedUnderIt() = runTest {
        withContext(Dispatchers.Default) {
            val rig = V2EditorRig().also { it.seed() }
            val session = assertIs<EditorOpening.Editor>(V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, FixedAdvanceTextMetricsProvider())).session
            val controller = session.controller
            val theirScene = SketchSceneV2.empty().add(rect("theirs", "a2")).getOrNull()!!
            rig.store.files[rig.path] = SheetCodecV2.encode(SketchDocumentV2(theirScene, rig.sheet.value)).getOrNull()!!

            controller.submit(AddElements(listOf(rect("mine", "a1"))))
            controller.flush()

            waitFor("the editor to hold the other device's element") { ElementId("theirs") in controller.doc.scene }
            assertTrue(ElementId("mine") in controller.doc.scene)
            assertEquals(setOf("mine", "theirs"), rig.diskDoc().scene.elementsInOrder.map { it.id.value }.toSet())
            val notice = controller.notices.visible(controller.notifier.now()).map { it.notice }.single { it.key == SketchEditorStrings.Toasts.KEY_MERGED }
            assertEquals("Merged changes from another device (1 element)", notice.text)
            assertEquals(SketchEditorStrings.Toasts.MERGED_SHOW, notice.action)

            controller.notifier.act(notice) { }
            assertEquals(setOf("theirs"), controller.ui.selection, "Show selects what merged")

            controller.submit(DeleteElements(listOf(ElementId("mine"))))
            controller.flush()
            waitFor("the delete to reach the file") { rig.diskDoc().scene[ElementId("mine")] == null }
            assertTrue(ElementId("theirs") in rig.diskDoc().scene, "the merged element survives the editor's next save")
            session.close()
        }
    }

    private companion object {
        const val ATTEMPTS = 100
        const val STEP_MS = 20L
    }
}
