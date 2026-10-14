# UI Screen Coverage — Pitfalls & Risks

Research date: 2026-10-13  
Source files inspected: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/AllAndroidUnitTests.kt`,
`kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/CopyFlowHostTest.kt`,
`kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/CopyPagesScreenTest.kt`,
`kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/NewGraphFlowTest.kt`,
`kmp/src/androidUnitTest/resources/robolectric.properties`,
`kmp/build.gradle.kts` (JaCoCo and testOptions sections),
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/{AllPagesScreen,LibraryStatsScreen,ImportScreen}.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/copy/{ConflictReviewScreen,CopyFlowHost}.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/ConflictResolutionScreen.kt`

---

## 1. Robolectric + Compose Test Pitfalls

### 1.1 SDK version — use only SDK 34

`kmp/src/androidUnitTest/resources/robolectric.properties` pins `sdk=34` project-wide.
All new test classes inherit this; do not add `@Config(sdk = [28])` or similar without a
documented reason — mismatches cause Robolectric bootstrap failures that look like
`java.lang.RuntimeException: SDK 28 not installed`.

Robolectric 4.16 (the version in `kmp/build.gradle.kts`) fully supports SDK 34, so there
is no compatibility gap to work around.

### 1.2 Bazel gap — Compose tests run under Gradle only

`AllAndroidUnitTests.kt` explicitly excludes `NewGraphFlowTest` (and several Mermaid tests)
from the Bazel `android_local_test` suite with the comment:

> `ComposeContentTestRule.setContent` hits a `NoSuchMethodError` in Bazel's resolved
> `androidx.compose.ui:ui-test-junit4` jar.

Every new Compose test (any class that calls `createComposeRule().setContent(...)`) will
hit the same gap. Do not add them to `AllAndroidUnitTests`; they run correctly under
`./gradlew testDebugUnitTest` and count toward JaCoCo coverage there. Add a comment
parallel to the existing ones when you deliberately exclude a new test class.

### 1.3 LazyColumn — off-screen items have no semantics

`LazyColumn` only composes items within its visible viewport. An item scrolled out of
view does not exist in the semantic tree; `onNodeWithText(...)` throws
`NoMatchingNodeException` (not a soft assertion failure). The fix:

```kotlin
rule.onNodeWithText("Retry").performScrollTo().performClick()  // already in CopyPagesScreenTest
```

`AllPagesScreen` uses `LazyColumn` with `combinedClickable` rows; tests that need to
interact with rows beyond the initial viewport must scroll first. Use a small default list
in tests unless viewport scrolling is specifically what is under test. Add
`@Config(qualifiers = "w411dp-h891dp-xxhdpi")` (already used in `CopyPagesScreenTest`) to
ensure a consistent viewport size.

### 1.4 AlertDialog / popup rendering

Compose `AlertDialog` is rendered in a secondary popup window. Robolectric's
`createComposeRule()` captures popup windows in the same semantic tree by default, so
`onNodeWithText("Delete")` works without `useUnmergedTree = true`. However, if you use
`onAllNodesWithText(...)` and a text string appears in both the dialog and the background
screen, use `onFirst()` or assert exact counts rather than assuming uniqueness.

`AllPagesScreen` has an `AlertDialog` for bulk-delete confirmation. Triggering it requires
first entering selection mode (long-press) via `combinedClickable`, which
`performTouchInput { longClick() }` can do. The dialog disappears on confirm; assert
`assertDoesNotExist()` after clicking "Delete" to verify the dialog closed.

### 1.5 `@OptIn(ExperimentalMaterial3Api::class)` and `ExperimentalFoundationApi`

Both `AllPagesScreen` (`@ExperimentalFoundationApi` for `combinedClickable`) and
`ConflictResolutionScreen` (`@ExperimentalMaterial3Api` for `TopAppBar`) use experimental
APIs. Test classes that invoke these composables do not need the same `@OptIn` annotations
because the test only calls the top-level composable function, not the experimental inner
APIs directly. No action needed.

---

## 2. Coroutine-Backed ViewModels in Compose Tests

### 2.1 `waitUntil` is the correct async primitive

The established pattern (from `CopyPagesScreenTest`) is:

```kotlin
rule.waitUntil(5_000) { vm.state.value.rows.size == 2 }
```

Do not use `runTest`, `advanceUntilIdle`, or `TestCoroutineScheduler` here — the test
runs on the UI thread and Compose's test clock is separate from kotlinx-coroutines test
schedulers. `waitUntil` polls the condition on the test main clock and is the safe,
idiomatic choice.

