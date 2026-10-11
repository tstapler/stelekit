# ADR-006: Keep the ~30 eager journal cap; load older journals lazily on demand

**Status**: Accepted (owner decision 2026-10-10) | **Date**: 2026-10-10 | **Amended**: 2026-10-10 (Phase 3 repair)

## Context
`loadJournalsImmediate(10)` plus `loadRemainingJournals(skip=10, take=20)` register only ~30 newest files (`GraphLoader.kt:1091-1100`); `indexRemainingPages` upgrades only existing METADATA_ONLY rows, so the other ~1.5k journals never enter the DB. `JournalsViewModel` pages over the DB, so older journals are unreachable by scroll.

## Decision
Cap stays. The new logic lives in a `JournalLazyLoader` seam (`db/JournalLazyLoader.kt`; constructor takes `FileRegistry`, the page lookup repository, and a parse-and-save function reference). `GraphLoader` only gains two one-line delegating overrides of the port methods; it does not gain the logic (hotspot: 1970 lines, 63 commits). Add three bounded on-demand paths: (1) journals view reaching the end of loaded data requests the next page (`loadJournalsOlderThan(date, count=30)`); (2) navigate-by-name and calendar jump load a single date via `ensureJournalLoaded(date)` (file resolved with existing `resolvePageFilePath`); (3) date-shaped search queries resolve through (2). All DB lookups use chunked `getJournalPagesByDates` (<=500). No standing unbounded query, no registration of all files.
Full-text search over never-loaded journals is explicitly not provided (see plan Unresolved Questions).

## Alternatives rejected
Register all journals as METADATA_ONLY (changes the O(graph) load profile the owner chose not to change); raise the cap.

## Consequences
Console `journals-diff` reports `recent (14d) missing` separately from `older (not loaded by design)`.
