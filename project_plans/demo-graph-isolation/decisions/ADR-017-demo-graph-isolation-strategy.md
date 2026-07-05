# ADR-017: Demo Graph Isolation Strategy

**Date**: 2026-07-05
**Status**: Accepted
**Deciders**: Tyler Stapler
**Feature**: demo-graph-isolation

---

## Context

When a user selects "Load Demo Graph" during JVM onboarding, the current code calls `graphManager.addGraph("deps/graph-parser/test/resources/exporter-test-graph")`. This has three consequences:

1. A real SQLite `.db` file is created inside the project source tree.
2. The relative path `deps/graph-parser/test/resources/exporter-test-graph` is persisted as `lastGraphPath` in `platformSettings`, which breaks on next launch in production builds (where `deps/` does not exist).
3. The entry appears in the persistent registry with `displayName = "exporter-test-graph"` and a real `activeGraphId`; on restart `GraphManager.init` tries to restore it, which either fails or hangs.

On WASM a path-substring workaround (`contains("/demo")`) was added to clear stale demo registry entries at startup. This is fragile (any real graph with `/demo/` in its path would be incorrectly cleared) and does not address the JVM problem.

The goal is to make the system structurally incapable of persisting the demo graph.

---

## Decision

### Option A — `isDemo: Boolean = false` flag on `GraphInfo` (ACCEPTED)

Add a `val isDemo: Boolean = false` field to `GraphInfo`. `GraphManager.addDemoGraph()` creates an in-memory-only `GraphInfo(isDemo = true)` without calling `saveRegistry()`. `loadRegistry()` strips any `isDemo = true` entries before they can influence startup. `switchGraph()` checks `graphInfo.isDemo` and forces `GraphBackend.IN_MEMORY`.

**Strengths**:
- Single source of truth — the `isDemo` flag propagates the distinction to `loadRegistry`, `switchGraph`, `renameGraph`, `GraphSwitcher`, and the banner with no string comparisons.
- Backward-compatible serialization: `ignoreUnknownKeys = true` + Kotlin default `= false` handles existing registries in both directions; `encodeDefaults` is not set so `isDemo = false` is omitted for real graphs.
- The "structural impossibility" property: `addDemoGraph()` never calls `saveRegistry()`, so even if `loadRegistry()` stripping were somehow bypassed, no demo entry was written.

**Weaknesses**:
- Two code paths in `switchGraph` (isDemo vs real). Mitigated by the guard being a single `if` at line 400.
- `DemoFileSystem` must be in `commonMain` for JVM to instantiate it — requires a build task output-path change.

### Option B — Sealed class `GraphKind` hierarchy

Replace `GraphInfo` with `sealed class GraphKind { data class Real(...); data class Demo(...) }`.

**Strength**: The type system makes it impossible to call `switchGraph` with a `Demo` instance in a context that expects a `Real`.

**Weakness**: Breaks every call site of `GraphInfo` across `GraphManager`, `Sidebar`, `ViewModel`, tests, and serialization — estimated 30+ files. Disproportionate for the scope of this feature (appetite: Small, 1–2 days).

**Rejected**: Too large a refactor for the problem at hand.

### Option C — Sentinel `GraphId("__demo__")` alone, no flag field

Detect demo by checking `graphId == DEMO_GRAPH_ID` everywhere, without modifying `GraphInfo`.

**Strength**: Zero model changes.

**Weakness**: Callers must know to check the ID; the check is not enforced at the type level. The `renameGraph` guard, the serialization exclusion, and the `loadRegistry` stripping all require this same sentinel check scattered across unrelated methods — same smell as the `contains("/demo")` workaround.

**Rejected**: String-matching anti-pattern; spreads the sentinel knowledge without encapsulating it.

---

## Consequences

### Positive
- Zero files created in `deps/` or outside the OS data directory when loading the demo.
- Demo is invisible to the persistent registry; `quit → relaunch` always routes to onboarding or the real graph.
- The `contains("/demo")` WASM workaround is deleted.
- `renameGraph` is guarded; `GraphSwitcher` never lists the demo; a banner explains the demo context.

### Negative / Trade-offs
- `switchGraph()` gains an `isDemo` branch for backend selection. This is a narrow, well-documented deviation.
- `DemoFileSystem.kt` moves from `wasmJsMain` to `commonMain`, requiring a 1-line build task output-path change and deletion of the old file.
- `App.kt`'s `GraphContent` composable derives `effectiveFileSystem` from `activeGraphInfo?.isDemo` to supply `DemoFileSystem` to `GraphLoader`. This adds one `remember` call and one `if` branch.

### Neutral
- `DemoGraphIntegrationTest` is unchanged (it loads from the classpath resource, not from `GraphManager`).
- `DemoFileSystemSyncTest` requires a 1-line path string update (Task 3.1.1d).

---

## Notes on Serialization

`Json { ignoreUnknownKeys = true }` (line 61 of `GraphManager.kt`) means:
- Old registries (no `isDemo` key) deserialize with `isDemo = false` via the Kotlin default.
- New registries with `isDemo = true` (for a persisted demo, which should never happen) would be stripped by `loadRegistry()`.

`encodeDefaults` is NOT set in the `Json` instance, so `isDemo = false` is omitted from JSON output. Only `isDemo = true` entries appear in the JSON, and those are for demo graphs that are never written to the registry.

---

## Implementation Reference

See `project_plans/demo-graph-isolation/implementation/plan.md` for full task breakdown.

Key files changed:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/GraphInfo.kt` — add `isDemo`, `DEMO_GRAPH_ID`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` — `addDemoGraph`, `loadRegistry`, `switchGraph`, `renameGraph`
- `kmp/build.gradle.kts` — generator output path change + JVM compile dependency
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` — `onDemoSelected`, `effectiveFileSystem`, `DemoBanner`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/onboarding/Onboarding.kt` — `onDemoSelected` param
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt` — filter isDemo, contentDescription
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt` — remove `/demo` workaround
