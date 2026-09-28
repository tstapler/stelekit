# Validation Plan: android-trace-db-fixes

**Date**: 2026-06-02

---

## Requirement → Test Mapping

### REQ-1: Fix A — updateBlockFull SQL UPDATE on warm path (replaces INSERT OR REPLACE)

| Requirement | Test File | Test Name | Type | Scenario | Success Criteria |
|-------------|-----------|-----------|------|----------|-----------------|
| REQ-1 warm-path UPDATE | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepositoryWarmPathTest.kt` (new) | `saveBlocksUpdate_should_updateExistingBlockContent_when_blockAlreadyExists` | Unit | Happy path: call saveBlocksUpdate on a block that is already in the DB; assert updated content is reflected | db.saveBlocks warm < 10ms/block |
| REQ-1 no DELETE+INSERT | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepositoryWarmPathTest.kt` (new) | `saveBlocksUpdate_should_preserveAutoIncrementId_when_updatingExistingBlock` | Unit | Assert that block's SQLite AUTOINCREMENT `id` (queried via `queries.selectBlockByUuid(...).executeAsOne().id`) does not change after `saveBlocksUpdate` — DELETE+INSERT would assign a new `id` | db.saveBlocks warm < 10ms/block; no FTS delete trigger storm |
| REQ-1 no FTS double-trigger | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/BlocksFtsTriggerTest.kt` (extend) | `saveBlocksUpdate_should_notFireFtsDeleteTrigger_when_contentUnchanged` | Unit | Intercept FTS `blocks_fts` delete count before/after calling `saveBlocksUpdate` with content-identical block; assert delete count = 0 | No FTS5 trigger storm per warm block |
| REQ-1 error path | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepositoryWarmPathTest.kt` (new) | `saveBlocksUpdate_should_returnWriteFailed_when_databaseThrows` | Unit | Pass a block whose `page_uuid` references a non-existent page (FK violation); assert `Either.Left(DomainError.DatabaseError.WriteFailed)` is returned | Error handling contract |
| REQ-1 multi-block transaction | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepositoryWarmPathTest.kt` (new) | `saveBlocksUpdate_should_persistAllBlocks_when_listSpansMultipleChunks` | Unit | Pass 200 blocks to `saveBlocksUpdate`; assert all 200 rows updated (mirrors `SaveBlocksChunkingTest` pattern) | Warm-path correctness at scale |
| REQ-1 performance guard | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepositoryWarmPathTest.kt` (new) | `saveBlocksUpdate_should_completeWithinBudget_when_updatingWarmDbPage` | jvmTest (timing) | Insert 50 blocks, then call `saveBlocksUpdate` with modified blocks, measure wall-clock elapsed; assert < 500ms total (≈ 10ms/block at 50 blocks) on JVM in-memory SQLite | db.saveBlocks warm DB < 10ms/block |

---

### REQ-2: Fix B — Composite actor execute in dispatchFullBlockWrites

