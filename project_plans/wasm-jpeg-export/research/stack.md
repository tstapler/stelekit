# Stack research: wasmJs JPEG encoding

## Repo's actual versions (verified, not assumed)

| Component | Version | Source |
|---|---|---|
| Kotlin | 2.3.21 | `settings.gradle.kts:9` |
| Compose Multiplatform plugin | 1.10.3 | `settings.gradle.kts:17` |
| `androidx.compose.ui:ui-graphics` (wasmJs artifact) | 1.10.3 (resolved from Compose plugin) | `~/.gradle/caches/modules-2/.../ui-graphics-wasm-js/1.10.3/` |
| kotlinx-coroutines-core | 1.10.2 | `kmp/build.gradle.kts:60` |
| kotlinx-serialization-json | 1.10.0 | `kmp/build.gradle.kts:62` |
| SQLDelight | 2.3.2 | `settings.gradle.kts:18` |
| wasmJs target config | `wasmJs { browser(); binaries.executable() }`, gated behind `enableJs=true` in `gradle.properties` | `kmp/build.gradle.kts:33-36` |
| Existing npm() precedent | `implementation(npm("@sqlite.org/sqlite-wasm", "3.46.1-build1"))` | `kmp/build.gradle.kts:157` |
| kotlinx-browser | **not currently a dependency** | grepped `kmp/build.gradle.kts` — absent |

## Key finding: pixel extraction needs zero JS interop

Compose Multiplatform's wasmJs target renders via Skia compiled to WebAssembly (Skiko), not the browser's native Canvas2D. I confirmed this concretely: extracting the cached `ui-graphics-wasm-js-1.10.3.klib` (a zip) and grepping its linkdata shows `readPixels` and `toPixelMap` symbols present in the `androidx.compose.ui.graphics` package for the wasmJs target, same as JVM/Android/iOS. This means **Approach 1's first step — `ImageBitmap.toPixelMap()` / `.readPixels(IntArray)` — is a plain common-API call with no JS boundary crossing.** Only the final "push pixels into a canvas and rasterize to JPEG" step needs interop. This directly resolves the "6M interop calls" concern in the requirements doc: the packed-ARGB → RGBA byte reordering happens Kotlin-side (cheap), and only one flat typed-array is handed to JS.

## Kotlin/Wasm JS interop model (current, per kotlinlang.org/docs/wasm-js-interop.html, updated 2026-03-16)

- `external fun ... : Promise<JsAny>` for declared JS functions, or `= js("...")` single-expression-body functions — both are restricted to a fixed type table: primitives ↔ `Number`/`Boolean`/`String`, `Long`/`ULong` ↔ `BigInt`, `JsAny` and subtypes ↔ any JS value, `Unit` ↔ `undefined`. Kotlin `Array`/`List` are **not** directly interop types on wasmJs — use `JsArray<T>` or typed-array conversions instead.
- Async JS calls return `kotlin.js.Promise<JsAny>`, consumed via `kotlinx.coroutines.await()` inside a `suspend fun`.
- Typed-array conversion (`IntArray` ↔ `Int32Array`, etc.) and DOM globals (`window`, `document`) are provided by the separate `org.jetbrains.kotlinx:kotlinx-browser` library (latest referenced in docs: `0.3`), under packages `org.khronos.webgl` and `kotlinx.browser`. **Not currently a dependency in this repo** — the existing interop files hand-roll everything with raw `js("...")` snippets instead of pulling this in.
- `kotlin.io.encoding.Base64` is **stable stdlib since Kotlin 2.2.0** (this repo is on 2.3.21) — available on wasmJs with no extra dependency, no `@OptIn`. Relevant because a canvas `toDataURL()` result is a `"data:image/jpeg;base64,..."` string that needs decoding to `ByteArray`.

## Repo's established interop convention (must follow per requirements.md)

Both existing wasmJs interop files use the same shape — small top-level `private`/`internal` functions wrapping one JS expression each, composed into `suspend fun`s that `.await()`, with failure handled by try/catch at the call site (not exceptions propagating):

- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt:5-17` — `Promise<JsAny>`-returning `js("...")` functions, `internal suspend fun` wrappers.
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/SqliteWorkerInterop.kt:3-52` — same pattern, including a multi-line `js("""...""")` block (lines 53-80) for building a `new Promise(...)` around a `worker.addEventListener`/`postMessage` round trip — useful precedent if the canvas or a Worker needs an event-based bridge.

Neither file uses `@JsModule` or `external class`/`external interface` — those are documented Kotlin/Wasm features but have no precedent in this repo yet.

## Approach 1: hidden `<canvas>` + `toDataURL('image/jpeg', quality)`

