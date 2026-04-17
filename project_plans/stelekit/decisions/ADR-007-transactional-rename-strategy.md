# ADR-007: Transactional Rename Strategy — DB-First with File-Write Compensation

**Status**: Proposed  
**Date**: 2026-04-12  
**Deciders**: Tyler Stapler  
**Feature**: Knowledge Graph Maintenance — Story 1 (Page Rename with Backlink Update)

---

## Context

Renaming a page in SteleKit requires two distinct mutations that cannot be wrapped in a single atomic operation:

1. **DB mutation** — update `pages.name`, update `blocks.content` to replace `[[OldName]]` with `[[NewName]]` for every affected block (SQLDelight transaction).
2. **File mutation** — rewrite each affected markdown file on disk to replace the wikilink text, and move the renamed page's own file (platform filesystem, no transaction support).

The risk is a partial state where the DB is updated but some files are not (or vice versa). This state causes `GraphLoader` to re-import stale wikilinks and create broken references.

Three orderings were considered:

**Option A — File-first, DB second**: rewrite all files first, then commit DB. If the DB commit fails, files have been modified with no DB record. On the next app launch, `GraphLoader` would re-import with the new names, effectively self-healing — but only if the DB commit failed cleanly (not mid-transaction).

**Option B — DB-first, file-write compensation**: commit DB changes first (fast, atomic), then rewrite files in parallel. If a file write fails, execute a compensating DB transaction (reverse the rename in the DB). The window of inconsistency is bounded: between DB commit and compensation, the DB shows the new name but some files show the old name.

**Option C — Saga pattern with persistent journal**: log rename intent to a `rename_journal` table before starting, mark steps complete as they succeed, and support recovery on restart. Full correctness but significant implementation complexity.

---

## Decision

**Option B — DB-first with file-write compensation** is adopted for v1.

Rationale:

- The DB transaction (SQLDelight) is always faster and more reliable than filesystem IO. Committing to DB first minimises the time the system is in a partially-updated state.
- The compensation path (reversing the DB rename) is a well-defined idempotent operation (re-run `BacklinkRenamer.execute` with names swapped). It does not require special recovery logic.
- SQLite transactions on a local file are effectively instantaneous for graphs up to 100 000 blocks. The entire DB mutation commits in <200 ms.
- The file-write phase runs with a `Semaphore(4)` for parallelism and tracks success/failure per file. Only failed files need compensation — successfully rewritten files do not need to be reverted.
- Option C (saga journal) is deferred to a future iteration if the compensation approach proves insufficient in practice.

---

## Consequences

**Positive**:
- Clear, auditable two-phase protocol in `BacklinkRenamer.execute`.
- Compensation logic is reusable (it is just another call to `BacklinkRenamer.execute`).
- No schema changes required for v1.

**Negative / Risks**:
- During the file-write phase, the DB shows the new name while some files still contain the old name. If the process is killed (power loss, force-quit) after DB commit but before file rewrites complete, the graph is in an inconsistent state that requires manual recovery. Mitigation: implement Option C's `rename_journal` table as a follow-up (tracked as PERF/KGM follow-up).
- The compensating DB transaction also rewrites files — if those rewrites fail too (e.g., disk full), the graph is permanently inconsistent. The error surface must make this visible so the user can use a backup.

---

## Alternatives Considered

**Option A rejected**: a failed DB commit after successful file writes leaves files in the new state but DB in the old state. `GraphLoader` would re-import files with new wikilinks but the page DB row would still show the old name — creating new pages for the new wikilinks and orphaning the old page row. This is harder to compensate than Option B's symmetric reversal.

**Option C deferred**: Saga + persistent journal provides the strongest correctness guarantees but requires a `rename_journal` table, a recovery coroutine on app startup, and additional test complexity. It is the right long-term answer but disproportionate for v1 given the low frequency of page renames and the presence of graph backups.
