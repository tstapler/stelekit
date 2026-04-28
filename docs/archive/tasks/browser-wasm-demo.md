# Browser Wasm Demo

**Status**: Planned
**Depends on**: nothing — self-contained migration

---

## Problem

The current `js(IR)` build produces a status page with raw DOM `innerHTML` writes — not the actual Compose app. To demonstrate SteleKit in the browser, the build must switch to the `wasmJs` Gradle target with `CanvasBasedWindow`, which renders the real Compose UI via Skia on a `<canvas>`.

## Architecture: Two Layers Required

Even with File System Access API handling the markdown files, **SQLite is still needed** as the query index. SteleKit's architecture separates concerns clearly:

- **File layer** (`PlatformFileSystem`) — reads/writes `.md` files; source of truth
- **Index layer** (SQLDelight + SQLite) — built from those files; enables fast backlink/search queries

For the browser, these map to:

| Layer | Desktop | Browser |
|-------|---------|---------|
| File I/O | `java.nio.file` | **File System Access API** (`FileSystemDirectoryHandle`) |
| SQLite | `sqlite-driver` (JVM) | **`@sqlite.org/sqlite-wasm`** (in-memory or OPFS) |

Both layers must be implemented for a real web target. The demo launch uses IN_MEMORY for SQLite and a seeded graph (no file picker), then the full implementation adds both layers.

## Phases

**Phase A — Demo launch**: `CanvasBasedWindow` renders the actual Compose UI; SQLite is `IN_MEMORY`; graph is seeded with demo content. Ships first to prove the canvas works.

**Phase B — Full web target**: File System Access API for file I/O + `@sqlite.org/sqlite-wasm` for the SQLite index. User picks their Logseq graph folder; the app reads/indexes it exactly like the desktop app. SQLite index is rebuilt from the markdown files on each load (fast enough for typical graph sizes); OPFS caching is an optimization for large graphs.

## Current State → Phase A Target

| Item | Current | Phase A Target |
|------|---------|----------------|
| Gradle target | `js(IR)` | `wasmJs` |
| Compose entry | Raw DOM writes in `Main.kt` | `CanvasBasedWindow { StelekitApp() }` |
| SQLite | `WebWorkerDriver` + sql.js | `IN_MEMORY` (demo only) |
| File I/O | stub | stub (demo graph seeded in code) |
| DB dep | `web-worker-driver` + sql.js npm | removed |
| Compose dep | `html-core` (DOM renderer) | removed |
| Output path | `kmp/build/distributions/` | `kmp/build/dist/wasmJs/productionExecutable/` |
| SharedArrayBuffer | not needed | `coi-serviceworker.min.js` required |

---

## Story 1: Switch Gradle target from `js(IR)` to `wasmJs`

### Task 1.1 — Update `kmp/build.gradle.kts`

Replace the `js(IR)` target with `wasmJs`:

```kotlin
// BEFORE
if (project.findProperty("enableJs") == "true") {
    js(IR) {
        browser()
        binaries.executable()
    }
}

// AFTER
if (project.findProperty("enableJs") == "true") {
    wasmJs {
        browser()
        binaries.executable()
    }
}
```

Replace the `jsMain` source set block with `wasmJsMain`. Remove all `js(IR)`-only dependencies:

```kotlin
// BEFORE
val jsMain by getting {
    dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:1.10.2")
        implementation("org.jetbrains.compose.html:html-core:1.7.3")
        implementation("app.cash.sqldelight:web-worker-driver:2.3.2")
        implementation(npm("@cashapp/sqldelight-sqljs-worker", "2.3.2"))
        implementation(npm("sql.js", "1.10.3"))
        implementation(devNpm("copy-webpack-plugin", "9.1.0"))
    }
}

// AFTER
val wasmJsMain by getting {
    dependencies {
        implementation(npm("@sqlite.org/sqlite-wasm", "3.46.1-build2"))
    }
}
```

Removed: `html-core`, `web-worker-driver`, `@cashapp/sqldelight-sqljs-worker`, `sql.js`, `copy-webpack-plugin`.
Added: `@sqlite.org/sqlite-wasm` (needed for Phase B; harmless to add now).

### Task 1.2 — Rename `jsMain` source directory to `wasmJsMain`

```
kmp/src/jsMain/  →  kmp/src/wasmJsMain/
```

Rename the directory. Package declarations are unchanged.

### Task 1.3 — Delete `kmp/webpack.config.d/sqljs-config.js`

This file copies `sql-wasm.wasm` from sql.js. Not needed for `wasmJs`.

---

## Story 2: Update platform-specific implementations

### Task 2.1 — `DriverFactory.wasmJs.kt` — Phase A: IN_MEMORY stub; Phase B: `@sqlite.org/sqlite-wasm`

