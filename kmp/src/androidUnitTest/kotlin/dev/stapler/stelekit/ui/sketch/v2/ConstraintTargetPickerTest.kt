// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 4.2.6b (UX-98): the armed picker on a screen. Robolectric; Gradle-only like the other Compose host tests (see BUILD.bazel).
 * Two lines meet at (10, 0); markers sit at 10 dp per unit, offset by 50 dp so every marker is inside the canvas.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
class ConstraintTargetPickerTest {
    @get:Rule
    val rule = createComposeRule()

    private fun u(i: Int) = SceneUnit.ofInt(i)

    private fun line(id: String, x0: Int, y0: Int, x1: Int, y1: Int) = LineElement(ElementBase(ElementId(id)), listOf(ScenePoint(u(x0), u(y0)), ScenePoint(u(x1), u(y1))))

    private val doc: SketchDocumentV2 = AddElements(
        listOf(line("a", 0, 0, 10, 0), line("b", 10, 0, 10, 10), EllipseElement(ElementBase(ElementId("c")), u(20), u(5), u(2), u(2))),
    ).apply(SketchDocumentV2()).fold({ error("$it") }, { it.doc })

    private class Run {
        var armed by mutableStateOf(true)
        var state by mutableStateOf(TargetPicker.arm(ConstraintKind.Coincident))
        var message by mutableStateOf<String?>(null)
        var completed by mutableStateOf<PickStep.Complete?>(null)
    }

    private fun show(run: Run, kind: ConstraintKind = ConstraintKind.Coincident, pointer: PointerClass = PointerClass.MouseOnly) {
        run.state = TargetPicker.arm(kind)
        rule.setContent { Harness(run, pointer) }
    }

    @Composable
    private fun Harness(run: Run, pointer: PointerClass) {
        if (!run.armed) {
            Text("disarmed", Modifier.testTag("disarmed"))
            return
        }
        val candidates = remember(run.state) { TargetPicker.candidates(doc, run.state, doc.scene.order) }
        val onPick = { target: ConstraintTarget ->
            when (val step = TargetPicker.pick(doc, run.state, target, { ConstraintId("k1") })) {
                is PickStep.Next -> { run.state = step.state; run.message = null }
                is PickStep.Rejected -> run.message = step.message
                is PickStep.Complete -> { run.completed = step; run.armed = false }
            }
        }
        val actions = TargetPickerActions(onPick, { run.state = it }, { run.armed = false })
        Column {
            Text(TargetPicker.statusText(run.state, candidates), Modifier.testTag(PickerTags.STATUS))
            Text(run.message.orEmpty(), Modifier.testTag("message"))
            ActionChipRow(listOf(TargetPicker.cancelChip { run.armed = false }))
            TargetListPicker(candidates, onPick)
            Box(Modifier.size(CANVAS_DP.dp)) {
                ConstraintTargetOverlay(candidates, run.state, pointer, { x, y -> (x * SCALE + OFFSET) to (y * SCALE + OFFSET) }, actions)
            }
        }
    }

    private fun marker(element: String, part: TargetPart) = rule.onNodeWithTag(PickerTags.marker("$element:${part.label}"))

