# Plan: db/repository layer test coverage gaps

## Approach

All new tests go in `kmp/src/businessTest/`. Each test class uses
`DriverFactory().createDriver("jdbc:sqlite::memory:")` + `SteleDatabase(driver)`
(the pattern from `BlockHierarchyCteTest`) so tests exercise the real SQL layer
without a filesystem. No source code changes. No new production code.

Implementation is broken into four independent work streams that can be developed
in parallel.

---

## Work stream A — SqlDelightAssetRepositoryContractTest

**Target file:** `businessTest/repository/SqlDelightAssetRepositoryContractTest.kt`

Replace the `InMemoryAssetRepository()` in the existing `AssetRepositoryTest` pattern
with a second test class backed by `SqlDelightAssetRepository(database)`. Keep the
existing `AssetRepositoryTest` (InMemory) intact.

### Test cases to add

1. **CRUD round-trip** — saveAsset, getAssetByUuid, verify all fields (same as InMemory test but on SQLDelight)
2. **getAssetPage BY_DATE_ADDED, null mediaType** — keyset pagination with `cursorMs`/`cursorUuid`, verify continuation token
3. **getAssetPage BY_NAME** — cursorName-based keyset
4. **getAssetPage BY_SIZE** — cursorSize-based keyset
5. **getAssetPage BY_DATE_ADDED, filtered by mediaType** — only IMAGE entries returned
6. **searchAssets special chars** — query with `%`, `_`, `\` must not throw and must return exact match
7. **getOrphanedAssets / countOrphanedAssets** — two assets, one orphaned; verify count and list
8. **getDistinctTags** — multiple assets with overlapping tags; verify deduplication and sort
9. **updateCloudDescription** — saves description + mlTagsSource
10. **updateOcrText** — null → value → null round-trip
11. **countUnprocessedAssets** — distinguishes ml_processed=1 vs ml_failed=1
12. **markMlProcessed / markMlFailed** — state transitions; verify via getAssetByUuid
13. **updatePageUuids** — JSON serialization of list persists correctly

Estimate: **4 h** (13 tests × ~18 min each, setup shared via `@BeforeTest`)

---

## Work stream B — SqlDelightBlockRepositoryTest (untested write paths)

**Target file:** `businessTest/db/SqlDelightBlockRepositoryTest.kt`

Add a new test class (separate from `BlockHierarchyCteTest`); file-level
`@OptIn(DirectRepositoryWrite::class)`.

### Test cases

1. **indentBlock** — block B follows block A at the same level; indent B → B becomes child of A
2. **outdentBlock** — block B is a child of A; outdent B → B becomes sibling of A
3. **moveBlock(uuid, newParentUuid, afterUuid=null)** — move block to first child slot
4. **moveBlock to after a specific sibling** — positional correctness
5. **moveBlockUp / moveBlockDown** — swap ordering with preceding/following sibling
6. **mergeBlocks** — blocks A and B on same page; merged content = A.content + sep + B.content; B deleted
7. **splitBlock** — single block split at offset; two blocks created; original deleted
8. **`saveBlocksDiff(toInsert, toUpdate)`** — atomic insert + update in one call
9. **saveBlocksAtomicWithChainRepair** — batch of blocks with gap in fractional index; positions repaired; order preserved
10. **deleteBulk with deleteChildren=true** — parent + children all removed
11. **deleteBulk with deleteChildren=false** — parent removed; children re-parented to grandparent
12. **updateBlockContentOnly** — only content field changes; position unchanged
13. **updateBlockContentsForRename** — bulk rename wikilinks in content; verify updated and non-matching unchanged
14. **getLinkedReferences paginated** — limit/offset returns correct page
15. **cacheEvictPage** — after eviction, next read hits DB again (verify via fresh data)

Estimate: **8 h** (15 tests, several require multi-block setup; some branch coverage needs negative cases)

---

## Work stream C — UndoManagerTest

**Target file:** `businessTest/db/UndoManagerTest.kt`

Setup: in-memory database, `OperationLogger`, `DatabaseWriteActor`, `UndoManager`.
Tests use `runBlocking { }` (not `runTest`) to avoid coroutine timing issues with the
actor's internal scope; the actor is closed after each test.

### Test cases

1. **undo UPDATE_BLOCK** — log an update (before/after), undo → block restored to before
2. **undo INSERT_BLOCK** — log insert, undo → block deleted
3. **undo DELETE_BLOCK** — log delete, undo → block re-inserted
4. **redo after undo UPDATE_BLOCK** — undo then redo → block back at after state
5. **redo after undo INSERT_BLOCK** — undo then redo → block re-inserted
6. **redo after undo DELETE_BLOCK** — undo then redo → block deleted again
7. **SYNC_BARRIER stops undo** — log op, log barrier, undo → nothing happens; canUndo=false
8. **BATCH_START/END inversion** — batch of two updates, undo → both reverted atomically
9. **canUndo flow** — false initially; true after first op logged; false after undo; true after another op
10. **canRedo flow** — false initially; true after undo; false after redo
11. **multiple sequential undos** — stack drains top-to-bottom
12. **payload parse failure** — corrupt JSON in payload → op is skipped, no crash
13. **refreshState** — new UndoManager instance on same DB reads existing ops → canUndo reflects history

Estimate: **5 h** (13 tests; UndoManager + actor setup is non-trivial)

---

## Work stream D — SqlDelightSearchRepositoryTest (branch coverage)

**Target file:** `businessTest/repository/SqlDelightSearchRepositoryTest.kt`
(new class; note `BacklinkRepositoryTest` already covers backlink paths)

### Test cases

1. **searchBlocksByContent basic** — FTS match returns correct block
2. **searchBlocksByContent empty query** — returns empty list, no crash
3. **searchBlocksByContent special chars** — query with `%` and `"` handled
4. **searchPagesByTitle basic** — FTS page title match
5. **countPagesByTitle SelectionFilter.ALL** — counts across all pages
6. **countPagesByTitle SelectionFilter.TAGS** (or relevant non-ALL filter) — only tagged subset counted
7. **searchWithFilters with tagFilter** — returns only blocks from pages with given tag
8. **searchWithFilters with dateRange** — returns only blocks within date window
9. **searchWithFilters with namespace filter** — namespace-prefixed pages only
10. **searchWithFilters combined (tag + namespace)** — intersection
11. **findBlocksReferencing** — block A references block B; query returns A
12. **rebuildFts** — no crash; subsequent FTS query still works
13. **integrityCheckFts** — no crash on fresh DB
14. **recordPageVisit** — page visit recorded; no crash

