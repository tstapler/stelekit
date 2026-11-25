// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.ui.components.sheet.TableManagerPort
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Story 5.6: column width by drag and by preset (no table write), alignment in the file, and the two freezes. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class SheetViewStateTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var fixture: SheetFixture? = null
    private var writes = 0

    private class CountingPort(private val inner: TableManagerPort, private val onWrite: () -> Unit) : TableManagerPort by inner {
        override fun write(uuid: BlockUuid, text: String, version: Long): Job {
            onWrite()
            return inner.write(uuid, text, version)
        }
    }

    @After
    fun tearDown() {
        fixture?.close()
    }

    private fun open(content: String): SheetFixture =
        SheetFixture(rule, content, wrapPort = { CountingPort(it) { writes++ } }).also {
            fixture = it
            it.show(realCommands = true, sizeClass = dev.stapler.stelekit.ui.WindowSizeClass.EXPANDED)
        }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private val three = "| A | B | C |\n|---|---|---|\n| 1 | 2 | 3 |\n| 4 | 5 | 6 |"

    @Test
    fun draggingTheHeaderEdge_should_WidenTheColumnBy60dpWithNoContentWrite() {
        val f = open(three)
        val before = f.s.view.widthOf(2)
        val density = rule.activity.resources.displayMetrics.density
        rule.onNodeWithTag("resize-B", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(60f * density, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(before + 60f, f.s.view.widthOf(2), 1f)
        assertEquals(0, writes, "a resize writes nothing to the block")
        assertEquals(three, f.h.managerContent())
    }

    @Test
    fun resizeHandle_should_HaveA48dpHitAreaWhereTheColumnAllowsIt() {
        open(three)
        rule.onNodeWithTag("resize-B", useUnmergedTree = true).assertWidthIsEqualTo(48.dp)
    }

    @Test
    fun widthPresets_should_SetNarrowMediumWideWithoutAWrite() {
        val f = open(three)
        listOf("widthNarrow" to 96f, "widthMedium" to 160f, "widthWide" to 240f).forEach { (id, dp) ->
            rule.onNodeWithTag("letter-B").performClick()
            rule.waitForIdle()
            tap("menu-$id")
            assertEquals(dp, f.s.view.widthOf(2), id)
        }
        assertEquals(0, writes)
        assertEquals(three, f.h.managerContent())
    }

    @Test
    fun autoFit_should_FitTheLongestCellWithinTheLimits() {
        val f = open("| A | B |\n|---|---|\n| 1 | a very long cell of text indeed |")
        rule.onNodeWithTag("letter-B").performClick()
        rule.waitForIdle()
        tap("menu-widthAutoFit")
        assertEquals(240f, f.s.view.widthOf(2), "capped at the widest preset")
        rule.onNodeWithTag("letter-A").performClick()
        rule.waitForIdle()
        tap("menu-widthAutoFit")
        assertEquals(96f, f.s.view.widthOf(1), "never below the narrow preset")
        assertEquals(0, writes)
    }

    @Test
    fun alignRight_should_ChangeOnlyTheDelimiterLine_AndUndoRestoresIt() {
        val f = open(three)
        rule.onNodeWithTag("letter-C").performClick()
        rule.waitForIdle()
        tap("menu-alignRight")
        val before = three.lines()
        val after = f.h.managerContent().lines()
        assertEquals(before.size, after.size)
        assertEquals(listOf(1), before.indices.filter { before[it] != after[it] }, "only the delimiter line changed")
        assertTrue(after[1].trimEnd().endsWith(":|") || after[1].contains("--:"), after[1])
        f.h.session!!.undo()
        rule.waitForIdle()
        assertEquals(three, f.h.managerContent())
    }

    private fun wide(): String = (1..12).joinToString(" | ", "| ", " |") { "H$it" } + "\n" + (1..12).joinToString("|", "|", "|") { "---" } +
        "\n" + (1..12).joinToString(" | ", "| ", " |") { "r$it" } + "\n" + (1..12).joinToString(" | ", "| ", " |") { "s$it" }

    @Test
    fun firstColumnFreeze_should_KeepColumnAVisibleWhileScrollingRight() {
        val f = open(wide())
        val gridLeft = rule.onNodeWithTag("sheetGrid").fetchSemanticsNode().boundsInRoot.left
        rule.onNodeWithTag("letter-A").performClick()
        rule.waitForIdle()
        tap("menu-freezeFirstColumn")
        assertTrue(f.s.view.freezeFirstColumn)
        rule.onNodeWithTag("sheetGrid").performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
        rule.waitForIdle()
        assertTrue(f.s.horizontalScroll > 0)
        val left = rule.onNodeWithTag("cell-A2").fetchSemanticsNode().boundsInRoot.left
        val gutter = with(rule.density) { 44.dp.toPx() }
        assertEquals(gridLeft + gutter, left, 2f, "column A stays right of the gutter")
    }

    @Test
    fun withoutTheFreeze_should_ColumnAScrollAway() {
        val f = open(wide())
        rule.onNodeWithTag("sheetGrid").performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
        rule.waitForIdle()
        assertTrue(f.s.horizontalScroll > 0)
        val gridLeft = rule.onNodeWithTag("sheetGrid").fetchSemanticsNode().boundsInRoot.left
        val gutter = with(rule.density) { 44.dp.toPx() }
        assertTrue(rule.onNodeWithTag("cell-A2").fetchSemanticsNode().boundsInRoot.left < gridLeft + gutter - 1f, "column A moved under the gutter")
    }

    @Test
    fun headerRow_should_BeFrozenByDefault_AndTheToggleUnfreezesIt() {
        val f = open(gridContent(rows = 60, cols = 3))
        assertTrue(f.s.view.freezeHeaderRow)
        rule.runOnUiThread { f.s.startEdit(CellAddress(50, 1)); f.s.cancelEdit() }
        rule.waitForIdle()
        assertTrue(rule.exists("sheetRow-1"), "the header row stays above the scrolled rows")
        rule.onNodeWithTag("letter-A").performClick()
        rule.waitForIdle()
        tap("menu-freezeHeaderRow")
        assertFalse(f.s.view.freezeHeaderRow)
        rule.runOnUiThread { f.s.startEdit(CellAddress(50, 1)); f.s.cancelEdit() }
        rule.waitForIdle()
        assertFalse(rule.exists("sheetRow-1"), "unfrozen, the header scrolls away with the rows")
    }

    @Test
    fun menuToggles_should_ShowTheirCheckedState() {
        val f = open(three)
        rule.onNodeWithTag("letter-A").performClick()
        rule.waitForIdle()
        assertTrue(rule.textOf("menu-freezeHeaderRow").orEmpty().startsWith("[x]"))
        assertTrue(rule.textOf("menu-freezeFirstColumn").orEmpty().startsWith("[ ]"))
        assertNotEquals(null, f.s.menu)
    }
}
