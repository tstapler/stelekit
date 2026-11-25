// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.LadderAction
import dev.stapler.stelekit.table.LadderKey
import dev.stapler.stelekit.table.LadderState
import dev.stapler.stelekit.table.NoticeType
import dev.stapler.stelekit.table.SyncDecision
import dev.stapler.stelekit.table.TableBackLadder
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.table.TableEditOutcome
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import dev.stapler.stelekit.table.TableSerializer
import dev.stapler.stelekit.table.TableSurface
import dev.stapler.stelekit.table.TableSyncPolicy
import dev.stapler.stelekit.table.formula.TableEngine
import dev.stapler.stelekit.ui.components.TableMessages
import dev.stapler.stelekit.ui.components.isGridTableContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * What the sheet body (the grid and its `SheetState`) gives the host. The host never reads cells; it asks the
 * body about its draft and tells it when the content under it changed.
 */
internal interface SheetBodyHooks {
    /** True while a cell draft differs from its committed text. */
    val hasDirtyDraft: Boolean

    /** The anchor of the selection, kept across a rebuild where the new table still has it. */
    val selectionAnchor: CellAddress?

    /** The dirty draft's cell and text, or null. */
    fun dirtyDraft(): DraftText?

    /** Commits a pending draft through [TableSheetSession.commit] (composition cleared first); no-op without one. */
    fun commitPendingDraft()

    /** Drops the draft without committing. */
    fun discardDraft()

    /** The content under the grid changed outside its own commits (undo, redo, external edit): re-parse, keep selection. */
    fun rebuild(content: String, keep: CellAddress?)

    /** Puts [text] back into a `CellDraft` for [address] (a discarded edit's Undo, a failed commit's draft). */
    fun restoreDraft(address: CellAddress, text: String)

    /** One step of the Back and Esc ladder below the conflict banner; [LadderAction.NotHandled] when nothing is open. */
    fun onBack(key: LadderKey): LadderAction = LadderAction.NotHandled

    /** True while a cell is in `Edit`: the Undo and Redo controls then act on that cell's draft, not on the table (UX-86). */
    val fieldScopedUndo: Boolean get() = false

    fun draftUndo() = Unit

    fun draftRedo() = Unit
}

/** An unsaved edit that outlives its sheet (ux.md 3.11): session memory keyed by block and cell. */
internal data class RecoveredDraft(val blockUuid: BlockUuid, val draft: DraftText)

/** Session-lifetime holder of [RecoveredDraft]s; one per window, owned by the shell. */
internal class RecoveredDraftStore {
    private val items: SnapshotStateList<RecoveredDraft> = mutableStateListOf()
    val all: List<RecoveredDraft> get() = items.toList()
    val pinned: RecoveredDraft? get() = items.firstOrNull()

    fun add(d: RecoveredDraft) {
        items.removeAll { it.blockUuid == d.blockUuid && it.draft.address == d.draft.address }
        items.add(d)
    }

    fun discard(d: RecoveredDraft) {
        items.remove(d)
    }
}

/**
 * Host-side state of one open sheet: the content the body shows, the conflict hold, lifecycle closes and notices.
 * Single-context contract: [onBlock], [commit] callbacks and the committer's events all run on [context].
 * The body calls [bind] once and [commit] for every committing action; it never touches the manager.
 */
