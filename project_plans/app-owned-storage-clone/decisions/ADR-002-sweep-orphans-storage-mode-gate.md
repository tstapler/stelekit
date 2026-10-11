# ADR-002: `GitShadowWorktree.sweepOrphans()` must be gated on persisted storage mode before any `AppOwned` promotion ships

**Status**: Accepted (blocking)
**Date**: 2026-09-12
**Project**: app-owned-storage-clone

## Context

`GitShadowWorktree.sweepOrphans()` (`GitShadowWorktree.kt:405-423`) deletes any shadow-worktree
directory whose `.last-used` marker is older than `DEFAULT_MAX_AGE_MILLIS` (60 days). Its
documented rationale (`GitShadowWorktree.kt:395-399`) is that this is safe because the shadow tree
is always recoverable by re-syncing from the SAF folder of record — i.e., it is a cache, never the
only copy.

This project's entire purpose is to let a user choose `AppOwned` (the shadow-worktree directory) as
**primary** storage, with no SAF folder behind it. The instant that happens for a given graph,
`sweepOrphans()`'s safety rationale is false for that graph: a 60-day gap in opening the app (or,
more precisely, in whatever touches the `.last-used` marker — verify the actual update triggers,
don't assume "app opened" is a reliable proxy) causes silent, unrecoverable, total data loss for a
graph the user explicitly asked to keep "in the app." This is `research/architecture.md` §5's
highest-severity finding (item 8) and `research/pitfalls.md` §2's parallel finding on
`allowBackup="false"` compounding it (no OS-level Auto Backup safety net either).

## Decision

Before any code path can create a graph with `StorageLocation.AppOwned` as primary (i.e., before
Phase 2's picker ships an `AppOwned` row that Android's git-clone flow can select), `sweepOrphans()`
must consult the persisted `StorageLocation` for that `graphId` (ADR-001's `storage_locations`
table) and unconditionally skip deletion for any graph whose location is `AppOwned`, regardless of
marker age.

This is sequenced as a blocking task in `plan.md` Phase 1 (Epic 1.2), before the Android
picker-integration stories in Phase 2 — not shipped as a follow-on hardening pass. A graph with no
`storage_locations` row (every graph that exists before this migration) must default to "not
`AppOwned`" so the sweep's existing, correct, cache-only behavior is unchanged for every graph that
predates this feature.

## Consequences

- Adds one read (persisted `StorageLocation` lookup) to `sweepOrphans()`'s hot path, run once at
  startup per shadow directory — negligible cost, but must not itself throw and abort the sweep for
  unrelated graphs (wrap per-directory, not around the whole sweep loop).
- Regression test required (`plan.md` Phase 1 Epic 1.2): an `AppOwned` graph's shadow directory with
  an artificially aged `.last-used` marker must survive a `sweepOrphans()` call.
- Does not by itself solve the `allowBackup="false"` risk for plain (non-git) `AppOwned` graphs —
  that is a distinct decision, see ADR-003.