**Phase A stub** (unblocks canvas rendering):

```kotlin
// kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/DriverFactory.wasmJs.kt
package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver

actual class DriverFactory actual constructor() {
    actual fun init(context: Any) {}
    actual fun createDriver(jdbcUrl: String): SqlDriver {
        // Phase B: replace with @sqlite.org/sqlite-wasm driver
        throw UnsupportedOperationException("Use RepositoryBackend.IN_MEMORY for browser demo")
    }
}

actual val defaultDatabaseUrl: String
    get() = "jdbc:sqlite:stelekit"
```

**Phase B implementation** (full web target): Wire `@sqlite.org/sqlite-wasm` via a dedicated Web Worker. The worker runs SQLite synchronously on the OPFS thread; the main thread communicates via `SharedArrayBuffer` message passing. This requires:

1. A `sqlite-worker.js` Web Worker script that initialises `@sqlite.org/sqlite-wasm` with the synchronous OPFS VFS
2. A Kotlin `JsInterop` wrapper that calls the worker's `exec`/`query` methods from `wasmJs`
3. A custom `SqlDriver` implementation wrapping the interop — executes SQL, maps results to `SqlCursor`

This is a non-trivial integration. The SQLite index is always rebuilt from the markdown files on load (same as the desktop app's cold start), so the OPFS database can be treated as a cache that is safe to discard. Start without OPFS persistence (in-memory `@sqlite.org/sqlite-wasm`), add OPFS once correctness is confirmed.

### Task 2.2 — `PlatformFileSystem.wasmJs.kt` — Phase A: stub; Phase B: `FileSystemDirectoryHandle`

**Phase A stub** (demo graph is seeded in code; no real file access needed):

```kotlin
// Key stubs:
// listFiles(path) → emptyList()
// readFile(path) → null
// writeFile(path, content) → no-op
// exists(path) → false
```

**Phase B implementation** (full web target): Back `PlatformFileSystem` with the File System Access API.

The browser shows a native folder picker (`window.showDirectoryPicker()`); the returned `FileSystemDirectoryHandle` is stored for the session. All `PlatformFileSystem` calls translate to `FileSystemDirectoryHandle.getFileHandle()` / `FileSystemFileHandle.getFile()` / `createWritable()`.

Key considerations:
- The file picker must be triggered by a user gesture (button click) — cannot be called on app startup
- Handle permission persistence: the browser revokes directory access when the tab closes; on reload the user must re-grant (or the app can request persistent permission via `queryPermission`)
- `FileSystemDirectoryHandle` access is async; `PlatformFileSystem` is currently synchronous on JVM. The `wasmJs` implementation will need to bridge this via coroutines (`suspending` calls or a blocking wrapper on the Wasm thread)

The UI flow:
1. App loads → shows "Open your graph folder" button
2. User clicks → browser folder picker appears
3. User selects their Logseq graph directory
4. `GraphManager.addGraph(handle)` indexes the files into SQLite
5. App navigates to journal view

### Task 2.3 — `PlatformSettings.wasmJs.kt` — `localStorage`

Store settings as JSON in `localStorage`. Reads/writes are synchronous; no async bridging needed.

### Task 2.4 — Stub `GitManager.wasmJs.kt`

Return `false` / no-op for all git operations. Git is desktop-only.

### Task 2.5 — Verify remaining platform files compile under `wasmJs`

Expected to require no changes:
- `PlatformDispatcher.wasmJs.kt`
- `PlatformBackHandler.wasmJs.kt`
- `PlatformBottomBar.wasmJs.kt`
- `PlatformClipboardProvider.wasmJs.kt`
- `DynamicColorSupport.wasmJs.kt`
- `DynamicColorScheme.wasmJs.kt`
- `ModifierExtensions.wasmJs.kt`
- `Time.wasmJs.kt`

Run `./gradlew :kmp:compileKotlinWasmJs -PenableJs=true` after each rename to catch issues early.

---

## Story 3: Add Compose canvas entry point

### Task 3.1 — Replace `Main.kt` with `CanvasBasedWindow` entry

```kotlin
// kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt
package dev.stapler.stelekit.browser

import androidx.compose.ui.window.CanvasBasedWindow
import dev.stapler.stelekit.ui.StelekitApp
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.repository.RepositoryBackend

fun main() {
    CanvasBasedWindow(canvasElementId = "ComposeTarget") {
        val graphManager = GraphManager(backend = RepositoryBackend.IN_MEMORY)
        StelekitApp(graphManager = graphManager)
    }
}
```

Check `ui/App.kt` for the exact `StelekitApp` constructor signature and adjust the call site.

### Task 3.2 — Update `index.html` for canvas target

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SteleKit — Try in Browser</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { background: #1e1e1e; }
        #loading {
            position: fixed; inset: 0;
            display: flex; flex-direction: column;
            justify-content: center; align-items: center;
            background: #1e1e1e; color: #ccc;
            font-family: system-ui, sans-serif; z-index: 10;
        }
        #loading.hidden { display: none; }
        canvas#ComposeTarget { display: block; width: 100vw; height: 100vh; }
    </style>
    <!-- Required: patches COOP/COEP headers for SharedArrayBuffer on GitHub Pages -->
    <script src="coi-serviceworker.min.js"></script>
</head>
<body>
    <div id="loading">
        <p style="font-size: 18px; margin-bottom: 8px;">Loading SteleKit…</p>
        <p style="font-size: 13px; color: #888;">Requires Chrome 119+, Firefox 120+, or Safari 18.2+</p>
    </div>
    <canvas id="ComposeTarget"></canvas>
    <script src="skiko.js"></script>
    <script src="kmp.js"></script>
    <script>
        setTimeout(() => document.getElementById('loading').classList.add('hidden'), 5000);
    </script>
</body>
</html>
```

The exact JS filenames (`skiko.js`, `kmp.js`) depend on the Gradle module name — verify against actual output in `build/dist/wasmJs/productionExecutable/` after first build.

### Task 3.3 — Add `coi-serviceworker.min.js` to resources

Download from https://github.com/gzuidhof/coi-serviceworker and place at:
`kmp/src/wasmJsMain/resources/coi-serviceworker.min.js`

This service worker intercepts fetch events and injects COOP/COEP headers, enabling `SharedArrayBuffer` on GitHub Pages (which cannot set HTTP headers directly). It causes a single page reload on first visit — expected behavior.

---

## Story 4: Seed a minimal demo graph

Until the full File System Access API integration ships, the demo loads with an empty graph, which is confusing.

### Task 4.1 — Add a `DemoGraphSeeder` in `wasmJsMain`

Pre-populate the `IN_MEMORY` `RepositorySet` before `StelekitApp` mounts:

- Today's journal entry ("Welcome to SteleKit")
- A "Getting Started" page with nested blocks demonstrating the outliner
- A "Backlinks" page that `[[links]]` to Getting Started, demonstrating bidirectional linking
- A "Block Editing" cheat-sheet page

**Deferred**: Do this after Story 3 confirms the canvas renders.

---

## Build and Test Commands

```bash
# Build wasmJs production bundle
./gradlew :kmp:wasmJsBrowserDistribution -PenableJs=true

# Development run (hot reload at localhost:8080)
./gradlew :kmp:wasmJsBrowserDevelopmentRun -PenableJs=true

# Verify output location
ls kmp/build/dist/wasmJs/productionExecutable/
```

---

## Success Criteria

### Phase A
- `./gradlew :kmp:wasmJsBrowserDistribution -PenableJs=true` completes without errors
- Output exists at `kmp/build/dist/wasmJs/productionExecutable/`
- Opening in Chrome shows the SteleKit Compose UI on a canvas (not a status page)
- No `SharedArrayBuffer` errors in the browser console
- JVM and Android builds are unaffected (`./gradlew jvmTest` still passes)

### Phase B
- User can click "Open graph folder", pick their Logseq directory, and navigate their pages
- Backlinks and search work (SQLite index built from the markdown files)
- Edits to blocks write back to the `.md` files via `FileSystemDirectoryHandle`
- No data loss: closing and reopening the same folder shows the same content

---

## Known Risks

| Risk | Mitigation |
|------|-----------|
| `CanvasBasedWindow` API differs between CMP 1.7.3 and 1.9.x | Check `Kotlin/kotlin-wasm-compose-template` for current API; upgrade CMP if needed |
| `StelekitApp` requires JVM-specific lifecycle APIs at startup | Create a `wasmJsMain`-specific app root that avoids `ViewModel` until the wasmJs lifecycle story is clear |
| `material-icons-extended` bloats bundle by 10–20 MB | Exclude from `wasmJsMain` dependencies; use a curated icon subset |
| `commonMain` code references `java.io.*` or other JVM-only APIs | Compile errors surface these; each needs a `wasmJsMain` `expect/actual` override |
| `FileSystemDirectoryHandle` async API vs synchronous `PlatformFileSystem` contract | Bridge via coroutines; the `wasmJsMain` impl suspends where JVM impl blocks |
| `@sqlite.org/sqlite-wasm` JS interop from `wasmJs` is uncharted territory | Prototype the interop boundary in isolation before wiring to SQLDelight |
| File System Access API not available in Firefox (behind flag) or older Safari | Show a clear "requires Chrome or Edge" message for the folder picker; demo graph mode works everywhere |
| `coi-serviceworker` one-time reload surprises users | Add a comment in the loading screen: "Loading… (may refresh once)" |
