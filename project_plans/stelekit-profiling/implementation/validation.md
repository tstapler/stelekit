# Validation Plan: stelekit-profiling

**Date**: 2026-05-29

---

## Requirement → Test Mapping

| Requirement | Test File | Test Name | Type | Scenario |
|-------------|-----------|-----------|------|----------|
| FR-1: Graph Copy Safety | `GraphCopyUtilsTest` | `copyGraphToTempDir_should_CreateCopyInTempDir_When_ValidSourcePath` | Unit | Happy path — valid source dir copies to temp |
| FR-1: Graph Copy Safety | `GraphCopyUtilsTest` | `copyGraphToTempDir_should_ThrowException_When_SourcePathDoesNotExist` | Unit | Error path — source path missing |
| FR-1: Graph Copy Safety | `GraphCopyUtilsTest` | `copyGraphToTempDir_should_NotModifyOriginal_When_BenchmarkWritesToCopy` | Integration | Write to copy, assert original unchanged |
| FR-1: Graph Copy Safety | `GraphLoadTimingTest` | `graphLoadTiming_should_NotWriteToOriginalPath_When_Test3RealGraphLoad` | Integration | Test 3 retrofit — original dir unchanged after load |
| FR-1: Graph Copy Safety | `GraphLoadTimingTest` | `graphLoadTiming_should_NotWriteToOriginalPath_When_Test5WriteLatencyRealGraph` | Integration | Test 5 retrofit — original dir unchanged after writes |
| FR-2: UserSessionBenchmarkTest — setup | `UserSessionBenchmarkTest` | `runUserSession_should_SkipGracefully_When_GraphPathSystemPropertyAbsent` | Unit | Skip path — `STELEKIT_GRAPH_PATH` not set |
| FR-2: UserSessionBenchmarkTest — setup | `UserSessionBenchmarkTest` | `runUserSession_should_LoadGraphAndReachNonEmptyUiState_When_GraphPathSet` | Integration | Happy path — ViewModel loads graph, uiState non-empty |
| FR-2: UserSessionBenchmarkTest — setup | `UserSessionBenchmarkTest` | `runUserSession_should_CleanUpTempDir_When_ExceptionThrownDuringSetup` | Integration | Error path — finally block deletes temp dir on failure |
| FR-2: UserSessionBenchmarkTest — Action A | `UserSessionBenchmarkTest` | `actionA_should_NavigateTo20PagesAndRecordSamples_When_PagesAvailable` | Integration | Happy path — 20 navigation samples collected |
| FR-2: UserSessionBenchmarkTest — Action A | `UserSessionBenchmarkTest` | `actionA_should_NotHang_When_PageUuidAbsentFromGraph` | Unit | Error path — withTimeoutOrNull prevents infinite hang |
| FR-2: UserSessionBenchmarkTest — Action B | `UserSessionBenchmarkTest` | `actionB_should_AddBlocksAndRecordWriteLatency_When_PageOpen` | Integration | Happy path — 30 blocks added, write latency (not pacing) recorded |
| FR-2: UserSessionBenchmarkTest — Action B | `UserSessionBenchmarkTest` | `actionB_should_RecordWriteLatencyOnly_When_PacingDelayExcluded` | Unit | Error path — sample does not include 200ms artificial delay |
| FR-2: UserSessionBenchmarkTest — Action C | `UserSessionBenchmarkTest` | `actionC_should_SearchAndReturnResults_When_QueryDerivedFromPageTitles` | Integration | Happy path — 10 search queries complete without hang |
| FR-2: UserSessionBenchmarkTest — Action C | `UserSessionBenchmarkTest` | `actionC_should_UseFallbackQueries_When_PageTitlesTooShort` | Unit | Error path — fallback query list used when titles < 2 chars |
| FR-2: UserSessionBenchmarkTest — Action D | `UserSessionBenchmarkTest` | `actionD_should_RenamePages_When_WriteActorNonNull` | Integration | Happy path — 5 pages renamed, backlink rewriter invoked |
| FR-2: UserSessionBenchmarkTest — Action D | `UserSessionBenchmarkTest` | `actionD_should_AwaitRenameCompletion_When_GetAllPagesFlowReEmits` | Integration | Error path — fallback delay used when uiState doesn't update within timeout |
| FR-2: UserSessionBenchmarkTest — Actions E-G | `UserSessionBenchmarkTest` | `actionEFG_should_IndentOutdentAndMoveBlocks_When_BlocksHaveValidSiblings` | Integration | Happy path — indent/outdent/move operations recorded |
| FR-2: UserSessionBenchmarkTest — Actions E-G | `UserSessionBenchmarkTest` | `actionEFG_should_SkipInvalidBlocks_When_BlockPositionMakesIndentNoOp` | Unit | Error path — re-fetch pageBlocks before each loop to avoid no-ops |
| FR-2: UserSessionBenchmarkTest — Action H | `UserSessionBenchmarkTest` | `actionH_should_CallUndoAndRecordSamples_When_UndoManagerNull` | Unit | Happy path — undo called 5x (baseline ~50ms expected, no-op) |
| FR-2: UserSessionBenchmarkTest — Action I | `UserSessionBenchmarkTest` | `actionI_should_GoBackAndRecordSamples_When_NavigationHistoryPopulated` | Unit | Happy path — goBack called 5x, pure state mutation measured |
| FR-2: UserSessionBenchmarkTest — Action J | `UserSessionBenchmarkTest` | `actionJ_should_SearchPostRenameAndAppendToSearchSamples_When_FtsIndexUpdated` | Integration | Happy path — 5 post-rename queries appended to search samples (total n=15) |
| FR-3: benchmark-session.json Schema | `BenchmarkJsonOutputTest` | `benchmarkSessionJson_should_ContainAllRequiredFields_When_SessionCompletes` | Unit | Happy path — JSON contains gitSha, graphPageCount, actions array |
| FR-3: benchmark-session.json Schema | `BenchmarkJsonOutputTest` | `benchmarkSessionJson_should_HaveCorrectActionKeys_When_AllActionsRun` | Unit | Happy path — all 9 action keys present (navigate_page … navigate_back) |
| FR-3: benchmark-session.json Schema | `BenchmarkJsonOutputTest` | `benchmarkSessionJson_should_ProduceValidJsonFile_When_OutputDirMissing` | Unit | Error path — output dir created if absent; no crash |
| FR-4: Retrofit Existing Real-Graph Tests | `GraphLoadTimingTest` | `graphLoadTimingTest3_should_PassWithCopiedGraph_When_RealGraphPathSet` | Integration | Happy path — test 3 passes after retrofit (real graph copy used) |
| FR-4: Retrofit Existing Real-Graph Tests | `GraphLoadTimingTest` | `graphLoadTimingTest5_should_PassWithCopiedGraph_When_RealGraphPathSet` | Integration | Happy path — test 5 passes after retrofit |
| FR-4: Retrofit Existing Real-Graph Tests | `GraphLoadTimingTest` | `graphLoadTimingTests1_2_4_should_BeUnchanged_When_OnlyTests3And5RetrofitApplied` | Unit | Error path (regression) — tests 1, 2, 4 compile and pass without modification |
| FR-5: benchmark-local.sh Unchanged Interface | `ScriptInterfaceTest` | `benchmarkLocalSh_should_ProduceSameOutputFiles_When_NewTestAdded` | Integration | Happy path — script produces existing + new JSON files |
| FR-5: benchmark-local.sh Unchanged Interface | `ScriptInterfaceTest` | `benchmarkLocalSh_should_AcceptSameArgSignature_When_NewSessionTestActive` | Unit | Regression — script signature unchanged (grep test) |
| FR-6: Span Capture During Session | `UserSessionBenchmarkTest` | `spanCapture_should_EnableRingBufferBeforeViewModelConstruction_When_Setup` | Unit | Happy path — ringBuffer.enabled = true before ViewModel ctor |
| FR-6: Span Capture During Session | `UserSessionBenchmarkTest` | `spanCapture_should_DrainSpansToFile_When_SessionCompletes` | Integration | Happy path — benchmark-session-spans.json is non-empty after full session |
| FR-6: Span Capture During Session | `UserSessionBenchmarkTest` | `spanCapture_should_WriteQueryStatsJson_When_QueryStatsCollectorAvailable` | Integration | Happy path — benchmark-session-query-stats.json written with sessionQueryStats key |
| FR-6: Span Capture During Session | `UserSessionBenchmarkTest` | `spanCapture_should_HandleConcurrentSpanRecords_When_ViewModelDispatchesOnMultipleThreads` | Unit | Error path — concurrent record() calls do not throw ConcurrentModificationException |
| NFR-1: Isolation | `GraphCopyUtilsTest` | `isolation_should_NeverWriteToOriginalPath_When_AnyBenchmarkActionRuns` | Integration | Property test — diff original before/after session |
| NFR-2: Determinism | `BenchmarkDeterminismTest` | `seededRandom_should_ProduceSamePageOrder_When_SeedIs42` | Unit | Happy path — same pages selected on two runs with seed=42 |
| NFR-2: Determinism | `BenchmarkDeterminismTest` | `seededRandom_should_UseDifferentSeedsForDifferentActions_When_ActionsABCDJSampled` | Unit | Error path — confirm seeds 42/43/44 produce distinct orderings |
| NFR-3: CI Compatibility | `CiCompatibilityTest` | `userSessionBenchmarkTest_should_SelfSkip_When_GraphPathPropertyAbsent` | Unit | Happy path — no test failure when property absent |
| NFR-3: CI Compatibility | `CiCompatibilityTest` | `userSessionBenchmarkTest_should_BeIncludedInJvmTestSourceSet_When_Compiled` | Unit | Regression — class compiles in jvmTest source set |
| NFR-4: No New Dependencies | `DependencyLintTest` | `buildGradle_should_NotAddNewDependencies_When_FeatureImplemented` | Unit | Regression — grep build.gradle.kts for new profiling deps |
| NFR-5: Flamegraph Coverage | `GradleIntegrationTest` | `jvmTestProfileTask_should_IncludeUserSessionBenchmarkTest_When_FilterConfigured` | Unit | Happy path — includeTestsMatching present in jvmTestProfile |
| NFR-5: Flamegraph Coverage | `GradleIntegrationTest` | `jvmTestProfileTask_should_NotRemoveExistingGraphLoadTimingTestFilter_When_NewFilterAdded` | Unit | Regression — GraphLoadTimingTest filter line unchanged |

