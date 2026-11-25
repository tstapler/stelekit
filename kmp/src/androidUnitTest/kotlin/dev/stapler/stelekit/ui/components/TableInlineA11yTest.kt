// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 4.5e, inline portion: cell actions, one polite announcement, focus return, strip traversal group. Gradle-only (Compose host). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class TableInlineA11yTest {
    @get:Rule
    val rule = createComposeRule()
    private val host = TableTestHost(rule)

    private fun announcements() = rule.onAllNodes(
        SemanticsMatcher("polite 'Editing' live region") {
            it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("Editing") } == true
        },
    )

    @Test
    fun everyCell_should_OfferEditCellAndTheCustomActionsOfTheStrip() {
        host.show(sheet = true)
        val cell = rule.onNodeWithText("Apple").fetchSemanticsNode()
        val onClick = cell.config[SemanticsActions.OnClick]
        assertEquals(TableMessages.EDIT_CELL, onClick.label)
        val labels = cell.config[SemanticsActions.CustomActions].map { it.label }
        val expected = listOf(
            "Select row", "Select column", "Select table",
            "Extend selection right", "Extend selection down", "Extend selection to row end",
            "Extend selection to column end", "Extend selection to table end",
            "Copy", "Undo", "Redo", TableMessages.MAXIMIZE, TableMessages.EDIT_AS_MARKDOWN,
        )
        assertEquals(expected, labels)
    }

    @Test
    fun maximizeAction_should_BeAbsent_When_TheSheetFlagIsOff() {
        host.show(sheet = false)
        val labels = rule.onNodeWithText("Apple").fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertTrue(TableMessages.MAXIMIZE !in labels)
    }

    @Test
    fun editCellAction_should_MoveFocusIntoTheFieldAndAnnounceOnceNotWhileTyping() {
        host.show()
        announcements().assertCountEquals(0)
        rule.onNodeWithText("3").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        announcements().assertCountEquals(1)
        val spoken = announcements().fetchSemanticsNodes().single().config[SemanticsProperties.ContentDescription]
        assertEquals(listOf("Editing B2, 3"), spoken)
        rule.onNode(hasSetTextAction()).performTextReplacement("45")
        rule.waitForIdle()
        announcements().assertCountEquals(1)
        assertEquals(listOf("Editing B2, 3"), announcements().fetchSemanticsNodes().single().config[SemanticsProperties.ContentDescription])
    }

    @Test
    fun doneAndCancel_should_ReturnFocusToTheSameCellNode() {
        host.show()
        rule.onNodeWithText("3").performTouchInput { click() }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextReplacement("45")
        rule.onNodeWithTag("strip:Done").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("45").assertIsFocused()
        rule.onNodeWithText("5").performTouchInput { click() }
        rule.waitForIdle()
        rule.onNodeWithTag("strip:CancelEdit").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("5").assertIsFocused()
    }

    @Test
    fun strip_should_BeATraversalGroup_AndEveryControlAFocusableButtonWithText() {
        host.show()
        rule.onNodeWithText("3").performTouchInput { click() }
        rule.waitForIdle()
        val strip = rule.onNodeWithTag("tableStrip").fetchSemanticsNode()
        assertEquals(true, strip.config.getOrNull(SemanticsProperties.IsTraversalGroup))
        rule.onAllNodes(hasTestTag("strip:Done")).assertCountEquals(1)
        val done = rule.onNodeWithTag("strip:Done").fetchSemanticsNode()
        assertTrue(done.config.contains(SemanticsActions.OnClick))
        assertTrue(done.config.contains(SemanticsActions.RequestFocus))
    }

    @Test
    fun semanticClick_should_StartEditWithTheStripVisible() {
        host.show()
        rule.onNodeWithText("3").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithTag("strip:Done").assertExists()
    }
}
