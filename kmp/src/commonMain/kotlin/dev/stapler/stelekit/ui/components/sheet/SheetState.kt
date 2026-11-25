// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellDraft
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.GridBounds
import dev.stapler.stelekit.table.GridCommand
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.GridNavigator
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.table.LadderAction
import dev.stapler.stelekit.table.LadderKey
import dev.stapler.stelekit.table.LadderState
import dev.stapler.stelekit.table.NavResult
import dev.stapler.stelekit.table.TableBackLadder
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.table.TableEditOutcome
import dev.stapler.stelekit.table.TableEditor
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import dev.stapler.stelekit.table.TableSerializer
import dev.stapler.stelekit.table.TableSurface
import dev.stapler.stelekit.table.TableViewState
import dev.stapler.stelekit.table.TextSelection
import dev.stapler.stelekit.table.formula.CellError
import dev.stapler.stelekit.table.formula.CellValue
import dev.stapler.stelekit.table.formula.PointModeRule
import dev.stapler.stelekit.table.formula.PointModeState
import dev.stapler.stelekit.table.formula.TableCalc
import dev.stapler.stelekit.table.formula.TableEngine
import dev.stapler.stelekit.ui.components.TableMessages
import dev.stapler.stelekit.ui.components.TableParseFailure
import kotlin.time.TimeSource

/** A table parsed for the grid: [doc] is null with a [failure] when it cannot be edited as a grid; [calc] is null above the formula cap. */
internal class ParsedSheet(val text: String, val doc: TableDoc?, val failure: TableParseFailure?, val calc: TableCalc?)

/** Pure and thread-agnostic: the sheet runs it on `PlatformDispatcher.Default` for large tables. */
internal fun parseSheet(content: String): ParsedSheet = when (val parsed = TableParser.parse(content)) {
    is TableParseResult.Parsed -> ParsedSheet(content, parsed.doc, null, TableCalc.orNull(parsed.doc))
    is TableParseResult.Failure -> ParsedSheet(content, null, TableParseFailure.fromReason(parsed.reason), null)
}

/** The reference a point-mode tap just wrote into the draft: where it sits and which cells it spans. */
internal data class PointRef(val start: Int, val end: Int, val anchor: CellAddress, val focus: CellAddress) {
    val text: String get() = if (anchor == focus) anchor.toString() else "$anchor:$focus"
}

/** Plain-data parts of [SheetState] that survive a configuration change (UX-14); never an IME composition. */
internal data class SheetSnapshot(
    val anchor: CellAddress,
    val focus: CellAddress,
    val kind: GridSelection.Kind,
    val mode: GridMode,
    val draftAddress: CellAddress?,
    val draftText: String?,
    val draftSelection: TextSelection?,
    val view: TableViewState,
    val firstIndex: Int,
    val firstOffset: Int,
    val hScroll: Int,
) {
    companion object
}

/**
 * Everything the sheet's grid, edit bar and keys share: the parsed table, the one `CellDraft`, selection, mode,
 * point mode and view state. It owns no coroutine scope and lives above the lazy rows, so scrolling a row out of
 * composition never commits or loses a draft; commits happen only on explicit triggers (Enter, Tab, arrows,
 * blur to another cell, close, maximize, Edit as markdown, `onStop`) through [host].
 */