- **No new Gradle dependency required.** Everything — `document.createElement('canvas')`, `2d` context, `ImageData`, `putImageData`, `toDataURL` — is a standard Web API reachable via `js("...")` snippets, same convention as `OpfsInterop.kt`.
- Flow: `bitmap.toPixelMap()` (or `readPixels(IntArray)`) → repack the `IntArray` (ARGB) into a flat `ByteArray`/typed array in RGBA byte order on the Kotlin side → **one** interop call to build a `Uint8ClampedArray`/`ImageData` from that flat buffer → **one** `ctx.putImageData(imageData, 0, 0)` call → **one** `canvas.toDataURL('image/jpeg', quality/100)` call → strip the `data:image/jpeg;base64,` prefix → `Base64.decode(...)` (stdlib) to `ByteArray`.
- Optional convenience dependency: `org.jetbrains.kotlinx:kotlinx-browser:0.3` would provide `IntArray.toInt32Array()`-style helpers and `kotlinx.browser.document`, reducing hand-written `js()` boilerplate — but it's a new dependency with no current usage in the repo, so sticking with raw `js("...")` (matching `OpfsInterop`/`SqliteWorkerInterop`) is more consistent with existing convention unless the plan phase decides otherwise.
- Feature-detection matters for acceptance criterion 3 (distinguishable failure): browsers that don't support `'image/jpeg'` in `toDataURL` silently fall back to PNG — the returned data URL's MIME prefix (`data:image/png` vs `data:image/jpeg`) is the concrete, checkable signal to turn into a failure rather than a silent wrong-format success. All evergreen browsers (Chrome/Firefox/Safari) do support JPEG here, so this is a defensive check, not an expected failure path.

## Approach 2: bundle `jpeg-js` (or similar) as an npm dependency

- Latest npm version: **`jpeg-js@0.4.4`**, last published roughly 4 years ago as of Sept 2026 (i.e., low-maintenance/stale) — [npm](https://www.npmjs.com/package/jpeg-js), [GitHub](https://github.com/jpeg-js/jpeg-js).
- The library's own README explicitly steers browser consumers away from it: *"this is a synchronous (i.e. CPU-blocking) library that is much slower than native alternatives... consider using... the Canvas API in the browser"* if a pure-JS implementation isn't required.
- API: `jpeg.encode({data, width, height}, quality) -> {data, width, height}` where `data` is RGBA bytes. The README's own example constructs `data` with Node's `Buffer`; it is **unverified** whether it works against a plain `Uint8Array` in a bundled browser build without a `buffer` polyfill — Webpack 5 (used by Kotlin/Wasm's browser distribution tooling) no longer auto-polyfills Node core modules, so this is an open risk to check before committing to this path, not a confirmed blocker.
- Precedent for the Gradle side: `implementation(npm("@sqlite.org/sqlite-wasm", "3.46.1-build1"))` (`kmp/build.gradle.kts:157`) plus a custom `wasmJsBrowserDistribution` doFirst hook that copies extra runtime files (`kmp/build.gradle.kts:330-350`) — but that copy step exists because sqlite-wasm ships separate `.wasm`/`.mjs` runtime files loaded at runtime via `import.meta.url`. `jpeg-js` is pure JS with no separate runtime asset, so it would need only the `npm(...)` line, not the extra copy-task machinery.
- Calling it from Kotlin requires `@JsModule("jpeg-js")` external declarations (documented Kotlin/Wasm feature) — a pattern with **no precedent** in this repo yet (existing interop only touches global browser APIs via `js("...")`, not a named npm export).
- Because `jpeg.encode` is synchronous, CPU-blocking, pure-JS JPEG encoding, running it on a 3000×2000 image (6M pixels) directly on the main thread risks failing acceptance criterion 2 (no unacceptable UI-thread blocking). The repo already has a Web Worker precedent for offloading CPU-bound wasmJs work (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/SqliteWorkerInterop.kt`, which runs SQLite in a dedicated worker) — mitigating this would mean replicating that worker-offload pattern for jpeg-js, which is materially more architecture than Approach 1 needs.

## Recommendation signal for the planning phase

Approach 1 (canvas + `toDataURL`) needs no new Gradle dependency, follows the existing `js("...")` interop convention exactly, delegates the actual JPEG encoding to the browser's native (fast, non-blocking-relative-to-pure-JS) encoder, and needs no Worker-offload architecture. Approach 2 pulls in a stale, explicitly-not-recommended-for-browser-use library, needs an interop pattern (`@JsModule`) not yet used in this repo, and likely needs Worker-offload to satisfy the performance acceptance criterion. This is a research-stage observation, not a decision — the plan phase should weigh it against any other dimension-2/3/4/5/6 research findings (e.g., testability, bundle size) before choosing.

## Open questions to resolve in planning/implementation

1. Exact byte-repack from Compose's packed-`Int` ARGB `PixelMap`/`IntArray` to canvas `ImageData`'s RGBA `Uint8ClampedArray` byte order — needs a small, verifiable unit conversion, not further stack research.
2. Whether `canvas.toDataURL('image/jpeg', ...)` blocks the main thread unacceptably at 3000×2000 in practice — needs the concrete threshold from requirements acceptance criterion 2 plus a real benchmark, not assumable from stack research alone.
3. (Approach 2 only, if chosen) Whether `jpeg-js`'s `Buffer` usage resolves against a plain `Uint8Array` under this repo's Kotlin/Wasm webpack bundling without extra polyfill config.
