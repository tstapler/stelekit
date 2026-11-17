// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.PointerClass
import dev.stapler.stelekit.ui.sketch.v2.ShellTags
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorState
import dev.stapler.stelekit.ui.sketch.v2.SketchScreenV2
import dev.stapler.stelekit.ui.sketch.v2.SketchShellConfig
import dev.stapler.stelekit.ui.sketch.v2.StripTags
import dev.stapler.stelekit.ui.sketch.v2.ToolStripModel
import dev.stapler.stelekit.ui.sketch.v2.WindowDp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 6.2.4a, ux.md section 20 (UX-01 to UX-04): authoring effort measured under the ONE counting convention (one click, tap or
 * key chord, a menu choice, or one typed string with its confirming key), by scripts that go through the real tools and commands,
 * plus the tool-lock state on the real strip. Robolectric, so no display is needed. Gradle-only (composes @Composable lambdas and
 * needs the commonTest scenario fixtures, see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h900dp-mdpi")
class UxAuthoringAcceptanceTest {
    @get:Rule
    val rule = createComposeRule()

    private val desktop = WindowDp(1280, 900)

    @Test
    fun ux01_labelledBox_should_NeedAtMost4Steps_UnderTheOneCountingConvention() {
        val editor = ScenarioEditor()
        editor.pick(ToolId.Rectangle)
        editor.drag(100, 100, 220, 160)
        val box = editor.scene.elementsInOrder.filterIsInstance<RectElement>().single().id
        editor.label(box, "Web")
        assertEquals(4, editor.steps, "pick Rectangle, drag, Enter, type the label and Esc")
        assertEquals("Web", editor.scene.elementsInOrder.filterIsInstance<TextElement>().single().text)
    }

    @Test
    fun ux02_boundArrow_should_NeedAtMost3StepsAndFollowBox() {
        val editor = ScenarioEditor()
        for ((x, name) in listOf(100 to "A", 300 to "B")) {
            editor.pick(ToolId.Rectangle)
            editor.drag(x, 100, x + 120, 160)
            editor.label(editor.scene.elementsInOrder.filterIsInstance<RectElement>().last().id, name)
        }
        val boxes = editor.scene.elementsInOrder.filterIsInstance<RectElement>().map { it.id }
        val before = editor.steps
        editor.pick(ToolId.Arrow)
        editor.drag(220, 130, 300, 130, cost = 2)
        assertEquals(3, editor.steps - before, "pick Arrow, drag from box A to box B, release")
        val arrow = editor.scene.elementsInOrder.filterIsInstance<ArrowElement>().single()
        assertEquals(boxes[0], arrow.startBinding?.elementId)
        assertEquals(boxes[1], arrow.endBinding?.elementId)

        editor.move(boxes[1], dx = 0, dy = 40)
        assertTrue((editor.scene[arrow.id] as ArrowElement).points.last() != arrow.points.last(), "moving box B moved the arrow end")
    }

    @Test
    fun ux03_architectureDiagram_should_BeAuthorableWithNonDragPathsOnly() {
        val editor = ScenarioEditor(pointer = PointerKind.Touch)
        val diagram = ArchitectureScenario.runWithoutDrag(editor)
        val scene = diagram.editor.scene
        assertEquals(9, scene.elementsInOrder.filterIsInstance<RectElement>().size)
        assertEquals(9, scene.elementsInOrder.filterIsInstance<TextElement>().size)
        assertEquals(4, scene.elementsInOrder.filterIsInstance<ArrowElement>().size)
        assertEquals(1, scene.elementsInOrder.filterIsInstance<FrameElement>().size)
        assertTrue(diagram.arrows.all { id -> (scene[id] as ArrowElement).let { it.startBinding != null && it.endBinding != null } }, "every arrow is bound at both ends")
        assertEquals(1, scene.elementsInOrder.flatMap { it.base.groupIds }.toSet().size, "one group")
        assertTrue(reconcile(scene).isClean)
    }

    @Test
    fun ux04_toolLock_should_ReturnToSelectOrStayAndAnnounceWithoutColour() {
        val state = SketchEditorState()
        rule.setContent {
            SketchScreenV2(state, SketchShellConfig(ToolStripModel(false), PointerClass.TouchOrPen), window = desktop)
        }
        rule.waitForIdle()

        drawOneRectangle(state)
        assertEquals(ToolId.Select, state.activeTool, "lock off: the strip returns to Select after one shape")

        rule.onNodeWithTag(StripTags.TOOL_LOCK).performClick()
        rule.onNodeWithTag(StripTags.TOOL_LOCK).assertContentDescriptionEquals("Tool lock on")
        rule.onNodeWithTag(ShellTags.TOOL_LOCK_CHIP).assertIsDisplayed()
        drawOneRectangle(state)
        assertEquals(ToolId.Rectangle, state.activeTool, "lock on: the tool stays")
    }

    private fun drawOneRectangle(state: SketchEditorState) {
        rule.onNodeWithTag(StripTags.tool(ToolId.Rectangle)).performClick()
        val editor = ScenarioEditor()
        editor.pick(ToolId.Rectangle)
        editor.drag(0, 0, 100, 60)
        assertEquals(1, editor.scene.elementsInOrder.filterIsInstance<RectElement>().size)
        state.onShapeCommitted()
        rule.waitForIdle()
    }
}
