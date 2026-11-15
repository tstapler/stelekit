// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.stapler.stelekit.model.MeasurementUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** X-14 / X-15 / X-16 (UX-14..UX-16): radio semantics, the Keep tool toggle and the Calibrate-first reason. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h891dp-xhdpi")
class AnnotationToolbarSemanticsTest {
    @get:Rule
    val rule = createComposeRule()

    private val labels = listOf("Select", "Distance", "Area", "Angle", "Label", "Reference")

    private fun render(
        tool: AnnotationTool,
        locked: Boolean = false,
        calibrated: Boolean = true,
        onSelect: (AnnotationTool) -> Unit = {},
        onToggle: () -> Unit = {},
        onDisabledTap: (AnnotationTool) -> Unit = {},
    ) {
        rule.setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                AnnotationToolbar(
                    currentTool = tool, canUndo = true, canRedo = false, displayUnit = MeasurementUnit.METERS,
                    isCalibrated = calibrated, onToolSelect = onSelect, onUndo = {}, onRedo = {}, onDeleteSelect = {},
                    onCalibrate = {}, onUnitSelect = {}, hasSelection = false, zoom = null,
                    keep = ToolbarKeep(locked, onToggle), onDisabledToolTap = onDisabledTap,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun role(chip: String) =
        rule.onNode(hasContentDescription(chip)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)

    @Test
    fun chips_should_BeRadioButtonsInSelectableGroup_AndAtLeast48dp() {
        render(AnnotationTool.DISTANCE)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertCountEquals(1)
        labels.forEach { assertEquals(Role.RadioButton, role(it), "$it role") }
        rule.onNode(hasContentDescription("Distance")).assertIsSelected()
        rule.onNode(hasContentDescription("Select")).assertIsNotSelected()
        labels.forEach {
            val bounds = rule.onNode(hasContentDescription(it)).getUnclippedBoundsInRoot()
            assertTrue(bounds.width >= 48.dp - 0.5.dp && bounds.height >= 48.dp - 0.5.dp, "$it is ${bounds.width} x ${bounds.height}")
        }
    }

    @Test
    fun keepTool_should_BeAbsentWhileSelectIsActive() {
        render(AnnotationTool.SELECT)
        rule.onNode(hasText("Keep tool")).assertDoesNotExist()
    }

    @Test
    fun keepTool_should_BePresentOnceAToolIsArmed() {
        render(AnnotationTool.AREA)
        rule.onNode(hasText("Keep tool")).assertExists()
    }

    @Test
    fun keepTool_should_Be48dpSwitch_ExposeLockedStateAndToggleOnTap() {
        var toggles = 0
        render(AnnotationTool.DISTANCE, onToggle = { toggles++ })
        val toggle = rule.onNode(hasText("Keep tool"))
        val node = toggle.fetchSemanticsNode()
        assertEquals(Role.Switch, node.config.getOrNull(SemanticsProperties.Role))
        assertEquals(KEEP_TOOL_UNLOCKED_DESCRIPTION, node.config.getOrNull(SemanticsProperties.StateDescription))
        val bounds = toggle.getUnclippedBoundsInRoot()
        assertTrue(bounds.width >= 47.5.dp && bounds.height >= 47.5.dp, "toggle ${bounds.width} x ${bounds.height}")
        toggle.performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun lockedKeepTool_should_ExposeLockedStateDescription_AndShowTheDecorativeBadgeOnTheActiveChip() {
        render(AnnotationTool.DISTANCE, locked = true)
        assertEquals(
            KEEP_TOOL_LOCKED_DESCRIPTION,
            rule.onNode(hasText("Keep tool")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
        rule.onNodeWithTag(TOOL_LOCK_BADGE_TAG, useUnmergedTree = true).assertExists()
        val badge = rule.onNodeWithTag(TOOL_LOCK_BADGE_TAG, useUnmergedTree = true).fetchSemanticsNode().config
        assertEquals(null, badge.getOrNull(SemanticsProperties.ContentDescription), "the badge is decorative")
    }

    @Test
    fun unlockedKeepTool_should_ShowNoBadge() {
        render(AnnotationTool.DISTANCE, locked = false)
        rule.onNodeWithTag(TOOL_LOCK_BADGE_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun longPressAndDoubleTapOnTheChip_should_ToggleKeepTool_AndASingleTapShouldOnlySelect() {
        var toggles = 0
        val selected = mutableListOf<AnnotationTool>()
        render(AnnotationTool.DISTANCE, onSelect = { selected += it }, onToggle = { toggles++ })
        rule.onNode(hasContentDescription("Distance")).performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(1, toggles, "long press")
        rule.onNode(hasContentDescription("Distance")).performTouchInput { doubleClick() }
        rule.waitForIdle()
        assertEquals(2, toggles, "double tap")
        assertEquals(emptyList(), selected, "neither gesture selects")
        rule.onNode(hasContentDescription("Distance")).performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(listOf(AnnotationTool.DISTANCE), selected, "a single tap still reaches onToolSelect (the screen disarms the active chip)")
        assertEquals(2, toggles)
    }

    @Test
    fun uncalibrated_should_ExposeDisabledWithCalibrateFirst_AndATapShouldExplainInsteadOfSelecting() {
        val selected = mutableListOf<AnnotationTool>()
        val explained = mutableListOf<AnnotationTool>()
        render(AnnotationTool.SELECT, calibrated = false, onSelect = { selected += it }, onDisabledTap = { explained += it })
        val reference = rule.onNode(hasContentDescription("Reference"))
        reference.assertIsNotEnabled()
        assertEquals(CALIBRATE_FIRST_DESCRIPTION, reference.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        rule.onNode(hasContentDescription("Select")).assertIsEnabled()
        reference.performClick()
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf(AnnotationTool.GRID_REF), explained)
        assertEquals(emptyList(), selected)
        assertEquals("Calibrate first to use Reference", calibrateFirstHint(toolLabel(AnnotationTool.GRID_REF)))
        assertTrue(rule.onAllNodesWithContentDescription("Reference").fetchSemanticsNodes().single().config.contains(SemanticsActions.OnClick))
    }
}
