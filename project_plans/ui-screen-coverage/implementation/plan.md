# Implementation Plan: ui-screen-coverage

**Feature**: Raise ui.screens test coverage from 37.5% to ≥55% via Robolectric Compose tests
**Date**: 2026-10-13
**Status**: Ready for implementation
**ADRs**: ADR-001-jacoco-report-split.md

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `CopyFlowContent` | Stateless `@Composable` renderer of `CopyFlowState`; the seam through which all CopyFlow stages are testable without a live `CopyFlowController`. | Defined at `CopyFlowHost.kt:89`. Used by `CopyFlowHostTest.kt`. |
| `CopyPagesContent` | Stateless `@Composable` renderer of `CopyPagesState`; the seam for picker-stage tests. | Defined in `CopyPagesScreen.kt`. Used by `CopyPagesScreenTest.kt`. |
| `ConflictReviewContent` | Stateless `@Composable` renderer of `ConflictReviewState`; the seam for conflict-list tests. Comment at `ConflictReviewScreen.kt:131` documents Robolectric intent. | Used by `ConflictReviewTest.kt`. |
| `ConflictResolutionScreen` | Stateful Compose screen for per-file/per-hunk git merge conflict resolution. Pure Pattern A (callbacks only, no VM). | `ui/screens/git/ConflictResolutionScreen.kt`. |
| `Step3Auth` | `internal` composable for the auth-type selection step of the git setup wizard. Pure Pattern A. | `GitSetupStep3Auth.kt:44`. |
| `LibraryStatsViewModel` | Holds `LibraryStatsState` (Idle/Loading/Loaded/Error) and drives `LibraryStatsScreen`. Accepts a `LibraryStatsProvider`. | `LibraryStatsViewModel.kt`. |
| `NoOpLibraryStatsProvider` | Test-friendly no-op implementation of `LibraryStatsProvider` that always returns `null`. Available on the classpath. | Defined in `stats/LibraryStatsProvider.kt` or nearby. |
| `AllPagesViewModel` | Holds filtered/sorted page list, filter query, sort state, and selection mode. Accepts `PageRepository` and `BlockSearchRepository`. | `AllPagesViewModel.kt`. |
| `ImportViewModel` | Drives `ImportScreen`; holds `ImportState` with rawText, stage, suggestions. Accepts a dispatcher parameter for test injection. | `ImportViewModel.kt`. |
| `ListLoad` | Sealed class in `CopyPagesState`: `Idle`, `Initial`, `Updating`, `Failed`. Drives loading-banner visibility. | `CopyPagesState.kt`. |
| `CopyStage` | Enum for the stages of the copy flow: `Idle`, `Picking`, `Running`, `Finished`, `RunFailed`, `ConfirmUndo`, `Conflicts`, `Interrupted`. (`DryRun` is NOT a stage value — it is a `DryRunView?` field on `CopyFlowState`; set `stage = CopyStage.Running` and `dryRun = DryRunView(...)` to test the DryRun dialog.) | `CopyFlowHost.kt`. |
| `ConflictReviewState` | Data class for the conflict review screen: `loading`, `rows`, `hasMore`, `confirmRemove`, `actionError`, `loadError`. | Inline in `ConflictReviewScreen.kt`. |
| `DestinationStatus` | Sealed class for a copy destination's availability: `Available`, `Disabled(kind, reason, action?)`, `Checking`, `CouldntCheck`. | `CopyPagesState.kt`. |
| `GitAuthType` | Enum for authentication mode in Step3Auth: `NONE`, `SSH_KEY`, `HTTPS_TOKEN`, `GITHUB_OAUTH`. | `git/model/GitAuthType.kt`. |
| `jacocoAndroidTestReport` | Gradle task that generates the JaCoCo coverage report for Robolectric/`testDebugUnitTest` runs. This is the report that new tests will move — distinct from `jacocoTestReport` (JVM only). | See ADR-001. |
| `Pattern A` | Test pattern for composables with pure callbacks and no ViewModel: construct state inline, pass lambdas, assert via captured vars. | Research "Architecture" section. |
| `Pattern B` | Test pattern for composables driven by a ViewModel: construct the VM with in-memory or no-op deps, inject `Dispatchers.Unconfined`, collect state via `collectAsState()` or `waitUntil`. | Research "Architecture" section. |
| `IN_MEMORY` | `RepositoryBackend.IN_MEMORY` — the in-process repository backend used for tests. No SQLite required. | `RepositoryFactory.kt`. |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason Rejected |
|-----------|---------------|--------|---------------------|-----------------|
| `CopyPagesScreen` | Pattern B via `CopyPagesContent` seam + `CopyPagesViewModel` for stateful tests | `CopyPagesScreenTest.kt` (existing pattern) | Full VM integration with a live `CopyFlowController` | `CopyFlowController` pulls in network/DB deps; seam already exists and is documented |
| `CopyFlowHost` (remaining stage coverage) | Pattern A via `CopyFlowContent` with hand-built `CopyFlowState` | `CopyFlowHostTest.kt` (existing pattern) | Testing `CopyFlowHost` directly | `CopyFlowHost` attaches to a live `CopyGraphBinding`; `CopyFlowContent` is the documented seam |
| `ConflictResolutionScreen` | Pattern A: pass lambdas, assert via captured vars and `waitForIdle` | `ConflictResolutionScreenTest.kt` (existing pattern) | ViewModel or router integration | Screen is pure callbacks; tests already demonstrate the pattern works |
| `ConflictReviewScreen` | Pattern A via `ConflictReviewContent` seam | `ConflictReviewTest.kt` (existing pattern) | `ConflictReviewViewModel` + in-memory repo | Seam is already documented and tested; adding ViewModel integration is out of scope |
| `AllPagesScreen` | Pattern B: `AllPagesViewModel` with in-memory repos and `TestCoroutineScope` | Research "Architecture" section | Stateless seam extraction | No stateless seam exists; VM constructor accepts repo params directly — clean injection path |
| `LibraryStatsScreen` | Pattern B: `LibraryStatsViewModel(provider = NoOpLibraryStatsProvider)` for Idle/Loading/Error; custom `LibraryStatsProvider` for Loaded | `LibraryStatsViewModel.kt` constructor; `NoOpLibraryStatsProvider` import | Mock framework (`mockk`) | No mock framework in the test classpath; constructor injection is cleaner |
| `ImportScreen` | Pattern B: `ImportViewModel` with injectable dispatcher (`Dispatchers.Unconfined`) | Research "Architecture" section; `ImportViewModel.kt` dispatcher param | `@Config` activity-level integration | ImportScreen has internal stage routing (`INPUT`/`REVIEW`); the VM controls it |
| `Step3Auth` | Pattern A: pure callbacks, test directly | `GitSetupStep3Auth.kt` — `internal` composable, no VM | ViewModel wrapper | The function has no state except what callers pass; four auth-type branches tested independently |

