package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.ui.screens.SearchViewModel
import dev.stapler.stelekit.ui.state.BlockStateManager

/**
 * Hosts [MobileBlockToolbar] and its link-picker dialog with full wiring to [BlockStateManager].
 *
 * Both PageView and JournalsView use this composable — there is exactly one toolbar wiring site.
 * Pass [modifier] with `Modifier.align(Alignment.BottomCenter).onSizeChanged { ... }` from the
 * enclosing Box to position the toolbar and measure its height for content padding.
 */
@Composable
fun EditorToolbar(
    blockStateManager: BlockStateManager,
    capabilities: EditorCapabilities,
    searchViewModel: SearchViewModel?,
    isLeftHanded: Boolean,
    modifier: Modifier = Modifier,
    /**
     * ux.md (i)/criterion 14: true while a `DiskConflict` is pending resolution in `AppState`.
     * Threaded straight through to [MobileBlockToolbar]'s `hasDiskConflictPending` — see that
     * parameter's doc for the live-state contract callers must uphold.
     */
    hasDiskConflictPending: Boolean = false,
    onSuggestTags: ((blockUuid: String, content: String) -> Unit)? = null,
    // Test-only recomposition probe (pitfalls.md §1 / Task B.1.2a regression guard).
    // Invoked via SideEffect once per recomposition of this composable's own scope.
    // Always null in production call sites (PageView/JournalsView) — exists solely so
    // EditorToolbarRecompositionTest can assert this composable does not recompose on
    // every keystroke into the editing block.
    onRecompose: (() -> Unit)? = null,
    // Test-only hook exposing the freshly-built onSuggestTags click handler on every
    // (re)composition. Always a no-op in production call sites — lets
    // EditorToolbarRecompositionTest hold a reference to the handler independent of this
    // composable's lifecycle, to prove it reads block content from a live source
    // (blockStateManager.blocks.value) rather than a collectAsState() snapshot that goes
    // stale once this composable leaves composition.
    onSuggestTagsHandlerReady: (handler: (() -> Unit)?) -> Unit = {},
) {
    SideEffect { onRecompose?.invoke() }

    val editingBlockUuid by blockStateManager.editingBlockUuid.collectAsState()
    val editingCursorIndex by blockStateManager.editingCursorIndex.collectAsState()
    val isInSelectionMode by blockStateManager.isInSelectionMode.collectAsState()
    val selectedBlockUuids by blockStateManager.selectedBlockUuids.collectAsState()
    val blockClipboard by blockStateManager.blockClipboard.collectAsState()

    var showLinkPicker by remember { mutableStateOf(false) }
    var linkPickerBlockUuid by remember { mutableStateOf<BlockUuid?>(null) }
    var linkPickerCursorIndex by remember { mutableStateOf<Int?>(null) }
    var linkPickerSelectionRange by remember { mutableStateOf<IntRange?>(null) }
    var linkPickerInitialQuery by remember { mutableStateOf<String?>(null) }

    if (showLinkPicker && searchViewModel != null) {
        SearchDialog(
            visible = true,
            onDismiss = { showLinkPicker = false },
            onNavigateToPage = {},
            onNavigateToBlock = {},
            onCreatePage = { pageName ->
                linkPickerBlockUuid?.let { blockUuid ->
                    blockStateManager.acceptLinkPickerResult(
                        blockUuid, pageName, linkPickerSelectionRange, linkPickerCursorIndex
                    )
                }
                showLinkPicker = false
            },
            viewModel = searchViewModel,
            initialQuery = linkPickerInitialQuery ?: "",
            onPageSelected = { pageName ->
                linkPickerBlockUuid?.let { blockUuid ->
                    blockStateManager.acceptLinkPickerResult(
                        blockUuid, pageName, linkPickerSelectionRange, linkPickerCursorIndex
                    )
                }
                showLinkPicker = false
            },
        )
    }

    MobileBlockToolbar(
        editingBlockId = editingBlockUuid?.value,
        onIndent = { blockUuid -> blockStateManager.indentBlock(BlockUuid(blockUuid)) },
        onOutdent = { blockUuid -> blockStateManager.outdentBlock(BlockUuid(blockUuid)) },
        onMoveUp = { blockUuid -> blockStateManager.moveBlockUp(BlockUuid(blockUuid)) },
        onMoveDown = { blockUuid -> blockStateManager.moveBlockDown(BlockUuid(blockUuid)) },
        onAddBlock = { blockUuid -> blockStateManager.addNewBlock(BlockUuid(blockUuid)) },
        onUndo = { blockStateManager.undo() },
        onRedo = { blockStateManager.redo() },
        hasDiskConflictPending = hasDiskConflictPending,
        onFormat = { action -> blockStateManager.requestFormat(action) },
        onTodoToggle = { blockStateManager.requestTodoToggle() },
        onEnterSelectionMode = { blockUuid -> blockStateManager.enterSelectionMode(BlockUuid(blockUuid)) },
        onAttachImage = run {
            val attachFn = capabilities.onAttachImage
            val targetUuid = editingBlockUuid
            if (attachFn != null && targetUuid != null) {
                { attachFn.invoke(targetUuid) }
            } else null
        },
        onSuggestTags = run {
            val suggestFn = onSuggestTags
            val targetUuid = editingBlockUuid
            val handler = if (suggestFn != null && targetUuid != null) {
                {
                    // Read blocks at click-time via .value, not a collectAsState()-derived
                    // variable captured in this closure — the latter changes on every
                    // keystroke and would force MobileBlockToolbar to recompose constantly.
                    val block = blockStateManager.blocks.value.values.flatten().find { it.uuid == targetUuid }
                    val content = block?.content ?: ""
                    suggestFn(targetUuid.value, content)
                }
            } else null
            onSuggestTagsHandlerReady(handler)
            handler
        },
        onCaptureImage = capabilities.onCaptureImage,
        onLinkPicker = if (searchViewModel != null) {
            {
                val curBlockUuid = editingBlockUuid
                val sel = blockStateManager.editingSelectionRange.value
                linkPickerBlockUuid = curBlockUuid
                linkPickerCursorIndex = editingCursorIndex ?: sel?.first
                linkPickerSelectionRange = sel
                linkPickerInitialQuery = if (sel != null && sel.first < sel.last && curBlockUuid != null) {
                    // Same click-time-read fix as onSuggestTags above (Task B.1.1b) — read
                    // blocks via .value here, not a collectAsState()-derived closure capture.
                    val block = blockStateManager.blocks.value.values.flatten().find { it.uuid == curBlockUuid }
                    block?.content?.substring(
                        sel.first.coerceAtMost(block.content.length),
                        sel.last.coerceAtMost(block.content.length)
                    )
                } else null
                showLinkPicker = true
            }
        } else null,
        isInSelectionMode = isInSelectionMode,
        selectedCount = selectedBlockUuids.size,
        onCopyBlocks = { blockStateManager.copySelectedBlocks() },
        onCutBlocks = { blockStateManager.cutSelectedBlocks() },
        onDeleteSelected = { blockStateManager.deleteSelectedBlocks() },
        onClearSelection = { blockStateManager.clearSelection() },
        clipboardEmpty = blockClipboard.isEmpty,
        onPaste = { editingBlockUuid?.let { blockStateManager.pasteBlocks(it) } },
        onClearClipboard = { blockStateManager.clearClipboard() },
        isLeftHanded = isLeftHanded,
        modifier = modifier,
    )
}
