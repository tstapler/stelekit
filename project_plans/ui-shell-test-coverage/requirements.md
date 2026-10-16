# UI Shell Test Coverage — Requirements

**Item ID**: f344219b-e9b6-4289-a729-06f33c9eee8f  
**Project name**: `ui-shell-test-coverage`  
**Measured baseline**: branch `stelekit-merges` @ e337d58c1e, `:kmp:jvmTest` + `:kmp:jacocoTestReport`

---

## Problem Statement

The `ui/` package has 20.9% line coverage (1257/6005) and 7.2% branch coverage (460/6420) — the lowest of any package that contains business-meaningful logic. Eight files sit at 0%:

| File | Lines | Notes |
|------|-------|-------|
| `GraphContentActiveShell.kt` | 430 | Composable + `GraphContentActiveShellInputs` wiring |
| `GraphDialogLayer.kt` | 350 | Dialog-selection fan-out |
| `ScreenRouter.kt` | 322 | Route dispatch + crash-recovery dialog |
| `WikiSubdirFixDialog.kt` | 254 | Three-way repair decision dialog |
| `GraphContentNewGraphFlow.kt` | 195 | New-graph wizard state controller |
| `FlashcardsScreen.kt` | 170 | Flashcard review screen |
| `GraphContentLeftSidebar.kt` | 170 | Sidebar composable |
| `GraphContentVaultActions.kt` | 148 | Vault lifecycle callbacks |

`App.kt` is 433 lines at 1%, `StelekitViewModel.kt` is 1222 lines at 59% line / 41% branch.

The existing pattern in this codebase (established by `NewGraphFlowTest.kt`, `AddGraphAppOwnedTest.kt`) is:
- Extract pure routing/decision functions to testable Kotlin (tested in `commonTest` or `businessTest`)
- Test composable wiring in `androidUnitTest` with Robolectric + `createComposeRule()`
- Reserve `jvmTest` for screenshot (Roborazzi) and desktop-platform-specific code

