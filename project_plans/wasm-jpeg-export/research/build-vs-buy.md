# Build vs. Buy: wasm-jpeg-export

Agent 6 research, SDD Phase 2. Repo Kotlin version: `2.3.21` (`settings.gradle.kts`,
`kotlin("multiplatform") version "2.3.21"`). `enableJs=true` wasmJsMain source set
already exists at `kmp/build.gradle.kts:154-166`, with one `npm()` dependency precedent
(`@sqlite.org/sqlite-wasm`, line 157).

## 1. Existing OSS library / native browser API

### Option A — `canvas.toDataURL('image/jpeg', quality)` (native browser API, zero new dependency)

- **Pros**
  - No new dependency, no bundle-size cost, no supply-chain surface to review or update.
  - Browser support is universal and has been stable since 2015 — [MDN: `toDataURL()`](https://developer.mozilla.org/en-US/docs/Web/API/HTMLCanvasElement/toDataURL) confirms `image/jpeg` output is required-to-support alongside `image/png`, "widely available... since July 2015."
  - JPEG encoding is delegated to the browser engine's own (native/OS- or GPU-backed) encoder — almost certainly faster and more optimized than any pure-JS encoder for this workload.
  - Fits the repo's established `js("...")` external-function interop pattern already used for `OpfsInterop.kt` and `SqliteWorkerInterop.kt` — no new interop idiom to introduce.
  - Follows this repo's global engineering-culture rung (ponytail/YAGNI-leaning CLAUDE.md instructions): reach for the stdlib/native-platform-feature before adding a dependency.
- **Cons**
  - `quality` param interpretation and the exact JPEG output are implementation-defined, not spec-guaranteed — MDN notes "a technically conforming JPEG encoder could choose to replace the whole image with solid black," so byte-for-byte output isn't portable across browsers/versions. In practice all major engines produce reasonable encodes; this is a theoretical/spec-level caveat, not an observed practical failure mode.
  - `toDataURL()` builds the whole encoded image as an in-memory base64 string — MDN recommends `toBlob()` for large images for performance/memory reasons. The plan (Phase 3) should weigh `toBlob()` + `FileReader`/`arrayBuffer()` against `toDataURL()` + base64-decode; both are native, zero-dependency options, so this is an implementation-detail choice, not a build-vs-buy one.
  - Still requires the batched-pixel-write engineering already flagged in `requirements.md` (avoid 6M individual interop calls for a 3000×2000 image) — this cost exists regardless of which encoding backend is chosen, since it's about getting pixels into the `<canvas>`, not about the encoder.
  - Default quality (0.92) and DPI metadata (96dpi) are non-configurable specifics worth noting in the plan, not blockers.
- **Verdict: Recommended.**

### Option B — `jpeg-js` npm library, bundled via `npm()` in `wasmJsMain`

- **Pros**
  - Pure-JS encoder with deterministic, cross-browser-identical output (doesn't depend on browser engine internals).
  - Permissive licensing: decoder is Apache-2.0 (fork of `jpgjs`), encoder is BSD-style (Adobe `as3corelib` port) — no copyleft concerns.
  - Precedent already exists in this repo for `npm()` deps in `wasmJsMain` (`@sqlite.org/sqlite-wasm`).
- **Cons**
  - **Maintenance status is weak.** [npm: jpeg-js](https://www.npmjs.com/package/jpeg-js) shows the latest release, `0.4.4`, was published roughly 4 years ago (per search results); the GitHub repo shows sparse recent commit activity (dependency bumps only, last touched around October 2025) but no corresponding npm release — i.e. no shipped fixes in years even if the repo isn't fully dead.
  - **The library's own README recommends against this exact use case.** From [jpeg-js/jpeg-js](https://github.com/jpeg-js/jpeg-js): *"NOTE: this is a synchronous (i.e. CPU-blocking) library that is much slower than native alternatives. If you don't need a pure javascript implementation, consider using async alternatives like sharp in node or the Canvas API in the browser."* This is a direct, first-party statement that Option A is the better fit for a browser target — the maintainers are pointing users at exactly the alternative this evaluation is comparing against.
  - Synchronous/CPU-blocking encode on a pure-JS implementation is a bad fit for a 3000×2000 export on the main JS thread (the `requirements.md` performance concern) — worse than a native/hardware-backed encoder, not better.
  - Adds a new supply-chain dependency (npm package + its transitive footprint, however small) that this repo's engineering culture explicitly avoids picking up without a specific justifying feature gap. There is no feature gap here: the native API covers the requirement.
- **Verdict: Not recommended** for this use case. Would only make sense if cross-browser byte-identical JPEG output were a hard requirement (it isn't, per `requirements.md` acceptance criteria) or if quality/color-accuracy problems were observed in practice with the native encoder (none reported).

### Note: `@jsquash/jpeg` (wasm-based mozjpeg, not one of the two proposed options)

Surfaced during research as a third alternative (not requested by the backlog item, included for completeness): a WebAssembly build of mozjpeg exposed as an npm package. It would give near-native encode quality with deterministic output, but at the cost of shipping and instantiating a second, unrelated Wasm module inside an app that is itself already a Kotlin/Wasm build — meaningfully heavier than either Option A or B for no benefit this project needs. Not evaluated further; native canvas already satisfies the requirements.

## 2. SaaS / managed API

**Not recommended, and not seriously considered.** SteleKit is a local-first, file-based outliner — `AnnotationExporter.bakeAndEncode()` runs synchronously as part of a client-side "export annotated image" user action, with the source markdown/image files living on local disk or OPFS. Sending image bytes to a cloud encoding API would introduce:

- A network dependency and latency for what is currently an instant, offline-capable local operation.
- A privacy regression — annotated screenshots/images from a personal notes app leaving the device.
- Availability coupling — export would break when offline, contradicting the app's local-first design (see `GraphManager`/`GraphWriter` local file-sync architecture in the top-level `CLAUDE.md`).

No further evaluation performed; ruled out on architectural grounds alone.

## 3. LLM-generated implementation vs. battle-tested library/stdlib

- **Manual JPEG encoding (DCT, quantization, Huffman coding, etc.)** — hand-writing this would be reckless and is not being considered by either proposed option. Both Option A (browser's native encoder) and Option B (`jpeg-js`) delegate the actual JPEG codec to existing, tested implementations. This is correctly out of scope.
- **Base64 encode/decode of the data-URL payload** — the repo's Kotlin version (`2.3.21`) has `kotlin.io.encoding.Base64` **stable since Kotlin 2.2** (confirmed directly on the [Kotlin stdlib API docs](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io.encoding/-base64/): "Since Kotlin — 2.2", no `@OptIn(ExperimentalEncodingApi::class)` needed at 2.3.21). This is part of `kotlin-stdlib-common`, so it works uniformly across all KMP targets including `wasmJs` — no platform-specific interop needed for the decode step. Hand-writing base64 decoding would be pure reinvention; use `Base64.Default.decode(...)` on the data-URL's payload substring.
- **Pixel-format conversion** (`ImageBitmap.toPixelMap()` → `Uint8ClampedArray`/`ImageData`) — this is unavoidable glue code specific to the Compose↔Canvas boundary; no existing library covers this exact conversion, and it's a small, mechanical transform (RGBA byte layout matches `ImageData` directly). Write it by hand, following the existing `js("...")` batched-array interop pattern in `OpfsInterop.kt`/`SqliteWorkerInterop.kt` (single flat-array calls, not per-pixel).

## 4. Fork or adapt existing community code

Searched for existing Kotlin/Wasm or Compose Multiplatform sample code solving `ImageBitmap` → JPEG export on `wasmJs`:

- [`Kotlin/kotlin-wasm-examples` — `compose-imageviewer`](https://github.com/Kotlin/kotlin-wasm-examples/blob/main/compose-imageviewer/README.md) — demonstrates the *inverse* direction (decoding bytes → `ImageBitmap` via `Image.makeFromEncoded(...).toComposeImageBitmap()`), not encoding out to JPEG. Not directly reusable.
- No JetBrains Compose Multiplatform sample repo, Slack thread, or blog post was found that implements `ImageBitmap`-to-JPEG export via canvas on `wasmJs`. This appears to be a gap in public community examples — consistent with `requirements.md`'s own root-cause note that "Compose Multiplatform for wasmJs does not expose Skia's JPEG encoder to user code."
- **Verdict: nothing to fork.** The `js("...")` interop pattern already established in this repo (`OpfsInterop.kt`, `SqliteWorkerInterop.kt`) is the right template to follow; there's no third-party implementation worth adapting instead of writing the (small) canvas-bridge code directly.

## Overall recommendation

**Option 1 (native Canvas 2D route) over Option 2 (`jpeg-js`).**

Reasoning, tied to this repo's actual constraints:

1. **Zero-dependency requirement is met exactly.** The native `canvas.toDataURL('image/jpeg', quality)` API satisfies every acceptance criterion in `requirements.md` (non-empty valid JPEG, reasonable performance, distinguishable failure) without adding a package. That's the top rung of this repo's own dependency ladder (global CLAUDE.md: prefer stdlib/native platform features before a new dependency), and it's directly satisfiable here — this isn't a case where the native option is a stretch fit.
2. **The candidate "buy" option's own maintainers point at the "build"/native option for browsers.** `jpeg-js`'s README explicitly tells browser users to prefer the Canvas API instead of their synchronous pure-JS encoder. Adopting `jpeg-js` here would mean choosing the option its own authors say is worse for this exact context (browser, not Node.js).
3. **Performance profile favors native.** `jpeg-js` is documented as synchronous/CPU-blocking; a 3000×2000 export (`requirements.md`'s stated realistic size) on a blocking pure-JS encoder risks the "unacceptable UI-thread stall" the acceptance criteria are explicitly trying to avoid. The browser's native encoder is hardware/OS-backed and runs off the JS main thread's interpreted-encoding cost entirely.
4. **Maintenance risk is asymmetric.** `jpeg-js` has had no npm release in ~4 years; the native canvas API is a maintained, versioned browser platform surface with universal support since 2015. Depending on `jpeg-js` adds an unmaintained link to the dependency chain for no capability the platform doesn't already provide.
5. **Interop cost is comparable either way.** Both options require the same batched-pixel-write engineering (`toPixelMap()` → flat array → single JS call) to avoid the 6M-interop-call problem flagged in `requirements.md`; Option 2 doesn't save that work, it only adds an additional encode step on top of it.

The one caveat worth carrying into Phase 3 (plan): decide `toDataURL()` + base64-decode vs. `toBlob()` + `arrayBuffer()` for the byte-extraction step — both are native/zero-dependency, so this is an implementation detail, not a build-vs-buy question, but `toBlob()` avoids the base64 string-size overhead MDN flags for large images and may be worth the (slightly more involved) async `Promise`-based interop.
