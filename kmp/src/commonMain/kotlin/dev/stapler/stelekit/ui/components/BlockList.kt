package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
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

internal enum class DropZone { ABOVE, CHILD, BELOW }

private data class BlockDragState(
    val draggedUuids: Set<String>,
    val pointerOffsetY: Float,
    val isDragging: Boolean
)

internal data class DropTargetResult(
    val targetUuid: String?,
    val zone: DropZone?,
    /** True if [targetUuid] is inside the dragged selection's own subtree — a legal
     * nearest-candidate but an illegal drop (would create a cycle). Distinguished from "no
     * target" so callers can render a rejection cue during the drag instead of only failing
     * silently at release (docs/ux/block-reorder-permutations.md §8, gap #1). */
    val isBlocked: Boolean,
)

/**
 * Resolves the drop target for [pointerY] against the current [blockBounds], or null/blocked
 * results per docs/ux/block-reorder-permutations.md. Pure and Compose-free so the zone-boundary
 * math (§4.5's 40/20/40 split with a floor on the CHILD zone) is unit-testable without a
 * Compose test harness — see BlockListDropZoneTest.
 *
 * @param blockedTargetUuids the dragged selection's own UUIDs plus all of their descendants —
 *   precompute once per drag (it only depends on draggedUuids, not on pointer position) rather
 *   than walking the tree on every pointer-move tick.
 * @param minChildZonePx floor on the CHILD zone's height so it stays reachable on short
 *   (single-line) rows — §4.5 recommends 12dp.
 * @param outOfBoundsMarginPx how far past the topmost/bottommost block's edge the pointer may
 *   travel and still resolve to a target; beyond this, a release cancels instead of committing
 *   to whichever edge block happened to be nearest (§4.7, gap #3). Deliberately generous —
 *   §4.7 row 2 requires that dragging far below the last block (still within the same panel,
 *   just past the visible content) keeps resolving to BELOW-last-block unconditionally; this
 *   margin only needs to catch the pointer leaving the app's rendered area entirely (e.g. an
 *   off-window drag on desktop), not ordinary overshoot within a short list.
 */