| Requirement | Test File | Test Name | Type | Scenario | Success Criteria |
|-------------|-----------|-----------|------|----------|-----------------|
| REQ-2 composite execute | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderIntegrationTest.kt` (extend) | `parseAndSavePage_should_completeWarmReload_when_pageAlreadyExists` | Integration | Load a page (cold), modify file content, load again (warm); assert blocks match modified content and no error is emitted | parseAndSavePage warm reload correctness |
| REQ-2 single actor round-trip | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/DatabaseWriteActorTest.kt` (extend) | `execute_should_invokeCallbackOnce_when_compositePageAndBlockSave` | Unit | Use a counting `execute` wrapper; trigger one warm-path save; assert `execute` is called exactly once, not twice (one call for both savePage + saveBlocks) | parseAndSavePage inter-span gap < 50ms |
| REQ-2 savePage failure short-circuits blocks | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/DatabaseWriteActorTest.kt` (extend) | `execute_should_returnLeft_when_savePageFailsInsideCompositeExecute` | Unit | Inject `FakePageRepository` that returns `Left`; call composite execute; assert result is `Left` and `saveBlocks` / `saveBlocksUpdate` were NOT called | Error short-circuit inside lambda |
| REQ-2 METADATA_ONLY path unaffected | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderIntegrationTest.kt` (extend) | `parseAndSavePage_should_savePageAndStubs_when_modeIsMetadataOnly` | Integration | Force `METADATA_ONLY` parse mode; assert page is saved and stub blocks are stored; assert no full block-diff path is taken | METADATA_ONLY path not broken by composite-execute refactor |
| REQ-2 no deadlock (no actor in lambda) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/DatabaseWriteActorTest.kt` (existing) | `mixed request types complete without deadlock` (already exists — ensure it still passes) | Unit | Existing test guards against deadlock; must still pass after composite-execute change | API contract preserved |

---

### REQ-3: saveBlocksUpdate interface contract (BlockWriteRepository + FakeBlockRepository)

| Requirement | Test File | Test Name | Type | Scenario | Success Criteria |
|-------------|-----------|-----------|------|----------|-----------------|
| REQ-3 interface stub | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/FakeRepositories.kt` (extend) | `saveBlocksUpdate_should_delegateToSaveBlocks_when_calledOnFakeBlockRepository` | Unit | Call `FakeBlockRepository.saveBlocksUpdate(blocks)`; assert blocks appear in `getBlocksForPage` — confirms the delegation `= saveBlocks(blocks)` works | Compilation + interface correctness |
| REQ-3 RestrictedDatabaseQueries stub | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/BlocksFtsTriggerTest.kt` (extend) | `updateBlockFull_stub_should_mapAllParametersCorrectly_when_calledOnRestrictedQueries` | Unit | Call `RestrictedDatabaseQueries.updateBlockFull(...)` with known values; assert the delegated query was called with matching arguments (use a spy or query the DB directly) | @DirectSqlWrite contract enforced |

---

### REQ-4: No regression on JVM, Android unit tests, iOS (commonMain)

| Requirement | Test File | Test Name | Type | Scenario | Success Criteria |
|-------------|-----------|-----------|------|----------|-----------------|
| REQ-4 JVM regression guard | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SaveBlocksChunkingTest.kt` (existing) | All three existing tests | jvmTest | Re-run unchanged; must still pass after `saveBlocksUpdate` is added alongside `saveBlocks` | No regression on JVM |
| REQ-4 DB trigger regression | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/BlocksFtsTriggerTest.kt` (existing) | `blocks_au_trigger_is_restricted_to_content_column_updates` (existing) | jvmTest | Existing test must still pass — the `blocks_au` trigger is unchanged | No regression on FTS triggers |
| REQ-4 query plan audit | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/QueryPlanAuditTest.kt` (existing) | `no unexpected full table scans` (existing) | jvmTest | Must still pass — no new queries added; `updateBlockFull` is an UPDATE not a SELECT | No index regression |
| REQ-4 actor tests | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/DatabaseWriteActorTest.kt` (existing) | All existing tests | jvmTest | All 18 existing actor tests must still pass after composite-execute refactor | No actor regression |
| REQ-4 GraphLoader existing | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderTest.kt` (existing) | All existing tests | jvmTest | Must still pass after `dispatchFullBlockWrites` signature change | No GraphLoader regression |
| REQ-4 Android unit | `kmp/src/androidUnitTest/` | All existing tests | androidUnitTest | `./gradlew testDebugUnitTest` passes — FakeBlockRepository update is the only Android-visible change | No regression on Android unit tests |

---

### REQ-5: Performance success criteria (db.saveBlocks warm, db.lookupPage, editor_input p50)

