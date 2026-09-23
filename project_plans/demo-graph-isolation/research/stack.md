# Stack Research: Demo Graph Isolation

**Date**: 2026-07-05
**Feature**: Demo Graph Isolation (JVM desktop)

---

## Q1. Can `GraphLoader.loadGraph()` load from a classpath resource directory on JVM?

**Short answer: No. A `JvmDemoFileSystem` shim (or equivalent) is required.**

`GraphLoader.loadGraph(graphPath)` delegates all filesystem access through the injected `FileSystem` interface. On JVM, the concrete implementation is `JvmFileSystemBase` (base class for `PlatformFileSystem`). Its `listFiles(path)` implementation calls `java.io.File(validatedPath).listFiles()` — a real filesystem API. It also goes through `validatePath()`, which requires the path to be under a pre-registered whitelist (home dir by default).

Classpath resources under `commonMain/resources/demo-graph/` ARE on the JVM classpath at runtime, but they are NOT accessible as `java.io.File` paths. On JVM they are bundled inside a JAR archive entry — `java.io.File` cannot enumerate JAR entries as a directory. `Class.getResource("demo-graph/pages")` returns a `jar:file:…` URL, not a plain `file:` URL; calling `File(url.toURI())` on it throws `IllegalArgumentException`.

**The existing demo graph content** (25 `.md` files across `pages/` and `journals/`) already lives in `kmp/src/commonMain/resources/demo-graph/`. The WASM target generates a `DemoFileSystem.kt` from these files at build time via the `:kmp:generateDemoFileSystem` Gradle task, inlining all content as string literals.

**The path forward for JVM** is the same pattern: implement a `JvmDemoFileSystem : FileSystem` (or extend/reuse `DemoFileSystem` in `commonMain`) that serves the same inlined content from string literals — no real filesystem access, no classpath URL resolution needed. The generated WASM `DemoFileSystem` is already in `wasmJsMain` and is WASM-only; a parallel `jvmMain` version is needed, or the generator task could be extended to also emit a `commonMain` version.

