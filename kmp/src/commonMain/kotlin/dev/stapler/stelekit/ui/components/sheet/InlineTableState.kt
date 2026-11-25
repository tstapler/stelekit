// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellDraft
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.EditSummary
import dev.stapler.stelekit.table.EntryProfile
import dev.stapler.stelekit.table.GridBounds
import dev.stapler.stelekit.table.GridCommand
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.GridNavigator
import dev.stapler.stelekit.table.GridSelection
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
import dev.stapler.stelekit.ui.components.TableParseFailure
import kotlin.time.TimeSource

/**
 * Hoisted state of one inline table: the parsed doc, the active cell and its draft. It outlives the cell
 * composables (a cell leaving composition keeps its text; nothing commits on dispose) and owns no scope.
 * [emit] receives the serialized table exactly once per commit.
 */
internal class InlineTableState(
    content: String,
    private val editor: TableEditor = TableEngine.editor(),
    private val sync: TableSyncPolicy = TableSyncPolicy(),
    private val usageLog: Logger = Logger("TableUsage"),
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

    /** A foreign edit met a pending draft: the draft stays and commits are held (the banner is PR-C). */
    var conflictHeld: Boolean by mutableStateOf(false)
        private set

    /** How `Edit` was entered, fixed for the whole edit; the strip policy (PR-B 4.4) reads it. */
    var entryProfile: EntryProfile = EntryProfile.Hardware
        private set

    private var heldContent: String? = null
    private var frozen = false
    private val clock = TimeSource.Monotonic.markNow()

    init {
        reload(content)
    }

    val editingAddress: CellAddress? get() = if (mode == GridMode.Edit) draft?.address else null

    private fun bounds(d: TableDoc) = GridBounds(d.rowCount, d.columnCount.coerceAtLeast(1))

    /** Replaces everything from [content] (first load, or the sheet released the block). */
    fun reload(content: String) {
        sync.registerLoaded(content)
        load(content)
        draft = null
        mode = GridMode.Navigate
        selection = null
        conflictHeld = false
        heldContent = null
        frozen = false
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
        frozen = true
        cancel()
        selection = null
    }

    val isFrozen: Boolean get() = frozen

    /** Starts editing [address], committing a dirty draft elsewhere first. */
    fun startEdit(address: CellAddress, profile: EntryProfile = EntryProfile.Hardware) {
        if (doc == null || frozen) return
        if (mode == GridMode.Edit && draft?.address == address) return
        if (draft?.isDirty == true) {
            if (conflictHeld) return
            commitDraft()
        }
        entryProfile = profile
        doc?.let { beginEdit(it, address) }
    }

    private fun beginEdit(d: TableDoc, address: CellAddress) {
        val clamped = bounds(d).clamp(address)
        val text = d.cell(clamped)?.value ?: ""
        draft = CellDraft.start(clamped, text)
        fieldValue = TextFieldValue(text, TextRange(text.length))
        selection = GridSelection.single(clamped, bounds(d))
        mode = GridMode.Edit
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
        if (conflictHeld || !d.isDirty) return false
        val outcome = editor.apply(before, TableEdit.SetCell(d.address, CellText(d.text)))
        if (outcome !is TableEditOutcome.Applied || outcome.summary is EditSummary.NoChange) return false
        val serialized = TableSerializer.serialize(outcome.doc)
        val seq = sync.registerCommit(serialized)
        doc = outcome.doc
        emit(serialized)
        usageLog.info("via=inline")
        // The write is fire-and-forget here, so retire it at once; a later undo then reads as an external change.
        sync.markDone(seq, draftPending = false, selection = selection?.anchor)?.let { applyDecision(it, serialized) }
        return true
    }

    /** Esc: drops the draft, shows the committed text, no commit. */
    fun cancel() {
        draft = null
        fieldValue = TextFieldValue()
        mode = GridMode.Navigate
        if (conflictHeld) {
            conflictHeld = false
            heldContent?.let { load(it) }
            heldContent = null
        }
    }

    /** Block selection began: commit what is pending, then hide the editor. */
    fun commitAndClear() {
        commitDraft()
        draft = null
        fieldValue = TextFieldValue()
        mode = GridMode.Navigate
        selection = null
    }

    /** Returns true when the key was consumed. */
    fun handleKey(key: CellEditorKey): Boolean {
        if (draft == null || fieldValue.composition != null) return false
        return when (key) {
            is CellEditorKey.Enter -> navigateAfterCommit(GridCommand.Enter(key.back))
            is CellEditorKey.Tab -> navigateAfterCommit(GridCommand.Tab(key.back))
            CellEditorKey.Cancel -> {
                cancel()
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
        // Held: consume the key and stay put; the conflict banner that explains why is PR-C.
        if (conflictHeld) return true
        commitDraft()
        val latest = doc ?: d
        when (val result = GridNavigator(bounds(latest)).handle(from, command)) {
            is NavResult.Moved -> beginEdit(latest, result.selection.anchor)
            is NavResult.CommitStay -> {
                draft = null
                fieldValue = TextFieldValue()
                mode = GridMode.Navigate
                selection = result.selection
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

    /** Classifies block content the host just observed (echo of our commit, undo, external edit). */
    fun onContent(content: String) {
        if (frozen) return
        val decision = sync.classify(content, draftPending = draft?.isDirty == true, selection = selection?.anchor)
        applyDecision(decision, content)
    }

    private fun applyDecision(decision: SyncDecision, content: String) {
        when (decision) {
            SyncDecision.Echo, SyncDecision.Stale, SyncDecision.Defer -> Unit
            is SyncDecision.Adopt -> rebuild(decision.content, selection?.anchor)
            is SyncDecision.Rebuild -> rebuild(content, decision.selection)
            SyncDecision.ConflictKeepDraft -> {
                conflictHeld = true
                heldContent = content
            }
        }
    }

    /** Re-parses [content] keeping the selection (and an unchanged-draft edit) where the new table still has it. */
    private fun rebuild(content: String, keep: CellAddress?) {
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
