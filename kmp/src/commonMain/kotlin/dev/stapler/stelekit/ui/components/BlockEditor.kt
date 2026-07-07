package dev.stapler.stelekit.ui.components

import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.LocalOpenSearchWithText
import dev.stapler.stelekit.ui.screens.FormatAction
import dev.stapler.stelekit.ui.screens.SearchResultItem

/**
 * The editable text field for a block in edit mode, including keyboard
 * handlers and autocomplete trigger detection.
 */
private val WIKI_LINK_AUTOCOMPLETE_REGEX = Regex("\\[\\[([^\\]]*)$")
private val HASHTAG_AUTOCOMPLETE_REGEX = Regex("#([^\\s#\\[\\](),!?;.\"']*)$")

private val WORD_BOUNDARY_CHARS = setOf(' ', '\t', '\n', '.', ',', ';', ':', '!', '?', '(', ')', '[', ']', '"', '\'')

/**
 * Returns the start index of the word immediately before or at [position].
 * Stops at whitespace, punctuation, and [[/]] wiki-link delimiters.
 */
internal fun findWordStart(text: String, position: Int): Int {
    var i = position
    while (i > 0) {
        // Stop at [[ or ]] boundaries (avoid substring allocation on hot path)
        if (i >= 2 && text[i - 2] == '[' && text[i - 1] == '[') return i
        if (i >= 2 && text[i - 2] == ']' && text[i - 1] == ']') return i
        if (text[i - 1] in WORD_BOUNDARY_CHARS) return i
        i--
    }
    return 0
}

/**
 * Returns the end index of the word starting at or after [position].
 * Stops at whitespace, punctuation, and [[/]] wiki-link delimiters.
 */
internal fun findWordEnd(text: String, position: Int): Int {
    var i = position
    val len = text.length
    while (i < len) {
        // Stop at [[ or ]] boundaries (avoid substring allocation on hot path)
        if (i + 2 <= len && text[i] == '[' && text[i + 1] == '[') return i
        if (i + 2 <= len && text[i] == ']' && text[i + 1] == ']') return i
        if (text[i] in WORD_BOUNDARY_CHARS) return i
        i++
    }
    return len
}

/**
 * Detects whether the text immediately before the cursor matches the `[[` wiki-link or `#`
 * hashtag autocomplete trigger pattern. Returns `null` (no trigger) while IME composition is
 * active ([TextFieldValue.composition] non-null) — see the IME-composition-guard Pattern
 * Decision (Phase E.1, pitfalls.md §2-§3): partial/candidate CJK/Japanese/Korean composition
 * text must never be mistaken for a committed `[[`/`#` trigger.
 *
 * Exposed as `internal` so the guard is unit-testable without a full Compose harness.
 */
internal fun detectAutocompleteMatch(newValue: TextFieldValue): Pair<MatchResult, AutocompleteTrigger>? {
    if (newValue.composition != null) return null
    val cursor = newValue.selection.min
    val textBeforeCursor = newValue.text.take(cursor)
    val wikiMatch = WIKI_LINK_AUTOCOMPLETE_REGEX.find(textBeforeCursor)
    if (wikiMatch != null) return Pair(wikiMatch, AutocompleteTrigger.WIKI_LINK)
    val hashMatch = HASHTAG_AUTOCOMPLETE_REGEX.find(textBeforeCursor)
    if (hashMatch != null) return Pair(hashMatch, AutocompleteTrigger.HASHTAG)
    return null
}

