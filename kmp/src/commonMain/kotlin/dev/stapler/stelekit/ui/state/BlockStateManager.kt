package dev.stapler.stelekit.ui.state

import dev.stapler.stelekit.db.DatabaseWriteActor
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
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.CancellationException

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
 * User-edit writes are routed through [DatabaseWriteActor] when one is injected, so they
 * queue behind any in-progress Phase-3 bulk load without contending for the raw SQLite write
 * lock. The [DirectRepositoryWrite] opt-in remains for the fallback path (tests, in-memory
 * backend) and for structural operations that still call the repository directly.
 */
@OptIn(DirectRepositoryWrite::class)
class BlockStateManager(
    private val blockRepository: BlockRepository,
    private val graphLoader: GraphLoader,
    // Default scope owns its own lifecycle so callers stored in remember{} don't pass
    // rememberCoroutineScope(), which is cancelled when the composable leaves composition.
    // Tests inject a TestCoroutineScope for deterministic scheduling.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val graphWriter: GraphWriter? = null,
    private val pageRepository: PageRepository? = null,
    private val graphPathProvider: () -> String = { "" },
    private val writeActor: DatabaseWriteActor? = null
) {
    private val logger = Logger("BlockStateManager")
    private val diskWriteDebounce = DebounceManager(scope, 300L)

    // When a DatabaseWriteActor is injected, user-edit writes go through it so they
    // are serialized behind any in-progress Phase-3 bulk load without holding a raw
    // SQLite write lock. Falls back to direct repository calls (tests, in-memory backend).
    private suspend fun writeBlock(block: Block) =
        writeActor?.saveBlock(block) ?: blockRepository.saveBlock(block)

    private suspend fun writeContentOnly(blockUuid: String, content: String) =
        writeActor?.updateBlockContentOnly(blockUuid, content)
            ?: blockRepository.updateBlockContentOnly(blockUuid, content)

    private suspend fun writePropertiesOnly(blockUuid: String, properties: Map<String, String>) =
        writeActor?.updateBlockPropertiesOnly(blockUuid, properties)
            ?: blockRepository.updateBlockPropertiesOnly(blockUuid, properties)

    // ---- Active page sessions ----

    private val _activePageUuids = MutableStateFlow<Set<String>>(emptySet())
    val activePageUuids: StateFlow<Set<String>> = _activePageUuids.asStateFlow()

    // ---- Block state (per-page) ----

    private val _blocks = MutableStateFlow<Map<String, List<Block>>>(emptyMap())
    val blocks: StateFlow<Map<String, List<Block>>> = _blocks.asStateFlow()

    /** Blocks whose content was updated locally but not yet confirmed by DB re-emission. */
    private val dirtyBlocks = mutableMapOf<String, Long>() // blockUuid → local version

    /**
     * UUIDs of blocks that were inserted optimistically into [_blocks] but whose DB write is
     * still in-flight. Prevents reactive re-emissions from dropping the block before the DB
     * confirms it (the block won't appear in incomingBlocks until the write commits).
     *
     * Lifecycle (coroutine-sequential in [addBlockToPage]):
     *   1. UUID added immediately before [blockRepository.saveBlock] is called
     *   2. UUID removed immediately after [blockRepository.saveBlock] returns
     * Once removed, the next [mergeBlocks] call will find the block in [incomingBlocks] and
     * treat it as confirmed. If the save fails the UUID is still removed — the block remains
     * in [_blocks] until the next page reload, at which point it disappears.
     */
    private val pendingNewBlockUuids = java.util.concurrent.CopyOnWriteArraySet<String>()

    /** UUIDs of blocks that have unsaved local edits. Used by ViewModel to protect pages from external overwrites. */
    val dirtyBlockUuids: Set<String> get() = dirtyBlocks.keys.toSet()

    private val observationJobs = mutableMapOf<String, Job>()
    // Delayed-cancellation jobs: unobservePage schedules cancellation after a keepalive window
    // so quick back-navigation reuses the live observation instead of cold-starting.
    private val pendingUnobserve = mutableMapOf<String, Job>()

    // ---- Editing focus ----

    private val _editingBlockUuid = MutableStateFlow<String?>(null)
    val editingBlockUuid: StateFlow<String?> = _editingBlockUuid.asStateFlow()

    private val _editingCursorIndex = MutableStateFlow<Int?>(null)
    val editingCursorIndex: StateFlow<Int?> = _editingCursorIndex.asStateFlow()

    private val _editingSelectionRange = MutableStateFlow<IntRange?>(null)
    val editingSelectionRange: StateFlow<IntRange?> = _editingSelectionRange.asStateFlow()

    private val _collapsedBlockUuids = MutableStateFlow<Set<String>>(emptySet())
    val collapsedBlockUuids: StateFlow<Set<String>> = _collapsedBlockUuids.asStateFlow()

    // ---- Selection state (delegated to BlockSelectionManager) ----

    private val selection = BlockSelectionManager(
        blocksSnapshot = { _blocks.value },
        visibleBlocksForPage = ::getVisibleBlocksForPage
    )
    val selectedBlockUuids: StateFlow<Set<String>> get() = selection.selectedBlockUuids
    val isInSelectionMode: StateFlow<Boolean> get() = selection.isInSelectionMode

    fun enterSelectionMode(uuid: String) = selection.enterSelectionMode(uuid)
    fun toggleBlockSelection(uuid: String) = selection.toggleBlockSelection(uuid)
    fun extendSelectionByOne(up: Boolean) = selection.extendSelectionByOne(up)
    fun extendSelectionTo(uuid: String) = selection.extendSelectionTo(uuid)
    fun selectAll(pageUuid: String) = selection.selectAll(pageUuid)
    fun clearSelection() = selection.clearSelection()
    private fun subtreeDedup(uuids: Set<String>, pageUuid: String) = selection.subtreeDedup(uuids, pageUuid)

    /**
     * Delete all currently selected blocks (and their subtrees) in a single
     * undo-able operation. Clears the selection when done.
     */
    fun deleteSelectedBlocks(): Job = scope.launch {
        val selected = selection.selectedBlockUuids.value
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
        val selected = selection.selectedBlockUuids.value
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

    // ---- Undo/redo (delegated to BlockUndoManager) ----

    private val undoManager = BlockUndoManager(scope)
    val canUndo: StateFlow<Boolean> get() = undoManager.canUndo
    val canRedo: StateFlow<Boolean> get() = undoManager.canRedo

    fun record(undo: suspend () -> Unit, redo: (suspend () -> Unit)? = null) =
        undoManager.record(undo, redo)

    fun undo(): Job = undoManager.undo()
    fun redo(): Job = undoManager.redo()

    // ---- Page observation ----

    /**
     * Start observing blocks for a page. Triggers lazy load if needed.
     * Uses dirty-set merge: incoming blocks only overwrite local state if
     * the block is NOT dirty (i.e., has no unconfirmed local edit).
     */
    fun observePage(pageUuid: String, isContentLoaded: Boolean = true) {
        _activePageUuids.update { it + pageUuid }
        // Cancel pending unobserve on re-navigation within the keepalive window
        pendingUnobserve.remove(pageUuid)?.cancel()
        if (pageUuid in observationJobs) return

        observationJobs[pageUuid] = scope.launch {
            // Skip disk load if blocks are already in the state cache (e.g. re-navigation after unobservePage).
            // unobservePage keeps blocks in _blocks so back-navigation is instant without a disk round-trip.
            val alreadyCached = _blocks.value[pageUuid]?.isNotEmpty() == true
            if (!isContentLoaded && !alreadyCached) {
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
     * Schedule cancellation of the page observation after a 5-second keepalive window.
     *
     * If [observePage] is called again within the window the pending cancellation is cancelled
     * and the existing observation job reused — zero cold-start latency on quick back-navigation.
     * After the window expires the observation job is cancelled and dirty entries are cleared;
     * blocks remain in [_blocks] as a stale cache for instant first-paint on slower re-navigation.
     */
    fun unobservePage(pageUuid: String) {
        _activePageUuids.update { it - pageUuid }
        pendingUnobserve.remove(pageUuid)?.cancel()
        pendingUnobserve[pageUuid] = scope.launch {
            delay(5_000)
            pendingUnobserve.remove(pageUuid)
            observationJobs.remove(pageUuid)?.cancel()
            val blockUuids = _blocks.value[pageUuid]?.map { it.uuid } ?: emptyList()
            blockUuids.forEach { dirtyBlocks.remove(it) }
        }
    }

    /** Evict in-memory caches for [pageUuid] when an external file change fires. */
    fun cacheEvictPage(pageUuid: String) {
        scope.launch {
            blockRepository.cacheEvictPage(pageUuid)
        }
    }

    /**
     * Merge incoming DB blocks with local state using dirty-set semantics.
     * Dirty blocks keep their local version; clean blocks accept the DB version.
     * Pending new blocks (optimistically inserted, DB write still in-flight) are
     * preserved at the end of the list so they don't disappear on reactive re-emissions.
     */
    private fun mergeBlocks(localBlocks: List<Block>, incomingBlocks: List<Block>): List<Block> {
        val merged = incomingBlocks.map { incoming ->
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
        // Append pending new blocks that aren't yet in the DB (won't appear in incomingBlocks
        // until the write commits). Once the write commits, they'll be in incomingBlocks and
        // removed from pendingNewBlockUuids before the next emission.
        val incomingUuids = incomingBlocks.mapTo(HashSet()) { it.uuid }
        val pending = localBlocks.filter { it.uuid in pendingNewBlockUuids && it.uuid !in incomingUuids }
        return if (pending.isEmpty()) merged else merged + pending
    }

    fun blocksForPage(pageUuid: String): List<Block> =
        _blocks.value[pageUuid] ?: emptyList()

    // ---- Editing focus management ----

    fun requestEditBlock(blockUuid: String?, cursorIndex: Int? = null) {
        if (blockUuid == null || blockUuid != _editingBlockUuid.value) _editingSelectionRange.value = null
        _editingBlockUuid.value = blockUuid
        _editingCursorIndex.value = cursorIndex
    }

    fun updateEditingSelection(range: IntRange?) {
        _editingSelectionRange.value = range
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
     * Inserts [[pageName]] at the cursor position for the given block.
     *
     * [overrideCursorIndex] should be captured by the caller *before* any dialog opens
     * (opening a dialog nulls [_editingCursorIndex] via focus-loss → [stopEditingBlock]).
     * When non-null it takes precedence over the stored cursor index.
     */
    fun insertLinkAtCursor(blockUuid: String, pageName: String, overrideCursorIndex: Int? = null) {
        scope.launch {
            val block = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return@launch
            val cursor = overrideCursorIndex ?: _editingCursorIndex.value ?: block.content.length
            val linkText = "[[$pageName]]"
            val safePos = cursor.coerceIn(0, block.content.length)
            val newContent = block.content.substring(0, safePos) + linkText + block.content.substring(safePos)
            val newVersion = block.version + 1
            updateBlockContent(blockUuid, newContent, newVersion)
            requestEditBlock(blockUuid, safePos + linkText.length)
        }
    }

    /**
     * Replaces the text in [selectionStart]..[selectionEnd] with [[pageName]].
     * Falls back to [insertLinkAtCursor] when start >= end (no real selection).
     */
    fun replaceSelectionWithLink(
        blockUuid: String,
        selectionStart: Int,
        selectionEnd: Int,
        pageName: String,
    ) {
        if (selectionStart >= selectionEnd) {
            insertLinkAtCursor(blockUuid, pageName, overrideCursorIndex = selectionStart)
            return
        }
        scope.launch {
            val block = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return@launch
            val safeStart = selectionStart.coerceIn(0, block.content.length)
            val safeEnd = selectionEnd.coerceIn(safeStart, block.content.length)
            val linkText = "[[$pageName]]"
            val newContent = block.content.substring(0, safeStart) + linkText + block.content.substring(safeEnd)
            val newVersion = block.version + 1
            updateBlockContent(blockUuid, newContent, newVersion)
            requestEditBlock(blockUuid, safeStart + linkText.length)
        }
    }

    /**
     * Single entry point for the link picker result: replaces a real selection or
     * falls back to cursor insertion. Callers should capture [selectionRange] and
     * [overrideCursorIndex] **before** the picker dialog opens.
     */
    fun acceptLinkPickerResult(
        blockUuid: String,
        pageName: String,
        selectionRange: IntRange?,
        overrideCursorIndex: Int?,
    ) {
        if (selectionRange != null && selectionRange.first < selectionRange.last) {
            replaceSelectionWithLink(blockUuid, selectionRange.first, selectionRange.last, pageName)
        } else {
            insertLinkAtCursor(blockUuid, pageName, overrideCursorIndex = overrideCursorIndex)
        }
    }

    /**
     * Persist updated properties for a block (e.g. flashcard review state).
     * Saves to DB, updates local in-memory state, and queues a disk write so
     * the markdown file reflects the new `key:: value` property lines.
     */
    fun updateBlockProperties(blockUuid: String, newProperties: Map<String, String>): Job = scope.launch {
        val pageUuid = _blocks.value.entries.find { (_, blocks) -> blocks.any { it.uuid == blockUuid } }?.key
            ?: blockRepository.getBlockByUuid(blockUuid).first().getOrNull()?.pageUuid
            ?: return@launch
        val propsResult = writePropertiesOnly(blockUuid, newProperties)
        if (propsResult.isLeft()) {
            logger.warn("updateBlockProperties: DB write failed for $blockUuid — properties live in-memory only")
        }
        _blocks.update { current ->
            val newBlocks = current.toMutableMap()
            val pageBlocks = newBlocks[pageUuid]?.toMutableList() ?: return@update current
            val idx = pageBlocks.indexOfFirst { it.uuid == blockUuid }
            if (idx >= 0) pageBlocks[idx] = pageBlocks[idx].copy(properties = newProperties)
            newBlocks[pageUuid] = pageBlocks
            newBlocks
        }
        queueDiskSave(pageUuid)
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
        val block = _blocks.value.values.flatten().find { it.uuid == blockUuid }
            ?: blockRepository.getBlockByUuid(blockUuid).first().getOrNull()
            ?: run {
                logger.warn("applyContentChange: block $blockUuid not found — content update dropped")
                return
            }

        // Mark dirty BEFORE saving so the observer merge keeps our version
        dirtyBlocks[blockUuid] = version

        val writeResult = writeContentOnly(blockUuid, content)
        if (writeResult.isLeft()) {
            logger.warn("applyContentChange: DB write failed for $blockUuid — content lives in-memory only")
        }

        val updated = block.copy(content = content, version = version)
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
     * Returns true if a debounced disk write is pending for [pageUuid].
     *
     * Used by [observeExternalFileChanges] to extend conflict protection into the
     * window between DB-save confirmation (dirty cleared) and the disk write
     * actually firing (~300ms later). Without this check, an external file change
     * arriving in that window silently overwrites local content with no dialog.
     */
    suspend fun hasPendingDiskWrite(pageUuid: String): Boolean =
        diskWriteDebounce.hasPending("disk-$pageUuid")

    /**
     * Cancel any pending debounced disk write for [pageUuid] without executing it.
     *
     * Called when a conflict dialog is shown, so the pending auto-save cannot
     * overwrite the disk file that the dialog is offering to restore.
     */
    suspend fun cancelPendingDiskSave(pageUuid: String) {
        diskWriteDebounce.cancel("disk-$pageUuid")
    }

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
        blockRepository.splitBlock(currentBlockUuid, block.content.length).onRight { newBlock ->
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
        blockRepository.splitBlock(blockUuid, cursorPosition).onRight { newBlock ->
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
        // Use in-memory state — avoids a DB round-trip since the page is already observed.
        val blocks = blocksForPage(pageUuid)

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
        // Optimistically add to local state so focus and keyboard land before the DB write
        // completes (the write may be queued behind a Phase-3 batch transaction).
        _blocks.update { current ->
            val updated = current.toMutableMap()
            val pageBlocks = updated[pageUuid]?.toMutableList() ?: mutableListOf()
            pageBlocks.add(newBlock)
            updated[pageUuid] = pageBlocks
            updated
        }
        pendingNewBlockUuids.add(newBlock.uuid)
        requestEditBlock(newBlock.uuid)
        writeBlock(newBlock).onLeft { err ->
            logger.error("addBlockToPage: DB write failed for ${newBlock.uuid}: $err")
            // Roll back the optimistic update only if the block content is unchanged. Comparing
            // content rather than checking dirtyBlocks avoids a race where the user types between
            // the dirtyBlocks.containsKey check and the _blocks.update: if the content has already
            // changed, the user's edits are preserved and the next debounce will retry the write.
            _blocks.update { current ->
                val liveContent = current[pageUuid]?.find { it.uuid == newBlock.uuid }?.content
                if (liveContent == newBlock.content) {
                    val updated = current.toMutableMap()
                    updated[pageUuid] = (updated[pageUuid] ?: emptyList()).filter { it.uuid != newBlock.uuid }
                    updated
                } else {
                    current
                }
            }
        }
        pendingNewBlockUuids.remove(newBlock.uuid)
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
            blockRepository.mergeBlocks(prevBlock.uuid, blockUuid, "").onRight {
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
            blockRepository.mergeBlocks(prevBlock.uuid, blockUuid, "").onRight {
                afterOp(prevBlock.uuid, prevBlock.content.length)
            }
        } else if (currentBlock.parentUuid != null) {
            val parent = pageBlocks.find { it.uuid == currentBlock.parentUuid }
            if (parent != null) {
                if (currentBlock.content.isEmpty()) {
                    blockRepository.deleteBlock(blockUuid)
                    afterOp(parent.uuid, parent.content.length)
                } else {
                    blockRepository.mergeBlocks(parent.uuid, blockUuid, "").onRight {
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
        } catch (e: CancellationException) {
            throw e
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
