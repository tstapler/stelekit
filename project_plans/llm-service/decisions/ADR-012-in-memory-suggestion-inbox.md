# ADR-012: In-Memory-Only `LlmSuggestionInbox` for v1 (Not a Persisted Table)

**Date**: 2026-07-01
**Status**: Accepted
**Deciders**: Tyler Stapler
**Context**: llm-service Phase 3 (planning) — resolves requirements.md Open Question 3

---

## Context

The approval-gated LLM edit workflow needs somewhere to hold pending suggestions
(block edits, tag changes, synthesized new pages) between the moment an LLM proposes
them and the moment the user explicitly accepts or rejects each one.
`requirements.md` Open Question 3 asks: "Does the approval-gated edit workflow need a
queue/inbox (multiple pending suggestions accumulate) or is it always synchronous (one
proposal, immediate accept/reject, no persistence across app restarts)?"

The codebase has a direct structural precedent for exactly this shape of problem:
`SyncState.JournalMergeReady(graphId, proposal: JournalMergeProposal)` — a subsystem
proposes a change, `StelekitViewModel.observeSyncState()` flips
`AppState.journalMergeReviewVisible = true`, a stateless review screen renders it, and
explicit `acceptJournalMerge()`/`abortJournalMerge()` methods re-validate state before
executing. `AppState.pendingConflicts: Map<String, PendingConflict>` is the same shape
generalized to multiple simultaneous items (file-watcher `DiskConflict`s). Both of these
existing precedents are **in-memory only** — neither is backed by a SQLDelight table.

Persisting pending suggestions to a new table would require the full machinery
`CLAUDE.md` mandates for any new table: a `CREATE TABLE IF NOT EXISTS` entry that must
also appear in `MigrationRunner.all` (enforced by `MigrationRunnerSchemaSyncTest`), and
all mutating access gated behind `@DirectSqlWrite` on `RestrictedDatabaseQueries`,
routed through `DatabaseWriteActor`. This is real, ongoing structural cost — not a
one-time cost — for every future field added to the suggestion shape.

