// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellDraft
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.Chord
import dev.stapler.stelekit.table.EditSummary
import dev.stapler.stelekit.table.EntryProfile
import dev.stapler.stelekit.table.GridBounds
import dev.stapler.stelekit.table.GridCommand
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.GridNavigator
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.table.LadderAction
import dev.stapler.stelekit.table.LadderKey
import dev.stapler.stelekit.table.LadderState
import dev.stapler.stelekit.table.NoticeType
import dev.stapler.stelekit.table.SelectionStats
import dev.stapler.stelekit.table.TableBackLadder
import dev.stapler.stelekit.table.TableShortcutMap
import dev.stapler.stelekit.table.TableStripPolicy
import dev.stapler.stelekit.table.TableSurface
import dev.stapler.stelekit.table.TableUndoScope
import dev.stapler.stelekit.table.NavResult
import dev.stapler.stelekit.table.SyncDecision
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.table.TableEditOutcome
import dev.stapler.stelekit.table.TableEditor
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import dev.stapler.stelekit.table.TableSerializer
import dev.stapler.stelekit.table.TableSyncPolicy
import dev.stapler.stelekit.table.TextSelection
import dev.stapler.stelekit.table.formula.TableEngine
import dev.stapler.stelekit.ui.components.TableMessages
import dev.stapler.stelekit.ui.components.TableParseFailure
import dev.stapler.stelekit.ui.components.TableUndoHost
import kotlin.time.TimeSource

/**
 * Hoisted state of one inline table: the parsed doc, the active cell and its draft. It outlives the cell
 * composables (a cell leaving composition keeps its text; the table's own disposal commits the draft) and owns no scope.
 * [emit] receives the serialized table exactly once per commit.
 */
