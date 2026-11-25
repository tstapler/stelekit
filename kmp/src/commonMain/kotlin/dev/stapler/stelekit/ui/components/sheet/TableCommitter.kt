// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.SyncDecision
import dev.stapler.stelekit.table.TableSyncPolicy
import dev.stapler.stelekit.table.TableUndoScope
import dev.stapler.stelekit.ui.components.TableMessages
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Why the sheet closes itself; [message] is the one line shown once afterwards (null: a plain user close). */
internal enum class SheetCloseReason(val message: String?) {
    User(null),
    FlagOff(null),
    GraphChanged(null),
    EditAsMarkdown(null),
    Removed(TableMessages.TABLE_REMOVED),
    NotATable(TableMessages.NOT_A_TABLE),
    ForeignEntry(TableMessages.EDITED_ELSEWHERE),
    EditorOpened(TableMessages.EDITOR_OPENED),
    Degraded(TableMessages.RELOADED_TO_STAY_SAFE),
    SaveFailedRemoved(TableMessages.SAVE_FAILED_REMOVED),
}

/** What a committer tells the host; delivered on the committer's own context, in order. */
internal sealed interface CommitterEvent {
    /** The write did not stick (Left, throw, or the manager disagrees); the draft stays and Retry re-issues it. */
    data class CommitFailed(val text: String, val label: String, val draft: DraftText?) : CommitterEvent
    data object NothingToUndo : CommitterEvent
    data object NothingToRedo : CommitterEvent
    data class Undone(val label: String?) : CommitterEvent
    data class Redone(val label: String?) : CommitterEvent

    /** The commit was registered in the ledger and is about to be written: the grid already shows [text]. */
    data class Issued(val text: String) : CommitterEvent

    /** `markDone` released held foreign content or settled the newest emission; the host applies it. */
    data class Released(val decision: SyncDecision, val content: String) : CommitterEvent
    data class MustClose(val reason: SheetCloseReason) : CommitterEvent
}

/** The slice of the page's `BlockStateManager` the committer needs, so tests can script it. */
internal interface TableManagerPort {
    val undoRecordCount: StateFlow<Long>
    val editingBlockUuid: StateFlow<BlockUuid?>

    /** `updateBlockContent(restoreEditFocus = false)`. */
    fun write(uuid: BlockUuid, text: String, version: Long): Job
    fun undo(): Job
    fun redo(): Job

    /** The manager's in-memory content for the block, or null when it cannot find it. */
    fun contentOf(uuid: BlockUuid): String?

    /** The persisted row's content (a failed write leaves the manager's in-memory copy ahead of it); null when unreadable. */
    suspend fun storedContent(uuid: BlockUuid): String?
}

internal class BlockStateManagerTablePort(
    private val manager: BlockStateManager,
    private val stored: suspend (BlockUuid) -> String?,
) : TableManagerPort {
    override val undoRecordCount: StateFlow<Long> get() = manager.undoRecordCount
    override val editingBlockUuid: StateFlow<BlockUuid?> get() = manager.editingBlockUuid
    override fun write(uuid: BlockUuid, text: String, version: Long): Job =
        manager.updateBlockContent(uuid, text, version, restoreEditFocus = false)

    override fun undo(): Job = manager.undo()
    override fun redo(): Job = manager.redo()
    override fun contentOf(uuid: BlockUuid): String? =
        manager.blocks.value.values.firstNotNullOfOrNull { page -> page.firstOrNull { it.uuid == uuid } }?.content

    override suspend fun storedContent(uuid: BlockUuid): String? = stored(uuid)
}

/** Undo and redo availability as the controls and the Ctrl+Z handler read it. */
internal data class SheetHistory(
    val undoDepth: Int = 0,
    val redoDepth: Int = 0,
    val nextUndoLabel: String? = null,
    val nextRedoLabel: String? = null,
)

