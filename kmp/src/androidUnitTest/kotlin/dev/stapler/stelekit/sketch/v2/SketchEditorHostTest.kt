// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.sketch.v2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.EditorOpening
import dev.stapler.stelekit.ui.sketch.v2.HostTags
import dev.stapler.stelekit.ui.sketch.v2.PainterResources
import dev.stapler.stelekit.ui.sketch.v2.ShellTags
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorV2Host
import dev.stapler.stelekit.ui.sketch.v2.V2EditorOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Task 6.2.0b: the host mounts over a real sheet, a mouse drag with the Rectangle tool draws, and the file on disk holds the same scene. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SketchEditorHostTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun mouseDragWithTheRectangleTool_should_DrawTheShapeAndSaveTheFile() {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val session = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val controller = session.controller
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.onNodeWithTag(ShellTags.CANVAS).assertExists()
        rule.runOnUiThread { controller.chooseTool(ToolId.Rectangle) }

        rule.onNodeWithTag(HostTags.CANVAS_INPUT).performMouseInput {
            moveTo(Offset(40f, 40f))
            press()
            moveTo(Offset(100f, 70f))
            moveTo(Offset(140f, 110f))
            release()
        }
        rule.waitForIdle()

        val drawn = controller.doc.scene.elementsInOrder.filterIsInstance<RectElement>()
        assertEquals(1, drawn.size, "the drag drew one rectangle")
        assertTrue(drawn.single().width.value > 0.0 && drawn.single().height.value > 0.0)
        assertEquals(setOf(drawn.single().id.value), controller.ui.selection)
        assertEquals(SaveState.Saved, runBlocking { controller.flush() })
        assertEquals(controller.doc.scene, rig.diskDoc().scene, "the saved file holds the drawn scene")
        rule.runOnUiThread { controller.undo() }
        rule.waitForIdle()
        assertTrue(controller.doc.scene.elementsInOrder.none { it is RectElement }, "undo removed the rectangle")
        session.close()
    }
}