@Composable
internal fun BlockEditor(
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    isEditing: Boolean,
    hasFocused: Boolean,
    onHasFocusedChange: (Boolean) -> Unit,
    blockUuid: String,
    autocompleteState: AutocompleteState?,
    onAutocompleteStateChange: (AutocompleteState?) -> Unit,
    searchResults: List<SearchResultItem>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onEnterFilterMode: () -> Unit = {},
    localVersion: Long,
    onLocalVersionIncrement: () -> Long,
    onContentChange: (String, Long) -> Unit,
    onStopEditing: () -> Unit,
    onNewBlock: (String) -> Unit,
    onSplitBlock: (String, Int) -> Unit,
    onMergeBlock: (String) -> Unit,
    onBackspace: () -> Unit,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onFocusUp: () -> Unit,
    onFocusDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val openSearchWithText = LocalOpenSearchWithText.current

    BasicTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            val oldText = textFieldValue.text
            val oldSelection = textFieldValue.selection

            // Soft-keyboard [[ ]] wrapping: on mobile, typing '[' replaces the selection via
            // onValueChange rather than firing Key.LeftBracket (handled separately in onKeyEvent
            // for hardware keyboards). Detect the replacement pattern and re-wrap the original
            // selection with [[ ]] before propagating.
            val wrappedValue = detectSoftKeyboardBracketWrap(oldText, oldSelection, newValue)
            val softKeyboardWrapped = wrappedValue != null
            if (wrappedValue != null) {
                onTextFieldValueChange(wrappedValue)
                val version = onLocalVersionIncrement()
                onContentChange(wrappedValue.text, version)
            }

            if (!softKeyboardWrapped) {
                onTextFieldValueChange(newValue)
                if (newValue.text != oldText) {
                    val newVersion = onLocalVersionIncrement()
                    onContentChange(newValue.text, newVersion)
                }
            }

            // Autocomplete trigger detection — guarded against active IME composition inside
            // detectAutocompleteMatch (Phase E.1 / pitfalls.md §2-§3): partial/candidate CJK
            // composition text must never falsely trigger [[ / # autocomplete.
            val cursor = newValue.selection.min
            val match = detectAutocompleteMatch(newValue)

            if (match != null) {
                val (activeMatch, trigger) = match
                val query = activeMatch.groupValues[1]
                val safeCursor = cursor.coerceIn(0, textLayoutResult?.layoutInput?.text?.length ?: 0)
                val rect = if (textLayoutResult != null && safeCursor <= textLayoutResult!!.layoutInput.text.length) {
                    try {
                        textLayoutResult?.getCursorRect(safeCursor)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                } else null

                if (rect != null) {
                    onAutocompleteStateChange(AutocompleteState(query, rect, trigger))
                } else {
                    onAutocompleteStateChange(null)
                }
            } else {
                onAutocompleteStateChange(null)
            }
        },
        onTextLayout = { textLayoutResult = it },
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onBackground
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                handleKeyEvent(
                    event = event,
                    textFieldValue = textFieldValue,
                    onTextFieldValueChange = onTextFieldValueChange,
                    textLayoutResult = textLayoutResult,
                    autocompleteState = autocompleteState,
                    onAutocompleteStateChange = onAutocompleteStateChange,
                    searchResults = searchResults,
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = onSelectedIndexChange,
                    onEnterFilterMode = onEnterFilterMode,
                    localVersion = localVersion,
                    onLocalVersionIncrement = onLocalVersionIncrement,
                    onContentChange = onContentChange,
                    blockUuid = blockUuid,
                    onNewBlock = onNewBlock,
                    onSplitBlock = onSplitBlock,
                    onMergeBlock = onMergeBlock,
                    onBackspace = onBackspace,
                    onIndent = onIndent,
                    onOutdent = onOutdent,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onFocusUp = onFocusUp,
                    onFocusDown = onFocusDown,
                    onOpenSearchWithText = openSearchWithText,
                )
            }
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    onHasFocusedChange(true)
                }
                if (!focusState.isFocused && isEditing && hasFocused) {
                    onStopEditing()
                }
            },
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp) // Match BlockViewer padding
            ) {
                innerTextField()
            }
        }
    )
}

/**
 * Handles all keyboard events for the block editor, including autocomplete
 * navigation and standard block operations (Enter, Backspace, Tab, arrows).
 */
