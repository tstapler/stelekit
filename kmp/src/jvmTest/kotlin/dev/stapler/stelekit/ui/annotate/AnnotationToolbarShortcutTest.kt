package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import dev.stapler.stelekit.model.MeasurementUnit
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for GAP-G02 (gap-backlog.md): Undo/Redo were the only two buttons in
 * [AnnotationToolbar] without a [androidx.compose.material3.TooltipBox]/shortcut hint, unlike
 * every other tool button, which wraps its [androidx.compose.material3.PlainTooltip] around
 * `"$label (${toolShortcut(tool)})"` (see `AnnotationToolbar.kt` lines 216-219, 253-260).
 *
 * Matches validation.md REQ-12's `annotationUndoRedo_should_showTooltipWithShortcutHint_When_toolButtonRendered`.
 */
@OptIn(ExperimentalTestApi::class)
class AnnotationToolbarShortcutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun renderToolbar(canUndo: Boolean = true, canRedo: Boolean = true) {
        composeTestRule.setContent {
            AnnotationToolbar(
                currentTool = AnnotationTool.SELECT,
                canUndo = canUndo,
                canRedo = canRedo,
                displayUnit = MeasurementUnit.METERS,
                isCalibrated = true,
                onToolSelect = {},
                onUndo = {},
                onRedo = {},
                onDeleteSelect = {},
                onCalibrate = {},
                onUnitSelect = {},
                hasSelection = false,
            )
        }
    }

    @Test
    fun annotationUndoRedo_should_showTooltipWithShortcutHint_When_toolButtonRendered() {
        renderToolbar()

        composeTestRule.onNodeWithContentDescription("Undo").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Undo (Ctrl+Z)").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Redo").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Redo (Ctrl+Shift+Z)").assertIsDisplayed()
    }
}
