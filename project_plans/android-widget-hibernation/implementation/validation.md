# Validation Plan: Android Widget / Share-Target Hibernation Recovery

## Overview

This document maps every functional and non-functional requirement to at least one test case.
Some Android-specific behaviours (Glance widget rendering, true OS hibernation, tile click in
a live Quick Settings panel) cannot be exercised by unit tests and are marked **manual**.

---

## 1. Test Cases

### TC-1: GraphManager auto-restore — registry has activeGraphId
**Requirement**: FR-1  
**Type**: businessTest (JVM unit test)  
**File**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerAutoRestoreTest.kt`

**Description**: Construct a `GraphManager` with a `StubSettings` pre-populated with a
`graph_registry` JSON that contains a non-null `activeGraphId`. Use
`GraphBackend.IN_MEMORY` so no real SQLite file is needed.

**Assertions**:
- Immediately after construction (before `awaitPendingMigration`): `getActiveRepositorySet()` returns null (IO coroutine not yet run).
- After `runBlocking { gm.awaitPendingMigration() }`: `getActiveRepositorySet()` is non-null (IN_MEMORY backend opens synchronously; confirms `switchGraph` was called).
- `getActiveGraphId()` returns the expected graph ID both before and after.

**Traceability**: FR-1 (auto-restore on process wake), FR-5 (only activates when configured)

---

### TC-2: GraphManager auto-restore — empty registry (fresh install)
**Requirement**: FR-1, FR-5  
**Type**: businessTest (JVM unit test)  
**File**: same file as TC-1

**Description**: Construct a `GraphManager` with empty `StubSettings` (no `graph_registry`
key). Use `GraphBackend.IN_MEMORY`.

**Assertions**:
- `runBlocking { gm.awaitPendingMigration() }` completes immediately (pre-completed deferred; `switchGraph` was never called).
- `getActiveRepositorySet()` is null.
- `getActiveGraphId()` is null.

**Traceability**: FR-5 (genuinely-unconfigured state must be preserved)

---

### TC-3: GraphManager — double switchGraph same ID resolves awaitPendingMigration
**Requirement**: NFR-2, NFR-3  
**Type**: businessTest (JVM unit test)  
**File**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerAutoRestoreTest.kt`

**Description**: Construct a `GraphManager` with an active graph (triggering the auto-restore
`switchGraph` from `init`), then immediately call `switchGraph(sameId)` again to simulate
the `MainActivity` / `StelekitApp` code path.

**Assertions**:
- `gm.awaitPendingMigration()` resolves without hanging (timeout assertion via `withTimeout(5_000)`).
- No exception is thrown.
- `getActiveRepositorySet()` is non-null after the second `awaitPendingMigration()` (second switch succeeds).

**Traceability**: NFR-2 (no duplicate-init corruption), NFR-3 (timeout safety — deferred always completes)

---

