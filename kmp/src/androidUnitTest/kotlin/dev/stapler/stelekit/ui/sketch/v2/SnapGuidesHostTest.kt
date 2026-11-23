// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.sketch.SnapKind
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.snap.GuideAxis
import dev.stapler.stelekit.sketch.v2.snap.SnapGuide
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchHostStrings
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
import kotlin.test.assertTrue

/**
 * Snap guides, handles that follow the snapped preview, the touch bypass and the toast's place, on a phone (360 dp wide, 3 px per
 * dp) through the real pointer path. Shots land in `build/outputs/roborazzi/snap-guides-*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class SnapGuidesHostTest {
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

    private fun handleCentreX(tag: String): Float = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center.x

    private fun guidesShown() = rule.onAllNodesWithTagCount(SnapGuideTags.OVERLAY)

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private fun resizeEastCorner(session: SketchEditorSession, to: Int) {
        rule.onNodeWithTag(ShellTags.CANVAS).performTouchInput { down(screen(session, 240, 320)); moveTo(screen(session, to, 320)) }
        rule.waitForIdle()
    }

    private fun release() {
        rule.onNodeWithTag(ShellTags.CANVAS).performTouchInput { up() }
        rule.waitForIdle()
    }

    @Test
    fun resize_should_DrawAGuideAndMoveTheHandleToTheSnappedCorner_ThenClearOnRelease() {
        val session = mount()
        assertEquals(0, guidesShown(), "no guide before a drag")
        val before = handleCentreX("handle-SE")

        resizeEastCorner(session, 354)

        assertEquals(1, guidesShown(), "one overlay while the snap is engaged")
        rule.onNodeWithTag(SnapGuideTags.OVERLAY).assertHasNoClickAction()
        val moved = handleCentreX("handle-SE") - before
        assertEquals(120f * session.controller.ui.viewport.zoom.toFloat(), moved, 1f, "the handle sits on the snapped corner (x 360), not under the finger (x 354) or at the old place")
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/snap-guides-resize-mid-drag.png")

        release()
        assertEquals(320.0, width(session))
        assertEquals(0, guidesShown(), "the guide goes with the drag")
        assertEquals(before + 120f * session.controller.ui.viewport.zoom.toFloat(), handleCentreX("handle-SE"), 1f, "the committed frame keeps the handle where it was shown")
    }

    @Test
    fun move_should_DrawAGuideAtTheSnappedEdge() {
        val session = mount()
        rule.onNodeWithTag(ShellTags.CANVAS).performTouchInput { down(screen(session, 140, 260)); moveTo(screen(session, 257, 260)) }
        rule.waitForIdle()

        assertEquals(1, guidesShown())
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/snap-guides-move-mid-drag.png")
        release()
        assertEquals(160.0, (session.controller.doc.scene[ElementId("r1")] as RectElement).x.value, "right edge 357 snapped to 360: x 40 + 120")
    }

    @Test
    fun secondFingerOnTheSnapButton_should_PauseSnappingUntilItLifts() {
        val session = mount()
        rule.onNodeWithTag("sketch.snap").performTouchInput { down(1, center) }
        rule.waitForIdle()
        rule.onNodeWithTag("sketch.snap").assertContentDescriptionEquals(SketchHostStrings.View.SNAP_PAUSED)

        resizeEastCorner(session, 354)
        assertEquals(0, guidesShown(), "no guide while the finger rests on Snap")
        rule.onNodeWithTag("sketch.snap").assertContentDescriptionEquals(SketchHostStrings.View.SNAP_PAUSED)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/snap-guides-paused.png")
        release()
        assertEquals(314.0, width(session), "the edge stays where the finger put it")

        rule.onNodeWithTag("sketch.snap").performTouchInput { up(1) }
        rule.waitForIdle()
        rule.onNodeWithTag("sketch.snap").assertContentDescriptionEquals(SketchHostStrings.View.SNAP_ON)
        // The shape is now 314 wide, so its corner handle is at x 354.
        rule.onNodeWithTag(ShellTags.CANVAS).performTouchInput { down(screen(session, 354, 320)); moveTo(screen(session, 357, 320)) }
        rule.waitForIdle()
        assertEquals(1, guidesShown(), "snapping is back once the finger lifts")
        release()
        assertEquals(320.0, width(session))
    }

    @Test
    fun snapToast_should_SitAboveTheChipRow_NotOverTheTopOfTheCanvas() {
        val session = mount()
        rule.onNodeWithTag("sketch.snap").performClick()
        rule.waitForIdle()

        val toast = rule.onNodeWithTag(ToastTags.row(SketchHostStrings.View.SNAP_KEY))
        toast.assertIsDisplayed()
        val canvas = rule.onNodeWithTag(ShellTags.CANVAS).fetchSemanticsNode().boundsInRoot
        val row = toast.fetchSemanticsNode().boundsInRoot
        assertTrue(row.top >= canvas.top + canvas.height / 2, "the toast is in the lower half; the top is left to the drag (row $row, canvas $canvas)")
        val chips = rule.onNodeWithTag(ShellTags.VIEW_CLUSTER).fetchSemanticsNode().boundsInRoot
        assertTrue(row.bottom <= chips.top + 1f, "and it stays clear of the view cluster")
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/snap-guides-toast.png")
        assertEquals(1, session.controller.notifier.queue.visible(session.controller.notifier.now()).size)
    }

    // ---- the overlay on its own ------------------------------------------------------------------------------------

    private class RecordingHaptics : HapticFeedback {
        val ticks = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            ticks += hapticFeedbackType
        }
    }

    private val viewport = SketchViewport.of()

    private fun guide(x: Double) = SnapGuide(GuideAxis.Vertical, x, 0.0, 100.0, SnapKind.ENDPOINT)

    @Test
    fun overlay_should_TickOncePerNewTarget_OnTouchOnly_AndDrawNothingWithoutGuides() {
        val haptics = RecordingHaptics()
        var guides by mutableStateOf(emptyList<SnapGuide>())
        var touch by mutableStateOf(true)
        rule.setContent { CompositionLocalProvider(LocalHapticFeedback provides haptics) { SnapGuideOverlay(guides, viewport, haptics = touch) } }
        rule.onNodeWithTag(SnapGuideTags.OVERLAY).assertDoesNotExist()

        guides = listOf(guide(50.0))
        rule.waitForIdle()
        rule.onNodeWithTag(SnapGuideTags.OVERLAY).assertIsDisplayed()
        assertEquals(1, haptics.ticks.size, "a new target ticks")

        guides = listOf(guide(50.0).copy(to = 120.0))
        rule.waitForIdle()
        assertEquals(1, haptics.ticks.size, "the same target running longer is not a new snap")

        touch = false
        guides = emptyList()
        rule.waitForIdle()
        guides = listOf(guide(900.0))
        rule.waitForIdle()
        assertEquals(1, haptics.ticks.size, "a mouse drag gets no tick")
    }
}
