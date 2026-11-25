// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.ui.LocalWindowSizeClass
import dev.stapler.stelekit.ui.WindowSizeClass
import dev.stapler.stelekit.ui.components.sheet.STATS_PERIOD_MS
import dev.stapler.stelekit.ui.components.sheet.SheetBodyContent
import dev.stapler.stelekit.ui.components.sheet.SheetState
import dev.stapler.stelekit.ui.components.sheet.TableSheetScreen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * Task 8.1a: phone-size renders of the inline table and the sheet, plus the vertical-budget bounds of UX-72 and UX-83.
 *
 * The IME is a grey placeholder taking 45% of the window: Robolectric reports no `WindowInsets.ime`, so the real
 * `imePadding` is not exercised here (device-only, Task 8.2a). The sheet is mounted without the host's `Dialog`
 * because pixels of a dialog window cannot be captured. PNGs land in `kmp/build/outputs/roborazzi/`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class TablePhoneRenderTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private var harness: SheetHarness? = null
    private var sheet: SheetState? = null

    @After
    fun tearDown() {
        harness?.close()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(STATS_PERIOD_MS + 100)
        rule.waitForIdle()
        rule.onAllNodes(isRoot()).onFirst().captureRoboImage("build/outputs/roborazzi/table-phone-$name.png")
    }

    private fun density() = rule.density.density

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun open(content: String, imeFraction: Float = 0f, fontScale: Float = 1f, landscape: Boolean = false, rtl: Boolean = false) {
        val h = SheetHarness(rule, content).also { harness = it }
        h.showBare { s ->
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale),
                LocalWindowSizeClass provides if (landscape) WindowSizeClass.MEDIUM else WindowSizeClass.COMPACT,
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                ImeSplit(imeFraction) {
                    TableSheetScreen(s, title = "Groceries") {
                        SheetBodyContent(s, onState = { sheet = it })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun edit(address: CellAddress) {
        rule.runOnUiThread { sheet!!.startEdit(address) }
        rule.waitForIdle()
    }

    // ---- sheet ---------------------------------------------------------------------------------------------

    @Test
    fun sheetAtRest_should_RenderTheChromeGridAndContextStrip() {
        open(BUDGET)
        rule.runOnUiThread { sheet!!.selectRange(GridSelection(CellAddress(2, 2), CellAddress(2, 2))) }
        shot("sheet-rest")
        rule.onNodeWithTag("sheetTopBar").assertExistsNode()
        rule.onNodeWithTag("sheetGrid").assertExistsNode()
    }

    @Test
    @Config(sdk = [29], qualifiers = "w320dp-h640dp-xxhdpi")
    fun narrow320dp_should_KeepTheChromeInsideTheWidth() {
        open(BUDGET)
        rule.runOnUiThread { sheet!!.selectRange(GridSelection(CellAddress(2, 2), CellAddress(2, 2))) }
        shot("sheet-320dp")
        for (tag in listOf("sheetTopBar", "formulaBar", "sheetContextStrip")) {
            val b = bounds(tag)
            assertTrue(b.left >= 0f && b.right <= 320 * density() + 1f, "$tag spans ${b.left / density()}..${b.right / density()}dp")
        }
    }

    @Test
    fun rtl_should_MirrorTheGridAndPutTheGutterOnTheRight() {
        open(BUDGET, rtl = true)
        rule.runOnUiThread { sheet!!.selectRange(GridSelection(CellAddress(2, 2), CellAddress(2, 2))) }
        shot("sheet-rtl")
        val gutter = bounds("gutter-2")
        assertTrue(gutter.right >= 360 * density() - 1f, "the gutter sits at the right edge: ${gutter.right / density()}dp")
    }

    @Test
    fun sheetWithRange_should_ShowTheStatusLine() {
        open(BUDGET)
        rule.runOnUiThread { sheet!!.selectRange(GridSelection(CellAddress(2, 2), CellAddress(5, 3))) }
        shot("sheet-range-status")
        rule.onNodeWithTag("statusLine").assertExistsNode()
    }

    @Test
    fun headerMenu_should_OpenAsABottomSheetOnAPhone() {
        open(BUDGET)
        rule.runOnUiThread { sheet!!.selectColumn(2) }
        shot("sheet-header-menu")
        rule.onNodeWithTag("sheetBottomMenu").assertExistsNode()
    }

    @Test
    fun longCell_should_NotBreakTheRowOrTheGrid() {
        open(LONG)
        rule.runOnUiThread { sheet!!.selectRange(GridSelection(CellAddress(2, 2), CellAddress(2, 2))) }
        shot("sheet-long-cell")
        val grid = bounds("sheetGrid")
        val row = bounds("sheetRow-2")
        assertTrue(row.height > 0f && row.top >= grid.top, "the long-cell row sits inside the grid")
    }

    @Test
    fun fontScale2_should_KeepTheChromeInsideTheWidthAndTheEditBarReachable() {
        open(BUDGET, fontScale = 2f)
        edit(CellAddress(2, 2))
        shot("sheet-font-scale-2")
        val width = 360 * density()
        for (tag in listOf("sheetTopBar", "formulaBar")) {
            val b = bounds(tag)
            assertTrue(b.left >= 0f && b.right <= width + 1f, "$tag spans ${b.left / density()}..${b.right / density()}dp")
        }
    }

    @Test
    fun editingACell_should_KeepEditBarActiveRowAndANeighbourAboveTheIme_AndTheChromeWithinHalf() {
        open(BUDGET, imeFraction = 0.45f)
        edit(CellAddress(2, 2))
        shot("sheet-edit-above-ime")
        assertVerticalBudget(row = 2, neighbour = 3)
    }

    @Test
    @Config(sdk = [29], qualifiers = "w780dp-h360dp-xxhdpi")
    fun landscapeEdit_should_KeepTheActiveRowAboveTheIme() {
        open(BUDGET, imeFraction = 0.45f, landscape = true)
        // landscape = true only picks the window size class; the compact layout itself comes from the real window size.
        edit(CellAddress(2, 2))
        shot("sheet-landscape-edit")
        val above = bounds("aboveIme").bottom
        println("RENDER landscape aboveIme=${above / density()}dp bar=${bounds("formulaBar")} grid=${bounds("sheetGrid")} row2=${bounds("sheetRow-2")}")
        assertTrue(bounds("formulaBar").bottom <= above + 0.5f, "edit bar above the IME")
        assertVisibleAbove("sheetRow-2", above)
    }

    /** A row counts as visible only when it ends above the IME and above the bottom of the grid viewport (the grid clips). */
    private fun assertVisibleAbove(rowTag: String, above: Float) {
        val row = rule.onAllNodes(hasTestTag(rowTag)).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        // Semantics bounds are clipped to the viewport, so a row cut by the IME or the grid edge is shorter than the 44dp minimum.
        assertTrue(row != null && row.height >= 44 * density(), "$rowTag is cut off: ${row?.height?.div(density())}dp visible")
        val grid = bounds("sheetGrid")
        assertTrue(row.bottom <= above + 0.5f, "$rowTag bottom ${row.bottom / density()}dp vs IME top ${above / density()}dp")
        assertTrue(row.bottom <= grid.bottom + 0.5f, "$rowTag bottom ${row.bottom / density()}dp is clipped by the grid viewport ending at ${grid.bottom / density()}dp")
    }

    /** UX-83: edit bar, active row and a neighbour above the IME; top bar + edit bar + context strip + header <= 50% of that space. */
    private fun assertVerticalBudget(row: Int, neighbour: Int) {
        val above = bounds("aboveIme").bottom
        val bar = bounds("formulaBar")
        assertTrue(bar.bottom <= above + 0.5f, "edit bar bottom ${bar.bottom / density()}dp vs IME top ${above / density()}dp")
        assertVisibleAbove("sheetRow-$row", above)
        assertVisibleAbove("sheetRow-$neighbour", above)
        val chrome = listOf("sheetTopBar", "formulaBar", "sheetContextStrip", "sheetLetters").sumOf { tag ->
            val b = bounds(tag)
            (b.bottom - b.top).toDouble()
        }
        assertTrue(chrome <= above * 0.5, "chrome ${chrome / density()}dp of ${above / density()}dp above the IME (limit 50%)")
    }

    // ---- inline --------------------------------------------------------------------------------------------

    private fun inline(inlineOn: Boolean, content: String = INLINE, width: Boolean = false, imeFraction: Float = 0f) {
        rule.setContent {
            MaterialTheme {
                Surface {
                    ImeSplit(imeFraction) {
                        Box(if (width) Modifier.width(360.dp) else Modifier.fillMaxWidth()) {
                            TableBlock(
                                content = content,
                                onStartEditing = {},
                                prefs = TableDisplayPrefs(inlineEditEnabled = inlineOn, sheetEnabled = inlineOn, numericRightAlign = false),
                                onCommit = {},
                                onOpenSheet = if (inlineOn) ({}) else null,
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun inlineEdit_should_ShowTheActiveCellTheStripAndTwoRowsAboveTheIme() {
        inline(inlineOn = true, imeFraction = 0.45f)
        rule.onNodeWithText("3").performTouchInput { click() }
        rule.waitForIdle()
        shot("inline-edit")
        val above = bounds("aboveIme").bottom
        assertTrue(rule.onNodeWithText("Apple").fetchSemanticsNode().boundsInRoot.bottom <= above + 0.5f, "active row above the IME")
        assertTrue(rule.onNodeWithText("Pear").fetchSemanticsNode().boundsInRoot.bottom <= above + 0.5f, "a second row above the IME")
        // The strip lives in its own overlay window: its dock to the IME top cannot be asserted headless (no ime inset).
        val strip = rule.onNodeWithTag("tableStrip").fetchSemanticsNode()
        println("RENDER strip boundsInWindow=${strip.boundsInWindow} aboveImeBottom=$above")
    }

    @Test
    fun inlineNavigate_should_RenderTheTableWithTheMaximizeAffordance() {
        inline(inlineOn = true, width = true)
        shot("inline-navigate")
    }

    @Test
    fun inlineDone_should_LeaveTheNavigateStripVisibleOnTouch() {
        inline(inlineOn = true, imeFraction = 0.45f)
        rule.onNodeWithText("3").performTouchInput { click() }
        rule.waitForIdle()
        rule.onNodeWithText("Done").performClick()
        shot("inline-navigate-strip")
        rule.onNodeWithTag("tableStrip").fetchSemanticsNode()
    }

    @Test
    fun inline120CharCell_should_WrapOnAPhone() {
        inline(inlineOn = true, content = LONG, width = true)
        shot("inline-long-cell")
    }

    @Test
    fun flagsOff_should_RenderTheBaselineTable() {
        inline(inlineOn = false, content = BASELINE_WRAP, width = true)
        shot("flag-off-wrap")
    }

    companion object {
        private val BUDGET = buildString {
            append("| Item | Qty | Price | Total |\n|---|---|---|---|")
            for (r in 2..13) append("\n| Item $r | $r | ${r * 2}.50 | =B$r*C$r |")
        }
        private val LONG_TEXT = "Cabinet with redundant power supplies, a spare output module, two fans and a long cable run to the stage " + "x".repeat(10)
        private val LONG = "| Item | Notes | Qty |\n|---|---|---|\n| Controller | $LONG_TEXT | 2 |\n| Link | short | 6 |"
        private val INLINE = "| Item | Qty | Note |\n|---|---|---|\n| Apple | 3 | red |\n| Pear | 5 | green |\n| Fig | 8 | purple |"
        private val BASELINE_WRAP = """
            | Item | Notes | Qty |
            |---|---|---|
            | Controller | Cabinet with redundant power supplies and a spare output module (60ch) | 2 |
            | Link | [the docs](https://example.com/docs) | 6 |
        """.trimIndent()
    }
}

/** The window split into the usable area (`aboveIme`) and a grey placeholder for the soft keyboard. */
@Composable
private fun ImeSplit(imeFraction: Float, content: @Composable () -> Unit) {
    if (imeFraction <= 0f) {
        Box(Modifier.fillMaxSize().testTag("aboveIme")) { content() }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f - imeFraction).fillMaxWidth().testTag("aboveIme")) { content() }
        Box(Modifier.weight(imeFraction).fillMaxWidth().background(Color(0xFF9E9E9E)).testTag("fakeIme")) {
            Text("IME placeholder (45% of the window)")
        }
    }
}

private fun SemanticsNodeInteraction.assertExistsNode() {
    fetchSemanticsNode()
}