Estimate: **4 h**

---

## Work stream E — RestrictedDatabaseQueries gaps (optional / low priority)

`RestrictedDatabaseQueries` coverage rises to ~60 %+ as work streams A–D land
(it's a thin wrapper; most paths will be exercised indirectly). Remaining untested
stubs that need targeted tests:

- `insertWikilinkReferencesBatch` with chunks > 500 entries — verify chunking
- `upsertLogicalClock` — already covered by `UndoManagerTest` via `OperationLogger`

Estimate: **2 h**

---

## Total estimate

| Stream | File | Estimate |
|--------|------|----------|
| A | SqlDelightAssetRepositoryContractTest | 4 h |
| B | SqlDelightBlockRepositoryTest | 8 h |
| C | UndoManagerTest | 5 h |
| D | SqlDelightSearchRepositoryTest | 4 h |
| E | RestrictedDatabaseQueries gaps | 2 h |
| **Total** | | **23 h** |

Streams A, B, C, D are fully independent and can be parallelised.

## Definition of done

- `bazel test //kmp:business_tests` is green with all new tests included.
- JaCoCo report (after a full `bazel test //kmp:jvm_tests` + `jacocoTestReport`
  run) shows:
  - `SqlDelightAssetRepository` ≥ 80 % lines
  - `SqlDelightBlockRepository` ≥ 70 % lines / 50 % branches
  - `UndoManager` ≥ 80 % lines
- No new `@Ignore` annotations introduced.
