# Research: db/repository layer test coverage gaps

## 1. Test infrastructure patterns in this repo

### In-memory SQLite driver setup

`BlockHierarchyCteTest` is the canonical reference for testing SQLDelight
repositories against a real in-memory SQLite driver:

```kotlin
private fun buildRepos(): Pair<SqlDelightBlockRepository, SqlDelightPageRepository> {
    val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
    val database = SteleDatabase(driver)
    return SqlDelightBlockRepository(database) to SqlDelightPageRepository(database)
}
```

`DriverFactory().createDriver("jdbc:sqlite::memory:")` creates an in-process JDBC
driver; `SteleDatabase(driver)` runs `Schema.create()` and all migrations before
returning. This pattern is already proven across many tests in `businessTest/db/`.

### Test source set placement

- `businessTest` — business logic without UI; no X11 dependency; runs under
  `bazel test //kmp:business_tests`. This is the correct target for repository
  and db-layer unit tests.
- `jvmTest` — Compose/Desktop UI tests + screenshot (Roborazzi); requires X11.
  Not needed for data-layer tests.
- `commonTest` — for pure logic with no platform APIs. Repository tests need the
  JVM SQLite driver, so they belong in `businessTest`, not `commonTest`.

### DirectRepositoryWrite opt-in

`SqlDelightBlockRepository` and `SqlDelightAssetRepository` are annotated
`@OptIn(DirectRepositoryWrite::class)`. Tests in `businessTest` that call write
methods directly need the file-level annotation
`@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)`.

### DatabaseWriteActor pattern for UndoManager tests

`UndoManager` takes a `DatabaseWriteActor`. Tests need to construct a real actor:
```kotlin
val actor = DatabaseWriteActor(blockRepo, pageRepo, operationLogger, scope = TestScope())
```
`OperationLogger` takes a `SteleDatabase` and a `sessionId` string.

## 2. SqlDelightBlockRepository untested paths

Major untested paths identified via source inspection (lines 692–1100):

- `moveBlock(uuid, newParentUuid, afterUuid)` — three-arg variant with sibling positioning
- `indentBlock(uuid)` — makes block a child of its preceding sibling
- `outdentBlock(uuid)` — promotes block to parent's next sibling
- `moveBlockUp` / `moveBlockDown` — reorder within parent
- `mergeBlocks(blockUuid, nextBlockUuid, separator)` — concatenate two blocks
- `splitBlock(...)` — split at a position
- `saveBlocksAtomicWithChainRepair` — batch insert + fractional index repair
- `deleteBulk(uuids, deleteChildren)` — multi-block delete
- `saveBlocksDiff(toInsert, toUpdate)` — diff-based save
- `updateBlockContentOnly` — content-only update (no position change)
- `updateBlockContentsForRename` — bulk rename wikilinks
- `getLinkedReferences(pageName, limit, offset)` — paginated linked references
- `cacheEvictPage` / `cacheEvictAll` — LRU cache eviction paths

The existing `BlockHierarchyCteTest` covers the read CTE. `SplitJournalTest`
covers journal splitting but not block structure operations.

## 3. SqlDelightAssetRepository untested paths

`AssetRepositoryTest` uses `InMemoryAssetRepository` throughout — confirmed by
`private fun repo() = InMemoryAssetRepository()` at line 14 of the test file.
Every public method of `SqlDelightAssetRepository` is therefore untested:

- Keyset pagination (`getAssetPage`) — multiple sort orders × media types
- `getOrphanedAssets` / `countOrphanedAssets`
- `getDistinctTags` — JSON list deserialization across multiple rows
- `updateCloudDescription` / `updateOcrText`
- `markMlFailed` / `markMlProcessed`
- `searchAssets` special-char escaping against real FTS

The fix is simple: add a parallel `SqlDelightAssetRepositoryContractTest` that
runs the same test cases against `SqlDelightAssetRepository(database)` with the
in-memory driver.

## 4. UndoManager — no tests exist

`UndoManager` (212 lines, 9% coverage) has zero dedicated test coverage. Key
behaviors to validate:
- `undo()` with UPDATE_BLOCK — restores `before` snapshot
- `undo()` with INSERT_BLOCK — deletes the inserted block
- `undo()` with DELETE_BLOCK — re-inserts the deleted block
- `redo()` after undo — replays the inverted op
- BATCH_START / BATCH_END — atomic group inversion
- SYNC_BARRIER — stops traversal (canUndo = false)
- `canUndo` / `canRedo` StateFlow transitions
- Persistence: `undoneOpIds` is in-memory only; a simulated restart (new
  `UndoManager` instance, same db) should still reconstruct stack from the
  `operations` table

`UndoManager` depends on `OperationLogger` (which logs ops to the DB) and
`DatabaseWriteActor` (which executes inversions). Tests need a real in-memory
database to verify the round-trip.

## 5. SqlDelightSearchRepository gaps

Already at 68% line coverage. Key remaining gaps:
- `searchWithFilters` branches: tag filter, date range, namespace filter, FTS +
  tag combined, empty result sets
- `countPagesByTitle` with `SelectionFilter.TAGS` vs `SelectionFilter.ALL`
- `integrityCheckFts` — rarely tested
- Special-character FTS query escaping (%, \, _)

## 6. RestrictedDatabaseQueries (27% coverage)

`RestrictedDatabaseQueries` acts as a write-gate; coverage rises automatically
as other tests call write methods through it. Adding repository contract tests
against the SQLDelight backend will exercise most of its methods indirectly.
Direct tests needed only for the few methods not reached by other tests (e.g.
`upsertLogicalClock`, `insertWikilinkReferencesBatch`).

## 7. Risk surface

- `FractionalIndexing` — used by `saveBlocksAtomicWithChainRepair`; may produce
  non-deterministic ordering if inputs aren't seeded. Tests need stable position
  values.
- `LruCache` — `SqlDelightBlockRepository` wraps hierarchy queries in a cache.
  Tests that verify cache eviction must construct the repo with a non-zero cache
  size and call `cacheEvictPage` explicitly.
- `OperationLogger.ensureSeqInitialized()` must not be called inside a
  transaction. `UndoManager` tests must call `refreshState()` after setup, not
  inside a transaction block.