---

## Adversarial Concerns → Test Traceability

The following test cases directly address concerns raised in `adversarial-review.md`:

| Concern | Addressed By |
|---------|-------------|
| Navigation infinite-hang risk | `actionA_should_NotHang_When_PageUuidAbsentFromGraph` — verifies `withTimeoutOrNull(5_000)` wraps every `uiState.first {}` |
| Rename await fragility (uiState pages Flow lag) | `actionD_should_AwaitRenameCompletion_When_GetAllPagesFlowReEmits` — tests fallback delay path |
| RingBuffer thread safety | `spanCapture_should_HandleConcurrentSpanRecords_When_ViewModelDispatchesOnMultipleThreads` |
| Action B timing includes 200ms delay | `actionB_should_RecordWriteLatencyOnly_When_PacingDelayExcluded` — unit test verifies split timing |
| Indent/outdent no-op on stale block positions | `actionEFG_should_SkipInvalidBlocks_When_BlockPositionMakesIndentNoOp` |
| `uiState.currentGraphPath` not a reliable fully-loaded signal | `runUserSession_should_LoadGraphAndReachNonEmptyUiState_When_GraphPathSet` — awaits `pages.isNotEmpty()` |

---

## Test Stack

- **Unit**: Kotlin `@Test` in `jvmTest` source set, JUnit 5 via `kotlin.test`, `kotlin.test.assertEquals` / `assertNotNull` / `assertTrue` / `assertFails`
- **Integration**: Same `jvmTest` / `businessTest` source sets; full SQLite backend via `SqlDelightRepositoryFactory` (in-memory SQLite for isolation tests, real temp-dir copy for real-graph tests); `runBlocking` / `TestCoroutineScheduler` for coroutine control
- **Regression / Linting**: `@Test` functions that grep/parse source files (`build.gradle.kts`, script text) to assert structural invariants — no runtime app startup needed
- **Script Interface**: Bash subprocess via `ProcessBuilder` — only runs when `STELEKIT_GRAPH_PATH` is set (same skip guard as session test)

