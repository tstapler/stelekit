// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import dev.stapler.stelekit.sketch.v2.tools.ElementTransform
import dev.stapler.stelekit.sketch.v2.tools.MoveSelectionCommand
import dev.stapler.stelekit.sketch.v2.tools.RectSpec
import dev.stapler.stelekit.sketch.v2.tools.ToolCommand
import dev.stapler.stelekit.sketch.v2.tools.TransformSelectionCommand
import dev.stapler.stelekit.sketch.v2.tools.elId
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sceneOfElements
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Stories 3.6.3 and the pan pad (UX-40, UX-120, WCAG 2.5.7): every drag has a taps-only alternative. Robolectric;
 * Gradle-only like the other Compose host tests (see BUILD.bazel).
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-mdpi")
class NonDragTransformTest {
    @get:Rule
    val rule = createComposeRule()

    private val phone = WindowDp(360, 640)
    private val scene = sceneOfElements(filledRectEl("r1", 0, 0, 100, 60), filledRectEl("l1", 300, 0, 40, 40, RectSpec(locked = true)))
    private val commands = mutableListOf<ToolCommand>()
    private val notices = mutableListOf<Pair<NonDragNotice, Int>>()
    private var exited = 0

    private fun showPanel(selection: List<String> = listOf("r1")) {
        val callbacks = NonDragCallbacks({ commands += it }, { n, skipped -> notices += n to skipped }, { exited++ })
        rule.setContent { NonDragTransformPanel(scene, selection.map(::elId), callbacks) }
        rule.waitForIdle()
    }

    private fun tap(tag: String) = rule.onNodeWithTag(tag).performClick()

    @Test
    fun resize_should_GrowTheWidthBy30_FromThreeTapsRightAtTenUnits_AndApplyOnce() {
        showPanel()
        tap(NonDragTags.action(NonDragAction.Resize))
        repeat(3) { tap(NonDragTags.nudge("right")) }
        rule.onNodeWithTag(NonDragTags.PREVIEW).assertTextEquals("width 100 → 130, height 60 → 60")
        assertTrue(commands.isEmpty(), "nothing is committed before Apply")
        tap(NonDragTags.APPLY)
        val fit = assertIs<TransformSelectionCommand>(commands.single()).steps.single() as ElementTransform.Fit
        assertEquals(130.0, fit.width)
    }

    @Test
    fun moveElement_should_ReadXPlus30_AndCommitOneMoveCommand() {
        showPanel()
        tap(NonDragTags.action(NonDragAction.MoveElement))
        repeat(3) { tap(NonDragTags.nudge("right")) }
        rule.onNodeWithTag(NonDragTags.PREVIEW).assertTextEquals("x +30, y 0")
        tap(NonDragTags.APPLY)
        assertEquals(30.0, assertIs<MoveSelectionCommand>(commands.single()).dx)
    }

    @Test
    fun cancel_should_DiscardThePendingNudge_ThenALaterCancelLeavesTheMode() {
        showPanel()
        tap(NonDragTags.action(NonDragAction.MoveElement))
        tap(NonDragTags.nudge("down"))
        tap(NonDragTags.CANCEL)
        tap(NonDragTags.APPLY)
        assertTrue(commands.isEmpty())
        assertEquals(0, exited)
        tap(NonDragTags.CANCEL)
        assertEquals(1, exited)
    }