private fun handleKeyEvent(
    event: KeyEvent,
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    textLayoutResult: TextLayoutResult?,
    autocompleteState: AutocompleteState?,
    onAutocompleteStateChange: (AutocompleteState?) -> Unit,
    searchResults: List<SearchResultItem>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onEnterFilterMode: () -> Unit = {},
    localVersion: Long,
    onLocalVersionIncrement: () -> Long,
    onContentChange: (String, Long) -> Unit,
    blockUuid: String,
    onNewBlock: (String) -> Unit,
    onSplitBlock: (String, Int) -> Unit,
    onMergeBlock: (String) -> Unit,
    onBackspace: () -> Unit,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onFocusUp: () -> Unit,
    onFocusDown: () -> Unit,
    onOpenSearchWithText: (String) -> Unit = {},
): Boolean {
    // Autocomplete keyboard navigation
    if (autocompleteState != null && searchResults.isNotEmpty()) {
        if (event.type == KeyEventType.KeyDown) {
            return when {
                event.key == Key.DirectionUp -> {
                    onSelectedIndexChange((selectedIndex - 1 + searchResults.size) % searchResults.size)
                    true
                }
                event.key == Key.DirectionDown -> {
                    onSelectedIndexChange((selectedIndex + 1) % searchResults.size)
                    true
                }
                event.key == Key.Enter && event.isCtrlPressed -> {
                    // Ctrl+Enter: create new page with the typed query
                    val createItem = searchResults.filterIsInstance<SearchResultItem.CreatePageItem>().firstOrNull()
                    if (createItem != null) {
                        val createIndex = searchResults.indexOf(createItem)
                        applyAutocompleteSelection(
                            searchResults = searchResults,
                            selectedIndex = createIndex,
                            textFieldValue = textFieldValue,
                            onTextFieldValueChange = onTextFieldValueChange,
                            autocompleteState = autocompleteState,
                            onAutocompleteStateChange = onAutocompleteStateChange,
                            onLocalVersionIncrement = onLocalVersionIncrement,
                            onContentChange = onContentChange,
                        )
                    }
                    true
                }
                event.key == Key.Enter -> {
                    applyAutocompleteSelection(
                        searchResults = searchResults,
                        selectedIndex = selectedIndex,
                        textFieldValue = textFieldValue,
                        onTextFieldValueChange = onTextFieldValueChange,
                        autocompleteState = autocompleteState,
                        onAutocompleteStateChange = onAutocompleteStateChange,
                        onLocalVersionIncrement = onLocalVersionIncrement,
                        onContentChange = onContentChange,
                    )
                    true
                }
                event.key == Key.Tab -> {
                    onEnterFilterMode()
                    true
                }
                event.key == Key.Escape -> {
                    onAutocompleteStateChange(null)
                    true
                }
                else -> false
            }
        }
        return false
    }

    // Formatting keyboard shortcuts (Ctrl+B, Ctrl+I, etc.)
    if (event.type == KeyEventType.KeyDown && (event.isCtrlPressed || event.isMetaPressed)) {
        // Cmd+K with selection: open global search pre-filled with the selected text.
        // Without a selection, fall through to the global Cmd+K handler.
        if (event.key == Key.K && !textFieldValue.selection.collapsed) {
            val selectedText = textFieldValue.text.substring(
                textFieldValue.selection.min, textFieldValue.selection.max
            )
            onOpenSearchWithText(selectedText)
            return true
        }
        // GAP-016 (Phase E.3): LINK/QUOTE/NUMBERED_LIST/HEADING had no hardware-keyboard
        // shortcut at all — keyboard-unreachable on Desktop/Web, toolbar-only on mobile.
        // Key choices checked against both this cascade's existing B/I/S/H/E bindings and
        // App.kt's onGraphKeyEvent global bindings (K, Comma, Z/Shift+Z/Y, Shift+B, Shift+P,
        // Shift+D, [, ]) to avoid shadowing global shortcuts while a block is focused.
        // NUMBERED_LIST mirrors Google Docs' Ctrl+Shift+7 convention; HEADING follows the same
        // Shift+digit shape (Ctrl+Shift+1) for internal consistency.
        // Story F.3.1: the key/shift → FormatAction mapping now lives in ShortcutTable so it
        // can never drift from the command palette's displayed shortcut badge — behavior is
        // unchanged (ShortcutTable.bindings preserves this cascade's original order/semantics).
        val formatAction = ShortcutTable.actionForKeyEvent(event.key, event.isShiftPressed)
        if (formatAction != null) {
            val result = applyFormatAction(
                formatAction, textFieldValue, onTextFieldValueChange,
                onLocalVersionIncrement, onContentChange
            )
            return result
        }
    }

    // Ctrl+Enter: toggle TODO state (Story C.1.2 / GAP-001), but only when no autocomplete
    // popup is open — the branch above (autocompleteState != null && searchResults.isNotEmpty())
    // already owns Ctrl+Enter's "create new page" meaning while a query is active, and must win.
    if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Enter) {
        if (autocompleteState == null) {
            val newValue = applyTodoToggle(textFieldValue)
            onTextFieldValueChange(newValue)
            val newVersion = onLocalVersionIncrement()
            onContentChange(newValue.text, newVersion)
            return true
        }
    }

    // Wrap selection with [[ ]] when [ is typed with a selection active
    if (event.type == KeyEventType.KeyDown && event.key == Key.LeftBracket) {
        val selection = textFieldValue.selection
        if (!selection.collapsed) {
            val text = textFieldValue.text
            val start = selection.min
            val end = selection.max
            val selected = text.substring(start, end)
            val newText = text.substring(0, start) + "[[" + selected + "]]" + text.substring(end)
            val newCursor = TextRange(start + 2, end + 2)
            onTextFieldValueChange(TextFieldValue(newText, newCursor))
            val newVersion = onLocalVersionIncrement()
            onContentChange(newText, newVersion)
            return true
        }
    }

    // Word navigation: Ctrl+Left / Ctrl+Right / Home / End
    if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
        when (event.key) {
            Key.DirectionLeft -> {
                val pos = textFieldValue.selection.start
                val newPos = findWordStart(textFieldValue.text, pos)
                val newSelection = if (event.isShiftPressed) {
                    TextRange(newPos, textFieldValue.selection.end)
                } else {
                    TextRange(newPos)
                }
                onTextFieldValueChange(textFieldValue.copy(selection = newSelection))
                return true
            }
            Key.DirectionRight -> {
                val pos = textFieldValue.selection.end
                val newPos = findWordEnd(textFieldValue.text, pos)
                val newSelection = if (event.isShiftPressed) {
                    TextRange(textFieldValue.selection.start, newPos)
                } else {
                    TextRange(newPos)
                }
                onTextFieldValueChange(textFieldValue.copy(selection = newSelection))
                return true
            }
            else -> Unit
        }
    }

    // Home / End navigation
    if (event.type == KeyEventType.KeyDown) {
        when (event.key) {
            Key.MoveHome -> {
                val newPos = 0
                val newSelection = if (event.isShiftPressed) {
                    TextRange(newPos, textFieldValue.selection.end)
                } else {
                    TextRange(newPos)
                }
                onTextFieldValueChange(textFieldValue.copy(selection = newSelection))
                return true
            }
            Key.MoveEnd -> {
                val newPos = textFieldValue.text.length
                val newSelection = if (event.isShiftPressed) {
                    TextRange(textFieldValue.selection.start, newPos)
                } else {
                    TextRange(newPos)
                }
                onTextFieldValueChange(textFieldValue.copy(selection = newSelection))
                return true
            }
            else -> Unit
        }
    }

    // Standard block keyboard shortcuts
    if (event.type == KeyEventType.KeyDown) {
        return when (event.key) {
            Key.Enter -> {
                if (event.isShiftPressed) {
                    false
                } else {
                    val selection = textFieldValue.selection
                    if (selection.collapsed && selection.start < textFieldValue.text.length) {
                        onSplitBlock(blockUuid, selection.start)
                    } else {
                        onNewBlock(blockUuid)
                    }
                    true
                }
            }
            Key.Backspace -> {
                if (textFieldValue.selection.collapsed && textFieldValue.selection.start == 0) {
                    if (textFieldValue.text.isEmpty()) {
                        onBackspace()
                    } else {
                        onMergeBlock(blockUuid)
                    }
                    true
                } else {
                    false
                }
            }
            Key.Tab -> {
                if (event.isShiftPressed) {
                    onOutdent()
                } else {
                    onIndent()
                }
                true
            }
            Key.DirectionUp -> {
                if (event.isAltPressed) {
                    onMoveUp()
                    true
                } else {
                    val selection = textFieldValue.selection
                    val layout = textLayoutResult
                    if (selection.collapsed && layout != null) {
                        val line = layout.getLineForOffset(selection.start)
                        if (line == 0) {
                            onFocusUp()
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
            }
            Key.DirectionDown -> {
                if (event.isAltPressed) {
                    onMoveDown()
                    true
                } else {
                    val selection = textFieldValue.selection
                    val layout = textLayoutResult
                    if (selection.collapsed && layout != null) {
                        val line = layout.getLineForOffset(selection.end)
                        if (line == layout.lineCount - 1) {
                            onFocusDown()
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
            }
            else -> false
        }
    }
    return false
}

/**
 * A block's todo-marker state, modeled as its own sum type rather than a [FormatAction] case —
 * see the "TODO-toggle state modeling" Pattern Decision in the rich-editing-experience plan.
 * Keeping this orthogonal to [FormatAction] means the three-state TODO→DONE cycle isn't
 * shoehorned into a binary (prefix, suffix) toggle, and applying an unrelated `FormatAction`
 * (HEADING/QUOTE/NUMBERED_LIST) can never silently strip a todo marker, or vice versa.
 */
internal enum class TodoState {
    NONE, TODO, DOING, DONE;

    /**
     * The next state in the cycle, per `EssentialCommands.toggleTodo`'s existing specified
     * (but previously discarded) behavior: NONE→TODO, TODO→DONE, DOING→DONE, DONE→TODO.
     */
    fun next(): TodoState = when (this) {
        NONE -> TODO
        TODO -> DONE
        DOING -> DONE
        DONE -> TODO
    }

    companion object {
        /** Reads the current todo state from a block's raw line content. */
        fun parse(content: String): TodoState = when {
            content.startsWith("TODO ") -> TODO
            content.startsWith("DOING ") -> DOING
            content.startsWith("DONE ") -> DONE
            else -> NONE
        }
    }
}

private fun TodoState.marker(): String = when (this) {
    TodoState.NONE -> ""
    TodoState.TODO -> "TODO "
    TodoState.DOING -> "DOING "
    TodoState.DONE -> "DONE "
}

/**
 * Toggles a block's TODO/DOING/DONE marker, cycling per [TodoState.next]. Strips only the
 * narrowly-scoped set `{"TODO ", "DOING ", "DONE "}` and prepends the next state's marker
 * (empty for [TodoState.NONE]) — this never reads or mutates [FormatAction]'s mutually-exclusive
 * line-prefix strip-group in [applyFormatAction], since todo state is an orthogonal axis, not a
 * member of that group.
 */
internal fun applyTodoToggle(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val currentMarker = TodoState.parse(text).marker()
    val stripped = text.removePrefix(currentMarker)
    val newMarker = TodoState.parse(text).next().marker()
    val newText = newMarker + stripped

    val delta = newMarker.length - currentMarker.length
    val newSelection = TextRange(
        (value.selection.start + delta).coerceIn(0, newText.length),
        (value.selection.end + delta).coerceIn(0, newText.length),
    )
    return TextFieldValue(newText, newSelection)
}

/**
 * Dispatches a toolbar/keyboard format [action] to the handler for its structural shape:
 * whole-block code-fence toggle, whole-block table-skeleton insert, line-prefix toggle
 * (with todo-marker preservation), or the generic selection wrap/toggle.
 */
internal fun applyFormatAction(
    action: FormatAction,
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onLocalVersionIncrement: () -> Long,
    onContentChange: (String, Long) -> Unit,
): Boolean {
    val text = textFieldValue.text
    val selection = textFieldValue.selection
    val prefix = action.prefix
    val suffix = action.suffix

    return when {
        action == FormatAction.CODE_BLOCK -> applyCodeBlockToggle(
            text, selection, onTextFieldValueChange, onLocalVersionIncrement, onContentChange,
        )
        action == FormatAction.TABLE_INSERT -> applyTableInsert(
            onTextFieldValueChange, onLocalVersionIncrement, onContentChange,
        )
        // Line-prefix actions (QUOTE, NUMBERED_LIST, HEADING) have an empty suffix.
        suffix.isEmpty() && prefix.isNotEmpty() -> applyLinePrefixToggle(
            text, selection, prefix, onTextFieldValueChange, onLocalVersionIncrement, onContentChange,
        )
        else -> applySelectionWrap(
            text, selection, prefix, suffix, onTextFieldValueChange, onLocalVersionIncrement, onContentChange,
        )
    }
}

/**
 * Commits a format-action result: pushes the new [TextFieldValue] into the editor,
 * bumps the block's local edit version, and notifies the content-change callback.
 * Shared trailer for every `applyFormatAction` branch — always returns `true`.
 */
private fun commit(
    newText: String,
    newCursor: TextRange,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onLocalVersionIncrement: () -> Long,
    onContentChange: (String, Long) -> Unit,
): Boolean {
    onTextFieldValueChange(TextFieldValue(newText, newCursor))
    val newVersion = onLocalVersionIncrement()
    onContentChange(newText, newVersion)
    return true
}

/**
 * CODE_BLOCK (GAP-006): wraps the entire block content in a fenced code block, cursor
 * placed just inside the opening fence. Structurally separate from the generic wrap/
 * line-prefix logic since it always wraps the whole block, not just a selection.
 */
private fun applyCodeBlockToggle(
    text: String,
    selection: TextRange,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onLocalVersionIncrement: () -> Long,
    onContentChange: (String, Long) -> Unit,
): Boolean {
    val fenceOpen = "```\n"
    val fenceClose = "\n```"
    val newText: String
    val newCursor: TextRange
    if (text.startsWith(fenceOpen) && text.endsWith(fenceClose)) {
        // Already fenced — toggle off
        newText = text.removePrefix(fenceOpen).removeSuffix(fenceClose)
        newCursor = TextRange(maxOf(0, selection.start - fenceOpen.length))
    } else {
        newText = fenceOpen + text + fenceClose
        newCursor = TextRange(fenceOpen.length + selection.start)
    }
    return commit(newText, newCursor, onTextFieldValueChange, onLocalVersionIncrement, onContentChange)
}

/**
 * TABLE_INSERT (GAP-007): inserts a 2x2 markdown table skeleton, cursor in the first cell,
 * per design/ux.md surface (a) interaction-flow item 4.
 */
private fun applyTableInsert(
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onLocalVersionIncrement: () -> Long,
    onContentChange: (String, Long) -> Unit,
): Boolean {
    val newText = "| | |\n| --- | --- |\n| | |"
    val newCursor = TextRange(2)
    return commit(newText, newCursor, onTextFieldValueChange, onLocalVersionIncrement, onContentChange)
}

/**
 * Line-prefix actions (QUOTE, NUMBERED_LIST, HEADING) have an empty suffix.
 * They toggle the prefix at the start of the entire block content, not at the cursor.
 *
 * Todo markers (if present) are an orthogonal axis to line-prefix formatting — see
 * applyTodoToggle. Split off any todo marker first so this toggle operates only on the
 * remainder, then re-prepend it, keeping "TODO "/"DOING "/"DONE " outermost/leftmost
 * rather than letting a heading (or other line-prefix) get inserted ahead of it.
 */
private fun applyLinePrefixToggle(
    text: String,
    selection: TextRange,
    prefix: String,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onLocalVersionIncrement: () -> Long,
    onContentChange: (String, Long) -> Unit,
): Boolean {
    val todoMarker = TodoState.parse(text).marker()
    val rest = text.removePrefix(todoMarker)
    val restSelectionStart = (selection.start - todoMarker.length).coerceAtLeast(0)

    val newRest: String
    val newRestCursor: Int
    if (rest.startsWith(prefix)) {
        // Already has this prefix — remove it (toggle off)
        newRest = rest.removePrefix(prefix)
        newRestCursor = maxOf(0, restSelectionStart - prefix.length)
    } else {
        // Strip any conflicting line-prefix before applying the new one
        val stripped = FormatAction.entries
            .filter { it.suffix.isEmpty() && it.prefix.isNotEmpty() && rest.startsWith(it.prefix) }
            .fold(rest) { acc, other -> acc.removePrefix(other.prefix) }
        newRest = prefix + stripped
        newRestCursor = restSelectionStart + prefix.length
    }
    val newText = todoMarker + newRest
    val newCursor = TextRange((todoMarker.length + newRestCursor).coerceIn(0, newText.length))
    return commit(newText, newCursor, onTextFieldValueChange, onLocalVersionIncrement, onContentChange)
}

/**
 * Wraps the selected text with formatting markers, or inserts markers at cursor.
 * If text is selected, wraps it: `**selected**`. If no selection, inserts markers
 * and places cursor between them: `**|**`.
 */
private fun applySelectionWrap(
    text: String,
    selection: TextRange,
    prefix: String,
    suffix: String,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onLocalVersionIncrement: () -> Long,
    onContentChange: (String, Long) -> Unit,
): Boolean {
    val newText: String
    val newCursor: TextRange

    if (selection.collapsed) {
        // No selection — insert markers and place cursor between them
        val pos = selection.start
        newText = text.substring(0, pos) + prefix + suffix + text.substring(pos)
        newCursor = TextRange(pos + prefix.length)
    } else {
        // Wrap selection
        val start = selection.min
        val end = selection.max
        val selected = text.substring(start, end)

        // If already wrapped with the same markers, unwrap instead (toggle)
        val alreadyWrapped = start >= prefix.length &&
            end + suffix.length <= text.length &&
            text.substring(start - prefix.length, start) == prefix &&
            text.substring(end, end + suffix.length) == suffix
        if (alreadyWrapped) {
            newText = text.substring(0, start - prefix.length) + selected + text.substring(end + suffix.length)
            newCursor = TextRange(start - prefix.length, end - prefix.length)
        } else {
            newText = text.substring(0, start) + prefix + selected + suffix + text.substring(end)
            newCursor = TextRange(start + prefix.length, end + prefix.length)
        }
    }

    return commit(newText, newCursor, onTextFieldValueChange, onLocalVersionIncrement, onContentChange)
}

/**
 * Applies the currently selected autocomplete item by replacing the
 * `[[query` text with `[[Page Name]]`.
 */
internal fun applyAutocompleteSelection(
    searchResults: List<SearchResultItem>,
    selectedIndex: Int,
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    autocompleteState: AutocompleteState,
    onAutocompleteStateChange: (AutocompleteState?) -> Unit,
    onLocalVersionIncrement: () -> Long,
    onContentChange: (String, Long) -> Unit,
) {
    val item = searchResults.getOrNull(selectedIndex) ?: return
    val pageName = when (item) {
        is SearchResultItem.PageItem -> item.page.name
        is SearchResultItem.AliasItem -> item.alias
        is SearchResultItem.CreatePageItem -> item.query
        else -> null
    }

    if (pageName != null) {
        val text = textFieldValue.text
        val cursor = textFieldValue.selection.min
        val query = autocompleteState.query

        if (autocompleteState.trigger == AutocompleteTrigger.HASHTAG) {
            // # trigger: replace from the # character up to cursor
            val triggerLength = 1 // #
            val startIndex = cursor - query.length - triggerLength
            if (startIndex >= 0) {
                val before = text.substring(0, startIndex)
                val after = text.substring(cursor)
                val replacement = if (pageName.contains(' ')) "#[[$pageName]]" else "#$pageName"
                val newText = before + replacement + after
                val newCursor = startIndex + replacement.length

                onTextFieldValueChange(TextFieldValue(newText, TextRange(newCursor)))
                val newVersion = onLocalVersionIncrement()
                onContentChange(newText, newVersion)
                onAutocompleteStateChange(null)
            }
        } else {
            val triggerLength = 2 // [[
            val startIndex = cursor - query.length - triggerLength
            if (startIndex >= 0) {
                val before = text.substring(0, startIndex)
                val rawAfter = text.substring(cursor)
                val after = if (rawAfter.startsWith("]]")) rawAfter.substring(2) else rawAfter
                val replacement = "[[$pageName]]"
                val newText = before + replacement + after
                val newCursor = startIndex + replacement.length

                onTextFieldValueChange(TextFieldValue(newText, TextRange(newCursor)))
                val newVersion = onLocalVersionIncrement()
                onContentChange(newText, newVersion)
                onAutocompleteStateChange(null)
            }
        }
    }
}

/**
 * Detects the soft-keyboard `[[` wrapping pattern: when the user types `[` while text is
 * selected, the soft keyboard delivers it as an `onValueChange` where the selection is replaced
 * by `[`. This function recognises that pattern and returns the [TextFieldValue] with the
 * selection wrapped in `[[` and `]]`, or `null` if the input doesn't match the pattern.
 *
 * The equivalent hardware-keyboard path is handled via `Key.LeftBracket` in [handleKeyEvent].
 *
 * Exposed as `internal` so it can be unit-tested without a full Compose harness.
 */
internal fun detectSoftKeyboardBracketWrap(
    oldText: String,
    oldSelection: TextRange,
    newValue: TextFieldValue,
): TextFieldValue? {
    // IME composition guard (Phase E.1 / pitfalls.md §2-§3): partial/candidate IME composition
    // text must never be mistaken for a committed `[` keystroke that should trigger [[ ]]
    // wrapping — CJK/Japanese/Korean candidate selection can transiently produce text that
    // matches this heuristic's diff pattern.
    if (newValue.composition != null) return null
    if (oldSelection.collapsed) return null
    val selStart = oldSelection.min
    val selEnd = oldSelection.max
    val expectedAfterBracket = oldText.substring(0, selStart) + "[" + oldText.substring(selEnd)
    if (newValue.text != expectedAfterBracket || newValue.selection != TextRange(selStart + 1)) return null
    val selected = oldText.substring(selStart, selEnd)
    val wrapped = oldText.substring(0, selStart) + "[[" + selected + "]]" + oldText.substring(selEnd)
    return TextFieldValue(wrapped, TextRange(selStart + 2, selEnd + 2))
}