### TC-4: GraphManager — `_pendingMigration` annotated `@Volatile` (code inspection)
**Requirement**: Concern C-1 from adversarial review (JVM memory model)  
**Type**: Static code inspection (enforced as a businessTest assertion on source text)  
**File**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerVolatileTest.kt`

**Description**: Read `GraphManager.kt` source text and assert that `_pendingMigration` is
declared with the `@Volatile` annotation. This mirrors the `MigrationRunnerCoverageTest`
pattern of using a static text assertion to enforce an architectural invariant.

**Assertions**:
- The string `@Volatile` appears on the line immediately before `_pendingMigration` declaration
  (or on the same line in a combined annotation form).

**Note**: This guards against regression where `@Volatile` is removed during a refactor.

**Traceability**: C-1 (adversarial review: JVM data race on non-volatile `var`)

---

### TC-5: VoiceCaptureWidgetViewModel — awaits migration before reading repoSet
**Requirement**: FR-4  
**Type**: androidUnitTest (Android local unit test, uses `viewModelScope`)  
**File**: `androidApp/src/test/kotlin/dev/stapler/stelekit/VoiceCaptureWidgetViewModelTest.kt`

**Description**: Create a `FakeGraphManager` stub that:
- Returns a non-null `activeGraphId` immediately (graph is configured).
- Suspends `awaitPendingMigration()` for 200 ms via `kotlinx.coroutines.delay`.
- Returns a `FakeRepositorySet` from `getActiveRepositorySet()` after the delay.

Call `vm.initialize { true }` inside `runTest`. Assert that `_state` is `Idle` at time 0
(the async `viewModelScope.launch` has not yet run `awaitPendingMigration`). Use
`advanceTimeBy(250)` then assert that the state has advanced beyond `Idle`.

**Assertions**:
- At `t=0`: `vm.state.value == VoiceCaptureState.Idle`
- After `advanceTimeBy(250)`: `vm.state.value != VoiceCaptureState.Idle`
- No `VoiceCaptureState.Error(kind = VoiceErrorKind.NO_GRAPH)` emitted at any time during the wait.

**Traceability**: FR-4 (ViewModel awaits pending migration), FR-5 (does not fail with NO_GRAPH when graph IS configured)

---

### TC-6: VoiceCaptureWidgetViewModel — fails fast when no graph configured at all
**Requirement**: FR-5  
**Type**: androidUnitTest  
**File**: same file as TC-5

**Description**: Create a `FakeGraphManager` stub that returns null from `getActiveGraphId()`
(fresh install scenario). Call `vm.initialize { true }`.

**Assertions**:
- `vm.state.value` is a `VoiceCaptureState.Error` with `kind == VoiceErrorKind.NO_GRAPH` after `advanceUntilIdle()`.
- `awaitPendingMigration()` on the fake is never called (verify via a flag on the stub).

**Traceability**: FR-5 (unconfigured state not masked; fast fail preserved)

---

### TC-7: VoiceCaptureWidgetViewModel — AtomicBoolean prevents double-initialization
**Requirement**: NFR-2 (no duplicate initialization)  
**Type**: androidUnitTest  
**File**: same file as TC-5

**Description**: Create a `FakeGraphManager` with `migrationDelayMs = 500L`. Call
`vm.initialize { true }` twice in rapid succession. Advance time past the delay. Assert that
the `awaitPendingMigration()` stub was called exactly once (not twice).

**Assertions**:
- Migration awaited exactly 1 time (tracked by a counter on the fake).
- `vm.state.value` reflects a single successful initialization.

**Traceability**: NFR-2 (no duplicate init), plan section on AtomicBoolean guard

---

### TC-8: VoiceCaptureWidgetViewModel — DB open failure shows NO_GRAPH error
**Requirement**: FR-4, FR-5 (graceful degradation when DB init fails)  
**Type**: androidUnitTest  
**File**: same file as TC-5

**Description**: `FakeGraphManager` has non-null `activeGraphId` and completes
`awaitPendingMigration()` immediately, but `getActiveRepositorySet()` returns null (DB open
failed scenario).

**Assertions**:
- After `advanceUntilIdle()`: `vm.state.value` is `VoiceCaptureState.Error(kind = NO_GRAPH)`.

**Traceability**: FR-4 (post-await null-check path), FR-5 (graceful degradation)

---

### TC-9: CaptureTileService — launches CaptureActivity when graph is configured but DB not open
**Requirement**: FR-6  
**Type**: androidUnitTest  
**File**: `androidApp/src/test/kotlin/dev/stapler/stelekit/tile/CaptureTileServiceTest.kt`

**Description**: Use Robolectric or a hand-rolled stub to exercise `CaptureTileService.onClick()`.
Provide a `SteleKitApplication` stub where `graphManager.getActiveGraphId()` returns `"abc"` and
`graphManager.getActiveRepositorySet()` returns null.

**Assertions**:
- The `Intent` passed to `startActivity` (captured via shadow or test double) targets `CaptureActivity::class.java`.
- `MainActivity::class.java` is NOT the target.

**Traceability**: FR-6 (tile onClick launches correct activity; race-safe synchronous check)

---

### TC-10: CaptureTileService — launches MainActivity when no graph configured
**Requirement**: FR-5, FR-6  
**Type**: androidUnitTest  
**File**: same file as TC-9

**Description**: Same setup as TC-9 but `graphManager.getActiveGraphId()` returns null.

**Assertions**:
- Intent targets `MainActivity::class.java`.

**Traceability**: FR-5 (unconfigured state), FR-6 (tile fallback)

---

### TC-11: CaptureWidget — provideGlance awaits migration before rendering
**Requirement**: FR-2  
**Type**: MANUAL (cannot unit-test Glance widget composition)

**Reason for manual**: `CaptureWidget.provideGlance` calls `provideContent { }` which
invokes the Glance composition engine. There is no supported test double for the Glance
`GlanceId` / `provideContent` infrastructure in Android unit tests or Robolectric.
Instrumented UI tests on a real device or emulator are possible but are not part of the
current `androidUnitTest` source set.

**Manual procedure**:
1. Force-stop the app: `adb shell am force-stop dev.stapler.stelekit`
2. Observe the Capture widget on the home screen — it should show the loading state briefly (last RemoteViews), then update to show the capture button within ~2 s.
3. Confirm no "Open SteleKit" text appears permanently.

**Traceability**: FR-2 (widget awaits graph initialization)

---

### TC-12: VoiceWidget — provideGlance awaits migration before rendering
**Requirement**: FR-2  
**Type**: MANUAL (same reason as TC-11)

**Manual procedure**: Same as TC-11 but for the VoiceWidget.

**Traceability**: FR-2

---

### TC-13: CaptureActivity — reactive recomposition when graph becomes available
**Requirement**: FR-3  
**Type**: MANUAL (Android Activity Compose UI test)

**Reason for manual**: `CaptureActivity.setContent` with `collectAsState()` requires an
Activity under test. This could be covered by a Compose UI test (Espresso + `ActivityScenario`)
but requires an instrumented test environment. The current `androidUnitTest` source set does
not have an Espresso/ComposeTestRule setup.

**Manual procedure**:
1. Force-stop the app.
2. Tap the Capture widget (or share a URL) — `CaptureActivity` opens immediately.
3. Observe that within ~1–2 s (DB open time), the placeholder disappears and the Capture sheet appears without a manual relaunch.
4. Confirm the `collectAsState()` recomposition happens automatically.

**Traceability**: FR-3 (reactive recomposition on null → non-null transition)

---

### TC-14: Hibernation recovery — end-to-end widget
**Requirement**: FR-1, FR-2, FR-5 (success criterion 1)  
**Type**: MANUAL (integration test — requires device + force-stop)

**Manual procedure**:
1. Install SteleKit, configure a graph, confirm Capture widget shows capture button.
2. `adb shell am force-stop dev.stapler.stelekit`
3. Tap the Capture widget.
4. **Expected**: Capture sheet appears within 2 s (AC-1). No "Open SteleKit first" text shown permanently.

**Traceability**: FR-1, FR-2, success criterion 1, AC-1

---

### TC-15: Hibernation recovery — share target (CaptureActivity)
**Requirement**: FR-1, FR-3 (success criterion 2)  
**Type**: MANUAL (integration test)

**Manual procedure**:
1. Force-stop app.
2. Share a URL from another app (Chrome, etc.) → SteleKit.
3. **Expected**: Capture sheet appears with shared URL pre-filled (AC-2).

**Traceability**: FR-1, FR-3, success criterion 2, AC-2

---

### TC-16: Hibernation recovery — Quick Settings tile
**Requirement**: FR-1, FR-6 (success criterion 3)  
**Type**: MANUAL (integration test)

**Manual procedure**:
1. Force-stop app.
2. Open Quick Settings → tap SteleKit capture tile.
3. **Expected**: `CaptureActivity` opens (not `MainActivity`). May briefly show placeholder (<1 s), then shows capture sheet (AC-3).

**Traceability**: FR-6, success criterion 3, AC-3

---

### TC-17: No regression — normal MainActivity path
**Requirement**: NFR-4, success criterion 2 (no regression)  
**Type**: MANUAL (integration test)

**Manual procedure**:
1. Cold-start via launcher icon (no force-stop).
2. **Expected**: Graph loads normally. `logcat` shows no double-open errors, no `switchGraph` failures, no blank flash (AC-4).
3. Also verify existing `GraphManagerAddGraphTest` and `businessTest` suite passes (automated gate).

**Traceability**: NFR-4 (no behavior change for non-hibernation path)

---

### TC-18: Fresh install — all entry points show unconfigured placeholder
**Requirement**: FR-5 (success criterion 3)  
**Type**: MANUAL (integration test)

**Manual procedure**:
1. Fresh install (no previous data — clear app data or new install).
2. Tap capture widget → **Expected**: "Open SteleKit" placeholder.
3. Share from another app → **Expected**: "No graph" placeholder.
4. Tap tile → **Expected**: `MainActivity` opens (graph configured check fails → fallback).

**Traceability**: FR-5, success criterion 3, AC-5

---

### TC-19: NFR-1 — GraphManager constructor does not block the calling thread
**Requirement**: NFR-1  
**Type**: businessTest (JVM unit test)  
**File**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerAutoRestoreTest.kt`

