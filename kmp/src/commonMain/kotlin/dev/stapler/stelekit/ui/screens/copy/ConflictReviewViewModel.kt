// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import arrow.core.Either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.MergePropertyKeys
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.MergeConflictEntry
import dev.stapler.stelekit.repository.PropertyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One flagged block as the review list shows it. [block] is kept so actions need no re-read. */
data class ConflictRow(
    val block: Block,
    val pageName: String,
    val originalText: String?,
    val sourceGraph: String,
) {
    val key: String get() = block.uuid.value
    val pageUuid: PageUuid get() = block.pageUuid
    val copiedText: String get() = block.content
}

enum class ConflictAction { MarkResolved, Remove }

/** [message] stays on screen with a Retry that re-runs [action] on [rowKey]. */
data class ConflictActionError(val message: String, val action: ConflictAction, val rowKey: String)

/** Offer to restore a removed block for a few seconds; [message] repeats the "may return" warning. */
data class ConflictUndoOffer(val message: String, internal val snapshot: List<Block>, internal val pageUuid: PageUuid)

data class ConflictReviewState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val rows: List<ConflictRow> = emptyList(),
    val hasMore: Boolean = false,
    val confirmRemove: ConflictRow? = null,
    val actionError: ConflictActionError? = null,
    val undo: ConflictUndoOffer? = null,
    /** Bumped after each row leaves the list so the screen can move focus to [focusIndex]. */
    val focusToken: Int = 0,
    val focusIndex: Int = 0,
)

/**
 * Backs the conflict review list. Reads are keyset pages of at most [PAGE_SIZE] rows; writes go through
 * [writeActor], then [persistPage] rewrites the page file so the flag leaves disk too.
 */
