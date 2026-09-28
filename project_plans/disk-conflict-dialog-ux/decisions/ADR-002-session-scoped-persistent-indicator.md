# ADR-002: Persistent Conflict Indicator Is Session-Scoped, Not Cross-Restart-Durable

**Status**: Accepted
**Date**: 2026-07-03
**Context**: disk-conflict-dialog-ux — Gap #4 (no persistent indicator for a deferred conflict)

---

## Context

Requirements ask for "a persistent UI indicator for pages with an unresolved deferred
(snackbar'd) conflict... until the conflict is resolved or the page is revisited and handled."
The word "persistent" is ambiguous between two readings:

1. **Persistent within the running session** — survives snackbar dismissal and navigating away
   and back, until the user actually resolves the conflict.
2. **Persistent across app restarts / graph switches** — the indicator is still there tomorrow
   if the app was closed without resolving.

`AppState.pendingConflicts: Map<String, PendingConflict>` (`AppState.kt` L136-138) already
exists and is purely in-memory, held in `_uiState`. It has the exact same lifecycle as
`LlmSuggestionInbox`, which documents itself (`LlmSuggestionInbox.kt` L11-14) as "session-scoped
only — discarded on app exit, no persistence." Both are also wiped on every graph switch, since
`StelekitViewModel` is torn down and rebuilt per `key(activeGraphId)` (`App.kt` L1124-1136,
`DisposableEffect` calling `viewModel.close()` → `scope.cancel()`).

Achieving reading (2) would require a new DB-backed table (or reusing `pending_conflicts`-shaped
persistence), a schema migration per the mandatory `MigrationRunner.all` rule in this repo's
`CLAUDE.md`, and read/write plumbing through `DatabaseWriteActor` — a materially larger change
than anything else in this project.

## Decision

Implement reading (1): the persistent indicator is **session-scoped**, backed directly by the
existing `AppState.pendingConflicts` map (plus the currently-open `AppState.diskConflict`, so the
indicator also covers the page whose dialog is open right now, not just deferred ones). It
survives snackbar dismissal and navigation within the running session. It does **not** survive
an app restart or a graph switch-and-back.

This is documented explicitly as a scoping decision, not a silent gap.

## Rationale

- Matches the existing, working pattern (`LlmSuggestionInbox`) exactly — no new persistence
  model to design, test, or migrate.
- Avoids a new DB table + `MigrationRunner.all` entry + `RestrictedDatabaseQueries` /
  `@DirectSqlWrite` write path for what is fundamentally ephemeral UI state about *in-flight*
  edits — a conflict that outlives an app restart is arguably better re-detected fresh by the
  file watcher on the next launch (comparing DB state to on-disk state) than replayed from a
  stale persisted snapshot.
- Requirements did not explicitly ask for restart-durability, and the four named UX gaps in
  `requirements.md` are all about in-session behavior (the dialog itself, its previews, and one
  snackbar-dismissal problem) — expanding to a DB-backed model would silently grow scope beyond
  what was requested.
- Keeps the fix to Gap #4's root architectural bug (`checkAndShowPendingConflict()` clearing the
  map entry before resolution, `StelekitViewModel.kt` L1471) the central, minimal change — adding
  persistence on top of a map that is *already being cleared at the wrong time* would compound
  the wrong abstraction rather than fix it.

## Consequences

- If a user closes the app (or switches graphs) with an unresolved deferred conflict, the
  indicator will be gone on next launch. The underlying disk/DB divergence is not lost — the file
  watcher's content-hash mechanism (`FileRegistry`) will simply re-surface it as a *new* pending
  conflict the next time the watcher detects the mismatch, or it will have already been
  reconciled by `GraphLoader`'s normal reconciliation path on load.
- This trade-off is called out in the PR description / UI copy rather than silently underdelivering
  on the word "persistent" in the requirements doc.
- A true cross-restart "Conflicts inbox" (DB-backed, surviving restart) is noted as a natural
  follow-up project in `plan.md`'s deferred-scope section, not built here.

## Alternatives Considered

- **DB-backed `pending_conflicts` table**: rejected for this project — correct direction if a
  future project decides restart-durability is required, but out of proportion to the four named
  UX gaps this project fixes. Would need its own requirements/research/plan cycle.
- **`Settings`/key-value store persistence** (lighter than a full table): rejected — still adds a
  new persistence surface and staleness-handling logic (what if the file changed *again* while
  the app was closed?) that the DB-backed option would face more cleanly; not worth doing halfway.