@Suppress("TooManyFunctions") // one state holder for the sheet's host policy; the pieces share the same hold and close flags
internal class TableSheetSession(
    val blockUuid: BlockUuid,
    initialContent: String,
    port: TableManagerPort,
    /** The one context the host's callbacks run on; the body's own collaborators reuse it instead of taking a composition scope. */
    val context: CoroutineContext,
    override val readOnly: Boolean = false,
    val recovered: RecoveredDraftStore = RecoveredDraftStore(),
    startVersion: Long = 0L,
    private val flushScope: CoroutineScope,
    private val flush: suspend () -> Unit,
    private val onCloseRequest: (SheetCloseReason) -> Unit,
) : SheetHostPort {
    private val log = Logger("TableSheetSession")
    private val sync = TableSyncPolicy().also { it.registerLoaded(initialContent) }
    // Snapshot state: the chrome reads `fieldScopedUndo` before the body binds, and must recompose when it does.
    private var hooks: SheetBodyHooks? by mutableStateOf(null)

    /** The content the body should show; changes when the sheet adopts or rebuilds. */
    var content: String by mutableStateOf(initialContent)
        private set

    /** Bumped on every adopt or rebuild so a body can key a re-parse on it. */
    var revision: Int by mutableIntStateOf(0)
        private set

    var conflictHeld: Boolean by mutableStateOf(false)
        private set
    var bannerOpen: Boolean by mutableStateOf(false)
        private set
    var announcement: String? by mutableStateOf(null)
        private set
    var closed: Boolean = false
        private set
    var closeReason: SheetCloseReason? = null
        private set

    val notices = TableNotices()

    private var heldContent: String? = null
    private var heldDraft: DraftText? = null
    private var staleContent: String? = null
    private var staleStreak = 0

    private val committer = TableCommitter(
        uuid = blockUuid,
        port = port,
        sync = sync,
        initialContent = initialContent,
        context = context,
        draftPending = { hooks?.hasDirtyDraft == true },
        selection = { hooks?.selectionAnchor },
        onEvent = ::onCommitterEvent,
        startVersion = startVersion,
    )

    val history get() = committer.history

    /** Commits, undos and redos still queued or running; the sheet shows "Saving..." when this stays above 0 for 300 ms. */
    val pending get() = committer.pending

    fun bind(body: SheetBodyHooks?) {
        hooks = body
    }

    // ---- commits and undo ---------------------------------------------------------------------------------------

    /** The body's single path to the block. False when nothing was issued (read-only, or held by a conflict). */
    override fun commit(text: String, label: String, draft: DraftText?): Boolean {
        if (closed) return false
        if (readOnly) return false.also { notices.info(TableMessages.READ_ONLY) }
        if (conflictHeld) {
            bannerOpen = true
            announcement = (heldDraft ?: draft)?.let { TableMessages.heldNotSaved(it.address.toString()) }
            return false
        }
        committer.commit(text, label, draft)
        return true
    }

    override fun undo() = committer.undo()

    override fun redo() = committer.redo()

    /** True while the body's cell is in `Edit`; the Undo and Redo controls then say so and act on the field. */
    val fieldScopedUndo: Boolean get() = hooks?.fieldScopedUndo == true

    /** The Undo control's action: the cell draft in `Edit`, otherwise the depth-gated table undo (same as Ctrl+Z). */
    fun undoControl() {
        val h = hooks
        if (h != null && h.fieldScopedUndo) h.draftUndo() else undo()
    }

    fun redoControl() {
        val h = hooks
        if (h != null && h.fieldScopedUndo) h.draftRedo() else redo()
    }

    /** The body's "Copy as formulas" for the overflow menu; null until the body provides it. */
    var bodyCopyAsFormulas: (() -> Unit)? by mutableStateOf(null)

    override fun closeSheet() = close(SheetCloseReason.User)

    /** A notice that offers Undo for the last structural or paste commit; routes through [undo] (UX-24). */
    fun noticeWithUndo(text: String) = notices.withAction(NoticeType.Undo, text, TableMessages.UNDO) { committer.undo() }

    fun onUndoRecordCount(count: Long) = committer.onUndoRecordCount(count)

    // ---- the observed feed --------------------------------------------------------------------------------------

    /** The `getBlockByUuid` emission, version already dropped by the caller: lifecycle checks, then classification. */
    fun onBlock(block: Block?) {
        if (closed) return
        if (block == null) return close(SheetCloseReason.Removed)
        val isTable = block.blockType is BlockType.Table || isGridTableContent(block.content)
        if (!isTable || TableParser.parse(block.content) !is TableParseResult.Parsed) return close(SheetCloseReason.NotATable)
        onContent(block.content)
    }

    fun onContent(observed: String) {
        if (closed) return
        val decision = sync.classify(observed, hooks?.hasDirtyDraft == true, hooks?.selectionAnchor)
        apply(decision, observed)
    }

    /** The block turned into the raw editor (a path the depth rule should make unreachable). */
    fun onEditingBlock(editing: BlockUuid?) {
        if (editing != null) close(SheetCloseReason.EditorOpened)
    }

    private fun onCommitterEvent(event: CommitterEvent) {
        when (event) {
            is CommitterEvent.CommitFailed -> {
                event.draft?.let { hooks?.restoreDraft(it.address, it.text) }
                notices.withAction(NoticeType.Retry, TableMessages.COMMIT_FAILED, TableMessages.RETRY) { committer.retry() }
            }
            CommitterEvent.NothingToUndo -> notices.info(TableMessages.NOTHING_TO_UNDO_TABLE)
            CommitterEvent.NothingToRedo -> notices.info(TableMessages.NOTHING_TO_REDO_TABLE)
            is CommitterEvent.Undone -> announcement = TableMessages.UNDONE
            is CommitterEvent.Redone -> announcement = TableMessages.REDONE
            // The ledger's displayed state moves here, so `content` follows it (an undo's late echo then cannot win).
            is CommitterEvent.Issued -> content = event.text
            is CommitterEvent.Released -> apply(event.decision, event.content)
            is CommitterEvent.MustClose -> close(event.reason)
        }
    }

    /** Diagnostics: every classified decision with the content it is about (the real-feed test records them). */
    var decisionObserver: ((SyncDecision, String) -> Unit)? = null

    private fun apply(decision: SyncDecision, observed: String) {
        decisionObserver?.invoke(decision, observed)
        if (decision == SyncDecision.Stale) noteStale(observed) else staleStreak = 0
        when (decision) {
            SyncDecision.Echo, SyncDecision.Stale, SyncDecision.Defer -> Unit
            is SyncDecision.Adopt -> show(decision.content, hooks?.selectionAnchor)
            is SyncDecision.Rebuild -> {
                committer.foreignContent(observed)
                show(observed, decision.selection)
                notices.info(TableMessages.UPDATED_FROM_DISK)
            }
            SyncDecision.ConflictKeepDraft -> hold(observed)
        }
    }

    private fun noteStale(observed: String) {
        staleStreak = if (observed == staleContent) staleStreak + 1 else 1
        staleContent = observed
        if (staleStreak > MAX_STALE_REPEATS) close(SheetCloseReason.Degraded)
    }

    private fun show(newContent: String, keep: CellAddress?) {
        content = newContent
        revision++
        hooks?.rebuild(newContent, keep)
    }

    // ---- conflict (S14, UX-68, UX-69) ----------------------------------------------------------------------------

    private fun hold(observed: String) {
        conflictHeld = true
        bannerOpen = true
        heldContent = observed
        heldDraft = hooks?.dirtyDraft()
        announcement = heldDraft?.let { TableMessages.heldNotSaved(it.address.toString()) }
    }

    /** Back, Esc or tapping outside: the banner closes, the draft and the hold stay, the Resolve chip shows. */
    fun dismissBanner() {
        bannerOpen = false
    }

    fun resolveChipTapped() {
        if (conflictHeld) bannerOpen = true
    }

    /** Theirs becomes the base and the draft is committed on top of it as one undo step. */
    fun keepMine() {
        val held = takeHold() ?: return
        adoptBase(held.first)
        val d = held.second
        if (d != null) {
            hooks?.restoreDraft(d.address, d.text)
            hooks?.commitPendingDraft()
        }
    }

    /** The draft is dropped; a notice offers to put it back into a `CellDraft` (no commit was made, so no block undo). */
    fun takeTheirs() {
        val (theirs, draft) = takeHold() ?: return
        hooks?.discardDraft()
        adoptBase(theirs)
        if (draft != null) {
            notices.withAction(NoticeType.Undo, TableMessages.DISCARDED_EDIT, TableMessages.UNDO) {
                hooks?.restoreDraft(draft.address, draft.text)
            }
        }
    }

    private fun takeHold(): Pair<String, DraftText?>? {
        val theirs = heldContent ?: return null
        val draft = heldDraft ?: hooks?.dirtyDraft()
        conflictHeld = false
        bannerOpen = false
        heldContent = null
        heldDraft = null
        announcement = null
        return theirs to draft
    }

    private fun adoptBase(theirs: String) {
        sync.registerLoaded(theirs)
        committer.foreignContent(theirs)
        show(theirs, hooks?.selectionAnchor)
    }

    /** Hands any unsaved cell to the recovered-draft notice, then closes into the raw editor. */
    override fun editAsMarkdown() {
        close(SheetCloseReason.EditAsMarkdown)
    }

    // ---- recovered drafts ---------------------------------------------------------------------------------------

    /** Whether [draft]'s cell still exists in the current table (Apply is disabled with a reason otherwise). */
    fun canApply(draft: DraftText): Boolean =
        (TableParser.parse(content) as? TableParseResult.Parsed)?.doc?.cell(draft.address) != null

    /** Writes a recovered draft into its cell as one commit; false when the cell is gone or the sheet is read-only. */
    fun applyRecovered(item: RecoveredDraft): Boolean {
        val doc = (TableParser.parse(content) as? TableParseResult.Parsed)?.doc ?: return false
        val outcome = TableEngine.editor().apply(doc, TableEdit.SetCell(item.draft.address, CellText(item.draft.text)))
        val applied = outcome as? TableEditOutcome.Applied ?: return false
        val text = TableSerializer.serialize(applied.doc)
        if (!commit(text, "edit ${item.draft.address}")) return false
        recovered.discard(item)
        show(text, item.draft.address)
        return true
    }

    /** `onStop` (backgrounded): commit the draft, unless a configuration change keeps it in this holder or a conflict holds it. */
    fun onStop(changingConfiguration: Boolean) {
        if (closed || changingConfiguration) return
        if (conflictHeld) {
            (heldDraft ?: hooks?.dirtyDraft())?.let(::recoverDraft)
            return
        }
        hooks?.commitPendingDraft()
    }

    // ---- back and close -----------------------------------------------------------------------------------------

    /** The sheet's Back and Esc ladder; rung 1 (the banner) lives here, the rest in the body. */
    fun onBack(key: LadderKey): LadderAction {
        val ladder = LadderState(conflictBannerOpen = bannerOpen)
        if (TableBackLadder.next(TableSurface.Sheet, ladder, key) == LadderAction.DismissBanner) {
            dismissBanner()
            return LadderAction.DismissBanner
        }
        val inner = hooks?.onBack(key) ?: LadderAction.NotHandled
        if (inner != LadderAction.NotHandled) return inner
        if (key != LadderKey.Back) return LadderAction.NotHandled
        close(SheetCloseReason.User)
        return LadderAction.CloseSheet
    }

    /** Idempotent: commits or recovers the draft, then flushes on [flushScope] and asks the host to dismiss. */
    fun close(reason: SheetCloseReason) {
        if (closed) return
        val draft = if (conflictHeld) heldDraft ?: hooks?.dirtyDraft() else hooks?.dirtyDraft()
        val commits = reason.commitsDraft && !conflictHeld && !readOnly
        if (commits) hooks?.commitPendingDraft() else if (draft != null) recoverDraft(draft)
        closed = true
        closeReason = reason
        log.info("sheet closed: ${reason.name}")
        flushScope.launch {
            committer.drain()
            flush()
            committer.cancel()
        }
        onCloseRequest(reason)
    }

    private fun recoverDraft(draft: DraftText) {
        recovered.add(RecoveredDraft(blockUuid, draft))
    }

    private companion object {
        const val MAX_STALE_REPEATS = 2
    }
}

private val SheetCloseReason.commitsDraft: Boolean
    get() = this == SheetCloseReason.User || this == SheetCloseReason.FlagOff || this == SheetCloseReason.EditAsMarkdown