The incremental coverage gate (PR #402) makes this actionable: new PRs will fail if they lower coverage.

---

## Constraints (from CLAUDE.md)

1. Robolectric (`androidUnitTest`) is the right home for Compose-behavior tests that don't need pixel rendering.
2. `commonTest`/`businessTest` for pure functions that don't touch platform APIs.
3. `jvmTest` only for screenshot tests and desktop-specific code — do not add UI logic tests there.
4. No fix without root cause — every test must document the behaviour it guards.

---

## Requirements

### R1 — Extract pure decision functions from ScreenRouter.kt

`ScreenRouter.kt` contains route-dispatch logic mixed into the composable. Extract the routing decision (which `Screen` variant maps to which composable) as a pure function testable without Compose:

```kotlin
// commonMain (or jvmMain if no platform-free home):
fun screenTitle(screen: Screen): String
fun isFullScreenRoute(screen: Screen): Boolean
fun requiresActiveGraph(screen: Screen): Boolean
```

Test: `commonTest` — no dependencies, no Compose, no Robolectric.

**Acceptance**: `ScreenRouterLogicTest` (or equivalent) in `commonTest` covers every `Screen` subclass with at least one assertion about the extracted function(s). 

---

### R2 — Robolectric tests for WikiSubdirFixDialog

`WikiSubdirFixDialog` is a pure composable — three-way decision dialog with no ViewModel. It already follows the `onDismiss`/`onMove`/`onMerge`/`onLeave` callback pattern, which is directly testable with `createComposeRule`.

Test: `androidUnitTest` — verify that clicking each button fires the correct callback and that the dialog renders its three options.

**Acceptance**: `WikiSubdirFixDialogTest` covers at least: (a) Move button fires `onMove`, (b) Merge button fires `onMerge`, (c) Leave button fires `onLeave`, (d) dialog displays both the configured and found-label strings.

---

### R3 — Robolectric tests for FlashcardsScreen

`FlashcardsScreen.kt` is a standalone screen with no 0%-coverage peer. Add tests covering:
- "no cards due" empty state is shown when the deck is empty
- card content renders when cards are present
- Flip / Mark-known / Skip interactions update displayed state

Test: `androidUnitTest`.

**Acceptance**: `FlashcardsScreenTest` covers the three scenarios above, exercising the `FlashcardsViewModel` via a fake/stub.

---

### R4 — Robolectric tests for GraphContentNewGraphFlow decision logic

`GraphContentNewGraphFlow.kt` contains `addGraphFlowMode()` and `newGraphPathFor()` — pure functions already tested in `NewGraphFlowTest.kt`. The remaining 0% is the dialog composables themselves. Add tests for:
- `GraphContentNewGraphDialogs` renders the right dialog variant per `AddGraphFlowMode`
- Location picker dialog transitions

Test: `androidUnitTest` for composable tests; `commonTest` for any newly extracted pure logic.

**Acceptance**: coverage on `GraphContentNewGraphFlow.kt` rises above 30% line, measured by a `:kmp:testDebugUnitTest` + JaCoCo run.

---

### R5 — Robolectric tests for GraphContentLeftSidebar

`GraphContentLeftSidebar.kt` renders the sidebar navigation list. Test:
- Sidebar is not displayed when `appState.isSidebarOpen == false`
- Page list items render when open
- Tapping a page item fires the correct navigation callback

Test: `androidUnitTest`.

**Acceptance**: `GraphContentLeftSidebarTest` covers the show/hide toggle and at least one navigation callback.

---

### R6 — StelekitViewModel branch coverage: navigation + UI toggles (target ≥ 50% branch)

`StelekitViewModel` is 59% line / 41% branch. The untested branches are concentrated in navigation guard conditions and state-toggle methods. Without refactoring the ViewModel (that is tracked separately in `project_plans/stelekit-viewmodel-decomposition/`), add tests for:
- `navigateTo(Screen)` with history-full guard (`navigationHistory.size >= MAX_HISTORY`)
- `goBack` / `goForward` at boundary conditions (empty history, at edges)
- `toggleSidebar`, `setSearchDialogVisible`, `setCommandPaletteVisible` state changes

Test: `businessTest` — `StelekitViewModel` already has a test infrastructure there (`StelekitViewModelCrashReproductionTest`, etc.).

**Acceptance**: `StelekitViewModelNavigationTest` covers the three navigation boundary cases; `StelekitViewModelUiToggleTest` covers the three toggle methods.

---

### R7 — GraphDialogLayer: extract dialog-selection predicate

`GraphDialogLayer.kt` has a large `when`/`if` block deciding which dialog is shown based on `AppState`. Extract the "which dialog is visible" predicate as a pure `AppState → ActiveDialog` function:

```kotlin
// commonMain
sealed class ActiveDialog { object None; ... }
fun resolveActiveDialog(appState: AppState): ActiveDialog
```

Test: `commonTest` — no Compose, no platform.

**Acceptance**: `GraphDialogLayerLogicTest` in `commonTest` covers at least 8 distinct `AppState` conditions. Coverage on `GraphDialogLayer.kt` rises above 20% line.

---

### R8 — Coverage gate integration: new floor enforced in CI

Once R1–R7 are implemented, update the incremental coverage gate (introduced in PR #402) to record the new floor:
- UI package line coverage ≥ 35% (up from ~21%)
- UI package branch coverage ≥ 20% (up from ~7%)
- `StelekitViewModel` branch coverage ≥ 50% (up from 41%)

**Acceptance**: `./gradlew ciCheck` passes with the new floors; lowering any covered line to uncovered in a subsequent PR triggers a CI failure.

---

## Out of Scope

- Full 80%+ coverage of every composable in `ui/` — screenshot tests (Roborazzi) and full integration tests are tracked separately.
- Refactoring `StelekitViewModel` into sub-ViewModels — tracked in `stelekit-viewmodel-decomposition`.
- `GraphContentActiveShell.kt` composable testing beyond what pure-function extraction enables — that file's render logic requires full `StelekitViewModel` state which is large to stub.
- iOS/WASM platform targets — JVM/Android coverage is the measurement basis for this item.

---

## Success Criteria

1. `WikiSubdirFixDialogTest`, `FlashcardsScreenTest`, `GraphContentLeftSidebarTest` exist in `androidUnitTest` and pass with `./gradlew testDebugUnitTest`.
2. `ScreenRouterLogicTest` (or equivalent name) exists in `commonTest` and covers every `Screen` subclass.
3. `GraphDialogLayerLogicTest` exists in `commonTest` and covers ≥ 8 dialog-selection conditions.
4. `StelekitViewModelNavigationTest` and `StelekitViewModelUiToggleTest` exist in `businessTest` and pass.
5. UI package line coverage ≥ 35% and branch coverage ≥ 20% as measured by `:kmp:jacocoTestReport`.
6. `StelekitViewModel` branch coverage ≥ 50%.
7. All new tests are headless — they pass without an X11 display and without `xvfb-run`.
8. CI incremental coverage gate updated to reflect the new floors.

## AC8 revision (owner decision)

Original AC8 (per-package floors: `ui/` line ≥ 28%, branch ≥ 8%, `StelekitViewModel` branch ≥ 48%) cannot be met: the CI gate has no per-package thresholds and no merged baseline exists to set one safely.

Revised AC8: the existing incremental gate (`:kmp:diffCoverage`, `diffCoverage.threshold` = 80% line coverage on changed executable lines, kmp/build.gradle.kts) passes in CI for this change. Per-package floors are out of scope until a merged JVM+Android baseline is measured.

## AC5 revision (owner decision, 2026-10-14)

Original AC5 required covering a history-size cap boundary. `StelekitViewModel.navigateTo` has no cap (`StelekitViewModel.kt:1086-1106`), and adding one is a product change outside this item.

Revised AC5: `StelekitViewModelNavigationTest` in `businessTest` covers `goBack()` at index 0, `goForward()` at the last item, and pins that history is not capped. Recorded in `validation.md` (ba1362a5).
