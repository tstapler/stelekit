# Plan: asset-service-test-coverage

## Objective

Raise line coverage on `AssetMoveService`, `AssetIndexService`, and
`AssetBrowserViewModel` from a combined ~13% to ≥40% via headless tests
that run in `businessTest`/`commonTest` (or `jvmTest` for WAL-replay paths
that require a real SQLDelight driver). No source code changes; test files only.

## Task breakdown

### Task 1 — Extend FakeFileSystem with renameFile support
**File:** `kmp/src/commonTest/kotlin/dev/stapler/stelekit/db/sidecar/FakeFileSystem.kt`
**Category:** test
**Estimate:** 1h

Override `renameFile(from, to)` in `FakeFileSystem` to move the entry between
the `textFiles`/`binaryFiles` maps and return `true` on success, `false` if
`from` does not exist. This unblocks the happy-path branch in `AssetMoveService`.

No new files created — edit the existing `FakeFileSystem`. The override is
a 6-line addition and is a net improvement for any future test.

---

### Task 2 — AssetMoveServiceTest (non-WAL paths, businessTest)
**File:** `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/AssetMoveServiceTest.kt`
**Category:** test
**Estimate:** 3h

Covers ACs 1, 2, 3, 4 from requirements:

```
moveAsset_happyPath_renamesFileAndUpdatesDb
moveAsset_renameFails_returnsError
moveAsset_samePath_isNoOp
moveAsset_invalidSubfolder_throws
```

Setup per test:
```kotlin
val fs = FakeFileSystem()   // after Task 1: renameFile returns true
val repo = InMemoryAssetRepository()
val svc = AssetMoveService(repo, restrictedQueries = null)
```

Key invariants to assert:
- After a successful move, `fileSystem.fileExists(newFilePath)` is true and
  `fileSystem.fileExists(oldFilePath)` is false.
- `repo.getAssetByUuid(uuid).first().getOrNull()?.filePath == newFilePath`.
- Rename-failure path returns `Either.Left`.
- Same-path call returns `Either.Right` without calling the filesystem.
- `require`-guard throws `IllegalArgumentException` for unknown subfolder.

Use `runBlocking` (not `runTest`): `AssetMoveService` uses a `Mutex` but is
otherwise pure suspend, no `TestScope` needed.

---