**Creative pass — alternatives considered:**

1. **Approach: Shared test-state factory objects** — extract a single `TestStateFactory.kt` helper shared across all test files. Strength: eliminates repeated boilerplate. Weakness: creates an invisible coupling point between tests; one change breaks the factory and everything with it. *Rejected.*

2. **Approach: Screenshot (Roborazzi) pixel-level tests in jvmTest** — add golden images for each new screen. Strength: catches visual regressions automatically. Weakness: requires a real X11 display for `jvmTest`, conflicts with the requirements' explicit out-of-scope exclusion, and slows down headless CI. *Rejected; out of scope per requirements.*

3. **Approach (chosen): Per-file state() helper + composable seams** — each test file defines its own minimal `state(...)` factory function. Strength: each test file is self-contained and readable; seams already documented in source. Weakness: some boilerplate repeated across files. *Chosen.*

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|------|---------------|-------------|---------------|
| `AllAndroidUnitTests.kt` exclusion of Compose tests | Bazel's resolved `ui-test-junit4` jar lacks the `setContent(Function0)` overload; all Compose tests hit `NoSuchMethodError` in Bazel. | **Leave as-is.** New Compose test classes must NOT be added to `@Suite.SuiteClasses`. Run via `./gradlew testDebugUnitTest` only. | Fixing the Bazel Compose-test gap is out of scope per the existing comment in `AllAndroidUnitTests.kt` lines 53-64. |
| `jacocoTestReport` vs `jacocoAndroidTestReport` split | AC-8 in requirements references "jacocoTestReport" (JVM) but Robolectric tests feed `jacocoAndroidTestReport` (Android). Both are unioned by the diff-coverage gate in PR #402. | **RESOLVED (per ADR-001): stories use `jacocoAndroidTestReport`; AC-8 requirement text had wrong task name.** | See ADR-001. |
| `ImportScreen` uses `rememberCoroutineScope()` | The screen passes `coroutineScope` to `confirmImport()` launch sites, which is a pattern the CLAUDE.md warns about for long-lived classes. However `ImportScreen` is a stateless composable that creates the scope for its own lifecycle — not a stored `remember { }` object. | **No change needed.** The usage is legitimate: `coroutineScope` is used for transient button-click launches within the composable's own lifetime. | CLAUDE.md scope rule targets stored `remember { }` objects; `ImportScreen` is not one. |

