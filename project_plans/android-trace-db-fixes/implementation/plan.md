# Implementation Plan: android-trace-db-fixes

**Feature**: Eliminate 300–2,000x warm-DB block save regression and ~1,000ms inter-span queue waits confirmed by Android session trace
**Date**: 2026-06-02
**Status**: Ready for implementation
**ADRs**: None — all fixes use existing SQLDelight, Arrow, and Kotlin coroutines patterns

---

## Requirements Corrections (Research-Confirmed)

The following requirements in `requirements.md` describe issues that are **already fixed** in the codebase and must NOT be re-implemented:

1. **Fix 2 (missing indexes)** — ALREADY DONE. `idx_pages_name`, `idx_blocks_page_position`, `idx_blocks_page_hash` all exist in `SteleDatabase.sq` and the covering migrations (`covering_indexes_page_blocks`, `drop_subsumed_blocks_single_column_indexes`). No new indexes needed.

2. **Fix 3 (reads routed through write actor)** — NOT THE ROOT CAUSE. `pageRepository.getPageByName()` and `blockRepository.getBlocksForPage()` already bypass the actor and use `PlatformDispatcher.DB` directly. The ~1,000ms inter-span gaps are queue wait time accumulated by the calling coroutine awaiting `writeActor.savePage()` and `writeActor.saveBlocks()` via `sendAndAwait()` — not because reads go through the actor.

The **real fixes** are:

- **Fix A**: Add `updateBlockFull` SQL UPDATE statement covering all mutable block fields → route warm-path `toUpdate` blocks through it in `saveBlocks`, avoiding `INSERT OR REPLACE`'s DELETE+INSERT+double-FTS-trigger storm
- **Fix B** (= requirements Fix 4): Collapse `writeActor.savePage()` + `writeActor.saveBlocks()` in `dispatchFullBlockWrites` into one composite `actor.execute { }` lambda, eliminating 2 `CompletableDeferred.await()` suspension points

---

## Dependency Visualization

```
Fix A: updateBlockFull SQL                        Fix B: Composite actor execute
  │                                                  │
  ▼                                                  ▼
1a. Add updateBlockFull query               2a. Identify call site in GraphLoader
  to SteleDatabase.sq                           (lines 1363–1389, dispatchFullBlockWrites)
  │                                              │
  ▼                                              ▼
1b. Add forwarding stub                    2b. Rewrite dispatchFullBlockWrites to use
  to RestrictedDatabaseQueries                  actor.execute { } for savePage+saveBlocks
  │                                              │
  ▼                                              ▼
1c. Update saveBlocks in                   2c. Move getBlocksForPage read before actor call
  SqlDelightBlockRepository to use              (already happens via lookup.cachedBlocks,
  updateBlockFull for toUpdate blocks           verify and clean up redundant read at line 1485)
  │                                              │
  ▼                                              ▼
1d. Tests: verify warm-path UPDATE          2d. Ensure evictHierarchyForPage called after
  does not fire DELETE trigger                  composite write
  │                                              │
  └──────────────────┬───────────────────────────┘
                     ▼
              3. Regression test
              (businessTest: warm-DB save
               does not exceed time budget;
               composite execute reduces
               actor round-trips to 1)
```

---

## Phase 1: Fix A — updateBlockFull to Replace INSERT OR REPLACE on Warm Path

### Epic 1.1: Add updateBlockFull SQL and Wire Through Repository

**Goal**: Change `saveBlocks` so that blocks in the `toUpdate` set use a targeted UPDATE statement instead of `INSERT OR REPLACE`, reducing per-block warm-path cost from ~16 B-tree+FTS ops to ~7.

#### Story 1.1.1: Add updateBlockFull query to SteleDatabase.sq

**As a** developer, **I want** a `updateBlockFull` SQL statement that updates all mutable block fields, **so that** warm-path block saves avoid the DELETE+INSERT+FTS-double-trigger overhead of `INSERT OR REPLACE`.

**Acceptance Criteria**:
- `updateBlockFull` query added after `updateBlockContent` in `SteleDatabase.sq`
- Updates all mutable fields: `page_uuid`, `parent_uuid`, `left_uuid`, `content`, `level`, `position`, `updated_at`, `properties`, `version = version + 1`, `content_hash`, `block_type`
- Does NOT update `uuid` or `created_at` (immutable identity fields)
- `WHERE uuid = ?` clause

