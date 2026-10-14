# Research Findings — UI Shell Test Coverage

**Date**: 2026-10-13  
**Branch**: stelekit-merges @ e337d58c1e

---

## 1. Coverage Baseline (verified from PR #402 description)

| Scope | Lines covered/total | Branch covered/total |
|-------|---------------------|----------------------|
| `ui/` package overall | 1257 / 6005 = **20.9%** | 460 / 6420 = **7.2%** |
| `StelekitViewModel.kt` | ~59% | ~41% |
| `GraphContentActiveShell.kt` | 0% | 0% |
| `GraphDialogLayer.kt` | 0% | 0% |
| `ScreenRouter.kt` | 0% | 0% |
| `WikiSubdirFixDialog.kt` | 0% | 0% |
| `GraphContentNewGraphFlow.kt` | 0% | 0% |
| `FlashcardsScreen.kt` | 0% | 0% |
| `GraphContentLeftSidebar.kt` | 0% | 0% |
| `GraphContentVaultActions.kt` | 0% | 0% |
| `App.kt` | ~1% | ~0% |
| `ShareExportCoordinator.kt` | ~30% | ~12% |

---

## 2. Test Infrastructure Audit

### 2a. Existing Robolectric pattern (androidUnitTest)
- `NewGraphFlowTest.kt` — tests pure functions (`addGraphFlowMode()`, `newGraphPathFor()`) and composables (`NewGraphDialog`) with `@RunWith(RobolectricTestRunner::class)` + `createComposeRule()`
- `AddGraphAppOwnedTest.kt` — tests `PlainGraphAppOwnedWarningDialog` composable
- `ConflictReviewTest.kt`, `CopyDialogsTest.kt`, `CopyFlowHostTest.kt` — same pattern for copy/conflict dialogs
- `BranchRepairUiTest.kt` — full composable test with fake state

All use the standard pattern:
```kotlin
@RunWith(RobolectricTestRunner::class)
class Foo {
    @get:Rule val composeTestRule = createComposeRule()
    
    @Test fun `behavior assertion`() {
        composeTestRule.setContent { MaterialTheme { SomeComposable(...) } }
        composeTestRule.onNodeWithText("...").assertIsDisplayed()
    }
}
```

### 2b. businessTest pattern for ViewModel logic
- `StelekitViewModelCrashReproductionTest` — tests ViewModel coroutine safety
- `LargeGraphWarmStartCrashTest` — full graph lifecycle test
- `StelekitViewModelCrashReproductionTest` in `businessTest/` uses `InMemoryRepositoryFactory` to build a real `StelekitViewModel` without any composable

### 2c. Screen sealed class structure (`AppState.kt`)
`Screen` has these subclasses:
- `Screen.Journals` — `@HelpPage`
- `Screen.Flashcards` — `@HelpPage`
- `Screen.AllPages(conflictsOnly: Boolean)` — `@HelpPage`
- `Screen.LibraryStats` — `@HelpExempt`
- `Screen.Notifications` — `@HelpExempt`
- `Screen.Logs` — `@HelpExempt`
- `Screen.Performance` — `@HelpExempt`
- `Screen.GlobalUnlinkedReferences` — `@HelpPage`
- `Screen.Import` — `@HelpExempt`
- `Screen.VaultUnlock` — `@HelpExempt`
- `Screen.PageView(page: Page)` — `@HelpPage`
- `Screen.Gallery` — `@HelpExempt`
- `Screen.AssetBrowser` — `@HelpExempt`
- `Screen.AssetDetail(assetUuid)` — `@HelpExempt`
- `Screen.Annotation` — likely `@HelpExempt`
- Plus others from ScreenRouter (QrDecode, etc.)

---

## 3. Per-file Testability Analysis

### 3a. WikiSubdirFixDialog.kt (254 lines, 0%)

**Assessment**: High testability. It's a pure composable with 4 lambdas (`onDismiss`, `onMove`, `onMerge`, `onLeave`), `DirectoryScanResult` data model, and `DryRunPreview` data class. No ViewModel, no coroutines, no platform APIs. 

**Test type**: `androidUnitTest` with `createComposeRule()`.  
**Effort**: LOW — 2–3 tests, ~1h.

### 3b. FlashcardsScreen.kt (170 lines, 0%)

