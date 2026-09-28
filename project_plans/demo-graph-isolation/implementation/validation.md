# Validation Plan: demo-graph-isolation

**Feature**: Isolate demo graph from real user graphs using `isDemo` flag and in-memory backend
**Date**: 2026-07-05
**Plan reference**: `project_plans/demo-graph-isolation/implementation/plan.md`

---

## 1. Unit Tests

Each row maps one or more in-scope requirements to a concrete test. All tests use
`defaultBackend = GraphBackend.IN_MEMORY` and the `StubSettings` / `StubFileSystem` helpers
already established in `GraphManagerAddGraphTest.kt` and `GraphManagerInitAutoRestoreTest.kt`.

### 1.1 `DemoGraphPersistenceTest` (new — businessTest)

**File**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/DemoGraphPersistenceTest.kt`

| # | Test name | Assert | Requirement |
|---|-----------|--------|-------------|
| T-1 | `demo graph is not persisted to registry after addDemoGraph` | After `addDemoGraph()`, `platformSettings.getString("graph_registry", "")` does not contain `"__demo__"` and does not contain `"isDemo"` | In-scope R2: `addDemoGraph()` never calls `saveRegistry()` |
| T-2 | `switchGraph for demo does not persist demo entry` | After `addDemoGraph()` + `switchGraph(DEMO_GRAPH_ID)`, the serialized registry JSON has no `"__demo__"` key and `activeGraphId` is null in the persisted JSON | In-scope R2 + success metric 2 |
| T-3 | `addDemoGraph is idempotent` | Calling `addDemoGraph()` twice leaves exactly one entry in `graphRegistry.value.graphs` with `id == DEMO_GRAPH_ID` | In-scope R2 |
| T-4 | `demo graph is stripped on registry load when isDemo flag is set` | Pre-populate `platformSettings["graph_registry"]` with a JSON containing one `GraphInfo(isDemo=true, id="__demo__")` and `activeGraphId="__demo__"`. Construct a fresh `GraphManager`. Assert `graphRegistry.value.graphs.none { it.isDemo }` and `graphRegistry.value.activeGraphId == null` | In-scope R3 + success metric 2 |
| T-5 | `loadRegistry resets onboardingCompleted when only demo entry existed` | Pre-populate with only a demo entry. After `GraphManager` init, assert `platformSettings.getBoolean("onboardingCompleted", true) == false` | In-scope R3 (empty-list reset sub-rule) |
| T-6 | `loadRegistry retains real graphs and strips only demo entries` | Pre-populate with one real `GraphInfo` and one `isDemo=true` entry. Assert only the real entry survives | In-scope R3 |
| T-7 | `renameGraph returns false for demo graph` | `addDemoGraph()`, then `renameGraph(DEMO_GRAPH_ID, "Custom Name")` returns `false`; `graphRegistry.value.graphs.first { it.id == DEMO_GRAPH_ID }.displayName == "Demo Graph"` | In-scope R2 + plan story 2.1.4 |

**Registration**: Add `DemoGraphPersistenceTest::class` to the `@Suite.SuiteClasses` list in
`kmp/src/businessTest/kotlin/dev/stapler/stelekit/AllBusinessTests.kt` (same pattern as other
businessTest classes; required because Kotlin/JVM test discovery in this project uses explicit
suite registration).

---

### 1.2 `GraphInfoSerializationTest` (extend — businessTest)

**File**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphInfoSerializationTest.kt`

| # | Test name | Assert | Requirement |
|---|-----------|--------|-------------|
| T-8 | `GraphInfo without isDemo field deserializes with isDemo=false default` | Old JSON lacking `"isDemo"` → deserialized `GraphInfo.isDemo == false` | In-scope R1 (backward-compatible serialization) |
| T-9 | `GraphInfo with isDemo=false omits field from JSON` | `Json.encodeToString(graphInfo)` where `isDemo=false` → output string does not contain `"isDemo"` | In-scope R1 (`encodeDefaults` not set) |
| T-10 | `GraphInfo with isDemo=true serializes and round-trips` | `Json.encodeToString(graphInfo)` where `isDemo=true` → contains `"isDemo":true`; decoded value is equal to original | In-scope R1 |

