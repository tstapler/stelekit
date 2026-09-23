package dev.stapler.stelekit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.stapler.stelekit.ui.components.AutocompleteState
import dev.stapler.stelekit.ui.components.AutocompleteTrigger
import dev.stapler.stelekit.ui.components.BlockEditor
import dev.stapler.stelekit.ui.components.TodoState
import dev.stapler.stelekit.ui.components.applyFormatAction
import dev.stapler.stelekit.ui.components.applyTodoToggle
import dev.stapler.stelekit.ui.components.detectAutocompleteMatch
import dev.stapler.stelekit.ui.components.detectSoftKeyboardBracketWrap
import dev.stapler.stelekit.ui.screens.FormatAction
import dev.stapler.stelekit.ui.screens.SearchResultItem
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)

/**
 * Integration tests verifying Compose key event propagation for Cmd+K.
 *
 * The production topology:
 *   App.kt  →  .onKeyEvent (bubble phase, fires AFTER children)  →  opens search
 *   BlockEditor.kt  →  .onPreviewKeyEvent (tunnel phase, fires BEFORE focus target)
 *
 * Behaviour:
 *   - No selection: Cmd+K falls through to global handler → opens search
 *   - Selection: Cmd+K consumed by BlockEditor → opens search pre-filled with selected text
 */
class KeyboardShortcutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Mirrors the BlockEditor / App key event topology with minimal dependencies.
    @Composable
    private fun ShortcutTestHarness(
        onGlobalSearch: () -> Unit,
        onOpenSearchWithText: (String) -> Unit = {},
        onTextChanged: (TextFieldValue) -> Unit,
        textState: TextFieldValue,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Mirrors App.kt: global shortcuts on the bubble-up (onKeyEvent) phase.
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.isMetaPressed || event.isCtrlPressed) &&
                        event.key == Key.K
                    ) {
                        onGlobalSearch()
                        true
                    } else false
                }
        ) {
            BasicTextField(
                value = textState,
                onValueChange = onTextChanged,
                modifier = Modifier
                    .testTag("editor")
                    // Mirrors BlockEditor.kt: Cmd+K with selection opens search pre-filled;
                    // without selection the event falls through to the global handler.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.isMetaPressed || event.isCtrlPressed) &&
                            event.key == Key.K
                        ) {
                            if (!textState.selection.collapsed) {
                                val selectedText = textState.text.substring(
                                    textState.selection.min, textState.selection.max
                                )
                                onOpenSearchWithText(selectedText)
                                true
                            } else {
                                false // no selection — pass through to global handler
                            }
                        } else false
                    }
            )
        }
    }

    @Test
    fun `Cmd+K without text selection fires global search`() {
        var globalSearchFired = false
        var textState by mutableStateOf(TextFieldValue(""))

        composeTestRule.setContent {
            ShortcutTestHarness(
                textState = textState,
                onGlobalSearch = { globalSearchFired = true },
                onTextChanged = { textState = it },
            )
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.MetaLeft) {
                keyDown(Key.K)
                keyUp(Key.K)
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(globalSearchFired, "Global search should fire when Cmd+K pressed with no selection")
        assertEquals("", textState.text, "Text should be unchanged when no selection")
    }

    @Test
    fun `Cmd+K with text selected opens search pre-filled with selected text`() {
        var globalSearchFired = false
        var searchWithTextArg: String? = null
        var textState by mutableStateOf(
            TextFieldValue("hello world", selection = TextRange(0, 5))
        )

        composeTestRule.setContent {
            ShortcutTestHarness(
                textState = textState,
                onGlobalSearch = { globalSearchFired = true },
                onOpenSearchWithText = { searchWithTextArg = it },
                onTextChanged = { textState = it },
            )
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()

        // Restore the selection explicitly since performClick may collapse it
        composeTestRule.runOnUiThread {
            textState = TextFieldValue("hello world", selection = TextRange(0, 5))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.MetaLeft) {
                keyDown(Key.K)
                keyUp(Key.K)
            }
        }
        composeTestRule.waitForIdle()

        assertFalse(globalSearchFired, "Global handler should NOT fire — event consumed by BlockEditor")
        assertEquals("hello", searchWithTextArg, "Search should open pre-filled with selected text")
        assertEquals("hello world", textState.text, "Text should not be modified (no link wrapping)")
    }

    @Test
    fun `Cmd+K in non-editor input (no preview handler) reaches global handler`() {
        // Simulates focus in a text field without BlockEditor's onPreviewKeyEvent.
        // The global handler should still fire because the event bubbles up unobstructed.
        var globalSearchFired = false
        var textState by mutableStateOf(TextFieldValue(""))

        composeTestRule.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.isMetaPressed || event.isCtrlPressed) &&
                            event.key == Key.K
                        ) {
                            globalSearchFired = true
                            true
                        } else false
                    }
            ) {
                BasicTextField(
                    value = textState,
                    onValueChange = { textState = it },
                    modifier = Modifier.testTag("plain-input")
                )
            }
        }

        composeTestRule.onNodeWithTag("plain-input").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("plain-input").performKeyInput {
            withKeyDown(Key.MetaLeft) {
                keyDown(Key.K)
                keyUp(Key.K)
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(globalSearchFired, "Global handler should fire when focused input has no Cmd+K handler")
    }

    // ------------------------------------------------------------------------------------
    // Phase C.1: TodoState / applyTodoToggle (pure-logic tests)
    // ------------------------------------------------------------------------------------

    @Test
    fun `applyTodoToggle cycles NONE to TODO to DONE to TODO for the same content`() {
        assertEquals(TodoState.NONE, TodoState.parse("Buy milk"))
        assertEquals(TodoState.TODO, TodoState.parse("TODO Buy milk"))
        assertEquals(TodoState.DONE, TodoState.parse("DONE Buy milk"))
        assertEquals(TodoState.TODO, TodoState.NONE.next())
        assertEquals(TodoState.DONE, TodoState.TODO.next())
        assertEquals(TodoState.DONE, TodoState.DOING.next())
        assertEquals(TodoState.TODO, TodoState.DONE.next())

        val once = applyTodoToggle(TextFieldValue("Buy milk"))
        assertEquals("TODO Buy milk", once.text)

        val twice = applyTodoToggle(once)
        assertEquals("DONE Buy milk", twice.text)

        val thrice = applyTodoToggle(twice)
        assertEquals("TODO Buy milk", thrice.text)
    }

    @Test
    fun `applyFormatAction should preserveTodoMarker when HEADING applied to TODO-prefixed block`() {
        var textState by mutableStateOf(TextFieldValue("TODO Buy milk"))
        var lastContent: String? = null

        applyFormatAction(
            FormatAction.HEADING,
            textState,
            onTextFieldValueChange = { textState = it },
            onLocalVersionIncrement = { 1L },
            onContentChange = { content, _ -> lastContent = content },
        )

        assertEquals("TODO # Buy milk", textState.text, "TODO marker must be preserved, not stripped by the HEADING strip-group")
        assertEquals("TODO # Buy milk", lastContent)

        // Conversely: applyTodoToggle on a HEADING-prefixed block preserves the heading marker.
        val toggled = applyTodoToggle(TextFieldValue("# Buy milk"))
        assertEquals("TODO # Buy milk", toggled.text, "HEADING marker must be preserved by applyTodoToggle")
    }

    @Test
    fun `applyFormatAction should preserveTodoMarker when QUOTE applied to TODO-prefixed block`() {
        var textState by mutableStateOf(TextFieldValue("TODO Buy milk"))
        var lastContent: String? = null

        applyFormatAction(
            FormatAction.QUOTE,
            textState,
            onTextFieldValueChange = { textState = it },
            onLocalVersionIncrement = { 1L },
            onContentChange = { content, _ -> lastContent = content },
        )

        assertEquals("TODO > Buy milk", textState.text, "TODO marker must be preserved, not stripped by the QUOTE strip-group")
        assertEquals("TODO > Buy milk", lastContent)

        // Conversely: applyTodoToggle on a QUOTE-prefixed block preserves the quote marker.
        val toggled = applyTodoToggle(TextFieldValue("> Buy milk"))
        assertEquals("TODO > Buy milk", toggled.text, "QUOTE marker must be preserved by applyTodoToggle")
    }

    @Test
    fun `applyFormatAction should preserveTodoMarker when NUMBERED_LIST applied to TODO-prefixed block`() {
        var textState by mutableStateOf(TextFieldValue("TODO Buy milk"))
        var lastContent: String? = null

        applyFormatAction(
            FormatAction.NUMBERED_LIST,
            textState,
            onTextFieldValueChange = { textState = it },
            onLocalVersionIncrement = { 1L },
            onContentChange = { content, _ -> lastContent = content },
        )

        assertEquals("TODO 1. Buy milk", textState.text, "TODO marker must be preserved, not stripped by the NUMBERED_LIST strip-group")
        assertEquals("TODO 1. Buy milk", lastContent)

        // Conversely: applyTodoToggle on a NUMBERED_LIST-prefixed block preserves the list marker.
        val toggled = applyTodoToggle(TextFieldValue("1. Buy milk"))
        assertEquals("TODO 1. Buy milk", toggled.text, "NUMBERED_LIST marker must be preserved by applyTodoToggle")
    }

    // ------------------------------------------------------------------------------------
    // Phase C.2: FormatAction.CODE_BLOCK / FormatAction.TABLE_INSERT (GAP-006 / GAP-007)
    // ------------------------------------------------------------------------------------

    @Test
    fun `applyFormatAction CODE_BLOCK wraps block content in a fenced code block`() {
        var textState by mutableStateOf(TextFieldValue("print(x)"))

        applyFormatAction(
            FormatAction.CODE_BLOCK,
            textState,
            onTextFieldValueChange = { textState = it },
            onLocalVersionIncrement = { 1L },
            onContentChange = { _, _ -> },
        )

        assertEquals("```\nprint(x)\n```", textState.text)
    }

    @Test
    fun `applyFormatAction CODE_BLOCK unwraps already-fenced content back to plain text`() {
        // Regression guard: re-pressing the CODE_BLOCK toggle on already-fenced content
        // (e.g. a mobile double-tap on the "Code block" toolbar button, which calls
        // onFormat(FormatAction.CODE_BLOCK) unconditionally with no toggle-state guard)
        // must strip the fence back off rather than double-wrapping it.
        var textState by mutableStateOf(TextFieldValue("```\nprint(x)\n```"))

        applyFormatAction(
            FormatAction.CODE_BLOCK,
            textState,
            onTextFieldValueChange = { textState = it },
            onLocalVersionIncrement = { 1L },
            onContentChange = { _, _ -> },
        )

        assertEquals("print(x)", textState.text)
    }

    @Test
    fun `applyFormatAction TABLE_INSERT inserts a 2x2 table skeleton with cursor in first cell`() {
        var textState by mutableStateOf(TextFieldValue(""))

        applyFormatAction(
            FormatAction.TABLE_INSERT,
            textState,
            onTextFieldValueChange = { textState = it },
            onLocalVersionIncrement = { 1L },
            onContentChange = { _, _ -> },
        )

        assertEquals("| | |\n| --- | --- |\n| | |", textState.text)
        assertEquals(TextRange(2), textState.selection, "Cursor should be positioned in the first cell")
    }

    // ------------------------------------------------------------------------------------
    // Phase C.1.2: Ctrl+Enter collision between autocomplete "create new page" and TODO-toggle
    // ------------------------------------------------------------------------------------

    // Minimal harness rendering the real production BlockEditor composable.
    @Composable
    private fun BlockEditorHarness(
        textState: TextFieldValue,
        onTextChanged: (TextFieldValue) -> Unit,
        autocompleteState: AutocompleteState? = null,
        searchResults: List<SearchResultItem> = emptyList(),
        onNewBlock: (String) -> Unit = {},
        onSplitBlock: (String, Int) -> Unit = { _, _ -> },
        onIndent: () -> Unit = {},
        onOutdent: () -> Unit = {},
    ) {
        BlockEditor(
            textFieldValue = textState,
            onTextFieldValueChange = onTextChanged,
            focusRequester = remember { FocusRequester() },
            isEditing = true,
            hasFocused = true,
            onHasFocusedChange = {},
            blockUuid = "block-test",
            autocompleteState = autocompleteState,
            onAutocompleteStateChange = {},
            searchResults = searchResults,
            selectedIndex = 0,
            onSelectedIndexChange = {},
            localVersion = 0L,
            onLocalVersionIncrement = { 1L },
            onContentChange = { _, _ -> },
            onStopEditing = {},
            onNewBlock = onNewBlock,
            onSplitBlock = onSplitBlock,
            onMergeBlock = {},
            onBackspace = {},
            onIndent = onIndent,
            onOutdent = onOutdent,
            onMoveUp = {},
            onMoveDown = {},
            onFocusUp = {},
            onFocusDown = {},
            modifier = Modifier.testTag("editor"),
        )
    }

    // ------------------------------------------------------------------------------------
    // Tab / Shift+Tab indent-dedent dispatch
    // ------------------------------------------------------------------------------------

    @Test
    fun `Shift+Tab fires onOutdent, not onIndent`() {
        var textState by mutableStateOf(TextFieldValue("Buy milk"))
        var indentCalled = false
        var outdentCalled = false

        composeTestRule.setContent {
            BlockEditorHarness(
                textState = textState,
                onTextChanged = { textState = it },
                onIndent = { indentCalled = true },
                onOutdent = { outdentCalled = true },
            )
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.ShiftLeft) {
                keyDown(Key.Tab)
                keyUp(Key.Tab)
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(outdentCalled, "Shift+Tab should dedent the focused block via onOutdent")
        assertFalse(indentCalled, "Shift+Tab must not also fire onIndent")
    }

    @Test
    fun `Plain Tab still fires onIndent (no regression from Shift+Tab fix)`() {
        var textState by mutableStateOf(TextFieldValue("Buy milk"))
        var indentCalled = false
        var outdentCalled = false

        composeTestRule.setContent {
            BlockEditorHarness(
                textState = textState,
                onTextChanged = { textState = it },
                onIndent = { indentCalled = true },
                onOutdent = { outdentCalled = true },
            )
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            keyDown(Key.Tab)
            keyUp(Key.Tab)
        }
        composeTestRule.waitForIdle()

        assertTrue(indentCalled, "Plain Tab should still indent the focused block via onIndent")
        assertFalse(outdentCalled, "Plain Tab must not fire onOutdent")
    }

    @Test
    fun `Ctrl+Enter toggles TODO state when autocomplete is closed`() {
        var textState by mutableStateOf(TextFieldValue("Call mom"))

        composeTestRule.setContent {
            BlockEditorHarness(
                textState = textState,
                onTextChanged = { textState = it },
                autocompleteState = null,
            )
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
        }
        composeTestRule.waitForIdle()

        assertEquals("TODO Call mom", textState.text, "Ctrl+Enter with no autocomplete open should toggle TODO state, not split the block")
    }

    @Test
    fun `Ctrl+Enter with autocomplete open creates new page instead of toggling TODO`() {
        var textState by mutableStateOf(TextFieldValue("[[Foo", selection = TextRange(5)))
        val autocomplete = AutocompleteState(
            query = "Foo",
            cursorRect = Rect.Zero,
            trigger = AutocompleteTrigger.WIKI_LINK,
        )

        composeTestRule.setContent {
            BlockEditorHarness(
                textState = textState,
                onTextChanged = { textState = it },
                autocompleteState = autocomplete,
                searchResults = listOf(SearchResultItem.CreatePageItem("Foo")),
            )
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()

        // Restore the selection explicitly since performClick may collapse it (mirrors the
        // Cmd+K test above).
        composeTestRule.runOnUiThread {
            textState = TextFieldValue("[[Foo", selection = TextRange(5))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
        }
        composeTestRule.waitForIdle()

        assertEquals("[[Foo]]", textState.text, "Existing 'create new page' behavior must win while autocomplete is open")
        assertFalse(textState.text.startsWith("TODO "), "TODO toggle must not fire while autocomplete is open")
    }

    // ------------------------------------------------------------------------------------
    // Phase E.1: IME composition guard (pure-logic tests — JVM/desktop tests cannot
    // reproduce real IME composition, per pitfalls.md §2, but the guard itself is a pure
    // check on TextFieldValue.composition and is fully unit-testable in isolation).
    // ------------------------------------------------------------------------------------

    @Test
    fun `onValueChange should suppressBracketAutocomplete when compositionIsActive`() {
        val midComposition = TextFieldValue(
            text = "hello [[wor",
            selection = TextRange(11),
            composition = TextRange(8, 11),
        )
        assertEquals(null, detectAutocompleteMatch(midComposition), "[[ trigger must not fire while IME composition is active")

        val committed = midComposition.copy(composition = null)
        val match = detectAutocompleteMatch(committed)
        assertTrue(match != null, "[[ trigger should evaluate normally once composition is committed (composition == null)")
    }

    @Test
    fun `detectSoftKeyboardBracketWrap should notFireMidComposition when imeCandidateSelectionInProgress`() {
        val oldText = "hello world"
        val oldSelection = TextRange(0, 5) // "hello" selected
        val newValueMidComposition = TextFieldValue(
            text = "[ world",
            selection = TextRange(1),
            composition = TextRange(0, 1),
        )
        assertEquals(
            null,
            detectSoftKeyboardBracketWrap(oldText, oldSelection, newValueMidComposition),
            "Bracket-wrap heuristic must not fire while IME composition is active — a false positive here would corrupt CJK input",
        )

        // Same text/selection with composition committed (null) evaluates normally.
        val newValueCommitted = newValueMidComposition.copy(composition = null)
        val wrapped = detectSoftKeyboardBracketWrap(oldText, oldSelection, newValueCommitted)
        assertEquals("[[hello]] world", wrapped?.text, "Bracket-wrap should apply normally once composition is committed")
    }

    // ------------------------------------------------------------------------------------
    // Phase E.3 (GAP-016): keyboard shortcuts for LINK/QUOTE/NUMBERED_LIST/HEADING —
    // previously keyboard-unreachable on Desktop/Web, toolbar-only on mobile.
    // ------------------------------------------------------------------------------------

    @Test
    fun `Ctrl+L wraps selection with wiki-link markers`() {
        var textState by mutableStateOf(TextFieldValue("hello world", selection = TextRange(0, 5)))

        composeTestRule.setContent {
            BlockEditorHarness(textState = textState, onTextChanged = { textState = it })
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread {
            textState = TextFieldValue("hello world", selection = TextRange(0, 5))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                keyDown(Key.L)
                keyUp(Key.L)
            }
        }
        composeTestRule.waitForIdle()

        assertEquals("[[hello]] world", textState.text, "Ctrl+L should wrap the selection with [[ ]] (FormatAction.LINK)")
    }

    @Test
    fun `Ctrl+Apostrophe applies QUOTE line prefix`() {
        var textState by mutableStateOf(TextFieldValue("Buy milk"))

        composeTestRule.setContent {
            BlockEditorHarness(textState = textState, onTextChanged = { textState = it })
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                keyDown(Key.Apostrophe)
                keyUp(Key.Apostrophe)
            }
        }
        composeTestRule.waitForIdle()

        assertEquals("> Buy milk", textState.text, "Ctrl+' should apply the QUOTE line prefix")
    }

    @Test
    fun `Ctrl+Shift+7 applies NUMBERED_LIST line prefix`() {
        var textState by mutableStateOf(TextFieldValue("Buy milk"))

        composeTestRule.setContent {
            BlockEditorHarness(textState = textState, onTextChanged = { textState = it })
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.ShiftLeft) {
                    keyDown(Key.Seven)
                    keyUp(Key.Seven)
                }
            }
        }
        composeTestRule.waitForIdle()

        assertEquals("1. Buy milk", textState.text, "Ctrl+Shift+7 should apply the NUMBERED_LIST line prefix (mirrors Google Docs convention)")
    }

    @Test
    fun `Ctrl+Shift+1 applies HEADING line prefix`() {
        var textState by mutableStateOf(TextFieldValue("Buy milk"))

        composeTestRule.setContent {
            BlockEditorHarness(textState = textState, onTextChanged = { textState = it })
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.ShiftLeft) {
                    keyDown(Key.One)
                    keyUp(Key.One)
                }
            }
        }
        composeTestRule.waitForIdle()

        assertEquals("# Buy milk", textState.text, "Ctrl+Shift+1 should apply the HEADING line prefix")
    }

    @Test
    fun `Ctrl+Shift+1 heading shortcut does not shadow global Ctrl+Shift+P command palette`() {
        // Regression guard for the keybinding-conflict check required by Story E.3.1: confirm
        // the new HEADING binding's key (One) is distinct from the global palette binding's key
        // (P), so both can be pressed independently without collision.
        var textState by mutableStateOf(TextFieldValue("note"))

        composeTestRule.setContent {
            BlockEditorHarness(textState = textState, onTextChanged = { textState = it })
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.ShiftLeft) {
                    keyDown(Key.P)
                    keyUp(Key.P)
                }
            }
        }
        composeTestRule.waitForIdle()

        assertEquals("note", textState.text, "Ctrl+Shift+P must not be consumed by any BlockEditor FormatAction binding")
    }

    // ------------------------------------------------------------------------------------
    // Shift+Enter multi-line block support
    // ------------------------------------------------------------------------------------

    @Test
    fun `Shift+Enter inserts a literal newline without splitting the block`() {
        var textState by mutableStateOf(TextFieldValue("hello world", selection = TextRange(5)))
        var splitCalled = false
        var newBlockCalled = false

        composeTestRule.setContent {
            BlockEditorHarness(
                textState = textState,
                onTextChanged = { textState = it },
                onSplitBlock = { _, _ -> splitCalled = true },
                onNewBlock = { newBlockCalled = true },
            )
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread {
            textState = TextFieldValue("hello world", selection = TextRange(5))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.ShiftLeft) {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
        }
        composeTestRule.waitForIdle()

        assertEquals("hello\n world", textState.text, "Shift+Enter should splice a literal newline at the cursor")
        assertFalse(splitCalled, "Shift+Enter must not split the current block")
        assertFalse(newBlockCalled, "Shift+Enter must not create a new block")
    }

    @Test
    fun `Plain Enter still splits the block (no regression from Shift+Enter change)`() {
        var textState by mutableStateOf(TextFieldValue("hello world", selection = TextRange(5)))
        var splitUuid: String? = null
        var splitOffset: Int? = null

        composeTestRule.setContent {
            BlockEditorHarness(
                textState = textState,
                onTextChanged = { textState = it },
                onSplitBlock = { uuid, offset -> splitUuid = uuid; splitOffset = offset },
            )
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread {
            textState = TextFieldValue("hello world", selection = TextRange(5))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        composeTestRule.waitForIdle()

        assertEquals("block-test", splitUuid, "Plain Enter should still split the block exactly as before")
        assertEquals(5, splitOffset)
        assertEquals("hello world", textState.text, "Plain Enter must not itself mutate the text content")
    }

    @Test
    fun `Shift+Enter does not corrupt active IME composition`() {
        var textState by mutableStateOf(
            TextFieldValue(text = "hello world", selection = TextRange(5), composition = TextRange(0, 5))
        )

        composeTestRule.setContent {
            BlockEditorHarness(textState = textState, onTextChanged = { textState = it })
        }

        composeTestRule.onNodeWithTag("editor").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread {
            textState = TextFieldValue(text = "hello world", selection = TextRange(5), composition = TextRange(0, 5))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor").performKeyInput {
            withKeyDown(Key.ShiftLeft) {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
        }
        composeTestRule.waitForIdle()

        assertEquals("hello world", textState.text, "Shift+Enter must not splice a newline while IME composition is active")
    }
}