**Assessment**: Testable with a fake `BlockStateManager`. `FlashcardsScreen` takes only `blockStateManager: BlockStateManager` — needs a stub that returns `StateFlow<Map<PageUuid, List<Block>>>`. The `FlashcardScheduler.isDue()` call is pure. Empty-state branch (`dueCards.isEmpty()`) is straightforward.

**Test type**: `androidUnitTest` with `createComposeRule()`.  
**Effort**: MEDIUM — need a minimal `BlockStateManager` fake (4–5h).

**Risk**: `blockStateManager.updateBlockProperties()` is a `suspend fun` — tests involving card-flip must launch a coroutine. `composeTestRule.runOnIdle` handles this.

### 3c. ScreenRouter.kt (322 lines, 0%)

**Assessment**: The entire composable is `ScreenRouter(screen, repos, blockStateManager, ...)` — 12+ parameters, all heavyweight. The `when(currentScreen)` dispatch block dispatches to named composables which themselves require `RepositorySet`, `StelekitViewModel`, etc. 

**Key insight**: Two early-return branches are directly testable:
1. `if (appState.fatalError != null)` → renders `FatalErrorScreen` — test with a minimal `AppState` stub
2. `if (appState.isLoading)` → renders `LoadingOverlay` — test similarly

The full route dispatch (`when (currentScreen)`) requires full `RepositorySet` mocking — only worth testing via a few representative routes with fake repositories.

**Better approach**: Extract route-classification pure functions:
- `ScreenRouter.kt` lines 111–127 (guard branches) → testable with `AppState` data class
- No new abstractions needed — just add tests for the guard conditions

**Test type**: `androidUnitTest` for guard-branch composable tests; `commonTest` for any extracted pure logic.  
**Effort**: MEDIUM (6–8h for guard tests + representative route tests).

### 3d. GraphDialogLayer.kt (350 lines, 0%)

**Assessment**: `GraphDialogLayer` is a fan-out composable with 12 child composable calls, each gated on an `AppState` field. The top-level composable takes `appState`, `searchViewModel`, `viewModel`, `notificationManager`, `fileSystem`, `frameMetric`, and `deps: GraphDialogLayerDeps`. 

**Key insight**: Each child host (`CommandPaletteHost`, `SearchDialogHost`, etc.) is a private `@Composable`. They delegate entirely to named composables elsewhere that are independently testable. The real value is testing the gating conditions.

**Better approach**: Test the gating via the individual host composables, not by extracting a pure function:
- `CommandPaletteHost` renders only when `appState.commandPaletteVisible == true`
- `WikiSubdirFixHost` renders only when `appState.wikiSubdirFixVisible == true`

**Test type**: `androidUnitTest`.  
**Effort**: MEDIUM (4–6h for 6–8 gating tests).

### 3e. GraphContentLeftSidebar.kt (170 lines, 0%)

**Assessment**: `GraphContentLeftSidebar` wraps `LeftSidebar` with callbacks. The sidebar is visible when `appState.sidebarExpanded == true`. Full test requires `GraphContentDeps` (has `fileSystem`, `graphManager`) and `GraphContentLeftSidebarInputs` (large parameter object).

**Key insight**: `LeftSidebar` itself likely has some coverage from screenshot tests. The wiring composable is thin. Tests should focus on:
1. Sidebar renders its graph-name when expanded
2. A page click triggers `viewModel.navigateTo()`
3. "New graph" button triggers `inputs.onStartNewGraphFlow`

**Test type**: `androidUnitTest` — need minimal `StelekitViewModel` fake.  
**Effort**: HIGH (8–10h — requires stubbing `GraphContentDeps` and `StelekitViewModel`).

**Risk**: `StelekitViewModel` is not an interface — cannot mock with Mockito. Need either a fake subclass or refactor to extract an interface. The existing tests (`NewGraphFlowTest.kt`) avoid `StelekitViewModel` entirely. This is the **primary blocker** for testing sidebar wiring.

**Recommendation**: Defer GraphContentLeftSidebar tests to the `stelekit-viewmodel-decomposition` project (which is expected to introduce `StelekitViewModelPort` or similar interface). Instead, prioritize tests on the pure-callback composables.

### 3f. GraphContentVaultActions.kt (148 lines, 0%)