---

### 1.3 `DemoFileSystemSyncTest` (path-update only — businessTest)

**File**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/DemoFileSystemSyncTest.kt`

| # | Test name | Assert | Requirement |
|---|-----------|--------|-------------|
| T-11 (existing) | `every demo-graph page appears in generated DemoFileSystem` | Post-move: path in `dir.resolve(...)` updated to `"src/commonMain/.../DemoFileSystem.kt"` | In-scope R4 (generator move) + success metric 5 |

Only the path string constant changes (Task 3.1.1d). The test logic is unchanged.

---

### 1.4 `DemoBannerTest` (new — businessTest or jvmTest)

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/DemoBannerTest.kt`

Use Compose test rule. `DemoBanner` is stateless on construction (banner shown by default, dismissed on click).

| # | Test name | Assert | Requirement / AC |
|---|-----------|--------|------------------|
| T-12 | `DemoBanner is visible when demo active` | `onNodeWithText("Exploring the demo — changes won't be saved").assertIsDisplayed()` | AC-6 |
| T-13 | `DemoBanner dismiss removes it from composition` | Click dismiss icon; `onNodeWithText("Exploring the demo").assertDoesNotExist()` | AC-7 |
| T-14 | `DemoBanner dismiss button contentDescription matches spec` | `onNodeWithContentDescription("Dismiss demo notice").assertExists()` | AC-17 |
| T-15 | `DemoBanner uses tertiaryContainer background` | `StelekitTheme { DemoBanner() }` renders without color assertion failure (visual check in screenshot) | AC-18 (automated portion) |

---

### 1.5 `GraphSwitcherDemoFilterTest` (new — jvmTest)

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/GraphSwitcherDemoFilterTest.kt`

Use Compose test rule with a `LeftSidebar` or `GraphSwitcher` stub.

| # | Test name | Assert | Requirement / AC |
|---|-----------|--------|------------------|
| T-16 | `GraphSwitcher does not show demo graph in dropdown` | Registry contains `[realGraph, demoGraph]`; `LeftSidebar` renders; dropdown does not contain text "Demo Graph" in the list items | AC-8 |
| T-17 | `GraphSwitcher pill contentDescription includes (demo) when demo active` | `onNodeWithContentDescription("Graph: Demo Graph (demo), tap to switch graph").assertExists()` | AC-5 |

---

## 2. Integration Tests

### 2.1 `DemoGraphIntegrationTest` (existing — jvmTest, unchanged)

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/DemoGraphIntegrationTest.kt`

No changes needed. These four tests already verify that the demo graph content loads
end-to-end from the classpath resources:

| Existing test | Covers |
|---|---|
| `all demo graph pages parse without errors` | In-scope R4 (seeded from classpath) + success metric 5 |
| `no demo graph block has empty content` | Content quality gate |
| `all wiki links in demo graph resolve` | Content integrity |
| `demo graph has expected page count` | Content completeness |

After Phase 3, `DemoFileSystem` is in `commonMain`, so any JVM test that instantiates
`DemoFileSystem()` directly can also confirm it is accessible. If a direct instantiation
test is desired, add it to `DemoGraphPersistenceTest` as T-4b:
`DemoFileSystem().listFiles("/demo/pages").isNotEmpty()`.

### 2.2 `GraphManagerInitAutoRestoreTest` (existing — businessTest, unchanged)

