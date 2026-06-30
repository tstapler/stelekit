package dev.stapler.stelekit.ui.state

import dev.stapler.stelekit.clipboard.BlockClipboard
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.screens.FormatAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Content editing: text changes, formatting, link operations, cursor state. */
interface BlockEditingPort {
    val editingBlockUuid: StateFlow<BlockUuid?>
    val editingCursorIndex: StateFlow<Int?>
    val editingSelectionRange: StateFlow<IntRange?>
    val formatEvents: SharedFlow<FormatAction>
    fun requestEditBlock(blockUuid: BlockUuid?, cursorIndex: Int? = null)
    fun stopEditingBlock(blockUuid: BlockUuid)
    fun updateBlockContent(blockUuid: BlockUuid, newContent: String, version: Long): Job
    fun updateEditingSelection(range: IntRange?)
    fun insertTextAtCursor(blockUuid: BlockUuid, text: String, overrideCursorIndex: Int? = null)
    fun insertLinkAtCursor(blockUuid: BlockUuid, pageName: String, overrideCursorIndex: Int? = null)
    fun replaceSelectionWithLink(blockUuid: BlockUuid, selectionStart: Int, selectionEnd: Int, pageName: String)
    fun acceptLinkPickerResult(blockUuid: BlockUuid, pageName: String, selectionRange: IntRange?, overrideCursorIndex: Int?)
    fun requestFormat(action: FormatAction)
    fun undo(): Job
    fun redo(): Job
}

/** Structural operations: indent, move, split, merge, add, delete. */
interface BlockStructurePort {
    fun indentBlock(blockUuid: BlockUuid): Job
    fun outdentBlock(blockUuid: BlockUuid): Job
    fun moveBlockUp(blockUuid: BlockUuid): Job
    fun moveBlockDown(blockUuid: BlockUuid): Job
    fun addNewBlock(currentBlockUuid: BlockUuid): Job
    fun addBlockToPage(pageUuid: PageUuid): Job
    fun splitBlock(blockUuid: BlockUuid, cursorPosition: Int): Job
    fun mergeBlock(blockUuid: BlockUuid): Job
    fun handleBackspace(blockUuid: BlockUuid): Job
    fun deleteSelectedBlocks(): Job
    fun moveSelectedBlocks(newParentUuid: BlockUuid?, insertAfterUuid: BlockUuid?): Job
    fun pasteBlocks(afterBlockUuid: BlockUuid): Job
}

/** Selection: multi-select mode, range extension. */
interface BlockSelectionPort {
    val isInSelectionMode: StateFlow<Boolean>
    val selectedBlockUuids: StateFlow<Set<String>>
    val blockClipboard: StateFlow<BlockClipboard>
    fun enterSelectionMode(uuid: BlockUuid)
    fun toggleBlockSelection(uuid: BlockUuid)
    fun extendSelectionTo(uuid: BlockUuid)
    fun extendSelectionByOne(up: Boolean)
    fun selectAll(pageUuid: PageUuid)
    fun clearSelection()
    fun copySelectedBlocks(): Job
    fun cutSelectedBlocks(): Job
}

/** Navigation and collapse state. */
interface BlockNavigationPort {
    val collapsedBlockUuids: StateFlow<Set<String>>
    fun focusPreviousBlock(blockUuid: BlockUuid): Job
    fun focusNextBlock(blockUuid: BlockUuid): Job
    fun toggleBlockCollapse(blockUuid: BlockUuid)
}
