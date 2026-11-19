// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
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

/**
 * Task 6.2.4h: the all-rows assertion of the one Esc order (ux.md section 17) over the real host. Every row the host can hold at
 * once is put in effect through its owner, then Esc is pressed on the focused canvas and each press must take the first row of
 * the order and change nothing else. The per-row tests stay in their owning stories; rows whose owner needs a window or a clock
 * this harness lacks (the dialog's own window, a running solve, a focused toast, the text field's own key handler, Crosshair mode, which the host does not mount) are not driven here (rows 1, 3, 4, 5, 7, 9 and 12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(ExperimentalTestApi::class)
class EscOrderIntegrationTest {
    @get:Rule
    val rule = createComposeRule()

    private class Mounted(val session: SketchEditorSession) {
        val controller get() = session.controller
        val state get() = controller.ui
        fun rows(): Set<EscRow> = controller.escRows() + session.extras!!.escRows() + ShellEscape.shellRows(state)
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

    private fun pressEsc() {
        rule.onNodeWithTag(ShellTags.CANVAS).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
    }

    private fun drawRectangleUnderToolLock(host: Mounted) {
        rule.runOnUiThread {
            host.state.toolLock = true
            host.controller.tools.chooseTool(ToolId.Rectangle)
        }
        rule.waitForIdle()
        rule.onNodeWithTag(HostTags.CANVAS_INPUT).performMouseInput {
            moveTo(Offset(40f, 40f))
            press()
            moveTo(Offset(100f, 70f))
            moveTo(Offset(140f, 110f))
            release()
        }
        rule.waitForIdle()
    }

    private fun startSecondRectangleWithoutFinishing(host: Mounted) {
        rule.runOnUiThread {
            for ((phase, at) in listOf(RawPhase.Down to ScreenPx(200.0, 200.0), RawPhase.Move to ScreenPx(260.0, 240.0))) {
                host.controller.tools.onPointer(RawPointer(2, PointerKind.Mouse, phase, at, timestamp = 100L))
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun escOrder_should_TakeRows2_6_8_10And11InOrder_When_TheyAreAllInEffectInTheRealHost() {
        val host = mount()
        drawRectangleUnderToolLock(host)
        startSecondRectangleWithoutFinishing(host)
        val committedId = host.controller.doc.scene.elementsInOrder.filterIsInstance<RectElement>().single().id.value
        rule.runOnUiThread {
            host.state.selection = setOf(committedId)
            host.state.panPadOpen = true
            host.state.walkthrough.start(InputKind.Keyboard, grouped = true, returnFocus = "canvas")
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.CANVAS).requestFocus()
        val committed = host.controller.doc.scene
        assertEquals(1, committed.elementsInOrder.count { it is RectElement }, "one rectangle is committed before the presses")
        assertEquals(
            setOf(EscRow.MenuOrFlyout, EscRow.MidDraw, EscRow.Walkthrough, EscRow.ActiveTool, EscRow.Selection),
            host.rows(),
            "every row of this walk is in effect before the first press",
        )

        val expected = listOf(EscRow.MenuOrFlyout, EscRow.MidDraw, EscRow.Walkthrough, EscRow.ActiveTool, EscRow.Selection)
        val taken = ArrayList<EscRow>()
        var before = host.rows()
        repeat(expected.size) { press ->
            pressEsc()
            val after = host.rows()
            val removed = before - after
            assertEquals(1, removed.size, "press ${press + 1} took exactly one row (before=$before after=$after)")
            assertEquals(expected[press], removed.single(), "press ${press + 1} took the first row of the order that applied")
            assertTrue(after.all { it in before }, "press ${press + 1} added no row")
            assertEquals(committed, host.controller.doc.scene, "Esc never undoes a committed edit (press ${press + 1})")
            taken += removed.single()
            before = after
        }
        assertEquals(expected, taken)
        assertEquals(emptySet(), host.rows(), "nothing applies after the last row")
        pressEsc()
        assertEquals(committed, host.controller.doc.scene, "a press with no row left changes nothing and never closes the editor")
        host.session.close()
    }

    @Test
    fun escOrder_should_EndTheTourWithoutTouchingTheMidDrawPreview_When_OnlyThoseTwoRowsApply() {
        val host = mount()
        rule.runOnUiThread { host.controller.tools.chooseTool(ToolId.Rectangle) }
        startSecondRectangleWithoutFinishing(host)
        rule.runOnUiThread { host.state.walkthrough.start(InputKind.Keyboard, grouped = true, returnFocus = "canvas") }
        rule.onNodeWithTag(ShellTags.CANVAS).requestFocus()
        rule.waitForIdle()
        assertTrue(EscRow.MidDraw in host.rows() && EscRow.Walkthrough in host.rows())

        pressEsc()

        assertEquals(false, EscRow.MidDraw in host.rows(), "row 6 (the mid-draw preview) comes before row 8 (the tour) and is taken first")
        assertEquals(true, EscRow.Walkthrough in host.rows(), "the tour is still showing after the first press")
        host.session.close()
    }
}