@Suppress("TooManyFunctions", "LargeClass") // one state machine for the grid: mode, draft, point mode and commands share fields
internal class SheetState(
    parsed: ParsedSheet,
    private val host: SheetHostPort,
    var sink: SheetCommandSink = NoOpSheetCommandSink,
    private val editor: TableEditor = TableEngine.editor(),
) : SheetBodyHooks {
    var doc: TableDoc? by mutableStateOf(parsed.doc)
        private set
    var failure: TableParseFailure? by mutableStateOf(parsed.failure)
        private set
    var selection: GridSelection by mutableStateOf(initialSelection(parsed.doc))
        private set
    var mode: GridMode by mutableStateOf(GridMode.Navigate)
        private set
    var draft: CellDraft? by mutableStateOf(null)
        private set
    var fieldValue: TextFieldValue by mutableStateOf(TextFieldValue())
        private set
    var view: TableViewState by mutableStateOf(TableViewState())
        private set
    var pointMode: PointModeState by mutableStateOf(PointModeState())
        private set
    var lastRef: PointRef? by mutableStateOf(null)
        private set
    var announcement: String? by mutableStateOf(null)
        private set
    var announcementSeq: Int by mutableIntStateOf(0)
        private set

    /** Bumped when focus should return to the grid (a commit left `Edit`). */
    var gridFocusToken: Int by mutableIntStateOf(0)
        private set

    /** Bumped when the edit bar's field should take focus (`Edit` began). */
    var barFocusToken: Int by mutableIntStateOf(0)
        private set

    /** True while a large paste or sort runs; the body shows "Applying..." after 300 ms (Story 5.9). Set by the structural workers. */
    var applying: Boolean by mutableStateOf(false)

    /** Set by the composable from the window insets; Back hides the IME before it does anything else (rung 3). */
    var imeVisible: Boolean by mutableStateOf(false)
    var hideIme: () -> Unit = {}

    /** Rows the grid shows per page; Page Up and Down move by this. */
    var pageRows: Int = DEFAULT_PAGE_ROWS

    /** The Undo notice of a discarded edit (Cancel edit): set by the body, which owns the notices. */
    var onDiscarded: (DraftText) -> Unit = {}

    var firstVisibleIndex: Int = 0
        private set
    var firstVisibleOffset: Int = 0
        private set
    var horizontalScroll: Int = 0
        private set

    private var calc: TableCalc? = parsed.calc
    private var calcVersion by mutableIntStateOf(0)
    private var lastText: String = parsed.text
    private var errorDismissedFor: CellAddress? by mutableStateOf(null)
    private val menuClosers = ArrayList<() -> Unit>()
    private var menuCount by mutableIntStateOf(0)
    private val clock = TimeSource.Monotonic.markNow()
    private var nextKey = 0L
    private var rowKeys: List<Long> by mutableStateOf(freshKeys(parsed.doc?.rowCount ?: 0))
    private var draftRowKey: Long = NO_KEY
    private var draftCol: Int = 1

    private fun freshKeys(count: Int): List<Long> = List(count) { nextKey++ }

    val rowCount: Int get() = doc?.rowCount ?: 0
    val columnCount: Int get() = doc?.columnCount ?: 0
    val bounds: GridBounds get() = GridBounds(rowCount.coerceAtLeast(1), columnCount.coerceAtLeast(1))
    val readOnly: Boolean get() = host.readOnly

    /** Stable identity of a row across inserts and deletes; the lazy list keys on it (Story 5.2). */
    fun rowKeyOf(row: Int): Long = rowKeys.getOrElse(row - 1) { -(row.toLong()) - 1 }

    /** The cell being edited, following its row if rows were inserted above it. */
    val editingAddress: CellAddress?
        get() {
            if (mode != GridMode.Edit || draft == null) return null
            val row = rowKeys.indexOf(draftRowKey) + 1
            return if (row >= 1) CellAddress(row, draftCol) else null
        }

    val activeAddress: CellAddress get() = selection.anchor

    // ---- display -------------------------------------------------------------------------------------------------

    /** What a resting cell shows; reads [calcVersion] so a recompute invalidates the cells that read it. */
    fun display(address: CellAddress): CellDisplay {
        calcVersion
        val d = doc ?: return CellDisplay.Plain("")
        return cellDisplay(d, calc, address)
    }

    /** The literal text the edit bar shows: the draft while editing, else the active cell's stored text. */
    val barText: String
        get() = if (mode == GridMode.Edit) fieldValue.text else doc?.cell(selection.anchor)?.value ?: ""

    /** The reason for the active error cell, or null; also the status line text (UX-61). */
    val errorDetail: String?
        get() {
            val address = selection.anchor
            if (mode != GridMode.Navigate || errorDismissedFor == address) return null
            return detailFor(address)
        }

    /** The reason for any error cell (the hover popup uses it for the cell under the pointer). */
    fun detailFor(address: CellAddress): String? {
        calcVersion
        return when (val shown = display(address)) {
            is CellDisplay.Failed -> if (calc?.valueAt(address) == CellValue.Err(CellError.Cycle)) {
                TableMessages.circularReference(calc?.cyclePath(address).orEmpty())
            } else shown.detail
            is CellDisplay.InvalidFormula -> shown.detail
            else -> null
        }
    }

    fun dismissErrorDetail() {
        errorDismissedFor = selection.anchor
    }

    /** The text indicator of point mode, or null when a tap would commit instead. */
    val pointIndicator: String?
        get() {
            if (mode != GridMode.Edit) return null
            val ref = lastRef
            return when {
                pointMode.justInserted && ref != null -> TableMessages.insertedReference(ref.text)
                pointMode.armed && !pointMode.stoppedForThisEdit -> TableMessages.TAP_TO_INSERT_REFERENCE
                else -> null
            }
        }

    // ---- menus (rung 2 of the ladder) --------------------------------------------------------------------------

    /** A menu or panel registers its closer while open; Back and Esc close the newest first. */
    fun registerMenu(close: () -> Unit): () -> Unit {
        menuClosers.add(close)
        menuCount = menuClosers.size
        return {
            menuClosers.remove(close)
            menuCount = menuClosers.size
        }
    }

    val menuOpen: Boolean get() = menuCount > 0

    // ---- editing -------------------------------------------------------------------------------------------------

    /** Starts editing [address] (default: the active cell); [initialText] replaces the cell's text (typing over it). */
    fun startEdit(address: CellAddress = selection.anchor, initialText: String? = null) {
        val d = doc ?: return
        if (host.readOnly) return announce(TableMessages.READ_ONLY)
        if (mode == GridMode.Edit && editingAddress == address && initialText == null) return
        if (mode == GridMode.Edit && !commitDraft()) return
        val at = bounds.clamp(address)
        val committed = d.cell(at)?.value ?: ""
        val text = initialText ?: committed
        beginDraft(at, CellDraft.start(at, committed, text), TextFieldValue(text, TextRange(text.length)))
    }

    private fun beginDraft(at: CellAddress, d: CellDraft, value: TextFieldValue) {
        draft = d
        draftRowKey = rowKeyOf(at.row)
        draftCol = at.col
        fieldValue = value
        selection = GridSelection.single(at, bounds)
        mode = GridMode.Edit
        lastRef = null
        pointMode = PointModeRule.onDraftChanged(PointModeRule.onEditStarted(), value.text, value.selection.end)
        barFocusToken++
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
        pointMode = PointModeRule.onDraftChanged(pointMode, value.text, value.selection.end)
    }

    /** Whether the field is mid-composition; the key decision passes every key to the IME then. */
    val composing: Boolean get() = fieldValue.composition != null

    /** Commit-if-changed. False only when a commit was refused (read-only or held conflict): the draft stays. */
    fun commitDraft(): Boolean {
        val d = draft ?: return true
        val address = editingAddress ?: return true
        if (!d.isDirty) return true
        val applied = applyEdit(TableEdit.SetCell(address, CellText(d.text)), "edit $address", DraftText(address, d.text))
        if (applied == null) return false
        draft = d.markCommitted()
        return true
    }

    private fun leaveEdit() {
        draft = null
        fieldValue = TextFieldValue()
        mode = GridMode.Navigate
        lastRef = null
        pointMode = PointModeState()
        gridFocusToken++
    }

    /** Esc: restores the original text; a changed draft is offered back through [onDiscarded]. */
    fun cancelEdit() {
        val discarded = draft?.takeIf { it.isDirty }?.let { d -> editingAddress?.let { DraftText(it, d.text) } }
        leaveEdit()
        discarded?.let(onDiscarded)
    }

    /**
     * Applies [edit] to the table and hands the serialized result to the host as one commit. Returns the outcome,
     * or null when it was refused, changed nothing, or the host would not take it (read-only, held conflict).
     */
    fun applyEdit(edit: TableEdit, label: String, draft: DraftText? = null): TableEditOutcome.Applied? {
        val before = doc ?: return null
        val outcome = editor.apply(before, edit) as? TableEditOutcome.Applied ?: return null
        if (outcome.doc == before) return null
        val text = TableSerializer.serialize(outcome.doc)
        if (!host.commit(text, label, draft)) return null
        adopt(outcome.doc, text, edit)
        return outcome
    }

    private fun adopt(newDoc: TableDoc, text: String, edit: TableEdit) {
        val before = rowKeys
        val oldCount = rowCount
        doc = newDoc
        lastText = text
        calc?.update(newDoc, edit)
        calcVersion++
        rowKeys = when {
            edit is TableEdit.InsertRows -> before.toMutableList().also { keys ->
                repeat(edit.count) { keys.add((edit.before - 1).coerceIn(0, keys.size), nextKey++) }
            }
            edit is TableEdit.DeleteRows -> before.filterIndexed { i, _ -> i + 1 !in edit.at until edit.at + edit.count }
            newDoc.rowCount != oldCount -> freshKeys(newDoc.rowCount)
            else -> before
        }
        if (edit is TableEdit.InsertRows || edit is TableEdit.DeleteRows) shiftSelectionForRows(edit)
        selection = selection.clamp(bounds)
        if (mode == GridMode.Edit && rowKeys.indexOf(draftRowKey) < 0) leaveEdit()
    }

    private fun shiftSelectionForRows(edit: TableEdit) {
        fun shift(a: CellAddress): CellAddress = when (edit) {
            is TableEdit.InsertRows -> if (a.row >= edit.before) CellAddress(a.row + edit.count, a.col) else a
            is TableEdit.DeleteRows -> when {
                a.row >= edit.at + edit.count -> CellAddress(a.row - edit.count, a.col)
                a.row >= edit.at -> CellAddress(edit.at.coerceAtLeast(1), a.col)
                else -> a
            }
            else -> a
        }
        selection = GridSelection(shift(selection.anchor), shift(selection.focus), selection.kind)
    }

    // ---- commands ------------------------------------------------------------------------------------------------

    /** The one entry for keys, the edit bar and later the strip: runs [command] for the current mode. */
    fun perform(command: GridCommand): Boolean = if (mode == GridMode.Edit) performInEdit(command) else performInNavigate(command)

    fun onKey(decision: SheetKeyDecision): Boolean = when (decision) {
        SheetKeyDecision.PassToIme, SheetKeyDecision.NotHandled -> false
        is SheetKeyDecision.Run -> perform(decision.command)
        is SheetKeyDecision.TypeChar -> {
            if (!host.readOnly) startEdit(initialText = decision.text)
            true
        }
    }

    private fun performInNavigate(command: GridCommand): Boolean {
        val d = doc ?: return false
        return when (command) {
            GridCommand.StartEdit -> true.also { startEdit() }
            GridCommand.Cancel -> onBack(LadderKey.Esc) != LadderAction.NotHandled
            GridCommand.ClearCells -> true.also {
                val range = selection.range
                applyEdit(TableEdit.ClearRange(range), "clear ${rangeLabel(selection)}")
            }
            GridCommand.Undo -> true.also { host.undo() }
            GridCommand.Redo -> true.also { host.redo() }
            GridCommand.ToggleMaximize -> true.also { host.closeSheet() }
            GridCommand.EditAsMarkdown -> true.also { host.editAsMarkdown() }
            GridCommand.Copy, GridCommand.Cut, GridCommand.Paste, GridCommand.InsertRowOrColumn,
            GridCommand.DeleteRowOrColumn, GridCommand.OpenContextMenu, GridCommand.InsertLineBreak,
            -> true.also { sink.onCommand(command, selection) }
            else -> navigate(d, command)
        }
    }

    private fun navigate(d: TableDoc, command: GridCommand): Boolean {
        val navigator = GridNavigator(bounds, pageRows = pageRows, isBlank = { a -> d.cell(a)?.value.isNullOrEmpty() })
        return when (val result = navigator.handle(selection, command)) {
            is NavResult.Moved -> true.also { select(result.selection) }
            is NavResult.CommitStay -> true.also { select(result.selection) }
            NavResult.NotNavigation -> false
        }
    }

    private fun performInEdit(command: GridCommand): Boolean {
        if (draft == null) return false
        return when (command) {
            is GridCommand.Enter, is GridCommand.Tab -> true.also { commitAndMove(command) }
            GridCommand.Cancel -> true.also { cancelEdit() }
            GridCommand.InsertLineBreak -> true.also { insertLineBreak() }
            GridCommand.Undo -> true.also { draftUndo() }
            GridCommand.Redo -> true.also { draftRedo() }
            GridCommand.ToggleMaximize -> true.also { host.closeSheet() }
            else -> false
        }
    }

    private fun commitAndMove(command: GridCommand) {
        if (doc == null || !commitDraft()) return
        val from = editingAddress?.let { GridSelection.single(it, bounds) } ?: selection
        leaveEdit()
        when (val result = GridNavigator(bounds).handle(from, command)) {
            is NavResult.Moved -> select(result.selection)
            is NavResult.CommitStay -> {
                select(result.selection)
                announce(TableMessages.END_OF_TABLE)
            }
            NavResult.NotNavigation -> Unit
        }
        hideIme()
    }

    /** The edit bar's (...) "Next column" and the IME action "Next" (Enter semantics) land here. */
    fun nextColumn() {
        if (mode == GridMode.Edit) commitAndMove(GridCommand.Tab())
    }

    fun onImeNext() {
        if (mode == GridMode.Edit) commitAndMove(GridCommand.Enter())
    }

    /** Done (the check): commit, leave `Edit` on the same cell. */
    fun done() {
        if (mode != GridMode.Edit) return
        if (commitDraft()) leaveEdit()
        hideIme()
    }

    fun insertLineBreak() {
        val v = fieldValue
        val text = v.text.replaceRange(v.selection.min, v.selection.max, "\n")
        onFieldChange(TextFieldValue(text, TextRange(v.selection.min + 1)))
    }

    fun draftUndo() {
        val d = draft?.takeIf { it.canUndo } ?: return announce(TableMessages.NOTHING_TO_UNDO_CELL)
        applyDraft(d.undo())
    }

    fun draftRedo() {
        val d = draft?.takeIf { it.canRedo } ?: return announce(TableMessages.NOTHING_TO_REDO_CELL)
        applyDraft(d.redo())
    }

    private fun applyDraft(d: CellDraft) {
        draft = d
        val len = d.text.length
        fieldValue = TextFieldValue(d.text, TextRange(d.selection.start.coerceIn(0, len), d.selection.end.coerceIn(0, len)))
        pointMode = PointModeRule.onDraftChanged(pointMode, d.text, fieldValue.selection.end)
    }

    // ---- selection and taps --------------------------------------------------------------------------------------

    private fun select(next: GridSelection) {
        if (next.anchor != selection.anchor) errorDismissedFor = null
        selection = next
    }

    /** A tap or click on a cell, from the single gesture owner (ADR-005); the sheet taps SELECT, a tap on the selected cell edits. */
    fun onCellTap(address: CellAddress) {
        if (doc == null) return
        val at = bounds.clamp(address)
        if (mode == GridMode.Edit) {
            if (at == editingAddress) return
            if (pointTap(at)) return
            if (!commitDraft()) return
            leaveEdit()
            select(GridSelection.single(at, bounds))
            return
        }
        gridFocusToken++
        if (selection.isSingleCell && selection.anchor == at) startEdit(at) else select(GridSelection.single(at, bounds))
    }

    /** Column letter tap: selects the whole column (the header menu is a later worker's). */
    fun selectColumn(col: Int) {
        if (!commitDraftAndLeave()) return
        select(GridSelection.single(CellAddress(1, col), bounds).selectColumns(bounds))
    }

    /** Row number tap: selects the whole row. */
    fun selectRow(row: Int) {
        if (!commitDraftAndLeave()) return
        select(GridSelection.single(CellAddress(row, 1), bounds).selectRows(bounds))
    }

    private fun commitDraftAndLeave(): Boolean {
        if (mode != GridMode.Edit) return true
        if (!commitDraft()) return false
        leaveEdit()
        return true
    }

    /** Extends the range from the focus; a header drag and the Extend picker both end here. */
    fun extendSelectionTo(target: CellAddress) {
        if (!commitDraftAndLeave()) return
        select(selection.extendTo(target, bounds))
    }

    // ---- point mode (5.8b, 5.8c) -------------------------------------------------------------------------------

    private fun pointTap(cell: CellAddress): Boolean {
        val d = draft ?: return false
        val v = fieldValue
        val replace = lastRef?.takeIf { PointModeRule.allowsReplace(pointMode) }
        if (replace == null && !PointModeRule.allows(d.text, v.selection.end, pointMode)) return false
        val start = replace?.start ?: v.selection.min
        val end = replace?.end ?: v.selection.max
        writeReference(start, end, PointRef(start, start, cell, cell))
        return true
    }

    private fun writeReference(start: Int, end: Int, ref: PointRef) {
        val text = fieldValue.text.replaceRange(start, end, ref.text)
        val placed = ref.copy(start = start, end = start + ref.text.length)
        val value = TextFieldValue(text, TextRange(placed.end))
        draft = draft?.edit(text, TextSelection(placed.end), composing = false, atMs = clock.elapsedNow().inWholeMilliseconds)
        fieldValue = value
        lastRef = placed
        pointMode = PointModeRule.onReferenceInserted(pointMode)
    }

    /** "Extend..." after an insert: grows the inserted reference without a drag (stepwise, or to an edge). */
    fun extendReference(choice: ExtendChoice) {
        val ref = lastRef ?: return
        if (!PointModeRule.allowsReplace(pointMode)) return
        val b = bounds
        val focus = ref.focus
        val next = when (choice) {
            ExtendChoice.Right -> CellAddress(focus.row, focus.col + 1)
            ExtendChoice.Down -> CellAddress(focus.row + 1, focus.col)
            ExtendChoice.RowEnd -> CellAddress(focus.row, b.cols)
            ExtendChoice.ColumnEnd -> CellAddress(b.rows, focus.col)
            ExtendChoice.TableEnd -> CellAddress(b.rows, b.cols)
        }
        writeReference(ref.start, ref.end, ref.copy(focus = b.clamp(next)))
    }

    /** "Stop inserting": a tap commits and moves for the rest of this edit. */
    fun stopInserting() {
        pointMode = PointModeRule.stop(pointMode)
        lastRef = null
    }

    // ---- back ladder ---------------------------------------------------------------------------------------------

    /** Rungs 2 to 6' (the banner is the session's rung 1); [LadderAction.NotHandled] lets the session close on Back. */
    override fun onBack(key: LadderKey): LadderAction {
        if (key == LadderKey.Esc && menuCount == 0 && errorDetail != null) {
            dismissErrorDetail()
            return LadderAction.CloseMenu
        }
        val ladder = LadderState(
            menuOpen = menuCount > 0,
            imeVisible = imeVisible && mode == GridMode.Edit,
            mode = mode,
            selectionSize = selection.cellCount,
        )
        val action = TableBackLadder.next(TableSurface.Sheet, ladder, key)
        when (action) {
            LadderAction.CloseMenu -> menuClosers.lastOrNull()?.invoke()
            LadderAction.HideIme -> hideIme()
            LadderAction.Done -> done()
            LadderAction.CancelEdit -> cancelEdit()
            LadderAction.CollapseRange -> select(selection.collapse())
            else -> Unit
        }
        return if (action == LadderAction.CloseSheet) LadderAction.NotHandled else action
    }

    // ---- scroll bookkeeping (the grid reports; the Saver reads) --------------------------------------------------

    fun recordScroll(firstIndex: Int, firstOffset: Int, horizontal: Int) {
        firstVisibleIndex = firstIndex
        firstVisibleOffset = firstOffset
        horizontalScroll = horizontal
    }

    fun updateView(next: TableViewState) {
        view = next
    }

    fun announce(text: String) {
        announcement = text
        announcementSeq++
    }

    // ---- SheetBodyHooks ------------------------------------------------------------------------------------------

    override val hasDirtyDraft: Boolean get() = mode == GridMode.Edit && draft?.isDirty == true
    override val selectionAnchor: CellAddress? get() = selection.anchor

    override fun dirtyDraft(): DraftText? {
        val d = draft?.takeIf { it.isDirty } ?: return null
        return editingAddress?.let { DraftText(it, d.text) }
    }

    override fun commitPendingDraft() {
        if (mode != GridMode.Edit) return
        fieldValue = fieldValue.copy(composition = null)
        commitDraft()
    }

    override fun discardDraft() {
        if (mode == GridMode.Edit) leaveEdit()
    }

    override fun rebuild(content: String, keep: CellAddress?) {
        if (content == lastText) return
        val parsed = parseSheet(content)
        val newDoc = parsed.doc ?: return
        val sameShape = newDoc.rowCount == rowCount
        doc = newDoc
        failure = null
        calc = parsed.calc
        calcVersion++
        lastText = content
        if (!sameShape) rowKeys = freshKeys(newDoc.rowCount)
        val anchor = bounds.clamp(keep ?: selection.anchor)
        selection = GridSelection.single(anchor, bounds)
        if (mode == GridMode.Edit) {
            val committed = newDoc.cell(anchor)?.value ?: ""
            beginDraft(anchor, CellDraft.start(anchor, committed), TextFieldValue(committed, TextRange(committed.length)))
        }
    }

    override fun restoreDraft(address: CellAddress, text: String) {
        val d = doc ?: return
        val at = bounds.clamp(address)
        beginDraft(at, CellDraft.start(at, d.cell(at)?.value ?: "", text), TextFieldValue(text, TextRange(text.length)))
    }

    // ---- Saver -----------------------------------------------------------------------------------------------------

    fun snapshot(): SheetSnapshot {
        val d = draft
        return SheetSnapshot(
            anchor = selection.anchor,
            focus = selection.focus,
            kind = selection.kind,
            mode = mode,
            draftAddress = editingAddress,
            draftText = d?.text,
            draftSelection = d?.selection,
            view = view,
            firstIndex = firstVisibleIndex,
            firstOffset = firstVisibleOffset,
            hScroll = horizontalScroll,
        )
    }

    fun restore(s: SheetSnapshot) {
        val b = bounds
        selection = GridSelection(b.clamp(s.anchor), b.clamp(s.focus), s.kind)
        view = s.view
        firstVisibleIndex = s.firstIndex
        firstVisibleOffset = s.firstOffset
        horizontalScroll = s.hScroll
        val address = s.draftAddress
        val text = s.draftText
        if (s.mode == GridMode.Edit && address != null && text != null) {
            val at = b.clamp(address)
            val committed = doc?.cell(at)?.value ?: ""
            val sel = s.draftSelection ?: TextSelection(text.length)
            beginDraft(at, CellDraft.start(at, committed, text), TextFieldValue(text, TextRange(sel.start.coerceIn(0, text.length), sel.end.coerceIn(0, text.length))))
            selection = GridSelection(b.clamp(s.anchor), b.clamp(s.focus), s.kind)
        }
    }

    companion object {
        private const val NO_KEY = Long.MIN_VALUE
        private const val DEFAULT_PAGE_ROWS = 10

        private fun initialSelection(doc: TableDoc?): GridSelection {
            val rows = doc?.rowCount ?: 1
            val cols = doc?.columnCount ?: 1
            val b = GridBounds(rows.coerceAtLeast(1), cols.coerceAtLeast(1))
            return GridSelection.single(CellAddress(minOf(2, b.rows), 1), b)
        }

        private fun rangeLabel(s: GridSelection): String =
            if (s.isSingleCell) s.anchor.toString() else "${s.range.topLeft}:${s.range.bottomRight}"

        /** Saves [SheetSnapshot]'s plain data; [parsed] is the table the restored state sits on. */
        fun saver(parsed: ParsedSheet, host: SheetHostPort, sink: SheetCommandSink = NoOpSheetCommandSink): Saver<SheetState, Any> =
            Saver(
                save = { state -> state.snapshot().toSaved() },
                restore = { saved -> SheetState(parsed, host, sink).also { it.restore(SheetSnapshot.fromSaved(saved)) } },
            )
    }
}

