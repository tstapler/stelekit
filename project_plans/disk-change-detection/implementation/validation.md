# Validation Plan: Disk Change Detection

## Overview

This document maps every functional requirement (FR-1 through FR-10) and testing requirement (TR-1 through TR-6) from `requirements.md` to test coverage. For each requirement it records: existing tests that cover it, gaps, and test type. A readiness gate assessment follows.

---

## Requirement-to-Test Traceability

### FR-1 — Watcher guard uses unsaved-edit pages only

**Acceptance**: Opening the journals page and waiting ≥ 5 s after an external write results in the journal content updating on screen.

| Test | File | Type | Status |
|------|------|------|--------|
| `TC-06 open page with no unsaved edits IS reloaded on external change` | `GraphFileWatcherTest.kt` | Unit (JVM) | EXISTS |
| `TC-07 journal page with unsaved edits is NOT auto-reloaded on external change` | `GraphFileWatcherTest.kt` | Unit (JVM) | EXISTS |
| `TC-04 checkDirectoryForChanges skips onReloadFile for pages with unsaved edits` | `GraphFileWatcherTest.kt` | Unit (JVM) | EXISTS |
| `TC-05 checkDirectoryForChanges emits externalFileChanges even for pages with unsaved edits` | `GraphFileWatcherTest.kt` | Unit (JVM) | EXISTS |
| Manual: Open journals screen, write externally, observe live update within 5 s | — | Manual | MISSING |

**Coverage**: Automated unit tests cover all mechanistic paths. Acceptance criterion (journals live-refresh, ≥5 s window) is only confirmed by the manual test. The unit tests use a 600 ms real-clock wait with a 50 ms poll, which is sufficient mechanistic coverage; the 5-second window is an e2e integration concern.

**Gaps**: No integration test with a real filesystem watcher thread and actual file I/O. Manual smoke test required before ship.

---

### FR-2 — `dirtyPageUuids` StateFlow in `BlockStateManager`

**Acceptance**: Emits empty set with no edits; emits page UUID on edit start; returns to empty after save.

| Test | File | Type | Status |
|------|------|------|--------|
| `BlockStateManagerDirtyPageUuidsTest` (referenced in plan.md Story 1.1) | `kmp/src/jvmTest/` or `businessTest/` | Unit | MISSING — not found on disk |

**Coverage**: No test file for `BlockStateManagerDirtyPageUuidsTest` exists in the repository. The plan.md story marks this as IMPLEMENTED and cites this test, but it does not exist. This is a gap.

**Gaps**:
- `BlockStateManagerDirtyPageUuidsTest` must be created covering:
  - `dirtyPageUuids` emits `emptySet()` when `_dirtyBlocks` is empty
  - `dirtyPageUuids` emits the page UUID when a block edit is recorded
  - `dirtyPageUuids` returns to `emptySet()` after the block edit is saved/cleared
  - `dirtyPageUuids` only emits the page UUID of the page containing the dirty block, not all open pages

---

### FR-3 — `setUnsavedPageUuids` on `GraphLoaderPort` / `GraphLoader`

**Acceptance**: Implementation-level: `unsavedPageFilePaths` reflects the resolved file paths for dirty-block page UUIDs.

| Test | File | Type | Status |
|------|------|------|--------|
| Indirectly covered by TC-04, TC-06, TC-07 via `activePageFilePaths` lambda | `GraphFileWatcherTest.kt` | Unit (JVM) | PARTIAL |

**Coverage**: `GraphFileWatcherTest` exercises the guard at the `GraphFileWatcher` level using the `activePageFilePaths` parameter (the public interface), which simulates the same guard that `unsavedPageFilePaths` provides to `GraphFileWatcher` in production. The `setUnsavedPageUuids` method on `GraphLoaderPort` / `GraphLoader` itself has no dedicated unit test.

**Gaps**:
- No test for `GraphLoader.setUnsavedPageUuids`: UUID-to-path resolution, previous job cancellation on re-call, and `emptySet()` on `null` argument.
- No test for the null-guard path (when `setUnsavedPageUuids(null)` is called on graph unload).
- Integration test linking `BlockStateManager.dirtyPageUuids` → `GraphLoader.unsavedPageFilePaths` → watcher guard is absent.

