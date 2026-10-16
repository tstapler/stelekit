# Requirements: db/repository layer test coverage gaps

## Background

JaCoCo analysis of `stelekit-merges @ e337d58c1e` reports the following hand-written
coverage gaps in `db/` + `repository/` (generated sources excluded):

| File | Lines | Line% | Branch% |
|------|-------|--------|---------|
| SqlDelightBlockRepository.kt | 1414 | 49% | 24% |
| SqlDelightAssetRepository.kt | 331 | 3% | — |
| DatalogBlockRepository.kt | 320 | 48% | — |
| GraphWriter.kt | 408 | 55% | 41% |
| SqlDelightSearchRepository.kt | 528 | 68% | — |
| UndoManager.kt | 212 | 9% | — |
| RestrictedDatabaseQueries.kt | 152 | 27% | — |

Root cause of the AssetRepository gap: `AssetRepositoryTest` exercises only
`InMemoryAssetRepository`, so `SqlDelightAssetRepository`'s 14 methods have
almost zero direct coverage — the SQL logic and JSON codec paths are untested.

Root cause of the BlockRepository gap: most of the complex write operations
(`moveBlock`, `indentBlock`, `outdentBlock`, `mergeBlocks`, `splitBlock`,
`saveBlocksAtomicWithChainRepair`, `deleteBulk`) have no direct tests; the only
SQLDelight-backed block tests are `BlockHierarchyCteTest` (hierarchy CTE),
`SplitJournalTest`, and incidental use in merge/copy tests.

Root cause of UndoManager gap: no unit test file exists for `UndoManager`.

## Goals

1. Raise `SqlDelightAssetRepository` line coverage to ≥ 80 % by adding a
   contract test class that exercises the SQLDelight backend against an
   in-memory SQLite driver (mirroring the BlockHierarchyCteTest pattern).
2. Raise `SqlDelightBlockRepository` line/branch coverage to ≥ 70 % / 50 % by
   adding a `SqlDelightBlockRepositoryTest` that exercises untested write paths
   (indent, outdent, move, merge, split, bulk delete, atomic chain repair).
3. Reach ≥ 80 % line coverage on `UndoManager` by adding `UndoManagerTest`
   covering undo/redo of UPDATE_BLOCK, INSERT_BLOCK, DELETE_BLOCK, batch groups,
   SYNC_BARRIER, and the `canUndo`/`canRedo` state flows.
4. Raise `SqlDelightSearchRepository` branch coverage to ≥ 60 % by testing
   the FTS edge-cases (empty query, special chars, `searchWithFilters`
   combinations, `countPagesByTitle` with filter variants).
5. Add contract tests against the SQLDelight backend for `RestrictedDatabaseQueries`
   write-gate paths that are currently unexercised.

## Out of scope

- `DatalogBlockRepository` — Datalog backend is the legacy path; coverage will
  improve incidentally as contract tests run against both backends if/when the
  factory wires them up.  No dedicated Datalog tests in this item.
- `GraphWriter` — integration-level file-system tests; separate item.
- Generated `SteleDatabaseQueries.kt` / `TelemetryQueries.kt` — generated code
  is excluded from the coverage denominator per the item description.
- Screenshot/UI tests (jvmTest Roborazzi set).

## Acceptance criteria

1. A new `SqlDelightAssetRepositoryTest` in `businessTest/repository/` exercises
   every public method of `SqlDelightAssetRepository` against a real in-memory
   SQLite driver and all tests pass under `bazel test //kmp:business_tests`.
2. A new `SqlDelightBlockRepositoryTest` in `businessTest/db/` covers `moveBlock`,
   `indentBlock`, `outdentBlock`, `mergeBlocks`, `splitBlock`,
   `saveBlocksAtomicWithChainRepair`, `deleteBulk`, `saveBlocksDiff`, and
   `updateBlockContentOnly` against a real in-memory SQLite driver; all pass.
3. A new `UndoManagerTest` in `businessTest/db/` covers `undo`/`redo` for all
   three op types, batch inversion, SYNC_BARRIER stop, `canUndo`/`canRedo` state
   transitions, and persistence across simulated restarts; all pass.
4. `SqlDelightSearchRepositoryTest` in `businessTest/repository/` exercises
   `searchWithFilters` with at least two non-trivial filter combinations (tag,
   date range, namespace), FTS special-character escaping, and `rebuildFts`; all
   pass.
5. No existing passing test regresses; `bazel test //kmp:business_tests` is green.
6. JaCoCo line coverage on `SqlDelightAssetRepository` is ≥ 80 %, on
   `SqlDelightBlockRepository` ≥ 70 %, on `UndoManager` ≥ 80 % (measured after
   the new tests land).