class ConflictReviewViewModel(
    private val propertyRepository: PropertyRepository,
    private val blockRepository: BlockRepository,
    private val writeActor: DatabaseWriteActor,
    private val persistPage: suspend (PageUuid) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e ->
            _state.update { it.copy(loading = false, loadError = e.message ?: "Unexpected error") }
        },
    )
    private val _state = MutableStateFlow(ConflictReviewState())
    val state: StateFlow<ConflictReviewState> = _state.asStateFlow()

    private var cursor: String? = null
    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        cursor = null
        _state.update { ConflictReviewState(loading = true) }
        loadJob?.cancel()
        loadJob = scope.launch {
            fetchPage().fold(
                { e -> _state.update { it.copy(loading = false, loadError = e.message) } },
                { page -> _state.update { it.copy(loading = false, rows = page.rows, hasMore = page.hasMore) } },
            )
        }
    }

    fun loadMore() {
        if (_state.value.loading || !_state.value.hasMore) return
        loadJob = scope.launch {
            fetchPage().fold(
                { e -> _state.update { it.copy(hasMore = false, loadError = e.message) } },
                { page -> _state.update { it.copy(rows = it.rows + page.rows, hasMore = page.hasMore) } },
            )
        }
    }

    private class ConflictPage(val rows: List<ConflictRow>, val hasMore: Boolean)

    private suspend fun fetchPage(): Either<DomainError, ConflictPage> =
        propertyRepository.getMergeConflicts(cursor, PAGE_SIZE).map { entries ->
            entries.lastOrNull()?.let { cursor = it.block.uuid.value }
            ConflictPage(entries.map { it.toRow() }, hasMore = entries.size >= PAGE_SIZE)
        }

    private fun MergeConflictEntry.toRow() = ConflictRow(
        block = block,
        pageName = pageName,
        originalText = original?.content,
        sourceGraph = block.properties[MergePropertyKeys.CONFLICT_SOURCE].orEmpty(),
    )

    fun markResolved(rowKey: String) {
        val row = rowFor(rowKey) ?: return
        scope.launch {
            val kept = row.block.properties - MergePropertyKeys.CONFLICT
            val result = writeActor.updateBlockPropertiesOnly(row.block.uuid, kept, row.pageUuid)
            finish(row, ConflictAction.MarkResolved, result, undo = null)
        }
    }

    fun requestRemove(rowKey: String) {
        _state.update { s -> s.copy(confirmRemove = s.rows.firstOrNull { it.key == rowKey }) }
    }

    fun cancelRemove() = _state.update { it.copy(confirmRemove = null) }

    fun confirmRemove() {
        val row = _state.value.confirmRemove ?: return
        _state.update { it.copy(confirmRemove = null) }
        remove(row)
    }

    fun retryAction() {
        val err = _state.value.actionError ?: return
        _state.update { it.copy(actionError = null) }
        when (err.action) {
            ConflictAction.MarkResolved -> markResolved(err.rowKey)
            ConflictAction.Remove -> rowFor(err.rowKey)?.let(::remove)
        }
    }

    private fun remove(row: ConflictRow) {
        scope.launch {
            val snapshot = blockRepository.getBlockHierarchy(row.block.uuid).first().getOrNull()
                ?.map { it.block }.orEmpty().ifEmpty { listOf(row.block) }
            val result = writeActor.deleteBlock(row.block.uuid, row.pageUuid)
            val offer = ConflictUndoOffer(removedMessage(row.sourceGraph), snapshot, row.pageUuid)
            finish(row, ConflictAction.Remove, result, undo = offer)
        }
    }

    fun undoRemove() {
        val offer = _state.value.undo ?: return
        _state.update { it.copy(undo = null) }
        scope.launch {
            val restored = writeActor.saveBlocks(offer.snapshot)
            if (restored.isRight()) {
                persistPageQuietly(offer.pageUuid)
                load()
            } else {
                _state.update { it.copy(loadError = "Could not restore the block. Try again.") }
            }
        }
    }

    fun dismissUndo() = _state.update { it.copy(undo = null) }

    fun dismissActionError() = _state.update { it.copy(actionError = null) }

    private fun rowFor(key: String) = _state.value.rows.firstOrNull { it.key == key }

    private suspend fun persistPageQuietly(pageUuid: PageUuid) {
        try {
            persistPage(pageUuid)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The DB change stands; the next save of this page rewrites the file.
        }
    }

    private suspend fun finish(row: ConflictRow, action: ConflictAction, result: Either<DomainError, Unit>, undo: ConflictUndoOffer?) {
        if (result.isLeft()) {
            val verb = if (action == ConflictAction.MarkResolved) "mark this conflict resolved" else "remove this block"
            _state.update { it.copy(actionError = ConflictActionError("Could not $verb on ${row.pageName}.", action, row.key)) }
            return
        }
        persistPageQuietly(row.pageUuid)
        _state.update { s ->
            val idx = s.rows.indexOfFirst { it.key == row.key }.coerceAtLeast(0)
            s.copy(rows = s.rows.filterNot { it.key == row.key }, undo = undo, actionError = null, focusToken = s.focusToken + 1, focusIndex = idx)
        }
        if (_state.value.rows.isEmpty() && _state.value.hasMore) loadMore()
    }

    fun close() = scope.cancel()

    companion object {
        const val PAGE_SIZE = PropertyRepository.MAX_CONFLICT_PAGE

        fun removedMessage(sourceGraph: String): String =
            "Block removed. It may return on the next copy from ${sourceName(sourceGraph)}."

        fun removeConfirmation(sourceGraph: String): String =
            "Remove this block? It will come back, flagged again, if you copy this page from ${sourceName(sourceGraph)} again. " +
                "To keep it from coming back, choose Mark resolved instead."

        private fun sourceName(sourceGraph: String) = sourceGraph.ifBlank { "the source graph" }
    }
}

/** Rewrites a page's file from the DB through the normal GraphWriter save path (as `BlockStateManager.savePageNow`). */
fun conflictPagePersister(
    pageRepository: PageRepository,
    blockRepository: BlockRepository,
    graphWriter: GraphWriter,
    graphPath: () -> String,
): suspend (PageUuid) -> Unit = { pageUuid ->
    val path = graphPath()
    if (path.isNotEmpty()) {
        val page = pageRepository.getPageByUuid(pageUuid).first().getOrNull()
        val blocks = blockRepository.getBlocksForPage(pageUuid).first().getOrNull()
        if (page != null && blocks != null) graphWriter.savePage(page, blocks, path)
    }
}