**Assessment**: `GraphContentVaultActions.kt` contains:
- `GraphContentVaultEnv` data class (trivial)
- `GraphContentVaultActions` data class (trivial — 6 lambda fields)
- `buildVaultActions()` — a function that returns a `GraphContentVaultActions` instance with all lambdas wired up; pure enough to test but requires `VaultManager`, `GraphContentVaultSetup`, etc.
- Two `LaunchedEffect` composables gating vault lifecycle

**Key insight**: The lambda factories test what matters — that `onVaultUnlock` calls `VaultManager.unlock(...)`, etc. But this requires heavyweight fakes.

**Recommendation**: Skip this file in the current pass — its value is low (vault unlock path) and the setup cost is high. Revisit after `stelekit-viewmodel-decomposition` introduces cleaner dependency injection.

### 3g. GraphContentActiveShell.kt (430 lines, 0%)

**Assessment**: Most complex file — 430 lines, deeply nested composable. Takes `GraphContentActiveShellInputs` (17 fields), `GraphContentDeps` (deep), `AppState`. Nearly all logic involves `StelekitViewModel` directly.

**Recommendation**: Skip for now — same blocker as GraphContentLeftSidebar. The `stelekit-viewmodel-decomposition` project must come first.

### 3h. StelekitViewModel branches (41% → 50% branch target)

**Assessment**: The untested branches are concentrated in:
- Navigation methods: `navigateTo()` history-full guard, `goBack()`/`goForward()` boundary conditions
- UI toggles: `toggleSidebar`, `setSearchDialogVisible`, etc.

The existing `StelekitViewModelCrashReproductionTest` shows the pattern: construct a `StelekitViewModel` with `InMemoryRepositoryFactory` in `businessTest`. These are pure state-machine tests — no Compose needed.

**Test type**: `businessTest`.  
**Effort**: LOW-MEDIUM (4–6h for navigation boundary tests + toggle tests).

---

## 4. Dependency Analysis: StelekitViewModel Mockability

`StelekitViewModel` is a concrete class with no interface. Existing tests that involve composables (`NewGraphFlowTest`, `AddGraphAppOwnedTest`) avoid `StelekitViewModel` entirely by testing composables that don't call into it. This is the right approach for this pass.

For composables that **require** `StelekitViewModel` callbacks, the options are:
1. **Extract an interface** (tracked in `stelekit-viewmodel-decomposition`) — proper fix, deferred
2. **Provide a fake subclass** — fragile, not recommended
3. **Test at a higher integration level** (full `StelekitApp` with InMemory backend) — expensive

**Decision for this pass**: Test only composables that don't require `StelekitViewModel` directly. That covers `WikiSubdirFixDialog`, `FlashcardsScreen` (uses `BlockStateManager` not VM), and `ScreenRouter` guard branches.

---

## 5. Coverage Impact Estimate

| Test added | Files covered | Estimated line gain |
|-----------|---------------|---------------------|
| `WikiSubdirFixDialogTest` (4 tests) | `WikiSubdirFixDialog.kt` | +200 lines |
| `FlashcardsScreenTest` (3 tests) | `FlashcardsScreen.kt` | +120 lines |
| `ScreenRouterGuardTest` (3 tests) | `ScreenRouter.kt` guard branches | +40 lines |
| `StelekitViewModelNavigationTest` (5 tests) | `StelekitViewModel.kt` | +60 lines branch |
| `StelekitViewModelUiToggleTest` (5 tests) | `StelekitViewModel.kt` | +30 lines branch |
| `GraphDialogLayerGatingTest` (6 tests) | `GraphDialogLayer.kt` | +80 lines |

**Total estimated gain**: ~530 lines covered (of ~6000). That lifts `ui/` from 1257 to ~1787 = **29.8%** line coverage. Branch coverage: from 460 to ~560 = **8.7%** (GraphDialogLayer's ~100 branch conditions dominate the denominator but are hard to exercise without full ViewModel).

**Revised floor proposal**: 28% line / 8% branch (conservative — achievable without StelekitViewModel interface).

---

## 6. Prior Art in This Repo

- `NewGraphFlowTest.kt` — canonical Robolectric composable test pattern
- `AddGraphAppOwnedTest.kt` — canonical dialog callback test pattern
- `BranchRepairUiTest.kt` — canonical multi-step UI flow test
- `StelekitViewModelCrashReproductionTest` — canonical businessTest ViewModel test

No external library changes needed — all required test infrastructure is already on the classpath.