**Files**:
- `/home/tstapler/Programming/stelekit/kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`

##### Task 1.1.1a: Add updateBlockFull query to SteleDatabase.sq (~3 min)

Insert after line 236 (after `updateBlockContent:` query):

```sql
updateBlockFull:
UPDATE blocks SET
    page_uuid = ?, parent_uuid = ?, left_uuid = ?, content = ?,
    level = ?, position = ?, updated_at = ?, properties = ?,
    version = version + 1, content_hash = ?, block_type = ?
WHERE uuid = ?;
```

Files: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`

---

#### Story 1.1.2 (CORRECTED): Add saveBlocksUpdate to BlockWriteRepository interface

**As a** developer, **I want** `saveBlocksUpdate` added to `BlockWriteRepository` (not `BlockRepository`), **so that** the interface hierarchy is correct and the method is inherited by `BlockRepository` correctly.

**Acceptance Criteria**:
- `saveBlocksUpdate` is added to `BlockWriteRepository.kt` with `@DirectRepositoryWrite` annotation
- `FakeBlockRepository` in `FakeRepositories.kt` (jvmTest) implements `saveBlocksUpdate` via `override suspend fun saveBlocksUpdate(blocks: List<Block>) = saveBlocks(blocks)`
- Compilation succeeds with no missing-override errors

**Files**:
- `/home/tstapler/Programming/stelekit/kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/BlockWriteRepository.kt`
- `/home/tstapler/Programming/stelekit/kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/FakeRepositories.kt`

##### Task 1.1.2a: Add saveBlocksUpdate to BlockWriteRepository (~2 min)

Add after `saveBlocks` in `BlockWriteRepository.kt`:
```kotlin
/**
 * Save multiple existing blocks using targeted UPDATE statements (not INSERT OR REPLACE).
 * Use for the warm-path diff.toUpdate list — avoids DELETE+INSERT FTS5 trigger storms.
 */
@DirectRepositoryWrite
suspend fun saveBlocksUpdate(blocks: List<Block>): Either<DomainError, Unit>
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/BlockWriteRepository.kt`

##### Task 1.1.2b: Add saveBlocksUpdate override to FakeBlockRepository (~2 min)

In `FakeRepositories.kt`, after the `saveBlocks` override (line ~174):
```kotlin
override suspend fun saveBlocksUpdate(blocks: List<Block>): Either<DomainError, Unit> =
    saveBlocks(blocks)
```

Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/FakeRepositories.kt`

---

#### Story 1.1.4: Add updateBlockFull forwarding stub to RestrictedDatabaseQueries

**As a** developer, **I want** `RestrictedDatabaseQueries` to expose `updateBlockFull` with `@DirectSqlWrite`, **so that** the write enforcement contract is maintained when the new query is called from the repository.

**Acceptance Criteria**:
- `updateBlockFull` forwarding stub added to `RestrictedDatabaseQueries` with `@DirectSqlWrite`
- Parameter order matches the `SteleDatabase.sq` query (page_uuid, parent_uuid, left_uuid, content, level, position, updated_at, properties, content_hash, block_type, uuid)
- No regressions in compilation

**Files**:
- `/home/tstapler/Programming/stelekit/kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`

##### Task 1.1.4a: Add updateBlockFull stub in RestrictedDatabaseQueries (~3 min)

Add after the `updateBlockContent` stub (around line 76–77):

```kotlin
@DirectSqlWrite
suspend fun updateBlockFull(
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: Long,
    updated_at: Long,
    properties: String?,
    content_hash: String?,
    block_type: String,
    uuid: String,
): Long = queries.updateBlockFull(
    page_uuid, parent_uuid, left_uuid, content, level, position,
    updated_at, properties, content_hash, block_type, uuid,
)
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`

---

#### Story 1.1.5: Implement saveBlocksUpdate in SqlDelightBlockRepository

**As a** developer, **I want** `SqlDelightBlockRepository` to implement `saveBlocksUpdate` using `updateBlockFull`, **so that** warm-path saves do not trigger DELETE+INSERT FTS5 trigger storms.

