# Pitfalls & Risks: Demo Graph Isolation

**Date**: 2026-07-05
**Scope**: Adding `isDemo: Boolean` to `GraphInfo`, `addDemoGraph()` on `GraphManager`, registry stripping on startup, JVM classpath resource seeding, WASM migration.

---

## 1. Serialization Backward Compatibility

### Risk Level: Low — but verify `encodeDefaults`

`GraphManager` uses `Json { ignoreUnknownKeys = true }` (line 61). This means:

- **Old → New (safe):** An old registry JSON without `isDemo` is deserialized by a new binary. `ignoreUnknownKeys` prevents crashes on unknown fields; the missing `isDemo` field gets `false` from the Kotlin default. This is exactly correct behavior — old persisted entries are treated as real graphs.
- **New → Old (safe for old users):** A registry written by a new binary (with `"isDemo":true`) is read by an old binary. Since the old binary does not know `isDemo` and has `ignoreUnknownKeys = true`, the field is silently ignored. The demo entry is loaded as a normal graph. On JVM this causes a corrupted startup (DB open attempt on a phantom path); on WASM the old registry workaround (`contains("/demo")`) would strip it anyway.
- **Edge case — `encodeDefaults`:** The `GraphManager` json instance does NOT set `encodeDefaults = true` (only `BugReportBuilder` and `PerfExporter` do). By default, kotlinx.serialization **omits** fields whose value equals the default. So `isDemo = false` will be **omitted from the JSON** for normal graphs — the field is only present when `true`. This is fine for forward/backward compat but must be confirmed: do not assume `isDemo` appears in all serialized `GraphInfo` entries.

**Action**: No code change needed for compat — the existing `ignoreUnknownKeys` and Kotlin default values handle both directions correctly. Add a unit test that round-trips a `GraphInfo` with no `isDemo` field through `GraphManager`'s `Json` instance to lock this behavior.

---

## 2. `IN_MEMORY` Backend Edge Cases

### Risk Level: Medium

`addGraph()` (lines 230–271) performs three side effects that are wrong for a demo graph:

1. **`checkGitignoreForDatabase(expandedPath)`** (line 242): reads `.gitignore` from the demo path. For an in-memory demo graph there is no real path, so this either silently returns (file not found) or warns about a non-existent path. The function is only `println` warnings, not fatal — but it clutters logs.

2. **`detectGitRoot` fire-and-forget coroutine** (lines 264–269): launched on `PlatformDispatcher.IO` after `addGraph`. For a fake in-memory path (e.g. `"__demo__"`) this walks parent directories looking for `.git`. It is bounded to 10 levels (`depth <= 10`) and returns null if not found, so it terminates safely — but it burns IO threads at startup for no reason.

3. **`driverFactory.getDatabaseUrl(id.value)`**: called inside `switchGraph` (line 391) to construct the JDBC URL. If `addDemoGraph` calls `switchGraph` with `defaultBackend = IN_MEMORY`, the code path at line 411 (`if (defaultBackend == GraphBackend.SQLDELIGHT)`) skips `UuidMigration` and `MigrationRunner`. The factory still calls `getDatabaseUrl` though — on JVM this constructs a `:memory:` JDBC URL only if `DriverFactory` is wired that way; confirm the JVM `DriverFactory` supports in-memory mode without creating a file.

4. **`fileSystem.displayNameForPath(expandedPath)`** (line 113 in `loadRegistry`): called during `loadRegistry` refresh. For the demo path this is harmless — just returns the path basename — but if the demo path is a sentinel like `"__demo__"` it may return an ugly display name. `addDemoGraph` must set `displayName = "Demo Graph"` explicitly (the requirements already specify this) and the `loadRegistry` refresh must not overwrite it.

**Key finding**: `addGraph` must NOT be called for the demo. `addDemoGraph` must bypass `checkGitignoreForDatabase`, skip git detection, use a stable synthetic path (e.g. `"__demo__"` or `"/stelekit/demo"`), and directly construct `GraphInfo` without invoking `fileSystem.displayNameForPath`.

---

## 3. Registry Stripping on Startup: Only-Graph Hang Risk