| Requirement | Test File | Test Name | Type | Scenario | Success Criteria |
|-------------|-----------|-----------|------|----------|-----------------|
| REQ-5 db.saveBlocks warm < 10ms/block | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepositoryWarmPathTest.kt` (new) | `saveBlocksUpdate_should_completeWithinBudget_when_updatingWarmDbPage` | jvmTest (timing) | See REQ-1 timing test above. JVM in-memory SQLite is faster than Android SAF; 10ms/block * 50 blocks = 500ms budget is conservative for the JVM proxy | db.saveBlocks warm DB < 10ms/block |
| REQ-5 db.lookupPage < 20ms | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/QueryPlanAuditTest.kt` (existing) | `no unexpected full table scans` (existing) | jvmTest | Existing EXPLAIN QUERY PLAN test covers `selectPageByName` and `selectPageByUuid` — confirms index usage guaranteeing < 20ms on real device | db.lookupPage < 20ms |
| REQ-5 editor_input p50 < 100ms | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderIntegrationTest.kt` (extend) | `parseAndSavePage_should_completeWarmReload_when_pageAlreadyExists` | Integration | Integration test verifies the composite execute reduces round-trips. The 100ms editor_input target is a downstream consequence: once `dispatchFullBlockWrites` suspends only once, the write-actor queue drains faster, unblocking editor debounce. Verify no `_writeErrors` are emitted during warm reload. | editor_input p50 < 100ms (indirect) |
| REQ-5 parseAndSavePage inter-span < 50ms | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/DatabaseWriteActorTest.kt` (extend) | `execute_should_invokeCallbackOnce_when_compositePageAndBlockSave` | Unit | Counts actor `execute` calls = 1 per page save; proxy for eliminating the 2x ~1,000ms `CompletableDeferred.await()` suspensions in the old code path | parseAndSavePage inter-span gap < 50ms |

---

## New Test Files to Create

| File | Source Set | Purpose |
|------|-----------|---------|
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepositoryWarmPathTest.kt` | jvmTest | REQ-1: warm-path UPDATE correctness + timing guard |

## Existing Test Files to Extend

| File | Extension |
|------|-----------|
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderIntegrationTest.kt` | Add warm-reload and METADATA_ONLY tests |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/DatabaseWriteActorTest.kt` | Add composite-execute invocation count test and savePage-fail short-circuit test |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/BlocksFtsTriggerTest.kt` | Add saveBlocksUpdate FTS trigger non-regression test |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/FakeRepositories.kt` | Add `saveBlocksUpdate` delegation override |

---

## Test Stack

- **Unit**: `kotlin.test` + `kotlinx-coroutines-test` (`runBlocking` / `runTest`); assertions via `kotlin.test.assertEquals`, `assertTrue`, `assertNotNull`
- **Integration (jvmTest)**: `DriverFactory().createDriver("jdbc:sqlite::memory:")` + `SteleDatabase(driver)` for real SQLite; `InMemoryBlockRepository` / `InMemoryPageRepository` for `GraphLoaderIntegrationTest`
- **Performance guards (jvmTest timing)**: `kotlin.time.measureTime { }` with hard upper-bound assertion; JVM in-memory SQLite is used as a proxy — not the Android SAF stack, but sufficient to catch catastrophic regressions (e.g. re-introduction of per-block transaction overhead)
- **Android unit tests**: `./gradlew testDebugUnitTest` — existing suite only (no new Android-specific tests needed; all changes are in `commonMain`)

---

## Coverage Targets

- Unit test coverage: all public methods on the new `saveBlocksUpdate` path have a happy path + at least one error path
- All success criteria from `requirements.md`: covered by at least one test case (see mapping table above)
- All external integrations (SQLite, DatabaseWriteActor): covered by jvmTest integration tests using real in-memory SQLite
- Regression guard: every existing test suite that touches the changed files (`SaveBlocksChunkingTest`, `BlocksFtsTriggerTest`, `QueryPlanAuditTest`, `DatabaseWriteActorTest`, `GraphLoaderTest`) must continue to pass unchanged

---

## Adversarial Review Mitigations Covered by Tests

| Concern (from adversarial-review.md) | Test Coverage |
|--------------------------------------|--------------|
| `saveBlocksUpdate` in `BlockWriteRepository` not `BlockRepository` | Compilation enforced; `FakeBlockRepository` delegation test (REQ-3) |
| `FakeBlockRepository` must implement `saveBlocksUpdate` | REQ-3 fake delegation test |
| `dispatchFullBlockWrites` must accept `page: Page` | REQ-2 integration test exercises the full warm-path call chain |
| METADATA_ONLY path must retain standalone `writeActor.savePage` | REQ-2 METADATA_ONLY integration test |
| `diff.toDelete` and `diff.toInsert` disjoint sets | Covered by existing `DiffMergeTest.kt` (no change needed) |
| `evictHierarchyForPage` cast silently no-ops in tests | Acceptable for tests using `InMemoryBlockRepository`; production SQLite path is exercised only on device |
