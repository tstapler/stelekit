package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockTypes
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.screens.FormatAction
import dev.stapler.stelekit.ui.screens.SearchResultItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Orchestrator composable that assembles a single block row from
 * [BlockGutter], [BlockEditor] / [BlockViewer], and the autocomplete menu.
 * This is the main composition unit for a single block.
 */
/** State for a pending link-suggestion confirmation popup or context menu. */
private data class SuggestionState(
    val canonicalName: String,
    val contentStart: Int,
    val contentEnd: Int,
    val capturedContent: String,
)

@Composable
internal fun BlockItem(
    block: Block,
    isDebugMode: Boolean = false,
    isEditing: Boolean,
    hasChildren: Boolean = false,
    isCollapsed: Boolean = false,
    textColor: Color = Color.Unspecified,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    isSelected: Boolean = false,
    isInSelectionMode: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onEnterSelectionMode: () -> Unit = {},
    isShiftDown: Boolean = false,
    onShiftClick: () -> Unit = {},
    onStartEditing: () -> Unit,
    onStopEditing: () -> Unit,
    onContentChange: (String, Long) -> Unit,
    onLinkClick: (String) -> Unit,
    onNewBlock: (String) -> Unit,
    onSplitBlock: (String, Int) -> Unit,
    onMergeBlock: (String) -> Unit = {},
    initialCursorPosition: Int? = null,
    onBackspace: () -> Unit = {},
    onLoadContent: () -> Unit = {},
    onToggleCollapse: () -> Unit = {},
    onIndent: () -> Unit = {},
    onOutdent: () -> Unit = {},
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onFocusUp: () -> Unit = {},
    onFocusDown: () -> Unit = {},
    onResolveContent: suspend (String) -> String? = { null },
    onSearchPages: (String) -> Flow<List<SearchResultItem>> = { emptyFlow() },
    formatEvents: SharedFlow<FormatAction>? = null,
    suggestionMatcher: AhoCorasickMatcher? = null,
    /** Called when the user requests to navigate all suggestions on screen (via context menu). */
    onNavigateAllSuggestions: (() -> Unit)? = null,
    onSelectionChange: ((IntRange?) -> Unit)? = null,
    onDragStart: (uuid: String, startY: Float) -> Unit = { _, _ -> },
    onDrag: (deltaY: Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    dropAbove: Boolean = false,
    dropBelow: Boolean = false,
    dropAsChild: Boolean = false,
    onArchiveUrl: ((url: String, blockUuid: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }

    var textFieldValue by remember(block.uuid) {
        mutableStateOf(TextFieldValue(text = block.content))
    }

    var localVersion by remember(block.uuid) { mutableLongStateOf(block.version) }

    // Autocomplete State
    var autocompleteState by remember { mutableStateOf<AutocompleteState?>(null) }
    var searchResults by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var selectedIndex by remember { mutableStateOf(0) }
    var filterText by remember { mutableStateOf("") }
    var isFilterActive by remember { mutableStateOf(false) }
    var isFilterFocused by remember { mutableStateOf(false) }

    // Reset filter when autocomplete closes
    LaunchedEffect(autocompleteState) {
        if (autocompleteState == null) {
            filterText = ""
            isFilterActive = false
        }
    }

    // Derived filtered results: CreatePageItem always stays at top, rest filtered by filterText
    val filteredResults by remember {
        derivedStateOf {
            if (filterText.isBlank()) {
                searchResults
            } else {
                val ft = filterText.lowercase()
                val createItem = searchResults.firstOrNull { it is SearchResultItem.CreatePageItem }
                val filtered = searchResults
                    .filterNot { it is SearchResultItem.CreatePageItem }
                    .filter { item ->
                        when (item) {
                            is SearchResultItem.PageItem -> item.page.name.lowercase().contains(ft)
                            is SearchResultItem.AliasItem -> item.alias.lowercase().contains(ft)
                            is SearchResultItem.BlockItem -> item.block.content.lowercase().contains(ft)
                            else -> true
                        }
                    }
                if (createItem != null) listOf(createItem) + filtered else filtered
            }
        }
    }

    // Fetch autocomplete results
    LaunchedEffect(autocompleteState?.query) {
        val query = autocompleteState?.query
        if (query != null) {
            onSearchPages(query).collect { results ->
                searchResults = results
                // Default to first real result (index 1) so CreatePageItem at top is reachable via Up
                selectedIndex = if (results.firstOrNull() is SearchResultItem.CreatePageItem) 1 else 0
                filterText = ""
                isFilterActive = false
            }
        } else {
            searchResults = emptyList()
        }
    }

    // Handle external updates to block content (e.g. undo/redo, sync)
    // Only update if the incoming version is newer than our local version
    LaunchedEffect(block.version) {
        if (block.version > localVersion) {
            val newSelection = if (textFieldValue.selection.max <= block.content.length) {
                textFieldValue.selection
            } else {
                TextRange(block.content.length)
            }
            textFieldValue = TextFieldValue(text = block.content, selection = newSelection)
            localVersion = block.version
        }
    }

    var hasFocused by remember { mutableStateOf(false) }

    // Page-suggestion popup state (null = no popup, shown on left-click)
    var suggestionState by remember { mutableStateOf<SuggestionState?>(null) }
    // Context menu state (null = closed, shown on right-click / long-press)
    var contextMenuState by remember { mutableStateOf<SuggestionState?>(null) }

    // Resolved block references content
    val resolvedRefs = remember { mutableStateMapOf<String, String>() }

    // Scan for block refs and fetch content
    LaunchedEffect(block.content) {
        MarkdownPatterns.blockRefPattern.findAll(block.content).forEach { match ->
            val refUuid = match.groupValues[1]
            if (!resolvedRefs.containsKey(refUuid)) {
                val content = onResolveContent(refUuid)
                if (content != null) {
                    resolvedRefs[refUuid] = content
                }
            }
        }
    }

    // Auto-dismiss suggestion popups when block content changes after they were captured
    LaunchedEffect(block.content) {
        if (suggestionState != null && suggestionState?.capturedContent != block.content) {
            suggestionState = null
        }
        if (contextMenuState != null && contextMenuState?.capturedContent != block.content) {
            contextMenuState = null
        }
    }

    // Request focus when entering edit mode
    LaunchedEffect(isEditing) {
        if (isEditing) {
            if (initialCursorPosition != null) {
                textFieldValue = textFieldValue.copy(selection = TextRange(initialCursorPosition))
            }
            focusRequester.requestFocus()
        } else {
            hasFocused = false
            autocompleteState = null // Clear autocomplete when exiting edit mode
        }
    }

    // Apply formatting events from the toolbar when this block is being edited
    LaunchedEffect(isEditing, formatEvents) {
        if (isEditing && formatEvents != null) {
            formatEvents.collect { action ->
                applyFormatAction(
                    action, textFieldValue,
                    onTextFieldValueChange = { textFieldValue = it; onSelectionChange?.invoke(IntRange(it.selection.min, it.selection.max)) },
                    onLocalVersionIncrement = { ++localVersion },
                    onContentChange = onContentChange
                )
            }
        }
    }

    // Trigger load if not loaded
    LaunchedEffect(block.isLoaded) {
        if (!block.isLoaded) {
            onLoadContent()
        }
    }

    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                else Modifier
            )
            .pointerInput(isInSelectionMode) {
                detectTapGestures(
                    onLongPress = {
                        if (!isInSelectionMode) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onEnterSelectionMode()
                        }
                    },
                    onTap = { if (isInSelectionMode) onToggleSelect() }
                )
            }
    ) {
        // Indentation guides
        repeat(block.level) { level ->
            Box(
                modifier = Modifier
                    .padding(start = (level * 24 + 30).dp) // Offset to align with bullet center roughly
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(StelekitTheme.colors.indentGuide)
            )
        }

        Column {
        val dividerColor = MaterialTheme.colorScheme.primary
        val indent = when {
            dropAsChild -> ((block.level + 1) * 24).dp
            dropAbove || dropBelow -> (block.level * 24).dp
            else -> 0.dp
        }
        if (dropAbove) {
            HorizontalDivider(
                color = dividerColor,
                thickness = 2.dp,
                modifier = Modifier.padding(start = indent)
            )
        }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = (block.level * 24).dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Gutter: drag handle, collapse toggle, bullet, debug info
            BlockGutter(
                level = block.level,
                isDebugMode = isDebugMode,
                hasChildren = hasChildren,
                isCollapsed = isCollapsed,
                onToggleCollapse = onToggleCollapse,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                isSelected = isSelected,
                isInSelectionMode = isInSelectionMode,
                onToggleSelect = onToggleSelect,
                blockUuid = block.uuid,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
            )

            if (!block.isLoaded) {
                Text(
                    text = "Loading...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else if (isEditing) {
                // Edit mode
                BlockEditor(
                    textFieldValue = textFieldValue,
                    onTextFieldValueChange = { textFieldValue = it; onSelectionChange?.invoke(IntRange(it.selection.min, it.selection.max)) },
                    focusRequester = focusRequester,
                    isEditing = isEditing,
                    hasFocused = hasFocused,
                    onHasFocusedChange = { hasFocused = it },
                    blockUuid = block.uuid,
                    autocompleteState = autocompleteState,
                    onAutocompleteStateChange = { autocompleteState = it },
                    searchResults = filteredResults,
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = { selectedIndex = it },
                    onEnterFilterMode = {
                        isFilterActive = true
                        // Reset to first real result index when entering filter
                        selectedIndex = if (filteredResults.firstOrNull() is SearchResultItem.CreatePageItem) 1 else 0
                    },
                    localVersion = localVersion,
                    onLocalVersionIncrement = { ++localVersion },
                    onContentChange = onContentChange,
                    onStopEditing = { if (!isFilterFocused) onStopEditing() },
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
                    modifier = Modifier.weight(1f),
                )
            } else {
                // View mode — dispatch on block type
                when (block.blockType) {
                    BlockTypes.HEADING -> HeadingBlock(
                        content = block.content,
                        level = headingLevelFromContent(block.content),
                        linkColor = linkColor,
                        onStartEditing = onStartEditing,
                        onLinkClick = onLinkClick,
                        modifier = Modifier.weight(1f),
                    )
                    BlockTypes.THEMATIC_BREAK -> ThematicBreakBlock(
                        onStartEditing = onStartEditing,
                        modifier = Modifier.weight(1f),
                    )
                    BlockTypes.CODE_FENCE -> CodeFenceBlock(
                        content = block.content,
                        language = codeFenceLanguage(block.content),
                        onStartEditing = onStartEditing,
                        modifier = Modifier.weight(1f),
                    )
                    BlockTypes.BLOCKQUOTE -> BlockquoteBlock(
                        content = block.content,
                        linkColor = linkColor,
                        onStartEditing = onStartEditing,
                        onLinkClick = onLinkClick,
                        modifier = Modifier.weight(1f),
                    )
                    BlockTypes.ORDERED_LIST_ITEM -> OrderedListItemBlock(
                        content = block.content,
                        number = orderedListNumber(block.content),
                        linkColor = linkColor,
                        onStartEditing = onStartEditing,
                        onLinkClick = onLinkClick,
                        modifier = Modifier.weight(1f),
                    )
                    BlockTypes.TABLE -> TableBlock(
                        content = block.content,
                        onStartEditing = onStartEditing,
                        modifier = Modifier.weight(1f),
                    )
                    else -> {
                        val imageData = remember(block.content) { extractSingleImageNode(block.content) }
                        if (imageData != null) {
                            val (url, altText) = imageData
                            ImageBlock(
                                url = url,
                                altText = altText,
                                onStartEditing = onStartEditing,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            BlockViewer( // BULLET, PARAGRAPH, RAW_HTML, unknown
                                content = block.content,
                                textColor = textColor,
                                linkColor = linkColor,
                                resolvedRefs = resolvedRefs,
                                onLinkClick = onLinkClick,
                                onStartEditing = onStartEditing,
                                modifier = Modifier.weight(1f),
                                isShiftDown = isShiftDown,
                                onShiftClick = onShiftClick,
                                suggestionMatcher = suggestionMatcher,
                                onSuggestionClick = { canonicalName, contentStart, contentEnd ->
                                    suggestionState = SuggestionState(canonicalName, contentStart, contentEnd, block.content)
                                },
                                onSuggestionRightClick = { canonicalName, contentStart, contentEnd ->
                                    contextMenuState = SuggestionState(canonicalName, contentStart, contentEnd, block.content)
                                },
                                onUrlRightClick = onArchiveUrl?.let { archive -> { url -> archive(url, block.uuid) } },
                            )
                        }
                    }
                }
            }
        }
        if (dropBelow || dropAsChild) {
            HorizontalDivider(
                color = dividerColor,
                thickness = 2.dp,
                modifier = Modifier.padding(start = indent)
            )
        }
        } // end Column

        // Render page-suggestion confirmation popup (left-click)
        val pending = suggestionState
        if (pending != null) {
            LinkSuggestionPopup(
                canonicalPageName = pending.canonicalName,
                onConfirm = {
                    if (pending.capturedContent != block.content) {
                        // Block was edited since the suggestion was captured — dismiss without linking
                        suggestionState = null
                    } else {
                        // Positional replacement — avoids replaceFirst ambiguity when the
                        // matched text appears more than once in the block content.
                        val content = block.content
                        val safeEnd = pending.contentEnd.coerceAtMost(content.length)
                        val safeStart = pending.contentStart.coerceIn(0, safeEnd)
                        val newContent = content.substring(0, safeStart) +
                            "[[${pending.canonicalName}]]" +
                            content.substring(safeEnd)
                        onContentChange(newContent, ++localVersion)
                        suggestionState = null
                    }
                },
                onDismiss = { suggestionState = null },
            )
        }

        // Render suggestion context menu (right-click / long-press)
        val ctxMenu = contextMenuState
        if (ctxMenu != null) {
            SuggestionContextMenu(
                canonicalName = ctxMenu.canonicalName,
                expanded = true,
                onDismiss = { contextMenuState = null },
                onLink = {
                    if (ctxMenu.capturedContent != block.content) {
                        // Block was edited since the suggestion was captured — dismiss without linking
                        contextMenuState = null
                    } else {
                        val content = block.content
                        val safeEnd = ctxMenu.contentEnd.coerceAtMost(content.length)
                        val safeStart = ctxMenu.contentStart.coerceIn(0, safeEnd)
                        val newContent = content.substring(0, safeStart) +
                            "[[${ctxMenu.canonicalName}]]" +
                            content.substring(safeEnd)
                        onContentChange(newContent, ++localVersion)
                        contextMenuState = null
                    }
                },
                onSkip = { contextMenuState = null },
                onNavigateAll = {
                    contextMenuState = null
                    onNavigateAllSuggestions?.invoke()
                },
            )
        }

        // Render Autocomplete Menu
        if (autocompleteState != null && searchResults.isNotEmpty()) {
            AutocompleteMenu(
                items = filteredResults,
                selectedIndex = selectedIndex,
                onItemSelected = { item ->
                    applyAutocompleteSelection(
                        searchResults = filteredResults,
                        selectedIndex = filteredResults.indexOf(item).coerceAtLeast(0),
                        textFieldValue = textFieldValue,
                        onTextFieldValueChange = { textFieldValue = it; onSelectionChange?.invoke(IntRange(it.selection.min, it.selection.max)) },
                        autocompleteState = autocompleteState!!,
                        onAutocompleteStateChange = { autocompleteState = it },
                        onLocalVersionIncrement = { ++localVersion },
                        onContentChange = onContentChange,
                    )
                },
                onDismiss = { autocompleteState = null },
                cursorRect = autocompleteState?.cursorRect,
                filterText = filterText,
                isFilterActive = isFilterActive,
                onFilterTextChange = { newFilter ->
                    filterText = newFilter
                    // Reset selection to first real result when filter changes
                    val startIdx = if (searchResults.firstOrNull() is SearchResultItem.CreatePageItem) 1 else 0
                    selectedIndex = startIdx
                },
                onFilterFocusChange = { focused -> isFilterFocused = focused },
                onDeactivateFilter = {
                    isFilterActive = false
                    filterText = ""
                    try { focusRequester.requestFocus() } catch (_: IllegalStateException) {}
                }
            )
        }
    }
}

/** Counts leading `#` characters in [content] to determine heading level (1–6). */
private fun headingLevelFromContent(content: String): Int {
    var count = 0
    for (c in content) {
        if (c == '#') count++ else break
    }
    return count.coerceIn(1, 6)
}

/** Extracts the language identifier from the first line of a fenced code block. */
private fun codeFenceLanguage(content: String): String {
    val firstLine = content.lines().firstOrNull() ?: return ""
    return firstLine.trimStart('`', '~').trim()
}

/** Parses the numeric prefix from an ordered list item (e.g. `"3. text"` → `3`). */
private fun orderedListNumber(content: String): Int {
    return content.trimStart().takeWhile { it.isDigit() }.toIntOrNull() ?: 1
}
