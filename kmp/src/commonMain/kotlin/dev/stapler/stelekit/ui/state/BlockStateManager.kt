package dev.stapler.stelekit.ui.state

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.outliner.BlockSorter
import dev.stapler.stelekit.performance.DebounceManager
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.ui.screens.FormatAction
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Single source of truth for block state across all screens (JournalsView, PageView).
 *
 * Uses a dirty-set pattern for optimistic updates:
 * - Local edits update state immediately and mark blocks as "dirty"
 * - Reactive DB emissions merge with local state: dirty blocks keep their local content
 * - When the DB confirms a write (version matches), the dirty flag clears
 *
 * This eliminates the race condition where reactive re-emissions overwrite local edits.
 *
 * Direct repository writes are permitted here because BSM operations are user-triggered
 * (single, sequential interactions) and do not run concurrently with the parallel graph
 * loading that causes SQLITE_BUSY. The annotation opt-in is intentional and documents this
 * reasoning explicitly.
 */
@OptIn(DirectRepositoryWrite::class)
class BlockStateManager(
    private val blockRepository: BlockRepository,
    private val graphLoader: GraphLoader,
    // Default scope owns its own lifecycle so callers stored in remember{} don't pass
    // rememberCoroutineScope(), which is cancelled when the composable leaves composition.
    // Tests inject a TestCoroutineScope for deterministic scheduling.
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val graphWriter: GraphWriter? = null,
    private val pageRepository: PageRepository? = null,
    private val graphPathProvider: () -> String = { "" }
) {
    private val scope = scope
    private val logger = Logger("BlockStateManager")
    private val diskWriteDebounce = DebounceManager(scope, 300L)

    // ---- Block state (per-page) ----

    private val _blocks = MutableStateFlow<Map<String, List<Block>>>(emptyMap())
    val blocks: StateFlow<Map<String, List<Block>>> = _blocks.asStateFlow()

    /** Blocks whose content was updated locally but not yet confirmed by DB re-emission. */
    private val dirtyBlocks = mutableMapOf<String, Long>() // blockUuid → local version

    /** UUIDs of blocks that have unsaved local edits. Used by ViewModel to protect pages from external overwrites. */
    val dirtyBlockUuids: Set<String> get() = dirtyBlocks.keys.toSet()

    private val observationJobs = mutableMapOf<String, Job>()

    // ---- Editing focus ----

    private val _editingBlockUuid = MutableStateFlow<String?>(null)
    val editingBlockUuid: StateFlow<String?> = _editingBlockUuid.asStateFlow()

    private val _editingCursorIndex = MutableStateFlow<Int?>(null)
    val editingCursorIndex: StateFlow<Int?> = _editingCursorIndex.asStateFlow()

    private val _collapsedBlockUuids = MutableStateFlow<Set<String>>(emptySet())
    val collapsedBlockUuids: StateFlow<Set<String>> = _collapsedBlockUuids.asStateFlow()

    // ---- Selection state ----

    private val _selectedBlockUuids = MutableStateFlow<Set<String>>(emptySet())
    val selectedBlockUuids: StateFlow<Set<String>> = _selectedBlockUuids.asStateFlow()

    private val _isInSelectionMode = MutableStateFlow(false)
    val isInSelectionMode: StateFlow<Boolean> = _isInSelectionMode.asStateFlow()

    private var selectionAnchorUuid: String? = null

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
        val pageUuid = _blocks.value.entries
            .find { (_, blocks) -> blocks.any { it.uuid == anchor } }
            ?.key ?: return
        val visibleBlocks = getVisibleBlocksForPage(pageUuid)
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
        // Find pageUuid from selected blocks or all blocks
        val pageUuid = _blocks.value.entries
            .find { (_, blocks) -> blocks.any { it.uuid == anchor } }
            ?.key ?: return
        val visibleBlocks = getVisibleBlocksForPage(pageUuid)
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
        val visible = getVisibleBlocksForPage(pageUuid)
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
    private fun subtreeDedup(uuids: Set<String>, pageUuid: String): List<String> {
        val allBlocks = _blocks.value[pageUuid] ?: return uuids.toList()
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

    /**
     * Delete all currently selected blocks (and their subtrees) in a single
     * undo-able operation. Clears the selection when done.
     */
    fun deleteSelectedBlocks(): Job = scope.launch {
        val selected = _selectedBlockUuids.value
        if (selected.isEmpty()) return@launch
        // Determine page UUID from the first selected block
        val pageUuid = _blocks.value.entries
            .find { (_, blocks) -> blocks.any { it.uuid in selected } }
            ?.key ?: return@launch
        val before = takePageSnapshot(pageUuid)
        val toDelete = subtreeDedup(selected, pageUuid)
        blockRepository.deleteBulk(toDelete, deleteChildren = true)
        val refreshed = blockRepository.getBlocksForPage(pageUuid).first().getOrNull() ?: emptyList()
        _blocks.update { state ->
            state.toMutableMap().apply { put(pageUuid, refreshed) }
        }
        queueDiskSave(pageUuid)
        val after = takePageSnapshot(pageUuid)
        record(
            undo = { restorePageToSnapshot(pageUuid, before) },
            redo = { restorePageToSnapshot(pageUuid, after) }
        )
        clearSelection()
    }

    /**
     * Move all currently selected blocks to a new parent, inserting after [insertAfterUuid].
     * Selected blocks are sorted by their current visual position, subtree-deduplicated,
     * and moved sequentially. Each call to [blockRepository.moveBlock] repairs the linked-list
     * chain incrementally, so position indices are safe to compute as (startPosition + loopIndex).
     *
     * Wraps the entire operation in a single undo entry.
     */
    fun moveSelectedBlocks(newParentUuid: String?, insertAfterUuid: String?): Job = scope.launch {
        val selected = _selectedBlockUuids.value
        if (selected.isEmpty()) return@launch

        val pageUuid = _blocks.value.entries
            .find { (_, blocks) -> blocks.any { it.uuid in selected } }
            ?.key ?: return@launch

        val before = takePageSnapshot(pageUuid)

        // Sort selected UUIDs by their current visual order
        val visible = getVisibleBlocksForPage(pageUuid)
        val sortedSelected = visible
            .filter { it.uuid in selected }
            .map { it.uuid }

        // Remove descendants of other selected blocks to avoid double-move
        val toMove = subtreeDedup(sortedSelected.toSet(), pageUuid)

        if (toMove.isEmpty()) {
            clearSelection()
            return@launch
        }

        // Compute start position: how many children the target parent already has
        // at the point after insertAfterUuid
        val allBlocks = _blocks.value[pageUuid] ?: emptyList()
        val siblingCount = allBlocks.count { it.parentUuid == newParentUuid }
        val startPosition = if (insertAfterUuid == null) {
            0
        } else {
            val insertAfterBlock = allBlocks.find { it.uuid == insertAfterUuid }
            if (insertAfterBlock != null) (insertAfterBlock.position + 1) else siblingCount
        }

        // Move each block in sorted order, incrementing position for each
        toMove.forEachIndexed { index, uuid ->
            blockRepository.moveBlock(uuid, newParentUuid, startPosition + index)
        }

        // Refresh state
        val refreshed = blockRepository.getBlocksForPage(pageUuid).first().getOrNull() ?: emptyList()
        _blocks.update { state ->
            state.toMutableMap().apply { put(pageUuid, refreshed) }
        }
        queueDiskSave(pageUuid)

        val after = takePageSnapshot(pageUuid)
        record(
            undo = { restorePageToSnapshot(pageUuid, before) },
            redo = { restorePageToSnapshot(pageUuid, after) }
        )
        clearSelection()
    }

    // ---- Formatting events (toolbar → active BlockItem) ----

    private val _formatEvents = MutableSharedFlow<FormatAction>(extraBufferCapacity = 1)
    val formatEvents: SharedFlow<FormatAction> = _formatEvents.asSharedFlow()

    fun requestFormat(action: FormatAction) {
        _formatEvents.tryEmit(action)
    }

    // ---- Undo/Redo ----

    private data class UndoEntry(
        val undo: suspend () -> Unit,
        val redo: (suspend () -> Unit)?
    )

    private val undoStack = ArrayDeque<UndoEntry>()
    private val redoStack = ArrayDeque<UndoEntry>()
    private val maxUndo = 100

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    fun record(undo: suspend () -> Unit, redo: (suspend () -> Unit)? = null) {
        undoStack.addLast(UndoEntry(undo, redo))
        if (undoStack.size > maxUndo) undoStack.removeFirst()
        redoStack.clear()
        _canUndo.value = true
        _canRedo.value = false
    }

    fun undo(): Job = scope.launch {
        val entry = undoStack.removeLastOrNull() ?: return@launch
        entry.undo()
        if (entry.redo != null) {
            redoStack.addLast(entry)
            _canRedo.value = true
        } else {
            redoStack.clear()
            _canRedo.value = false
        }
        _canUndo.value = undoStack.isNotEmpty()
    }

    fun redo(): Job = scope.launch {
        val entry = redoStack.removeLastOrNull() ?: return@launch
        entry.redo?.invoke() ?: return@launch
        undoStack.addLast(entry)
        _canUndo.value = true
        _canRedo.value = redoStack.isNotEmpty()
    }

    // ---- Page observation ----

    /**
     * Start observing blocks for a page. Triggers lazy load if needed.
     * Uses dirty-set merge: incoming blocks only overwrite local state if
     * the block is NOT dirty (i.e., has no unconfirmed local edit).
     */
    fun observePage(pageUuid: String, isContentLoaded: Boolean = true) {
        if (pageUuid in observationJobs) return

        observationJobs[pageUuid] = scope.launch {
            if (!isContentLoaded) {
                _loadingPageUuids.update { it + pageUuid }
                try {
                    graphLoader.loadFullPage(pageUuid)
                } finally {
                    _loadingPageUuids.update { it - pageUuid }
                }
            }

            blockRepository.getBlocksForPage(pageUuid).collect { result ->
                val incomingBlocks = result.getOrNull() ?: emptyList()
                _blocks.update { current ->
                    val localBlocks = current[pageUuid] ?: emptyList()
                    val merged = mergeBlocks(localBlocks, incomingBlocks)
                    current + (pageUuid to merged)
                }
            }
        }
    }

    /**
     * Stop observing blocks for a page and remove its state.
     * Clears any dirty entries for blocks on that page.
     */
    fun unobservePage(pageUuid: String) {
        observationJobs.remove(pageUuid)?.cancel()
        // Clear dirty entries for this page (blocks stay cached so re-navigation is instant)
        val blockUuids = _blocks.value[pageUuid]?.map { it.uuid } ?: emptyList()
        blockUuids.forEach { dirtyBlocks.remove(it) }
    }

    /**
     * Merge incoming DB blocks with local state using dirty-set semantics.
     * Dirty blocks keep their local version; clean blocks accept the DB version.
     */
    private fun mergeBlocks(localBlocks: List<Block>, incomingBlocks: List<Block>): List<Block> {
        return incomingBlocks.map { incoming ->
            val dirtyVersion = dirtyBlocks[incoming.uuid]
            if (dirtyVersion != null && dirtyVersion > incoming.version) {
                // Block has a local edit not yet confirmed — keep local version
                val local = localBlocks.find { it.uuid == incoming.uuid }
                local ?: incoming
            } else {
                // DB version is current — accept it and clear dirty flag
                dirtyBlocks.remove(incoming.uuid)
                incoming
            }
        }
    }

    fun blocksForPage(pageUuid: String): List<Block> =
        _blocks.value[pageUuid] ?: emptyList()

    // ---- Editing focus management ----

    fun requestEditBlock(blockUuid: String?, cursorIndex: Int? = null) {
        _editingBlockUuid.value = blockUuid
        _editingCursorIndex.value = cursorIndex
    }

    /**
     * Stops editing only if [blockUuid] is still the currently-editing block.
     * Safe to call from focus-loss handlers: if focus was already transferred
     * programmatically to a different block, this is a no-op.
     */
    fun stopEditingBlock(blockUuid: String) {
        if (_editingBlockUuid.value == blockUuid) {
            _editingBlockUuid.value = null
            _editingCursorIndex.value = null
        }
    }

    fun toggleBlockCollapse(blockUuid: String) {
        _collapsedBlockUuids.update { collapsed ->
            if (blockUuid in collapsed) collapsed - blockUuid else collapsed + blockUuid
        }
    }

    // ---- Block content operations ----

    /**
     * Inserts [[pageName]] at the last known cursor position for the given block.
     * Used by the mobile link picker after the user selects a page.
     */
    fun insertLinkAtCursor(blockUuid: String, pageName: String) {
        scope.launch {
            val block = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return@launch
            val cursor = _editingCursorIndex.value ?: block.content.length
            val linkText = "[[$pageName]]"
            val safePos = cursor.coerceIn(0, block.content.length)
            val newContent = block.content.substring(0, safePos) + linkText + block.content.substring(safePos)
            val newVersion = block.version + 1
            updateBlockContent(blockUuid, newContent, newVersion)
            requestEditBlock(blockUuid, safePos + linkText.length)
        }
    }

    /**
     * Persist updated properties for a block (e.g. flashcard review state).
     * Saves to DB, updates local in-memory state, and queues a disk write so
     * the markdown file reflects the new `key:: value` property lines.
     */
    fun updateBlockProperties(blockUuid: String, newProperties: Map<String, String>): Job = scope.launch {
        val block = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return@launch
        val updated = block.copy(properties = newProperties, version = block.version + 1)
        dirtyBlocks[blockUuid] = updated.version
        blockRepository.saveBlock(updated)
        _blocks.update { current ->
            val newBlocks = current.toMutableMap()
            val pageBlocks = newBlocks[block.pageUuid]?.toMutableList() ?: return@update current
            val idx = pageBlocks.indexOfFirst { it.uuid == blockUuid }
            if (idx >= 0) pageBlocks[idx] = updated
            newBlocks[block.pageUuid] = pageBlocks
            newBlocks
        }
        queueDiskSave(block.pageUuid)
    }

    /**
     * Optimistically update block content. Updates local state immediately,
     * marks the block as dirty, and persists to DB asynchronously.
     */
    fun updateBlockContent(blockUuid: String, newContent: String, newVersion: Long): Job = scope.launch {
        val block = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return@launch
        val oldContent = block.content
        val oldVersion = block.version
        if (oldContent == newContent) return@launch

        applyContentChange(blockUuid, newContent, newVersion)

        record(
            undo = {
                applyContentChange(blockUuid, oldContent, oldVersion)
                requestEditBlock(blockUuid, oldContent.length)
            },
            redo = {
                applyContentChange(blockUuid, newContent, newVersion)
                requestEditBlock(blockUuid, newContent.length)
            }
        )
    }

    /**
     * Core optimistic update: update local state, mark dirty, persist to DB,
     * and queue a debounced disk write.
     */
    private suspend fun applyContentChange(blockUuid: String, content: String, version: Long) {
        val block = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return
        val updated = block.copy(content = content, version = version)

        // Mark dirty BEFORE saving so the observer merge keeps our version
        dirtyBlocks[blockUuid] = version

        blockRepository.saveBlock(updated)

        _blocks.update { current ->
            val newBlocks = current.toMutableMap()
            val pageBlocks = newBlocks[block.pageUuid]?.toMutableList() ?: return@update current
            val idx = pageBlocks.indexOfFirst { it.uuid == blockUuid }
            if (idx >= 0) pageBlocks[idx] = updated
            newBlocks[block.pageUuid] = pageBlocks
            newBlocks
        }

        // Queue debounced disk write
        queueDiskSave(block.pageUuid)
    }

    /**
     * Queue a debounced disk write for a page. Uses the optimistic local state
     * (not DB) so the latest edits are always included.
     */
    private fun queueDiskSave(pageUuid: String) {
        if (graphWriter == null || pageRepository == null) return
        val graphPath = graphPathProvider()
        if (graphPath.isEmpty()) return

        diskWriteDebounce.debounce("disk-$pageUuid") {
            val page = pageRepository.getPageByUuid(pageUuid).first().getOrNull() ?: return@debounce
            // Use local optimistic state, not DB, to get the latest content
            val blocks = _blocks.value[pageUuid] ?: blockRepository.getBlocksForPage(pageUuid).first().getOrNull() ?: emptyList()
            graphWriter.queueSave(page, blocks, graphPath)
        }
    }

    /**
     * Public: queue a debounced disk write for [pageUuid].
     * Used by StelekitViewModel for conflict resolution.
     */
    fun queuePageSave(pageUuid: String) = queueDiskSave(pageUuid)

    /**
     * Public: write [pageUuid] to disk immediately (no debounce).
     * Used by StelekitViewModel for journal initialisation and conflict resolution.
     */
    suspend fun savePageNow(pageUuid: String) {
        if (graphWriter == null || pageRepository == null) return
        val graphPath = graphPathProvider()
        if (graphPath.isEmpty()) return
        val page = pageRepository.getPageByUuid(pageUuid).first().getOrNull() ?: return
        // Use local blocks only if non-empty. An empty list may be transient (the brief window
        // between deleteBlocksForPage and saveBlocks in parseAndSavePage), so fall back to the DB
        // to avoid writing empty content to disk.
        val localBlocks = _blocks.value[pageUuid]
        val blocks = if (localBlocks?.isNotEmpty() == true) {
            localBlocks
        } else {
            blockRepository.getBlocksForPage(pageUuid).first().getOrNull() ?: emptyList()
        }
        graphWriter.savePage(page, blocks, graphPath)
    }

    /**
     * Flush all pending disk writes immediately. Call on app shutdown.
     */
    suspend fun flush() {
        diskWriteDebounce.flushAll()
        graphWriter?.flush()
    }

    // ---- Structural block operations ----

    private suspend fun getPageUuidForBlock(blockUuid: String): String? {
        return _blocks.value.entries
            .find { (_, blocks) -> blocks.any { it.uuid == blockUuid } }
            ?.key
            ?: blockRepository.getBlockByUuid(blockUuid).first().getOrNull()?.pageUuid
    }

    private suspend fun takePageSnapshot(pageUuid: String): List<Block> =
        blockRepository.getBlocksForPage(pageUuid).first().getOrNull() ?: emptyList()

    private suspend fun restorePageToSnapshot(pageUuid: String, snapshot: List<Block>) {
        val current = blockRepository.getBlocksForPage(pageUuid).first().getOrNull() ?: return
        val snapshotUuids = snapshot.map { it.uuid }.toSet()
        val currentUuids = current.map { it.uuid }.toSet()
        (currentUuids - snapshotUuids).forEach { uuid ->
            blockRepository.deleteBlock(uuid, deleteChildren = false)
        }
        blockRepository.saveBlocks(snapshot)
        _blocks.update { state ->
            val newBlocks = state.toMutableMap()
            newBlocks[pageUuid] = snapshot
            newBlocks
        }
        queueDiskSave(pageUuid)
    }

    private suspend fun refreshBlocksForPage(blockUuid: String) {
        val pageUuid = _blocks.value.entries
            .find { (_, blocks) -> blocks.any { it.uuid == blockUuid } }
            ?.key ?: return

        val pageBlocks = blockRepository.getBlocksForPage(pageUuid).first().getOrNull() ?: return
        _blocks.update { state ->
            val newBlocks = state.toMutableMap()
            newBlocks[pageUuid] = pageBlocks
            newBlocks
        }

        queueDiskSave(pageUuid)
    }

    fun indentBlock(blockUuid: String): Job = scope.launch {
        val pageUuid = getPageUuidForBlock(blockUuid) ?: return@launch
        val before = takePageSnapshot(pageUuid)
        blockRepository.indentBlock(blockUuid)
        refreshBlocksForPage(blockUuid)
        val after = takePageSnapshot(pageUuid)
        record(
            undo = { restorePageToSnapshot(pageUuid, before); requestEditBlock(blockUuid) },
            redo = { restorePageToSnapshot(pageUuid, after); requestEditBlock(blockUuid) }
        )
    }

    fun outdentBlock(blockUuid: String): Job = scope.launch {
        val pageUuid = getPageUuidForBlock(blockUuid) ?: return@launch
        val before = takePageSnapshot(pageUuid)
        blockRepository.outdentBlock(blockUuid)
        refreshBlocksForPage(blockUuid)
        val after = takePageSnapshot(pageUuid)
        record(
            undo = { restorePageToSnapshot(pageUuid, before); requestEditBlock(blockUuid) },
            redo = { restorePageToSnapshot(pageUuid, after); requestEditBlock(blockUuid) }
        )
    }

    fun moveBlockUp(blockUuid: String): Job = scope.launch {
        val pageUuid = getPageUuidForBlock(blockUuid) ?: return@launch
        val before = takePageSnapshot(pageUuid)
        blockRepository.moveBlockUp(blockUuid)
        refreshBlocksForPage(blockUuid)
        val after = takePageSnapshot(pageUuid)
        record(
            undo = { restorePageToSnapshot(pageUuid, before); requestEditBlock(blockUuid) },
            redo = { restorePageToSnapshot(pageUuid, after); requestEditBlock(blockUuid) }
        )
    }

    fun moveBlockDown(blockUuid: String): Job = scope.launch {
        val pageUuid = getPageUuidForBlock(blockUuid) ?: return@launch
        val before = takePageSnapshot(pageUuid)
        blockRepository.moveBlockDown(blockUuid)
        refreshBlocksForPage(blockUuid)
        val after = takePageSnapshot(pageUuid)
        record(
            undo = { restorePageToSnapshot(pageUuid, before); requestEditBlock(blockUuid) },
            redo = { restorePageToSnapshot(pageUuid, after); requestEditBlock(blockUuid) }
        )
    }

    fun addNewBlock(currentBlockUuid: String): Job = scope.launch {
        val block = blockRepository.getBlockByUuid(currentBlockUuid).first().getOrNull() ?: return@launch
        val pageUuid = block.pageUuid
        val before = takePageSnapshot(pageUuid)
        blockRepository.splitBlock(currentBlockUuid, block.content.length).onSuccess { newBlock ->
            requestEditBlock(newBlock.uuid)
            queueDiskSave(pageUuid)
            val after = takePageSnapshot(pageUuid)
            record(
                undo = { restorePageToSnapshot(pageUuid, before); requestEditBlock(currentBlockUuid) },
                redo = { restorePageToSnapshot(pageUuid, after); requestEditBlock(newBlock.uuid) }
            )
        }
    }

    fun splitBlock(blockUuid: String, cursorPosition: Int): Job = scope.launch {
        val pageUuid = getPageUuidForBlock(blockUuid) ?: return@launch
        val before = takePageSnapshot(pageUuid)
        blockRepository.splitBlock(blockUuid, cursorPosition).onSuccess { newBlock ->
            requestEditBlock(newBlock.uuid)
            queueDiskSave(pageUuid)
            val after = takePageSnapshot(pageUuid)
            record(
                undo = { restorePageToSnapshot(pageUuid, before); requestEditBlock(blockUuid, cursorPosition) },
                redo = { restorePageToSnapshot(pageUuid, after); requestEditBlock(newBlock.uuid) }
            )
        }
    }

    fun addBlockToPage(pageUuid: String): Job = scope.launch {
        val blocksResult = blockRepository.getBlocksForPage(pageUuid).first()
        val blocks = blocksResult.getOrNull() ?: emptyList()

        val topLevelBlocks = blocks.filter { it.parentUuid == null }.sortedBy { it.position }
        val lastBlock = topLevelBlocks.lastOrNull()
        val newPosition = if (lastBlock != null) (lastBlock.position) + 1 else 0

        val now = kotlin.time.Clock.System.now()
        val newBlock = Block(
            uuid = UuidGenerator.generateV7(),
            pageUuid = pageUuid,
            parentUuid = null,
            leftUuid = lastBlock?.uuid,
            content = "",
            level = 0,
            position = newPosition,
            createdAt = now,
            updatedAt = now,
            properties = emptyMap(),
            isLoaded = true
        )
        blockRepository.saveBlock(newBlock)
        requestEditBlock(newBlock.uuid)
        queueDiskSave(pageUuid)
    }

    fun mergeBlock(blockUuid: String): Job = scope.launch {
        val currentBlock = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return@launch
        val pageUuid = currentBlock.pageUuid
        val before = takePageSnapshot(pageUuid)

        val pageBlocks = blockRepository.getBlocksForPage(pageUuid).first().getOrNull() ?: return@launch
        val siblings = pageBlocks
            .filter { it.parentUuid == currentBlock.parentUuid }
            .sortedBy { it.position }

        val currentIndex = siblings.indexOfFirst { it.uuid == blockUuid }

        if (currentIndex > 0) {
            val prevBlock = siblings[currentIndex - 1]
            blockRepository.mergeBlocks(prevBlock.uuid, blockUuid, "").onSuccess {
                requestEditBlock(prevBlock.uuid, prevBlock.content.length)
                queueDiskSave(pageUuid)
                val after = takePageSnapshot(pageUuid)
                record(
                    undo = { restorePageToSnapshot(pageUuid, before); requestEditBlock(blockUuid, 0) },
                    redo = { restorePageToSnapshot(pageUuid, after); requestEditBlock(prevBlock.uuid, prevBlock.content.length) }
                )
            }
        }
    }

    fun handleBackspace(blockUuid: String): Job = scope.launch {
        val currentBlock = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return@launch
        val pageUuid = currentBlock.pageUuid
        val before = takePageSnapshot(pageUuid)

        val pageBlocks = blockRepository.getBlocksForPage(pageUuid).first().getOrNull() ?: return@launch
        val siblings = pageBlocks
            .filter { it.parentUuid == currentBlock.parentUuid }
            .sortedBy { it.position }

        val currentIndex = siblings.indexOfFirst { it.uuid == blockUuid }

        suspend fun afterOp(focusUuid: String, focusPos: Int) {
            requestEditBlock(focusUuid, focusPos)
            queueDiskSave(pageUuid)
            val after = takePageSnapshot(pageUuid)
            record(
                undo = { restorePageToSnapshot(pageUuid, before); requestEditBlock(blockUuid, 0) },
                redo = { restorePageToSnapshot(pageUuid, after); requestEditBlock(focusUuid, focusPos) }
            )
        }

        if (currentIndex > 0) {
            val prevBlock = siblings[currentIndex - 1]
            blockRepository.mergeBlocks(prevBlock.uuid, blockUuid, "").onSuccess {
                afterOp(prevBlock.uuid, prevBlock.content.length)
            }
        } else if (currentBlock.parentUuid != null) {
            val parent = pageBlocks.find { it.uuid == currentBlock.parentUuid }
            if (parent != null) {
                if (currentBlock.content.isEmpty()) {
                    blockRepository.deleteBlock(blockUuid)
                    afterOp(parent.uuid, parent.content.length)
                } else {
                    blockRepository.mergeBlocks(parent.uuid, blockUuid, "").onSuccess {
                        afterOp(parent.uuid, parent.content.length)
                    }
                }
            }
        } else {
            if (currentBlock.content.isEmpty() && siblings.size > 1) {
                val nextBlock = siblings[1]
                blockRepository.deleteBlock(blockUuid)
                afterOp(nextBlock.uuid, 0)
            }
        }
    }

    // ---- Focus navigation ----

    fun focusPreviousBlock(blockUuid: String): Job = scope.launch {
        val currentBlock = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return@launch
        val visibleBlocks = getVisibleBlocksForPage(currentBlock.pageUuid)
        val currentIndex = visibleBlocks.indexOfFirst { it.uuid == blockUuid }
        if (currentIndex > 0) {
            val prevBlock = visibleBlocks[currentIndex - 1]
            requestEditBlock(prevBlock.uuid, prevBlock.content.length)
        }
    }

    fun focusNextBlock(blockUuid: String): Job = scope.launch {
        val currentBlock = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return@launch
        val visibleBlocks = getVisibleBlocksForPage(currentBlock.pageUuid)
        val currentIndex = visibleBlocks.indexOfFirst { it.uuid == blockUuid }
        if (currentIndex >= 0 && currentIndex < visibleBlocks.size - 1) {
            val nextBlock = visibleBlocks[currentIndex + 1]
            requestEditBlock(nextBlock.uuid, 0)
        }
    }

    private fun getVisibleBlocksForPage(pageUuid: String): List<Block> {
        val blocks = _blocks.value[pageUuid] ?: return emptyList()
        val collapsedUuids = _collapsedBlockUuids.value
        val sortedBlocks = BlockSorter.sort(blocks)
        if (collapsedUuids.isEmpty()) return sortedBlocks

        val childrenByParent = blocks.groupBy { it.parentUuid }

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

        val hiddenUuids = collapsedUuids.flatMap { getDescendantUuids(it) }.toSet()
        return sortedBlocks.filter { it.uuid !in hiddenUuids }
    }

    // ---- Page content loading ----

    private val _loadingPageUuids = MutableStateFlow<Set<String>>(emptySet())
    val loadingPageUuids: StateFlow<Set<String>> = _loadingPageUuids.asStateFlow()

    fun loadPageContent(pageUuid: String): Job = scope.launch {
        if (_loadingPageUuids.value.contains(pageUuid)) return@launch

        _loadingPageUuids.update { it + pageUuid }
        try {
            val blocksResult = blockRepository.getBlocksForPage(pageUuid).first()
            val currentBlocks = blocksResult.getOrNull() ?: emptyList()
            val hasUnloadedBlocks = currentBlocks.isNotEmpty() && currentBlocks.any { !it.isLoaded }

            if (hasUnloadedBlocks) {
                graphLoader.loadFullPage(pageUuid)
            }

            // Re-fetch and update after potential reload
            val result = blockRepository.getBlocksForPage(pageUuid).first()
            val blocks = result.getOrNull() ?: emptyList()
            _blocks.update { current ->
                current + (pageUuid to blocks)
            }
        } catch (e: Exception) {
            logger.error("Failed to load page content: $pageUuid", e)
        } finally {
            _loadingPageUuids.update { it - pageUuid }
        }
    }

    fun close() {
        scope.cancel()
    }
}