**Description**: Construct a `GraphManager` with a non-null `activeGraphId` in settings. Measure
whether `getActiveRepositorySet()` is non-null immediately after construction (before any
suspension point). It must be null — confirming `switchGraph`'s DB work was launched as a
coroutine and not executed inline.

**Assertions**:
- Immediately after `GraphManager(...)` returns (no `delay`, no `awaitPendingMigration`): `gm.getActiveRepositorySet() == null`.
- This proves the constructor returned before the IO coroutine ran.

**Note**: Mirrors the pattern in `GraphManagerAddGraphTest` which checks that `fileExists` runs
off the caller thread.

**Traceability**: NFR-1 (no main-thread blocking in Application.onCreate)

---

## 2. What Cannot Be Automated (and Why)

| Test | Cannot Automate Because |
|---|---|
| TC-11: CaptureWidget `provideGlance` | No unit-testable Glance `provideContent` stub; requires live Glance runtime on device/emulator |
| TC-12: VoiceWidget `provideGlance` | Same reason |
| TC-13: CaptureActivity reactive recomposition | Requires Activity lifecycle + Compose composition engine; instrumented test possible but not in current `androidUnitTest` scope |
| TC-14: Widget hibernation end-to-end | Requires real OS process kill (`am force-stop`) + home screen widget |
| TC-15: Share-target end-to-end | Requires live share intent across apps |
| TC-16: Tile end-to-end | Requires Quick Settings panel + live tile service |
| TC-17: MainActivity no-regression | Requires full app cold start + logcat inspection |
| TC-18: Fresh-install all entry points | Requires clean install state |

