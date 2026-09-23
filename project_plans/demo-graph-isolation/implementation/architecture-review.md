# Architecture Review: demo-graph-isolation
**Date**: 2026-07-05
**Verdict**: CONCERNS (0 blockers, 5 concerns, 3 nitpicks)

---

## Constitution Violations

`docs/adr/ADR-000-architecture-constitution.md` does not exist. No constitution to check against.

---

## Blockers

None.

---

## Concerns

- [ ] **Story 2.1.1 / 2.1.3 — `switchGraph(DEMO_GRAPH_ID)` calls `saveRegistry()` unconditionally**

  After `addDemoGraph()` correctly skips `saveRegistry()`, `switchGraph()` at line 444–446 calls `saveRegistry()` with `_graphRegistry.value` still containing the demo `GraphInfo(isDemo=true)`. The demo entry IS written to the persisted JSON during the active demo session. The design relies entirely on `loadRegistry()` stripping it on next restart. If stripping ever fails (JSON exception, code regression), a `switchGraph("/demo")` with `SQLDELIGHT` backend fires at startup and may create a `__demo__.db` file.

  **Remediation**: Add a guard in `switchGraph()` immediately before `saveRegistry()` (line 446):
  ```kotlin
  if (!graphInfo.isDemo) saveRegistry()
  ```
  This makes the "demo never touches persistent storage" guarantee structural, not dependent on cleanup at load time. The `loadRegistry()` stripping in Story 2.1.2 remains as a belt-and-suspenders guard for any `isDemo=true` entries that might pre-exist from a prior buggy release.

- [ ] **Story 2.1.2 — `loadRegistry()` writing `onboardingCompleted` violates SRP**

  `GraphManager` is a graph-lifecycle manager. Writing `platformSettings.putBoolean("onboardingCompleted", false)` reaches into a settings key that semantically belongs to the ViewModel/App layer (`StelekitViewModel.setOnboardingCompleted`, `appState.onboardingCompleted`). `GraphManager` now has an implicit out-of-band dependency on the App layer's key naming convention.

  **Remediation**: Instead of writing the flag inside `GraphManager`, expose the stripped state via the `graphRegistry` `StateFlow` itself. `App.kt` already observes `graphRegistry`; it can react to `graphs.isEmpty() && activeGraphId == null` by routing to onboarding. If the direct `platformSettings` write is kept for pragmatic reasons (App.kt route depends on `onboardingCompleted` synchronously), add a comment documenting the coupling and a constant for the settings key shared between `GraphManager` and the ViewModel.

- [ ] **Phase 3 Task 3.1.1b — `afterEvaluate` Gradle anti-pattern**

  The plan uses:
  ```kotlin
  afterEvaluate {
      tasks.findByName("compileKotlinJvm")?.dependsOn(generateDemoFileSystem)
  }
  ```
  `afterEvaluate` with `findByName` is a configuration-avoidance anti-pattern that breaks Gradle's parallel configuration and can cause intermittent failures if the task is not yet registered when `afterEvaluate` fires.

  **Remediation**: Replace with lazy configuration:
  ```kotlin
  tasks.named("compileKotlinJvm") { dependsOn(generateDemoFileSystem) }
  ```
  If `compileKotlinJvm` is not always present (e.g., only when the JVM target is enabled), use `tasks.matching { it.name == "compileKotlinJvm" }.configureEach { dependsOn(generateDemoFileSystem) }`.

- [ ] **Phase 6 — WASM workaround removal is not safe to ship atomically with `isDemo` flag**

  The `contains("/demo")` workaround in `Main.kt` lines 117–121 catches OLD-format registry entries where path is `/demo` but there is no `isDemo` key in the JSON. These entries deserialized by the new `loadRegistry()` will have `isDemo = false` (Kotlin default), so the new stripping logic will NOT remove them. If the workaround is removed in the same release that adds the `isDemo` flag, WASM users with an old-format registry will hit `switchGraph` on the `/demo` path with the SQLDELIGHT backend on OPFS — causing the same loading-overlay hang the workaround was preventing.

  The plan's own "Rabbit Holes" section acknowledged this risk but Phase 6 does not address it.

  **Remediation** (one of two options):
  - **Option A (preferred)**: Extend `loadRegistry()` stripping to also filter entries where `path == "/demo" && !isDemo`, then remove the WASM workaround. This one-shot migration is safe and removes the workaround cleanly.
  - **Option B**: Keep the WASM workaround for one additional release cycle; remove it in a follow-up after isDemo propagates to all active users.

- [ ] **Phase 3 + Bazel — generated `DemoFileSystem.kt` in `commonMain` not verified against Bazel**

  The plan specifies Gradle task changes and accepts `bazel build //kmp:desktop_app` as passing. However, the plan does not show any `BUILD.bazel` changes, and if `kmp/BUILD.bazel` contains a manual `srcs` list (rather than a `glob(["src/commonMain/**/*.kt"])` pattern), moving `DemoFileSystem.kt` from `wasmJsMain` to `commonMain` will silently break the Bazel `jvm_tests` and `desktop_app` targets without a Gradle failure.

  **Remediation**: Before closing Phase 3, run `bazel build //kmp:desktop_app --config=android` (or `bazel test //kmp:jvm_tests`) and confirm it succeeds. If `BUILD.bazel` is manually maintained, add `DemoFileSystem.kt` to the `commonMain` sources entry.

---

## Nitpicks

- **`DEMO_GRAPH_ID` constant placement**: Declaring `val DEMO_GRAPH_ID = GraphId("__demo__")` as a top-level constant in `GraphInfo.kt` puts an infrastructure sentinel value in the domain model file. `GraphInfo.kt` should describe the shape of graph metadata, not encode system identifiers. Consider placing `DEMO_GRAPH_ID` in `GraphManager.kt` (where it is exclusively used) as a companion object constant, or in a dedicated `DemoGraphConstants.kt`.

- **`addDemoGraph()` return type**: The method always returns the same constant `DEMO_GRAPH_ID`. Returning `GraphId` implies the ID is computed or may vary; it won't. Either change the return type to `Unit` and have callers reference `DEMO_GRAPH_ID` directly, or document that the return value is always `DEMO_GRAPH_ID`.

- **Task 5.1.2a `isDemoActive` computation**: The plan computes `isDemoActive` as `availableGraphs.size < (availableGraphs + demo).size` — this is opaque and would fail to compile as written. The `LeftSidebar` already has access to `activeGraphInfo` (or can derive it). Pass `isDemoActive = activeGraphInfo?.isDemo == true` directly — one boolean, one read, no list arithmetic.

---

## Summary

The design is structurally sound. `isDemo: Boolean = false` on `GraphInfo` is the correct type-driven approach; the rejected alternatives (sealed class, sentinel path) were correctly discarded. The phase ordering (model → manager → build → UI → tests) is coherent.

The five concerns are all fixable in-place without design rethink:
- The `switchGraph` `saveRegistry` concern (Concern 1) is the highest-value fix — one line prevents the most failure mode.
- The WASM staging concern (Concern 4) is the highest-risk ship blocker — it affects real users.
- The Gradle `afterEvaluate` concern (Concern 3) is a correctness issue on CI flakiness risk.