**Acceptance Criteria**:
- `saveBlocksUpdate` is implemented in `SqlDelightBlockRepository` using `updateBlockFull` for all blocks
- All mutable fields are updated: `page_uuid`, `parent_uuid`, `left_uuid`, `content`, `level`, `position`, `updated_at`, `properties`, `version+1`, `content_hash`, `block_type`
- Uses the same `chunked(WRITE_CHUNK_SIZE)` + `queries.transaction { }` structure as `saveBlocks`
- Both `saveBlocks` (INSERT OR REPLACE — for new blocks) and `saveBlocksUpdate` remain separate methods
- `jvmTest` passes; no regressions in `DatalogBlockRepositoryTest` or `GraphLoaderTest`

**Note on design**: `DiffMerge.diff()` already separates `diff.toInsert` from `diff.toUpdate` in `dispatchFullBlockWrites` (GraphLoader.kt line 1369–1380). The composite execute in Epic 2.1 will call `blockRepository.saveBlocks(diff.toInsert)` for new blocks and `blockRepository.saveBlocksUpdate(diff.toUpdate)` for existing blocks. This keeps `saveBlocks` signature unchanged (backward-compatible with METADATA_ONLY path and tests).

**Files**:
- `/home/tstapler/Programming/stelekit/kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

##### Task 1.1.5a: Implement saveBlocksUpdate in SqlDelightBlockRepository (~5 min)

Add after `saveBlocks` (line 289):
```kotlin
override suspend fun saveBlocksUpdate(blocks: List<Block>): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
    try {
        blocks.chunked(WRITE_CHUNK_SIZE).forEach { chunk ->
            queries.transaction {
                chunk.forEach { block ->
                    queries.updateBlockFull(
                        block.pageUuid.value,
                        block.parentUuid,
                        block.leftUuid,
                        block.content,
                        block.level.toLong(),
                        block.position.toLong(),
                        block.updatedAt.toEpochMilliseconds(),
                        block.properties.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { null },
                        block.contentHash ?: ContentHasher.sha256ForContent(block.content),
                        block.blockType,
                        block.uuid.value,
                    )
                }
            }
        }
        Unit.right()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
    }
}
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

---

## Phase 2: Fix B — Composite Actor Execute in dispatchFullBlockWrites

### Epic 2.1: Collapse Sequential Actor Calls into One execute { } Lambda

**Goal**: Eliminate the two `CompletableDeferred.await()` suspensions in `dispatchFullBlockWrites` that cause ~1,000ms inter-span queue waits when a LOW-priority bulk load is behind HIGH-priority actor work.

#### Story 2.1.1: Rewrite dispatchFullBlockWrites to use composite actor.execute { }

**As a** developer, **I want** `savePage` and `saveBlocks` (and `saveBlocksUpdate`) for a single page to execute inside one `actor.execute { }` lambda, **so that** the calling coroutine suspends only once per page instead of twice.

**Acceptance Criteria**:
- `writeActor.savePage(page, priority)` call at GraphLoader.kt line 1466 is removed
- `writeActor.saveBlocks(blocksToWrite, priority)` call at GraphLoader.kt line 1383 is removed
- Both are replaced by a single `writeActor.execute(priority) { ... }` call
- Inside the lambda: `pageRepository.savePage(page)` called directly (NOT `writeActor.savePage`)
- Inside the lambda: `blockRepository.saveBlocks(diff.toInsert)` for new blocks (if non-empty)
- Inside the lambda: `blockRepository.saveBlocksUpdate(diff.toUpdate)` for existing blocks (if non-empty)
- After the composite `execute` completes: `(blockRepository as? SqlDelightBlockRepository)?.evictHierarchyForPage(pageUuid.value)` is called
- `savePage` failure still short-circuits block writes (return an `Either.Left` from the lambda)
- The `deleteBlock` calls for `diff.toDelete` remain as individual `writeActor.deleteBlock(uuid)` calls BEFORE the composite execute (they are already fire-and-forget with `.onLeft` logging)
- `jvmTest` and `androidUnitTest` pass

**Critical constraint**: The composite `execute { }` lambda must NOT call `writeActor.*` methods — only repository methods directly. Calling actor methods from inside the actor's `execute { }` lambda deadlocks.

**Files**:
- `/home/tstapler/Programming/stelekit/kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`