---

## Migration Plan

N/A — no schema or data changes.

---

## Observability Plan

- **Logs**: N/A — test code only.
- **Metrics**: Coverage delta measured by `./gradlew :kmp:testDebugUnitTest :kmp:jacocoAndroidTestReport` (Robolectric/Android — the only report Robolectric tests move; `jacocoTestReport` is JVM-only and unaffected by these tests) after each story branch merges. The diff-coverage gate in PR #402 blocks any diff line with 0% coverage at CI time.
- **Alerts**: PR #402's incremental gate immediately flags new uncovered diffs; this plan closes the pre-existing gap before that bar rises.

---

## Risk Control

- **Feature flag**: not gated — tests are additive.
- **Rollback procedure**: standard PR revert; tests are additive with no production code changes.
- **Staged rollout**: all tests ship on merge — no rollout required.

---

## Unresolved Questions

- [ ] **jacocoTestReport vs jacocoAndroidTestReport**: AC-8 references "jacocoTestReport" but Robolectric (`testDebugUnitTest`) tests feed **only** `jacocoAndroidTestReport` — they cannot move the `jacocoTestReport` number at all (`jacocoTestReport` consumes `jacoco/jvmTest.exec` and JVM class files; Robolectric feeds `jacoco/testDebugUnitTest.exec` → `jacocoAndroidTestReport`). AC-8's requirement text should reference `jacocoAndroidTestReport`. — **blocks Story 3.1 coverage measurement** — owner: Tyler
- [ ] **Target branch**: `stelekit-merges` or a new branch from `main`? — **blocks PR creation for all stories** — owner: Tyler

---

## Dependency Visualization

```
Phase 1 (Core) ─────────────────────────────────────────────────────────────────────
  Epic 1.1: Copy-Pages Augmentation
    Story 1.1.1: CopyFlowContent stage coverage ─────────────────────────────────┐
    Story 1.1.2: CopyPagesContent augmentation ───────────────────────────────────┤
  Epic 1.2: Conflict Screens Augmentation                                         │
    Story 1.2.1: ConflictResolutionScreen augmentation ──────────────────────────┤
    Story 1.2.2: ConflictReviewContent augmentation ─────────────────────────────┤
  Epic 1.3: New Screen Tests                                                      │
    Story 1.3.1: LibraryStatsScreen tests ───────────────────────────────────────┤
    Story 1.3.2: AllPagesScreen tests ───────────────────────────────────────────┤

Phase 2 (Expansion) ─────────────────────────────────────────────────────────────  │
  Epic 2.1: ImportScreen augmentation (depends on nothing) ───────────────────────┤
  Epic 2.2: GitSetupStep3Auth tests (depends on nothing) ─────────────────────────┤

Phase 3 (Gate) ────────────────────────────────────────────────────────────────── │
  Story 3.1: Measure overall coverage ← all Phase 1 + 2 stories ─────────────────┘
```

All Phase 1 and 2 stories are independent of each other and can be implemented in parallel across branches. Phase 3 requires all prior stories to be merged.

---

## Phase 1: Core Screen Tests (High ROI, 0%→≥50%)

### Epic 1.1: Copy-Pages Flow Tests

**Goal**: Bring `CopyFlowHost.kt` (167 lines, ~1%) and `CopyPagesScreen.kt` (447 lines, baseline 0% but already has tests) to ≥50% each by extending the existing test files.

---

#### Story 1.1.1: CopyFlowContent — additional stage coverage

