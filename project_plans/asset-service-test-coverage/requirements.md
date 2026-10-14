# Requirements: asset-service-test-coverage

## Context

Measured on branch `stelekit-merges @ e337d58c1e` via `:kmp:jvmTest` + `:kmp:jacocoTestReport`.
35 files across `ui.assets`, `ui.gallery`, `asset` packages: lines 250/1917 = **13.0%**, branches 113/1908 = **5.9%**.

### Files with lowest coverage

| File | Lines | Coverage |
|------|-------|----------|
| AssetDetailScreen.kt | 294 | 4% |
| GalleryScreen.kt | 226 | 0% |
| AssetBrowserScreen.kt | 221 | 0% |
| AssetBrowserViewModel.kt | 208 | 0% |
| AssetItemCard.kt | 125 | 0% |
| AssetMoveService.kt | 120 | 0% |
| AssetIndexService.kt | 95 | 43% |
| AssetPipelineService.kt | 86 | 43% |
| MimeTypeDetector.kt | 62 | 48% |

## Scope

This item focuses on the non-UI services and view models where logic can be
covered by `businessTest`/`commonTest` (no renderer, no X11 display needed):
`AssetMoveService`, `AssetIndexService`, and `AssetBrowserViewModel`. Compose
UI screens (`AssetBrowserScreen`, `GalleryScreen`, `AssetDetailScreen`,
`AssetItemCard`) are out of scope for this item — those belong to a separate
screenshot/Robolectric pass.

## Goals

1. Drive line coverage on `AssetMoveService` from 0% to ≥80%.
2. Drive line coverage on `AssetIndexService` from 43% to ≥80%.
3. Drive line coverage on `AssetBrowserViewModel` from 0% to ≥60%.
4. Do not reduce overall JVM test count or cause regressions.
5. All new tests run in `businessTest` or `commonTest` (headless, no display required).

## Acceptance Criteria

### AC-1: AssetMoveService — happy path
A test verifies that `moveAsset` with a valid subfolder renames the file on disk,
updates the DB row via the repository, and deletes the WAL entry.

### AC-2: AssetMoveService — rename failure returns error
A test verifies that when `FileSystem.renameFile` returns `false`, `moveAsset`
returns `Either.Left(DomainError)` and does not attempt to update the DB.

### AC-3: AssetMoveService — same-path no-op
A test verifies that moving an asset to the folder it already lives in returns
`Either.Right(Unit)` without touching the filesystem or DB.

### AC-4: AssetMoveService — invalid subfolder throws
A test verifies that passing a subfolder not in `allowedSubfolders` throws
`IllegalArgumentException` (consistent with the `require` guard in the code).

### AC-5: AssetMoveService — WAL replay: incomplete move
A WAL row with `old_file_path` present on disk and `new_file_path` absent triggers
the file rename + DB update + WAL deletion in `replayPendingMoves`.

### AC-6: AssetMoveService — WAL replay: already complete
A WAL row where `old_file_path` is absent and `new_file_path` is present triggers
only a DB update + WAL deletion (no second rename attempt).

### AC-7: AssetMoveService — WAL replay: both files absent
A WAL row where neither path exists on disk is cleared without error.

### AC-8: AssetIndexService — registerAsset builds correct entry
`registerAsset` for a known file path produces an `AssetEntry` with the correct
`mediaType`, `subfolder`, `relativePath`, and `sizeBytes` determined from the
file's MIME detection, and saves it to the repository.

### AC-9: AssetIndexService — backfill processes files in bounded batches
`startBackfill` on a directory with >50 files indexes all of them grouped into
batches of ≤50, without loading more than `BACKFILL_BATCH_SIZE` at a time.

### AC-10: AssetIndexService — empty assets directory is a no-op
`startBackfill` on a graph root with no `assets/` directory (or an empty one)
completes without error and makes no repository calls.

### AC-11: AssetBrowserViewModel — initial load populates state
Constructing `AssetBrowserViewModel` with a pre-populated `InMemoryAssetRepository`
results in `uiState.assets` containing the expected entries once `isLoading` is false.

### AC-12: AssetBrowserViewModel — setFilter resets cursor and reloads
`setFilter(AssetFilter.IMAGES)` clears pagination cursors, issues a fresh load,
and the resulting `uiState.selectedFilter` equals `AssetFilter.IMAGES`.

### AC-13: AssetBrowserViewModel — setSearch triggers debounced reload
`setSearch("sunrise")` eventually produces a state with only assets whose path
contains "sunrise" (verified after the 300ms debounce elapses in a test context).

### AC-14: AssetBrowserViewModel — loadMore appends page and advances cursor
After the first page is loaded, `loadMore` appends additional assets to
`uiState.assets` and advances the keyset cursor fields.

### AC-15: AssetBrowserViewModel — deleteAsset removes entry from state
`deleteAsset(asset)` results in `uiState.assets` no longer containing that asset.

### AC-16: AssetBrowserViewModel — copyMarkdownLink generates correct markdown
For an IMAGE asset, `copyMarkdownLink` writes `![filename](relativePath)` to the
`ClipboardProvider`; for non-image assets it writes `[filename](relativePath)`.

### AC-17: FakeFileSystem supports renameFile
The `FakeFileSystem` in `commonTest` overrides `renameFile` so that a successful
rename moves the file content to the new path and removes it from the old path.
Tests for `AssetMoveService` rely on this behaviour.

### AC-18: All new tests pass in `bazel test //kmp:business_tests`
Every new test is placed in `businessTest` or `commonTest` source sets, runs
headless, and passes in CI without X11.
