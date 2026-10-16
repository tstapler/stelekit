# Build-vs-Buy: UI Screen Test Coverage Infrastructure

Research date: 2026-10-13  
Target: raise `ui.screens` line coverage from 37.5 % → ≥ 55 %  
Approach confirmed in requirements: Robolectric Compose tests (androidUnitTest) for screens,
pure state-reducer tests in commonTest/businessTest for ViewModels.

---

## 1. Robolectric — already in use, no upgrade needed

**Version:** `org.robolectric:robolectric:4.16` (`kmp/build.gradle.kts` line 365).  
**Default SDK:** `sdk=34` (`kmp/src/androidUnitTest/resources/robolectric.properties`).  
**compileSdk:** 36, **minSdk:** 26 (build.gradle.kts lines 1482, 1486).

4.16 is the current stable release; it supports SDK 34 and Compose UI testing via
`androidx.compose.ui:ui-test-junit4:1.10.6` (already on the classpath, line 378). No upgrade
is needed. Tests run headless (no X11 display required), which is the key reason the
requirements call for Robolectric over `jvmTest`.

Tests use `@RunWith(RobolectricTestRunner::class)` plus `@Config(qualifiers = "w411dp-h891dp-xxhdpi")`
at the class level, and per-test `@Config` overrides for alternate screen sizes. This is the
established pattern in `CopyPagesScreenTest.kt` and every other UI test in androidUnitTest.

---

## 2. ComposeTestRule helpers — no shared helper exists; use `createComposeRule()` directly

No `TestUtils.kt`, no base test class, no shared extension functions for `ComposeContentTestRule`
exist in `kmp/src/androidUnitTest`. Every test file calls `createComposeRule()` directly via a
`@get:Rule` property, then calls `rule.setContent { MaterialTheme { ScreenContent(state, actions) } }`
inline. This pattern is lightweight and consistent across all existing Compose tests
(`CopyPagesScreenTest`, `CopyFlowHostTest`, `ConflictReviewTest`, `ShareInboxPanelTest`, etc.).

**Decision: do not build a shared helper.** The direct `createComposeRule()` pattern is one
line and imposes no coupling between test files. A shared base class would add indirection
without meaningful reduction in boilerplate given how lightweight the setup already is.

---

## 3. Fake / mock factories — partial reuse available; no shared factory for target screens

### Already reusable (production code, in `commonMain`)

- `InMemoryPageRepository` — full in-memory `PageRepository` implementation. Used in at least 7
  test files across businessTest and androidUnitTest (e.g. `QrDecodeViewModelBackgroundingTest`,
  `ExportServiceJournalRangeTest`). Reuse for any ViewModel test that needs a PageRepository.
- `InMemoryBlockRepository` — same pattern; same reuse.

### Available in test sources (domain/git-layer fakes)

- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/git/testsupport/StubFileSystem.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/testsupport/GitTestFakes.kt`
  (covers `FakeSafFileSystem`, `FakeCredentialAccess`)
- `FakeGraphMoveQuiesceStrategy`, `FakeRelocationFileSystem` (businessTest)

### Not available — must be built per test or shared

No fakes exist for:
- `GraphManager` — tests that need it should use an in-memory `RepositorySet` or stub the
  specific interface the ViewModel calls, not `GraphManager` itself.
- `CopyFlowGateway` — UI tests pass inline `CopyPagesActions` callbacks (lambdas) directly;
  no gateway mock is needed because existing tests invoke `CopyPagesContent` (the stateless
  composable) not the full screen with a live ViewModel.
- `LibraryStatsScreen`, `AllPagesScreen`, `ImportScreen`, `GitSetupStep3Auth` — no existing
  fakes for their ViewModel dependencies.

### Pattern for target screens

All existing UI tests for copy/conflict screens follow the **stateless-composable approach**:
they construct a state DTO inline (a `state()` builder function in the test class), pass it
directly to the `*Content` composable, and pass no-op or capturing lambdas for actions. This
avoids needing a fake ViewModel or GraphManager entirely. All uncovered screens in scope
(`CopyPagesScreen`, `ConflictResolutionScreen`, `AllPagesScreen`, etc.) expose a `*Content`
composable that accepts a state + actions pair — the same pattern applies.

**Decision: build inline state builders per test file, reuse `InMemoryPageRepository` /
`InMemoryBlockRepository` for ViewModel-layer tests.** Do not build a cross-file shared factory
for screen states; the DTO constructors are already concise enough that a per-test `state()`
helper function (a local private function, as in `CopyPagesScreenTest`) is cleaner and avoids
coupling test files to each other.

---

## 4. Turbine / coroutine-test

**`kotlinx.coroutines.test:1.10.2`** — on the classpath in every test source set
(build.gradle.kts lines 154, 368, 394, 424). `runTest { }`, `TestCoroutineScheduler`,
`StandardTestDispatcher`, and `advanceUntilIdle()` are all available.

**Turbine** — NOT on the classpath (searched build.gradle.kts; no match). `StateFlow` testing
for ViewModel-layer tests should use `runTest { stateFlow.test { … } }` via the
`kotlinx.coroutines.test` `turbine`-style extensions that ship in `kotlinx-coroutines-test`
itself (e.g. `collectIn` + `cancel`), or collect into a list with `toList(take(N))`. Adding
Turbine is optional; `kotlinx-coroutines-test` alone is sufficient for the planned tests.

---

## 5. Screenshot tests (Roborazzi) — not recommended

Roborazzi 1.59.0 is present and used in `jvmTest`. Screenshot tests require a real X11
display (they render pixels); they cannot run headless on CI without Xvfb, and the
`jvm-display-check.sh` wrapper in the repo exists specifically because Wayland-only sessions
have no X11 display. They also only catch visual regressions, not logic coverage. They do
not count toward Robolectric-based line coverage metrics.

**Verdict: not recommended for this coverage initiative.** Keep Roborazzi for its current
role (pixel regression on desktop). All new tests go to `androidUnitTest` via Robolectric.

---

## 6. Build-or-reuse decision summary

| Concern | Decision | Rationale |
|---|---|---|
| `ComposeTestRule` setup | Reuse `createComposeRule()` directly | Zero boilerplate; no shared helper needed |
| Screen state construction | Build local `state()` helper per test file | DTOs are small; coupling between files costs more than it saves |
| `PageRepository` / `BlockRepository` fakes | Reuse `InMemoryPageRepository` / `InMemoryBlockRepository` | Already shared, battle-tested |
| `GraphManager` / gateway fakes | Avoid entirely — test `*Content` composables | Stateless-composable pattern makes VM/manager fakes unnecessary for UI tests |
| ViewModel state-reducer tests | `runTest` from `kotlinx-coroutines-test` | Turbine not needed; existing coroutine-test API is sufficient |
| Screenshot tests | Skip for this initiative | Display-dependent; not Robolectric; no coverage credit |

The most important finding: **every uncovered screen has a `*Content` stateless composable**
that takes a plain Kotlin state DTO and action lambdas. New tests can use the exact same
self-contained pattern as `CopyPagesScreenTest` without adding any new infrastructure.