Requirements explicitly scope the edit workflow as single-shot propose → user
approves/rejects, **not** an agentic/multi-turn loop (listed under Out of Scope: "A
full multi-step autonomous agent... the edit workflow is single-shot propose → user
approves/rejects, not an agentic loop"). There is therefore no long-running background
job whose progress must survive a process restart.

## Decision

Model pending LLM suggestions as an in-memory-only `LlmSuggestionInbox` for v1, shaped
like the existing `AppState.pendingConflicts` pattern:

```kotlin
class LlmSuggestionInbox {
    private val _pending = MutableStateFlow<Map<String, PendingLlmSuggestion>>(emptyMap())
    val pending: StateFlow<Map<String, PendingLlmSuggestion>> = _pending.asStateFlow()
    fun propose(suggestion: PendingLlmSuggestion) { _pending.update { it + (suggestion.id to suggestion) } }
    fun remove(id: String) { _pending.update { it - id } }
}
```

`PendingLlmSuggestion` is a sealed interface (`BlockEdit`, `TagChange`, `NewPage`)
carrying `id` (UUIDv7), `sourceProviderId`, `proposedAtEpochMs`, and — per pitfalls
research §5.1 — a `graphId` field from day one, since this codebase supports multiple
simultaneously open graphs (`GraphManager`) and a proposal must be scoped to the graph
it was generated against.

`StelekitViewModel` observes `LlmSuggestionInbox.pending` the same way it observes
`syncState` today, flipping a new `AppState.llmSuggestionReviewVisible` flag when the
map is non-empty. A new stateless review screen (parallel to
`JournalMergeReviewScreen`) calls `acceptLlmSuggestion(id)` / `rejectLlmSuggestion(id)`
on the ViewModel. Accept re-validates the suggestion is still present (same "state may
have advanced" guard as `abortJournalMerge`), removes it from the inbox, and writes
through `GraphWriter.savePage(...)`/`queueSave(...)` — never `DatabaseWriteActor`
directly, and never a new SQLDelight table. Reject is `LlmSuggestionInbox.remove(id)`
only — pure in-memory, cannot fail, no `Either` needed.

**No new SQLDelight table, no new `MigrationRunner` entry, no new `@DirectSqlWrite`
surface is introduced by this feature in v1.**

## Consequences

**Positive**:
- Zero new database schema, migration, or write-gating surface for the entire
  approval-inbox feature — the highest-leverage scope reduction available for this
  workflow.
- Directly reuses a pattern (`pendingConflicts`/`JournalMergeReady`) already proven
  in production, including its accept/reject race-guard idiom.
- Nothing is written to the graph until explicit accept, so the worst-case failure
  mode of losing in-memory suggestions (app restart, crash) is "the user re-runs the
  proposal" — not data loss, since the underlying graph content was never touched.
- Matches the requirements' explicit non-agentic, single-shot-propose scoping — there
  is no in-flight background state that needs restart survival.

**Negative / documented v2 tradeoff**:
- **Pending suggestions are lost on app restart or crash.** A user who leaves several
  LLM-proposed edits pending and then closes/crashes SteleKit will find the queue
  empty on relaunch and must regenerate the proposals. This is an explicit, accepted
  v1 limitation, not an oversight.
- If real usage later shows suggestions need to survive restarts (e.g. a future
  long-running graph-wide synthesis job that takes longer than a session, or users
  routinely batching many proposals across sessions), that is a **follow-up ADR with
  its own table + migration**, not a v1 default. This ADR does not attempt to
  pre-design that table.
- The in-memory model still needs the staleness/race handling described in pitfalls
  research §5.1 (re-validate target block/page content hash at approval time against
  what the proposal was generated from — reusing the same conflict-detection concept
  as `GraphLoader.externalFileChanges`/`DiskConflict`) — going in-memory does not
  remove this requirement, since the underlying graph can still change out from under
  a pending suggestion within a single session.
- Approval-fatigue mitigation (pitfalls research §5.2 — batching related proposals
  with a shared summary rather than N independent single-item reviews) is a UI-design
  concern orthogonal to this storage decision, but is easier to get right against an
  in-memory `Map` than it would be against paginated table reads, since the full
  pending set for a graph is always available for batch-grouping without a query.

## Alternatives Considered

### Persisted SQLDelight table (`pending_llm_suggestions` or similar)

Rejected for v1. Would require: a new `CREATE TABLE IF NOT EXISTS` in
`SteleDatabase.sq`, a matching entry in `MigrationRunner.all` (enforced by
`MigrationRunnerSchemaSyncTest`), `@DirectSqlWrite`-annotated forwarding stubs on
`RestrictedDatabaseQueries` for insert/update/delete, and all access routed through
`DatabaseWriteActor`. This is substantial, ongoing structural cost for a feature whose
proposals are cheap to regenerate (a fresh LLM call) and where nothing has been
written to the graph yet — the risk being protected against (losing an unapproved,
unpersisted proposal) is qualitatively different from the risk the write-gating
machinery exists to prevent (losing or corrupting already-committed user graph data).
Explicitly noted as the v2 path if a concrete need for restart-survival emerges.

### Fully synchronous, single-proposal-at-a-time (no queue at all)

Considered but not adopted. A queue-of-one loses the ability to batch multiple
related proposals from a single graph-wide synthesis pass (e.g. "12 tag corrections
across your graph") into one review session with shared context, which pitfalls
research §5.2 identifies as the primary mitigation for approval fatigue when a
synthesis pass can plausibly generate several proposals at once. The `Map`-based
inbox costs nothing extra over a single-item holder and directly supports the
batching-with-drill-down UX the fatigue research recommends, so the queue shape was
kept even though persistence was not.