---

## 3. Coverage Matrix

| Requirement | Test Cases | Coverage |
|---|---|---|
| **FR-1** Auto-restore graph on process wake | TC-1, TC-2, TC-3, TC-19, TC-14 (manual) | Unit + manual |
| **FR-2** Widget awaits graph initialization | TC-11 (manual), TC-12 (manual) | Manual only* |
| **FR-3** CaptureActivity observes StateFlow | TC-13 (manual), TC-15 (manual) | Manual only* |
| **FR-4** VoiceCaptureWidgetViewModel awaits migration | TC-5, TC-8 | Unit |
| **FR-5** Preserve no-graph behavior | TC-2, TC-6, TC-10, TC-18 (manual) | Unit + manual |
| **FR-6** CaptureTileService synchronous check | TC-9, TC-10, TC-16 (manual) | Unit + manual |
| **NFR-1** No main-thread blocking | TC-19 | Unit |
| **NFR-2** No duplicate initialization | TC-3, TC-7 | Unit |
| **NFR-3** Timeout safety | TC-3 (withTimeout assertion) | Unit |
| **NFR-4** No behavior change for normal path | TC-17 (manual), TC-3 | Manual + unit |
| **C-1** `@Volatile` on `_pendingMigration` | TC-4 (static inspection) | Unit (static) |
| **C-2** CaptureViewModel sync init gap | Verification task (code read), not a test | Pre-implementation check |
| **C-3** VoiceCaptureViewModel.close() scope | TC-7 (partial), manual observation | Partial |