### Risk Level: High

The `init` block (lines 92–103) does:

```kotlin
loadRegistry()
val activeId = _graphRegistry.value.activeGraphId
if (activeId != null) {
    val graphInfo = ...firstOrNull { it.id == activeId }
    if (graphInfo?.isParanoidMode != true) {
        switchGraph(activeId)
    }
}
```

If `loadRegistry` strips `isDemo = true` entries and the demo was the **only graph** and was **active**, the resulting registry has:
- `graphs = emptyList()`
- `activeGraphId = <stripped demo id>`

`switchGraph(activeId)` is then called with an id not in `graphs`, so `firstOrNull` returns null and `switchGraph` returns immediately (line 339: `if (graphInfo == null) return`). The registry is saved with a stale `activeGraphId` pointing to a non-existent entry.

**Loading overlay hang**: `StelekitViewModel.init` (line 476–480) loads graph only if `path.isNotEmpty() && onboarded`. If `onboardingCompleted = true` was saved and `lastGraphPath` still holds the demo path, `loadGraph(demoPath)` fires. Since the demo path is not a real filesystem path, `GraphLoader.loadDirectory` will either fail or return 0 pages. Whether `isLoading` is set to false depends on the error path — lines 762/773 do reset `isLoading`, but the `statusMessage` will show an error rather than onboarding.

**Mitigation required**: When `loadRegistry` strips the demo entry and the result is an empty graph list, it must also:
1. Set `activeGraphId = null` in the registry before saving.
2. Set `onboardingCompleted = false` in `platformSettings` so the onboarding screen is shown again (or redirect to onboarding in App.kt when `graphs.isEmpty()`).

Alternatively, never strip on startup — instead filter demo entries from the graph switcher UI and treat them as transient. Strip only when writing the registry at shutdown.

---

## 4. WASM Registry Migration: Stale Demo Entries in Old Format

### Risk Level: Medium

The current WASM workaround (Main.kt lines 117–121):

```kotlin
val existingRegistry = localStorage.getItem("graph_registry") ?: ""
if (existingRegistry.contains("/demo")) {
    localStorage.removeItem("graph_registry")
}
```

This clears the **entire** registry whenever any entry has `/demo` in its path — a blunt instrument. After adding `isDemo`, the scenario to handle is:

- **User on old WASM build** has a registry with `{"path":"/stelekit/demo",...}` (no `isDemo` field). They update to the new build. The new build's `loadRegistry` deserializes with `ignoreUnknownKeys`; `isDemo` defaults to `false`. The demo entry looks like a real graph. The `contains("/demo")` workaround in Main.kt would still fire and strip it — but ONLY if the path contains `/demo`.
- **After migration**: new demo entries use `isDemo = true` with a synthetic path that may NOT contain `/demo`. The `contains("/demo")` check no longer catches them. The WASM workaround must be replaced with `registry.graphs.none { it.isDemo }` (or removed entirely if `loadRegistry` stripping handles it).
- **Double-strip race**: If both `loadRegistry` stripping and the WASM `contains("/demo")` workaround are active simultaneously, a user whose ONLY real graph happens to have `/demo/` in its path (e.g., `/home/user/demo-notes/`) gets their registry wiped. The path-substring check is inherently fragile.

**Mitigation**: Remove the `contains("/demo")` workaround in Main.kt as part of this feature. Replace with a one-time registry migration: if `isDemo` field is absent and path contains `/demo`, backfill `isDemo = true`; otherwise leave the entry alone.

---

## 5. JVM Classpath Resource Loading for Demo Content

### Risk Level: Medium

`DemoFileSystem` currently exists only in `wasmJsMain` — it is a generated file (`// GENERATED — do not edit`). For JVM onboarding, the requirements say "seed content from classpath resources." Pitfalls:

1. **Gradle resource bundling**: Demo markdown files in `commonMain/resources/demo-graph/` (as referenced in `BUILD.bazel:39`) must be included in the JVM fat JAR / distribution. Verify `processResources` picks them up — resources in `jvmMain/resources` are included automatically; resources in `commonMain/resources` require explicit configuration in `build.gradle.kts` for `jvmMain`.