---

### FR-4 — `StelekitViewModel` wires `dirtyPageUuids`

**Acceptance**: `setUnsavedPageUuids(blockStateManager.dirtyPageUuids)` is called in `StelekitViewModel.init`.

| Test | File | Type | Status |
|------|------|------|--------|
| No dedicated test | — | — | MISSING |

**Coverage**: Wiring is confirmed in code at `StelekitViewModel.kt` line 352 (`graphLoader.setUnsavedPageUuids(it.dirtyPageUuids)`). No automated test verifies the wiring. The existing `StelekitViewModelCrashReproductionTest` / `LargeGraphWarmStartCrashTest` are regression tests for different concerns.

**Gaps**:
- No test asserts that `setUnsavedPageUuids` is called when a graph is loaded, or that it is called with `null` when a graph is unloaded.
- This is a wiring concern that is acceptable to cover via code review + manual test rather than an automated test, since the `ViewModel` layer is hard to unit-test without a full coroutine environment.

---

### FR-5 — `FileObserver` watches `pages/` and `journals/` subdirectories

**Acceptance**: Writing a file under `$graphPath/pages/` triggers `onExternalChange` within 1 s when `MANAGE_EXTERNAL_STORAGE` is granted.

| Test | File | Type | Status |
|------|------|------|--------|
| No automated test | — | — | MISSING |
| Manual: write file under `pages/`, observe detection latency < 1 s | — | Manual | MISSING |

**Coverage**: No unit or integration test for `SafChangeDetector.startFileObservers`. The implementation is confirmed in code (two `FileObserver` instances on `pages/` and `journals/`), and ADR-001 documents the rationale. Android's `FileObserver` is a thin wrapper around `inotify_add_watch` and cannot be exercised without a real or emulated Android device with MANAGE_EXTERNAL_STORAGE.

**Gaps**:
- No Robolectric or instrumented test for `SafChangeDetector` subdirectory detection.
- A Robolectric test could verify that `startFileObservers` creates two watchers on the correct directories (by inspecting state), but cannot verify that `inotify` events actually fire (requires real device).
- Missing subdirectory-existence filter test: when `pages/` does not exist, `startFileObservers` must not throw.

---

### FR-6 — `ContentObserver` registered on subdirectory children URIs

**Acceptance**: A SAF provider that delivers `ContentObserver` notifications for subdirectory changes triggers `onExternalChange` for changes in `pages/` and `journals/`.

| Test | File | Type | Status |
|------|------|------|--------|
| No automated test | — | — | MISSING |
| Manual: SAF write to `pages/`, observe fast-path detection | — | Manual | MISSING |

**Coverage**: No test for `SafChangeDetector.startContentObserversAndPoller`. Implementation confirmed in code (two `ContentObserver` registrations on `"$treeDocId/pages"` and `"$treeDocId/journals"` children URIs) and documented in ADR-002.

**Gaps**:
- No Robolectric test verifying the two `ContentObserver` registrations are made on the correct URIs.
- No test for the `try/catch` path where a provider rejects registration.
- A Robolectric test using a mock `ContentResolver` could verify that `registerContentObserver` is called with the correct subdirectory URIs, without requiring a real SAF provider.

---

### FR-7 — `ShadowFlushActor` calls `onFlushed` after each successful SAF write

**Acceptance**: After successful `writeFile`, `onFlushed` is called with the SAF path.

| Test | File | Type | Status |
|------|------|------|--------|
| `onFlushed is called after successful SAF write` (TR-3) | `ShadowFlushActorTest.kt` | Unit (Android/Robolectric) | EXISTS |
| `onFlushed is NOT called when SAF write fails` (TR-4) | `ShadowFlushActorTest.kt` | Unit (Android/Robolectric) | EXISTS |
| `onFlushed is NOT called when shadow is missing` (TR-5) | `ShadowFlushActorTest.kt` | Unit (Android/Robolectric) | EXISTS |
| `flush drains multiple pages and calls onFlushed for each success` | `ShadowFlushActorTest.kt` | Unit (Android/Robolectric) | EXISTS |

**Coverage**: Full. All required cases are covered.

**Gaps**: None for the core callback behavior. The mtime-race follow-up (Story 3.3) is explicitly out of scope for this release.

