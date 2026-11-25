// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
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

/** Story 5.7 on the real sheet body over the real block manager: the labelled sort, its announcement, helper text, refusals and totals. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w800dp-h780dp-xxhdpi")
class SheetSortTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var fixture: SheetFixture? = null

    @After
    fun tearDown() {
        fixture?.close()
    }

    private fun open(content: String): SheetFixture = SheetFixture(rule, content).also {
        fixture = it
        it.show(realCommands = true)
    }

    private fun addr(text: String) = CellAddress(text.drop(1).toInt(), text[0] - 'A' + 1)

    private fun doc(f: SheetFixture) = (TableParser.parse(f.h.managerContent()) as TableParseResult.Parsed).doc

    private fun column(f: SheetFixture, col: Char) = (2..doc(f).rowCount).map { doc(f).cell(addr("$col$it"))?.value }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun openColumnMenu(col: Char) {
        rule.onNodeWithTag("letter-$col").performClick()
        rule.waitForIdle()
    }

    private fun select(f: SheetFixture, a: String, b: String = a) {
        rule.runOnUiThread { f.s.selectRange(GridSelection(addr(a), addr(b))) }
        rule.waitForIdle()
    }

    private fun noticeText() = rule.textOf("noticeText")

    private val names = "| Name | Qty |\n|---|---|\n| Pear | 5 |\n| Apple | 3 |\n| Fig | 4 |"

    @Test
    fun sortAscending_should_OrderTheBody_KeepTheHeader_Announce_AndOneUndoRestores() {
        val f = open(names)
        openColumnMenu('A')
        assertTrue(rule.textOf("menu-sortAsc").orEmpty().startsWith("Sort rows A-Z (changes file)"))
        assertTrue(rule.textOf("menu-sortDesc").orEmpty().startsWith("Sort rows Z-A (changes file)"))
        tap("menu-sortAsc")
        assertEquals(listOf("Apple", "Fig", "Pear"), column(f, 'A'))
        assertEquals("Name", doc(f).cell(addr("A1"))?.value)
        assertEquals("Sorted by Name", f.s.announcement)
        f.h.session!!.undo()
        rule.waitForIdle()
        assertEquals(names, f.h.managerContent())
    }

    @Test
    fun sortDescending_should_ReverseTheOrder() {
        val f = open(names)
        openColumnMenu('A')
        tap("menu-sortDesc")
        assertEquals(listOf("Pear", "Fig", "Apple"), column(f, 'A'))
    }

    @Test
    fun sortIndicator_should_ShowOnTheColumn_AndClearWhenAnotherEditChangesTheRowOrder() {
        val f = open(names)
        openColumnMenu('A')
        tap("menu-sortAsc")
        assertTrue(rule.textOf("letter-A").orEmpty().contains("▲"), "ascending arrow on the sorted column")
        assertEquals(1, f.s.view.sort?.col)
        rule.onNodeWithTag("gutter-2").performClick()
        tap("menu-insertBelow")
        assertEquals(null, f.s.view.sort, "an insert changes the row order, so the indicator clears (ADR-007)")
    }

    @Test
    fun announcement_should_AddTheFormulaAndBlankSuffixesOnlyWhenTheCountsAreAboveZero() {
        // Rows 2 and 3 swap; the cross-row formulas sit in unmoved blank-key rows, so the sort is allowed.
        val content = "| Item | Qty | Link |\n|---|---|---|\n| x | 2 |  |\n| y | 1 |  |\n| z |  | =B2 |\n| w |  | =B3 |\n| v |  |  |"
        val f = open(content)
        openColumnMenu('B')
        tap("menu-sortAsc")
        assertEquals("Sorted by Qty. 2 formulas reference other rows. 3 blank cells sorted last", f.s.announcement)
    }

    @Test
    fun menuHelper_should_SayHowManyFormulasWillBeRewrittenBeforeConfirming() {
        // All four rows reverse, and each own-row formula is rewritten to its new row.
        val content = "| K | N | D |\n|---|---|---|\n| a | 4 | =B2*2 |\n| b | 3 | =B3*2 |\n| c | 2 | =B4*2 |\n| d | 1 | =B5*2 |"
        val f = open(content)
        openColumnMenu('B')
        val helper = rule.onNodeWithTag("menu-sortAsc").fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        assertTrue(helper.contains("4 formulas will be rewritten"), helper)
        assertEquals(content, f.h.managerContent(), "opening the menu committed nothing")
        tap("menu-sortAsc")
        assertEquals(listOf("1", "2", "3", "4"), column(f, 'B'))
        assertEquals(listOf("=B2*2", "=B3*2", "=B4*2", "=B5*2"), column(f, 'C'))
    }

    @Test
    fun sortByComputedValue_should_OrderByTheNumbersTheFormulasProduce() {
        val content = "| A | B |\n|---|---|\n| 3 | =A2*2 |\n| 1 | =A3*2 |\n| 2 | =A4*2 |"
        val f = open(content)
        openColumnMenu('B')
        tap("menu-sortAsc")
        assertEquals(listOf("1", "2", "3"), column(f, 'A'))
        assertEquals(listOf("=A2*2", "=A3*2", "=A4*2"), column(f, 'B'))
    }

    @Test
    fun totalsRow_should_StayAtTheBottom_AndTheMenuSaysSo() {
        val content = "| N | V |\n|---|---|\n| a | 3 |\n| b | 1 |\n| c | 2 |\n| Total | =SUM(B2:B4) |"
        val f = open(content)
        openColumnMenu('B')
        assertTrue(rule.textOf("menu-sortAsc").orEmpty().contains("Totals row stays at the bottom"))
        tap("menu-sortAsc")
        assertEquals(listOf("1", "2", "3", "=SUM(B2:B4)"), column(f, 'B'))
        assertEquals("Total", doc(f).cell(addr("A5"))?.value)
    }

    @Test
    fun multiRowSelection_should_SortOnlyThoseRows_UnderTheSelectedRowsLabels() {
        val content = "| N | V |\n|---|---|\n| a | 9 |\n| b | 3 |\n| c | 2 |\n| d | 1 |"
        val f = open(content)
        select(f, "B3", "B5")
        rule.runOnUiThread { f.s.openMenu(dev.stapler.stelekit.ui.components.sheet.SheetMenuTarget.Column(2)) }
        rule.waitForIdle()
        assertTrue(rule.textOf("menu-sortAsc").orEmpty().startsWith("Sort selected rows A-Z (changes file)"))
        assertTrue(rule.textOf("menu-sortDesc").orEmpty().startsWith("Sort selected rows Z-A (changes file)"))
        tap("menu-sortAsc")
        assertEquals(listOf("9", "1", "2", "3"), column(f, 'B'), "row 2 stays, rows 3 to 5 sort")
    }

    @Test
    fun sortThatWouldChangeFormulaResults_should_BeRefusedWithDismissAndEditAsMarkdown() {
        val content = "| A | B |\n|---|---|\n| 3 | =A3 |\n| 1 | 0 |"
        val f = open(content)
        openColumnMenu('A')
        tap("menu-sortAsc")
        assertEquals("Sort would change formula results: 1 formula references other rows", noticeText())
        assertEquals(TableMessages.EDIT_AS_MARKDOWN, rule.textOf("noticeAction"))
        assertTrue(rule.exists("noticeDismiss"))
        assertEquals(content, f.h.managerContent())
    }

    @Test
    fun sortThatWouldPutATotalInsideItsOwnRange_should_BeRefusedWithNoChange() {
        val content = "| V |\n|---|\n| 3 |\n| 1 |\n| 2 |\n| =SUM(A2:A4) |"
        val f = open(content)
        select(f, "A2", "A5")
        rule.runOnUiThread { f.s.openMenu(dev.stapler.stelekit.ui.components.sheet.SheetMenuTarget.Column(1)) }
        rule.waitForIdle()
        tap("menu-sortDesc")
        assertEquals("Sort would put a total inside its own range", noticeText())
        assertEquals(content, f.h.managerContent())
        assertFalse(f.s.view.sort != null)
    }
}
