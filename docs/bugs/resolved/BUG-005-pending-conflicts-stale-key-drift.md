# BUG-005: pendingConflicts Count Drifts From the All Pages Conflicts Filter [SEVERITY: Medium]

**Status**: Resolved
**Discovered**: 2026-07-27 during conflict/sync UI state investigation
**GitHub Issue**: None filed — found and fixed in the same session
**Impact**: The sidebar/banner "N page(s) have unresolved conflict(s)" count and the
"All Pages → conflicts" filter tab read from two different sources of truth that could
drift apart. Users could see "1 conflicts" in the banner while the filtered list rendered
"No conflicted pages found," with no way to clear the stale count short of restarting the app.

## Problem Description

`AppState.pendingConflicts` (`AppState.kt:140`, exposed via `pendingConflictFilePaths`)
is an in-memory `Map<String, PendingConflict>` keyed by raw file-watcher path strings,
populated whenever `observeExternalFileChanges()` sees a disk write to a page that isn't
currently open. `AllPagesScreen`'s conflicts tab instead filters a live DB snapshot
(`AllPagesViewModel.allFilePaths` / `getAllPagesSnapshot()`) by
`it.page.filePath in conflictFilePaths`.

`pendingConflicts` entries were only ever removed by the disk-conflict resolution dialog
flow (`clearPendingConflict()`, fired from `keepLocalChanges`/`acceptDiskVersion`/etc.) or
a false-positive check in `checkAndShowPendingConflict`. Nothing pruned a key when the
underlying page was deleted or renamed (its `filePath` changes), so a bulk delete or
rename of a conflicted page left an orphaned key in the map forever — the count included
it, but the DB-backed filter had nothing at that path to show.

## Reproduction Steps

1. Edit a page's file on disk while the page is not open in the app (triggers a deferred
   `pendingConflicts` entry keyed by the file path).
2. Delete or rename that page from the app (via bulk delete or the rename dialog) without
   first resolving the conflict.
3. Expected: the conflict count drops to 0 and the All Pages conflicts tab is empty.
4. Actual: the banner still reports "1 conflicts," but the tab shows no matching pages.

## Root Cause

Two independent sources of truth (`AppState.pendingConflicts`, keyed by raw path strings
that were never validated against live pages) with no reconciliation step. Any mutation
that changes or removes a page's `filePath` — delete, rename — bypassed the only pruning
mechanism, which lived exclusively in the resolve-dialog flow.

## Files Affected (4 files)

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` — added
  `reconcilePendingConflicts(livePaths: Set<String>)`, and calls to `clearPendingConflict`
  in `bulkDeletePages` and `renamePage`'s `RenameResult.Success` branch
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/AllPagesViewModel.kt` — added
  `allFilePaths: StateFlow<Set<String>>`, the live-path snapshot fed into reconciliation
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ScreenRouter.kt` — wires
  `AllPagesViewModel.allFilePaths` into `reconcilePendingConflicts` via a `LaunchedEffect`
  scoped to `Screen.AllPages`
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/DiskConflictResolutionTest.kt` — regression
  tests

## Fix Approach

Two complementary fixes:

1. **Point fixes at the two known mutation sites**: `bulkDeletePages` now calls
   `clearPendingConflict(filePath)` for every deleted page's path; `renamePage`'s success
   branch calls it for the page's pre-rename `filePath` (the old path can never be
   revisited once the file has moved).
2. **General reconciliation as a backstop**: `reconcilePendingConflicts(livePaths)` drops
   any `pendingConflicts` key not present in the current live path set, called whenever the
   All Pages screen (which already computes the live snapshot for its own filter) is
   active — so the two views can never disagree while that screen is visible, regardless of
   how a page's path became stale.

## Verification

```
./gradlew :kmp:jvmTest --tests "*DiskConflictResolutionTest*" --console=plain
```
46 tests, all passed, including the three new regression tests:
- `reconcilePendingConflicts_drops_a_stale_key_not_present_in_livePaths_but_keeps_a_live_one`
- `bulkDeletePages_clears_the_pendingConflicts_entry_for_the_deleted_pages_file_path`
- `renamePage_clears_the_pendingConflicts_entry_for_the_old_file_path`

No regressions in the 43 pre-existing tests in the file.

## Related Tasks

None.
