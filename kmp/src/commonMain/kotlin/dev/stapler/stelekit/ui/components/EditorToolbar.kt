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
    onSuggestTags: ((blockUuid: String, content: String) -> Unit)? = null,
) {
    val editingBlockUuid by blockStateManager.editingBlockUuid.collectAsState()
    val editingCursorIndex by blockStateManager.editingCursorIndex.collectAsState()
    val isInSelectionMode by blockStateManager.isInSelectionMode.collectAsState()
    val selectedBlockUuids by blockStateManager.selectedBlockUuids.collectAsState()
    val allBlocks by blockStateManager.blocks.collectAsState()

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
        onFormat = { action -> blockStateManager.requestFormat(action) },
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
            if (suggestFn != null && targetUuid != null) {
                {
                    val block = allBlocks.values.flatten().find { it.uuid == targetUuid }
                    val content = block?.content ?: ""
                    suggestFn(targetUuid.value, content)
                }
            } else null
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
                    val block = allBlocks.values.flatten().find { it.uuid == curBlockUuid }
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
        onCopySelected = { blockStateManager.copySelectedBlocks("") },
        onDeleteSelected = { blockStateManager.deleteSelectedBlocks() },
        onClearSelection = { blockStateManager.clearSelection() },
        isLeftHanded = isLeftHanded,
        modifier = modifier,
    )
}