2. **`getResourceAsStream` vs `ClassLoader.getSystemResourceAsStream`**: In production JARs, `this::class.java.getResourceAsStream("/demo/pages/foo.md")` works. In dev (running from IDE or `bazel run`), the classpath root differs. The WASM `DemoFileSystem` bakes content as string literals to avoid this entirely — the same approach (code generation) is safer for JVM than runtime classpath loading.

3. **Path separator on Windows**: Classpath resources use `/` as separator regardless of OS, but if any code concatenates paths with `File.separator` the resource lookup will fail on Windows. Use only `/` for resource paths.

4. **Thread safety**: `Class.getResourceAsStream` is thread-safe. Not a concern.

5. **The `generateDemoFileSystem` Gradle task**: `DemoFileSystemSyncTest` enforces that the generated file matches `commonMain/resources/demo-graph/`. If a JVM-side `DemoFileSystem` is added for JVM (not shared with WASM), a separate generation task or a shared interface in `commonMain` is needed. Risk: two out-of-sync generated files if the Gradle task only generates the WASM one.

**Recommendation**: Introduce a `DemoFileSystem` interface in `commonMain` backed by a shared resource map. Generate ONE file that compiles on both JVM and WASM, or use `expect/actual` with the same resource list. Do not load resources at runtime via classpath on JVM — the generated string-literal approach avoids the entire classpath lookup problem.

---

## 6. Demo Graph in the Graph Switcher: Accidental Mutation

### Risk Level: Medium

`renameGraph` (lines 318–330) and `removeGraph` (lines 290–315) operate on any `GraphId` without checking `isDemo`. If the demo graph is visible in the graph switcher:

- **Rename**: User renames "Demo Graph" to "My Notes" — the `isDemo` flag is not affected. On next startup, `loadRegistry` strips the `isDemo = true` entry, user loses their renamed demo.
- **Remove**: `removeGraph` returns `false` if the graph is active, but succeeds if it's not active. A user can remove the demo from the switcher, then the demo id is gone from the registry. This is actually desirable behavior — no guard needed for removal.
- **Backup attempts**: A user might try to export/backup the demo. Since it uses `IN_MEMORY` backend, `GraphWriter` writes to the `DemoFileSystem` (which has an `overrides` mutable map in the WASM version) — writes are silently lost on restart. No crash, but user confusion.
- **Git sync registration**: `registerGitSyncService` (line 575) is called from `GraphContent` in `App.kt`. If Git sync is wired for the demo graph (it shouldn't be, but nothing prevents it if `detectedRepoRoot` is non-null for a demo graph seeded from a real git repo), it will attempt file operations on a non-existent path.

**Mitigation**: Filter `isDemo = true` from the graph switcher list in `Sidebar.kt`. Disable `renameGraph` for demo entries (or make `renameGraph` a no-op for `isDemo = true`). No need to guard `removeGraph` — letting users remove the demo is correct. Add guard in `addDemoGraph` to ensure `detectedRepoRoot = null` so git sync is never registered.

---

## Summary

| # | Risk | Level | Key Mitigation |
|---|------|-------|---------------|
| 1 | Serialization compat (`isDemo` defaults) | Low | Existing `ignoreUnknownKeys` handles it; add round-trip test |
| 2 | `addGraph` side effects (gitignore check, git detection, DB URL) for demo | Medium | `addDemoGraph` must bypass `addGraph`, build `GraphInfo` directly |
| 3 | Empty graph list after stripping demo — loading overlay hang | **High** | Strip must also null `activeGraphId`; reset onboarding or redirect |
| 4 | WASM `contains("/demo")` workaround conflicts with `isDemo` flag | Medium | Remove path-substring check; replace with `isDemo` field check |
| 5 | JVM classpath resource loading brittle in production JARs | Medium | Use generated string-literal approach (same as WASM), avoid runtime classpath |
| 6 | Demo in graph switcher: rename/backup confusion, git sync risk | Medium | Hide `isDemo` entries from switcher; guard `renameGraph`; null `detectedRepoRoot` |
