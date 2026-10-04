# Pitfalls Research: Wiki Subdir UX

## Summary

This feature involves destructive file operations across multiple platforms with varying
semantics, concurrent git sync, and crash recovery requirements. The research identifies
8 critical risk areas that must be explicitly designed against.

## 1. SAF Directory-Level Operations Gap

- **Risk:** No cross-platform recursive move/copy primitive exists in `PlatformFileSystem`
- **Impact:** Cannot implement "Merge" strategy reliably across platforms
- **Affected code:** `FileSystem.kt` provides `renameFile()` (single-file, destructive) but
  no directory-level recursive move or non-destructive `copyFile()` primitive
- **Design Required:** Add `FileSystem.copyFile()` interface (with platform-specific
  `actual` implementations) OR build merge from read/write/delete primitives. The Merge
  case requires non-destructive copy — `renameFile` alone is insufficient.

## 2. Periodic Rescan Performance Catastrophe

- **Risk:** SAF directory listings are IPC calls (50-500ms each), not local syscalls
- **Impact:** O(graph) performance degradation could take minutes for large graphs on SAF
- **Affected code:** The requirements call for "re-scan candidates on every warm reconcile"
  (C.14), but `GraphManager.kt` warm reconcile currently completes in ~150-200ms per the
  diagnostics log sample. A SAF re-scan could blow past that budget by orders of magnitude.
- **Design Required:** Must use existing `MAX_NESTED_PROBES` cap and benchmark SAF listing
  costs. Consider gating behind a heuristic (only scan when page count < threshold) to
  bound cost. Must be bounded (fixed shallow depth, not recursive) per repo CLAUDE.md
  rules on graph-scale reads.

## 3. Soft-Delete Trash Management

- **Risk:** Completely new artifact requirement with no existing implementation
- **Impact:** Need trash path structure (`.stelekit/trash/<timestamp>/`), retention policy,
  and cleanup mechanism — all new
- **Affected code:** No `.stelekit/trash/` or similar soft-delete area exists anywhere in
  the codebase. The `StorageMoveChoiceDialog` safety note ("old copy stays until you confirm
  it's safe to remove") is copy-only, not enforced by actual code.
- **Design Required:** Integrate with `GraphManager` state, design lifecycle management
  (time-based vs count-based vs until-next-sync retention), and per-API-level SAF
  capabilities. On SAF specifically, true soft-delete (copy to trash) is infeasible — it
  costs another full content-provider round-trip, so the SAF path needs a metadata-marked
  for-deletion approach instead of physical staging.

## 4. Git Racing Condition

- **Risk:** Manual moves could race with `periodicSyncJob` and auto-sync cycles
- **Impact:** Inconsistent state, potential data loss or corruption
- **Affected code:** `GitSyncService.autoSync` / `periodicSyncJob`, `GitSyncService.commitLocalChanges`
  (lines 450-475)
- **Design Required:** Coordinate between `GitSyncService.sync()` and move operations.
  `GitSyncService` already exposes `GitSyncBusyCounter` (GitSyncService.kt:65-68),
  documented as intended for "a quiesce strategy" — that's the existing synchronization
  primitive for sequencing reconciliation commits against in-flight auto-sync ticks,
  solving the git-commit-atomicity rabbit hole. File moves must complete before
  `stageSubdir` is called; DB writes must flush before filesystem moves begin.

## 5. Crash-Resumable Operations

- **Risk:** No existing pattern for marking/resuming partial operations
- **Impact:** Could leave app in inconsistent state after crashes (especially on Android
  process death)
- **Affected code:** No marker file system, no transaction log, no resumption support
  anywhere in the codebase for file operations.
- **Design Required:** Marker files (`.stelekit/move-in-progress.json`) with transaction
  log and resumption support. The architecture.md research notes this should use
  `kotlinx-serialization-json` (already a dependency). Must be checked on next launch.

## 6. SAF Provider Capability Variability

- **Risk:** Android's `DocumentsContract` has no atomic "move" primitive on all
  API levels/providers — a move may require copy-then-delete with partial-failure handling
- **Impact:** Provider-dependent behavior, potential for partial moves on some devices
- **Affected code:** Existing `safRenameFile()` in `PlatformFileSystem.kt` handles the
  fallback pattern already, but only for single files. Directory-level moves need the same
  pattern extended.
- **Design Required:** Capability detection (`FLAG_SUPPORTS_MOVE`) and graceful fallback
  logic. Per-OEM/provider variability means some moves will degrade to copy+delete.

## 7. UI Dialog Extension — Three-Way Choice

- **Risk:** `StorageMoveChoiceDialog` only supports two choices (Relocate/Link), needs
  three (Move/Merge/Leave as-is)
- **Impact:** Semantic and UI design challenges for the "Leave as-is" explicit option
  (which must be logged as an explicit decision, not a silent no-op)
- **Affected code:** `StorageMoveChoiceDialog.kt:45-111` uses stacked `OutlinedButton`s
  for even-handed styling — the three-way version needs either a new composable sibling
  or parameterization that doesn't compromise the existing two-option semantics.
- **Design Required:** A two-stage dialog or three-button neutral styling approach.
  The "Leave as-is" dismissal must be logged as an explicit "left in place" decision
  (brainstorm idea D.27).

## 8. Existing Infrastructure Gaps — Signal Siloed

- **Risk:** Mismatch detection signal exists (`GraphDiagnostics.NESTED GRAPH CANDIDATE`)
  but doesn't surface live in the UI
- **Impact:** Feature requires extending rather than replacing existing code, but
  extension points aren't clean — detection runs only on export, not during warm reconcile
- **Affected code:** `GraphDiagnostics.kt:81-114` (`appendDisk()` →
  `appendDiskCandidate()`) computes the signal but produces a text string only.
  `GraphContentMainArea.kt:95-174` hosts the banner system but `GitDetectionBanner` goes
  silent once any git config is saved.
- **Design Required:** Extract mismatch detection into a reusable function callable from
  both `GraphDiagnosticsCollector` (export path) and a new warm-reconcile hook (proactive
  path), then emit via `StateFlow` on `StelekitViewModel` to drive a new banner condition.

## Implementation Strategy

**Critical Path:** Complete SAF directory-level operations implementation first, as this
enables the core move/merge functionality across all platforms.

**Architectural Dependencies:**
1. SAF `copyDocument` primitive (or fallback stream-copy)
2. Directory traversal with depth limiting (`MAX_NESTED_PROBES`)
3. Platform-agnostic copy/merge logic from existing primitives
4. Crash-safe marker file system with resumption
5. Coordinated sync race condition handling via `GitSyncBusyCounter`

**Performance Constraints:**
- SAF listing cost: 50-500ms per directory (IPC round-trip)
- Must not scan entire graph on every warm reconcile — bounded shallow depth only
- Copy operations can be I/O intensive for large directories

## Conclusion

The research reveals significant architectural gaps that require careful design. The
feature's success depends on addressing platform-specific challenges (especially SAF)
while maintaining consistent behavior. Implementation requires extending existing
primitives rather than adding new libraries, but the SAF gaps and missing directory
operations present substantial implementation challenges that need comprehensive
testing and robust error handling.

The dominant risk is **data safety**: any move/merge touches the user's actual notes.
The requirements' safety guardrails (dry-run manifest, soft-delete trash, resumable
marker file, verify-after-fix re-scan) are not optional polish — they are the core
of the feature and must be implemented before any file writes occur.
