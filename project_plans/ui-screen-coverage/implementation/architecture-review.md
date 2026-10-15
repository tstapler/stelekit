# Architecture Review: ui-screen-coverage
**Date**: 2026-10-13
**Verdict**: CLEAN (0 blockers, 0 concerns, 1 nitpick remaining)

---

## Blockers

- [x] **Story 1.1.1 Task 2 — `CopyStage.DryRun` does not exist; test would not compile**

  **RESOLVED (2026-10-13)**: Plan now uses `CopyFlowState(stage = CopyStage.Running, dryRun = DryRunView(...))` throughout Task 2. The Domain Glossary entry for `CopyStage` explicitly lists all valid enum values and calls out: "`DryRun` is NOT a stage value — it is a `DryRunView?` field on `CopyFlowState`". The compile error is eliminated.

  ~~The actual enum in `CopyFlowController.kt:82` is:
  ```
  enum class CopyStage { Idle, Picking, Running, Finished, RunFailed, ConfirmUndo, Conflicts, Interrupted }
  ```
  `DryRun` is **not** a stage value. The dry-run dialog is controlled by `state.dryRun != null` (a `DryRunView?` field on `CopyFlowState`), rendered unconditionally at `CopyFlowHost.kt:112–123` independently of `state.stage`.

  The plan's glossary lists `DryRun` as one of "8 stages" and Story 1.1.1 Task 2 says:
  > "build `CopyFlowState(stage = CopyStage.DryRun, dryRun = ..., request = ...)`"

  `CopyStage.DryRun` is an unresolved reference — this would be a compile error. The glossary must be corrected and the test rewritten to use `CopyFlowState(dryRun = DryRunView(...))` with any non-DryRun stage (e.g. `CopyStage.Picking`).~~

---

## Concerns

- [x] **Story 1.3.1 — `TestScope()` default dispatcher will hang `waitUntil` assertions**

  **RESOLVED (2026-10-13)**: Story 1.3.1 Task 2 now prescribes `CoroutineScope(Dispatchers.Unconfined)` with an in-plan explanation of why `TestScope()` must not be used ("its default `StandardTestDispatcher` suspends coroutines until explicitly advanced, so `load()` won't execute when `waitUntil` polls the Looper"). The dangling `also { ... }` fragment has been removed. The same fix also applies to Story 1.3.2 Task 2, which now uses `CoroutineScope(Dispatchers.Unconfined)` as well.

  ~~The `LibraryStatsScreenTest` boilerplate (plan section 1.3.1, Task 2) constructs
  `LibraryStatsViewModel(..., TestScope().also { ... })`. In `kotlinx-coroutines-test:1.10.2`
  (the version on the classpath), `TestScope()` with no arguments uses
  `StandardTestDispatcher(TestCoroutineScheduler())`. Coroutines launched inside `load()` via
  `scope.launch { }` are enqueued in that scheduler and will **not** execute until the
  scheduler is advanced — `composeTestRule.waitUntil(5_000) { vm.state.value is Loaded }` only
  pumps the Android Looper, not the `TestCoroutineScheduler`. Every test that calls `load()` and
  then polls `vm.state` would spin for 5 s then fail.~~

- [x] **Story 1.3.2 — no `BlockSearchRepository` no-op is available in the `androidUnitTest` classpath**

  **RESOLVED (2026-10-13)**: Story 1.3.2 Task 2 now explicitly instructs the implementer to first check whether a no-op implementation exists, and if not, create one inline as an anonymous object: `val noOpSearch = object : BlockSearchRepository { override fun search(query: String) = emptyFlow<List<Block>>() /* stub remaining methods similarly */ }`. An implementer following the plan will not be blocked by a missing class.

  ~~`AllPagesViewModel` requires a `BlockSearchRepository` constructor argument. The existing
  `FakeBlockRepository` that satisfies this in `AllPagesViewModelTest` lives in
  `jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/FakeRepositories.kt`, a source set
  inaccessible to `androidUnitTest`. There is no `NoOpBlockSearchRepository` or
  `InMemoryBlockSearchRepository` in `commonMain` or any shared test source set.~~

---

## Nitpicks

- ~~**Glossary `CopyStage` entry is factually wrong on two counts**: lists `DryRun` (does not
  exist) and omits `Idle` (does exist). Correct to match the actual enum in
  `CopyFlowController.kt:82`.~~ **RESOLVED**: Glossary now lists the correct enum values and notes `DryRun` is a field, not a stage.

- ~~**`TestScope().also { ... }` is an incomplete code fragment**: the `also` body is never shown,
  leaving it ambiguous whether a teardown `scope.cancel()` is intended. The boilerplate as
  written would not compile as-is (trailing dangling `also {}`). Either complete the body or
  remove it and document teardown separately.~~ **RESOLVED**: The `also { ... }` fragment is gone; `CoroutineScope(Dispatchers.Unconfined)` is used directly.

- **`waitUntil(5_000)` blanket mandate conflates sync and async cases** (same issue as
  adversarial-review minor 1): Pattern A tests (ConflictResolutionScreen, Step3Auth) make
  purely synchronous state changes. `composeTestRule.waitForIdle()` is both semantically
  correct and faster (no 5 s failure-path latency). The plan's blanket mandate should qualify
  "use `waitUntil` only when the state change is driven by a coroutine." *(Not addressed — cosmetic, does not block implementation.)*
