# UI Screen Coverage — Architecture Research

## Screen interface patterns

Two distinct patterns exist in `ui.screens`:

### Pattern A — Pure callbacks + state structs (no ViewModel at call site)

The composable function accepts only data types and lambdas. No ViewModel instance crosses the
boundary. Tests call `setContent { Screen(state, onX = {}) }` with no fake machinery.

| Screen | Entry point | State/callbacks |
|--------|-------------|-----------------|
| `ConflictResolutionScreen` | `ConflictResolutionScreen(conflicts, onResolve, onDismiss)` | `List<ConflictFile>` + suspend lambdas |
| `Step3Auth` (GitSetupStep3Auth.kt) | `Step3Auth(authType, onAuthTypeChange, sshKeyPath, …)` | all primitives + lambdas |
| `Step5TestAndSave` (GitSetupStep3Auth.kt's sibling step) | `Step5TestAndSave(testState, saving, …)` | all primitives + lambdas |

`ConflictResolutionScreenTest` and `GitSetupStep5CancelTest` both exercise this pattern directly —
`setContent { MaterialTheme { Screen(…) } }` with inline lambdas, no fakes. These are the
canonical reference tests.

### Pattern B — ViewModel parameter (concrete class, constructor-injectable)

The composable accepts a concrete ViewModel (no interface). Tests must either instantiate the
ViewModel with fake repository collaborators or add a stateless inner composable as a seam.

| Screen | ViewModel | Injectable seam |
|--------|-----------|-----------------|
| `AllPagesScreen` | `AllPagesViewModel(pageRepository, blockSearchRepository, scope)` | None yet — needs seam or fake repos |
| `LibraryStatsScreen` | `LibraryStatsViewModel(provider, graphPath, scope)` | `NoOpLibraryStatsProvider` already exists — trivial to construct |
| `ConflictReviewScreen` | `ConflictReviewViewModel(pageRepository, blockRepository, …)` | `ConflictReviewContent(state, onResolve, …)` **already exists** (line 131: "Robolectric tests exercise this directly") |
| `ImportScreen` | `ImportViewModel(…)` | Partial coverage exists (30%); ViewModel injectable |

## CopyFlowHost structure

`CopyFlowHost.kt` contains two composables with different test-access profiles:

- `CopyFlowHost(controller, binding, conflictReviewFactory, onOpenPage, onNotice)` — **stateful**
  wrapper. Owns `DisposableEffect`/`LaunchedEffect` lifecycles, collects `StateFlow` from the
  concrete `CopyFlowController`. Not directly testable without a full controller fake.

- `CopyFlowContent(state, progress, nameOf, actions, conflicts)` — **stateless renderer**. The
  docstring explicitly reads: "Stateless renderer of `CopyFlowState`; Robolectric tests drive it
  with hand-built states." This is the named test seam. Tests construct `CopyFlowState` values
  directly and pass a no-op `CopyFlowActions` implementation.

`CopyFlowActions` is an **interface** (not a class), so a no-op implementation requires only an
`object : CopyFlowActions { … }` inline stub — no mocking framework needed.

## ViewModel interfaces vs concrete classes

None of the target-screen ViewModels have interfaces. They are all concrete classes. However,
every one accepts its dependencies through the constructor (repositories, providers, scope), which
makes them constructible in tests without a DI framework:

- `LibraryStatsViewModel(provider = NoOpLibraryStatsProvider)` — zero-effort construction, provider
  already exists.
- `AllPagesViewModel(pageRepository, blockSearchRepository, scope)` — requires fake repositories.
  The codebase has an in-memory backend (`RepositoryFactory.IN_MEMORY`); a `TestScope` covers the
  coroutine scope.
- `ConflictReviewViewModel(pageRepository, blockRepository, propertyRepository, writeActor, writer,
  scope)` — heavier constructor; prefer testing via the `ConflictReviewContent` seam instead.

## DI patterns affecting test setup

No Koin/Hilt/Dagger is used at the composable level. Every screen receives its ViewModel from the
caller. The test harness is therefore flat: construct the ViewModel (or its fake), call
`setContent`, drive via ComposeTestRule. No container, no module, no component needed.

`RepositoryFactory.IN_MEMORY` (used extensively in `businessTest`) provides real SQLDelight
schema over an in-memory SQLite driver, which is the idiomatic way to get a real-but-fast
`PageRepository` in tests.

## Recommendations by screen area

### Extend as-is (pure seam already exists)

- **`ConflictResolutionScreen`** — test via the existing top-level signature. Already has 7
  Robolectric tests in `ConflictResolutionScreenTest`. Add scenarios for the outer "Use one
  version" / "Resolve line by line" tab strip and the multi-file case.

- **`CopyFlowHost`** — test via `CopyFlowContent`. Build `CopyFlowState` for each
  `CopyStage` value (Picking, Running, Finished, RunFailed, Undoing, …) and stub `CopyFlowActions`
  inline. This avoids any dependency on `CopyFlowController`.

- **`ConflictReviewScreen`** — test via `ConflictReviewContent(state, onResolve, …)`. The seam is
  already named and documented. Build `ConflictReviewState` variants for Loading, Loaded-with-rows,
  Error, and pagination.

- **`GitSetupStep3Auth`** — `Step3Auth` is a pure-callback composable; instantiate directly.

- **`LibraryStatsScreen`** — `LibraryStatsViewModel(NoOpLibraryStatsProvider)` works out of the
  box. One test file covers Loading/Loaded/Error states.

### Isolate via seam (ViewModel must be either faked or a seam added)

- **`AllPagesScreen`** — `AllPagesViewModel` is a concrete class with no stateless inner
  composable today. Two options:
  1. Construct `AllPagesViewModel` with `RepositoryFactory.IN_MEMORY` repos and a `TestScope` —
     matches the businessTest idiom already used for repository tests.
  2. Extract `AllPagesContent(pages, isLoading, selectedUuids, sortColumn, …, onX)` as a
     stateless inner composable (matches the `CopyFlowContent` / `ConflictReviewContent` pattern).
  Option 1 is faster; option 2 gives a permanent seam. Given the screen's 251-line size, option 1
  is sufficient to hit ≥55% without the refactor cost.

### Extend as-is (partial coverage, ViewModel injectable)

- **`ImportScreen`** — already at 30%. `ImportViewModel` accepts its collaborators by constructor.
  Focus new tests on the REVIEW stage, the HTML-paste path, and QR-transfer entry point.

- **`CopyPagesScreen`** — needs the `CopyPagesPickerViewModel` (held inside `CopyFlowState.picker`);
  confirm its constructor is injectable. If so, build a picker state instance and pass it directly
  to `CopyPagesScreen`. Alternatively, exercise it through `CopyFlowContent` with a
  `Picking`-stage state that includes a wired picker.

## Summary of test file placement

All new tests go in `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/`.
- `@RunWith(RobolectricTestRunner::class)` on every class
- `@get:Rule val composeTestRule = createComposeRule()`
- Wrap every `setContent` in `MaterialTheme { … }`
- No `@Ignore`, no `Thread.sleep` — use `waitForIdle()` after async state changes