private fun SheetSnapshot.toSaved(): List<Any> = listOf(
    anchor.row, anchor.col, focus.row, focus.col, kind.name, mode.name,
    draftAddress?.row ?: 0, draftAddress?.col ?: 0, draftText ?: NONE, draftSelection?.start ?: 0, draftSelection?.end ?: 0,
    view.freezeHeaderRow, view.freezeFirstColumn, view.columnWidthsDp.entries.flatMap { listOf(it.key, it.value) },
    firstIndex, firstOffset, hScroll,
)

@Suppress("UNCHECKED_CAST", "MagicNumber") // positional layout written by toSaved above
private fun SheetSnapshot.Companion.fromSaved(saved: Any): SheetSnapshot {
    val l = saved as List<Any>
    val text = l[8] as String
    val row = l[6] as Int
    var view = TableViewState().withFreezeHeaderRow(l[11] as Boolean).withFreezeFirstColumn(l[12] as Boolean)
    (l[13] as List<Any>).chunked(2).forEach { (col, w) -> view = view.setWidth(col as Int, w as Float) }
    return SheetSnapshot(
        anchor = CellAddress(l[0] as Int, l[1] as Int),
        focus = CellAddress(l[2] as Int, l[3] as Int),
        kind = GridSelection.Kind.valueOf(l[4] as String),
        mode = GridMode.valueOf(l[5] as String),
        draftAddress = if (row > 0) CellAddress(row, l[7] as Int) else null,
        draftText = text.takeIf { it != NONE },
        draftSelection = if (text != NONE) TextSelection(l[9] as Int, l[10] as Int) else null,
        view = view,
        firstIndex = l[14] as Int,
        firstOffset = l[15] as Int,
        hScroll = l[16] as Int,
    )
}

private const val NONE = "\u0000none"
