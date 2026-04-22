package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.ui.screens.FormatAction
import dev.stapler.stelekit.ui.screens.SearchResultItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow

private enum class DropZone { ABOVE, CHILD, BELOW }

private data class BlockDragState(
    val draggedUuids: Set<String>,
    val pointerOffsetY: Float,
    val isDragging: Boolean
)

/**
 * Renders a list of blocks with proper hierarchy, supporting collapse/expand.
 * Updated to use UUID-native storage.
 */
@Composable
fun BlockList(
    blocks: List<Block>,
    isDebugMode: Boolean = false,
    editingBlockUuid: String?,
    collapsedBlocks: Set<String> = emptySet(),
    onStartEditing: (String) -> Unit,
    onStopEditing: (blockUuid: String) -> Unit,
    onContentChange: (String, String, Long) -> Unit,
    onLinkClick: (String) -> Unit,
    onNewBlock: (String) -> Unit,
    onSplitBlock: (String, Int) -> Unit,
    onMergeBlock: (String) -> Unit = {},
    editingCursorIndex: Int? = null,
    onBackspace: (String) -> Unit = {},
    onLoadContent: (String) -> Unit = {},
    onToggleCollapse: (String) -> Unit = {},
    onIndent: (String) -> Unit = {},
    onOutdent: (String) -> Unit = {},
    onMoveUp: (String) -> Unit = {},
    onMoveDown: (String) -> Unit = {},
    onFocusUp: (String) -> Unit = {},
    onFocusDown: (String) -> Unit = {},
    onResolveContent: suspend (String) -> String? = { null },
    onSearchPages: (String) -> Flow<List<SearchResultItem>> = { emptyFlow() },
    formatEvents: SharedFlow<FormatAction>? = null,
    selectedBlockUuids: Set<String> = emptySet(),
    isInSelectionMode: Boolean = false,
    onToggleSelect: (String) -> Unit = {},
    onEnterSelectionMode: (String) -> Unit = {},
    onShiftClick: (String) -> Unit = {},
    onShiftArrowUp: () -> Unit = {},
    onShiftArrowDown: () -> Unit = {},
    suggestionMatcher: AhoCorasickMatcher? = null,
    /**
     * Called when the user requests to navigate all visible suggestions.
     * [BlockList] collects suggestions from all visible (non-hidden) blocks and
     * passes them to this callback so the parent screen can open the navigator panel.
     */
    onNavigateAllSuggestions: ((List<SuggestionItem>) -> Unit)? = null,
    onMoveSelectedBlocks: (newParentUuid: String?, insertAfterUuid: String?) -> Unit = { _, _ -> },
    onAutoSelectForDrag: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (isDebugMode) {
        val recomposeCount = remember { androidx.compose.runtime.mutableIntStateOf(0) }
        androidx.compose.runtime.SideEffect { println("[Recompose] BlockList #${++recomposeCount.intValue}") }
    }

    // Build a map of parent UUID to children for quick lookup
    val childrenByParent = remember(blocks) {
        blocks.groupBy { it.parentUuid }
    }

    // Get UUIDs of blocks that have children
    val blocksWithChildren = remember(blocks) {
        blocks.mapNotNull { it.parentUuid }.toSet()
    }

    // Get all descendant UUIDs of a block (for hiding when collapsed)
    fun getDescendantUuids(blockUuid: String): Set<String> {
        val descendants = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(blockUuid)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            childrenByParent[current]?.forEach { child ->
                descendants.add(child.uuid)
                queue.add(child.uuid)
            }
        }
        return descendants
    }

    // Calculate which blocks should be hidden due to collapsed ancestors
    val hiddenBlocks = remember(blocks, collapsedBlocks) {
        collapsedBlocks.flatMap { getDescendantUuids(it) }.toSet()
    }

    var isShiftDown by remember { mutableStateOf(false) }
    var dragState by remember { mutableStateOf<BlockDragState?>(null) }
    var blockBounds by remember { mutableStateOf<Map<String, Pair<Float, Float>>>(emptyMap()) }
    var dropTargetUuid by remember { mutableStateOf<String?>(null) }
    var currentDropZone by remember { mutableStateOf<DropZone?>(null) }

    // Clean up drag state when this composable leaves the composition (e.g. page navigation)
    DisposableEffect(Unit) {
        onDispose {
            dragState = null
            dropTargetUuid = null
            currentDropZone = null
        }
    }

    fun computeDropTarget(state: BlockDragState, _allBlocksList: List<Block>) {
        val currentY = state.pointerOffsetY
        val dropTarget = blockBounds.entries
            .filter { (uuid, _) -> uuid !in state.draggedUuids }
            .minByOrNull { (_, bounds) -> kotlin.math.abs((bounds.first + bounds.second) / 2f - currentY) }
        if (dropTarget != null) {
            val (targetUuid, bounds) = dropTarget
            dropTargetUuid = targetUuid
            currentDropZone = when {
                currentY < bounds.first + (bounds.second - bounds.first) * 0.33f -> DropZone.ABOVE
                currentY > bounds.first + (bounds.second - bounds.first) * 0.67f -> DropZone.BELOW
                else -> DropZone.CHILD
            }
        } else {
            dropTargetUuid = null
            currentDropZone = null
        }
    }

    Box(
        modifier = modifier
            .onKeyEvent { event ->
                when {
                    event.key == Key.ShiftLeft || event.key == Key.ShiftRight -> {
                        isShiftDown = event.type == KeyEventType.KeyDown
                        false
                    }
                    event.type == KeyEventType.KeyDown && event.isShiftPressed && event.key == Key.DirectionUp -> {
                        onShiftArrowUp()
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.isShiftPressed && event.key == Key.DirectionDown -> {
                        onShiftArrowDown()
                        true
                    }
                    event.key == Key.Escape && dragState != null -> {
                        dragState = null
                        dropTargetUuid = null
                        currentDropZone = null
                        true
                    }
                    else -> false
                }
            }
    ) {
        Column {
        blocks.forEach { block ->
            // Only show if not hidden by a collapsed ancestor
            if (block.uuid !in hiddenBlocks) {
                val hasChildren = block.uuid in blocksWithChildren
                val isCollapsed = block.uuid in collapsedBlocks

                val isDropTarget = dragState != null && dropTargetUuid == block.uuid
                BlockRenderer(
                    block = block,
                    isDebugMode = isDebugMode,
                    isEditing = editingBlockUuid == block.uuid,
                    hasChildren = hasChildren,
                    isCollapsed = isCollapsed,
                    isSelected = block.uuid in selectedBlockUuids,
                    isInSelectionMode = isInSelectionMode,
                    onToggleSelect = { onToggleSelect(block.uuid) },
                    onEnterSelectionMode = { onEnterSelectionMode(block.uuid) },
                    isShiftDown = isShiftDown,
                    onShiftClick = { onShiftClick(block.uuid) },
                    onStartEditing = {
                        if (isInSelectionMode) onToggleSelect(block.uuid) else onStartEditing(block.uuid)
                    },
                    onStopEditing = { onStopEditing(block.uuid) },
                    onContentChange = { newContent, version -> onContentChange(block.uuid, newContent, version) },
                    onLinkClick = onLinkClick,
                    onNewBlock = onNewBlock,
                    onSplitBlock = onSplitBlock,
                    onMergeBlock = onMergeBlock,
                    initialCursorPosition = if (editingBlockUuid == block.uuid) editingCursorIndex else null,
                    onBackspace = { onBackspace(block.uuid) },
                    onLoadContent = { onLoadContent(block.pageUuid) },
                    onToggleCollapse = { onToggleCollapse(block.uuid) },
                    onIndent = { onIndent(block.uuid) },
                    onOutdent = { onOutdent(block.uuid) },
                    onMoveUp = { onMoveUp(block.uuid) },
                    onMoveDown = { onMoveDown(block.uuid) },
                    onFocusUp = { onFocusUp(block.uuid) },
                    onFocusDown = { onFocusDown(block.uuid) },
                    onResolveContent = onResolveContent,
                    onSearchPages = onSearchPages,
                    formatEvents = formatEvents,
                    suggestionMatcher = suggestionMatcher,
                    onNavigateAllSuggestions = if (onNavigateAllSuggestions != null) {
                        {
                            // Collect suggestions from all visible blocks
                            val allSuggestions = blocks
                                .filter { it.uuid !in hiddenBlocks }
                                .flatMap { b ->
                                    extractSuggestions(b.content, suggestionMatcher)
                                        .map { span ->
                                            SuggestionItem(b.uuid, span.canonicalName, span.start, span.end)
                                        }
                                }
                            onNavigateAllSuggestions(allSuggestions)
                        }
                    } else null,
                    onDragStart = { uuid, startY ->
                        val toHighlight = if (uuid in selectedBlockUuids) selectedBlockUuids else setOf(uuid)
                        if (uuid !in selectedBlockUuids) {
                            onAutoSelectForDrag(uuid)
                        }
                        dragState = BlockDragState(
                            draggedUuids = toHighlight,
                            pointerOffsetY = startY,
                            isDragging = true
                        )
                    },
                    onDrag = { deltaY ->
                        val newState = dragState?.copy(
                            pointerOffsetY = (dragState?.pointerOffsetY ?: 0f) + deltaY
                        )
                        dragState = newState
                        if (newState != null) {
                            computeDropTarget(newState, blocks)
                        }
                    },
                    onDragEnd = {
                        val state = dragState
                        if (state != null) {
                            val targetUuid = dropTargetUuid
                            val zone = currentDropZone
                            // Guard: disallow dropping onto own subtree
                            val allDescendants = state.draggedUuids + state.draggedUuids.flatMap { getDescendantUuids(it) }
                            val isOwnSubtree = targetUuid != null && targetUuid in allDescendants
                            if (!isOwnSubtree && targetUuid != null && zone != null) {
                                val targetBlock = blocks.find { it.uuid == targetUuid }
                                if (targetBlock != null) {
                                    when (zone) {
                                        DropZone.ABOVE -> {
                                            val siblingBefore = blocks
                                                .filter { it.parentUuid == targetBlock.parentUuid && it.position < targetBlock.position }
                                                .maxByOrNull { it.position }
                                            onMoveSelectedBlocks(targetBlock.parentUuid, siblingBefore?.uuid)
                                        }
                                        DropZone.BELOW -> {
                                            onMoveSelectedBlocks(targetBlock.parentUuid, targetBlock.uuid)
                                        }
                                        DropZone.CHILD -> {
                                            onMoveSelectedBlocks(targetBlock.uuid, null)
                                        }
                                    }
                                }
                            }
                        }
                        dragState = null
                        dropTargetUuid = null
                        currentDropZone = null
                    },
                    dropAbove = isDropTarget && currentDropZone == DropZone.ABOVE,
                    dropBelow = isDropTarget && currentDropZone == DropZone.BELOW,
                    dropAsChild = isDropTarget && currentDropZone == DropZone.CHILD,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        val top = coords.positionInParent().y
                        blockBounds = blockBounds + (block.uuid to Pair(top, top + coords.size.height))
                    },
                )
            }
        }
        } // end Column

        // Ghost overlay shown during drag
        val currentDragState = dragState
        if (currentDragState != null && currentDragState.isDragging) {
            val density = LocalDensity.current
            BlockDragGhost(
                draggedCount = currentDragState.draggedUuids.size,
                modifier = Modifier
                    .offset(
                        x = 16.dp,
                        y = with(density) { currentDragState.pointerOffsetY.toDp() } - 24.dp
                    )
                    .align(Alignment.TopStart)
                    .zIndex(10f)
            )
        }
    } // end Box
}