### 2.2 Inject `Dispatchers.Unconfined` for test ViewModels

`CopyPagesScreenTest` passes `dispatcher = Dispatchers.Unconfined` to `CopyPagesViewModel`.
Do the same for any new ViewModel that accepts a dispatcher parameter. `Dispatchers.Unconfined`
executes coroutines eagerly on the calling thread, which means state updates produced by
`init { load() }` blocks are visible before the first `setContent` call, avoiding the need
for a separate `waitUntil` just to reach the initial state.

### 2.3 `ImportScreen` and `ConflictResolutionScreen` use `rememberCoroutineScope()`

Both screens own a `rememberCoroutineScope()` inside the composable body (not the
ViewModel). This scope is backed by the test composition's coroutine context, which is
driven by the test's main clock. Side effects launched from button clicks (`scope.launch`)
run asynchronously. You need `rule.waitUntil(...)` or `rule.mainClock.advanceTimeBy(...)`
after triggering such clicks if the test asserts on state that the launched coroutine
produces. Do not advance the clock blindly — prefer asserting on visible UI changes via
`waitUntil`, which is more robust to timing changes.

### 2.4 `LaunchedEffect` in `LibraryStatsScreen` triggers load on first frame

`LibraryStatsScreen` calls `viewModel.load()` inside `LaunchedEffect(Unit)` when
`state == LibraryStatsState.Idle`. In tests using a real ViewModel, the `LaunchedEffect`
fires automatically when the composition is first committed. If the VM's `load()` is
immediate/synchronous under test, the screen will show the loaded state by the time the
first assertion runs. If it is async, use `waitUntil` to await the `Loaded` state before
asserting content.

---

## 3. KMP Compose Screens in Robolectric

### 3.1 `expect`/`actual` platform callouts — verify Android actuals exist

Several screens import platform-bridged composables:
- `NavigationTracingEffect` — called in every screen. If the Android actual is a no-op
  this is harmless; if it calls Android telemetry APIs that Robolectric doesn't shadow, the
  test crashes. Verify this is a no-op under test before relying on it silently passing.
- `PlatformBackHandler` — used in `ConflictReviewScreen`. The Android actual wraps
  `BackHandler`, which Robolectric supports, but the no-op from test won't block navigation.

### 3.2 `ImportViaCameraMenuItem` may access Android-specific APIs

`ImportScreen` imports `ImportViaCameraMenuItem`. Pass `onImportViaCamera = null` in tests
to keep the button out of the composition entirely, avoiding any Android camera permission
API calls that Robolectric may not shadow.

---

## 4. CopyFlowHost — White Box vs. Black Box Risks

`CopyFlowHostTest` already tests `CopyFlowContent` (the stateless rendering function) as a
black box: it constructs explicit `CopyFlowState` values and a stub `CopyFlowActions`,
then asserts which buttons appear and which callbacks fire. This is the right approach —
it covers ~1% of `CopyFlowHost.kt`'s lines because `CopyFlowHost` itself (the stateful
orchestrator that wires the ViewModel to `CopyFlowContent`) is not under test.

Risks of testing `CopyFlowHost` as a white box (with a real `CopyFlowViewModel`):
- Requires constructing a full `CopyFlowViewModel` with a real or stub `CopyFlowGateway`,
  `GraphManager` reference, and coroutine scopes — high setup cost.
- The flow has at least 12 `CopyStage` variants, each with different content; exercising
  all transitions through the real VM is combinatorially expensive and couples tests to
  implementation details.
- Test failures would be harder to localise (VM logic failure vs. rendering failure).

**Recommended approach**: keep black-box tests on `CopyFlowContent`, cover each
`CopyStage` variant that is currently untested (Idle, DryRunning, DryRunFailed, Stopping,
Partial, PartialUndo, UndoRunning, UndoFailed). Each variant is a 5-line test:
construct the `CopyFlowState`, call `show()`, assert one label and one button action.

---

## 5. JaCoCo Coverage Gaps

### 5.1 Two separate JaCoCo reports — Android and JVM

`build.gradle.kts` registers two tasks:
- `jacocoTestReport` — reads `jacoco/jvmTest.exec`; covers JVM-compiled `commonMain` classes
- `jacocoAndroidTestReport` — reads `outputs/unit_test_code_coverage/**/*.exec` from
  `testDebugUnitTest`; covers Android-compiled `commonMain` classes from
  `tmp/kotlin-classes/debug`

The diff-coverage gate unions both XML reports. New `androidUnitTest` tests count toward
`jacocoAndroidTestReport`; they do NOT feed into `jacocoTestReport`. If the coverage
target (≥55% line) is measured from the Android report, new Robolectric tests count.
If it is measured from the JVM report, they do not. Confirm which report is used to
baseline the 37.5% current coverage before writing the implementation plan.

