// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 3.6.7, Task 3.6.7b (WCAG 1.4.13, UX-90): the text of a hover tooltip also reaches touch and keyboard users as a
 * popup that stays until dismissed. Robolectric; Gradle-only like the other Compose host tests (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-mdpi")
class TouchHelpTest {
    @get:Rule
    val rule = createComposeRule()

    private val phone = WindowDp(360, 640)

    private fun showShell(state: SketchEditorState = SketchEditorState()): SketchEditorState {
        rule.setContent { SketchScreenV2(state, SketchShellConfig(ToolStripModel(true), PointerClass.TouchOrPen), window = phone) }
        rule.waitForIdle()
        return state
    }

    @Test
    fun longPressOnAStripButton_should_ShowItsNameAsAPopupThatStaysUntilDismissed() {
        val state = showShell()
        rule.onNodeWithTag(StripTags.groupMain(StripGroup.Shapes)).performTouchInput { longClick() }
        rule.onNodeWithTag(HelpTags.POPUP).assertIsDisplayed()
        assertEquals(StripGroup.Shapes.tools.first().name.lowercase(), state.help.shown?.name?.substringBefore(' ')?.lowercase())
        rule.mainClock.advanceTimeBy(HELP_WAIT_MS)
        rule.onNodeWithTag(HelpTags.POPUP).assertIsDisplayed()
        rule.onNodeWithTag(HelpTags.CLOSE).performClick()
        rule.onNodeWithTag(HelpTags.POPUP).assertDoesNotExist()
        assertNull(state.help.shown)
    }

    @Test
    fun aDisabledControl_should_ExposeItsReasonTheSameWayOnTouch() {
        showShell()
        rule.onNodeWithTag(StripTags.DELETE).performTouchInput { longClick() }
        rule.onNodeWithTag(HelpTags.POPUP).assertIsDisplayed()
        rule.onNodeWithTag(HelpTags.POPUP).assertContentDescriptionEquals("Delete, unavailable: nothing selected")
    }

    @Test
    fun helpable_should_ExposeNameAndTextInSemantics_AndOpenOnLongPress() {
        val help = HelpPopupState()
        val entry = HelpEntry("glyph", "Parallel", "Parallel conflicts with Horizontal on Line 3")
        rule.setContent {
            Box(Modifier.size(HOST)) {
                Box(Modifier.size(GLYPH).helpable(help, entry).testTag("glyph"))
                HelpPopupHost(help)
            }
        }
        rule.onNodeWithTag("glyph").assertContentDescriptionEquals("Parallel. Parallel conflicts with Horizontal on Line 3")
        rule.onNodeWithTag("glyph").performTouchInput { longClick() }
        assertVisible("Parallel conflicts with Horizontal on Line 3")
    }

    @Test
    fun theInfoAffordance_should_OpenTheSameText_AndAnotherEntryReplacesIt() {
        val help = HelpPopupState()
        val first = HelpEntry("a", "Opacity", "How see-through the element is")
        val second = HelpEntry("b", "Roughness", "How hand-drawn the lines look")
        rule.setContent {
            Column(Modifier.size(HOST)) {
                Row {
                    HelpInfoButton(help, first)
                    HelpInfoButton(help, second)
                }
                HelpPopupHost(help)
            }
        }
        rule.onNodeWithTag(HelpTags.infoButton("a")).performClick()
        assertVisible("How see-through the element is")
        rule.onNodeWithTag(HelpTags.infoButton("b")).performClick()
        assertVisible("How hand-drawn the lines look")
        rule.onNodeWithText("How see-through the element is").assertDoesNotExist()
    }

    @Test
    fun focusHelp_should_FollowKeyboardFocusOnly_AndEscDismisses() {
        val help = HelpPopupState()
        val entry = HelpEntry("a", "Opacity", "How see-through the element is")
        help.showForFocus(entry)
        help.blurred(entry)
        assertNull(help.shown, "a popup that only followed focus leaves with it")
        help.show(entry)
        help.blurred(entry)
        assertEquals(entry, help.shown, "a popup the user opened stays when focus moves on")
        help.dismiss()
        assertNull(help.shown)
    }

    private fun assertVisible(text: String) {
        val bounds = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        val popup = rule.onNodeWithTag(HelpTags.POPUP).fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width > 0f && bounds.height > 0f, "text $bounds inside popup $popup")
    }

    private companion object {
        const val HELP_WAIT_MS = 30_000L
        val HOST = 200.dp
        val GLYPH = 44.dp
    }
}
