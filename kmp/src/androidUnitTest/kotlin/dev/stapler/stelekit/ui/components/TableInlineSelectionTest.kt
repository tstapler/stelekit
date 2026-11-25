// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.center
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.ui.components.sheet.OUTLINE_MIN_CONTRAST
import dev.stapler.stelekit.ui.components.sheet.OutlineEdges
import dev.stapler.stelekit.ui.components.sheet.contrastRatio
import dev.stapler.stelekit.ui.components.sheet.outlineColor
import dev.stapler.stelekit.ui.components.sheet.outlineEdges
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 4.4: range selection, copy, outline. Gradle-only (Compose host). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class TableInlineSelectionTest {
    @get:Rule
    val rule = createComposeRule()
    private val host = TableTestHost(rule)

    /** The strip lives in a popup, which adds a second root once it shows. */
    private fun mainRoot() = rule.onAllNodes(isRoot()).onFirst()

    private fun centerOf(text: String): Offset {
        val b = rule.onNodeWithText(text).getBoundsInRoot()
        return with(rule.density) { Offset(((b.left + b.right) / 2).toPx(), ((b.top + b.bottom) / 2).toPx()) }
    }

    private fun mouseDrag(from: String, to: String) {
        val start = centerOf(from)
        val end = centerOf(to)
        mainRoot().performMouseInput {
            moveTo(start)
            press()
            moveTo((start + end) / 2f)
            moveTo(end)
            release()
        }
        rule.waitForIdle()
    }

    private fun ctrl(key: Key) {
        mainRoot().performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(key)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
    }

    private fun activate(text: String) {
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    @Test
    fun mouseDrag_should_SelectRangeAndCtrlCCopyTsv_AndTheBlockCopyDoesNotFire() {
        host.show()
        mouseDrag("Apple", "5")
        rule.onAllNodes(isSelected()).assertCountEquals(4)
        host.pageCopyKeys = 0
        ctrl(Key.C)
        assertEquals(listOf("Apple\t3\r\nPear\t5"), host.clipboard.copies)
        // Ctrl down and C down reach the table first; the page's own Ctrl+C handler sees only what it leaves.
        assertTrue(host.pageCopyKeys <= 1, "only the bare Ctrl modifier press may bubble, got ${host.pageCopyKeys}")
        assertTrue(host.commits.isEmpty())
        assertEquals(0, host.started)
    }

    @Test
    fun touchDrag_should_NotSelectARange() {
        host.show()
        rule.onNodeWithText("Apple").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onAllNodes(isSelected()).assertCountEquals(0)
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag("tableStrip")).assertCountEquals(0)
    }

    @Test
    fun touchTapThenSelectRowThenCopy_should_CopyOneTsvLine() {
        host.show()
        rule.onNodeWithText("Apple").performTouchInput { click() }
        rule.waitForIdle()
        activate("Select row")
        rule.onAllNodes(isSelected()).assertCountEquals(3)
        activate("Copy")
        assertEquals(listOf("Apple\t3\tred"), host.clipboard.copies)
        assertTrue(host.commits.isEmpty(), "an unchanged draft commits nothing")
    }

    @Test
    fun selectedCells_should_CarryAnOutlineEdgeMap_AndOutlineContrastBeThreeToOne() {
        val sel = GridSelection(CellAddress(2, 1), CellAddress(3, 2))
        assertEquals(OutlineEdges(top = true, bottom = false, left = true, right = false), outlineEdges(sel, CellAddress(2, 1)))
        assertEquals(OutlineEdges(top = false, bottom = true, left = false, right = true), outlineEdges(sel, CellAddress(3, 2)))
        assertEquals(OutlineEdges.None, outlineEdges(sel, CellAddress(2, 3)))
        assertEquals(OutlineEdges.None, outlineEdges(null, CellAddress(2, 1)))
        // A weak accent on white is replaced by black; a strong one is kept.
        val white = Color.White
        val weak = Color(0xFFCCCCCC)
        assertTrue(contrastRatio(outlineColor(weak, white), white) >= OUTLINE_MIN_CONTRAST)
        val strong = Color(0xFF3F51B5)
        assertEquals(strong, outlineColor(strong, white))
    }

    @Test
    fun shiftClick_should_ExtendTheRangeFromTheAnchor() {
        host.show()
        mouseDrag("Apple", "3")
        val target = centerOf("5")
        mainRoot().performKeyInput { keyDown(Key.ShiftLeft) }
        mainRoot().performMouseInput {
            click(target)
        }
        mainRoot().performKeyInput { keyUp(Key.ShiftLeft) }
        rule.waitForIdle()
        rule.onAllNodes(isSelected()).assertCountEquals(4)
    }

    @Test
    fun editingASecondTable_should_ResetTheFirstAndLeaveOneStrip() {
        val prefs = TableDisplayPrefs(inlineEditEnabled = true)
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalTablePreferences provides TablePreferences(null)) {
                androidx.compose.material3.MaterialTheme {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.width(360.dp)) {
                        TableBlock(content = "| A | B |\n|---|---|\n| apple | 1 |", onStartEditing = {}, prefs = prefs, blockUuid = dev.stapler.stelekit.model.BlockUuid("t1"), onCommit = {})
                        TableBlock(content = "| C | D |\n|---|---|\n| cherry | 2 |", onStartEditing = {}, prefs = prefs, blockUuid = dev.stapler.stelekit.model.BlockUuid("t2"), onCommit = {})
                    }
                }
            }
        }
        rule.onNodeWithText("apple").performTouchInput { click() }
        rule.waitForIdle()
        rule.onAllNodesWithTag("strip:Done").assertCountEquals(1)
        rule.onNodeWithText("cherry").performTouchInput { click() }
        rule.waitForIdle()
        rule.onAllNodesWithTag("strip:Done").assertCountEquals(1)
        rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).assertCountEquals(1)
    }
}