### 5.2 JaCoCo does not count Kotlin coroutine suspension points as separate branches

Generated `$stateMachine` classes and `invokeSuspend` methods from suspend functions
contain unreachable branches in the bytecode (the "resume from suspension" transitions
that are never re-entered in unit tests). JaCoCo counts them as missed branches,
lowering branch coverage on any suspend function. Line coverage is unaffected. This
affects `ConflictResolutionScreen`'s `onClick = { scope.launch { ... } }` lambda and any
`suspend fun` in ViewModel `load()` calls.

### 5.3 Kotlin inline function lambda bodies may count as one line

When a lambda is passed to an inline function (`items { }` in `LazyColumn`, `let { }`,
`run { }`, etc.), the compiler inlines the lambda body at the call site. JaCoCo may
attribute all inlined lines to the single call-site line, depending on the Kotlin version
and line number table. This means a multi-statement lambda inside `items { }` in
`AllPagesScreen` or `LibraryStatsScreen` may show as a single covered line regardless of
how many internal branches execute. Practical impact: JaCoCo line coverage will
**overcount** these code paths (they look covered even if only one branch ran), which
is safe for the goal of raising a line coverage floor.

### 5.4 `@Composable` skipping — not a JaCoCo issue, but a runtime observation

JaCoCo covers `@Composable` function bodies like any other function. However, if
Compose's runtime skips a recomposition (because all inputs are equal), the composable
body does not execute and its lines are not covered. Tests must ensure the composable
actually composes (not skips) the targeted branches, which is why driving tests via
distinct state objects rather than relying on Compose's internal diffing is important.

---

## 6. CLAUDE.md Constraints (Summary of Relevant Warnings)

- **No X11 / display required**: `androidUnitTest` (Robolectric) runs headless. The
  `scripts/jvm-display-check.sh` wrapper and Xvfb requirement documented in CLAUDE.md
  apply only to `jvmTest` / `./gradlew ciCheck`. New Robolectric Compose tests do not need
  any display.
- **Correct test home**: "A Compose-behavior test … belongs in `androidUnitTest`
  (Robolectric) … not `jvmTest`." All new screen tests go in
  `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/`.
- **Bazel exclusion required**: Any test class that calls `createComposeRule()` must NOT
  be added to `AllAndroidUnitTests.kt`'s `@Suite.SuiteClasses` list (the Bazel target),
  matching the existing commented exclusions there.
- **`rememberCoroutineScope` must not escape composition**: `ConflictResolutionScreen`
  uses `rememberCoroutineScope()` inside the composable. Tests that interact with its
  `onClick` confirm button will see the async `onResolve` call; provide a stub that
  returns `Either.Right(Unit)` immediately, not a suspending fake.

---

## 7. `performClick` on Off-Screen Compose Nodes

### 7.1 `NoMatchingNodeException` vs. assertion failure

`onNodeWithText("x").performClick()` throws `NoMatchingNodeException` (not a test
assertion failure) when the node is not in the semantic tree. This is a runtime crash, not
a test failure with a message — the stack trace points at the performClick line, not at
any assertion. Always use `assertExists()` or `performScrollTo()` before `performClick()`
on anything that might be hidden.

### 7.2 `performScrollTo()` requires the parent `ScrollState` to be present

`performScrollTo()` works when the node's ancestor has a `ScrollState` or `LazyListState`.
In `AllPagesScreen`, the row list is a `LazyColumn` — `performScrollTo()` works. In
`ConflictResolutionScreen`, the file list is a `Column` with `verticalScroll` — also works.
In `LibraryStatsScreen`, the content is a `LazyColumn` — works.

One edge case: `ConflictReviewScreen` uses `rememberLazyListState()` plus a
`focusRequester` that requests focus on the first item via `withFrameNanos`. In tests,
`withFrameNanos` requires at least one frame to be committed. The `rule.waitForIdle()`
call after `setContent` ensures frames are drained, so focus will be established before
the first assertion.

### 7.3 `FilterChip` in `ConflictResolutionScreen` is inside `LazyColumn` per-file cards

`ConflictResolutionScreen` renders conflict files in a non-lazy `Column` inside a
`verticalScroll` (line 114+). The "Resolve line by line" button and per-hunk `FilterChip`
widgets are inside that column. These are always composed when the screen renders, so
`performScrollTo()` is still needed for items near the bottom on a small test viewport,
but the items are not conditionally absent from the tree.