---

## Coverage Targets

- Unit test coverage: ≥80% (line) across `BenchmarkGraphUtils`, `UserSessionBenchmarkTest`, percentile helper
- All public benchmark entry points (happy path + error paths): covered above
- All external integrations (SQLite, FTS5, disk writes, span ring buffer): unit-level mock + at least one integration test against real temp-dir graph
- Regression tests for unchanged behavior: `GraphLoadTimingTest` tests 1/2/4, `benchmark-local.sh` signature, `jvmTestProfile` existing filter line

---

## Test File Location Map

| Test File | Source Set | Location |
|-----------|------------|----------|
| `GraphCopyUtilsTest` | `jvmTest` | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/GraphCopyUtilsTest.kt` |
| `UserSessionBenchmarkTest` | `jvmTest` | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/UserSessionBenchmarkTest.kt` |
| `BenchmarkJsonOutputTest` | `jvmTest` | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/BenchmarkJsonOutputTest.kt` |
| `BenchmarkDeterminismTest` | `jvmTest` | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/BenchmarkDeterminismTest.kt` |
| `CiCompatibilityTest` | `jvmTest` | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/CiCompatibilityTest.kt` |
| `DependencyLintTest` | `jvmTest` | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/DependencyLintTest.kt` |
| `GradleIntegrationTest` | `jvmTest` | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/GradleIntegrationTest.kt` |
| `ScriptInterfaceTest` | `jvmTest` | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/ScriptInterfaceTest.kt` |
| `GraphLoadTimingTest` (modified) | `jvmTest` | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/GraphLoadTimingTest.kt` |

---

## Summary Counts

| Type | Count |
|------|-------|
| Unit | 22 |
| Integration | 18 |
| **Total** | **40** |

Requirements covered: **11 / 11** (FR-1 through FR-6 + NFR-1 through NFR-5)