**Key files:**
- `kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/platform/JvmFileSystemBase.kt` — `listFiles()` uses `java.io.File`, cannot traverse JARs
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt` — generated, serves inlined string content
- `kmp/src/commonMain/resources/demo-graph/` — source of truth (25 `.md` files)

---

## Q2. How does `DemoFileSystem` (WASM) handle resource loading — is the pattern portable to JVM?

**The WASM `DemoFileSystem` is fully portable to JVM without modification.**

`DemoFileSystem` implements the `FileSystem` interface by holding all demo content as inline `Map<String, String>` string literals baked into the class at build time by the `:kmp:generateDemoFileSystem` Gradle task. It has no WASM-specific APIs — no JS interop, no browser APIs. Its entire implementation is pure Kotlin with `kotlin.time.Clock` and `kotlinx.datetime`.

Key design decisions that make it portable:
- `readFile(path)` strips the `/demo/` prefix and looks up the key in `demoFiles` (the static map) or `overrides` (session-only writes).
- `listFiles(path)` filters keys by prefix — no real filesystem calls.
- `writeFile(path, content)` stores to `overrides` (session-only; lost on restart) — returning `true` without actually writing to disk.
- `directoryExists(path)` uses a hardcoded `knownDirectories` set.

The only barrier to using this in `commonMain` directly is that the generated file currently lives in `wasmJsMain`. Either move it to `commonMain` (sharing it across all platforms) or generate a separate JVM copy. Moving to `commonMain` is cleaner — the WASM Main.kt independently constructs a `DemoFileSystem()` and passes it to `GraphManager`, so it would continue to work.

`DemoFileSystemSyncTest` in `businessTest` (JVM) already reads `demo-graph/pages/` via `javaClass.classLoader.getResource()` and verifies sync with the generated file — proving the classpath resource approach already works for reading individual files by known path.

---

## Q3. Is adding `isDemo: Boolean = false` to `GraphInfo` backward-compatible?

**Yes — fully backward-compatible.**

`GraphInfo` is annotated `@Serializable` and uses `kotlinx.serialization`. `GraphManager` deserializes it via:
```kotlin
private val json = Json { ignoreUnknownKeys = true }
val registry = json.decodeFromString<GraphRegistry>(registryJson)
```

`ignoreUnknownKeys = true` is already set on the `Json` instance, which means:
1. Old registries that do NOT contain `"isDemo"` will decode cleanly — the field gets its default value `false`.
2. New registries written with `"isDemo": false` will decode cleanly on old app versions (if `ignoreUnknownKeys` is set there too — it is, same class).
3. The `= false` default on the Kotlin property is what supplies the value when the JSON key is absent; this is standard kotlinx.serialization behavior for `@Serializable` data classes with defaults.

No migration is needed. `GraphRegistry` also has `@Serializable` with only `List<GraphInfo>` and `GraphId?` — no issues there.

**Existing fields with defaults (precedent):** `isParanoidMode: Boolean = false`, `detectedRepoRoot: String? = null`, `gitDetectionDismissed: Boolean = false` all follow this exact pattern and are already in production.

---

## Q4. Which specific files/classes are in the demo graph loading path on JVM?

### Current (broken) path — JVM

1. **`Onboarding.kt`** (`ui/onboarding/Onboarding.kt`, line 162) — calls `onGraphSelected("deps/graph-parser/test/resources/exporter-test-graph")` hardcoded relative path.
2. **`StelekitViewModel.setGraphPath(path)`** — persists path to `Settings` as `"current_graph_path"`.
3. **`GraphManager.addGraph(path)`** — calls `fileSystem.expandTilde(path)`, generates `GraphId` from `sha256(expandedPath).take(16)`, creates a `GraphInfo` with `displayName = fileSystem.displayNameForPath(path)` → `"exporter-test-graph"`, creates a real SQLite `.db` file via `DriverFactory` inside the project source tree.
4. **`GraphManager.init { loadRegistry(); switchGraph(activeId) }`** — on next launch, restores the stale relative path from the registry and calls `switchGraph()` → fails or hangs.

### New (target) path — JVM

1. **`Onboarding.kt`** — calls new `onDemoGraphSelected()` callback instead of `onGraphSelected(demoPath)`.
2. **`GraphManager.addDemoGraph()`** (new method) — constructs a `GraphInfo` with `isDemo = true`, `displayName = "Demo Graph"`, `path = "/demo"` (virtual), and creates a `RepositorySet` with `GraphBackend.IN_MEMORY` — no SQLite `.db` file on disk.
3. **`GraphLoader`** — receives a `JvmDemoFileSystem` (or shared `DemoFileSystem`) instead of the real `PlatformFileSystem`; `loadGraph("/demo")` calls `fileSystem.directoryExists("/demo")` (returns `true`), `fileSystem.listFiles("/demo/pages")` (returns file names from the inline map), etc.
4. **`GraphManager.loadRegistry()`** — strips entries where `isDemo == true` before restoring, so the demo is never auto-opened on next launch.
5. **WASM `Main.kt`** — current `contains("/demo")` guard at line ~118 can be removed once `isDemo` flag is authoritative.

### Files requiring changes

| File | Change |
|---|---|
| `model/GraphInfo.kt` | Add `isDemo: Boolean = false` |
| `db/GraphManager.kt` | `addDemoGraph()` method; strip `isDemo` in `loadRegistry()` |
| `ui/onboarding/Onboarding.kt` | Call `onDemoGraphSelected()` instead of `onGraphSelected(demoPath)` |
| `wasmJsMain/platform/DemoFileSystem.kt` | Move to `commonMain` (or generate a JVM copy) |
| `wasmJsMain/browser/Main.kt` | Remove `contains("/demo")` workaround |

### Files NOT requiring changes

| File | Reason |
|---|---|
| `GraphLoader.kt` | Already fully interface-driven via `FileSystem`; no changes needed if `DemoFileSystem` is injected |
| `JvmFileSystemBase.kt` | Not used for demo path once `DemoFileSystem` is injected |
| `SteleDatabase.sq` / `MigrationRunner.kt` | `IN_MEMORY` backend skips SQLite entirely |
| `DriverFactory` | `IN_MEMORY` backend path doesn't create a `.db` file |
