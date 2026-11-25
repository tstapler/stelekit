package dev.stapler.stelekit.table

/** What the sheet host does with a content the repository feed just emitted. */
sealed interface SyncDecision {
    /** Our own commit (or a repeated emission): the doc is not rebuilt. */
    data object Echo : SyncDecision

    /** A late echo of an older state: ignored. */
    data object Stale : SyncDecision

    /** A foreign emission held back while an own commit is `InFlight`; `markDone` releases it (P5-5). */
    data object Defer : SyncDecision

    /** Undo or redo result: rebuild from [content], keeping the selection. */
    data class Adopt(val content: String) : SyncDecision

    /** External edit, no draft: rebuild keeping [selection] clamped to the new table. */
    data class Rebuild(val selection: CellAddress?) : SyncDecision

    /** A pending draft meets a different content: keep the draft, raise the conflict. */
    data object ConflictKeepDraft : SyncDecision
}

/** A decision released by `markDone`, with the content it applies to. */
data class ReleasedDecision(val decision: SyncDecision, val content: String)

/**
 * Classifies observed block content for the table sheet (Story 3.2, ADR-004). Takes content only, never a version:
 * the feed's DB-row counter and the UI's commit order cannot be compared (R2-B1).
 */
class TableSyncPolicy(val ledger: OwnCommitLedger = OwnCommitLedger()) {
    fun registerLoaded(content: String): Long = ledger.registerLoaded(content)

    fun registerCommit(content: String): Long = ledger.registerCommit(content)

    fun expectAdopt(content: String): Long = ledger.expectAdopt(content)

    fun markDone(seq: Long, draftPending: Boolean = false, selection: CellAddress? = null): SyncDecision? =
        markDoneWithContent(seq, draftPending, selection)?.decision

    /** Like [markDone], plus the content the decision is about (held foreign content or the settled emission). */
    fun markDoneWithContent(seq: Long, draftPending: Boolean = false, selection: CellAddress? = null): ReleasedDecision? {
        ledger.markDone(seq)
        val held = ledger.takeDeferred()
        if (held != null) return ReleasedDecision(classify(held, draftPending, selection), held)
        val settled = ledger.takeSettled() ?: return null
        return ReleasedDecision(decide(ledger.settle(settled, draftPending), settled, selection), settled)
    }

    fun classify(content: String, draftPending: Boolean = false, selection: CellAddress? = null): SyncDecision =
        decide(ledger.classify(content, draftPending), content, selection)

    private fun decide(verdict: LedgerVerdict, content: String, selection: CellAddress?): SyncDecision =
        when (verdict) {
            LedgerVerdict.Echo -> SyncDecision.Echo
            LedgerVerdict.Stale -> SyncDecision.Stale
            LedgerVerdict.Defer -> SyncDecision.Defer
            LedgerVerdict.Adopt -> SyncDecision.Adopt(content)
            LedgerVerdict.External -> SyncDecision.Rebuild(clampToTable(selection, content))
            LedgerVerdict.AdoptWithDraft, LedgerVerdict.ExternalWithDraft -> SyncDecision.ConflictKeepDraft
        }

    private fun clampToTable(selection: CellAddress?, content: String): CellAddress? {
        val parsed = (TableParser.parse(content) as? TableParseResult.Parsed)?.doc ?: return selection
        return selection?.let { GridBounds(parsed.rowCount, parsed.columnCount).clamp(it) }
    }
}
