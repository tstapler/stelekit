// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.logging.LogSink
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.ui.components.sheet.CellEditorKey
import dev.stapler.stelekit.ui.components.sheet.InlineTableBlock
import dev.stapler.stelekit.ui.components.sheet.INLINE_CELL_WINDOW
import dev.stapler.stelekit.ui.components.sheet.InlineTableState
import dev.stapler.stelekit.ui.components.sheet.inlineRowWindow
import dev.stapler.stelekit.ui.components.sheet.resolveCellEditorKey
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stories 4.3 and 4.5 (display, fallback, window): the in-cell editor, commit, echo and formula display. Gradle-only (Compose host). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class TableInlineEditTest {
    @get:Rule
    val rule = createComposeRule()

    private val table = "| Item | Qty | Note |\n|---|---|---|\n| Apple | 3 | red |\n| Pear | 5 | green |"
    private var content by mutableStateOf(table)
    private val commits = mutableListOf<String>()
    private var started = 0
    private val usage = mutableListOf<String>()
    private val sheetLog = mutableListOf<String>()
    private var handle: dev.stapler.stelekit.logging.SinkHandle? = null

    @Before
    fun captureLogs() {
        handle = LogManager.addSink(object : LogSink {
            override fun write(entry: LogEntry, formatted: String) {
                if (entry.tag == "TableUsage") usage += entry.message
                if (entry.tag == "TableSheet") sheetLog += entry.message
            }
        })
    }

    @After
    fun releaseLogs() {
        handle?.close()
    }

    private fun show(initial: String = table, sheetEnabled: Boolean = false) {
        content = initial
        rule.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(360.dp)) {
                        TableBlock(
                            content = content,
                            onStartEditing = { started++ },
                            prefs = TableDisplayPrefs(inlineEditEnabled = true, sheetEnabled = sheetEnabled),
                            onCommit = { commits += it },
                            onOpenSheet = if (sheetEnabled) ({}) else null,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tap(text: String) {
        rule.onNodeWithText(text).performTouchInput { click() }
        rule.waitForIdle()
    }

    private fun editor() = rule.onNode(hasSetTextAction())
    private fun editors() = rule.onAllNodes(hasSetTextAction())

    private fun press(key: Key) {
        editor().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun hasStateDescription(part: String) = SemanticsMatcher("stateDescription contains $part") {
        it.config.getOrNull(SemanticsProperties.StateDescription)?.contains(part) == true
    }

    private fun hasError(text: String) = SemanticsMatcher("error == $text") {
        it.config.getOrNull(SemanticsProperties.Error) == text
    }

    // ---- commit, cancel, escaping --------------------------------------------------------------

    @Test
    fun enter_should_CommitOnceWithExactRowAndMoveEditDown() {
        show()
        tap("3")
        editor().performTextReplacement("4")
        editor().performTextInput("5")
        press(Key.Enter)
        assertEquals(listOf("| Item | Qty | Note |\n|---|---|---|\n| Apple | 45 | red |\n| Pear | 5 | green |"), commits)
        editors().assertCountEquals(1)
        editor().assertTextEquals("5")
        assertEquals(listOf("via=inline"), usage)
    }

    @Test
    fun enterOnUnchangedDraft_should_MoveWithoutCommit() {
        show()
        tap("3")
        press(Key.Enter)
        assertTrue(commits.isEmpty())
        editor().assertTextEquals("5")
    }

    @Test
    fun escape_should_CancelWithoutCommitAndShowOldValue() {
        show()
        tap("3")
        editor().performTextReplacement("45")
        press(Key.Escape)
        assertTrue(commits.isEmpty())
        editors().assertCountEquals(0)
        rule.onNodeWithText("3").assertExists()
        assertTrue(usage.isEmpty())
    }

    @Test
    fun tab_should_CommitOnceAndEditNextColumn() {
        show()
        tap("3")
        editor().performTextReplacement("9")
        press(Key.Tab)
        assertEquals(1, commits.size)
        assertTrue(commits[0].contains("| Apple | 9 | red |"))
        editor().assertTextEquals("red")
    }

    @Test
    fun pipe_should_BeEscapedInSerializedRow() {
        show()
        tap("3")
        editor().performTextReplacement("a|b")
        press(Key.Enter)
        assertTrue(commits.single().contains("| Apple | a\\|b | red |"), commits.single())
    }

    @Test
    fun altEnter_should_InsertLineBreakSerializedAsBr() {
        show()
        tap("3")
        editor().performTextReplacement("a")
        editor().performKeyInput {
            keyDown(Key.AltLeft)
            pressKey(Key.Enter)
            keyUp(Key.AltLeft)
        }
        rule.waitForIdle()
        assertTrue(commits.isEmpty())
        editor().performTextInput("b")
        press(Key.Enter)
        assertTrue(commits.single().contains("| Apple | a<br>b | red |"), commits.single())
    }

    @Test
    fun tapOnAnotherCell_should_CommitDirtyDraftOnceThenEditThatCell() {
        show()
        tap("3")
        editor().performTextReplacement("8")
        rule.onNodeWithText("green").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(1, commits.size)
        editor().assertTextEquals("green")
    }

    // ---- IME composition guard (pure resolver; Compose cannot set a composition from a test) ------

    @Test
    fun compositionGuard_should_NotConsumeEnterTabOrEscapeWhileComposing() {
        assertNull(resolveCellEditorKey(Key.Enter, shift = false, alt = false, ctrl = false, meta = false, composing = true))
        assertNull(resolveCellEditorKey(Key.Tab, shift = false, alt = false, ctrl = false, meta = false, composing = true))
        assertNull(resolveCellEditorKey(Key.Escape, shift = false, alt = false, ctrl = false, meta = false, composing = true))
    }

    @Test
    fun resolver_should_MapChordsWhenNotComposing() {
        fun r(k: Key, shift: Boolean = false, alt: Boolean = false) =
            resolveCellEditorKey(k, shift, alt, ctrl = false, meta = false, composing = false)
        assertEquals(CellEditorKey.Enter(back = false), r(Key.Enter))
        assertEquals(CellEditorKey.Enter(back = true), r(Key.Enter, shift = true))
        assertEquals(CellEditorKey.LineBreak, r(Key.Enter, alt = true))
        assertEquals(CellEditorKey.Tab(back = false), r(Key.Tab))
        assertEquals(CellEditorKey.Tab(back = true), r(Key.Tab, shift = true))
        assertEquals(CellEditorKey.Cancel, r(Key.Escape))
        assertNull(r(Key.A))
        assertNull(resolveCellEditorKey(Key.Enter, false, false, ctrl = true, meta = false, composing = false))
    }

    @Test
    fun state_should_NotMoveOrCommit_When_KeyArrivesWhileComposing() {
        val sent = mutableListOf<String>()
        val state = InlineTableState(table) { sent += it }
        state.startEdit(CellAddress(2, 2))
        state.onFieldChange(TextFieldValue("77", TextRange(2), composition = TextRange(0, 2)))
        assertFalse(state.handleKey(CellEditorKey.Enter(back = false)))
        assertTrue(sent.isEmpty())
        assertEquals(CellAddress(2, 2), state.draft?.address)
    }

    // ---- shift-enter, undo-free state behaviour -------------------------------------------------

    @Test
    fun shiftEnter_should_CommitAndMoveUp() {
        val sent = mutableListOf<String>()
        val state = InlineTableState(table) { sent += it }
        state.startEdit(CellAddress(3, 2))
        state.onFieldChange(TextFieldValue("6"))
        assertTrue(state.handleKey(CellEditorKey.Enter(back = true)))
        assertEquals(1, sent.size)
        assertEquals(CellAddress(2, 2), state.draft?.address)
        assertEquals(GridMode.Edit, state.mode)
    }

    @Test
    fun enterOnLastRow_should_CommitAndStayInNavigate() {
        val sent = mutableListOf<String>()
        val state = InlineTableState(table) { sent += it }
        state.startEdit(CellAddress(3, 2))
        state.onFieldChange(TextFieldValue("6"))
        state.handleKey(CellEditorKey.Enter(back = false))
        assertEquals(1, sent.size)
        assertEquals(GridMode.Navigate, state.mode)
        assertEquals(CellAddress(3, 2), state.selection?.anchor)
    }

    @Test
    fun commitDraft_should_BeExposedAndCommitOnlyWhenChanged() {
        val sent = mutableListOf<String>()
        val state = InlineTableState(table) { sent += it }
        state.startEdit(CellAddress(2, 2))
        assertFalse(state.commitDraft())
        state.onFieldChange(TextFieldValue("4"))
        assertTrue(state.commitDraft())
        assertFalse(state.commitDraft())
        assertEquals(1, sent.size)
    }

    // ---- echo ------------------------------------------------------------------------------------

    @Test
    fun echo_should_NotResetEditorOrCommitAgain() {
        show()
        tap("3")
        editor().performTextReplacement("4")
        press(Key.Enter)
        editor().performTextReplacement("7")
        content = commits.single()
        rule.waitForIdle()
        editor().assertTextEquals("7")
        assertEquals(1, commits.size)
    }

    @Test
    fun externalEdit_should_RebuildWhenNoDraft() {
        show()
        tap("3")
        press(Key.Escape)
        content = "| Item | Qty | Note |\n|---|---|---|\n| Mango | 1 | yellow |"
        rule.waitForIdle()
        rule.onNodeWithText("Mango").assertExists()
        rule.onNodeWithText("Apple").assertDoesNotExist()
    }

    @Test
    fun externalEdit_should_KeepDraftAndHoldCommits_When_DraftPending() {
        show()
        tap("3")
        editor().performTextReplacement("9")
        content = "| Item | Qty | Note |\n|---|---|---|\n| Mango | 1 | yellow |"
        rule.waitForIdle()
        editor().assertTextEquals("9")
        press(Key.Enter)
        assertTrue(commits.isEmpty(), "full conflict banner is PR-C; commits are held meanwhile")
        editor().assertTextEquals("9")
    }

    // ---- formula display (Task 4.5b) -------------------------------------------------------------

    private val formulas = "| A | B | C |\n|---|---|---|\n| x | 3 | =B2*2 |\n| y | =B2/0 | =5 apples |"

    @Test
    fun formula_should_ShowValueWithFxMarkerAndFormulaInStateDescription() {
        show(formulas)
        rule.onNodeWithText("6").assert(hasStateDescription("=B2*2"))
        rule.onNodeWithText("fx", useUnmergedTree = true).assertExists()
    }

    @Test
    fun formula_should_ShowRawFormulaInEdit() {
        show(formulas)
        tap("6")
        editor().assertTextEquals("=B2*2")
    }

    @Test
    fun errorCell_should_ShowTokenWithErrorSemantics() {
        show(formulas)
        rule.onNodeWithText("#DIV/0!").assert(hasError("Division by zero"))
    }

    @Test
    fun unparseableFormulaShapedText_should_KeepLiteralWithMarker() {
        show(formulas)
        rule.onNodeWithText("=5 apples").assert(hasError("Not a valid formula"))
        rule.onNodeWithText("#PARSE!").assertDoesNotExist()
        tap("=5 apples")
        editor().assertTextEquals("=5 apples")
    }

    // ---- fallback and window (Task 4.5c) ---------------------------------------------------------

    @Test
    fun unparseableTable_should_ShowFallbackWithReasonAndEditAction_When_ParserFails() {
        show("| a | b |\n| 1 | 2 |")
        rule.onNodeWithText("This table can't be edited as a grid: missing delimiter row.").assertExists()
        rule.onNodeWithText("Edit as markdown").performClick()
        assertEquals(1, started)
        rule.waitForIdle()
        assertEquals(1, sheetLog.size, sheetLog.toString())
        editors().assertCountEquals(0)
    }

    @Test
    fun inlineRowWindow_should_CapRowsToTheCellBudget() {
        assertEquals(5_000, INLINE_CELL_WINDOW)
        assertEquals(833, inlineRowWindow(rowCount = 1_001, columnCount = 6, windowCells = INLINE_CELL_WINDOW))
        assertEquals(10, inlineRowWindow(rowCount = 10, columnCount = 6, windowCells = INLINE_CELL_WINDOW))
        assertEquals(1, inlineRowWindow(rowCount = 50, columnCount = 9_000, windowCells = INLINE_CELL_WINDOW))
    }

    @Test
    fun largeTable_should_RenderCappedWindowWithMaximize_When_OverBudget() {
        val rows = (1..10).joinToString("\n") { "| r$it | $it |" }
        val big = "| K | V |\n|---|---|\n$rows"
        rule.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(360.dp)) {
                        InlineTableBlock(
                            content = big,
                            onStartEditing = {},
                            prefs = TableDisplayPrefs(inlineEditEnabled = true, sheetEnabled = true),
                            onCommit = {},
                            onOpenSheet = {},
                            cellWindow = 8,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Showing the first 4 of 11 rows").assertExists()
        rule.onNodeWithText("Maximize").assertExists()
        rule.onNodeWithText("r2").assertExists()
        rule.onNodeWithText("r9").assertDoesNotExist()
        rule.onAllNodes(SemanticsMatcher("full collection counts") {
            it.config.getOrNull(SemanticsProperties.CollectionInfo)?.let { c -> c.rowCount == 11 && c.columnCount == 2 } == true
        }).assertCountEquals(1)
    }

    @Test
    fun largeTable_should_OfferOnlyEditAsMarkdown_When_SheetFlagOff() {
        val rows = (1..10).joinToString("\n") { "| r$it | $it |" }
        val big = "| K | V |\n|---|---|\n$rows"
        rule.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(360.dp)) {
                        InlineTableBlock(
                            content = big,
                            onStartEditing = {},
                            prefs = TableDisplayPrefs(inlineEditEnabled = true),
                            onCommit = {},
                            cellWindow = 8,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Maximize").assertDoesNotExist()
        rule.onNodeWithText("Edit as markdown").assertExists()
    }

    @Test
    fun leavingComposition_should_CommitADirtyDraft() {
        val host = TableTestHost(rule)
        host.show()
        rule.onNodeWithText("3").performTouchInput { click() }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextReplacement("45")
        rule.waitForIdle()
        assertTrue(host.commits.isEmpty())
        host.present = false
        rule.waitForIdle()
        assertTrue(host.commits.last().contains("| 45 |"), "dispose must commit the draft: ${host.commits}")
    }
}
