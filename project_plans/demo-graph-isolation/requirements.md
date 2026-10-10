# Requirements: Demo Graph Isolation

**Date**: 2026-07-05
**Type**: feature addition
**Complexity**: 2 — focused feature

## Problem Statement

When a user selects "Load Demo Graph" during onboarding (JVM desktop), the demo graph is
treated identically to a real user graph:
- A real SQLite `.db` file is created inside `deps/graph-parser/test/resources/exporter-test-graph/` — inside the project source tree. This file is gitignored but dirties the working tree.
- The demo path is persisted as the "current graph path" (a relative path that breaks in production builds where `deps/` does not exist).
- The graph's display name is `"exporter-test-graph"` — not user-facing.
- There is no `isDemo` flag on `GraphInfo`, so the system cannot distinguish demo from real graphs at startup.
- On WASM, stale `/demo` registry entries already cause the loading overlay to hang on next startup (workaround exists in `Main.kt:118`); JVM has no equivalent guard.

**The net effect**: a user who explores via "Load Demo Graph" ends up with a fragile registry entry that may fail to restore on next launch, creates DB files in the source tree, and appears as a confusing unnamed graph alongside their real graphs.

## Baseline

Today, clicking "Load Demo Graph" in `Onboarding.kt`:
1. Calls `onGraphSelected("deps/graph-parser/test/resources/exporter-test-graph")`
2. Calls `graphManager.addGraph(path)` → creates a `GraphInfo` with `displayName = "exporter-test-graph"` and creates a SQLite db inside the project source tree
3. Calls `viewModel.setGraphPath(path)` — persists this relative path
4. On next app launch, `GraphManager.init` calls `switchGraph(demoGraphId)`, which tries to open the SQLite db at the relative path — fails silently or hangs in production builds

WASM already handles the analogous case with a manual check (`if (existingRegistry.contains("/demo")) { localStorage.removeItem("graph_registry") }`), but this is a fragile workaround.

## Users / Consumers

- **New SteleKit desktop users** exploring via onboarding
- **GraphManager** (reads `GraphInfo.isDemo` to skip auto-restore on startup)
- **Graph switcher UI** (shows "Demo Graph" label + visual distinction)

## Success Metrics

- Loading the demo graph creates zero files in `deps/` or anywhere outside the app's data directory
- After a "Load Demo Graph" → quit → relaunch cycle, the app opens to the empty onboarding or the user's real graph — never tries to restore the demo graph
- The demo graph appears in the graph registry as "Demo Graph" (not `exporter-test-graph` or a path fragment)
- The WASM workaround in `Main.kt` (`contains("/demo")`) is no longer needed — replaced by the `isDemo` flag
- Existing `DemoGraphIntegrationTest` and `DemoFileSystemSyncTest` pass unchanged

## Appetite

Small (1–2 days)
*(Scope must fit the appetite. If it doesn't fit, cut scope — do not move the deadline.)*

## Constraints

- `GraphInfo` is `@Serializable` and stored in the persisted registry JSON — any field addition must use a default value for backwards compatibility
- The demo graph on JVM must still load actual markdown content (the `demo-graph/` classpath resources already exist at `kmp/src/commonMain/resources/demo-graph/`) — use those, not `deps/`
- No new dependencies

## Non-functional Requirements

- **Performance SLO**: no regression — demo graph load time unaffected
- **Scalability**: not applicable
- **Security classification**: internal
- **Data residency**: no special requirements

## Scope

### In Scope

1. Add `isDemo: Boolean = false` to `GraphInfo` (serialized with default so old registries parse cleanly)
2. `GraphManager.addDemoGraph()` (or flag parameter on `addGraph`) — sets `isDemo = true`, `displayName = "Demo Graph"`, uses `GraphBackend.IN_MEMORY`
3. `GraphManager.init` / `loadRegistry()` — strip `isDemo = true` entries from the restored registry so the demo is never auto-opened on startup
4. `Onboarding.kt` "Load Demo Graph" button — calls the new demo path, seeds content from `kmp/src/commonMain/resources/demo-graph/` classpath resources (mirror the WASM `DemoFileSystem` approach for JVM)
5. Remove the manual WASM workaround (`contains("/demo")` check) once `isDemo` flag covers it
6. A test asserting that demo graphs are not persisted across restarts (or are cleared on load)

### Out of Scope

- Changing the demo graph's content
- Making the demo graph read-only (edits in-session are fine; just not persisted)
- Adding a "Load Demo" button outside of onboarding
- iOS/Android demo graph path handling (follow-on)

## Rabbit Holes

- **JVM classpath resource loading in `GraphLoader`**: `GraphLoader.loadGraph(path)` calls `fileSystem.listFiles(path)`, which on JVM uses `java.io.File`. Classpath resources under `commonMain/resources/` ARE on the JVM classpath but not as a `File`-accessible directory — may require a `JvmDemoFileSystem` shim or unpacking resources to a temp dir before loading. Investigate before committing to an approach.
- **Serialization backward compat**: the `graph_registry` JSON key for `isDemo` — with `@SerialName` and a default of `false`, old registries missing the key will parse correctly. Confirm no `@Required` annotation conflicts.
- **WASM registry migration**: clearing the `contains("/demo")` workaround must be staged — the workaround should remain until the `isDemo` flag propagates to users (or keep both paths).

## Alternatives Considered

- **Use a temp directory for the demo SQLite db** — simpler than `IN_MEMORY`, but still creates disk files and still needs special cleanup. `IN_MEMORY` is cleaner.
- **Don't add `isDemo`; just clear ALL graphs on startup** — too destructive; users lose their real graph list.
- **Hardcode `/demo` as a sentinel path check** — fragile (path can collide); a typed field is more robust.

## Feasibility Risks

- JVM classpath resource → `FileSystem.listFiles()` incompatibility (the main rabbit hole above)
- `IN_MEMORY` backend for demo graphs means edits don't survive quit — acceptable per scope

## Open Questions

- Can `GraphLoader.loadGraph()` load from a classpath resource directory on JVM, or does it need a `JvmDemoFileSystem` similar to `DemoFileSystem` on WASM? (Research Phase answer needed)
- Should the demo graph entry be omitted from the graph switcher entirely, or shown with a "(Demo)" badge?
