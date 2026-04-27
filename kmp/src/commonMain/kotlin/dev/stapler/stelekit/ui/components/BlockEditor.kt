package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
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
import dev.stapler.stelekit.ui.screens.FormatAction
import dev.stapler.stelekit.ui.screens.SearchResultItem

/**
 * The editable text field for a block in edit mode, including keyboard
 * handlers and autocomplete trigger detection.
 */
private val WIKI_LINK_AUTOCOMPLETE_REGEX = Regex("\\[\\[([^\\]]*)$")
private val HASHTAG_AUTOCOMPLETE_REGEX = Regex("#([^\\s#\\[\\](),!?;.\"']*)$")

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

    BasicTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            val oldText = textFieldValue.text
            onTextFieldValueChange(newValue)
            if (newValue.text != oldText) {
                val newVersion = onLocalVersionIncrement()
                onContentChange(newValue.text, newVersion)
            }

            // Autocomplete trigger detection
            val cursor = newValue.selection.min
            val textBeforeCursor = newValue.text.take(cursor)
            val wikiMatch = WIKI_LINK_AUTOCOMPLETE_REGEX.find(textBeforeCursor)
            val hashMatch = if (wikiMatch == null) HASHTAG_AUTOCOMPLETE_REGEX.find(textBeforeCursor) else null

            val (activeMatch, trigger) = when {
                wikiMatch != null -> Pair(wikiMatch, AutocompleteTrigger.WIKI_LINK)
                hashMatch != null -> Pair(hashMatch, AutocompleteTrigger.HASHTAG)
                else -> Pair(null, AutocompleteTrigger.WIKI_LINK)
            }

            if (activeMatch != null) {
                val query = activeMatch.groupValues[1]
                val safeCursor = cursor.coerceIn(0, textLayoutResult?.layoutInput?.text?.length ?: 0)
                val rect = if (textLayoutResult != null && safeCursor <= textLayoutResult!!.layoutInput.text.length) {
                    try {
                        textLayoutResult?.getCursorRect(safeCursor)
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
        val formatAction = when (event.key) {
            Key.B -> FormatAction.BOLD
            Key.I -> FormatAction.ITALIC
            // Only consume Cmd+K for link wrapping when text is selected; without a selection
            // the event falls through to the global Cmd+K handler (open search).
            Key.K -> if (!textFieldValue.selection.collapsed) FormatAction.LINK else null
            Key.S -> FormatAction.STRIKETHROUGH
            Key.H -> FormatAction.HIGHLIGHT
            Key.E -> FormatAction.CODE
            else -> null
        }
        if (formatAction != null) {
            val result = applyFormatAction(
                formatAction, textFieldValue, onTextFieldValueChange,
                onLocalVersionIncrement, onContentChange
            )
            return result
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
 * Wraps the selected text with formatting markers, or inserts markers at cursor.
 * If text is selected, wraps it: `**selected**`. If no selection, inserts markers
 * and places cursor between them: `**|**`.
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

    // Line-prefix actions (QUOTE, NUMBERED_LIST, HEADING) have an empty suffix.
    // They toggle the prefix at the start of the entire block content, not at the cursor.
    if (suffix.isEmpty() && prefix.isNotEmpty()) {
        val newText: String
        val newCursor: TextRange
        if (text.startsWith(prefix)) {
            // Already has this prefix — remove it (toggle off)
            newText = text.removePrefix(prefix)
            newCursor = TextRange(maxOf(0, selection.start - prefix.length))
        } else {
            // Strip any conflicting line-prefix before applying the new one
            val stripped = FormatAction.entries
                .filter { it.suffix.isEmpty() && it.prefix.isNotEmpty() && text.startsWith(it.prefix) }
                .fold(text) { acc, other -> acc.removePrefix(other.prefix) }
            newText = prefix + stripped
            newCursor = TextRange(selection.start + prefix.length)
        }
        onTextFieldValueChange(TextFieldValue(newText, newCursor))
        val newVersion = onLocalVersionIncrement()
        onContentChange(newText, newVersion)
        return true
    }

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

    onTextFieldValueChange(TextFieldValue(newText, newCursor))
    val newVersion = onLocalVersionIncrement()
    onContentChange(newText, newVersion)
    return true
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
