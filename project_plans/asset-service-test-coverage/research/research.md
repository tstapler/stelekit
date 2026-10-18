# Research: asset-service-test-coverage

## Existing test infrastructure

### FakeFileSystem — `commonTest/db/sidecar/FakeFileSystem.kt`

An in-memory `FileSystem` used by sidecar and image storage tests. Supports
`readFile`/`writeFile`/`readFileBytes`/`writeFileBytes`/`fileExists`/`listFiles`/
`createDirectory`/`deleteFile`. **`renameFile` inherits the interface default
(`return false`)**—it does not implement a real rename, which means any test
trying to drive `AssetMoveService.moveAsset` through a successful rename path
will always get `renamed == false` and receive the error branch. A one-method
override (`fun renameFile(from, to)` that moves the entry in `binaryFiles`/
`textFiles` and returns `true`) is the only infrastructure gap.

### InMemoryAssetRepository — `commonMain`

Fully functional in-memory implementation of `AssetRepository`. Supports all
methods including the keyset-paginated `getAssetPage`, `getOrphanedAssets`, and
`getDistinctTags` added for the assets-viewer epic. Usable directly in
`AssetBrowserViewModel` tests without any stub.

### `businessTest` build target

`bazel test //kmp:business_tests` runs headless (no Compose renderer, no X11).
Pattern established by `AssetRepositoryTest`, `GalleryViewModelTest`, etc.
Uses `kotlin.test` `@Test` + `runTest` / `runBlocking`.

### GalleryViewModelTest — reference pattern for ViewModel tests

Uses `runBlocking { vm.state.first { !it.isLoading } }` to await async state
stabilisation without real-time delays. The same pattern works for
`AssetBrowserViewModel` since it also exposes a `StateFlow<AssetBrowserUiState>`.

### DatabaseWriteActor

`AssetBrowserViewModel` accepts a nullable `writeActor`. For unit tests, pass
`null`; the VM then calls the repository directly, which is correct for an
`InMemoryAssetRepository` that never blocks.

### AssetMoveService constructor

Takes `(assetRepository, restrictedQueries?)`. For tests covering the WAL-less
path, pass `restrictedQueries = null`; the service logs a warning but still
proceeds with the rename and DB update. For WAL tests, we need a fake
`RestrictedDatabaseQueries` or a real SQLDelight in-memory driver.

**Decision:** WAL tests (`replayPendingMoves`) require `RestrictedDatabaseQueries`.
Looking at how other tests use the SQLDelight in-memory driver:
`AssetPipelineServiceTest` and `MigrationRunnerTest` use `DriverFactory.createInMemoryDriver()`
(JVM only, in `jvmTest`). Since `RestrictedDatabaseQueries` wraps the raw queries
object, WAL-replay tests belong in **`jvmTest`** (or use a stub).

For `businessTest` compatibility, the WAL integration path can be tested with a
hand-rolled stub that records `insertPendingMove` / `deletePendingMove` calls.
However, the `selectAllPendingMoves` is a SQLDelight-generated query with
`asFlow().mapToList()` — the stub needs to return an in-memory flow too.

**Simpler approach (confirmed feasible):** Stub `RestrictedDatabaseQueries` just
enough for the WAL tests to run in `businessTest`:
- Expose an in-memory list for pending moves, a `lastId` counter.
- `insertPendingMove(...)` appends to the list, returns.
- `deletePendingMove(id)` removes from the list.
- `lastInsertRowId()` returns `lastId++`.
- `rawQueries.selectAllPendingMoves()` returns a `MutableStateFlow`-backed query
  that `asFlow().mapToList()` can collect.

However, `RestrictedDatabaseQueries` is a concrete class wrapping `SteleDatabaseQueries`,
not an interface—it cannot be directly subclassed. Looking at the actual
signature in `AssetMoveService`:

```kotlin
class AssetMoveService(
    private val assetRepository: AssetRepository,
    private val restrictedQueries: RestrictedDatabaseQueries?,
)
```

**Alternative:** Test WAL insertion/deletion side-effects via an `InMemoryAssetRepository`
combined with a real in-memory SQLDelight driver. This works in `jvmTest` but not
`businessTest`. For `businessTest`, testing only the `restrictedQueries = null` code
path (no WAL logging) is acceptable for the happy path; the WAL-full path can live
in `jvmTest/asset/AssetMoveServiceWalTest.kt`.

**Resolution:**
- `businessTest/AssetMoveServiceTest.kt` — non-WAL paths: happy path, rename
  failure, same-path no-op, invalid subfolder (all work with `restrictedQueries = null`).
- `jvmTest/asset/AssetMoveServiceWalTest.kt` — WAL replay scenarios using an
  in-memory SQLDelight driver (matches the existing pattern in `jvmTest`).

## AssetIndexService: `startBackfill` is fire-and-forget

`startBackfill` launches on an internal `CoroutineScope` (not `TestScope`), so
`runTest` won't automatically advance it. The testable path for batch processing
is the private `backfillGraph`—but since it's private, the test must invoke
`startBackfill` and then poll/wait for the repository to be populated. Using
`TestCoroutineScheduler` is not possible (external scope). The practical approach:
`startBackfill` + `delay` or a loop polling the repository with a timeout. Since
tests should be deterministic, a better pattern is calling the internal path
indirectly: populate a `FakeFileSystem` with files, call `startBackfill`, then
poll `assetRepository.countAssets()` in a loop with timeout. The `BACKFILL_BATCH_SIZE`
constant (`= 50`) is accessible for batch-count verification.

## Coverage arithmetic

Current baseline (JVM only): lines 250/1917 = 13.0%.

Adding tests for the three targets at the expected exercise rates:
- `AssetMoveService` 120 lines × 80% = ~96 new lines covered
- `AssetIndexService` 95 lines × 80% = ~76 new lines covered (38 already covered)
- `AssetBrowserViewModel` 208 lines × 60% = ~125 new lines covered

New covered: ~297 lines (some overlap with existing 250).
Conservative estimate after overlap: 250 + 250 = 500 / 1917 ≈ **26%** — roughly doubles
current coverage on the measured packages.

Overall project coverage impact is smaller (36338 lines covered out of 71945 = 50.5% baseline);
adding ~250 lines brings it to ~50.9%. These packages are not on the critical path for
the overall gate, but the item description calls them out as data-loss-adjacent.

## Risk: AssetBrowserViewModel uses `Dispatchers.Default` internally

`AssetBrowserViewModel` owns a real `Dispatchers.Default` scope, so its async
loads race with test thread. Pattern: `uiState.first { !it.isLoading }` to await
the initial load, same as `GalleryViewModelTest`.

`setSearch` introduces a real 300ms `delay` — tests that exercise search
debouncing need `runBlocking` plus an explicit `delay(350)`. Since the VM uses a
real scope, `TestScope` cannot control its internal scheduler. The practical
approach: `delay(350)` in `runBlocking` for the debounce test.

## Existing tests that already cover adjacent logic

- `AssetRepositoryTest` — full CRUD on `InMemoryAssetRepository` ✓
- `MimeTypeDetectorTest` — extension + magic-bytes detection ✓
- `AssetStoragePathResolverTest` — subfolder/path resolution ✓
- `AssetPipelineServiceTest` — plugin orchestration ✓
- `AssetFilterTest` — `AssetFilter.all` ordering ✓
- `GalleryViewModelTest` — ViewModel state management pattern ✓
