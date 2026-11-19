// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.StandardToolRegistry
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Keyboard-only drawing and the other accessibility mounts of the real host (Tasks 3.6.3c, 3.6.1, 3.6.7): Crosshair mode draws
 * with Enter and the arrow keys or with the pad, Esc backs out one row at a time (rows 3, 5, 6 and 7 of the one Esc order), the
 * arrow keys pan by 40 dp, undo says what it undid, and a drawing past the soft threshold degrades and raises its banner.
 * Robolectric, so nothing here says how a screen reader or a device behaves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class KeyboardAccessHostTest {
    @get:Rule
    val rule = createComposeRule()

    private class Mounted(val session: SketchEditorSession) {
        val controller get() = session.controller
        val access get() = session.extras!!.access
        fun rows(): Set<EscRow> = controller.escRows() + session.extras!!.escRows() + ShellEscape.shellRows(controller.ui)
    }

    private fun mount(): Mounted {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val session = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        return Mounted(session)
    }

    private fun press(keys: List<Key>) {
        for (key in keys) rule.onNodeWithTag(ShellTags.CANVAS).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusCanvas(tool: ToolId) {
        rule.runOnUiThread { mounted.controller.tools.chooseTool(tool) }
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.CANVAS).requestFocus()
    }

    private lateinit var mounted: Mounted

    @Test
    fun rectangle_should_BeDrawnWithEnterAndArrowKeysAlone_When_TheRectangleToolIsActive() {
        mounted = mount()
        focusCanvas(ToolId.Rectangle)
        press(listOf(Key.Enter))
        assertNotNull(mounted.access.crosshair, "Enter on the canvas starts Crosshair mode for the active tool")
        press(listOf(Key.Enter) + List(STEPS) { Key.DirectionRight } + List(STEPS) { Key.DirectionDown } + Key.Enter)
        val rect = mounted.controller.doc.scene.elementsInOrder.filterIsInstance<RectElement>().single()
        assertEquals(STEPS.toDouble(), rect.width.value, "each right press moves the second corner one unit")
        assertEquals(STEPS.toDouble(), rect.height.value)
        mounted.session.close()
    }

    @Test
    fun pad_should_DrawWhatTheKeysDraw_When_TheChipStartsCrosshairMode() {
        mounted = mount()
        rule.runOnUiThread { mounted.controller.tools.chooseTool(ToolId.Rectangle) }
        rule.waitForIdle()
        rule.onNodeWithTag("sketch.chip.crosshair").performClick()
        rule.onNodeWithTag(CrosshairTags.PAD).assertExists()
        for (tag in listOf(CrosshairTags.PLACE) + List(STEPS) { CrosshairTags.RIGHT } + List(STEPS) { CrosshairTags.DOWN } + CrosshairTags.PLACE) {
            rule.onNodeWithTag(tag).performClick()
        }
        assertEquals(1, mounted.controller.doc.scene.elementsInOrder.count { it is RectElement })
        rule.onNodeWithTag(CrosshairTags.EXIT).performClick()
        assertEquals(null, mounted.access.crosshair, "the exit button leaves Crosshair mode")
        mounted.session.close()
    }

    @Test
    fun escOrder_should_TakeRow6ThenRow7ThenRow10_When_CrosshairHoldsAMidDrawRectangle() {
        mounted = mount()
        focusCanvas(ToolId.Rectangle)
        press(listOf(Key.Enter, Key.Enter))
        assertEquals(setOf(EscRow.MidDraw, EscRow.Crosshair, EscRow.ActiveTool), mounted.rows())
        press(listOf(Key.Escape))
        assertEquals(setOf(EscRow.Crosshair, EscRow.ActiveTool), mounted.rows(), "the first Esc cancels the half-drawn rectangle and stays in Crosshair mode")
        assertEquals(0, mounted.controller.doc.scene.elementsInOrder.size, "Esc added nothing")
        press(listOf(Key.Escape))
        assertEquals(setOf(EscRow.ActiveTool), mounted.rows(), "the second Esc leaves Crosshair mode")
        press(listOf(Key.Escape))
        assertEquals(emptySet(), mounted.rows(), "the third Esc returns to Select")
        mounted.session.close()
    }

    @Test
    fun escOrder_should_TakeRow3BeforeRow7_When_CrosshairPlacedATextPoint() {
        mounted = mount()
        focusCanvas(ToolId.Text)
        press(listOf(Key.Enter, Key.Enter))
        assertTrue(EscRow.TextOverlayOrDimensionField in mounted.rows(), "placing the Text tool's point opens its overlay (row 3)")
        rule.onNodeWithTag(ShellTags.CANVAS).requestFocus()
        press(listOf(Key.Escape))
        assertEquals(false, EscRow.TextOverlayOrDimensionField in mounted.rows(), "the first Esc takes row 3")
        assertEquals(true, EscRow.Crosshair in mounted.rows(), "Crosshair mode is still on after row 3")
        mounted.session.close()
    }

    @Test
    fun escOrder_should_TakeRow5_When_AConstraintPickerIsArmed() {
        mounted = mount()
        rule.runOnUiThread { mounted.controller.constraints.arm(ConstrainMenuItem.Horizontal) }
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.CANVAS).requestFocus()
        assertEquals(setOf(EscRow.ArmedPicker), mounted.rows())
        press(listOf(Key.Escape))
        assertEquals(null, mounted.controller.constraints.picker, "Esc disarms the picker and adds nothing")
        mounted.session.close()
    }

    @Test
    fun arrowKey_should_PanTheViewByFortyDp_When_NothingIsSelected() {
        mounted = mount()
        rule.onNodeWithTag(ShellTags.CANVAS).requestFocus()
        val before = mounted.controller.ui.viewport.panX
        press(listOf(Key.DirectionRight))
        assertEquals(before - 40.0 * 2.0, mounted.controller.ui.viewport.panX, 0.001, "40 dp at 2 px per dp, against the arrow")
        mounted.session.close()
    }

    @Test
    fun undo_should_SayWhatItUndid_When_AnEditIsUndone() {
        mounted = mount()
        val rect = filledRectEl("r1", 0, 0, 10, 10)
        rule.runOnUiThread {
            mounted.controller.submit(AddElement(rect))
            mounted.controller.undo()
        }
        val said = generateSequence(0L) { it + 2_000L }.take(5).mapNotNull { mounted.controller.notices.nextAnnouncement(it)?.text }.toList()
        assertTrue(said.any { it.startsWith("Undid: add ") }, "undo announces the edit it undid: $said")
        mounted.session.close()
    }

    @Test
    fun largeDrawing_should_DegradeAndRaiseItsBanner_When_TheSceneCrossesTheSoftThreshold() {
        val limits = SceneLimits(softElementThreshold = 1)
        var counter = 0
        val controller = SketchEditorController(
            dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2(),
            EditorPorts(StandardToolRegistry.create(FixedAdvanceTextMetricsProvider(), limits, false), ids = ElementIdSource { dev.stapler.stelekit.sketch.v2.scene.ElementId("e${counter++}") }, limits = limits),
            Dispatchers.Default,
        )
        val port = InMemoryDiagnosticsPort(SketchEditorStrings.Banners, limits)
        val scene = EditorLargeScene(controller, port, HostServices(limits = limits))
        scene.refresh()
        assertEquals(false, scene.degradation.degraded, "an empty drawing is not degraded")
        controller.submit(AddElement(filledRectEl("a", 0, 0, 10, 10)))
        scene.refresh()
        assertTrue(scene.degradation.has(DegradationStep.SketchyOff) && scene.degradation.has(DegradationStep.ConstraintGlyphsHidden))
        assertTrue(port.diagnostics.value.entries.any { it.row == BannerRowId.LargeDrawing }, "the LargeDrawing banner is raised")
        scene.reenableEffects()
        assertEquals(false, scene.degradation.has(DegradationStep.SketchyOff), "Re-enable effects turns the effects back on")
        assertEquals(false, port.diagnostics.value.entries.any { it.row == BannerRowId.LargeDrawing }, "and withdraws the banner")
        scene.close()
        controller.close()
    }

    private companion object {
        const val STEPS = 8
    }
}
