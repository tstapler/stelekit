// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.1.3 (UX-93): fold, unfold, rotation and recreation keep the selection, tool, tool lock, open tab, view, the
 * text being typed and the focused control. Robolectric; Gradle-only like the other Compose host tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi")
class AdaptiveStateSurvivalTest {
    @get:Rule
    val rule = createComposeRule()

    private val folded = WindowDp(360, 640)
    private val unfolded = WindowDp(1280, 800)

    private fun busyState() = SketchEditorState(
        activeTool = ToolId.Ellipse,
        toolLock = true,
        panelTab = PanelTab.Constraints,
        selection = setOf("a", "b"),
        viewport = SketchViewport.of(zoom = 2.0, panX = 40.0, panY = -12.0),
        textDraft = "half a labe",
    )

    private fun assertBusy(state: SketchEditorState) {
        assertEquals(ToolId.Ellipse, state.activeTool)
        assertTrue(state.toolLock)
        assertEquals(PanelTab.Constraints, state.panelTab)
        assertEquals(setOf("a", "b"), state.selection)
        assertEquals(SketchViewport.of(zoom = 2.0, panX = 40.0, panY = -12.0), state.viewport)
        assertEquals("half a labe", state.textDraft)
    }

    @Test
    fun foldAndUnfold_should_KeepSelectionToolLockTabViewAndTextDraft() {
        var window by mutableStateOf(folded)
        val state = busyState()
        rule.setContent {
            SketchScreenV2(state, SketchShellConfig(ToolStripModel(false), PointerClass.TouchOrPen), window = window) {
                Box { Text(state.textDraft.orEmpty(), Modifier.testTag("draft")) }
            }
        }
        rule.onNodeWithTag(ShellTags.layout(StripPlacement.BottomDock)).assertExists()
        for (next in listOf(unfolded, folded, WindowDp(800, 360), folded)) {
            window = next
            rule.waitForIdle()
            assertBusy(state)
            rule.onNodeWithTag("draft").assertTextEquals("half a labe")
            rule.onNodeWithTag(ShellTags.tab(PanelTab.Constraints)).assertExists()
        }
    }

    @Test
    fun theActiveToolButton_should_StayMarkedSelected_AfterTheLayoutChanges() {
        var window by mutableStateOf(unfolded)
        val state = SketchEditorState(activeTool = ToolId.Rectangle)
        rule.setContent { SketchScreenV2(state, SketchShellConfig(ToolStripModel(false), PointerClass.TouchOrPen), window = window) }
        rule.onNodeWithTag(StripTags.tool(ToolId.Rectangle)).assertIsSelected()
        window = folded
        rule.waitForIdle()
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Shapes)).assertIsSelected()
    }

    @Test
    fun focus_should_ReturnToTheSameLogicalControl_AfterTheLayoutChanges() {
        var window by mutableStateOf(unfolded)
        val state = SketchEditorState()
        rule.setContent { SketchScreenV2(state, SketchShellConfig(ToolStripModel(false), PointerClass.TouchOrPen), window = window) }
        rule.onNodeWithTag(StripTags.UNDO).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        rule.onNodeWithTag(StripTags.UNDO).assertIsFocused()
        assertEquals("undo", state.focusedControl)
        window = folded
        rule.waitForIdle()
        rule.onNodeWithTag(StripTags.UNDO).assertIsFocused()
        window = WindowDp(800, 360)
        rule.waitForIdle()
        rule.onNodeWithTag(StripTags.UNDO).assertIsFocused()
    }

    @Test
    fun recreation_should_RestoreTheEditorState_When_TheActivityIsRecreated() {
        val tester = StateRestorationTester(rule)
        var seen: SketchEditorState? = null
        tester.setContent {
            val state = rememberSketchEditorState()
            seen = state
            SketchScreenV2(state, SketchShellConfig(ToolStripModel(false), PointerClass.TouchOrPen), window = folded)
        }
        val first = seen!!
        val busy = busyState()
        first.activeTool = busy.activeTool
        first.toolLock = busy.toolLock
        first.panelTab = busy.panelTab
        first.selection = busy.selection
        first.viewport = busy.viewport
        first.textDraft = busy.textDraft
        first.focusedControl = "undo"
        rule.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        val restored = seen!!
        assertBusy(restored)
        assertEquals("undo", restored.focusedControl)
    }

    @Test
    fun noTextDraft_should_StayNullAcrossRecreation() {
        val tester = StateRestorationTester(rule)
        var seen: SketchEditorState? = null
        tester.setContent {
            seen = rememberSketchEditorState()
            SketchScreenV2(seen!!, SketchShellConfig(ToolStripModel(false), PointerClass.TouchOrPen), window = folded)
        }
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(null, seen!!.textDraft)
        assertEquals(null, seen!!.panelTab)
    }
}