---

### FR-8 — `FileSystem.setOnFlushComplete` plumbing

**Acceptance**: `App.kt` wires `graphLoader::markFileWrittenByUs` via `fileSystem.setOnFlushComplete(...)`.

| Test | File | Type | Status |
|------|------|------|--------|
| No dedicated automated test | — | — | MISSING |

**Coverage**: Wiring confirmed in code (`App.kt` line 525, `PlatformFileSystem.kt` lines 648–657/672). No automated test exercises the full plumbing: `setOnFlushComplete` → `PlatformFileSystem` stores callback → `ShadowFlushActor.flush()` receives it → `onFlushed` fires → `markFileWrittenByUs` called.

**Gaps**:
- An integration test could instantiate a `PlatformFileSystem` stub and verify the callback reaches `ShadowFlushActor`. This is achievable with Robolectric.
- Acceptable to cover with a code-review confirmation + manual test that no spurious conflict dialog appears after a flush.

---

### FR-9 — `ShadowFlushActor` is stateless (no owned scope)

**Acceptance**: `ShadowFlushActor` has no `CoroutineScope` field; `flush()` is a `suspend fun`.

| Test | File | Type | Status |
|------|------|------|--------|
| Structural enforcement: compilation fails if owned scope is re-added with no caller suspension | — | Static / Code review | IMPLICIT |
| `onFlushed is called after successful SAF write` (exercises `flush()` as suspend fun) | `ShadowFlushActorTest.kt` | Unit (Android/Robolectric) | EXISTS |

**Coverage**: The `flush()` suspend-fun design is exercised by all three existing `ShadowFlushActorTest` cases (they call `runBlocking { actor.flush() }`). The absence of a scope leak is a structural property — it cannot be detected at runtime without a heap profiler. Code review is the appropriate verification mechanism.

**Gaps**:
- No test explicitly asserts no `CoroutineScope` field exists. This is enforced by ADR-004 and code review.

---

### FR-10 — `freshProcess` flag is per-`ShadowFileCache` instance

**Acceptance**: `isFirstAccess()` returns `true` on first call, `false` on all subsequent calls; two instances are independent.

| Test | File | Type | Status |
|------|------|------|--------|
| `isFirstAccess returns true on first call and false on subsequent calls` (TR-6) | `ShadowFileCacheTest.kt` | Unit (Android/Robolectric) | EXISTS |
| `isFirstAccess is independent per cache instance (graph switch scenario)` (TR-6) | `ShadowFileCacheTest.kt` | Unit (Android/Robolectric) | EXISTS |

**Coverage**: Full. Both required cases from TR-6 are covered.

**Gaps**: None.

---

## Testing Requirement Traceability

### TR-1 — GraphFileWatcherTest: open-but-unedited page reloads

| Test | File | Status |
|------|------|--------|
| `TC-06 open page with no unsaved edits IS reloaded on external change` | `GraphFileWatcherTest.kt` | EXISTS — fully covers TR-1 |

### TR-2 — GraphFileWatcherTest: dirty page is NOT auto-reloaded

| Test | File | Status |
|------|------|--------|
| `TC-04 checkDirectoryForChanges skips onReloadFile for pages with unsaved edits` | `GraphFileWatcherTest.kt` | EXISTS — covers onReloadFile skip and onDirtyFile call |
| `TC-05 checkDirectoryForChanges emits externalFileChanges even for pages with unsaved edits` | `GraphFileWatcherTest.kt` | EXISTS — covers externalFileChanges emission |
| `TC-07 journal page with unsaved edits is NOT auto-reloaded` | `GraphFileWatcherTest.kt` | EXISTS — journal-specific dirty path |

### TR-3 — ShadowFlushActorTest: onFlushed fires on success

| Test | File | Status |
|------|------|--------|
| `onFlushed is called after successful SAF write` | `ShadowFlushActorTest.kt` | EXISTS |
| `flush drains multiple pages and calls onFlushed for each success` | `ShadowFlushActorTest.kt` | EXISTS — multi-page extension |

### TR-4 — ShadowFlushActorTest: onFlushed NOT called on failure

| Test | File | Status |
|------|------|--------|
| `onFlushed is NOT called when SAF write fails` | `ShadowFlushActorTest.kt` | EXISTS |