/**
 * The only writer of one block while the sheet is open (ADR-004, R3-C1..C6). Every operation runs one at a time:
 * each joins its predecessor, so DB write order equals commit order. It owns its scope on [context], which must be
 * single-threaded (the ledger and the host's classification share it). [commit] registers in the ledger before it
 * issues the write and retires the entry when the manager's `Job` completes. [undo] and [redo] wait for every pending
 * commit, are gated by the sheet's own [TableUndoScope] (the page's stack is page-wide), and declare the expected
 * content to the ledger before calling the manager. An undo-record rise nobody here caused closes the sheet.
 */
@Suppress("LongParameterList") // one collaborator per concern; a holder object would only rename them
internal class TableCommitter(
    private val uuid: BlockUuid,
    private val port: TableManagerPort,
    private val sync: TableSyncPolicy,
    initialContent: String,
    context: CoroutineContext,
    private val draftPending: () -> Boolean = { false },
    private val selection: () -> CellAddress? = { null },
    private val onEvent: (CommitterEvent) -> Unit,
    startVersion: Long = 0L,
    private val usageLog: Logger = Logger("TableUsage"),
) {
    private val log = Logger("TableCommitter")
    private val scope = CoroutineScope(SupervisorJob() + context)
    private val undoScope = TableUndoScope()
    private val undoTargets = ArrayDeque<String>()
    private val redoTargets = ArrayDeque<String>()
    private var current = initialContent
    private var knownRecords = port.undoRecordCount.value
    private var lastMinted = startVersion
    private var tail: Job? = null
    private var running = false
    private var closed = false
    private var failed: PendingCommit? = null

    private class PendingCommit(val text: String, val label: String, val draft: DraftText?)

    private val _history = MutableStateFlow(SheetHistory())
    val history: StateFlow<SheetHistory> = _history.asStateFlow()

    private val pendingOps = MutableStateFlow(0)

    /** Queued commits, undos and redos that have not finished; the "Saving..." indicator reads it (UX-66). */
    val pending: StateFlow<Int> = pendingOps.asStateFlow()

    /** Queues a commit of [text] (the whole serialized table). Equal-to-current content is skipped. UI thread only. */
    fun commit(text: String, label: String, draft: DraftText? = null) = enqueue { runCommit(text, label, draft) }

    fun undo() = enqueue { runHistory(redo = false) }

    fun redo() = enqueue { runHistory(redo = true) }

    /** Re-issues the last failed commit with a new `seq`. */
    fun retry() {
        val f = failed ?: return
        enqueue { runCommit(f.text, f.label, f.draft) }
    }

    /** Resolves after every queued operation finished. */
    suspend fun drain() {
        tail?.join()
    }

    fun cancel() {
        closed = true
        scope.cancel()
    }

    /** An external write or an adopted foreign state replaced the table: the earlier steps no longer apply. */
    fun foreignContent(content: String) {
        current = content
        undoScope.foreignEntry()
        undoTargets.clear()
        redoTargets.clear()
        publish()
    }

    /** The host observed the manager's undo-record counter; a rise while idle is somebody else's entry (R3-C4). */
    fun onUndoRecordCount(count: Long) {
        if (!running && !closed && count > knownRecords) mustClose(SheetCloseReason.ForeignEntry)
    }

    private fun enqueue(op: suspend () -> Unit) {
        if (closed) return
        val previous = tail
        pendingOps.update { it + 1 }
        tail = scope.launch {
            try {
                previous?.join()
                if (closed) return@launch
                running = true
                try {
                    op()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("table operation failed for ${uuid.value}: ${e.message}")
                } finally {
                    running = false
                }
            } finally {
                pendingOps.update { it - 1 }
            }
        }
    }

    private suspend fun runCommit(text: String, label: String, draft: DraftText?) {
        if (text == current || foreignEntryDetected()) return
        val before = port.contentOf(uuid) ?: return mustClose(SheetCloseReason.SaveFailedRemoved)
        val seq = sync.registerCommit(text)
        val recordsBefore = port.undoRecordCount.value
        usageLog.info("via=sheet")
        onEvent(CommitterEvent.Issued(text))
        // After a failed DB write the manager's copy is already `text`, so it would skip the write as a no-op; going
        // through the last persisted content first makes the retry real, at the price of one extra undo step.
        val restore = current.takeIf { before == text }
        var thrown = restore?.let { !issue(it) } ?: false
        if (!thrown) thrown = !issue(text)
        val after = port.contentOf(uuid)
        val persisted = if (thrown) null else port.storedContent(uuid)
        retire(seq)
        if (after == null) return mustClose(SheetCloseReason.SaveFailedRemoved)
        val delta = port.undoRecordCount.value - recordsBefore
        if (thrown || after != text || persisted != text) {
            // The manager records its undo entry even when the DB write failed, so the sheet counts it as a step.
            val recorded = delta == 1L && restore == null && after == text && before != text
            if (delta != 0L && !recorded) return mustClose(SheetCloseReason.Degraded)
            knownRecords += delta
            if (recorded) recordStep(before, label)
            failed = PendingCommit(text, label, draft)
            current = persisted ?: after
            publish()
            return onEvent(CommitterEvent.CommitFailed(text, label, draft))
        }
        val steps = if (restore != null) 2 else if (before != text) 1 else 0
        if (delta != steps.toLong()) return mustClose(SheetCloseReason.Degraded)
        knownRecords += delta
        failed = null
        if (restore != null) recordStep(before, label)
        if (steps > 0) recordStep(restore ?: before, label)
        current = text
        publish()
    }

    /** One manager write, joined; false when it threw. */
    private suspend fun issue(text: String): Boolean =
        try {
            port.write(uuid, text, ++lastMinted).join()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("table write failed for ${uuid.value}: ${e.message}")
            false
        }

    private fun recordStep(target: String, label: String) {
        undoTargets.addLast(target)
        if (undoTargets.size > TableUndoScope.CAP) undoTargets.removeFirst()
        redoTargets.clear()
        undoScope.record(label, changed = true)
    }

    private suspend fun runHistory(redo: Boolean) {
        if (foreignEntryDetected()) return
        val available = if (redo) undoScope.canRedo else undoScope.canUndo
        if (!available) return onEvent(if (redo) CommitterEvent.NothingToRedo else CommitterEvent.NothingToUndo)
        val from = if (redo) redoTargets else undoTargets
        val to = if (redo) undoTargets else redoTargets
        val target = from.removeLastOrNull() ?: return mustClose(SheetCloseReason.Degraded)
        val label = if (redo) undoScope.redo() else undoScope.undo()
        to.addLast(port.contentOf(uuid) ?: return mustClose(SheetCloseReason.SaveFailedRemoved))
        // Declared before the manager runs so the echo of the result classifies as Adopt, never Rebuild (R3-C1).
        sync.expectAdopt(target)
        val recordsBefore = port.undoRecordCount.value
        (if (redo) port.redo() else port.undo()).join()
        val consistent = port.contentOf(uuid) == target &&
            port.undoRecordCount.value == recordsBefore &&
            port.editingBlockUuid.value == null
        if (!consistent) return mustClose(SheetCloseReason.Degraded)
        current = target
        publish()
        onEvent(if (redo) CommitterEvent.Redone(label) else CommitterEvent.Undone(label))
    }

    private fun foreignEntryDetected(): Boolean {
        if (port.undoRecordCount.value <= knownRecords) return false
        mustClose(SheetCloseReason.ForeignEntry)
        return true
    }

    private fun retire(seq: Long) {
        sync.markDoneWithContent(seq, draftPending(), selection())?.let { onEvent(CommitterEvent.Released(it.decision, it.content)) }
    }

    private fun mustClose(reason: SheetCloseReason) {
        closed = true
        onEvent(CommitterEvent.MustClose(reason))
    }

    private fun publish() {
        _history.value = SheetHistory(undoScope.undoDepth, undoScope.redoDepth, undoScope.nextUndoLabel, undoScope.nextRedoLabel)
    }
}

/** A cell and its unsaved text, kept for failed commits, held conflicts and recovered drafts. */
internal data class DraftText(val address: CellAddress, val text: String)