### Task 3 — AssetMoveServiceWalTest (WAL replay, jvmTest)
**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/asset/AssetMoveServiceWalTest.kt`
**Category:** test
**Estimate:** 4h

Covers ACs 5, 6, 7 from requirements (WAL replay scenarios). Uses the existing
in-memory SQLDelight driver pattern from `jvmTest` (same approach as
`AssetPipelineServiceTest`'s integration tests — create a real `SteleDatabase`
with `DriverFactory.createInMemoryDriver()`, obtain `RestrictedDatabaseQueries`
from it).

```
replayPendingMoves_incompleteMove_completesRenameAndClearsWal
replayPendingMoves_alreadyComplete_updatesDbAndClearsWal
replayPendingMoves_bothAbsent_clearsWalRowWithoutError
replayPendingMoves_ambiguous_leavesWalRowAndLogsWarning
```

For each test, insert a WAL row directly via the `RestrictedDatabaseQueries`, set
up the `FakeFileSystem` in the appropriate state, call `replayPendingMoves`, then
assert the DB state and WAL row presence/absence.

---

### Task 4 — AssetIndexServiceTest (businessTest)
**File:** `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/AssetIndexServiceTest.kt`
**Category:** test
**Estimate:** 2h

Covers ACs 8, 9, 10 from requirements:

```
registerAsset_detectsMimeAndBuildsCorrectEntry
registerAsset_savesEntryToRepository
startBackfill_emptyAssetsDir_isNoOp
startBackfill_indexesAllFilesInBoundedBatches
```

For the backfill test: populate a `FakeFileSystem` with 70 files under
`/graph/assets/images/`, call `startBackfill`, poll
`repo.countAssets().getOrNull()` with a timeout loop, then assert that all 70
files were indexed (≤50 in the first batch, remainder in the second).

The `BACKFILL_BATCH_SIZE = 50` constant is accessible for assertion.

For the empty-dir test: provide a `FakeFileSystem` with no `assets/` directory.
Verify `countAssets() == 0L` after the call.

---

### Task 5 — AssetBrowserViewModelTest (businessTest)
**File:** `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserViewModelTest.kt`
**Category:** test
**Estimate:** 4h

Covers ACs 11–16 from requirements.

Pattern (from `GalleryViewModelTest`):
```kotlin
val repo = InMemoryAssetRepository()
// pre-populate repo
val vm = AssetBrowserViewModel(repo, writeActor = null)
val state = runBlocking { vm.uiState.first { !it.isLoading } }
```

Tests:

```
initialLoad_populatesAssetsFromRepository
setFilter_images_resetsAndReloads
setSort_byName_resetsAndReloads
setSearch_debounced_filtersResults
loadMore_appendsNextPageAndAdvancesCursor
deleteAsset_removesFromState
copyMarkdownLink_imageAsset_writesImageSyntax
copyMarkdownLink_nonImageAsset_writesLinkSyntax
showActionMenu_setsActionMenuAsset
dismissActionMenu_clearsActionMenuAsset
```

For `setSearch` debounce: use `runBlocking { delay(350); vm.uiState.first {...} }`.

For `loadMore`: pre-populate repo with 55 entries (PAGE_SIZE=50); assert first
load has 50, then `loadMore()`, assert total 55 and `hasMore = false`.

`copyMarkdownLink` test: inject a `ClipboardProvider` stub that captures the
written string.

---

### Task 6 — Validation run
**Category:** test
**Estimate:** 1h

After all tests are written, run:
```
bazel test //kmp:business_tests
```
and verify zero failures. Check that the 4 new WAL-replay tests also pass via:
```
bazel test //kmp:jvm_tests
```
(with the display check script or `business_tests` only if no display is available).

## Adversarial review

**Risk A — `startBackfill` is fire-and-forget on a real `Dispatchers.Default` scope.**
The test cannot use `TestScope` to advance time. Mitigation: poll-with-timeout
loop on `repo.countAssets()`. This is deterministic on a fast machine but could
flake under heavy load. Counter-mitigation: use a generous timeout (5s) and
document the approach.

**Risk B — `AssetBrowserViewModel.setSearch` has a real 300ms delay.**
A `delay(350)` in `runBlocking` is wall-clock time in test execution. Acceptable
for a test that needs to verify debounce behaviour; add a comment explaining why.

**Risk C — `FakeFileSystem.renameFile` only exists in `commonTest`.**
`AssetMoveServiceTest` (businessTest) needs it. `businessTest` includes `commonTest`
transitive dependencies — confirmed by how `GalleryViewModelTest` uses
`InMemoryImageAnnotationRepository` from `commonMain`. `FakeFileSystem` is in
`commonTest` (not `commonMain`), so it IS available to `businessTest` as a test
dependency. Verified by the existing `AssetPipelineServiceTest` which uses other
`commonTest` utilities.

**Risk D — WAL tests in jvmTest need a display check.**
The WAL tests are pure logic (no Compose), so `jvmTest` runs without X11 for
these specific classes. However, running the full `//kmp:jvm_tests` target still
needs a display for Compose tests. Use `bazel test //kmp:business_tests` as the
primary gate and document this.

**Risk E — `restrictedQueries = null` path skips WAL logging entirely.**
The WAL tests (Task 3) explicitly cover the `restrictedQueries != null` path;
the non-WAL tests (Task 2) cover the `null` path. Both branches of the null
check are exercised across the two test files.

## File map

| New file | Source set | ACs covered |
|---|---|---|
| `FakeFileSystem.kt` (edit existing) | commonTest | AC-17 |
| `AssetMoveServiceTest.kt` | businessTest | AC 1–4 |
| `AssetMoveServiceWalTest.kt` | jvmTest | AC 5–7 |
| `AssetIndexServiceTest.kt` | businessTest | AC 8–10 |
| `AssetBrowserViewModelTest.kt` | businessTest | AC 11–16 |

## Out of scope

- Compose UI tests for `AssetBrowserScreen`, `GalleryScreen`, `AssetDetailScreen`,
  `AssetItemCard` — these require a display and belong in a separate screenshot/
  Robolectric pass.
- `SqlDelightAssetRepository` SQL correctness — already gated by the incremental
  coverage PR (#402) and `UpgradeResilienceTest`.
