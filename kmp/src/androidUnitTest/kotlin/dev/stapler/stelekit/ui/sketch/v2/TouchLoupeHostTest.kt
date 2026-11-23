// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The touch loupe on a phone: a finger dragging one of two close shapes shows the magnifier, a tap does not, the bubble flips at
 * the top and side edges, and the setting turns it off. Renders land in `build/outputs/roborazzi/loupe-*.png`. Robolectric;
 * Gradle-only like the other host tests (the layout and gating are checked here, finger and device feel need the emulator).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class TouchLoupeHostTest {
    @get:Rule
    val rule = createComposeRule()

    private fun rect(id: String, x: Int, y: Int, w: Int, h: Int, fill: String) = RectElement(
        ElementBase(ElementId(id), style = ElementStyle(backgroundColor = fill, roughness = 0)),
        SceneUnit.ofInt(x), SceneUnit.ofInt(y), SceneUnit.ofInt(w), SceneUnit.ofInt(h),
    )

    private fun mount(vararg shapes: RectElement): SketchEditorController {
        val rig = V2EditorRig()
        val scene = shapes.fold(SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull()!! }
        runBlocking { rig.seed(SketchDocumentV2(scene = scene, sheetUuid = rig.sheet.value)) }
        val metrics = FixedAdvanceTextMetricsProvider()
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, assertNotNull(opened.sheet), HostServices())
        session.controller.ui.fingerDrawing = true
        session.controller.ui.viewport = SketchViewport.of(ZOOM, 0.0, 0.0)
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        return session.controller
    }

    private fun canvas() = rule.onNodeWithTag(HostTags.CANVAS_INPUT)

    /** Selects [id] directly: a tap followed at once by a press would read as a double click and open the label editor. */
    private fun select(controller: SketchEditorController, id: String) {
        rule.runOnIdle { controller.tools.select(listOf(ElementId(id))) }
        rule.waitForIdle()
    }

    /** Presses at [from] and drags to [to] with the finger left down, long enough for the bubble to show. */
    private fun holdDrag(from: Offset, to: Offset) {
        canvas().performTouchInput {
            down(from)
            moveTo(to)
        }
        rule.mainClock.advanceTimeBy(LoupeSpec.HOLD_MS * 2)
        rule.waitForIdle()
    }

    private fun release() {
        canvas().performTouchInput { up() }
        rule.waitForIdle()
    }

    private fun pair(y: Int) = arrayOf(rect("a", 40, y, 90, 70, "#a5d8ff"), rect("b", 140, y, 90, 70, "#ffc9c9"))

    @Test
    fun loupe_should_ShowBesideTheFinger_When_DraggingOneOfTwoCloseShapes() {
        val controller = mount(*pair(120))
        select(controller, "a")
        holdDrag(Offset(370f, 465f), Offset(398f, 472f))
        assertTrue(controller.tools.loupe.state.visible)
        rule.onNodeWithTag(LoupeTags.BUBBLE).assertExists()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/loupe-1-drag-close-shapes.png")
        release()
        assertFalse(controller.tools.loupe.state.visible, "release hides the bubble")
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/loupe-2-after-release.png")
    }

    @Test
    fun loupe_should_NotShow_When_TheFingerOnlyTaps() {
        val controller = mount(*pair(120))
        rule.mainClock.autoAdvance = false
        canvas().performTouchInput { down(Offset(255f, 465f)) }
        rule.mainClock.advanceTimeBy(LoupeSpec.HOLD_MS / 2)
        assertFalse(controller.tools.loupe.state.visible, "a press that has not moved or been held shows nothing")
        canvas().performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertFalse(controller.tools.loupe.state.visible)
    }

    @Test
    fun loupe_should_ShowAfterAHold_When_TheFingerDoesNotMove() {
        val controller = mount(*pair(120))
        select(controller, "a")
        canvas().performTouchInput { down(Offset(255f, 465f)) }
        assertEquals(LoupePhase.Armed, controller.tools.loupe.state.phase)
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(LoupeSpec.HOLD_MS - FRAME_SLACK_MS)
        assertFalse(controller.tools.loupe.state.visible, "not before the hold time")
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(controller.tools.loupe.state.visible, "${controller.tools.loupe.state}")
        release()
    }

    @Test
    fun loupe_should_FlipBesideTheFinger_When_TheContactIsNearTheTopEdge() {
        val controller = mount(*pair(10))
        select(controller, "a")
        holdDrag(Offset(370f, 135f), Offset(398f, 120f))
        assertTrue(controller.tools.loupe.state.visible)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/loupe-3-top-edge-flip.png")
        release()
    }

    @Test
    fun loupe_should_StayInsideTheCanvas_When_TheContactIsNearASide() {
        val controller = mount(rect("a", 2, 150, 60, 60, "#a5d8ff"), rect("b", 70, 150, 60, 60, "#ffc9c9"))
        select(controller, "a")
        holdDrag(Offset(120f, 540f), Offset(60f, 548f))
        assertTrue(controller.tools.loupe.state.visible)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/loupe-4-left-edge.png")
        release()
    }

    @Test
    fun loupe_should_StayInsideTheCanvas_When_TheContactIsNearTheRightEdge() {
        val controller = mount(rect("a", 290, 150, 60, 60, "#a5d8ff"), rect("b", 220, 150, 60, 60, "#ffc9c9"))
        select(controller, "a")
        holdDrag(Offset(900f, 540f), Offset(1000f, 548f))
        assertTrue(controller.tools.loupe.state.visible)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/loupe-6-right-edge.png")
        release()
    }

    @Test
    fun loupe_should_NotShow_When_TheSettingIsOff() {
        val controller = mount(*pair(120))
        controller.ui.touchLoupe = false
        rule.waitForIdle()
        select(controller, "a")
        holdDrag(Offset(370f, 465f), Offset(398f, 472f))
        assertTrue(controller.tools.loupe.state.visible, "the gesture is tracked")
        rule.onNodeWithTag(LoupeTags.BUBBLE).assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/loupe-5-setting-off.png")
        release()
    }

    @Test
    fun loupe_should_NotShow_When_FingerDrawingIsOff_BecauseATouchDragPans() {
        val controller = mount(*pair(120))
        controller.ui.fingerDrawing = false
        select(controller, "a")
        holdDrag(Offset(370f, 465f), Offset(398f, 472f))
        assertEquals(LoupePhase.Hidden, controller.tools.loupe.state.phase)
        rule.onNodeWithTag(LoupeTags.BUBBLE).assertDoesNotExist()
        release()
    }

    @Test
    fun overflowSwitch_should_TurnTheLoupeOff() {
        val controller = mount(*pair(120))
        assertTrue(controller.ui.touchLoupe)
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(LoupeTags.TOGGLE).assertExists().assertIsToggleable().assertHeightIsAtLeast(MIN_TARGET_DP.dp)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/loupe-7-overflow-switch.png")
        rule.onNodeWithTag(LoupeTags.TOGGLE).performClick()
        rule.waitForIdle()
        assertFalse(controller.ui.touchLoupe)
    }

    private companion object {
        const val ZOOM = 3.0
        const val FRAME_SLACK_MS = 20L
        const val MIN_TARGET_DP = 40
    }
}
