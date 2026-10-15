# Validation: db/repository layer test coverage gaps

## Adversarial review pass

### Claim 1: AssetRepositoryTest uses InMemoryAssetRepository only

VERIFIED — source inspection of
`kmp/src/businessTest/kotlin/dev/stapler/stelekit/repository/AssetRepositoryTest.kt:14`:
`private fun repo() = InMemoryAssetRepository()`. Every `@Test` method calls
`repo()`, so `SqlDelightAssetRepository` receives zero direct test coverage.
The 3% JaCoCo line coverage is consistent: only the class definition and field
initializers are touched via reflective loading.

### Claim 2: SqlDelightBlockRepository complex write paths are untested

VERIFIED — grep confirmed no `@Test` method in the entire `businessTest/` tree
invokes `indentBlock`, `outdentBlock`, `mergeBlocks`, `splitBlock`,
`saveBlocksAtomicWithChainRepair`, or `deleteBulk` directly on
`SqlDelightBlockRepository`. Tests in `merge/` and `db/SplitJournalTest` do
exercise `saveBlock` and `saveBlocks`, but these are the simpler one-shot paths.

### Claim 3: No UndoManager tests exist

VERIFIED — `grep -rn "UndoManager" businessTest/` returns zero hits in any `*Test*` file.
The 9% coverage comes from the class being instantiated in production code paths
that are indirectly exercised by integration tests, not from direct unit tests.

### Claim 4: The in-memory driver pattern is sufficient for repository tests

VERIFIED — `BlockHierarchyCteTest` and `MigrationRunnerSchemaSyncTest` both use
`DriverFactory().createDriver("jdbc:sqlite::memory:")` in `businessTest/db/` and
pass consistently in CI (`business_tests` target). `MigrationRunner.all` is
applied by `SteleDatabase(driver)` constructor, so schema is current.

### Claim 5: FractionalIndexing is non-deterministic without stable seeds

PLAUSIBLE — `FractionalIndexing` generates position strings algorithmically from
a list of existing positions. If existing positions in the test are set to known
values ("a0", "a1" etc.), the output is deterministic. Tests must set explicit
position values on all seed blocks to avoid ordering flakiness.

### Claim 6: DatabaseWriteActor can be safely constructed in tests

PLAUSIBLE — `DatabaseWriteActor` owns its own `CoroutineScope`. Tests that
construct it must call `actor.close()` (or cancel its scope) in a teardown
to avoid coroutine leaks between tests. `TestScope` from `kotlinx-coroutines-test`
is the cleanest option; or a real `CoroutineScope(SupervisorJob())` that is
cancelled `@AfterTest`.

### Risk: UndoManager's `undoneOpIds` is in-memory

CONFIRMED RISK — `undoneOpIds` is a `mutableSetOf<String>()` field on the class.
A new `UndoManager` instance (simulating app restart) starts with an empty set,
so previously undone ops appear redoable again. This is by design (the persistence
test in the plan verifies this behaviour), but tests must not assume cross-instance
undo state transfer — they must document this as expected behaviour, not a bug.

### Risk: OperationLogger.ensureSeqInitialized must run outside a transaction

CONFIRMED — the source comment at line 64 of `OperationLogger.kt` states this
explicitly. `UndoManagerTest` must call `refreshState()` (which reads from DB) as
a standalone suspend call, not inside a `transaction { }` block. The plan correctly
calls this out.

### Risk: SqlDelightSearchRepository FTS requires schema setup

VERIFIED — `rebuildFts()` must succeed on a fresh DB (no existing FTS content).
The FTS5 virtual table is created by `Schema.create()` via `SteleDatabase(driver)`,
so tests do not need to call `rebuildFts()` before searching — they can insert
pages/blocks directly and search immediately. The `rebuildFts` test case verifies
it doesn't crash, not that it improves results.

## Completeness check

The four work streams (A–D) together address all files named in the backlog item:

| File | Addressed by |
|------|-------------|
| SqlDelightBlockRepository.kt (49%, 24%) | Work stream B |
| SqlDelightAssetRepository.kt (3%) | Work stream A |
| UndoManager.kt (9%) | Work stream C |
| SqlDelightSearchRepository.kt (68%) | Work stream D |
| RestrictedDatabaseQueries.kt (27%) | Indirectly by A–D; Work stream E for residual |
| DatalogBlockRepository.kt (48%) | Out of scope (legacy path) |
| GraphWriter.kt (55%, 41%) | Out of scope (separate integration item) |

## Gaps not addressed by this plan

1. **GraphWriter** — file-system-level integration; would require a fake or temp
   filesystem. Left for a follow-on item.
2. **DatalogBlockRepository** — legacy query engine; the CLAUDE.md architecture
   sections do not reference Datalog as the active backend. Leaving uncovered is
   an acceptable trade-off unless Datalog is promoted to production default.
3. **Android-only paths** — `androidUnitTest` (Robolectric) coverage is not merged
   into the JaCoCo report. The new businessTest tests run on JVM only; Android
   driver differences are covered by the existing contract tests in the merge/ suite.

## Acceptance criteria validation

| AC | Validated? | Notes |
|----|-----------|-------|
| 1. SqlDelightAssetRepositoryContractTest all methods pass | Pending implementation | Pattern is proven (BlockHierarchyCteTest) |
| 2. SqlDelightBlockRepositoryTest write-path cases pass | Pending implementation | 15 test cases scoped |
| 3. UndoManagerTest all cases pass | Pending implementation | Actor teardown is critical |
| 4. SqlDelightSearchRepositoryTest filter cases pass | Pending implementation | 14 test cases scoped |
| 5. No regressions in business_tests | Will be verified at implementation time | |
| 6. Coverage thresholds met | Pending implementation; thresholds set conservatively | |