*FR-2 and FR-3 are Android-UI-surface concerns (Glance widget rendering, Activity Compose
composition). They have no practical unit-test path. The manual integration tests (TC-14,
TC-15) provide end-to-end coverage. If instrumented tests (`androidTest` source set with
Espresso + ComposeTestRule) are added in future, TC-11, TC-12, and TC-13 should be
automated there.

---

## 4. Test Count Summary

| Category | Count |
|---|---|
| businessTest (JVM unit) | 5 (TC-1, TC-2, TC-3, TC-4, TC-19) |
| androidUnitTest (Android local unit) | 4 (TC-5, TC-6, TC-7, TC-8) |
| androidUnitTest (Android local unit — Tile) | 2 (TC-9, TC-10) |
| Manual / integration | 8 (TC-11 – TC-18) |
| **Total** | **19** |

---

## 5. Readiness Gate Assessment

### Criterion 1: Requirements complete
All 6 FRs and 4 NFRs in `requirements.md` are addressed in `plan.md`:
- FR-1 → `GraphManager.init` one-liner
- FR-2 → `CaptureWidget`/`VoiceWidget` `awaitPendingMigration()` before `provideContent`
- FR-3 → `CaptureActivity` `collectAsState()`
- FR-4 → `VoiceCaptureWidgetViewModel` `viewModelScope.launch` + `awaitPendingMigration()`
- FR-5 → `getActiveGraphId()` null-guard before await; fresh-install guard in `init`
- FR-6 → `CaptureTileService` `getActiveGraphId()` synchronous check
- NFR-1 → `switchGraph()` launches on `PlatformDispatcher.IO`; no blocking in constructor
- NFR-2 → existing `switchGraph` close-then-reopen; `AtomicBoolean` in ViewModel
- NFR-3 → `finally { deferred.complete(Unit) }` guarantee
- NFR-4 → double-`switchGraph` is safe; existing behavior preserved

**Status: PASS**

### Criterion 2: Plan actionable
Each of the 6 file changes has an exact before/after diff, line-level rationale, and
initialization-order analysis. The implementation order is specified (GraphManager first,
ViewModel last). No ambiguity about what to do in any file.

**One gap**: C-2 from the adversarial review (verify `CaptureViewModel.kt` for synchronous
`getActiveRepositorySet()` access) is flagged as a pre-implementation check, not a task with
a diff. The plan explicitly notes this gap. Implementer must read `CaptureViewModel.kt`
before implementing Task 3.1.

**Status: CONCERNS** — C-2 verification is required before implementing `CaptureActivity` changes.

### Criterion 3: Tests designed
This document covers all 10 requirements (6 FRs + 4 NFRs) with at least one test each.
Manual tests cover the 3 requirements that cannot be automated (FR-2, FR-3, NFR-4).
Static inspection test (TC-4) covers C-1 from the adversarial review.

**Status: PASS**

### Criterion 4: Adversarial review verdict
`adversarial-review.md` verdict: **CONCERNS** (not BLOCKED).
All three concerns are addressed in this validation plan:
- C-1: covered by TC-4 (static `@Volatile` inspection test)
- C-2: flagged as a required pre-implementation verification task
- C-3: covered partially by TC-7; noted as a pre-existing issue requiring `VoiceCaptureViewModel.close()` inspection

**Status: CONCERNS** — C-1 fix must be in the implementation; C-2 must be verified before coding `CaptureActivity`.

---

## 6. Overall Readiness Verdict: CONCERNS (not BLOCKED)

The plan is ready to implement with two action items that must be completed before or during
implementation:

**Action 1 (High — before any code change)**: Verify `CaptureViewModel.kt` does not call
`getActiveRepositorySet()` synchronously in its `init` block or constructor. If it does, add
`awaitPendingMigration()` there as well (same pattern as FR-4). This was explicitly called
out in the adversarial review as C-2.

**Action 2 (High — in GraphManager.kt change)**: Ensure `_pendingMigration` is declared
`@Volatile`. The plan text assumes this; TC-4 enforces it. Include the annotation in the
implementation diff.

Both actions are targeted one-line-or-less checks. Neither blocks the overall design.
Implementation can proceed in the order specified in the plan.
