package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.outliner.BlockSorter
import dev.stapler.stelekit.ui.screens.FormatAction
import dev.stapler.stelekit.ui.screens.SearchResultItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Shared block-content area used by both PageView and JournalEntry.
 *
 * Renders one of three states:
 * - Loading spinner (blocks empty + isLoading)
 * - "Click to write..." empty state (blocks empty + not loading)
 * - BlockList + tap-below area (blocks non-empty)
 *
 * Headers, toolbars, overlays, and chrome stay in the caller.
 */
@Composable
fun PageContent(
    blocks: List<Block>,
    isLoading: Boolean,
    isDebugMode: Boolean = false,
    editingBlockUuid: String?,
    editingCursorIndex: Int?,
    collapsedBlocks: Set<String> = emptySet(),
    selectedBlockUuids: Set<String> = emptySet(),
    isInSelectionMode: Boolean = false,
    cutBlockUuids: Set<String> = emptySet(),
    suggestionMatcher: AhoCorasickMatcher? = null,
    localPageNames: Set<String> = emptySet(),
    hasSectionFilter: Boolean = false,
    formatEvents: SharedFlow<FormatAction>? = null,
    onAddBlockToPage: () -> Unit,
    onStartEditing: (String) -> Unit,
    onStopEditing: (String) -> Unit,
    onContentChange: (String, String, Long) -> Unit,
    onLinkClick: (String) -> Unit,
    onNewBlock: (String) -> Unit,
    onSplitBlock: (String, Int) -> Unit,
    onMergeBlock: (String) -> Unit = {},
    onBackspace: (String) -> Unit = {},
    onLoadContent: (String) -> Unit = {},
    onToggleCollapse: (String) -> Unit = {},
    onIndent: (String) -> Unit = {},
    onOutdent: (String) -> Unit = {},
    onMoveUp: (String) -> Unit = {},
    onMoveDown: (String) -> Unit = {},
    onFocusUp: (String) -> Unit = {},
    onFocusDown: (String) -> Unit = {},
    onToggleSelect: (String) -> Unit = {},
    onEnterSelectionMode: (String) -> Unit = {},
    onShiftClick: (String) -> Unit = {},
    onShiftArrowUp: () -> Unit = {},
    onShiftArrowDown: () -> Unit = {},
    onMoveSelectedBlocks: (newParentUuid: String?, insertAfterUuid: String?) -> Unit = { _, _ -> },
    onAutoSelectForDrag: (String) -> Unit = {},
    onBlockSelectionChange: ((blockUuid: String, range: IntRange?) -> Unit)? = null,
    onResolveContent: suspend (String) -> String? = { null },
    onSearchPages: (String) -> Flow<List<SearchResultItem>> = { emptyFlow() },
    onNavigateAllSuggestions: ((List<SuggestionItem>) -> Unit)? = null,
    onOpenAnnotationEditor: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (blocks.isEmpty()) {
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAddBlockToPage() }
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = "Click to write...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                )
            }
        }
    } else {
        val sortedBlocks = remember(blocks) { BlockSorter.sort(blocks) }

        BlockList(
            blocks = sortedBlocks,
            isDebugMode = isDebugMode,
            editingBlockUuid = editingBlockUuid,
            editingCursorIndex = editingCursorIndex,
            collapsedBlocks = collapsedBlocks,
            selectedBlockUuids = selectedBlockUuids,
            isInSelectionMode = isInSelectionMode,
            onToggleSelect = onToggleSelect,
            onEnterSelectionMode = onEnterSelectionMode,
            onShiftClick = onShiftClick,
            onShiftArrowUp = onShiftArrowUp,
            onShiftArrowDown = onShiftArrowDown,
            onStartEditing = onStartEditing,
            onStopEditing = onStopEditing,
            onContentChange = onContentChange,
            onLinkClick = onLinkClick,
            onNewBlock = onNewBlock,
            onSplitBlock = onSplitBlock,
            onMergeBlock = onMergeBlock,
            onIndent = onIndent,
            onOutdent = onOutdent,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onLoadContent = onLoadContent,
            onBackspace = onBackspace,
            onToggleCollapse = onToggleCollapse,
            onFocusUp = onFocusUp,
            onFocusDown = onFocusDown,
            onResolveContent = onResolveContent,
            onSearchPages = onSearchPages,
            formatEvents = formatEvents,
            suggestionMatcher = suggestionMatcher,
            onNavigateAllSuggestions = onNavigateAllSuggestions,
            onMoveSelectedBlocks = onMoveSelectedBlocks,
            onAutoSelectForDrag = onAutoSelectForDrag,
            onBlockSelectionChange = onBlockSelectionChange,
            onOpenAnnotationEditor = onOpenAnnotationEditor,
            hasSectionFilter = hasSectionFilter,
            localPageNames = localPageNames,
            cutBlockUuids = cutBlockUuids,
            modifier = modifier,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable { onAddBlockToPage() }
        )
    }
}