    @Test
    fun ux98_picker_should_MarkByShapeNameInText_And_WorkByTapKeyboardCycleAndList() {
        val run = Run()
        show(run)
        rule.onNodeWithTag(PickerTags.STATUS).assertTextEquals("Pick the first point")
        marker("a", TargetPart.End).assertIsDisplayed()
        marker("c", TargetPart.Centre).assertIsDisplayed()

        // keyboard: Tab cycles in z order then Start, End (a start, a end, b start ...); the focused marker is named in a chip
        rule.onNodeWithTag(PickerTags.OVERLAY).performKeyInput { pressKey(Key.Tab) }
        rule.onNodeWithTag(PickerTags.CHIP).assertTextEquals("Endpoint of Line 1")
        rule.onNodeWithTag(PickerTags.OVERLAY).performKeyInput { pressKey(Key.Tab) }
        rule.onNodeWithTag(PickerTags.CHIP).assertTextEquals("Endpoint of Line 1, also: Line 2 start")
        rule.onNodeWithTag(PickerTags.OVERLAY).performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithTag(PickerTags.STATUS).assertTextEquals("Pick the second point")
        assertEquals(listOf(ConstraintTarget(ElementId("a"), TargetPart.End)), run.state.picks)

        // the same part twice: Tab, Tab to Line 1 end again, Enter
        rule.onNodeWithTag(PickerTags.OVERLAY).performKeyInput { pressKey(Key.Tab) }
        rule.onNodeWithTag(PickerTags.OVERLAY).performKeyInput { pressKey(Key.Tab) }
        rule.onNodeWithTag(PickerTags.OVERLAY).performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithTag("message").assertTextEquals("Pick a different target")
        assertEquals(1, run.state.picks.size)

        // the Pick target list: grouped by element, by name, and picking from it is a pick
        rule.onNodeWithTag(PickerTags.LIST_BUTTON).performClick()
        rule.onNodeWithTag(PickerTags.listRow("b:start")).assertTextEquals("Line 2 start")
        rule.onNodeWithTag(PickerTags.listRow("c:centre")).assertTextEquals("Circle 1 centre")
        rule.onNodeWithTag(PickerTags.listRow("b:start")).performClick()

        val done = run.completed
        assertTrue(done != null, "two picks complete the constraint")
        assertEquals("Added Coincident: Line 1 end, Line 2 start", done.details)
        rule.onNodeWithTag("disarmed").assertIsDisplayed()
    }

    @Test
    fun ux98_picker_should_PickByTapAlone_WithNoDrag() {
        val run = Run()
        show(run)
        marker("a", TargetPart.Start).performClick()
        marker("b", TargetPart.End).performClick()
        assertEquals("Added Coincident: Line 1 start, Line 2 end", run.completed?.details)
    }

    @Test
    fun ux98_picker_should_ShowOnlyTargetsTheArmedConstraintCanUse() {
        val run = Run()
        show(run, ConstraintKind.Parallel)
        rule.onNodeWithTag(PickerTags.STATUS).assertTextEquals("Pick an edge")
        marker("a", TargetPart.Edge(0)).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTagCount(PickerTags.marker("a:start")) == 0, "a point is not a Parallel target")
    }

    @Test
    fun ux98_cancelChip_should_DisarmWithoutCommitting_And_EmptyCanvasTapMustNotCancel() {
        val run = Run()
        show(run)
        marker("a", TargetPart.Start).performClick()
        rule.onNodeWithTag(PickerTags.OVERLAY).performTouchInput { click(Offset(CANVAS_DP * 2f, CANVAS_DP * 2f)) }
        assertTrue(run.armed, "tapping empty canvas does not cancel")
        assertEquals(1, run.state.picks.size, "the first pick survives a mis-tap")

        rule.onNodeWithTag(PickerTags.CANCEL).assertHeightIsAtLeast(TouchTargetFloor.TOUCH_DP.dp).performClick()
        assertTrue(!run.armed)
        assertNull(run.completed, "nothing was committed")
        rule.onNodeWithTag("disarmed").assertIsDisplayed()
    }

    @Test
    fun ux98_escape_should_CancelTheArmedPicker() {
        val run = Run()
        show(run)
        marker("a", TargetPart.Start).performClick()
        rule.onNodeWithTag(PickerTags.OVERLAY).performKeyInput { pressKey(Key.Escape) }
        assertTrue(!run.armed)
        assertNull(run.completed)
    }

    @Test
    fun ux92_markers_should_HaveATouchFloorHitArea_WhenATouchOrPenPointerIsPresent() {
        val touch = Run()
        show(touch, pointer = PointerClass.TouchOrPen)
        marker("a", TargetPart.Start).assertWidthIsAtLeast(TouchTargetFloor.TOUCH_DP.dp).assertHeightIsAtLeast(TouchTargetFloor.TOUCH_DP.dp)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private companion object {
        const val SCALE = 10.0
        const val OFFSET = 50.0
        const val CANVAS_DP = 400
    }
}