**File**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerInitAutoRestoreTest.kt`

The existing `init does NOT auto-restore paranoid-mode active graph` pattern is the
reference for T-4 above. T-4 and T-5 use an identical stub harness — verify they
still pass after the `loadRegistry()` changes in Phase 2.

### 2.3 End-to-end: demo path through App.kt (manual + screenshot)

No automated Compose test currently exercises the full `Onboarding → addDemoGraph →
switchGraph → GraphContent` flow. This gap is documented in section 4.

---

## 3. UX Acceptance Criteria Coverage

| AC | Description | Automatable? | Test / Check | Pass criterion |
|----|-------------|--------------|--------------|----------------|
| AC-1 | "Try Demo Graph" button visible with subtitle | Partial | `jvmTest` Compose rule: `onNodeWithText("Try Demo Graph").assertIsDisplayed()` + `onNodeWithText("Explore sample notes — no files saved").assertIsDisplayed()` | Both nodes exist when `OnboardingStep.GRAPH_SELECTION` is shown |
| AC-2 | After tapping "Try Demo Graph", advance to KEYMAP_INTRO within 2 s | Yes | Compose test: tap the button, assert `onNodeWithText("Keyboard Shortcuts")` (or KEYMAP_INTRO heading) appears before 2 000 ms timeout | Heading visible and no loading overlay |
| AC-3 | After "Finish" on KEYMAP_INTRO, main app opens with sidebar visible | Partial | Compose test through `StelekitApp` with `GraphBackend.IN_MEMORY`; assert sidebar container is displayed | Sidebar not null / `assertIsDisplayed()` |
| AC-4 | Demo active → GraphSwitcher pill shows "Demo Graph" | Yes | T-17 variant: `onNodeWithText("Demo Graph").assertIsDisplayed()` inside the pill | Pill text matches |
| AC-5 | Demo active → pill contentDescription = "Graph: Demo Graph (demo), tap to switch graph" | Yes | T-17: `onNodeWithContentDescription("Graph: Demo Graph (demo), tap to switch graph").assertExists()` | Node found |
| AC-6 | Demo active → banner visible in sidebar | Yes | T-12: `onNodeWithText("Exploring the demo — changes won't be saved").assertIsDisplayed()` | Node found and displayed |
| AC-7 | Tapping [×] removes banner within 300 ms; no reappear during session | Partial (dismiss yes; timing manual) | T-13 (dismiss logic) + manual smoke: dismiss, navigate pages, verify banner absent | Banner node gone after dismiss; does not reappear on page nav |
| AC-8 | Demo excluded from graph switcher dropdown | Yes | T-16: open dropdown with `[realGraph, demoGraph]`; assert "Demo Graph" absent from list items | `onNodeWithText("Demo Graph")` in list = 0 results |
| AC-9 | Selecting real graph exits demo; banner gone; pill shows real name | Partial | Compose test: set up demo, select real graph from switcher, assert banner absent and pill text changed | Banner node gone; pill text == real graph name |
| AC-10 | After demo → switch to real graph, no SQLite files related to demo appear | Yes | businessTest: after `addDemoGraph()` + `switchGraph(DEMO_GRAPH_ID)`, scan `driverFactory.getDatabaseDirectory()` for `*.db` files containing "__demo__" or "/demo"; assert count == 0 | Zero matching `.db` files |
| AC-11 | If demo content fails to load, user sees main app (not onboarding) | Partial | Unit test: inject a `DemoFileSystem` that throws on `listFiles`; assert `viewModel.setOnboardingCompleted(true)` is called before the exception propagates | `onboardingCompleted == true` in settings |
| AC-12 | If demo fails, snackbar appears and auto-dismisses within 5 s | Manual | Instrumented test or manual test: force `DemoFileSystem` failure, observe snackbar | Snackbar visible then gone within 5 s |
| AC-13 | If demo fails, banner still visible | Manual | Same failure scenario as AC-12; verify banner node exists after snackbar dismisses | Banner displayed |
| AC-14 | If demo fails, user can reach graph switcher within 2 taps | Manual | From failure state: tap sidebar open (1), tap switcher pill (2); assert dropdown visible | Dropdown visible in 2 taps |
| AC-15 | "Try Demo Graph" reachable via Tab; activatable via Enter/Space | Manual (keyboard) | Manual test on Desktop JVM: Tab to button; press Enter; verify advance to KEYMAP_INTRO | Step changes |
| AC-16 | Banner dismiss reachable via Tab; activatable via Enter/Space | Manual (keyboard) | Manual test on Desktop JVM: Tab to dismiss [×]; press Space; verify banner gone | Banner gone |
| AC-17 | Screen reader announces banner and dismiss | Partial | T-14: `onNodeWithContentDescription("Dismiss demo notice").assertExists()` (automated); full announcement requires manual TalkBack/VoiceOver test | Semantic node present; announcement text correct manually |
| AC-18 | Banner text on tertiaryContainer meets WCAG AA (≥4.5:1) in both themes | Manual (color) | Compare `MaterialTheme.colorScheme.tertiaryContainer` vs `onTertiaryContainer` for the project theme; record contrast ratios in design review | Ratio ≥ 4.5:1 in both light and dark themes |

### Automation summary

| Category | Count |
|---|---|
| Fully automatable | AC-1, AC-2, AC-4, AC-5, AC-6, AC-8, AC-10 (7 ACs) |
| Partially automatable (core logic automated, edge cases manual) | AC-3, AC-7, AC-9, AC-11, AC-17 (5 ACs) |
| Manual only | AC-12, AC-13, AC-14, AC-15, AC-16, AC-18 (6 ACs) |

Manual ACs (12–16, 18) must be checked during a dedicated QA smoke run before Phase 7
sign-off. They should be recorded in a manual test checklist alongside the PR.

---

## 4. Coverage Gaps

| Gap | Missing coverage | Recommended action |
|-----|------------------|--------------------|
| **Full App.kt demo wire-up** | No automated test exercises `Onboarding onDemoSelected → addDemoGraph → switchGraph → GraphContent` as a unit. AC-2 and AC-3 require a Compose integration test that mounts `StelekitApp` with a fake `GraphManager`. | Add `DemoOnboardingFlowTest.kt` in `jvmTest` using `createComposeRule` + `FakeGraphManager` stub. Covers AC-2, AC-3, AC-9. (Out of scope for Phase 7; mark as follow-up issue.) |
| **WASM `contains("/demo")` removal** | After Phase 6 removes the workaround, there is no automated test that confirms the WASM path still strips demo entries correctly on restart. The new coverage is the `loadRegistry()` unit tests (T-4/T-5), but those run on JVM. | Accept the gap for now — `loadRegistry()` is platform-agnostic Kotlin. Add a WASM smoke test if E2E WASM test infrastructure is added in future. |
| **`effectiveFileSystem` derivation in App.kt** | No test verifies that `DemoFileSystem` is used when `isDemo=true` and `PlatformFileSystem` when `isDemo=false`. | Add a test via `DemoOnboardingFlowTest` above, asserting `graphLoader` loaded ≥ 21 pages (same threshold as `DemoGraphIntegrationTest`). |
| **`saveRegistry()` with 11 call sites** | Only T-2 and T-3 exercise two of the call sites that could accidentally persist the demo. The remaining 9 call sites (e.g., `removeGraph`, `updateGraphInfoDetection`) are not explicitly tested. | The filter in `saveRegistry()` is a single-point guard — testing two call sites provides adequate confidence. Document this as a design invariant in a comment inside `saveRegistry()`. |
| **Demo snackbar on load failure (AC-12/13)** | No automated test injects a failing `DemoFileSystem`. | Add an error-injection unit test in `DemoGraphPersistenceTest` or a dedicated `DemoLoadFailureTest` after AC-11 is resolved. Mark as follow-up. |

---

## 5. Test Execution Order

Tests are grouped by the phase gates they validate. All tests in a gate must be green
before work on the next phase begins.

### Gate A — Before Phase 2 implementation starts (data model only)
Validates Phase 1 changes do not break existing serialization.

| Test | Source set | Command |
|------|-----------|---------|
| `GraphInfoSerializationTest` (T-8, T-9, T-10) | businessTest | `bazel test //kmp:business_tests` |