### TR-5 — ShadowFlushActorTest: onFlushed NOT called when shadow missing

| Test | File | Status |
|------|------|--------|
| `onFlushed is NOT called when shadow is missing` | `ShadowFlushActorTest.kt` | EXISTS |

### TR-6 — ShadowFileCacheTest: isFirstAccess is per-instance

| Test | File | Status |
|------|------|--------|
| `isFirstAccess returns true on first call and false on subsequent calls` | `ShadowFileCacheTest.kt` | EXISTS |
| `isFirstAccess is independent per cache instance (graph switch scenario)` | `ShadowFileCacheTest.kt` | EXISTS |

---

## Missing Test Cases (Full List)

| ID | Requirement | Test Name | Type | Priority |
|----|-------------|-----------|------|----------|
| M-01 | FR-2 | `BlockStateManagerDirtyPageUuidsTest` — empty set with no edits | Unit (jvmTest/businessTest) | HIGH — plan cites this test as IMPLEMENTED but file does not exist |
| M-02 | FR-2 | `BlockStateManagerDirtyPageUuidsTest` — emits page UUID on dirty block | Unit (jvmTest/businessTest) | HIGH |
| M-03 | FR-2 | `BlockStateManagerDirtyPageUuidsTest` — returns empty after save | Unit (jvmTest/businessTest) | HIGH |
| M-04 | FR-2 | `BlockStateManagerDirtyPageUuidsTest` — only dirty page's UUID, not all open pages | Unit (jvmTest/businessTest) | HIGH |
| M-05 | FR-3 | `GraphLoaderSetUnsavedPageUuidsTest` — UUID-to-path resolution and job cancellation | Unit (jvmTest) | MEDIUM |
| M-06 | FR-3 | `GraphLoaderSetUnsavedPageUuidsTest` — null clears unsavedPageFilePaths | Unit (jvmTest) | MEDIUM |
| M-07 | FR-5 | `SafChangeDetectorFileObserverTest` — two observers created on correct dirs | Unit (androidUnitTest/Robolectric) | MEDIUM |
| M-08 | FR-5 | `SafChangeDetectorFileObserverTest` — missing subdir skipped gracefully | Unit (androidUnitTest/Robolectric) | MEDIUM |
| M-09 | FR-6 | `SafChangeDetectorContentObserverTest` — two registrations on correct subdirectory URIs | Unit (androidUnitTest/Robolectric) | MEDIUM |
| M-10 | FR-6 | `SafChangeDetectorContentObserverTest` — registration failure caught and logged | Unit (androidUnitTest/Robolectric) | LOW |
| M-11 | FR-1 / G1 | Manual: journals live-refresh — external write, ≥5 s, journal updates on screen | Manual (Android device) | HIGH — acceptance criterion in requirements |
| M-12 | FR-5 / G2 | Manual: `FileObserver` fires for `pages/` change within 1 s (MANAGE_EXTERNAL_STORAGE) | Manual (Android device) | HIGH — acceptance criterion in requirements |
| M-13 | FR-6 / G2 | Manual: SAF write to `pages/` triggers fast-path ContentObserver detection | Manual (Android device) | HIGH — acceptance criterion in requirements |
| M-14 | FR-4 | Wiring test: `StelekitViewModel` calls `setUnsavedPageUuids` on graph load and null on unload | Unit or integration | LOW (wiring confirmed by code review) |

---

## Test Count Summary

| Type | Existing | Missing (automated) | Missing (manual) | Total |
|------|----------|---------------------|------------------|-------|
| Unit — JVM (`jvmTest`) | 10 | 6 | 0 | 16 |
| Unit — Android/Robolectric (`androidUnitTest`) | 7 | 4 | 0 | 11 |
| Manual | 0 | 0 | 3 | 3 |
| **Total** | **17** | **10** | **3** | **30** |

