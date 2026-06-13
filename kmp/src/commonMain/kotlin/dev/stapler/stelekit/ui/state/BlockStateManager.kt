package dev.stapler.stelekit.ui.state

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
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
import arrow.core.right

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
 * Each direct-repository call site carries a function-level @OptIn(DirectRepositoryWrite::class)
 * so the bypass is explicit and visible at the narrowest possible scope.
 */
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
    private val writeActor: DatabaseWriteActor? = null,
    private val histogramWriter: dev.stapler.stelekit.performance.HistogramWriter? = null
) : BlockEditingPort, BlockStructurePort, BlockSelectionPort, BlockNavigationPort {
    private val logger = Logger("BlockStateManager")
    private val diskWriteDebounce = DebounceManager(scope, 300L)

    // When a DatabaseWriteActor is injected, user-edit writes go through it so they
    // are serialized behind any in-progress Phase-3 bulk load without holding a raw
    // SQLite write lock. Falls back to direct repository calls (tests, in-memory backend).
    // Each helper carries @OptIn(DirectRepositoryWrite::class) at the narrowest scope so the
    // bypass is explicit and visible; the class no longer carries a blanket opt-in.
    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writeBlock(block: Block) =
        writeActor?.saveBlock(block) ?: blockRepository.saveBlock(block)

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writeContentOnly(blockUuid: BlockUuid, content: String) =
        writeActor?.updateBlockContentOnly(blockUuid, content)
            ?: blockRepository.updateBlockContentOnly(blockUuid, content)

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writePropertiesOnly(blockUuid: BlockUuid, properties: Map<String, String>) =
        writeActor?.updateBlockPropertiesOnly(blockUuid, properties)
            ?: blockRepository.updateBlockPropertiesOnly(blockUuid, properties)

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writeSplitBlock(
        blockUuid: BlockUuid,
        cursorPosition: Int,
        newBlockUuid: BlockUuid?,
    ) = writeActor?.splitBlock(blockUuid, cursorPosition, newBlockUuid)
        ?: blockRepository.splitBlock(blockUuid, cursorPosition, newBlockUuid)

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writeMergeBlocks(
        blockUuid: BlockUuid,
        nextBlockUuid: BlockUuid,
        separator: String,
    ) = writeActor?.mergeBlocks(blockUuid, nextBlockUuid, separator)
        ?: blockRepository.mergeBlocks(blockUuid, nextBlockUuid, separator)

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writeDeleteBlockStructural(blockUuid: BlockUuid) =
        writeActor?.deleteBlockStructural(blockUuid)
            ?: blockRepository.deleteBlock(blockUuid)

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writeDeleteBulk(uuids: List<String>, deleteChildren: Boolean) =
        writeActor?.execute { blockRepository.deleteBulk(uuids.map { BlockUuid(it) }, deleteChildren) }
            ?: blockRepository.deleteBulk(uuids.map { BlockUuid(it) }, deleteChildren)

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writeMoveBlock(uuid: BlockUuid, newParentUuid: BlockUuid?, position: Int) =
        writeActor?.execute { blockRepository.moveBlock(uuid, newParentUuid, position) }
            ?: blockRepository.moveBlock(uuid, newParentUuid, position)

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writeIndentBlock(uuid: BlockUuid) =
        writeActor?.execute { blockRepository.indentBlock(uuid) }
            ?: blockRepository.indentBlock(uuid)

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writeOutdentBlock(uuid: BlockUuid) =
        writeActor?.execute { blockRepository.outdentBlock(uuid) }
            ?: blockRepository.outdentBlock(uuid)

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writeMoveBlockUp(uuid: BlockUuid) =
        writeActor?.execute { blockRepository.moveBlockUp(uuid) }
            ?: blockRepository.moveBlockUp(uuid)

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writeMoveBlockDown(uuid: BlockUuid) =
        writeActor?.execute { blockRepository.moveBlockDown(uuid) }
            ?: blockRepository.moveBlockDown(uuid)

    // ---- Active page sessions ----

    private val _activePageUuids = MutableStateFlow<Set<String>>(emptySet())
    val activePageUuids: StateFlow<Set<String>> = _activePageUuids.asStateFlow()

    // ---- Block state (per-page) ----

    private val _blocks = MutableStateFlow<Map<String, List<Block>>>(emptyMap())
    val blocks: StateFlow<Map<String, List<Block>>> = _blocks.asStateFlow()

    /** Blocks whose content was updated locally but not yet confirmed by DB re-emission.
     *  MutableStateFlow<Map> provides thread-safe CAS updates from concurrent Default-dispatcher
     *  coroutines without requiring platform-specific atomics. */
    private val _dirtyBlocks = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * UUIDs of blocks that were inserted optimistically into [_blocks] but whose DB write is
     * still in-flight. Prevents reactive re-emissions from dropping the block before the DB
     * confirms it (the block won't appear in incomingBlocks until the write commits).
     *
     * Type: [MutableStateFlow]<[Set]<[String]>> (not CopyOnWriteArraySet).
     *
     * Lifecycle (coroutine-sequential in [addBlockToPage]):
     *   1. UUID added immediately before [blockRepository.saveBlock] is called
     *   2. UUID removed immediately after [blockRepository.saveBlock] returns
     * Once removed, the next [mergeBlocks] call will find the block in [incomingBlocks] and
     * treat it as confirmed.
     *
     * On DB write failure the block is rolled back from [_blocks] only if the block's content
     * is still equal to what was written (content-equality guard). If the user typed while the
     * write was in-flight the content will have changed, so the block is kept in [_blocks] and
     * the next debounced save will retry the write.
     */
    private val pendingNewBlockUuids = MutableStateFlow<Set<String>>(emptySet())

    /** UUIDs of blocks that have unsaved local edits. Used by ViewModel to protect pages from external overwrites. */
    val dirtyBlockUuids: Set<String> get() = _dirtyBlocks.value.keys

    private val observationJobs = mutableMapOf<String, Job>()
    // Delayed-cancellation jobs: unobservePage schedules cancellation after a keepalive window
    // so quick back-navigation reuses the live observation instead of cold-starting.
    private val pendingUnobserve = mutableMapOf<String, Job>()

    // ---- Editing focus ----

    private val _editingBlockUuid = MutableStateFlow<BlockUuid?>(null)
    override val editingBlockUuid: StateFlow<BlockUuid?> = _editingBlockUuid.asStateFlow()

    private val _editingCursorIndex = MutableStateFlow<Int?>(null)
    override val editingCursorIndex: StateFlow<Int?> = _editingCursorIndex.asStateFlow()

    private val _editingSelectionRange = MutableStateFlow<IntRange?>(null)
    override val editingSelectionRange: StateFlow<IntRange?> = _editingSelectionRange.asStateFlow()

    private val _collapsedBlockUuids = MutableStateFlow<Set<String>>(emptySet())
    override val collapsedBlockUuids: StateFlow<Set<String>> = _collapsedBlockUuids.asStateFlow()

    // ---- Selection state (delegated to BlockSelectionManager) ----

    private val selection = BlockSelectionManager(
        blocksSnapshot = { _blocks.value },
        visibleBlocksForPage = ::getVisibleBlocksForPage
    )
    override val selectedBlockUuids: StateFlow<Set<String>> get() = selection.selectedBlockUuids
    override val isInSelectionMode: StateFlow<Boolean> get() = selection.isInSelectionMode

    override fun enterSelectionMode(uuid: BlockUuid) = selection.enterSelectionMode(uuid.value)
    override fun toggleBlockSelection(uuid: BlockUuid) = selection.toggleBlockSelection(uuid.value)
    override fun extendSelectionByOne(up: Boolean) = selection.extendSelectionByOne(up)
    override fun extendSelectionTo(uuid: BlockUuid) = selection.extendSelectionTo(uuid.value)
    override fun selectAll(pageUuid: PageUuid) = selection.selectAll(pageUuid.value)
    override fun clearSelection() = selection.clearSelection()
    private fun subtreeDedup(uuids: Set<String>, pageUuid: String) = selection.subtreeDedup(uuids, pageUuid)

    /**
     * Delete all currently selected blocks (and their subtrees) in a single
     * undo-able operation. Clears the selection when done.
     */
    override fun deleteSelectedBlocks(): Job = scope.launch {
        val selected = selection.selectedBlockUuids.value
        if (selected.isEmpty()) return@launch
        // Determine page UUID from the first selected block
        val pageUuid = _blocks.value.entries
            .find { (_, blocks) -> blocks.any { it.uuid.value in selected } }
            ?.key ?: return@launch
        val before = takePageSnapshot(pageUuid)
        val toDelete = subtreeDedup(selected, pageUuid)
        writeDeleteBulk(toDelete, deleteChildren = true)
        val refreshed = blockRepository.getBlocksForPage(PageUuid(pageUuid)).first().getOrNull() ?: emptyList()
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
    override fun moveSelectedBlocks(newParentUuid: BlockUuid?, insertAfterUuid: BlockUuid?): Job = scope.launch {
        val selected = selection.selectedBlockUuids.value
        if (selected.isEmpty()) return@launch

        val pageUuid = _blocks.value.entries
            .find { (_, blocks) -> blocks.any { it.uuid.value in selected } }
            ?.key ?: return@launch

        val before = takePageSnapshot(pageUuid)

        // Sort selected UUIDs by their current visual order
        val visible = getVisibleBlocksForPage(pageUuid)
        val sortedSelected = visible
            .filter { it.uuid.value in selected }
            .map { it.uuid.value }

        // Remove descendants of other selected blocks to avoid double-move
        val toMove = subtreeDedup(sortedSelected.toSet(), pageUuid)

        if (toMove.isEmpty()) {
            clearSelection()
            return@launch
        }

        // Compute start position: how many children the target parent already has
        // at the point after insertAfterUuid
        val allBlocks = _blocks.value[pageUuid] ?: emptyList()
        val siblingCount = allBlocks.count { it.parentUuid == newParentUuid?.value }
        val startPosition = if (insertAfterUuid == null) {
            0
        } else {
            val insertAfterBlock = allBlocks.find { it.uuid == insertAfterUuid }
            if (insertAfterBlock != null) (insertAfterBlock.position + 1) else siblingCount
        }

        // Move each block in sorted order, incrementing position for each
        toMove.forEachIndexed { index, uuid ->
            writeMoveBlock(BlockUuid(uuid), newParentUuid, startPosition + index)
        }

        // Refresh state
        val refreshed = blockRepository.getBlocksForPage(PageUuid(pageUuid)).first().getOrNull() ?: emptyList()
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
    override val formatEvents: SharedFlow<FormatAction> = _formatEvents.asSharedFlow()

    override fun requestFormat(action: FormatAction) {
        _formatEvents.tryEmit(action)
    }

    // ---- Undo/redo (delegated to BlockUndoManager) ----

    private val undoManager = BlockUndoManager(scope)
    val canUndo: StateFlow<Boolean> get() = undoManager.canUndo
    val canRedo: StateFlow<Boolean> get() = undoManager.canRedo

    fun record(undo: suspend () -> Unit, redo: (suspend () -> Unit)? = null) =
        undoManager.record(undo, redo)

    override fun undo(): Job = undoManager.undo()
    override fun redo(): Job = undoManager.redo()

    // ---- Page observation ----

    /**
     * Start observing blocks for a page. Triggers lazy load if needed.
     * Uses dirty-set merge: incoming blocks only overwrite local state if
     * the block is NOT dirty (i.e., has no unconfirmed local edit).
     */
    fun observePage(pageUuid: PageUuid, isContentLoaded: Boolean = true) {
        val pageUuidStr = pageUuid.value
        _activePageUuids.update { it + pageUuidStr }
        // Cancel pending unobserve on re-navigation within the keepalive window
        pendingUnobserve.remove(pageUuidStr)?.cancel()
        if (pageUuidStr in observationJobs) return

        observationJobs[pageUuidStr] = scope.launch {
            // Skip disk load if blocks are already in the state cache (e.g. re-navigation after unobservePage).
            // unobservePage keeps blocks in _blocks so back-navigation is instant without a disk round-trip.
            val alreadyCached = _blocks.value[pageUuidStr]?.isNotEmpty() == true
            if (!isContentLoaded && !alreadyCached) {
                _loadingPageUuids.update { it + pageUuidStr }
                try {
                    graphLoader.loadFullPage(pageUuidStr)
                } finally {
                    _loadingPageUuids.update { it - pageUuidStr }
                }
            }

            blockRepository.getBlocksForPage(pageUuid).collect { result ->
                val incomingBlocks = result.getOrNull() ?: emptyList()
                _blocks.update { current ->
                    val localBlocks = current[pageUuidStr] ?: emptyList()
                    val merged = mergeBlocks(localBlocks, incomingBlocks)
                    current + (pageUuidStr to merged)
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
    fun unobservePage(pageUuid: PageUuid) {
        val pageUuidStr = pageUuid.value
        _activePageUuids.update { it - pageUuidStr }
        pendingUnobserve.remove(pageUuidStr)?.cancel()
        pendingUnobserve[pageUuidStr] = scope.launch {
            delay(5_000)
            pendingUnobserve.remove(pageUuidStr)
            observationJobs.remove(pageUuidStr)?.cancel()
            val blockUuids = _blocks.value[pageUuidStr]?.map { it.uuid.value } ?: emptyList()
            _dirtyBlocks.update { m -> m - blockUuids.toSet() }
        }
    }

    /** Evict in-memory caches for [pageUuid] when an external file change fires. */
    fun cacheEvictPage(pageUuid: PageUuid) {
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
            val dirtyVersion = _dirtyBlocks.value[incoming.uuid.value]
            if (dirtyVersion != null && dirtyVersion > incoming.version) {
                // Block has a local edit not yet confirmed — keep local version
                val local = localBlocks.find { it.uuid == incoming.uuid }
                local ?: incoming
            } else {
                // DB version is current — accept it and clear dirty flag
                _dirtyBlocks.update { it - incoming.uuid.value }
                incoming
            }
        }
        // Append pending new blocks that aren't yet in the DB (won't appear in incomingBlocks
        // until the write commits). Once the write commits, they'll be in incomingBlocks and
        // removed from pendingNewBlockUuids before the next emission.
        val incomingUuids = incomingBlocks.mapTo(HashSet()) { it.uuid }
        val pending = localBlocks.filter { it.uuid.value in pendingNewBlockUuids.value && it.uuid !in incomingUuids }
        return if (pending.isEmpty()) merged else merged + pending
    }

    fun blocksForPage(pageUuid: String): List<Block> =
        _blocks.value[pageUuid] ?: emptyList()

    // ---- Editing focus management ----

    override fun requestEditBlock(blockUuid: BlockUuid?, cursorIndex: Int?) {
        if (blockUuid == null || blockUuid != _editingBlockUuid.value) _editingSelectionRange.value = null
        _editingBlockUuid.value = blockUuid
        _editingCursorIndex.value = cursorIndex
    }

    override fun updateEditingSelection(range: IntRange?) {
        _editingSelectionRange.value = range
    }

    /**
     * Stops editing only if [blockUuid] is still the currently-editing block.
     * Safe to call from focus-loss handlers: if focus was already transferred
     * programmatically to a different block, this is a no-op.
     */
    override fun stopEditingBlock(blockUuid: BlockUuid) {
        if (_editingBlockUuid.value == blockUuid) {
            _editingBlockUuid.value = null
            _editingCursorIndex.value = null
        }
    }

    override fun toggleBlockCollapse(blockUuid: BlockUuid) {
        val uuidStr = blockUuid.value
        _collapsedBlockUuids.update { collapsed ->
            if (uuidStr in collapsed) collapsed - uuidStr else collapsed + uuidStr
        }
    }

    // ---- Block content operations ----

    /**
     * Inserts [text] at the cursor position for the given block.
     * Used for image attachment markdown insertion (`![alt](path)`).
     *
     * [overrideCursorIndex] should be captured by the caller *before* any dialog opens.
     * When non-null it takes precedence over the stored cursor index.
     */
    override fun insertTextAtCursor(blockUuid: BlockUuid, text: String, overrideCursorIndex: Int?) {
        scope.launch {
            val block = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return@launch
            val cursor = overrideCursorIndex ?: _editingCursorIndex.value ?: block.content.length
            val safePos = cursor.coerceIn(0, block.content.length)
            val newContent = block.content.substring(0, safePos) + text + block.content.substring(safePos)
            val newVersion = block.version + 1
            updateBlockContent(blockUuid, newContent, newVersion)
            requestEditBlock(blockUuid, safePos + text.length)
        }
    }

    /**
     * Inserts [[pageName]] at the cursor position for the given block.
     *
     * [overrideCursorIndex] should be captured by the caller *before* any dialog opens
     * (opening a dialog nulls [_editingCursorIndex] via focus-loss → [stopEditingBlock]).
     * When non-null it takes precedence over the stored cursor index.
     */
    override fun insertLinkAtCursor(blockUuid: BlockUuid, pageName: String, overrideCursorIndex: Int?) {
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
    override fun replaceSelectionWithLink(
        blockUuid: BlockUuid,
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
    override fun acceptLinkPickerResult(
        blockUuid: BlockUuid,
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
    fun updateBlockProperties(blockUuid: BlockUuid, newProperties: Map<String, String>): Job = scope.launch {
        val uuidStr = blockUuid.value
        val pageUuid = _blocks.value.entries.find { (_, blocks) -> blocks.any { it.uuid == blockUuid } }?.key
            ?: blockRepository.getBlockByUuid(blockUuid).first().getOrNull()?.pageUuid?.value
            ?: return@launch
        val propsResult = writePropertiesOnly(blockUuid, newProperties)
        if (propsResult.isLeft()) {
            logger.warn("updateBlockProperties: DB write failed for $uuidStr — properties live in-memory only")
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
    override fun updateBlockContent(blockUuid: BlockUuid, newContent: String, newVersion: Long): Job = scope.launch {
        val block = _blocks.value.values.flatten().find { it.uuid == blockUuid }
            ?: blockRepository.getBlockByUuid(blockUuid).first().getOrNull()
            ?: return@launch
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
    private suspend fun applyContentChange(blockUuid: BlockUuid, content: String, version: Long) {
        val uuidStr = blockUuid.value
        val block = _blocks.value.values.flatten().find { it.uuid == blockUuid }
            ?: blockRepository.getBlockByUuid(blockUuid).first().getOrNull()
            ?: run {
                logger.warn("applyContentChange: block $uuidStr not found — content update dropped")
                return
            }

        // Mark dirty BEFORE saving so the observer merge keeps our version
        _dirtyBlocks.update { it + (uuidStr to version) }

        val t0 = dev.stapler.stelekit.performance.HistogramWriter.epochMs()
        val writeResult = writeContentOnly(blockUuid, content)
        histogramWriter?.record("editor_input", dev.stapler.stelekit.performance.HistogramWriter.epochMs() - t0)
        if (writeResult.isLeft()) {
            logger.warn("applyContentChange: DB write failed for $uuidStr — content lives in-memory only")
        }

        val updated = block.copy(content = content, version = version)
        _blocks.update { current ->
            val newBlocks = current.toMutableMap()
            val pageBlocks = newBlocks[block.pageUuid.value]?.toMutableList() ?: return@update current
            val idx = pageBlocks.indexOfFirst { it.uuid == blockUuid }
            if (idx >= 0) pageBlocks[idx] = updated
            newBlocks[block.pageUuid.value] = pageBlocks
            newBlocks
        }

        // Queue debounced disk write
        queueDiskSave(block.pageUuid.value)
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
            val page = pageRepository.getPageByUuid(PageUuid(pageUuid)).first().getOrNull() ?: return@debounce
            // Use local optimistic state, not DB, to get the latest content
            val blocks = _blocks.value[pageUuid] ?: blockRepository.getBlocksForPage(PageUuid(pageUuid)).first().getOrNull() ?: emptyList()
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
     * Covers two sequential windows:
     * 1. BlockStateManager's own 300ms debounce (diskWriteDebounce is pending).
     * 2. GraphWriter's 500ms debounce (graphWriter.hasPendingForPage is true) — the
     *    BlockStateManager entry is already gone but the write hasn't landed on disk.
     *
     * Both windows must be covered so an external change arriving in either cannot
     * silently overwrite local content.
     */
    suspend fun hasPendingDiskWrite(pageUuid: String): Boolean =
        diskWriteDebounce.hasPending("disk-$pageUuid") ||
        (graphWriter?.hasPendingForPage(PageUuid(pageUuid)) == true)

    /**
     * Returns true if the [DatabaseWriteActor] has pending writes (structural ops in queue
     * or in-flight). Returns false when no actor is configured (in-memory/test path).
     *
     * Used by conflict detection: an external file change arriving while a split/merge is
     * in the actor queue must trigger the conflict dialog rather than silently overwriting
     * local data.
     */
    val hasActorPendingWrites: Boolean
        get() = writeActor?.hasPendingWrites ?: false

    /**
     * Cancel any pending debounced disk write for [pageUuid] without executing it.
     *
     * Cancels both the BlockStateManager debounce AND any queued GraphWriter job so
     * neither window can overwrite the disk content after a conflict dialog is shown.
     */
    suspend fun cancelPendingDiskSave(pageUuid: String) {
        diskWriteDebounce.cancel("disk-$pageUuid")
        graphWriter?.cancelPendingForPage(PageUuid(pageUuid))
    }

    /**
     * Public: write [pageUuid] to disk immediately (no debounce).
     * Used by StelekitViewModel for journal initialisation and conflict resolution.
     */
    suspend fun savePageNow(pageUuid: String) {
        if (graphWriter == null || pageRepository == null) return
        val graphPath = graphPathProvider()
        if (graphPath.isEmpty()) return
        val page = pageRepository.getPageByUuid(PageUuid(pageUuid)).first().getOrNull() ?: return
        // Use local blocks only if non-empty. An empty list may be transient (the brief window
        // between deleteBlocksForPage and saveBlocks in parseAndSavePage), so fall back to the DB
        // to avoid writing empty content to disk.
        val localBlocks = _blocks.value[pageUuid]
        val blocks = if (localBlocks?.isNotEmpty() == true) {
            localBlocks
        } else {
            blockRepository.getBlocksForPage(PageUuid(pageUuid)).first().getOrNull() ?: emptyList()
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

    private suspend fun getPageUuidForBlock(blockUuid: BlockUuid): String? {
        return _blocks.value.entries
            .find { (_, blocks) -> blocks.any { it.uuid == blockUuid } }
            ?.key
            ?: blockRepository.getBlockByUuid(blockUuid).first().getOrNull()?.pageUuid?.value
    }

    private suspend fun takePageSnapshot(pageUuid: String): List<Block> =
        blockRepository.getBlocksForPage(PageUuid(pageUuid)).first().getOrNull() ?: emptyList()

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun restorePageToSnapshot(pageUuid: String, snapshot: List<Block>) {
        val current = blockRepository.getBlocksForPage(PageUuid(pageUuid)).first().getOrNull() ?: return
        val snapshotUuids = snapshot.map { it.uuid }.toSet()
        val currentUuids = current.map { it.uuid }.toSet()
        val toDelete = currentUuids - snapshotUuids
        // Route the delete+save pair through the actor so it doesn't race with queued content saves.
        var success = true
        writeActor?.execute {
            for (uuid in toDelete) {
                blockRepository.deleteBlock(uuid, deleteChildren = false)
                    .onLeft { logger.error("restorePageToSnapshot: deleteBlock failed: $it"); success = false }
            }
            if (success) {
                blockRepository.saveBlocks(snapshot)
                    .onLeft { logger.error("restorePageToSnapshot: saveBlocks failed: $it"); success = false }
            }
            Unit.right()
        } ?: run {
            for (uuid in toDelete) {
                blockRepository.deleteBlock(uuid, deleteChildren = false)
                    .onLeft { logger.error("restorePageToSnapshot: deleteBlock failed: $it"); success = false }
            }
            if (success) {
                blockRepository.saveBlocks(snapshot)
                    .onLeft { logger.error("restorePageToSnapshot: saveBlocks failed: $it"); success = false }
            }
        }
        if (!success) return
        _blocks.update { state ->
            val newBlocks = state.toMutableMap()
            newBlocks[pageUuid] = snapshot
            newBlocks
        }
        queueDiskSave(pageUuid)
    }

    private suspend fun refreshBlocksForPage(blockUuid: BlockUuid) {
        val pageUuid = _blocks.value.entries
            .find { (_, blocks) -> blocks.any { it.uuid == blockUuid } }
            ?.key ?: return

        val pageBlocks = blockRepository.getBlocksForPage(PageUuid(pageUuid)).first().getOrNull() ?: return
        _blocks.update { state ->
            val newBlocks = state.toMutableMap()
            newBlocks[pageUuid] = pageBlocks
            newBlocks
        }

        queueDiskSave(pageUuid)
    }

    override fun indentBlock(blockUuid: BlockUuid): Job = scope.launch {
        val pageUuid = getPageUuidForBlock(blockUuid) ?: return@launch
        val before = takePageSnapshot(pageUuid)
        writeIndentBlock(blockUuid)
        refreshBlocksForPage(blockUuid)
        val after = takePageSnapshot(pageUuid)
        record(
            undo = { restorePageToSnapshot(pageUuid, before); requestEditBlock(blockUuid) },
            redo = { restorePageToSnapshot(pageUuid, after); requestEditBlock(blockUuid) }
        )
    }

    override fun outdentBlock(blockUuid: BlockUuid): Job = scope.launch {
        val pageUuid = getPageUuidForBlock(blockUuid) ?: return@launch
        val before = takePageSnapshot(pageUuid)
        writeOutdentBlock(blockUuid)
        refreshBlocksForPage(blockUuid)
        val after = takePageSnapshot(pageUuid)
        record(
            undo = { restorePageToSnapshot(pageUuid, before); requestEditBlock(blockUuid) },
            redo = { restorePageToSnapshot(pageUuid, after); requestEditBlock(blockUuid) }
        )
    }

    override fun moveBlockUp(blockUuid: BlockUuid): Job = scope.launch {
        val pageUuid = getPageUuidForBlock(blockUuid) ?: return@launch
        val before = takePageSnapshot(pageUuid)
        writeMoveBlockUp(blockUuid)
        refreshBlocksForPage(blockUuid)
        val after = takePageSnapshot(pageUuid)
        record(
            undo = { restorePageToSnapshot(pageUuid, before); requestEditBlock(blockUuid) },
            redo = { restorePageToSnapshot(pageUuid, after); requestEditBlock(blockUuid) }
        )
    }

    override fun moveBlockDown(blockUuid: BlockUuid): Job = scope.launch {
        val pageUuid = getPageUuidForBlock(blockUuid) ?: return@launch
        val before = takePageSnapshot(pageUuid)
        writeMoveBlockDown(blockUuid)
        refreshBlocksForPage(blockUuid)
        val after = takePageSnapshot(pageUuid)
        record(
            undo = { restorePageToSnapshot(pageUuid, before); requestEditBlock(blockUuid) },
            redo = { restorePageToSnapshot(pageUuid, after); requestEditBlock(blockUuid) }
        )
    }

    override fun addNewBlock(currentBlockUuid: BlockUuid): Job = scope.launch {
        val sourceBlock = _blocks.value.values.flatten().find { it.uuid == currentBlockUuid }
            ?: blockRepository.getBlockByUuid(currentBlockUuid).first().getOrNull()
            ?: return@launch
        val pageUuidStr = sourceBlock.pageUuid.value
        val before = takePageSnapshot(pageUuidStr)
        val cursorPosition = sourceBlock.content.length

        // Optimistic: insert empty new block in-memory and move focus immediately
        val expectedNewUuid = UuidGenerator.generateV7()
        val expectedNewBlockUuid = BlockUuid(expectedNewUuid)
        val now = kotlin.time.Clock.System.now()
        val optimisticNew = sourceBlock.copy(
            uuid = expectedNewBlockUuid,
            content = "",
            position = sourceBlock.position + 1,
            leftUuid = currentBlockUuid.value,
            createdAt = now,
            updatedAt = now,
        )
        _blocks.update { state ->
            val pageBlocks = state[pageUuidStr]?.toMutableList() ?: return@update state
            val idx = pageBlocks.indexOfFirst { it.uuid == currentBlockUuid }
            if (idx >= 0) pageBlocks.add(idx + 1, optimisticNew)
            state + (pageUuidStr to pageBlocks)
        }
        pendingNewBlockUuids.update { it + expectedNewUuid }
        requestEditBlock(expectedNewBlockUuid)   // focus moves here, before DB

        writeSplitBlock(currentBlockUuid, cursorPosition, expectedNewBlockUuid).onRight { newBlock ->
            pendingNewBlockUuids.update { it - expectedNewUuid }
            // Repository was given expectedNewUuid — UUIDs always match; no correction needed.
            queueDiskSave(pageUuidStr)
            val after = takePageSnapshot(pageUuidStr)
            record(
                undo = { restorePageToSnapshot(pageUuidStr, before); requestEditBlock(currentBlockUuid) },
                redo = { restorePageToSnapshot(pageUuidStr, after); requestEditBlock(newBlock.uuid) }
            )
        }.onLeft { err ->
            logger.error("addNewBlock: DB write failed for $currentBlockUuid: $err")
            pendingNewBlockUuids.update { it - expectedNewUuid }
            // Roll back optimistic update
            _blocks.update { state ->
                val pageBlocks = state[pageUuidStr]?.toMutableList() ?: return@update state
                pageBlocks.removeAll { it.uuid == expectedNewBlockUuid }
                state + (pageUuidStr to pageBlocks)
            }
            requestEditBlock(currentBlockUuid, cursorPosition)
        }
    }

    override fun splitBlock(blockUuid: BlockUuid, cursorPosition: Int): Job = scope.launch {
        val pageUuid = getPageUuidForBlock(blockUuid) ?: return@launch
        val before = takePageSnapshot(pageUuid)

        // Optimistic: split _blocks in-memory and move focus immediately
        val sourceBlock = _blocks.value[pageUuid]?.find { it.uuid == blockUuid } ?: return@launch
        val clampedCursor = cursorPosition.coerceIn(0, sourceBlock.content.length)
        val firstPart = sourceBlock.content.substring(0, clampedCursor).trim()
        val secondPart = sourceBlock.content.substring(clampedCursor).trim()
        val expectedNewUuid = UuidGenerator.generateV7()
        val expectedNewBlockUuid = BlockUuid(expectedNewUuid)
        val now = kotlin.time.Clock.System.now()
        val optimisticNew = sourceBlock.copy(
            uuid = expectedNewBlockUuid,
            content = secondPart,
            position = sourceBlock.position + 1,
            leftUuid = blockUuid.value,
            createdAt = now,
            updatedAt = now,
        )
        _blocks.update { state ->
            val pageBlocks = state[pageUuid]?.toMutableList() ?: return@update state
            val idx = pageBlocks.indexOfFirst { it.uuid == blockUuid }
            if (idx >= 0) {
                pageBlocks[idx] = pageBlocks[idx].copy(content = firstPart)
                pageBlocks.add(idx + 1, optimisticNew)
            }
            state + (pageUuid to pageBlocks)
        }
        pendingNewBlockUuids.update { it + expectedNewUuid }
        requestEditBlock(expectedNewBlockUuid)   // focus moves here, before DB

        writeSplitBlock(blockUuid, clampedCursor, expectedNewBlockUuid).onRight { newBlock ->
            pendingNewBlockUuids.update { it - expectedNewUuid }
            // Repository was given expectedNewUuid — UUIDs always match; no correction needed.
            queueDiskSave(pageUuid)
            val after = takePageSnapshot(pageUuid)
            record(
                undo = { restorePageToSnapshot(pageUuid, before); requestEditBlock(blockUuid, clampedCursor) },
                redo = { restorePageToSnapshot(pageUuid, after); requestEditBlock(newBlock.uuid) }
            )
        }.onLeft { err ->
            logger.error("splitBlock: DB write failed for $blockUuid: $err")
            pendingNewBlockUuids.update { it - expectedNewUuid }
            // Roll back optimistic update
            _blocks.update { state ->
                val pageBlocks = state[pageUuid]?.toMutableList() ?: return@update state
                pageBlocks.removeAll { it.uuid == expectedNewBlockUuid }
                val idx = pageBlocks.indexOfFirst { it.uuid == blockUuid }
                if (idx >= 0) pageBlocks[idx] = pageBlocks[idx].copy(content = sourceBlock.content)
                state + (pageUuid to pageBlocks)
            }
            requestEditBlock(blockUuid, clampedCursor)
        }
    }

    override fun addBlockToPage(pageUuid: PageUuid): Job = scope.launch {
        val pageUuidStr = pageUuid.value
        // Use in-memory state — avoids a DB round-trip since the page is already observed.
        val blocks = blocksForPage(pageUuidStr)

        val topLevelBlocks = blocks.filter { it.parentUuid == null }.sortedBy { it.position }
        val lastBlock = topLevelBlocks.lastOrNull()
        val newPosition = if (lastBlock != null) (lastBlock.position) + 1 else 0

        val now = kotlin.time.Clock.System.now()
        val newBlock = Block(
            uuid = BlockUuid(UuidGenerator.generateV7()),
            pageUuid = pageUuid,
            parentUuid = null,
            leftUuid = lastBlock?.uuid?.value,
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
            val pageBlocks = updated[pageUuidStr]?.toMutableList() ?: mutableListOf()
            pageBlocks.add(newBlock)
            updated[pageUuidStr] = pageBlocks
            updated
        }
        pendingNewBlockUuids.update { it + newBlock.uuid.value }
        requestEditBlock(newBlock.uuid)
        writeBlock(newBlock).onLeft { err ->
            logger.error("addBlockToPage: DB write failed for ${newBlock.uuid}: $err")
            // Roll back the optimistic update only if the block content is unchanged. Comparing
            // content rather than checking dirtyBlocks avoids a race where the user types between
            // the dirtyBlocks.containsKey check and the _blocks.update: if the content has already
            // changed, the user's edits are preserved and the next debounce will retry the write.
            _blocks.update { current ->
                val liveContent = current[pageUuidStr]?.find { it.uuid == newBlock.uuid }?.content
                if (liveContent == newBlock.content) {
                    val updated = current.toMutableMap()
                    updated[pageUuidStr] = (updated[pageUuidStr] ?: emptyList()).filter { it.uuid != newBlock.uuid }
                    updated
                } else {
                    current
                }
            }
        }
        pendingNewBlockUuids.update { it - newBlock.uuid.value }
        queueDiskSave(pageUuidStr)
    }

    /**
     * Adds a new block at the end of the page with the given initial [content].
     *
     * Unlike [addBlockToPage] (which starts with empty content and focuses the new block),
     * this variant writes the content immediately without requesting edit focus.
     * Intended for programmatic insertion such as drag-and-drop image attachment.
     */
    fun addBlockWithContent(pageUuid: PageUuid, content: String): Job = scope.launch {
        val pageUuidStr = pageUuid.value
        val blocks = blocksForPage(pageUuidStr)
        val topLevelBlocks = blocks.filter { it.parentUuid == null }.sortedBy { it.position }
        val lastBlock = topLevelBlocks.lastOrNull()
        val newPosition = if (lastBlock != null) (lastBlock.position) + 1 else 0

        val now = kotlin.time.Clock.System.now()
        val newBlock = Block(
            uuid = BlockUuid(UuidGenerator.generateV7()),
            pageUuid = pageUuid,
            parentUuid = null,
            leftUuid = lastBlock?.uuid?.value,
            content = content,
            level = 0,
            position = newPosition,
            createdAt = now,
            updatedAt = now,
            properties = emptyMap(),
            isLoaded = true
        )
        _blocks.update { current ->
            val updated = current.toMutableMap()
            val pageBlocks = updated[pageUuidStr]?.toMutableList() ?: mutableListOf()
            pageBlocks.add(newBlock)
            updated[pageUuidStr] = pageBlocks
            updated
        }
        writeBlock(newBlock).onLeft { err ->
            logger.error("addBlockWithContent: DB write failed for ${newBlock.uuid}: $err")
            _blocks.update { current ->
                val liveContent = current[pageUuidStr]?.find { it.uuid == newBlock.uuid }?.content
                if (liveContent == newBlock.content) {
                    val updated = current.toMutableMap()
                    updated[pageUuidStr] = (updated[pageUuidStr] ?: emptyList()).filter { it.uuid != newBlock.uuid }
                    updated
                } else {
                    current
                }
            }
        }
        queueDiskSave(pageUuidStr)
    }

    override fun mergeBlock(blockUuid: BlockUuid): Job = scope.launch {
        val currentBlock = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return@launch
        val pageUuidStr = currentBlock.pageUuid.value
        val before = takePageSnapshot(pageUuidStr)

        val pageBlocks = blockRepository.getBlocksForPage(currentBlock.pageUuid).first().getOrNull() ?: return@launch
        val siblings = pageBlocks
            .filter { it.parentUuid == currentBlock.parentUuid }
            .sortedBy { it.position }

        val currentIndex = siblings.indexOfFirst { it.uuid == blockUuid }

        if (currentIndex > 0) {
            val prevBlock = siblings[currentIndex - 1]
            // Snapshot pre-merge focus so the rollback path can restore it precisely.
            val preMergeEditUuid = _editingBlockUuid.value
            val preMergeEditCursor = _editingCursorIndex.value
            // Move focus before the DB round-trip so keyboard lands immediately
            requestEditBlock(prevBlock.uuid, prevBlock.content.length)
            writeMergeBlocks(prevBlock.uuid, blockUuid, "").onRight {
                queueDiskSave(pageUuidStr)
                val after = takePageSnapshot(pageUuidStr)
                record(
                    undo = { restorePageToSnapshot(pageUuidStr, before); requestEditBlock(blockUuid, 0) },
                    redo = { restorePageToSnapshot(pageUuidStr, after); requestEditBlock(prevBlock.uuid, prevBlock.content.length) }
                )
            }.onLeft { err ->
                logger.error("mergeBlock: DB write failed for $blockUuid: $err")
                // Restore the exact focus state that existed before the optimistic move.
                requestEditBlock(preMergeEditUuid, preMergeEditCursor)
            }
        }
    }

    override fun handleBackspace(blockUuid: BlockUuid): Job = scope.launch {
        val currentBlock = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return@launch
        val pageUuidStr = currentBlock.pageUuid.value
        val before = takePageSnapshot(pageUuidStr)

        val pageBlocks = blockRepository.getBlocksForPage(currentBlock.pageUuid).first().getOrNull() ?: return@launch
        val siblings = pageBlocks
            .filter { it.parentUuid == currentBlock.parentUuid }
            .sortedBy { it.position }

        val currentIndex = siblings.indexOfFirst { it.uuid == blockUuid }

        suspend fun afterOp(focusUuid: BlockUuid, focusPos: Int) {
            queueDiskSave(pageUuidStr)
            val after = takePageSnapshot(pageUuidStr)
            record(
                undo = { restorePageToSnapshot(pageUuidStr, before); requestEditBlock(blockUuid, 0) },
                redo = { restorePageToSnapshot(pageUuidStr, after); requestEditBlock(focusUuid, focusPos) }
            )
        }

        if (currentIndex > 0) {
            val prevBlock = siblings[currentIndex - 1]
            // Move focus before the DB round-trip so keyboard lands immediately
            requestEditBlock(prevBlock.uuid, prevBlock.content.length)
            writeMergeBlocks(prevBlock.uuid, blockUuid, "").onRight {
                afterOp(prevBlock.uuid, prevBlock.content.length)
            }.onLeft { err ->
                logger.error("handleBackspace: DB merge failed for $blockUuid: $err")
                requestEditBlock(blockUuid, 0)
            }
        } else if (currentBlock.parentUuid != null) {
            val parent = pageBlocks.find { it.uuid.value == currentBlock.parentUuid }
            if (parent != null) {
                if (currentBlock.content.isEmpty()) {
                    // Move focus before the DB round-trip
                    requestEditBlock(parent.uuid, parent.content.length)
                    val result = writeDeleteBlockStructural(blockUuid)
                    result.onRight {
                        afterOp(parent.uuid, parent.content.length)
                    }.onLeft { err ->
                        logger.error("handleBackspace: DB delete failed for $blockUuid: $err")
                        requestEditBlock(blockUuid, 0)
                    }
                } else {
                    // Move focus before the DB round-trip
                    requestEditBlock(parent.uuid, parent.content.length)
                    writeMergeBlocks(parent.uuid, blockUuid, "").onRight {
                        afterOp(parent.uuid, parent.content.length)
                    }.onLeft { err ->
                        logger.error("handleBackspace: DB merge failed for $blockUuid: $err")
                        requestEditBlock(blockUuid, 0)
                    }
                }
            }
        } else {
            if (currentBlock.content.isEmpty() && siblings.size > 1) {
                val nextBlock = siblings[1]
                // Move focus before the DB round-trip
                requestEditBlock(nextBlock.uuid, 0)
                val result = writeDeleteBlockStructural(blockUuid)
                result.onRight {
                    afterOp(nextBlock.uuid, 0)
                }.onLeft { err ->
                    logger.error("handleBackspace: DB delete failed for $blockUuid: $err")
                    requestEditBlock(blockUuid, 0)
                }
            }
        }
    }

    // ---- Focus navigation ----

    override fun focusPreviousBlock(blockUuid: BlockUuid): Job = scope.launch {
        val currentBlock = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return@launch
        val visibleBlocks = getVisibleBlocksForPage(currentBlock.pageUuid.value)
        val currentIndex = visibleBlocks.indexOfFirst { it.uuid == blockUuid }
        if (currentIndex > 0) {
            val prevBlock = visibleBlocks[currentIndex - 1]
            requestEditBlock(prevBlock.uuid, prevBlock.content.length)
        }
    }

    override fun focusNextBlock(blockUuid: BlockUuid): Job = scope.launch {
        val currentBlock = blockRepository.getBlockByUuid(blockUuid).first().getOrNull() ?: return@launch
        val visibleBlocks = getVisibleBlocksForPage(currentBlock.pageUuid.value)
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
                    descendants.add(child.uuid.value)
                    queue.add(child.uuid.value)
                }
            }
            return descendants
        }

        val hiddenUuids = collapsedUuids.flatMap { getDescendantUuids(it) }.toSet()
        return sortedBlocks.filter { it.uuid.value !in hiddenUuids }
    }

    // ---- Page content loading ----

    private val _loadingPageUuids = MutableStateFlow<Set<String>>(emptySet())
    val loadingPageUuids: StateFlow<Set<String>> = _loadingPageUuids.asStateFlow()

    fun loadPageContent(pageUuid: PageUuid): Job = scope.launch {
        val pageUuidStr = pageUuid.value
        if (_loadingPageUuids.value.contains(pageUuidStr)) return@launch

        _loadingPageUuids.update { it + pageUuidStr }
        try {
            val blocksResult = blockRepository.getBlocksForPage(pageUuid).first()
            val currentBlocks = blocksResult.getOrNull() ?: emptyList()
            val hasUnloadedBlocks = currentBlocks.isNotEmpty() && currentBlocks.any { !it.isLoaded }

            if (hasUnloadedBlocks) {
                graphLoader.loadFullPage(pageUuidStr)
            }

            // Re-fetch and update after potential reload
            val result = blockRepository.getBlocksForPage(pageUuid).first()
            val blocks = result.getOrNull() ?: emptyList()
            _blocks.update { current ->
                current + (pageUuidStr to blocks)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to load page content: $pageUuidStr", e)
        } finally {
            _loadingPageUuids.update { it - pageUuidStr }
        }
    }

    fun close() {
        scope.cancel()
    }
}
