// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * A handle resize on a phone (360 dp wide, 3 px per dp) snaps the dragged edge to a neighbour's edge, in the preview while the
 * finger is down and in the committed shape, through the real pointer path. Shots land in `build/outputs/roborazzi/resize-snap-*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class ResizeSnapHostTest {
    @get:Rule
    val rule = createComposeRule()

    private val subject = filledRectEl("r1", 40, 200, 200, 120)
    private val neighbour = filledRectEl("r2", 360, 150, 160, 220)

    private fun mount(): SketchEditorSession {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val deps = V2EditorDeps(repos = rig.repos, store = { rig.store }, leases = null, settings = rig.deps.settings, graphId = { V2EditorRig.GRAPH }, touchPresent = true)
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, assertNotNull(opened.sheet), HostServices())
        rule.runOnUiThread { listOf(subject, neighbour).forEach { session.controller.submit(AddElement(it)) } }
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        rule.runOnUiThread { session.controller.ui.fingerDrawing = true; session.controller.tools.select(listOf(ElementId("r1"))) }
        rule.waitForIdle()
        return session
    }

    private fun screen(session: SketchEditorSession, x: Int, y: Int): Offset {
        val p = session.controller.ui.viewport.toScreen(ScenePoint(SceneUnit.ofInt(x), SceneUnit.ofInt(y)))
        return Offset(p.x.toFloat(), p.y.toFloat())
    }

    private fun width(session: SketchEditorSession) = (session.controller.doc.scene[ElementId("r1")] as RectElement).width.value

    private fun dragCornerHandle(session: SketchEditorSession, shot: String?) {
        // A 200 x 120 shape on a phone shows corner handles: the south-east one is at (240, 320); the neighbour's left edge is at 360, the finger stops 6 units short.
        rule.onNodeWithTag(ShellTags.CANVAS).performTouchInput { down(screen(session, 240, 320)); moveTo(screen(session, 354, 320)) }
        rule.waitForIdle()
        if (shot != null) rule.onRoot().captureRoboImage("build/outputs/roborazzi/resize-snap-$shot.png")
        rule.onNodeWithTag(ShellTags.CANVAS).performTouchInput { up() }
        rule.waitForIdle()
    }

    @Test
    fun resizeHandle_should_SnapTheDraggedEdgeToTheNeighbour_WhileDraggingAndOnCommit() {
        val session = mount()

        dragCornerHandle(session, "mid-drag-snapped")

        assertEquals(320.0, width(session), "right edge 354 snapped to the neighbour's left edge (360): 360 - 40")
    }

    @Test
    fun snapToggledOff_should_LeaveTheResizeRaw() {
        val session = mount()
        rule.onNodeWithTag("sketch.snap").performClick()
        rule.waitForIdle()

        dragCornerHandle(session, "mid-drag-snap-off")

        assertEquals(314.0, width(session), "with Snap off the edge stays where the finger put it")
    }
}