**Effort**: M (6–8 test methods)
**File**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/CopyFlowHostTest.kt` (extend)

**AC Coverage**: supports AC-1 (CopyFlowHost/CopyPagesScreen combined) and AC-8 (overall).

**Context**: `CopyFlowHostTest.kt` already has the `CopyFlowActions` stub and a `show()` helper. Additional test cases should cover the `Running`, `Finished`, `RunFailed`, `Conflicts`, `ConfirmUndo`, and `Interrupted` stages by constructing `CopyFlowState` with those `CopyStage` values and asserting the rendered dialog. Note: `DryRun` is **not** a `CopyStage` value — it is a `DryRunView?` field on `CopyFlowState`; test the DryRun dialog by setting `stage = CopyStage.Running` and `dryRun = DryRunView(...)`. Also cover `switchConfirm` state: build `CopyFlowState(switchConfirm = SwitchConfirmState(...))` and assert the switch-confirm dialog is shown with its confirm and cancel buttons.

**Tasks**:

1. In `CopyFlowHostTest.kt`, add a `state(stage)` factory that builds a `CopyFlowState` for each `CopyStage` with minimal required fields.
2. Add test: `dryRun field present shows DryRun dialog` — build `CopyFlowState(stage = CopyStage.Running, dryRun = DryRunView(...))` and assert the DryRun dialog title is present. (`dryRun` is a field on `CopyFlowState`, not a `CopyStage` value — `CopyStage.DryRun` does not exist.)
3. Add test: `Running stage shows progress overlay` — assert progress text or "Copying" label is displayed.
4. Add test: `Finished stage shows result dialog with Done button` — assert "Done" button wires `done` action.
5. Add test: `RunFailed stage shows Retry and Choose another` — assert both buttons call expected actions.
6. Add test: `Conflicts stage shows Review conflicts button` — assert `reviewConflicts` is invoked on click.
7. Add test: `ConfirmUndo stage shows confirmation dialog` — assert `confirmUndo` and `cancelUndo` wiring.
8. Add test: `Interrupted stage shows InterruptedCopyDialog with Resume, Dismiss, and Open picker buttons` — build `CopyFlowState(stage = CopyStage.Interrupted, ...)`, assert dialog title and all three button labels present; verify each callback fires.
9. Add test: `switchConfirm overlay shows switch-confirm dialog` — build `CopyFlowState(stage = CopyStage.Picking, switchConfirm = SwitchConfirmState(...))`, assert dialog and its callbacks are wired.

**Given-When-Then (AC-1 example):**
- **Given**: `CopyFlowContent` is rendered with `CopyFlowState(stage = CopyStage.Running)` and a mock `CopyFlowActions`.
- **When**: the progress overlay is visible.
- **Then**: a "Stop" or in-progress label is present in the semantics tree.

---

#### Story 1.1.2: CopyPagesContent — augmentation

**Effort**: S (3–4 test methods)
**File**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/CopyPagesScreenTest.kt` (extend)

**Context**: Existing `CopyPagesScreenTest.kt` covers loading states, selection, destinations, Gate 1/2, discard dialog, font scale, and RTL. Still needed: the "Still reading... results so far" banner (ListLoad with intermediate rows), the state where copied pages have error state (loadError non-null with rows present), and the "no results" empty state.

**Tasks**:

1. Add test: `ListLoad.Updating with no total shows "Counting..." and existing rows`.
2. Add test: `empty result from a completed load shows "No pages found"` (if that string exists in source) or `0 results, 0 selected`.
3. Add test: `loadError banner coexists with rows and Retry wires callback` (non-empty rows with loadError set).

**Given-When-Then (AC-1 example):**
- **Given**: `CopyPagesContent` rendered with `state(rows = emptyList(), listLoad = ListLoad.Idle, total = 0L)`.
- **When**: the screen is idle with no results.
- **Then**: `rule.onNodeWithText("0 results, 0 selected").assertExists()`.

---

### Epic 1.2: Conflict Screens Augmentation

**Goal**: Bring `ConflictResolutionScreen.kt` (327 lines, baseline 0% — now has tests) and `ConflictReviewScreen.kt` (187 lines — now has tests) to ≥50% each by extending the existing test files.

---

#### Story 1.2.1: ConflictResolutionScreen — augmentation

**Effort**: S (3–4 test methods)
**File**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/git/ConflictResolutionScreenTest.kt` (extend)

**Context**: Existing test covers: finish-gating, keep-both, untick, keep-theirs, restore-ticks, hand-edit, reset-to-picks, failed-resolve. Still missing: multiple-file navigation (if multiple conflicts), the "Use one version" / "Resolve line by line" toggle on the initial view (before a hunk mode is chosen), and abort-merge path.

**Tasks**:

1. Add test: `initial state shows Use one version and Resolve line by line tabs for each hunk`.
2. Add test: `keep mine resolves to LOCAL side` — click "Keep mine", Finish Merge, assert `HunkResolution.AcceptLocal`.
3. Add test: `multiple files — all must be resolved before Finish Merge is enabled` — build a `ConflictFile` list with 2 files, resolve only the first, assert Finish Merge disabled; resolve second, assert enabled.
4. Add test: `onDismiss is called when the dialog's dismiss action is triggered` (if a close button/back handler exists on the screen).

**Given-When-Then (AC-2 example):**
- **Given**: `ConflictResolutionScreen` with one conflict hunk, `onResolve` captures the hunks.
- **When**: user clicks "Keep mine" then "Finish Merge".
- **Then**: `resolvedHunks[filePath]!!.single().resolution == HunkResolution.AcceptLocal`.