These tests should be written (or the existing test extended) immediately after Task 1.1.1a
so the serialization contract is locked before the `GraphManager` changes.

---

### Gate B — Before Phase 3 (build system change)
Validates Phase 2 GraphManager changes in isolation with no filesystem involved.

| Test | Source set | Command |
|------|-----------|---------|
| `DemoGraphPersistenceTest` (T-1 through T-7) | businessTest | `bazel test //kmp:business_tests` |
| `GraphManagerInitAutoRestoreTest` (existing, regression) | businessTest | same |
| `GraphManagerAddGraphTest` (existing, regression) | businessTest | same |

---

### Gate C — Before Phase 4 (App.kt wiring)
Validates the `DemoFileSystem` move to `commonMain` and the updated sync test path.

| Test | Source set | Command |
|------|-----------|---------|
| `DemoFileSystemSyncTest` (T-11, path updated) | businessTest | `bazel test //kmp:business_tests` |
| `DemoGraphIntegrationTest` (all 4, unchanged) | jvmTest | `bazel test //kmp:jvm_tests` |

The `DemoGraphIntegrationTest` must pass here because Phase 4's `effectiveFileSystem`
derivation depends on `DemoFileSystem` being importable from `commonMain`.

---

### Gate D — Before Phase 5 (UX / sidebar)
Validates Phase 4 wiring is complete (onDemoSelected callback, effectiveFileSystem).

