# Requirements: SteleKit Web — OPFS Durable Storage & Local Dev

## Problem Statement

The SteleKit wasmJs build runs in the browser using an in-memory `DemoFileSystem` with hard-coded content and an `IN_MEMORY` repository backend. Data is lost on every page reload. The project plan (see `DriverFactory.js.kt:9`) marks this as "Phase B" — replace with a real `@sqlite.org/sqlite-wasm` driver backed by OPFS. Additionally, there is no single command to build and open the web app locally; developers must know to run Gradle + a separate Node server manually.

## Goals

1. **Durable storage**: Replace `IN_MEMORY` backend with OPFS-backed SQLite so graph data persists across browser sessions.
2. **Local dev convenience**: Provide a single command (script or Gradle task) that builds the wasmJs target and opens a local preview.
3. **Cross-platform verification**: The web app must work on Chrome 119+, Firefox 120+, Safari 18.2+, as stated in `index.html`.

## Out of Scope (this iteration)

- File System Access API folder picker (user's own .md files — deferred to follow-up)
- IndexedDB fallback (OPFS is available in all target browsers since 2023)
- PWA / offline caching

## Functional Requirements

### F1 — OPFS SQLite Driver
- `DriverFactory.js.kt` must return a working `SqlDriver` backed by `@sqlite.org/sqlite-wasm` using the OPFS async VFS.
- The driver must create/open a per-graph SQLite database file in OPFS (`/stelekit/<graphId>.sqlite`).
- SQLDelight-generated queries must work without modification.
- On first launch (no existing OPFS file) the app must bootstrap an empty graph and show today's journal page.

### F2 — Browser Main using SQLDELIGHT backend
- `browser/Main.kt` must switch `defaultBackend` from `GraphBackend.IN_MEMORY` to `GraphBackend.SQLDELIGHT` once the driver is available.
- A real `PlatformFileSystem` (OPFS-backed) must replace `DemoFileSystem` for file reads/writes.

### F3 — Local dev script
- A shell script `scripts/serve-web.sh` (or equivalent Gradle task) that:
  1. Builds `./gradlew :kmp:wasmJsBrowserDistribution -PenableJs=true`
  2. Starts `node e2e/server.mjs` pointing at the build output
  3. Prints the local URL
- Must work on macOS and Linux.

### F4 — CI coverage of OPFS path
- The existing Playwright e2e test must be extended (or a new test added) to verify that data written in one "session" (page load) is visible after a reload — i.e., OPFS persistence is actually exercised.

## Non-Functional Requirements

- **Performance**: Initial OPFS database open must complete within 3 seconds on a modern desktop browser.
- **Compatibility**: OPFS async VFS is required; Chromium, Firefox, and Safari all support it since late 2022.
- **Security**: OPFS is origin-scoped; no cross-origin leakage risk.
- **Error handling**: If OPFS is unavailable (e.g., private browsing with restricted storage), fall back to `IN_MEMORY` with a console warning.

## Constraints

- Kotlin/WASM JS interop is used via `@JsModule` / `external` declarations — no TypeScript build step.
- SQLDelight 2.3.2 is already on the classpath; no version upgrade.
- The `@sqlite.org/sqlite-wasm` npm package must be bundled with the WASM distribution (or loaded via CDN with integrity hash).
- COOP/COEP headers are already set by `server.mjs` and GitHub Pages — `SharedArrayBuffer` is available.

## Acceptance Criteria

| ID | Criterion |
|----|-----------|
| AC1 | `./scripts/serve-web.sh` builds the WASM target and serves it locally in < 2 commands |
| AC2 | Opening the app at `http://localhost:8787`, typing a note, and reloading shows the note still present |
| AC3 | The Playwright e2e suite includes a persistence test that passes in CI |
| AC4 | `./gradlew :kmp:wasmJsBrowserDistribution -PenableJs=true` succeeds without errors |
| AC5 | The app falls back to IN_MEMORY (with a console warning) if OPFS is blocked |

## Current State Mapping

| File | Current State | Required Change |
|------|--------------|-----------------|
| `kmp/src/wasmJsMain/.../db/DriverFactory.js.kt` | Throws UnsupportedOperationException | Return OPFS-backed SqlDriver |
| `kmp/src/wasmJsMain/.../browser/Main.kt` | Uses `IN_MEMORY` + `DemoFileSystem` | Use `SQLDELIGHT` + OPFS FileSystem |
| `kmp/src/wasmJsMain/.../platform/DemoFileSystem.kt` | Hard-coded demo content | Keep for offline fallback; add OPFS-based impl |
| `e2e/tests/demo.spec.ts` | Verifies canvas renders | Add OPFS persistence assertion |
| `scripts/serve-web.sh` | Does not exist | Create |