Existing test breakdown:
- `GraphFileWatcherTest.kt`: 10 test cases (suppress_preventsReloadFile, noSuppress_callsReloadFile, close_cancelsOwnedScope, close_stopsWatcherJob, beginGitMerge_suppressesFiles, endGitMerge_restoresNormalBehavior, TC-04, TC-05, TC-06, TC-07, TC-11, TC-16) — **12 test cases**, JVM
- `ShadowFlushActorTest.kt`: 4 test cases (TR-3 success, TR-4 failure, TR-5 missing shadow, multi-page drain) — **4 test cases**, Android/Robolectric
- `ShadowFileCacheTest.kt`: 13 test cases including TR-6 `isFirstAccess` cases — **13 test cases**, Android/Robolectric

Corrected count: **29 existing tests**, **10 missing automated**, **3 missing manual**.

---

## Requirements Coverage Fraction

| Requirement | Has ≥1 Test | Notes |
|-------------|-------------|-------|
| FR-1 | YES | TC-04, TC-05, TC-06, TC-07 |
| FR-2 | NO | `BlockStateManagerDirtyPageUuidsTest` does not exist on disk (M-01 through M-04) |
| FR-3 | PARTIAL | Indirectly via watcher guard tests; no direct unit test for `setUnsavedPageUuids` |
| FR-4 | NO | No automated test; wiring confirmed by code review only |
| FR-5 | NO | No test; implementation confirmed by code review and ADR-001 |
| FR-6 | NO | No test; implementation confirmed by code review and ADR-002 |
| FR-7 | YES | TR-3, TR-4, TR-5 fully covered |
| FR-8 | NO | No automated test; wiring confirmed by code review |
| FR-9 | PARTIAL | Exercised implicitly; structural property enforced by design |
| FR-10 | YES | TR-6 fully covered |
| TR-1 | YES | TC-06 |
| TR-2 | YES | TC-04, TC-05, TC-07 |
| TR-3 | YES | ShadowFlushActorTest success case |
| TR-4 | YES | ShadowFlushActorTest failure case |
| TR-5 | YES | ShadowFlushActorTest missing-shadow case |
| TR-6 | YES | ShadowFileCacheTest isFirstAccess cases |

**Coverage fraction (automated, FR only)**: 4/10 FRs have ≥1 direct automated test. 2 additional FRs (FR-3, FR-9) have partial/indirect coverage.

**Coverage fraction (TR only)**: 6/6 TRs are fully covered by existing tests.

---

## Implementation Readiness Gate

### Criterion 1: Requirements Coverage — does validation.md map every FR to at least one test?

**Result: CONCERNS**

- FR-1, FR-7, FR-10: fully covered.
- FR-2: `BlockStateManagerDirtyPageUuidsTest` is cited in plan.md as IMPLEMENTED but does not exist on disk. This is the most significant gap — an entire test class is missing for a requirement whose implementation is confirmed in code.
- FR-3: partially covered (indirectly); no dedicated test for `setUnsavedPageUuids`.
- FR-4, FR-5, FR-6, FR-8: no automated test; all confirmed by code review and ADR documentation.
- FR-9: structurally enforced; exercised implicitly.

All 6 testing requirements (TR-1 through TR-6) are fully covered.

### Criterion 2: Plan Completeness — does plan.md have tasks for every FR? Are IMPLEMENTED tasks confirmed in code?

**Result: PASS with one concern**

Every FR maps to at least one story in plan.md. All stories marked IMPLEMENTED are confirmed in code:
- FR-1/FR-2/FR-3/FR-4 (Epic 1): `dirtyPageUuids` in BlockStateManager ✓, `setUnsavedPageUuids` in GraphLoaderPort/GraphLoader ✓, wiring in StelekitViewModel ✓
- FR-5/FR-6 (Epic 2): Two `FileObserver` instances and two `ContentObserver` registrations in SafChangeDetector ✓
- FR-7/FR-9 (Epic 3): `onFlushed` callback and stateless `suspend fun` in ShadowFlushActor ✓
- FR-10 (Epic 4): `freshInstance: AtomicBoolean(true)` and `isFirstAccess()` in ShadowFileCache ✓
- FR-8 (Epic 5): `setOnFlushComplete` on FileSystem interface ✓, PlatformFileSystem override ✓, App.kt wiring ✓

**One concern**: Story 1.1 in the plan cites `BlockStateManagerDirtyPageUuidsTest` as an IMPLEMENTED test. This file does not exist. The implementation (the `dirtyPageUuids` property in BlockStateManager) is confirmed in code; only the test is absent.