| Test | Source set | Command |
|------|-----------|---------|
| All Gate B tests (regression) | businessTest | `bazel test //kmp:business_tests` |
| All Gate C tests (regression) | jvmTest | `bazel test //kmp:jvm_tests` |
| AC-2 automated portion (KEYMAP_INTRO advance) | jvmTest | `bazel test //kmp:jvm_tests` |
| AC-10 no-SQLite-file assertion | businessTest | `bazel test //kmp:business_tests` |

---

### Gate E — Before Phase 6 (WASM cleanup)
Validates Phase 5 UX components (banner, switcher filter, pill contentDescription).

| Test | Source set | Command |
|------|-----------|---------|
| `DemoBannerTest` (T-12 through T-15) | jvmTest | `bazel test //kmp:jvm_tests` |
| `GraphSwitcherDemoFilterTest` (T-16, T-17) | jvmTest | `bazel test //kmp:jvm_tests` |
| AC-1, AC-4, AC-5, AC-6, AC-8 automated assertions | jvmTest | same |

---

### Gate F — Before merge / PR sign-off (all phases complete)
Full suite + manual checklist.

| Test | Source set | Command |
|------|-----------|---------|
| `bazel test //...` (entire test suite) | all | `bazel test //... --config=ci` |
| `DemoGraphPersistenceTest` (T-1 through T-7) | businessTest | included above |
| `DemoGraphIntegrationTest` (existing) | jvmTest | included above |
| `DemoFileSystemSyncTest` (T-11) | businessTest | included above |
| `GraphInfoSerializationTest` (T-8 through T-10) | businessTest | included above |
| Manual checklist: AC-7 timing, AC-12, AC-13, AC-14, AC-15, AC-16, AC-18 | Manual | QA smoke run |

---

## Appendix: Stub Harness for DemoGraphPersistenceTest

All new businessTest tests should reuse the following stub pattern (already in
`GraphManagerInitAutoRestoreTest.kt` — copy, do not duplicate logic):

```kotlin
private class InMemorySettings : Settings {
    private val store = mutableMapOf<String, String>()
    override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
    override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
    override fun putString(key: String, value: String) { store[key] = value }
    override fun containsKey(key: String) = store.containsKey(key)
}

private fun makeGraphManager(settings: InMemorySettings = InMemorySettings()) = GraphManager(
    platformSettings = settings,
    driverFactory = DriverFactory(),
    fileSystem = StubFileSystem(),          // same stub as GraphManagerInitAutoRestoreTest
    defaultBackend = GraphBackend.IN_MEMORY,
)
```

The `DEMO_GRAPH_ID` constant (`GraphId("__demo__")`) should be imported from
`dev.stapler.stelekit.model.DEMO_GRAPH_ID` once Task 1.1.1a adds it to `GraphInfo.kt`.
