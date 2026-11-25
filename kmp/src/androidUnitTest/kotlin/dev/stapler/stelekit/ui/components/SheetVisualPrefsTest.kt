// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.ColumnAlign
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.ui.components.sheet.LocalTableVisualPrefs
import dev.stapler.stelekit.ui.components.sheet.OutlineWidths
import dev.stapler.stelekit.ui.components.sheet.cellBackgrounds
import dev.stapler.stelekit.ui.components.sheet.sheetColorsFor
import dev.stapler.stelekit.ui.components.sheet.TableNotices
import dev.stapler.stelekit.ui.components.sheet.TableNoticeHost
import dev.stapler.stelekit.ui.components.sheet.TableVisualPrefs
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 6.1d: reflow at 320dp, text spacing, forced colors, reduced motion and right-to-left, on the real sheet.
 * Robolectric only: forced colors on a real browser, Windows High Contrast and device animator settings are owner-gated.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w320dp-h700dp-xxhdpi")
class SheetVisualPrefsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private var fixture: SheetFixture? = null

    @After
    fun tearDown() {
        fixture?.close()
    }

    private fun open(content: String, prefs: TableVisualPrefs? = null, rtl: Boolean = false, extra: @Composable (@Composable () -> Unit) -> Unit = { it() }): SheetFixture =
        SheetFixture(rule, content).also {
            fixture = it
            it.show(realCommands = true, wrap = { inner ->
                val layered = @Composable {
                    if (prefs == null) inner() else CompositionLocalProvider(LocalTableVisualPrefs provides prefs) { inner() }
                }
                val directed = @Composable {
                    if (rtl) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { layered() } else layered()
                }
                extra(directed)
            })
        }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun select(f: SheetFixture, a: CellAddress) {
        rule.runOnUiThread { f.s.selectRange(GridSelection(a, a)) }
        rule.waitForIdle()
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    // ---- reflow and text spacing ----------------------------------------------------------------------------------

    @Test
    fun reflow_should_FoldTheTopBarIntoTheOverflowMenu_AndKeepEveryControlInsideThe320dpWidth() {
        open(gridContent(rows = 3, cols = 4))
        val width = rule.onNodeWithTag("sheetTopBar").fetchSemanticsNode().boundsInRoot.right
        val dp = rule.density.density
        assertTrue(width <= 320 * dp + 1f, "top bar spans ${width / dp}dp")
        for (tag in listOf("sheetClose", "sheetTitle", "sheetOverflow")) {
            val b = bounds(tag)
            assertTrue(b.left >= 0f && b.right <= 320 * dp + 1f, "$tag at ${b.left / dp}..${b.right / dp}dp")
        }
        assertFalse(rule.exists("sheetUndo") || rule.exists("sheetRedo"), "Undo and Redo left the bar")
        rule.onNodeWithTag("sheetOverflow").performClick()
        rule.waitForIdle()
        val undo = bounds("menuUndo")
        val redo = bounds("menuRedo")
        val markdown = bounds("menuEditAsMarkdown")
        assertTrue(undo.top < redo.top && redo.top < markdown.top, "Undo and Redo are the first two overflow items")
    }

    @Test
    fun textSpacing_should_GrowTheRowAndNeverClipACell() {
        val note = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen"
        val content = "| Name | Note |\n|---|---|\n| a | $note |"
        val spaced = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.12.em)
        open(content, extra = { inner -> MaterialTheme { ProvideTextStyle(spaced) { inner() } } })
        val row = bounds("sheetRow-2")
        val cell = rule.onNodeWithTag("cell-B2", useUnmergedTree = true).fetchSemanticsNode()
        val text = rule.onNodeWithTag("cell-B2", useUnmergedTree = true).onChildren().fetchSemanticsNodes().first().boundsInRoot
        val lineHeightPx = 21 * rule.density.density
        assertTrue(text.height >= 2 * lineHeightPx, "the text wraps onto several 1.5x lines: ${text.height / rule.density.density}dp")
        assertTrue(row.height >= text.height, "the row grew to hold it: ${row.height} >= ${text.height}")
        assertTrue(text.bottom <= cell.boundsInRoot.bottom + 1f, "the text stays inside its cell")
        assertTrue(rule.onNodeWithTag("sheetGrid").fetchSemanticsNode().boundsInRoot.right <= 320 * rule.density.density + 1f, "no page-level horizontal scroll")
    }

    // ---- forced colors --------------------------------------------------------------------------------------------

    private val formulas = "| A | B |\n|---|---|\n| 1 | =A2*2 |\n| =1/0 | 5 |"

    @Test
    fun normalSelection_should_BeAtLeast2dpWide_AndReach3To1OnEveryCellBackground() {
        for ((name, scheme) in listOf("light" to lightColorScheme(), "dark" to darkColorScheme())) {
            val colors = sheetColorsFor(scheme, TableVisualPrefs())
            val (width, edge) = renderedOutline(colors.outline, OutlineWidths.of(TableVisualPrefs()), scheme.surface)
            assertTrue(width >= 6, "$name outline is $width px at 3x, so ${width / 3f}dp")
            assertTrue(width < 9, "$name outline is the normal width")
            cellBackgrounds(scheme).forEach { assertTrue(contrast(edge, it) >= 3.0, "$name $edge on $it") }
        }
    }

    @Test
    fun highContrast_should_DrawSelectionByWidthInInk_NoTints_AndMarkErrorsWithAGlyph() {
        val prefs = TableVisualPrefs(highContrast = true)
        for ((name, scheme) in listOf("light" to lightColorScheme(), "dark" to darkColorScheme())) {
            val colors = sheetColorsFor(scheme, prefs)
            val (width, edge) = renderedOutline(colors.outline, OutlineWidths.of(prefs), scheme.surface)
            assertTrue(width >= 12, "$name outline is $width px at 3x, so ${width / 3f}dp")
            assertTrue(contrast(edge, scheme.surface) >= 7.0, "$name ink $edge on ${scheme.surface}")
            assertEquals(Color.Transparent, colors.formulaTint, "$name: no tint carries the formula state")
            assertEquals(Color.Transparent, colors.zebra, "$name: no zebra fill")
        }
        open(formulas, prefs = prefs)
        assertTrue(rule.onAllNodes(hasText("fx"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "fx marker stays")
        assertTrue(rule.onAllNodes(hasText("!"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "error glyph")
    }

    @Test
    fun normalErrorCell_should_NotCarryTheHighContrastGlyph() {
        open(formulas)
        assertTrue(rule.onAllNodes(hasText("!"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    // ---- reduced motion -------------------------------------------------------------------------------------------

    @Test
    fun reducedMotion_should_JumpToTheCellInstead_OfAnimatingTheScroll() {
        val f = open(gridContent(rows = 80, cols = 3), prefs = TableVisualPrefs(reducedMotion = true))
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { f.s.selectRange(GridSelection(CellAddress(70, 1), CellAddress(70, 1))) }
        var frames = 0
        while (!rule.exists("sheetRow-70") && frames < 60) {
            rule.mainClock.advanceTimeByFrame()
            frames++
        }
        assertTrue(rule.exists("sheetRow-70"), "row 70 never appeared in 60 frames; first visible ${f.s.firstVisibleIndex}")
        assertTrue(frames <= 8, "an animated scroll takes far longer than a jump: row 70 needed $frames frames")
    }

    @Test
    fun noticeHost_should_ShowANoticeFullyOpaqueInTheFirstFrame_UnderReducedMotion() {
        val notices = TableNotices()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTableVisualPrefs provides TableVisualPrefs(reducedMotion = true)) {
                    TableNoticeHost(notices = notices, recovered = null, recoveredActions = noRecoveredActions)
                }
            }
        }
        rule.runOnUiThread {
            notices.info("Updated from disk")
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        val first = bounds("tableNotice")
        rule.mainClock.advanceTimeBy(1_500)
        assertEquals(bounds("tableNotice"), first, "no slide: the first frame already sits where the settled notice does")
    }

    private val noRecoveredActions = dev.stapler.stelekit.ui.components.sheet.RecoveredDraftActions(
        onCopy = {},
        onApply = {},
        canApply = { true },
        onDiscard = {},
    )

    // ---- right to left --------------------------------------------------------------------------------------------

    @Test
    fun ltrBaseline_should_DrawColumnOneLeftmost_WithTheGutterOnTheLeft() {
        open(gridContent(rows = 3, cols = 3))
        val a = bounds("letter-A")
        val b = bounds("letter-B")
        val gutter = bounds("gutter-2")
        val cellA = bounds("cell-A2")
        assertTrue(a.left < b.left, "baseline LTR: A is left of B")
        assertTrue(gutter.right <= cellA.left + 1f, "baseline LTR: gutter is left of A")
    }

    @Test
    fun rtlGrid_should_MirrorTheColumnsAndKeepTheGutterAtTheRightEdge_AfterAScroll() {
        open(gridContent(rows = 3, cols = 6), rtl = true)
        val a = bounds("letter-A")
        val b = bounds("letter-B")
        val grid = bounds("sheetGrid")
        assertTrue(a.left > b.left, "RTL: A is right of B")
        assertTrue(bounds("gutter-2").left >= bounds("cell-A2").right - 1f, "RTL: the gutter is right of column A")
        rule.onNodeWithTag("sheetGrid").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        if (bounds("letter-A").left == a.left) {
            rule.onNodeWithTag("sheetGrid").performTouchInput { swipeRight() }
            rule.waitForIdle()
        }
        assertTrue(bounds("letter-A").left != a.left, "the grid scrolled")
        assertTrue(kotlin.math.abs(bounds("gutter-2").right - grid.right) <= 2f, "the gutter stays pinned at the right edge: ${bounds("gutter-2").right} vs ${grid.right}")
    }

    @Test
    fun rtlKeys_should_MirrorArrowsOnly_AndHomeIsTheRightmostCell_WithLogicalAddresses() {
        val f = open(gridContent(rows = 3, cols = 4), rtl = true)
        select(f, CellAddress(2, 2))
        fun press(key: Key) {
            rule.onNodeWithTag("sheetGridFocus").performKeyInput { pressKey(key) }
            rule.waitForIdle()
        }
        press(Key.DirectionRight)
        assertEquals(CellAddress(2, 1), f.s.selection.anchor, "Right moves toward the lower-numbered column")
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        assertEquals(CellAddress(2, 3), f.s.selection.anchor)
        press(Key.MoveHome)
        assertEquals(CellAddress(2, 1), f.s.selection.anchor, "Home is the rightmost cell, column 1")
        press(Key.MoveEnd)
        assertEquals(CellAddress(2, 4), f.s.selection.anchor)
        press(Key.Tab)
        assertEquals(CellAddress(3, 1), f.s.selection.anchor, "Tab walks the file order: past the last column it wraps down")
        press(Key.Tab)
        assertEquals(CellAddress(3, 2), f.s.selection.anchor, "and then moves toward the higher column, visually leftward")
    }

    @Test
    fun rtlCopy_should_KeepLogicalColumnOrder_AndAlignLeftStillWritesTheLeftMarker() {
        val f = open(gridContent(rows = 3, cols = 3), rtl = true)
        rule.runOnUiThread { f.s.selectRange(GridSelection(CellAddress(2, 1), CellAddress(2, 3))) }
        rule.waitForIdle()
        rule.runOnUiThread { f.s.perform(dev.stapler.stelekit.table.GridCommand.Copy) }
        rule.waitForIdle()
        assertEquals("2-1\t2-2\t2-3", f.clipboard.text, "TSV is in logical (file) order")
        val align = f.s.sink.menuFor(dev.stapler.stelekit.ui.components.sheet.SheetMenuTarget.Column(2))!!.items.single { it.id == "alignLeft" }
        assertEquals(TableMessages.ALIGN_LEFT, align.label)
        rule.runOnUiThread { align.action() }
        rule.waitForIdle()
        assertEquals(ColumnAlign.LEFT, f.s.doc!!.delimiter.aligns[1])
        assertTrue("| :-- |" in f.h.managerContent() && "--:" !in f.h.managerContent(), "the physical left marker, whatever the direction: ${f.h.managerContent()}")
    }
}