    @Test
    fun aLockedElement_should_ShowTheLockedMessage_AndRunNothing() {
        showPanel(listOf("l1"))
        tap(NonDragTags.action(NonDragAction.Resize))
        rule.onNodeWithTag(NonDragTags.NOTICE).assertTextEquals(NonDragNotice.Locked.text)
        tap(NonDragTags.APPLY)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun applyingAClampedResize_should_ReportMinimumSizeReached() {
        showPanel()
        tap(NonDragTags.action(NonDragAction.Resize))
        repeat(CLAMP_TAPS) { tap(NonDragTags.nudge("left")) }
        rule.onNodeWithTag(NonDragTags.PREVIEW).assertTextEquals(NonDragNotice.MinimumSize.text)
        tap(NonDragTags.APPLY)
        assertEquals(NonDragNotice.MinimumSize to 0, notices.single())
    }

    @Test
    fun keyboard_should_NudgeWithArrows_ShiftIsTenUnits_EnterApplies_EscCancels() {
        showPanel()
        tap(NonDragTags.action(NonDragAction.MoveElement))
        tap(NonDragTags.step(NudgeStep.One))
        rule.onNodeWithTag(NonDragTags.PANEL).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag(NonDragTags.PANEL).performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag(NonDragTags.PREVIEW).assertTextEquals("x +1, y 0")
        rule.onNodeWithTag(NonDragTags.PANEL).performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.DirectionDown); keyUp(Key.ShiftLeft) }
        rule.onNodeWithTag(NonDragTags.PREVIEW).assertTextEquals("x +1, y +10")
        rule.onNodeWithTag(NonDragTags.PANEL).performKeyInput { pressKey(Key.Enter) }
        assertEquals(1.0, assertIs<MoveSelectionCommand>(commands.single()).dx)
        tap(NonDragTags.action(NonDragAction.MoveElement))
        tap(NonDragTags.nudge("up"))
        rule.onNodeWithTag(NonDragTags.PANEL).performKeyInput { pressKey(Key.Escape) }
        rule.onNodeWithTag(NonDragTags.PREVIEW).assertTextEquals("x 0, y 0")
        assertEquals(1, commands.size)
    }

    // ---- the bottom sheet and the pan pad, taps only ------------------------------------------------------------

    private fun showShell(state: SketchEditorState, config: SketchShellConfig = SketchShellConfig(ToolStripModel(true), PointerClass.TouchOrPen)) {
        rule.setContent { SketchScreenV2(state, config, window = phone) }
        rule.waitForIdle()
    }

    @Test
    fun theSheet_should_OpenResizeAndClose_WithTapsOnly() {
        val state = SketchEditorState(panelTab = PanelTab.Style)
        showShell(state)
        rule.onNodeWithTag(ShellTags.PANEL).assertIsDisplayed()
        rule.onNodeWithTag(SheetTags.TOGGLE).assertTextEquals("Expand")
        tap(SheetTags.HANDLE)
        assertEquals(SheetSize.Full, state.sheetSize)
        rule.onNodeWithTag(SheetTags.TOGGLE).assertTextEquals("Collapse")
        tap(SheetTags.TOGGLE)
        assertEquals(SheetSize.Half, state.sheetSize)
        tap(SheetTags.CLOSE)
        assertEquals(null, state.panelTab)
        rule.onNodeWithTag(ShellTags.PANEL).assertDoesNotExist()
    }

    @Test
    fun theSheetHandle_should_ResizeWithUpAndDownArrows_WhenFocused() {
        val state = SketchEditorState(panelTab = PanelTab.Style)
        showShell(state)
        rule.onNodeWithTag(SheetTags.HANDLE).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag(SheetTags.HANDLE).performKeyInput { pressKey(Key.DirectionUp) }
        assertEquals(SheetSize.Full, state.sheetSize)
        rule.onNodeWithTag(SheetTags.HANDLE).performKeyInput { pressKey(Key.DirectionDown) }
        assertEquals(SheetSize.Half, state.sheetSize)
    }

    @Test
    fun theSheetSize_should_SurviveARecreation() {
        val state = SketchEditorState(panelTab = PanelTab.Style, sheetSize = SheetSize.Full)
        val scope = SaverScope { true }
        val saved = with(SketchEditorState.Saver) { scope.save(state) }
        val restored = SketchEditorState.Saver.restore(checkNotNull(saved))
        assertEquals(SheetSize.Full, restored?.sheetSize)
        assertEquals(PanelTab.Style, restored?.panelTab)
    }

    @Test
    fun autoPanArrows_should_ShowNoButtonWithFingerDrawingOff() {
        showShell(SketchEditorState())
        rule.onNodeWithTag(PanTags.BUTTON).assertDoesNotExist()
    }

    @Test
    fun autoPanArrows_should_ShowTheButtonWithFingerDrawingOnAndATouchPointer() {
        showShell(SketchEditorState(fingerDrawing = true))
        rule.onNodeWithTag(PanTags.BUTTON).assertIsDisplayed()
    }

    @Test
    fun alwaysPanArrows_should_PanAQuarterOfTheCanvasPerTap_AndClosePadOnSecondActivation() {
        val state = SketchEditorState()
        showShell(state, SketchShellConfig(ToolStripModel(true), PointerClass.TouchOrPen, panArrows = PanArrows.Always))
        val canvasWidthPx = rule.onNodeWithTag(ShellTags.CANVAS).fetchSemanticsNode().size.width
        rule.onNodeWithTag(PanTags.PAD).assertDoesNotExist()
        tap(PanTags.BUTTON)
        rule.onNodeWithTag(PanTags.PAD).assertIsDisplayed()
        tap(PanTags.arrow(PanDirection.Right))
        assertEquals(-canvasWidthPx / 4.0, state.viewport.panX, 1.0)
        tap(PanTags.arrow(PanDirection.Up))
        assertTrue(state.viewport.panY > 0.0)
        tap(PanTags.BUTTON)
        rule.onNodeWithTag(PanTags.PAD).assertDoesNotExist()
    }

    @Test
    fun theOverflowMenu_should_OpenThePad_OnADesktopWithNoPanButton() {
        val state = SketchEditorState()
        showShell(state, SketchShellConfig(ToolStripModel(true), PointerClass.MouseOnly))
        rule.onNodeWithTag(PanTags.BUTTON).assertDoesNotExist()
        tap(ShellTags.OVERFLOW)
        tap(ShellTags.OVERFLOW_PAN)
        rule.onNodeWithTag(PanTags.PAD).assertIsDisplayed()
    }

    @Test
    fun theGridPad_should_HaveFiveCellsAndTheRowFour_BothAtTheTouchFloor() {
        rule.setContent { PanPad(PanVariant.Grid, { _, _ -> }, {}, {}) }
        for (d in PanDirection.entries) rule.onNodeWithTag(PanTags.arrow(d)).assertIsDisplayed()
        rule.onNodeWithTag(PanTags.FIT).assertIsDisplayed()
        val cell = rule.onNodeWithTag(PanTags.arrow(PanDirection.Up)).fetchSemanticsNode().size
        assertEquals(PanPadPolicy.CELL_DP * rule.density.density, cell.width.toFloat(), 1f)
    }

    @Test
    fun theRowPad_should_DropFit() {
        rule.setContent { PanPad(PanVariant.Row, { _, _ -> }, {}, {}) }
        for (d in PanDirection.entries) rule.onNodeWithTag(PanTags.arrow(d)).assertIsDisplayed()
        rule.onNodeWithTag(PanTags.FIT).assertDoesNotExist()
    }

    private companion object {
        const val CLAMP_TAPS = 11
    }
}

