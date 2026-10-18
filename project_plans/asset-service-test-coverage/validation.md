# Validation: asset-service-test-coverage

## Checklist

### Requirements completeness

| AC | Requirement | Covered by | Status |
|----|-------------|-----------|--------|
| AC-1 | moveAsset happy path | AssetMoveServiceTest | ✓ planned |
| AC-2 | rename failure returns error | AssetMoveServiceTest | ✓ planned |
| AC-3 | same-path no-op | AssetMoveServiceTest | ✓ planned |
| AC-4 | invalid subfolder throws | AssetMoveServiceTest | ✓ planned |
| AC-5 | WAL replay: incomplete move | AssetMoveServiceWalTest | ✓ planned |
| AC-6 | WAL replay: already complete | AssetMoveServiceWalTest | ✓ planned |
| AC-7 | WAL replay: both absent | AssetMoveServiceWalTest | ✓ planned |
| AC-8 | registerAsset builds correct entry | AssetIndexServiceTest | ✓ planned |
| AC-9 | backfill processes bounded batches | AssetIndexServiceTest | ✓ planned |
| AC-10 | empty assets dir is no-op | AssetIndexServiceTest | ✓ planned |
| AC-11 | ViewModel initial load | AssetBrowserViewModelTest | ✓ planned |
| AC-12 | setFilter resets cursor | AssetBrowserViewModelTest | ✓ planned |
| AC-13 | setSearch debounced reload | AssetBrowserViewModelTest | ✓ planned |
| AC-14 | loadMore appends and advances cursor | AssetBrowserViewModelTest | ✓ planned |
| AC-15 | deleteAsset removes from state | AssetBrowserViewModelTest | ✓ planned |
| AC-16 | copyMarkdownLink correct syntax | AssetBrowserViewModelTest | ✓ planned |
| AC-17 | FakeFileSystem supports renameFile | FakeFileSystem edit | ✓ planned |
| AC-18 | All tests pass bazel business_tests | Validation run | ✓ planned |

### Plan validation

**Does the plan avoid source code changes?** Yes — only test files and one
test-utility edit (`FakeFileSystem`).

**Are all new tests headless?** Yes — `businessTest` / `commonTest` have no
Compose renderer dependency. `jvmTest` WAL tests are pure-logic (no UI), but
they depend on the in-memory SQLDelight driver (JVM only). The WAL tests are
isolated from the Compose UI test target.

**Can the plan be parallelised?**
Tasks 2, 4, 5 are independent and can be implemented concurrently after Task 1
(FakeFileSystem) is done. Task 3 (WAL tests) is also independent of Tasks 4/5.
A two-agent split could be: Agent A does Tasks 1+2+3 (service tests);
Agent B does Tasks 4+5 (ViewModel test).

**Is `FakeFileSystem` extension safe?** Yes — it's a test-only class used by
sidecar and image storage tests. Adding `renameFile` only adds a capability;
existing callers that expected the default `false` response still get `false`
if they call it on a path that doesn't exist.

**Does WAL-replay testing require `restrictedQueries`?** Yes. The
`insertPendingMove` / `deletePendingMove` / `selectAllPendingMoves` calls live on
`RestrictedDatabaseQueries.rawQueries` (the wrapped `SteleDatabaseQueries`).
An in-memory driver is the correct fixture — consistent with the existing
`jvmTest` pattern for integration-level DB tests.

### Open questions / risks

1. **`startBackfill` polling timeout:** The test polls `repo.countAssets()` with
   a 5s timeout. On a heavily-loaded CI machine this could be tight if 70
   batch-processed assets take longer. If flakiness is observed, raise to 10s or
   restructure to call `backfillGraph` as a suspend fun via reflection — but
   prefer the polling approach first since it exercises the real API surface.

2. **`setSearch` delay is wall-clock:** The 350ms `delay` in the debounce test
   runs on real clock time. This adds ~350ms per test run. Acceptable for one
   test; if more debounce tests are added later, consider extracting a
   `delayProvider` parameter to allow test injection.

3. **WAL "ambiguous" case (both old and new present):** AC-5 through AC-7 cover
   three of the four `replayMove` branches. The fourth branch
   (`oldExists && newExists`) logs a warning and leaves the WAL row. This case
   should be added to `AssetMoveServiceWalTest` as AC-7b even though it is not
   listed in the requirements; it is the only branch with no cleanup path and
   the most likely to mask a real data-loss scenario.

### Coverage projection (conservative)

| Class | Baseline lines | Target lines | New covered |
|---|---|---|---|
| AssetMoveService.kt | 0/120 | ≥80% | ~96 |
| AssetIndexService.kt | ~41/95 | ≥80% | ~35 |
| AssetBrowserViewModel.kt | 0/208 | ≥60% | ~125 |
| **Total new** | | | **~256** |

Revised package coverage: (250 + 256) / 1917 ≈ **26.4%** lines — roughly double
the current 13.0% baseline on these packages.

### Definition of done

- [ ] `FakeFileSystem.renameFile` override committed
- [ ] `AssetMoveServiceTest` (4 tests) in businessTest, all passing
- [ ] `AssetMoveServiceWalTest` (4 tests) in jvmTest, all passing
- [ ] `AssetIndexServiceTest` (4 tests) in businessTest, all passing
- [ ] `AssetBrowserViewModelTest` (10 tests) in businessTest, all passing
- [ ] `bazel test //kmp:business_tests` green with zero failures
- [ ] No existing tests broken