@Suppress("TooManyFunctions", "LargeClass") // one hoisted holder for the grid's selection, draft, strip actions and undo; splitting would scatter one state machine
internal class InlineTableState(
    content: String,
    private val editor: TableEditor = TableEngine.editor(),
    private val sync: TableSyncPolicy = TableSyncPolicy(),
    private val usageLog: Logger = Logger("TableUsage"),
    /** Cells the grid composes; navigation and selection never address rows past this window. */
    private val cellWindow: Int = Int.MAX_VALUE,
    private val emit: (String) -> Unit,
) {
    var doc: TableDoc? by mutableStateOf(null)
        private set
    var failure: TableParseFailure? by mutableStateOf(null)
        private set
    var selection: GridSelection? by mutableStateOf(null)
        private set
    var mode: GridMode by mutableStateOf(GridMode.Navigate)
        private set
    var draft: CellDraft? by mutableStateOf(null)
        private set
    var fieldValue: TextFieldValue by mutableStateOf(TextFieldValue())
        private set

    /** A foreign edit met a pending draft: the draft stays and commits are held until a choice is made. */
    var conflictHeld: Boolean by mutableStateOf(false)
        private set

    /** The banner is up; Back dismisses only it and leaves the Resolve chip while [conflictHeld] (UX-68). */
    var conflictBannerOpen: Boolean by mutableStateOf(false)
        private set

    /** How `Edit` was entered, fixed for the whole edit; the strip policy (PR-B 4.4) reads it. */
    var entryProfile: EntryProfile = EntryProfile.Hardware
        private set

    /** Callbacks the composable sets each composition: clipboard, sheet, raw editor, per-cell text and error detail. */
    val hooks = StripHooks()
    val notices = TableNotices()

    /** The page's undo manager, when the shell provides one; null leaves the strip's Undo and Redo dimmed. */
    var undoHost: TableUndoHost? = null

    var openMenu: StripMenu? by mutableStateOf(null)
        private set

    /** The menu that just closed through Back; the strip returns focus to its invoker, then clears it. */
    var menuFocusReturn: StripMenu? by mutableStateOf(null)
        private set

    /** Bumped whenever focus should return to the active cell node (Done, Cancel, Close, Back). */
    var focusToken: Int by mutableIntStateOf(0)
        private set

    /** One polite "Editing B3, 3" per activation; cleared when the edit ends so typing announces nothing. */
    var announcement: String? by mutableStateOf(null)
        private set

    private val undoScope = TableUndoScope()
    private var scopeUndoDepth by mutableIntStateOf(0)
    private var scopeRedoDepth by mutableIntStateOf(0)
    private val undoTargets = ArrayDeque<String>()
    private val redoTargets = ArrayDeque<String>()
    private var currentContent: String = content
    private var seenRecords = 0L
    private var ownRecordsPending = 0

    private var heldContent: String? = null
    private var frozen = false
    private val clock = TimeSource.Monotonic.markNow()

    init {
        reload(content)
    }

    val editingAddress: CellAddress? get() = if (mode == GridMode.Edit) draft?.address else null

    private fun bounds(d: TableDoc): GridBounds {
        val cols = d.columnCount.coerceAtLeast(1)
        return GridBounds(inlineRowWindow(d.rowCount, cols, cellWindow), cols)
    }

    /** Replaces everything from [content] (first load, or the sheet released the block). */
    fun reload(content: String) {
        sync.registerLoaded(content)
        currentContent = content
        undoTargets.clear()
        redoTargets.clear()
        resetUndoScope()
        load(content)
        draft = null
        mode = GridMode.Navigate
        selection = null
        conflictHeld = false
        conflictBannerOpen = false
        heldContent = null
        frozen = false
        openMenu = null
        announcement = null
        notices.clear()
    }

    private fun load(content: String) {
        when (val parsed = TableParser.parse(content)) {
            is TableParseResult.Parsed -> {
                doc = parsed.doc
                failure = null
            }
            is TableParseResult.Failure -> {
                doc = null
                failure = TableParseFailure.fromReason(parsed.reason)
            }
        }
    }

    /** The sheet owns this block: inline renders read-only and drops any selection. */
    fun freeze() {
        commitDraft()
        frozen = true
        cancel()
        selection = null
        hideStrip()
    }

    val isFrozen: Boolean get() = frozen

    /** Starts editing [address], committing a dirty draft elsewhere first. */
    fun startEdit(address: CellAddress, profile: EntryProfile = EntryProfile.Hardware) {
        if (doc == null || frozen) return
        if (mode == GridMode.Edit && draft?.address == address) return
        if (draft?.isDirty == true) {
            if (conflictHeld) return reshowBanner()
            commitDraft()
        }
        entryProfile = profile
        openMenu = null
        doc?.let { beginEdit(it, address) }
        announcement = draft?.let { TableMessages.editing(it.address.toString(), it.text) }
    }

    private fun beginEdit(d: TableDoc, address: CellAddress) {
        val clamped = bounds(d).clamp(address)
        val text = d.cell(clamped)?.value ?: ""
        draft = CellDraft.start(clamped, text)
        fieldValue = TextFieldValue(text, TextRange(text.length))
        selection = GridSelection.single(clamped, bounds(d))
        mode = GridMode.Edit
        announcement = null
    }

    fun onFieldChange(value: TextFieldValue) {
        val d = draft ?: return
        draft = d.edit(
            newText = value.text,
            newSelection = TextSelection(value.selection.start, value.selection.end),
            composing = value.composition != null,
            atMs = clock.elapsedNow().inWholeMilliseconds,
        )
        fieldValue = value
    }

    /**
     * Commit-if-changed: one `SetCell`, serialized, handed to [emit] once. Acts only on `Applied`. Returns whether
     * a commit was emitted; false for an unchanged draft, a refused edit or a held conflict.
     */
    fun commitDraft(): Boolean {
        val d = draft ?: return false
        val before = doc ?: return false
        if (conflictHeld) {
            if (d.isDirty) reshowBanner()
            return false
        }
        if (!d.isDirty) return false
        val outcome = editor.apply(before, TableEdit.SetCell(d.address, CellText(d.text)))
        if (outcome !is TableEditOutcome.Applied || outcome.summary is EditSummary.NoChange) return false
        val serialized = TableSerializer.serialize(outcome.doc)
        val seq = sync.registerCommit(serialized)
        doc = outcome.doc
        recordCommit(d.address, serialized)
        // The draft stays open (ON_STOP, dispose) but is no longer dirty: no false conflict or "discarded edit".
        draft = d.markCommitted()
        emit(serialized)
        usageLog.info("via=inline")
        // The write is fire-and-forget here, so retire it at once; a later undo then reads as an external change.
        sync.markDoneWithContent(seq, draftPending = false, selection = selection?.anchor)?.let { applyDecision(it.decision, it.content) }
        return true
    }

    private fun recordCommit(address: CellAddress, serialized: String) {
        undoTargets.addLast(currentContent)
        if (undoTargets.size > TableUndoScope.CAP) undoTargets.removeFirst()
        redoTargets.clear()
        currentContent = serialized
        undoScope.record("edit $address", changed = true)
        ownRecordsPending++
        syncDepths()
    }

    /** Esc: drops the draft, shows the committed text, no commit, no notice. */
    fun cancel() {
        draft = null
        fieldValue = TextFieldValue()
        mode = GridMode.Navigate
        announcement = null
        if (conflictHeld) {
            conflictHeld = false
            conflictBannerOpen = false
            heldContent?.let { takeBase(it) }
            heldContent = null
        }
    }

    /** Cancel edit (strip or Esc): like [cancel], and a dirty draft is recoverable from an Undo notice. */
    fun cancelEdit() {
        val discarded = draft?.takeIf { mode == GridMode.Edit && it.isDirty }
        cancel()
        focusToken++
        if (discarded != null) {
            notices.withAction(NoticeType.Undo, TableMessages.DISCARDED_EDIT, TableMessages.UNDO) {
                restoreDraft(discarded.address, discarded.text)
            }
        }
    }

    private fun restoreDraft(address: CellAddress, text: String) {
        val d = doc ?: return
        if (frozen) return
        val clamped = bounds(d).clamp(address)
        draft = CellDraft.start(clamped, d.cell(clamped)?.value ?: "", initialText = text)
        fieldValue = TextFieldValue(text, TextRange(text.length))
        selection = GridSelection.single(clamped, bounds(d))
        mode = GridMode.Edit
    }

    /** Block selection began: commit what is pending, then hide the editor. */
    fun commitAndClear() {
        if (conflictHeld) {
            // The commit is refused while a foreign edit is held: drop the draft, show the held content, keep it recoverable.
            val discarded = draft?.takeIf { it.isDirty }
            cancel()
            clearInteraction()
            if (discarded != null) {
                notices.withAction(NoticeType.Undo, TableMessages.DISCARDED_EDIT, TableMessages.UNDO) { restoreDraft(discarded.address, discarded.text) }
            }
            return
        }
        commitDraft()
        clearInteraction()
    }

    private fun clearInteraction() {
        draft = null
        fieldValue = TextFieldValue()
        mode = GridMode.Navigate
        selection = null
        announcement = null
        hideStrip()
    }

    /**
     * Commits a pending draft (exactly one commit) and leaves `Edit` on the same cell. False when a held
     * conflict refuses the commit: the draft and mode stay and the caller must not go on.
     */
    fun commitAndLeaveEdit(): Boolean {
        if (mode != GridMode.Edit) return true
        if (conflictHeld && draft?.isDirty == true) return false.also { reshowBanner() }
        commitDraft()
        draft = null
        fieldValue = TextFieldValue()
        mode = GridMode.Navigate
        announcement = null
        return true
    }

    /** Returns true when the key was consumed. */
    fun handleKey(key: CellEditorKey): Boolean {
        if (draft == null || fieldValue.composition != null) return false
        return when (key) {
            is CellEditorKey.Enter -> navigateAfterCommit(GridCommand.Enter(key.back))
            is CellEditorKey.Tab -> navigateAfterCommit(GridCommand.Tab(key.back))
            CellEditorKey.Cancel -> {
                // Rung 1 of the ladder: Esc closes the banner before it cancels the edit (UX-68).
                if (conflictBannerOpen) dismissBanner() else cancelEdit()
                true
            }
            CellEditorKey.LineBreak -> {
                insertLineBreak()
                true
            }
        }
    }

    private fun navigateAfterCommit(command: GridCommand): Boolean {
        val d = doc ?: return true
        val from = selection ?: return true
        if (conflictHeld) return true.also { if (draft?.isDirty == true) reshowBanner() }
        commitDraft()
        val latest = doc ?: d
        when (val result = GridNavigator(bounds(latest)).handle(from, command)) {
            is NavResult.Moved -> beginEdit(latest, result.selection.anchor)
            is NavResult.CommitStay -> {
                draft = null
                fieldValue = TextFieldValue()
                mode = GridMode.Navigate
                selection = result.selection
                announcement = null
            }
            NavResult.NotNavigation -> Unit
        }
        return true
    }

    private fun insertLineBreak() {
        val v = fieldValue
        val start = v.selection.min
        val text = v.text.replaceRange(start, v.selection.max, "\n")
        onFieldChange(TextFieldValue(text, TextRange(start + 1)))
    }

    // ---- selection (4.4a) ---------------------------------------------------------------------------

    val stripPolicy: TableStripPolicy get() = TableStripPolicy(mode, entryProfile, conflictHeld)

    /** The strip shows for the active selection when the policy allows it for this mode and entry profile. */
    val stripVisible: Boolean get() = !frozen && selection != null && stripPolicy.visible

    /** Mouse drag (after slop) from [from] to [to]: commits a pending draft first, then selects the range. */
    fun dragSelect(from: CellAddress, to: CellAddress) {
        val d = doc ?: return
        if (frozen || !commitAndLeaveEdit()) return
        val b = bounds(d)
        val start = GridSelection.single(from, b)
        selection = if (from == to) start else start.extendTo(to, b)
        openMenu = null
        focusToken++
    }

    /** Shift+click: grows the range from the anchor. */
    fun extendSelectionTo(target: CellAddress) {
        val d = doc ?: return
        val current = selection ?: return
        if (frozen || !commitAndLeaveEdit()) return
        selection = current.extendTo(target, bounds(d))
        focusToken++
    }

    fun performExtend(choice: ExtendChoice) {
        val d = doc ?: return
        val current = selection ?: return
        val b = bounds(d)
        val focus = current.focus
        val target = when (choice) {
            ExtendChoice.Right -> CellAddress(focus.row, focus.col + 1)
            ExtendChoice.Down -> CellAddress(focus.row + 1, focus.col)
            ExtendChoice.RowEnd -> CellAddress(focus.row, b.cols)
            ExtendChoice.ColumnEnd -> CellAddress(b.rows, focus.col)
            ExtendChoice.TableEnd -> CellAddress(b.rows, b.cols)
        }
        selection = current.extendTo(target, b)
        openMenu = null
        menuFocusReturn = StripMenu.Extend
    }

    private fun navigateSelection(command: GridCommand) {
        val d = doc ?: return
        val current = selection ?: return
        (GridNavigator(bounds(d)).handle(current, command) as? NavResult.Moved)?.let { selection = it.selection }
    }

    private fun textAt(address: CellAddress): String = doc?.let { d -> hooks.cellText?.invoke(d, address) ?: d.cell(address)?.value } ?: ""

    /** Copies the selection as TSV through the clipboard hook and posts "Copied R x C cells". */
    fun copySelection() {
        val current = selection ?: return
        val range = current.range
        val truncated = doc?.let { it.rowCount > bounds(it).rows && range.bottomRight.row == bounds(it).rows } == true
        if (current.cellCount > MAX_COPY_CELLS) return notices.info(TableMessages.COPY_TOO_LARGE)
        val tsv = SelectionStats.toTsv(current, ::textAt)
        if (tsv.length > MAX_COPY_CHARS) return notices.info(TableMessages.COPY_TOO_LARGE)
        // The write is async: say "Copied" only once the clipboard accepted it.
        hooks.copyToClipboard(tsv) { ok ->
            notices.info(
                when {
                    !ok -> TableMessages.COPY_FAILED
                    truncated -> TableMessages.copiedVisible(range.rows, range.cols)
                    else -> TableMessages.copied(range.rows, range.cols)
                },
            )
        }
    }

    // ---- strip actions (4.4a, 4.4c) --------------------------------------------------------------------

    /** Runs a strip action. Everything but Close, Cancel edit, Undo, Redo and Why? commits the draft first. */
    fun perform(action: StripAction) {
        hooks.onStripAction()
        when (action) {
            StripAction.Close -> closeOrCollapse()
            StripAction.CancelEdit -> cancelEdit()
            StripAction.Done -> if (commitAndLeaveEdit()) focusToken++
            StripAction.NextColumn -> handleKey(CellEditorKey.Tab(back = false))
            StripAction.LineBreak -> handleKey(CellEditorKey.LineBreak)
            StripAction.Undo -> undo()
            StripAction.Redo -> redo()
            StripAction.Why -> showWhy()
            StripAction.Resolve -> reopenBanner()
            else -> if (commitAndLeaveEdit()) performAfterCommit(action)
        }
    }

    private fun performAfterCommit(action: StripAction) {
        if (action != StripAction.Extend && action != StripAction.More) openMenu = null
        when (action) {
            StripAction.SelectRow -> navigateSelection(GridCommand.SelectRow)
            StripAction.SelectColumn -> navigateSelection(GridCommand.SelectColumn)
            StripAction.SelectTable -> navigateSelection(GridCommand.SelectAll)
            StripAction.Extend -> openMenu = if (openMenu == StripMenu.Extend) null else StripMenu.Extend
            StripAction.More -> openMenu = if (openMenu == StripMenu.More) null else StripMenu.More
            StripAction.Copy -> copySelection()
            StripAction.Maximize -> hooks.openSheet?.invoke()
            StripAction.Markdown -> hooks.editAsMarkdown()
            else -> Unit
        }
    }

    /** The standalone Maximize button and Ctrl+Shift+Enter: commit first, then open the sheet once. */
    fun maximize() {
        if (commitAndLeaveEdit()) hooks.openSheet?.invoke()
    }

    /** "Edit as markdown": commit first, then the raw editor; a held draft goes to the recovered-draft notice instead. */
    fun editAsMarkdown() {
        if (conflictHeld && draft?.isDirty == true) {
            val d = draft
            hooks.recoverDraft?.let { recover -> d?.let { recover(DraftText(it.address, it.text)) } }
            cancel()
            hooks.editAsMarkdown()
            return
        }
        if (commitAndLeaveEdit()) hooks.editAsMarkdown()
    }

    // ---- conflict banner (4.5e) ---------------------------------------------------------------------------------

    private fun reshowBanner() {
        conflictBannerOpen = true
        draft?.let { announcement = TableMessages.heldNotSaved(it.address.toString()) }
    }

    fun dismissBanner() {
        conflictBannerOpen = false
    }

    fun reopenBanner() {
        if (conflictHeld) conflictBannerOpen = true
    }

    /** Theirs becomes the base and the draft is committed on top of it as one undo step. */
    fun keepMine() {
        val held = heldContent ?: return
        conflictHeld = false
        conflictBannerOpen = false
        heldContent = null
        announcement = null
        takeBase(held)
        commitDraft()
    }

    /** The draft is dropped; the notice's Undo puts it back into a `CellDraft`. */
    fun takeTheirs() = cancelEdit()

    /** [content] is the new committed base: the ledger, undo targets and grid all start from it. */
    private fun takeBase(content: String) {
        sync.registerLoaded(content)
        currentContent = content
        undoTargets.clear()
        redoTargets.clear()
        resetUndoScope()
        load(content)
    }

    private fun closeOrCollapse() {
        val current = selection ?: return
        if (current.cellCount > 1) {
            selection = current.collapse()
            focusToken++
        } else {
            closeStrip()
        }
    }

    /** Hides the strip: the selection goes, the undo scope restarts at zero and notices clear. */
    fun closeStrip() {
        selection = null
        hideStrip()
    }

    private fun hideStrip() {
        openMenu = null
        resetUndoScope()
        notices.clear()
    }

    fun closeMenu() {
        val open = openMenu
        if (open != null) {
            menuFocusReturn = open
            openMenu = null
        } else if (notices.visible?.type == NoticeType.Why) {
            notices.dismiss()
        }
    }

    fun menuFocusReturned() {
        menuFocusReturn = null
    }

    private fun showWhy() {
        val anchor = selection?.anchor ?: return
        val detail = doc?.let { hooks.errorDetail?.invoke(it, anchor) } ?: return
        notices.withAction(NoticeType.Why, detail, TableMessages.EDIT) { startEdit(anchor, entryProfile) }
    }

    // ---- undo and redo (4.4c) ---------------------------------------------------------------------------

    private fun resetUndoScope() {
        undoScope.reset()
        syncDepths()
    }

    private fun syncDepths() {
        scopeUndoDepth = undoScope.undoDepth
        scopeRedoDepth = undoScope.redoDepth
    }

    val undoEnabled: Boolean
        get() = if (mode == GridMode.Edit) draft?.canUndo == true else scopeUndoDepth > 0 && undoHost?.canUndo?.value == true

    val redoEnabled: Boolean
        get() = if (mode == GridMode.Edit) draft?.canRedo == true else scopeRedoDepth > 0 && undoHost?.canRedo?.value == true

    val undoLabel: String
        get() = if (mode == GridMode.Edit) TableMessages.undoLabel(null) else TableMessages.undoLabel(scopeUndoDepth.takeIf { it > 0 }?.let { undoScope.nextUndoLabel })

    val redoLabel: String
        get() = if (mode == GridMode.Edit) TableMessages.redoLabel(null) else TableMessages.redoLabel(scopeRedoDepth.takeIf { it > 0 }?.let { undoScope.nextRedoLabel })

    private fun undo() {
        if (mode == GridMode.Edit) return draftUndo()
        val host = undoHost
        if (host == null || !undoEnabled) return notices.info(TableMessages.NOTHING_TO_UNDO_TABLE)
        moveHistory(undo = true)
        host.undo()
        notices.info(TableMessages.UNDONE)
    }

    private fun redo() {
        if (mode == GridMode.Edit) return draftRedo()
        val host = undoHost
        if (host == null || !redoEnabled) return notices.info(TableMessages.NOTHING_TO_REDO_TABLE)
        moveHistory(undo = false)
        host.redo()
        notices.info(TableMessages.REDONE)
    }

    /** Registers the content the manager's undo or redo is about to produce, so the ledger adopts it (P5-5). */
    private fun moveHistory(undo: Boolean) {
        val from = if (undo) undoTargets else redoTargets
        val to = if (undo) redoTargets else undoTargets
        val target = from.removeLastOrNull()
        if (undo) undoScope.undo() else undoScope.redo()
        if (target != null) {
            to.addLast(currentContent)
            sync.expectAdopt(target)
            currentContent = target
        }
        syncDepths()
    }

    /** Ctrl+Z inside the cell: steps the draft back, never commits and never reaches the page's undo. */
    fun draftUndo() {
        val d = draft ?: return
        if (!d.canUndo) return notices.info(TableMessages.NOTHING_TO_UNDO_CELL)
        applyDraft(d.undo())
    }

    fun draftRedo() {
        val d = draft ?: return
        if (!d.canRedo) return notices.info(TableMessages.NOTHING_TO_REDO_CELL)
        applyDraft(d.redo())
    }

    private fun applyDraft(d: CellDraft) {
        draft = d
        val end = d.selection.end.coerceIn(0, d.text.length)
        fieldValue = TextFieldValue(d.text, TextRange(d.selection.start.coerceIn(0, d.text.length), end))
    }

    /** The host's undo-record counter moved: own commits were expected, anything else is a foreign entry (UX-85). */
    fun onUndoRecordCount(count: Long) {
        val delta = count - seenRecords
        seenRecords = count
        if (delta <= 0) return
        val own = minOf(delta, ownRecordsPending.toLong()).toInt()
        ownRecordsPending -= own
        if (delta > own) {
            undoScope.foreignEntry()
            undoTargets.clear()
            redoTargets.clear()
            syncDepths()
        }
    }

    // ---- keys and back (4.4b, 4.4e, 4.5a) -------------------------------------------------------------------

    /** Navigate-mode chord for the focused table; true when consumed so the page never also handles it. */
    fun handleNavigateKey(chord: Chord): Boolean {
        if (mode != GridMode.Navigate || frozen) return false
        val current = selection ?: return false
        return when (val command = TableShortcutMap.lookup(chord, GridMode.Navigate) ?: return false) {
            GridCommand.Copy -> true.also { copySelection() }
            GridCommand.Undo -> true.also { perform(StripAction.Undo) }
            GridCommand.Redo -> true.also { perform(StripAction.Redo) }
            GridCommand.ToggleMaximize -> hooks.openSheet != null && true.also { maximize() }
            GridCommand.EditAsMarkdown -> true.also { editAsMarkdown() }
            GridCommand.Cancel -> onBack(LadderKey.Esc, imeVisible = false) {} != LadderAction.NotHandled
            GridCommand.StartEdit -> true.also { startEdit(current.anchor, entryProfile) }
            is GridCommand.Move, is GridCommand.Tab, is GridCommand.Enter, is GridCommand.LineEdge,
            is GridCommand.TableEdge, is GridCommand.Page, GridCommand.SelectColumn, GridCommand.SelectRow, GridCommand.SelectAll,
            -> true.also { navigateSelection(command); focusToken++ }
            else -> false
        }
    }

    /** One step of the Back and Esc ladder for the inline surface; the caller supplies the IME state and how to hide it. */
    fun onBack(key: LadderKey, imeVisible: Boolean, hideIme: () -> Unit): LadderAction {
        val active = selection != null && !frozen
        val ladder = LadderState(
            conflictBannerOpen = conflictBannerOpen && conflictHeld,
            menuOpen = openMenu != null || notices.visible?.type == NoticeType.Why,
            imeVisible = imeVisible && mode == GridMode.Edit,
            mode = if (active) mode else null,
            selectionSize = selection?.cellCount ?: 1,
            stripVisible = stripVisible,
        )
        val action = TableBackLadder.next(TableSurface.Inline, ladder, key)
        when (action) {
            LadderAction.DismissBanner -> dismissBanner()
            LadderAction.CloseMenu -> closeMenu()
            LadderAction.HideIme -> hideIme()
            LadderAction.Done -> perform(StripAction.Done)
            LadderAction.CancelEdit -> cancelEdit()
            LadderAction.CollapseRange -> closeOrCollapse()
            LadderAction.CloseStrip -> closeStrip()
            else -> Unit
        }
        return action
    }

    /** Back is only claimed while something is open; otherwise the platform keeps it. */
    val backEnabled: Boolean get() = !frozen && selection != null

    /** Classifies block content the host just observed (echo of our commit, undo, external edit). */
    fun onContent(content: String) {
        if (frozen) return
        adoptOutsideHistoryMove(content)
        val decision = sync.classify(content, draftPending = draft?.isDirty == true, selection = selection?.anchor)
        applyDecision(decision, content)
    }

    /**
     * An undo or redo issued outside this table (page shortcut, toolbar) shows up as content equal to the previous
     * state. Without registering it the ledger calls it `Stale` (P5-5). Undo needs the host's redo flag so a late
     * echo of an older own commit is not mistaken for an undo; redo can only follow a detected undo.
     */
    private fun adoptOutsideHistoryMove(content: String) {
        if (content == currentContent) return
        val host = undoHost
        if (host != null && host.canRedo.value && undoTargets.lastOrNull() == content) {
            moveHistory(undo = true)
        } else if (redoTargets.lastOrNull() == content) {
            moveHistory(undo = false)
        }
    }

    private fun applyDecision(decision: SyncDecision, content: String) {
        when (decision) {
            SyncDecision.Echo, SyncDecision.Stale, SyncDecision.Defer -> Unit
            is SyncDecision.Adopt -> if (draft?.isDirty == true) holdDraft(decision.content) else rebuild(decision.content, selection?.anchor)
            is SyncDecision.Rebuild -> {
                // An external edit invalidates our history; this also heals own-record accounting that drifted.
                undoTargets.clear()
                redoTargets.clear()
                ownRecordsPending = 0
                undoScope.foreignEntry()
                syncDepths()
                rebuild(content, decision.selection)
            }
            SyncDecision.ConflictKeepDraft -> holdDraft(content)
        }
    }

    private fun holdDraft(content: String) {
        conflictHeld = true
        conflictBannerOpen = true
        heldContent = content
        draft?.let { announcement = TableMessages.heldNotSaved(it.address.toString()) }
    }

    /** Re-parses [content] keeping the selection (and an unchanged-draft edit) where the new table still has it. */
    private fun rebuild(content: String, keep: CellAddress?) {
        currentContent = content
        load(content)
        val d = doc
        if (d == null) {
            draft = null
            mode = GridMode.Navigate
            selection = null
            return
        }
        val anchor = keep?.let { bounds(d).clamp(it) }
        if (mode == GridMode.Edit && anchor != null) beginEdit(d, anchor) else selection = anchor?.let { GridSelection.single(it, bounds(d)) }
    }
}

private const val MAX_COPY_CELLS = 20_000
private const val MAX_COPY_CHARS = 2_000_000
