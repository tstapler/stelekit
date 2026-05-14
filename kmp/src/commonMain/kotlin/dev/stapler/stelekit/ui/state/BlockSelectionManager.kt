package dev.stapler.stelekit.ui.state

import dev.stapler.stelekit.model.Block
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Manages block selection state (multi-select mode).
 *
 * Receives the current blocks map and visible-blocks function from [BlockStateManager]
 * via constructor lambdas so it has no direct dependency on BSM or the repository layer.
 */
class BlockSelectionManager(
    private val blocksSnapshot: () -> Map<String, List<Block>>,
    private val visibleBlocksForPage: (pageUuid: String) -> List<Block>
) {
    private val _selectedBlockUuids = MutableStateFlow<Set<String>>(emptySet())
    val selectedBlockUuids: StateFlow<Set<String>> = _selectedBlockUuids.asStateFlow()

    private val _isInSelectionMode = MutableStateFlow(false)
    val isInSelectionMode: StateFlow<Boolean> = _isInSelectionMode.asStateFlow()

    var selectionAnchorUuid: String? = null
        private set

    fun enterSelectionMode(uuid: String) {
        selectionAnchorUuid = uuid
        _selectedBlockUuids.value = setOf(uuid)
        _isInSelectionMode.value = true
    }

    fun toggleBlockSelection(uuid: String) {
        _selectedBlockUuids.update { current ->
            if (uuid in current) current - uuid else current + uuid
        }
        _isInSelectionMode.value = _selectedBlockUuids.value.isNotEmpty()
        if (_selectedBlockUuids.value.isEmpty()) selectionAnchorUuid = null
    }

    fun extendSelectionByOne(up: Boolean) {
        val anchor = selectionAnchorUuid ?: return
        val pageUuid = findPageUuidForBlock(anchor) ?: return
        val visibleBlocks = visibleBlocksForPage(pageUuid)
        val selected = _selectedBlockUuids.value
        if (selected.isEmpty()) {
            enterSelectionMode(anchor)
            return
        }
        if (up) {
            val topIdx = visibleBlocks.indexOfFirst { it.uuid in selected }
            if (topIdx > 0) {
                _selectedBlockUuids.update { it + visibleBlocks[topIdx - 1].uuid }
            }
        } else {
            val bottomIdx = visibleBlocks.indexOfLast { it.uuid in selected }
            if (bottomIdx >= 0 && bottomIdx < visibleBlocks.size - 1) {
                _selectedBlockUuids.update { it + visibleBlocks[bottomIdx + 1].uuid }
            }
        }
        _isInSelectionMode.value = _selectedBlockUuids.value.isNotEmpty()
    }

    fun extendSelectionTo(uuid: String) {
        val anchor = selectionAnchorUuid
        if (anchor == null) {
            enterSelectionMode(uuid)
            return
        }
        val pageUuid = findPageUuidForBlock(anchor) ?: return
        val visibleBlocks = visibleBlocksForPage(pageUuid)
        val anchorIdx = visibleBlocks.indexOfFirst { it.uuid == anchor }
        val targetIdx = visibleBlocks.indexOfFirst { it.uuid == uuid }
        if (anchorIdx < 0 || targetIdx < 0) return
        val range = if (anchorIdx <= targetIdx)
            visibleBlocks.subList(anchorIdx, targetIdx + 1)
        else
            visibleBlocks.subList(targetIdx, anchorIdx + 1)
        _selectedBlockUuids.value = range.map { it.uuid }.toSet()
        _isInSelectionMode.value = _selectedBlockUuids.value.isNotEmpty()
    }

    fun selectAll(pageUuid: String) {
        val visible = visibleBlocksForPage(pageUuid)
        selectionAnchorUuid = visible.firstOrNull()?.uuid
        _selectedBlockUuids.value = visible.map { it.uuid }.toSet()
        _isInSelectionMode.value = _selectedBlockUuids.value.isNotEmpty()
    }

    fun clearSelection() {
        _selectedBlockUuids.value = emptySet()
        _isInSelectionMode.value = false
        selectionAnchorUuid = null
    }

    /**
     * Removes UUIDs from the set whose ancestor is also in the set,
     * to prevent double-deletion when parent+child are both selected.
     */
    fun subtreeDedup(uuids: Set<String>, pageUuid: String): List<String> {
        val allBlocks = blocksSnapshot()[pageUuid] ?: return uuids.toList()
        val blockByUuid = allBlocks.associateBy { it.uuid }
        return uuids.filter { uuid ->
            var current = blockByUuid[uuid]?.parentUuid
            while (current != null) {
                if (current in uuids) return@filter false
                current = blockByUuid[current]?.parentUuid
            }
            true
        }
    }

    private fun findPageUuidForBlock(blockUuid: String): String? =
        blocksSnapshot().entries.find { (_, blocks) -> blocks.any { it.uuid == blockUuid } }?.key
}
