// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.ui.components.sheet.SheetMenuTarget
import dev.stapler.stelekit.ui.components.sheet.sheetRowCompositionProbe
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The frozen header row (row 1 of the table, on by default) takes the same gestures as the body rows, a horizontal
 * scroll does not recompose the rows, the live region changes on a repeated announcement and touch targets are 48dp.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class SheetGridHeaderRowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private var fixture: SheetFixture? = null

    @After
    fun tearDown() {
        sheetRowCompositionProbe = null
        fixture?.close()
    }

    private fun open(content: String = gridContent(rows = 5, cols = 3)): SheetFixture =
        SheetFixture(rule, content).also {
            fixture = it
            it.show(realCommands = true)
        }

    private fun local(tag: String) =
        rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center -
            rule.onNodeWithTag("sheetGridFocus").fetchSemanticsNode().boundsInRoot.topLeft

    @Test
    fun clickingAHeaderCell_should_SelectIt() {
        val f = open()
        rule.onNodeWithTag("sheetGridFocus").performMouseInput { click(local("cell-B1")) }
        rule.waitForIdle()
        assertEquals(CellAddress(1, 2), f.s.selection.anchor)
    }

    @Test
    fun rightClickingAHeaderCell_should_OpenItsCellMenu() {
        val f = open()
        rule.onNodeWithTag("sheetGridFocus").performMouseInput { rightClick(local("cell-B1")) }
        rule.waitForIdle()
        assertEquals(SheetMenuTarget.Cell(CellAddress(1, 2)), f.s.menu)
    }

    @Test
    fun aRangeStartingInTheHeaderRow_should_ShowItsStartHandleOnScreen() {
        val f = open()
        rule.runOnUiThread { f.s.enterRangeMode(CellAddress(1, 1)) }
        rule.waitForIdle()
        rule.onNodeWithTag("handleStart").assertIsDisplayed()
        val handle = rule.onNodeWithTag("handleStart").fetchSemanticsNode().boundsInRoot
        val cell = rule.onNodeWithTag("cell-A1").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(handle.center.y - cell.top) < 2f && kotlin.math.abs(handle.center.x - cell.left) < 2f, "the handle sits on the cell's top-left corner: $handle vs $cell")
    }

    @Test
    fun scrollingHorizontally_should_NotRecomposeTheRows() {
        val f = open(gridContent(rows = 5, cols = 12))
        val composed = mutableListOf<Int>()
        sheetRowCompositionProbe = { composed += it }
        rule.waitForIdle()
        composed.clear()
        rule.onNodeWithTag("sheetGrid").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertTrue(f.s.horizontalScroll > 0, "the swipe scrolled the grid")
        assertEquals(emptyList(), composed, "the gutter and a frozen column follow the scroll in the layout phase, not by recomposing rows")
    }

    @Test
    fun aRepeatedAnnouncement_should_ChangeTheLiveRegionNode() {
        val f = open()
        fun described() = rule.onNodeWithTag("announcement").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        rule.runOnUiThread { f.s.announce("Sorted by B") }
        rule.waitForIdle()
        val first = described()
        rule.runOnUiThread { f.s.announce("Sorted by B") }
        rule.waitForIdle()
        assertNotEquals(first, described(), "the same sentence twice must still change the node, or a screen reader says it once")
        assertEquals(first.trim('​'), described().trim('​'))
    }

    @Test
    fun gutterAndLetterRow_should_MeetTheTouchTargetSize() {
        open()
        rule.onNodeWithTag("gutter-2").assertWidthIsAtLeast(48.dp)
        rule.onNodeWithTag("gutter-2").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("letter-A").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("letter-A").assertWidthIsAtLeast(48.dp)
    }
}