---

#### Story 1.2.2: ConflictReviewContent — augmentation

**Effort**: S (3–5 test methods)
**File**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/ConflictReviewTest.kt` (extend)

**Context**: Existing test covers: Escape-closes, loading skeleton, load-failure, empty, action-failure, row content, custom actions, buttons, live region, missing original, remove confirmation, font scale, RTL. Still potentially useful: `hasMore` pagination trigger ("Load more" or similar), Undo snackbar display, and the `confirmRemove` flow being cancelled via the Cancel button.

**Tasks**:

1. Add test: `hasMore=true shows a "Load more" button that calls onLoadMore`.
2. Add test: `Undo snackbar appears when undoMessage is set in state` (if `ConflictReviewState` supports it).
3. Add test: `resolving the last row shows No conflicts to review next render` — trigger `onResolve`, re-render with empty rows.

**Given-When-Then (AC-3 example):**
- **Given**: `ConflictReviewContent` with `loaded(row(1), row(2, "Plan"), hasMore = true)`.
- **When**: the screen renders.
- **Then**: a "Load more" node exists and invoking `onLoadMore` on click is confirmed by the captured call count.

---

### Epic 1.3: New Screen Tests (0% Screens)

---

#### Story 1.3.1: LibraryStatsScreen tests

**Effort**: M (6–8 test methods)
**File**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/LibraryStatsScreenTest.kt` (new)

**Boilerplate**:
```kotlin
@RunWith(RobolectricTestRunner::class)
class LibraryStatsScreenTest {
    @get:Rule val composeTestRule = createComposeRule()
    // helpers...
}
```

**Do NOT add to `AllAndroidUnitTests.kt`.**

**Context**: `LibraryStatsScreen` delegates to `LibraryStatsViewModel`. The VM has `Idle`, `Loading`, `Loaded`, `Error` states. `NoOpLibraryStatsProvider` returns `null` synchronously, causing the `Error("Graph path not found: ...")` branch. A custom `LibraryStatsProvider` returning a real `GraphStatsReport` drives the `Loaded` branch.

**Tasks**:

1. Create `LibraryStatsScreenTest.kt` in `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/`.
2. Add helper `private fun vm(provider: LibraryStatsProvider = NoOpLibraryStatsProvider, path: String = "/graphs/test") = LibraryStatsViewModel(provider, path, CoroutineScope(Dispatchers.Unconfined))`. (Do NOT use `TestScope()` here — its default `StandardTestDispatcher` suspends coroutines until explicitly advanced, so `load()` won't execute when `waitUntil` polls the Looper. `Dispatchers.Unconfined` executes coroutines eagerly and synchronously, which is what the Key Constraints require.)
3. Add test: `Idle state shows Tap refresh to scan your library` — construct VM without calling `load()`, assert text.
4. Add test: `Loading state shows Scanning library and circular indicator` — call `vm.load()` while provider suspends (use `Dispatchers.Unconfined` + a `CompletableDeferred`), observe loading state.
5. Add test: `LaunchedEffect auto-triggers load on first compose when state is Idle` — render with a provider that immediately returns a report, use `waitUntil` for Loaded state, assert a stat label is visible (e.g., "pages total" or the page count from the report).
6. Add test: `Error state shows error message in error color` — `NoOpLibraryStatsProvider` returns null → `Error("Graph path not found: /graphs/test")`; assert message text displayed.
7. Add test: `Refresh button in non-loading state calls load() again` — click Refresh icon, assert load was triggered a second time.
8. Add test: `Loaded state renders StatsContent with page count` — provide a minimal `GraphStatsReport` with `pageCount = 42`, assert "42" appears in semantics.

**Given-When-Then (AC-5 example):**
- **Given**: `LibraryStatsScreen` is rendered with a `LibraryStatsViewModel` whose provider immediately returns a `GraphStatsReport(pageCount = 42, ...)`.
- **When**: the `LaunchedEffect(Unit)` fires `viewModel.load()`.
- **Then**: `composeTestRule.waitUntil(5_000) { vm.state.value is LibraryStatsState.Loaded }` passes, and `"42"` is found in the semantics tree.

---

#### Story 1.3.2: AllPagesScreen tests

**Effort**: L (8–12 test methods across 1 file)
**File**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/AllPagesScreenTest.kt` (new)

**Boilerplate**:
```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AllPagesScreenTest {
    @get:Rule val composeTestRule = createComposeRule()
    // helpers...
}
```

**Do NOT add to `AllAndroidUnitTests.kt`.**

**Context**: `AllPagesScreen` accepts `AllPagesViewModel`, `onPageClick`, `onBulkDelete`. The VM accepts `PageRepository` and `BlockSearchRepository`. Use `InMemoryPageRepository` (available in `businessTest`/`androidUnitTest` classpath) and a no-op `BlockSearchRepository`. Inject `TestScope` (from `kotlinx-coroutines-test`). Use `Dispatchers.Unconfined` or `TestScope.testScheduler` to control coroutine execution.

**LazyColumn warning**: Any test that interacts with rows below the visible viewport must call `performScrollTo()` before `performClick()`. Keep test lists at ≤5 pages unless scroll behavior is specifically under test. Use `@Config(qualifiers = "w411dp-h891dp-xxhdpi")` for consistent viewport.

**Tasks**:

1. Create `AllPagesScreenTest.kt` in `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/`.
2. First, check whether a no-op `BlockSearchRepository` already exists on the `androidUnitTest` classpath (e.g. `InMemoryBlockSearchRepository`). If it does not exist, create one inline in the test file: `val noOpSearch = object : BlockSearchRepository { override fun search(query: String) = emptyFlow<List<Block>>() /* stub remaining methods similarly */ }`. Then add helper `private fun makeVm(pages: List<Page> = emptyList()): AllPagesViewModel` — build with `InMemoryPageRepository` seeded with `pages`, the no-op `BlockSearchRepository`, and `CoroutineScope(Dispatchers.Unconfined)` (not `TestScope` — see Key Constraints).
3. Add test: `page list renders all page names` — seed 3 pages, render, `waitUntil` list size == 3, assert each page name visible.
4. Add test: `filter text field narrows the visible list` — seed "Alpha" and "Beta", type "Beta" in the filter field, assert only "Beta" row visible.
5. Add test: `clicking a page row invokes onPageClick with the correct Page` — verify callback receives the right `Page` object.
6. Add test: `long-press enters selection mode` (if `combinedClickable` with long-press is the pattern) — simulate long-press, assert `selectedUuids` grows to 1.
7. Add test: `delete dialog appears when delete action taken in selection mode` — enter selection mode, select 1 page, trigger delete action, assert `"Delete 1 selected page(s)? This cannot be undone."` dialog text visible.
8. Add test: `confirming delete calls onBulkDelete with selected UUIDs` — select 1 page, open delete dialog, confirm, assert callback invoked with that UUID.
9. Add test: `sort column toggle changes sort order` — assert descending sort produces reversed name order vs ascending.
10. Add test: `empty graph shows no page rows and no delete button` — seed no pages, assert empty state or zero rows.

**Given-When-Then (AC-4 example):**
- **Given**: `AllPagesScreen` rendered with an `AllPagesViewModel` backed by an `InMemoryPageRepository` containing pages named "Alpha", "Beta", "Gamma".
- **When**: the screen finishes loading (`isLoading == false`).
- **Then**: `composeTestRule.onNodeWithText("Alpha").assertExists()`, `onNodeWithText("Beta").assertExists()`, `onNodeWithText("Gamma").assertExists()`.

---

## Phase 2: Coverage Expansion (Partial Coverage → ≥55% Overall)

### Epic 2.1: ImportScreen Tests (30%→55%)

**Goal**: Bring `ImportScreen.kt` (436 lines, 30%) and `ImportViewModel.kt` to ≥55% line coverage.

---

#### Story 2.1.1: ImportScreen — Input stage tests

**Effort**: M (6–8 test methods)
**File**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/ImportScreenTest.kt` (new)

**Boilerplate**:
```kotlin
@RunWith(RobolectricTestRunner::class)
class ImportScreenTest {
    @get:Rule val composeTestRule = createComposeRule()
}
```

**Do NOT add to `AllAndroidUnitTests.kt`.**

**Context**: `ImportScreen` has an internal `Stage` enum (`INPUT`/`REVIEW`). `ImportViewModel` accepts a dispatcher parameter. The `LaunchedEffect(state.savedPageName, state.showUndoSnackbar)` auto-dismisses when save completes. Use `Dispatchers.Unconfined` so coroutines launched in the VM execute eagerly without needing `advanceUntilIdle`.

**Tasks**:

1. Create `ImportScreenTest.kt` in `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/`.
2. Add helper `private fun vm(...): ImportViewModel` constructing with no-op dependencies and `Dispatchers.Unconfined`.
3. Add test: `initial state shows Import text title and PASTE tab` — render, assert "Import text" top-bar text and "Paste" tab label.
4. Add test: `typing in the text field updates rawText in VM state` — type text, `waitUntil` rawText matches.
5. Add test: `Import button is disabled when rawText is empty` — assert "Import" or "Next" button is not enabled with empty state.
6. Add test: `Next/Import button is enabled when rawText is non-empty` — set rawText, assert button enabled.
7. Add test: `tapping Next moves to REVIEW stage and shows Review screen title` — set rawText, tap next, assert review-stage content visible.
8. Add test: `URL tab is visible and switching to it shows a URL input field` — click URL tab label, assert URL input field present.

**Given-When-Then (AC-6 example):**
- **Given**: `ImportScreen` rendered with a `ImportViewModel` that has no initial text.
- **When**: user types "My new page content" into the text area.
- **Then**: `composeTestRule.waitUntil(5_000) { vm.state.value.rawText == "My new page content" }` succeeds.

---

#### Story 2.1.2: ImportScreen — Review stage tests

**Effort**: M (was S; upgraded — see implementation note below)
**File**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/ImportScreenTest.kt` (extend Story 2.1.1 file)

**Implementation note**: `Stage.REVIEW` is a private enum in `ImportScreen.kt`; tests cannot inject the review stage directly. Navigate to review by calling `ImportViewModel.submit()` (or its equivalent) after setting rawText and running with `Dispatchers.Unconfined`. If ImportViewModel requires a page-saver or url-fetcher collaborator, pass a no-op lambda stub. Upgrade effort estimate to M (was S).

**Tasks**:

1. Add test: `review stage shows Back button that returns to INPUT` — navigate to review, click Back, assert input stage label.
2. Add test: `Confirm import in review stage saves and triggers auto-dismiss` — navigate to review, click confirm, assert `onDismiss` callback is invoked.
3. Add test: `Undo snackbar appears after save and hides after it expires` — if the snackbar is visible, assert its text.

---

### Epic 2.2: GitSetupStep3Auth Tests (16%→≥50%)

**Goal**: Bring `GitSetupStep3Auth.kt` (239 lines, 16%) to ≥50% line coverage.

---

#### Story 2.2.1: Step3Auth — all four auth-type branches

**Effort**: M (6–8 test methods)
**File**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupStep3AuthTest.kt` (new)

**Boilerplate**:
```kotlin
@RunWith(RobolectricTestRunner::class)
class GitSetupStep3AuthTest {
    @get:Rule val composeTestRule = createComposeRule()

    private fun show(
        authType: GitAuthType = GitAuthType.NONE,
        onAuthTypeChange: (GitAuthType) -> Unit = {},
        sshKeyPath: String = "",
        httpsToken: String = "",
        tokenVisible: Boolean = false,
        onBack: () -> Unit = {},
        onNext: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                Step3Auth(
                    authType = authType,
                    onAuthTypeChange = onAuthTypeChange,
                    sshKeyPath = sshKeyPath,
                    onSshKeyPathChange = {},
                    httpsToken = httpsToken,
                    onHttpsTokenChange = {},
                    tokenVisible = tokenVisible,
                    onToggleTokenVisible = {},
                    sshPassphrase = "",
                    onSshPassphraseChange = {},
                    onBack = onBack,
                    onNext = onNext,
                )
            }
        }
    }
}
```

**Do NOT add to `AllAndroidUnitTests.kt`.**

**Tasks**:

1. Add test: `initial NONE shows four auth-type radio rows` — assert all four label strings present.
2. Add test: `clicking SSH key radio invokes onAuthTypeChange with SSH_KEY`.
3. Add test: `SSH_KEY auth type shows the SSH key path field and passphrase field`.
4. Add test: `clicking HTTPS token radio invokes onAuthTypeChange with HTTPS_TOKEN`.
5. Add test: `HTTPS_TOKEN auth type shows the token input field`.
6. Add test: `token visibility toggle calls onToggleTokenVisible` — with `tokenVisible = false`, click the visibility icon, assert callback fired.
7. Add test: `GITHUB_OAUTH auth type shows the OAuth section`.
8. Add test: `Back and Next buttons wire their callbacks`.

**Given-When-Then (AC-7 example):**
- **Given**: `Step3Auth` is rendered with `authType = GitAuthType.NONE`.
- **When**: user clicks the "SSH key" radio button row.
- **Then**: `onAuthTypeChange` is invoked with `GitAuthType.SSH_KEY`.

---

## Phase 3: Verification and Gate

### Epic 3.1: Coverage Measurement and Gate Validation

---

#### Story 3.1.1: Measure overall ui.screens coverage after all prior stories

**Effort**: XS (no new test methods; run and verify)
**Owner**: Tyler (needs target-branch resolution from Unresolved Questions)

**Tasks**:

1. Merge all Phase 1 and Phase 2 story branches into the target branch.
2. Run `./gradlew :kmp:testDebugUnitTest :kmp:jacocoAndroidTestReport`. The JaCoCo XML is at `kmp/build/reports/jacoco/jacoco-android.xml`. To extract per-package line coverage for `ui/screens`:
   ```
   grep -A3 'ui/screens' kmp/build/reports/jacoco/jacoco-android.xml | grep 'LINE'
   ```
3. Confirm per-file coverage against ACs 1–7:
   - `CopyPagesScreen.kt` ≥50%
   - `ConflictResolutionScreen.kt` ≥50%
   - `ConflictReviewScreen.kt` ≥50%
   - `AllPagesScreen.kt` ≥50%
   - `LibraryStatsScreen.kt` ≥40%
   - `ImportScreen.kt` ≥55%
   - `GitSetupStep3Auth.kt` ≥50%
4. Confirm overall `ui.screens` ≥55% (AC-8).
5. If any AC falls short, identify the uncovered lines (JaCoCo HTML report) and add targeted tests in the appropriate file.
6. Paste the relevant section of `kmp/build/reports/jacoco/jacoco-android.xml` showing overall `ui.screens` line coverage ≥55% into the PR description before closing this story. This is the manual gate — Story 3.1.1 is not done until this output is recorded.

**Given-When-Then (AC-8 example):**
- **Given**: All Phase 1 and Phase 2 stories are merged and `testDebugUnitTest` passes.
- **When**: `jacocoAndroidTestReport` is generated for the `ui.screens` package.
- **Then**: overall line coverage is ≥55%.

---

#### Story 3.1.2: Confirm no existing tests are broken

**Effort**: XS

**Tasks**:

1. Run `./gradlew testDebugUnitTest` from the root. All pre-existing tests must pass.
2. Run `bazel test //kmp:android_unit_tests` to confirm the Bazel suite (which excludes all new Compose tests by design) still passes.
3. Confirm no new test classes were accidentally added to `AllAndroidUnitTests.kt`.

---

## Effort Summary

| Story | Effort | Test Methods | Target Files |
|-------|--------|-------------|--------------|
| 1.1.1 CopyFlowContent stage coverage | M | 6–8 | `CopyFlowHostTest.kt` |
| 1.1.2 CopyPagesContent augmentation | S | 3–4 | `CopyPagesScreenTest.kt` |
| 1.2.1 ConflictResolutionScreen augmentation | S | 3–4 | `ConflictResolutionScreenTest.kt` |
| 1.2.2 ConflictReviewContent augmentation | S | 3–5 | `ConflictReviewTest.kt` |
| 1.3.1 LibraryStatsScreen tests | M | 6–8 | `LibraryStatsScreenTest.kt` (new) |
| 1.3.2 AllPagesScreen tests | L | 8–12 | `AllPagesScreenTest.kt` (new) |
| 2.1.1 ImportScreen Input stage | M | 6–8 | `ImportScreenTest.kt` (new) |
| 2.1.2 ImportScreen Review stage | S | 3–5 | `ImportScreenTest.kt` (extend) |
| 2.2.1 Step3Auth all auth branches | M | 6–8 | `GitSetupStep3AuthTest.kt` (new) |
| 3.1.1 Coverage measurement | XS | 0 | — |
| 3.1.2 Regression check | XS | 0 | — |
| **Total** | | **~48–66** | 4 new files, 5 extended files |

**Cost class key**: XS = 1–2 tests; S = 3–5 tests; M = 6–10 tests; L = multiple files (8–12 tests).

---

## Key Constraints (implementer checklist)

- `@RunWith(RobolectricTestRunner::class)` on every new Compose test class.
- `@get:Rule val composeTestRule = createComposeRule()` — no `createAndroidComposeRule`.
- `MaterialTheme { ... }` wrapper always required.
- Use `composeTestRule.waitUntil(5_000) { condition }` for async state, NOT `runTest` / `advanceUntilIdle`.
- Inject `Dispatchers.Unconfined` (not `TestCoroutineDispatcher`) for ViewModels in Compose tests — it executes coroutines eagerly synchronously.
- Use `@Config(qualifiers = "w411dp-h891dp-xxhdpi")` on files that test LazyColumn viewport-sensitive behaviour.
- **Never** add new Compose test classes to `AllAndroidUnitTests.kt`; they hit `NoSuchMethodError` in Bazel.
- **Never** add screen tests to `jvmTest` — they belong in `androidUnitTest`.
- All new files go in `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/` (or subpackage matching the source package).
