// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import dev.stapler.stelekit.ui.components.settings.TABLE_INLINE_EDIT_LABEL
import dev.stapler.stelekit.ui.components.settings.TableSettingsRows
import dev.stapler.stelekit.ui.components.sheet.TABLE_HINTS_SEEN_KEY
import dev.stapler.stelekit.ui.components.sheet.TableHint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 4.5d: one-time tip H1, the first-run note, "Show tips again"; non-modal, no focus change. Gradle-only (Compose host). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class TableHintsTest {
    @get:Rule
    val rule = createComposeRule()
    private val host = TableTestHost(rule)
    private val settings = MapSettings()

    private fun tapTouch(text: String) {
        rule.onNodeWithText(text).performTouchInput { click() }
        rule.waitForIdle()
    }

    private fun chips() = rule.onAllNodes(hasTestTag("tableHint"))

    @Test
    fun h1_should_ShowOnTheFirstTouchEdit_WithoutMovingFocus_AndNotComeBackAfterDismissal() {
        host.show(preferences = TablePreferences(settings))
        tapTouch("3")
        rule.onNodeWithText(TableMessages.TIP_SELECT_ROW_COPY).assertExists()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        assertTrue(settings.getString(TABLE_HINTS_SEEN_KEY, "").contains("H1"))
        rule.onNodeWithTag("tableHint").assertExists()
        rule.onNode(hasText(TableMessages.DISMISS) and hasAnyAncestor(hasTestTag("tableHint"))).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        chips().assertCountEquals(0)
    }

    @Test
    fun h1_should_NotReturnAfterARestart_ButReturnsAfterShowTipsAgain() {
        val first = TablePreferences(settings)
        host.show(preferences = first)
        tapTouch("3")
        chips().assertCountEquals(1)
        val restarted = TablePreferences(settings)
        assertTrue(restarted.hints.isSeen(TableHint.H1))
        host.restart(restarted)
        tapTouch("3")
        chips().assertCountEquals(0)
        restarted.hints.showAgain()
        assertFalse(restarted.hints.isSeen(TableHint.H1))
        assertEquals("", settings.getString(TABLE_HINTS_SEEN_KEY, "x"))
        host.restart(restarted)
        tapTouch("3")
        chips().assertCountEquals(1)
    }

    @Test
    fun h1_should_BeDismissedByTheFirstStripAction() {
        host.show(preferences = TablePreferences(settings))
        tapTouch("3")
        chips().assertCountEquals(1)
        rule.onNodeWithTag("strip:CancelEdit").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        chips().assertCountEquals(0)
    }

    @Test
    fun h1_should_YieldToANotice_AndNeverShowForAHardwareEdit() {
        host.show(preferences = TablePreferences(settings))
        tapTouch("3")
        chips().assertCountEquals(1)
        rule.onNodeWithTag("strip:Undo").performSemanticsAction(SemanticsActions.OnClick) // "Nothing more to undo in this cell"
        rule.waitForIdle()
        rule.onNodeWithText(TableMessages.NOTHING_TO_UNDO_CELL).assertExists()
        chips().assertCountEquals(0)

        host.restart(TablePreferences(MapSettings()))
        rule.onNodeWithText("3").performMouseInput { click() }
        rule.waitForIdle()
        chips().assertCountEquals(0)
    }

    @Test
    @Config(sdk = [29], qualifiers = "w800dp-h360dp")
    fun h1_should_BeSuppressedInLandscape() {
        host.show(preferences = TablePreferences(settings))
        tapTouch("3")
        chips().assertCountEquals(0)
    }

    @Test
    fun firstRunNote_should_ShowUnderTheToggleOnlyWhileTheFlagIsOn_AndShowTipsAgainClearsTheKey() {
        val prefs = TablePreferences(settings)
        settings.putString(TABLE_HINTS_SEEN_KEY, "H1")
        rule.setContent { MaterialTheme { Column { TableSettingsRows(prefs) } } }
        rule.onAllNodes(hasText(TableMessages.FIRST_RUN_NOTE)).assertCountEquals(0)
        rule.onNodeWithText(TABLE_INLINE_EDIT_LABEL, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText(TableMessages.FIRST_RUN_NOTE)).assertCountEquals(1)
        rule.onNodeWithText(TableMessages.SHOW_TIPS_AGAIN).performClick()
        rule.waitForIdle()
        assertEquals("", settings.getString(TABLE_HINTS_SEEN_KEY, "x"))
    }
}