##### Task 2.1.1a: Map the exact write sites and control flow in parseAndSavePage (~2 min)

The current flow in `parseAndSavePage` (lines 1408–1525):

```
line 1448: lookupExistingPageAndCheckFreshness()      // direct read, no actor
line 1455: markdownParser.parsePage(content)           // CPU only
line 1459: buildPageModel(...)                         // CPU only
line 1466: writeActor.savePage(page, priority)         // ← STANDALONE ACTOR CALL #1
            ↑ savePage failure short-circuits here (lines 1468–1473)
line 1478: if (mode == METADATA_ONLY) →               // exits here — NEVER reaches dispatchFullBlockWrites
               saveMetadataOnlyBlocks(...)             // calls writeActor.saveBlocks(stubs)
               return@withLock
line 1484: existingBlocks = lookup.cachedBlocks ?: blockRepository.getBlocksForPage(...) // direct read
line 1510: dispatchFullBlockWrites(...)                // calls writeActor.saveBlocks at line 1383
```

**Key finding**: The standalone `writeActor.savePage(page, priority)` at line 1466 serves BOTH paths:
- METADATA_ONLY: calls savePage at 1466 → then saveMetadataOnlyBlocks (stubs) → returns
- FULL mode: calls savePage at 1466 → then dispatchFullBlockWrites (which calls saveBlocks)

**Correct refactoring approach** (avoids METADATA_ONLY regression):
1. Keep `writeActor.savePage(page, priority)` at line 1466 for the METADATA_ONLY path only
2. For FULL mode, skip the standalone savePage and move it into the composite execute inside `dispatchFullBlockWrites`

The cleanest restructuring:

```kotlin
// Line 1466 area — KEEP for METADATA_ONLY, skip for FULL mode:
if (mode == ParseMode.METADATA_ONLY) {
    val savePageResult = writeActor.savePage(page, priority)
    if (savePageResult.isLeft()) { ... return@withLock }
    saveMetadataOnlyBlocks(...)
    return@withLock
}

// FULL mode continues here — no savePage call yet
val existingBlocks = lookup.cachedBlocks ?: blockRepository.getBlocksForPage(pageUuid).first().getOrNull() ?: emptyList()
// ... process blocks ...
dispatchFullBlockWrites(
    filePathStr, content, existingBlocks, blocksToSave, page, priority, traceId, rootSpan.spanId
    // ↑ pass `page` so the composite execute can call pageRepository.savePage(page) inside the lambda
)
```

