# Build vs. Buy: Demo Graph Isolation — Classpath Resource Loading

**Date**: 2026-07-05
**Scope**: JVM classpath resource loading for demo content; sharing `DemoFileSystem` across platforms

---

## Q1: Is there a KMP library for loading classpath/bundled resources as a virtual filesystem?

**Verdict: No external library needed — the codebase already has everything required.**

Candidates evaluated:

| Library | Status | Verdict |
|---|---|---|
| `kotlinx-io` | Not in deps | Overkill — it is a byte-channel I/O abstraction, not a virtual filesystem |
| Ktor file APIs | In deps (ktor-client-core 3.1.3) | Client-only; no resource-loading or VFS surface |
| OkIO (`okio:3.17.0`) | In commonMain deps | Has `FakeFileSystem` (in test scope, line 148) but its API is more complex than the existing `Map<String,String>` approach |
| OkIO `FakeFileSystem` | In test deps only | Designed for testing; not appropriate as a production demo backend |
| Android `AssetManager` | Platform-specific | Android-only; does not help JVM desktop |

The project already has `ResourceLoader` (commonMain interface) with `JvmResourceLoader` (jvmMain) which uses `ClassLoader.getResourceAsStream()` — the battle-tested JVM approach for reading bundled classpath resources. The demo content already lives in `kmp/src/commonMain/resources/demo-graph/`. There is no gap that an external library fills.

---

## Q2: Could the WASM `DemoFileSystem` compile on JVM/commonMain with minor changes?

**Verdict: Yes — zero changes to the class body required. Only the generator output path needs updating.**

The generated `DemoFileSystem.kt` imports only:

```kotlin
import kotlin.time.Clock             // KMP (commonMain)
import kotlinx.datetime.TimeZone     // KMP (commonMain)
import kotlinx.datetime.todayIn      // KMP (commonMain)
```

It implements `FileSystem` (commonMain interface), uses `mutableMapOf` / `buildMap` (Kotlin stdlib), and stores everything as `Map<String, String>`. There is not a single platform-specific API in the file. The only reason it lives in `wasmJsMain` is that the generator task hard-codes its output to:

```
src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt
```

Changing that path to `src/commonMain/kotlin/...` (or `src/jvmMain/kotlin/...`) and removing the wasmJs-only copy is the complete migration. The generated class would then compile for JVM, Android, iOS, and WASM from a single file.

One test (`DemoFileSystemSyncTest.kt:34`) also hard-codes the wasmJs path and would need updating to point at the new location.

---

## Q3: Is there a battle-tested JVM approach for classpath resource loading, or is a custom shim the right call?

**Verdict: `ClassLoader.getResourceAsStream()` is the canonical, battle-tested JVM answer — and it is already implemented as `JvmResourceLoader`.**

The JVM ecosystem has used `getResourceAsStream()` for classpath resources since Java 1.1. There is no OSS library that meaningfully improves on it for simple text-file loading. The `ResourceLoader` interface in commonMain (`platform/ResourceLoader.kt`) and its JVM implementation (`JvmResourceLoader.kt`) already provide this cleanly:

```kotlin
// JvmResourceLoader — already in the codebase
classLoader.getResourceAsStream(path)?.bufferedReader()?.readText()
```

Since the demo files are already committed in `commonMain/resources/demo-graph/`, they are on the classpath in both JVM desktop and test runs. A custom `JvmDemoFileSystem` that wraps `JvmResourceLoader` is therefore viable — but it is strictly more code than moving the generator output to `commonMain` and sharing the generated class across all platforms.

---

## Q4: Move `DemoFileSystem` to `commonMain` — does the generator support it?

**Verdict: Not yet, but the change is trivial (one line in `build.gradle.kts`).**

The generator task (`generateDemoFileSystem`, build.gradle.kts line 425–577) currently hard-codes:

```kotlin
val outputFile = layout.projectDirectory.file(
    "src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt"
)
```

Changing `wasmJsMain` to `commonMain` in that path is the entire generator change. The class body emitted by the generator is already 100% KMP-compatible. After this change:

- Both JVM desktop and WASM consume the same class from `commonMain`
- No per-platform copy or `expect/actual` is needed
- `DemoFileSystemSyncTest` path reference (line 34) must be updated to match

The wiring in `afterEvaluate { tasks.findByName("compileKotlinWasmJs")?.dependsOn(generateDemoFileSystem) }` can stay unchanged; JVM compilation will also need `dependsOn(generateDemoFileSystem)` added to `compileKotlinJvm` (or kept as a `jvmTest` dependency as it is today, since the app only uses `DemoFileSystem` during test or on WASM).

---

## Recommendation Summary

| Option | Effort | Risk | Verdict |
|---|---|---|---|
| Add `kotlinx-io` / OkIO VFS | Medium | Medium — new dep, unfamiliar API surface | Reject — no gap to fill |
| Write a standalone `JvmDemoFileSystem` using `JvmResourceLoader` | Low | Low | Viable fallback but more code than option below |
| Move generator output to `commonMain` (share existing class) | Very low (1–2 line change) | Very low | **Recommended** |

**Build from within, not buy.** The `DemoFileSystem` pattern is already correct. The only work needed is:

1. Change the generator output path from `wasmJsMain` to `commonMain` in `build.gradle.kts`.
2. Wire `compileKotlinJvm` to `dependsOn(generateDemoFileSystem)` so the desktop build has the class.
3. Update `DemoFileSystemSyncTest` path reference.
4. Add `GraphInfo.isDemo` flag and strip `isDemo` entries from the registry on startup (no library involved — pure domain logic in `GraphManager`).

For the JVM `GraphManager.addDemoGraph()` implementation, pass the shared `DemoFileSystem()` instance as the `fileSystem` argument and use `GraphBackend.IN_MEMORY` as the `defaultBackend` — the infrastructure for both already exists.
