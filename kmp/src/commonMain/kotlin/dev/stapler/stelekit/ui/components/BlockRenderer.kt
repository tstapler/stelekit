package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.ui.screens.FormatAction
import dev.stapler.stelekit.ui.screens.SearchResultItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Which trigger opened the autocomplete popup.
 */
enum class AutocompleteTrigger { WIKI_LINK, HASHTAG }

/**
 * State holder for the autocomplete popup, tracking the current query,
 * the cursor rectangle for positioning, and which trigger opened it.
 */
data class AutocompleteState(
    val query: String,
    val cursorRect: Rect,
    val trigger: AutocompleteTrigger = AutocompleteTrigger.WIKI_LINK
)

/**
 * Public entry point for rendering a single block.
 *
 * Delegates to [BlockItem] which orchestrates [BlockGutter],
 * [BlockEditor] / [BlockViewer], and the autocomplete menu.
 *
 * This function preserves the original public API so that all existing
 * callers (e.g. [BlockList]) continue to work without changes.
 */
@Composable
fun BlockRenderer(
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
    onNavigateAllSuggestions: (() -> Unit)? = null,
    onDragStart: (uuid: String, startY: Float) -> Unit = { _, _ -> },
    onDrag: (deltaY: Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    dropAbove: Boolean = false,
    dropBelow: Boolean = false,
    dropAsChild: Boolean = false,
    onSelectionChanged: ((IntRange?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BlockItem(
        block = block,
        isDebugMode = isDebugMode,
        isEditing = isEditing,
        hasChildren = hasChildren,
        isCollapsed = isCollapsed,
        textColor = textColor,
        linkColor = linkColor,
        isSelected = isSelected,
        isInSelectionMode = isInSelectionMode,
        onToggleSelect = onToggleSelect,
        onEnterSelectionMode = onEnterSelectionMode,
        isShiftDown = isShiftDown,
        onShiftClick = onShiftClick,
        onStartEditing = onStartEditing,
        onStopEditing = onStopEditing,
        onContentChange = onContentChange,
        onLinkClick = onLinkClick,
        onNewBlock = onNewBlock,
        onSplitBlock = onSplitBlock,
        onMergeBlock = onMergeBlock,
        initialCursorPosition = initialCursorPosition,
        onBackspace = onBackspace,
        onLoadContent = onLoadContent,
        onToggleCollapse = onToggleCollapse,
        onIndent = onIndent,
        onOutdent = onOutdent,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        onFocusUp = onFocusUp,
        onFocusDown = onFocusDown,
        onResolveContent = onResolveContent,
        onSearchPages = onSearchPages,
        formatEvents = formatEvents,
        suggestionMatcher = suggestionMatcher,
        onNavigateAllSuggestions = onNavigateAllSuggestions,
        onDragStart = onDragStart,
        onDrag = onDrag,
        onDragEnd = onDragEnd,
        dropAbove = dropAbove,
        dropBelow = dropBelow,
        dropAsChild = dropAsChild,
        onSelectionChanged = onSelectionChanged,
        modifier = modifier
    )
}