`dispatchFullBlockWrites` gains a `page: Page` parameter and handles `pageRepository.savePage(page)` inside the composite execute.

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`

##### Task 2.1.1b: Rewrite parseAndSavePage and dispatchFullBlockWrites for composite execute (~5 min)

**Step 1**: Update `parseAndSavePage` (lines ~1465–1512) to restructure the savePage/METADATA_ONLY/FULL control flow as described in Task 2.1.1a.

**Step 2**: Update `dispatchFullBlockWrites` signature to add `page: Page` parameter:
```kotlin
private suspend fun dispatchFullBlockWrites(
    filePath: String,
    content: String,
    existingBlocks: List<Block>,
    blocksToSave: List<Block>,
    page: Page,               // NEW — needed for composite execute
    priority: DatabaseWriteActor.Priority,
    traceId: String,
    parentSpanId: String,
)
```

**Step 3**: Replace the else-branch of `dispatchFullBlockWrites` (lines 1363–1389). The new structure:

```kotlin
else -> {
    val existingSummaries = existingBlocks.map { b ->
        DiffMerge.ExistingBlockSummary(uuid = b.uuid, contentHash = b.contentHash, isLoaded = b.isLoaded)
    }
    val diffSpan = Span("diff", traceId, parentSpanId)
    val diff = DiffMerge.diff(existingSummaries, blocksToSave)
    diffSpan.finish("OK",
        "to.insert" to diff.toInsert.size.toString(),
        "to.delete" to diff.toDelete.size.toString()
    )

    // Deletions run before the composite write to avoid FK constraint violations
    // (a block being re-inserted after structural change must be deleted first).
    diff.toDelete.forEach { uuid ->
        writeActor.deleteBlock(uuid).onLeft { e ->
            logger.warn("deleteBlock failed for $uuid in $filePath: ${e.message}")
        }
    }

    val blocksToInsert = diff.toInsert
    val blocksToUpdate = diff.toUpdate
    if (blocksToInsert.isNotEmpty() || blocksToUpdate.isNotEmpty()) {
        val saveBlocksSpan = Span("db.saveBlocks", traceId, parentSpanId)
        // Single actor.execute { } for savePage + saveBlocks — eliminates 2 await() suspensions
        val compositeResult = writeActor.execute(priority) {
            val pageResult = pageRepository.savePage(page)
            if (pageResult.isLeft()) return@execute pageResult
            if (blocksToInsert.isNotEmpty()) {
                val r = blockRepository.saveBlocks(blocksToInsert)
                if (r.isLeft()) return@execute r
            }
            if (blocksToUpdate.isNotEmpty()) {
                val r = blockRepository.saveBlocksUpdate(blocksToUpdate)
                if (r.isLeft()) return@execute r
            }
            Unit.right()
        }
        compositeResult.onLeft { e ->
            logger.warn("composite savePage+saveBlocks failed for $filePath: ${e.message}")
            _writeErrors.tryEmit(WriteError(filePath, blocksToInsert.size + blocksToUpdate.size, e))
        }
        saveBlocksSpan.finish("OK", "block.count" to (blocksToInsert.size + blocksToUpdate.size).toString())
        (blockRepository as? SqlDelightBlockRepository)?.evictHierarchyForPage(pageUuid.value)
    } else {
        // No block changes — still need to save the page (e.g. metadata update)
        writeActor.savePage(page, priority).onLeft { e ->
            logger.warn("savePage (no blocks) failed for $filePath: ${e.message}")
            _writeErrors.tryEmit(WriteError(filePath, 0, e))
        }
    }
}
```

**Note**: The standalone `writeActor.savePage(page, priority)` call at line 1466 (before `dispatchFullBlockWrites`) must be removed, since `savePage` is now inside the composite execute. Update the `savePageResult` failure-check accordingly — the composite result covers both savePage and saveBlocks failure.

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`

##### Task 2.1.1c: Remove the standalone savePage call from parseAndSavePage (~3 min)

At GraphLoader.kt lines 1465–1473:
```kotlin
// REMOVE THIS BLOCK:
val savePageSpan = Span("db.savePage", traceId, rootSpan.spanId)
val savePageResult = writeActor.savePage(page, priority)
savePageSpan.finish()
if (savePageResult.isLeft()) {
    val e = savePageResult.leftOrNull()!!
    logger.warn("savePage failed for $filePath — skipping block writes to prevent FK violation: ${e.message}")
    _writeErrors.tryEmit(WriteError(filePathStr, 0, e))
    return@withLock
}
```

The failure case is now handled inside the composite execute in `dispatchFullBlockWrites`. The `dispatchFullBlockWrites` call must be updated to pass `page` so the lambda can call `pageRepository.savePage(page)`.

Update the `dispatchFullBlockWrites` signature to accept `page: Page` and use it inside the lambda.

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`

---

## Phase 3: Tests

### Epic 3.1: Regression Tests

**Goal**: Add automated tests that would catch regressions to either fix if they are accidentally reverted.

#### Story 3.1.1: Unit test — saveBlocksUpdate fires UPDATE not DELETE+INSERT

**As a** developer, **I want** a test that verifies `saveBlocksUpdate` does not delete existing rows (no FTS delete trigger), **so that** the warm-path performance fix is protected against regression.

**Acceptance Criteria**:
- Test creates a page + blocks in an in-memory DB (JVM test, uses `PooledJdbcSqliteDriver` + `SteleDatabase.Schema.create`)
- Inserts initial blocks via `saveBlocks`
- Calls `saveBlocksUpdate` with modified blocks
- Asserts blocks still have same `uuid` and `id` (AUTOINCREMENT id would change on DELETE+INSERT)
- Asserts updated `content` is reflected

**Files**:
- `/home/tstapler/Programming/stelekit/kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepositoryWarmPathTest.kt` (new file)

##### Task 3.1.1a: Create SqlDelightBlockRepositoryWarmPathTest (~5 min)

Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepositoryWarmPathTest.kt`:

