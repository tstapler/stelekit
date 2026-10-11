# ADR-001: `StorageLocation`/`StorageMoveOperation` as sealed interfaces, persisted in a new table

**Status**: Proposed
**Date**: 2026-09-12
**Project**: app-owned-storage-clone

## Context

No `StorageLocation`/`GraphLocation` concept exists in `commonMain` today (confirmed by grep,
`research/architecture.md` §1). A graph's storage backend is currently implicit: Android infers it
from whether a SAF tree URI is on file plus `isDirectAccess()`; Web infers it from whether
`HostDirectorySync`'s `hostDirHandle` is set. Neither is queryable as a first-class value, so
`GitShadowWorktree.sweepOrphans()` (see ADR-002) and the new relocate/link flows have nothing to
consult to know "is this graph's shadow tree a cache or the only copy."

Two precedents were evaluated for how to model it: `GraphBackend`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt:30-36`, a flat
enum with no per-case data) and `DomainError`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`, a sealed interface with
data-carrying leaves). `GraphBackend`-style flat enum cannot express per-case fields (a SAF tree
URI string vs. a real path vs. a display name) without an external side-table keyed by kind, which
just re-implements a sealed interface with extra steps.

## Decision

Model `StorageLocation` as a `sealed interface` (`AppOwned`, `SafFolder`, `DirectAccessFolder`,
`HostFolder` leaves) and `StorageMoveOperation` as a separate `sealed interface` (`Relocate`,
`Link` leaves), both in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/`, following
`DomainError`'s nested-sealed-interface convention. Add a `DomainError.StorageError` family
alongside the existing `DomainError` nested families for move-failure returns, per this repo's
`Either<DomainError, T>` convention.

Persist `StorageLocation` in a new SQLDelight table (`storage_locations`, one row per `graphId`,
nullable columns for the per-kind fields), not by parsing it back out of `GraphManager`'s existing
path bookkeeping — this is the row `GitShadowWorktree.sweepOrphans()`'s new gate (ADR-002) reads.

`DirectAccessFolder` (Android `MANAGE_EXTERNAL_STORAGE`) is modeled as its own internal case but is
**not** surfaced as a separate row in the unified picker UI — it is an invisible acceleration
detail under "folder," consistent with `isDirectAccess()`'s existing role
(`research/architecture.md` §1, resolving requirements.md's Open Question 3).

## Consequences

- New domain surface (2 sealed interfaces, 1 error family, 1 table) that every relocate/link/picker
  task in `plan.md` depends on — sequenced first (Phase 1) for exactly that reason.
- A graph created before this feature ships has no `storage_locations` row. Absence must default to
  "not `AppOwned`" (sweep-safe, matching today's cache-only behavior) — never inferred as `AppOwned`
  by omission. See ADR-002 for why this direction of the default matters.
- This "never inferred" rule holds even for pre-existing graphs once a real source location is
  actually needed (e.g. the relocate/link flow, `plan.md` Phase 3). Rather than letting a caller
  infer a location ad hoc at the point of use, `StorageLocationResolver` (`plan.md` Story 1.1.4)
  derives it once from the graph's real current state and persists it via `onGraphLocationDetermined`
  before anything reads it as a source — so every consumer still only ever reads a value that came
  from the table, just not always one written at graph-creation time.
- Rejected alternative: extend `GraphBackend`'s flat-enum shape. Rejected because it cannot carry
  per-case data without a parallel side-table, which is strictly more code than a sealed interface
  for the same information.