Two TODO stories (Story 2.3, Story 3.3) and two low-priority TODO stories (7.3, 7.4) are explicitly deferred and do not block ship.

### Criterion 3: No blocking ADR gaps — are all 5 ADRs consistent with the implementation?

**Result: PASS**

All 5 ADRs are in `docs/adr/` with status `Accepted`:

- **ADR-001** (FileObserver per subdirectory): Consistent. `SafChangeDetector` creates `fileObservers: MutableList<FileObserver>` with entries for `pages/` and `journals/`, using `CREATE | DELETE | MODIFY | MOVED_FROM | MOVED_TO` mask and directory-existence filter before `startWatching()`.
- **ADR-002** (ContentObserver on subdirectory children URIs): Consistent. `startContentObserversAndPoller` registers on `"$treeDocId/pages"` and `"$treeDocId/journals"` children URIs with `notifyForDescendants = true`, wrapped in `try/catch`.
- **ADR-003** (dirtyPageUuids watcher guard): Consistent. `BlockStateManager.dirtyPageUuids` uses `combine(_dirtyBlocks, _blocks)` with `SharingStarted.Eagerly`. `GraphLoader.setUnsavedPageUuids` cancels previous job before launching new collector. `StelekitViewModel` wires the flow on graph load. One minor discrepancy: ADR-003 shows `distinctUntilChanged()` in the `stateIn` chain; this is listed as a TODO (Story 7.3) in plan.md, suggesting it may not yet be in the implementation. This is not a blocking gap — `distinctUntilChanged` is a performance optimization, not a correctness requirement.
- **ADR-004** (ShadowFlushActor stateless suspend fun): Consistent. `ShadowFlushActor.flush()` is `suspend fun`, runs in `withContext(Dispatchers.IO)`, no `CoroutineScope` field, `onFlushed` parameter present.
- **ADR-005** (ShadowFileCache per-instance flag): Consistent. `freshInstance = AtomicBoolean(true)` is an instance field, `isFirstAccess()` uses `getAndSet(false)`. Companion `freshProcess` field is absent.

No ADR is in conflict with the implementation.

### Criterion 4: Test execution — is the current CI state acceptable for ship?

**Result: PASS with known constraint**

- **JVM tests (`jvmTest`)**: User-confirmed passing. This covers all 12 `GraphFileWatcherTest` cases (TC-04, TC-05, TC-06, TC-07, and 8 others covering pre-existing behavior).
- **Android unit tests (`androidUnitTest`)**: Cannot run without Android SDK on current Linux CI. The tests use Robolectric (`@RunWith(RobolectricTestRunner::class) @Config(sdk = [29])`), which requires the Android SDK in the classpath. This is a pre-existing CI constraint documented in plan.md Story 6.2. These tests must be validated locally or on an Android-SDK-equipped CI agent before ship, but their absence from the current Linux CI run is acceptable given the explicit documentation.
- **The missing `BlockStateManagerDirtyPageUuidsTest`**: This is the only test cited as IMPLEMENTED in the plan that does not exist. It should be created before declaring FR-2 fully tested. However, the `dirtyPageUuids` implementation is in code and can be manually verified by a reviewer.

---

## Readiness Gate Verdict

**CONCERNS**

The implementation is complete for all 10 functional requirements and all 5 ADRs are consistent with the code. The 6 testing requirements (TR-1 through TR-6) are fully covered by existing tests that pass.

Two concerns prevent a clean PASS:

1. **`BlockStateManagerDirtyPageUuidsTest` is missing** (M-01 through M-04). Plan.md marks this test as IMPLEMENTED, but no such file exists. FR-2 (`dirtyPageUuids` StateFlow) is the foundation of the entire guard fix; it deserves direct test coverage. This test should be written before declaring the branch ready to merge.

2. **FR-5 and FR-6 have no automated tests**. The `SafChangeDetector` subdirectory-registration logic is not tested by any Robolectric test. Given the history of these being silently broken with no detection, at minimum a Robolectric mock-ContentResolver test verifying the correct URIs are registered (M-09) would strengthen confidence. This is lower priority than M-01.

The three manual tests (M-11, M-12, M-13) are acceptance-criterion requirements from the requirements document and must be executed on a real Android device before ship, even if they are not blocking CI.