```kotlin
@Test
fun `saveBlocksUpdate updates existing blocks without deleting and re-inserting them`() = runTest {
    // Setup: insert page + blocks
    // Act: saveBlocksUpdate with changed content
    // Assert: block row id (AUTOINCREMENT) unchanged — proves no DELETE+INSERT occurred
    //         block content is updated
}
```

Use the same test DB setup pattern as `GraphLoaderTest.kt` (uses `createTestDatabase()` helper or similar).

Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepositoryWarmPathTest.kt`

---

#### Story 3.1.2: Integration test — composite execute reduces actor round-trips

**As a** developer, **I want** a test verifying `parseAndSavePage` on a warm DB results in only one `actor.execute` call (not two separate `savePage` + `saveBlocks` calls), **so that** Fix B is protected from regression.

**Acceptance Criteria**:
- Existing `GraphLoaderIntegrationTest` or `GraphLoaderTest` has at least one warm-DB round-trip test (page loaded once, then loaded again from disk with same content)
- Test passes post-refactor (no behavior regression)
- Optionally: a test counting actor `execute` invocations (via `onWriteSuccess` callback) shows only 1 invocation per page save in warm mode, not 2

**Files**:
- `/home/tstapler/Programming/stelekit/kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderIntegrationTest.kt` (extend existing)

##### Task 3.1.2a: Verify and add warm-reload assertion to GraphLoaderIntegrationTest (~5 min)

In `GraphLoaderIntegrationTest.kt`, find the existing warm-reload test or add:

```kotlin
@Test
fun `parseAndSavePage warm reload completes without error`() = runTest {
    // Load page (cold path)
    // Load same page again (warm path — triggers diff merge + composite execute)
    // Assert blocks match expected content
}
```

Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderIntegrationTest.kt`

---

## File Change Summary

| File | Change |
|------|--------|
| `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq` | Add `updateBlockFull` query after `updateBlockContent` (line ~236) |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt` | Add `updateBlockFull` forwarding stub with `@DirectSqlWrite` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/BlockWriteRepository.kt` | Add `saveBlocksUpdate` to interface with `@DirectRepositoryWrite` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt` | Add `saveBlocksUpdate` implementation using `updateBlockFull` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` | Restructure METADATA_ONLY/FULL branching; add `page: Page` param to `dispatchFullBlockWrites`; replace FULL-mode standalone `writeActor.savePage` + `writeActor.saveBlocks` with composite `actor.execute`; add `evictHierarchyForPage` call |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/FakeRepositories.kt` | Add `saveBlocksUpdate` override to `FakeBlockRepository` (delegates to `saveBlocks`) |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepositoryWarmPathTest.kt` | New: regression test verifying warm-path UPDATE does not fire DELETE trigger |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderIntegrationTest.kt` | Extend: warm-reload integration assertion |

**Files NOT changed** (confirmed by research):
- `MigrationRunner.kt` — no new tables or indexes required
- `DatabaseWriteActor.kt` — `execute { }` API is already correct; no new request types needed
- `SteleDatabase.sq` schema tables/indexes — no new tables; indexes already exist in schema
- `BlockRepository.kt` — composite interface, inherits from `BlockWriteRepository` automatically
- iOS/JVM platform code — all changes are in `commonMain`

---

## Key Constraints (Do Not Violate)

1. **No `writeActor.*` calls inside `execute { }` lambda** — deadlock. Call `pageRepository.savePage()` and `blockRepository.saveBlocks()` / `saveBlocksUpdate()` directly.
2. **No new indexes** — all required indexes already exist in the schema.
3. **`evictHierarchyForPage` must be called after the composite write** — the hierarchy cache in `SqlDelightBlockRepository` will serve stale data for the updated page otherwise.
4. **`updateBlockFull` does NOT update `uuid` or `created_at`** — these are immutable identity/audit fields.
5. **saveBlocks (INSERT OR REPLACE) remains for the METADATA_ONLY stub path and new blocks** — only the `toUpdate` diff-list goes through `saveBlocksUpdate`.
6. **`diff.toDelete` deletions remain as individual `writeActor.deleteBlock(uuid)` calls BEFORE the composite execute** — these must run first to avoid FK constraint violations when a block is deleted and re-inserted at a new position.