internal fun computeDropTarget(
    pointerY: Float,
    blockBounds: Map<String, Pair<Float, Float>>,
    draggedUuids: Set<String>,
    blockedTargetUuids: Set<String>,
    minChildZonePx: Float,
    outOfBoundsMarginPx: Float,
): DropTargetResult {
    if (blockBounds.isEmpty()) return DropTargetResult(null, null, false)

    val listTop = blockBounds.values.minOf { it.first }
    val listBottom = blockBounds.values.maxOf { it.second }
    if (pointerY < listTop - outOfBoundsMarginPx || pointerY > listBottom + outOfBoundsMarginPx) {
        return DropTargetResult(null, null, false)
    }

    val candidate = blockBounds.entries
        .filter { (uuid, _) -> uuid !in draggedUuids }
        .minByOrNull { (_, bounds) -> kotlin.math.abs((bounds.first + bounds.second) / 2f - pointerY) }
        ?: return DropTargetResult(null, null, false)

    val (targetUuid, bounds) = candidate
    val top = bounds.first
    val height = bounds.second - bounds.first
    // 20% nominal CHILD zone, floored to minChildZonePx and capped at the row's own height —
    // ABOVE/BELOW split whatever remains evenly (40/40 on a normal-height row).
    val childZoneHeight = (height * 0.2f).coerceAtLeast(minChildZonePx).coerceAtMost(height)
    val childZoneStart = top + (height - childZoneHeight) / 2f
    val childZoneEnd = childZoneStart + childZoneHeight
    val zone = when {
        pointerY < childZoneStart -> DropZone.ABOVE
        pointerY > childZoneEnd -> DropZone.BELOW
        else -> DropZone.CHILD
    }
    return DropTargetResult(targetUuid, zone, isBlocked = targetUuid in blockedTargetUuids)
}

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
    /** GAP-005 (Story D.2.1): dispatches todo-toggle requests from the mobile toolbar's new
     * overflow-row "☑ TODO" button to whichever block is currently being edited — mirrors
     * [formatEvents]'s decoupling shape exactly. */
    todoToggleEvents: SharedFlow<Unit>? = null,
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
    onBlockSelectionChange: ((blockUuid: String, range: IntRange?) -> Unit)? = null,
    /** Called when the user taps an image_annotation block thumbnail to open the annotation editor. */
    onOpenAnnotationEditor: (imageAnnotationUuid: String) -> Unit = {},
    hasSectionFilter: Boolean = false,
    localPageNames: Set<String> = emptySet(),
    onUnavailableLinkTap: () -> Unit = {},
    /** UUIDs of blocks that are pending a CUT-paste; rendered at reduced alpha. */
    cutBlockUuids: Set<String> = emptySet(),
    /**
     * GAP-010 (Story D.3.1): called with `true` while a drag-to-reorder gesture is active and
     * `false` once it ends. [blockBounds] below is cached relative to this composable's own
     * internal `Column` and is never re-derived from an ancestor scroll offset — callers (e.g.
     * `JournalsView`/`PageView`) should set their hosting `LazyColumn`'s `userScrollEnabled` to
     * `false` while this is `true`, so a mid-drag scroll can never desynchronize `blockBounds`
     * from the drop-target computation (`docs/tasks/drag-and-drop-reorder.md`'s "Bug 002").
     */
    onDragStateChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (isDebugMode) {
        val recomposeCount = remember { androidx.compose.runtime.mutableIntStateOf(0) }
        androidx.compose.runtime.SideEffect { println("[Recompose] BlockList #${++recomposeCount.intValue}") }
    }

    // Build a map of parent UUID to children for quick lookup
    val childrenByParent = remember(blocks) {
        blocks.groupBy { it.parentUuid?.value }
    }

    // Get UUIDs of blocks that have children
    val blocksWithChildren = remember(blocks) {
        blocks.mapNotNull { it.parentUuid?.value }.toSet()
    }

    // Get all descendant UUIDs of a block (for hiding when collapsed)
    fun getDescendantUuids(blockUuid: String): Set<String> {
        val descendants = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(blockUuid)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            childrenByParent[current]?.forEach { child ->
                descendants.add(child.uuid.value)
                queue.add(child.uuid.value)
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
    var isDropBlocked by remember { mutableStateOf(false) }

    // GAP-010: notify the caller whenever an active drag starts/stops so the hosting scroll
    // container can suspend scrolling for the duration (see onDragStateChange's doc above).
    // rememberUpdatedState avoids capturing a stale lambda reference inside the LaunchedEffect
    // below if the caller passes a new onDragStateChange lambda across recompositions.
    val currentOnDragStateChange by rememberUpdatedState(onDragStateChange)
    val isDragging = dragState?.isDragging == true
    LaunchedEffect(isDragging) { currentOnDragStateChange(isDragging) }

    // Clean up drag state when this composable leaves the composition (e.g. page navigation)
    DisposableEffect(Unit) {
        onDispose {
            dragState = null
            dropTargetUuid = null
            currentDropZone = null
            isDropBlocked = false
            currentOnDragStateChange(false)
        }
    }

    // docs/ux/block-reorder-permutations.md §4.5/§4.7: zone-floor and out-of-bounds margins in
    // px, and the dragged selection's own subtree (own UUIDs + all descendants) precomputed once
    // per drag rather than walked on every pointer-move tick.
    val density = LocalDensity.current
    val minChildZonePx = with(density) { 12.dp.toPx() }
    val outOfBoundsMarginPx = with(density) { 2000.dp.toPx() }
    val blockedTargetUuids = remember(dragState?.draggedUuids) {
        val uuids = dragState?.draggedUuids ?: emptySet()
        uuids + uuids.flatMap { getDescendantUuids(it) }
    }

    fun refreshDropTarget(state: BlockDragState) {
        val result = computeDropTarget(
            pointerY = state.pointerOffsetY,
            blockBounds = blockBounds,
            draggedUuids = state.draggedUuids,
            blockedTargetUuids = blockedTargetUuids,
            minChildZonePx = minChildZonePx,
            outOfBoundsMarginPx = outOfBoundsMarginPx,
        )
        dropTargetUuid = result.targetUuid
        currentDropZone = result.zone
        isDropBlocked = result.isBlocked
    }

    Box(
        modifier = modifier
            .onPreviewKeyEvent { event ->
                // docs/ux/block-reorder-permutations.md §4.9: structural shortcuts (indent/
                // outdent/move) race an in-flight drag's eventual move if left to reach the
                // focused block's own handler — swallow them here, before they get that far,
                // for the duration of an active drag.
                val isStructuralShortcut = event.key == Key.Tab ||
                    ((event.key == Key.DirectionUp || event.key == Key.DirectionDown) && event.isAltPressed)
                if (dragState != null && isStructuralShortcut) true else false
            }
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
                        isDropBlocked = false
                        true
                    }
                    else -> false
                }
            }
    ) {
        // Blocks are each wrapped in their own per-row Box (below, for the cut-block alpha
        // treatment), so `coords.positionInParent()` on a block would report its position
        // relative to that ephemeral per-row Box (always ~(0,0)) rather than its position within
        // the list — every block would collapse to identical bounds. Track this Column's own
        // coordinates and resolve each block's position relative to it via localPositionOf,
        // which is robust to any intermediate wrapper nodes.
        var columnCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
        Column(modifier = Modifier.onGloballyPositioned { columnCoords = it }) {
        blocks.forEach { block ->
            // Only show if not hidden by a collapsed ancestor
            if (block.uuid.value !in hiddenBlocks) {
                val hasChildren = block.uuid.value in blocksWithChildren
                val isCollapsed = block.uuid.value in collapsedBlocks

                val isDropTarget = dragState != null && dropTargetUuid == block.uuid.value
                val isCutBlock = cutBlockUuids.isNotEmpty() && block.uuid.value in cutBlockUuids
                Box(modifier = if (isCutBlock) Modifier.graphicsLayer { alpha = 0.4f } else Modifier) {
                BlockRenderer(
                    block = block,
                    isDebugMode = isDebugMode,
                    isEditing = editingBlockUuid == block.uuid.value,
                    hasChildren = hasChildren,
                    isCollapsed = isCollapsed,
                    isSelected = block.uuid.value in selectedBlockUuids,
                    isInSelectionMode = isInSelectionMode,
                    onToggleSelect = { onToggleSelect(block.uuid.value) },
                    onEnterSelectionMode = { onEnterSelectionMode(block.uuid.value) },
                    isShiftDown = isShiftDown,
                    onShiftClick = { onShiftClick(block.uuid.value) },
                    onStartEditing = {
                        if (isInSelectionMode) onToggleSelect(block.uuid.value) else onStartEditing(block.uuid.value)
                    },
                    onStopEditing = { onStopEditing(block.uuid.value) },
                    onContentChange = { newContent, version -> onContentChange(block.uuid.value, newContent, version) },
                    onLinkClick = onLinkClick,
                    onNewBlock = onNewBlock,
                    onSplitBlock = onSplitBlock,
                    onMergeBlock = onMergeBlock,
                    initialCursorPosition = if (editingBlockUuid == block.uuid.value) editingCursorIndex else null,
                    onBackspace = { onBackspace(block.uuid.value) },
                    onLoadContent = { onLoadContent(block.pageUuid.value) },
                    onToggleCollapse = { onToggleCollapse(block.uuid.value) },
                    onIndent = { onIndent(block.uuid.value) },
                    onOutdent = { onOutdent(block.uuid.value) },
                    onMoveUp = { onMoveUp(block.uuid.value) },
                    onMoveDown = { onMoveDown(block.uuid.value) },
                    onFocusUp = { onFocusUp(block.uuid.value) },
                    onFocusDown = { onFocusDown(block.uuid.value) },
                    onResolveContent = onResolveContent,
                    onSearchPages = onSearchPages,
                    formatEvents = formatEvents,
                    todoToggleEvents = todoToggleEvents,
                    suggestionMatcher = suggestionMatcher,
                    onNavigateAllSuggestions = if (onNavigateAllSuggestions != null) {
                        {
                            // Collect suggestions from all visible blocks
                            val allSuggestions = blocks
                                .filter { it.uuid.value !in hiddenBlocks }
                                .flatMap { b ->
                                    extractSuggestions(b.content, suggestionMatcher)
                                        .map { span ->
                                            SuggestionItem(b.uuid.value, span.canonicalName, span.start, span.end)
                                        }
                                }
                            onNavigateAllSuggestions(allSuggestions)
                        }
                    } else null,
                    onOpenAnnotationEditor = onOpenAnnotationEditor,
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
                            refreshDropTarget(newState)
                        }
                    },
                    onDragEnd = {
                        val state = dragState
                        if (state != null) {
                            val targetUuid = dropTargetUuid
                            val zone = currentDropZone
                            // isDropBlocked already reflects the own-subtree guard, computed
                            // live during the drag (§8 gap #1) rather than re-derived here.
                            if (!isDropBlocked && targetUuid != null && zone != null) {
                                val targetBlock = blocks.find { it.uuid.value == targetUuid }
                                if (targetBlock != null) {
                                    when (zone) {
                                        DropZone.ABOVE -> {
                                            val siblingBefore = blocks
                                                .filter { it.parentUuid == targetBlock.parentUuid && it.position < targetBlock.position }
                                                .maxByOrNull { it.position }
                                            onMoveSelectedBlocks(targetBlock.parentUuid?.value, siblingBefore?.uuid?.value)
                                        }
                                        DropZone.BELOW -> {
                                            onMoveSelectedBlocks(targetBlock.parentUuid?.value, targetBlock.uuid.value)
                                        }
                                        DropZone.CHILD -> {
                                            // §4.3: a block dropped as a child of a collapsed
                                            // target would otherwise vanish with no feedback —
                                            // auto-expand so the moved block is visible.
                                            if (targetBlock.uuid.value in collapsedBlocks) {
                                                onToggleCollapse(targetBlock.uuid.value)
                                            }
                                            onMoveSelectedBlocks(targetBlock.uuid.value, null)
                                        }
                                    }
                                }
                            }
                        }
                        dragState = null
                        dropTargetUuid = null
                        currentDropZone = null
                        isDropBlocked = false
                    },
                    dropAbove = isDropTarget && !isDropBlocked && currentDropZone == DropZone.ABOVE,
                    dropBelow = isDropTarget && !isDropBlocked && currentDropZone == DropZone.BELOW,
                    dropAsChild = isDropTarget && !isDropBlocked && currentDropZone == DropZone.CHILD,
                    onSelectionChange = if (onBlockSelectionChange != null) {
                        { range -> onBlockSelectionChange(block.uuid.value, range) }
                    } else null,
                    hasSectionFilter = hasSectionFilter,
                    localPageNames = localPageNames,
                    onUnavailableLinkTap = onUnavailableLinkTap,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        val parent = columnCoords
                        if (parent != null) {
                            val top = parent.localPositionOf(coords, androidx.compose.ui.geometry.Offset.Zero).y
                            blockBounds = blockBounds + (block.uuid.value to Pair(top, top + coords.size.height))
                        }
                    },
                )
                } // end Box (cut block alpha wrapper)
            }
        }
        } // end Column

        // Ghost overlay shown during drag
        val currentDragState = dragState
        if (currentDragState != null && currentDragState.isDragging) {
            BlockDragGhost(
                draggedCount = currentDragState.draggedUuids.size,
                isBlocked = isDropBlocked,
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
