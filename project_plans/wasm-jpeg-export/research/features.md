# Research: Features — wasm-jpeg-export

Agent 2 (Features research), SDD Phase 2.

## 1. Existing export/encoding features and the conventions they establish

### Current state of `ImageEncoder.encodeToJpeg` callers — blast radius is smaller than it looks

`bakeAndEncode` / `ImageEncoder.encodeToJpeg` currently have **zero non-test call sites** anywhere
in `commonMain`, `androidMain`, or `jvmMain` (`grep -rn "bakeAndEncode\|AnnotationExporter\."
kmp/src --include="*.kt"` matches only `DriveExportService`'s doc comments and
`AnnotationExporterTest.kt`). `DriveExportService.exportToDrive` similarly has no callers outside
its own file. So today the wasmJs stub cannot actually be hit through any wired UI flow — the bug
is real (per the backlog item) but the feature is still mid-rollout; the "Export to Drive" UI
(plan.md Task 7.4.1) and any local "Save/Share annotated image" button apparently haven't landed
yet, or the wiring lives in an in-progress branch not visible here. **Implication for the plan**:
this fix should not assume there's a battle-tested UI error-surfacing path to reuse — it needs to
either wire a minimal caller (to have something to manually verify against) or the acceptance
criteria will be validated purely through tests + the two other platforms' proven pattern.

### The `Either<DomainError, T>` convention, concretely

Two real precedents in this codebase show how encode/export-like operations should signal
failure, per this repo's CLAUDE.md Arrow rule:

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/google/DriveExportService.kt:111` —
  `exportToDrive(...): Either<DomainError, DriveExportResult>`. Wraps the underlying
  `DriveUploader.uploadFile` Either, converting failures into
  `DomainError.NetworkError.HttpError(statusCode, message = "Drive upload ... failed: ${err.message}")`
  — i.e. re-wraps a lower-level error with added context rather than passing it through raw.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ShareProvider.kt:39` — `saveToFile(...):
  Either<DomainError, Boolean>`, with an explicit **three-way contract** documented in the
  KDoc: `Right(true)` = saved, `Right(false)` = user cancelled (not an error), `Left(...)` = write
  failure. This is the right shape to mirror for JPEG encoding, since "browser Canvas API blocked"
  and "quality param invalid" are true errors, but there's no analogous "user cancelled" case for
  a pure encode step — a plain `Either<DomainError, ByteArray>` should suffice.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt:104-107` — there is
  already a `sealed interface ExportError : DomainError` with `SerializationFailed`,
  `ClipboardFailed`, `ShareFailed`. **No `EncodingFailed`/`ImageEncodingFailed` case exists yet.**
  Adding one here (e.g. `ExportError.EncodingFailed(override val message: String)`) is the natural,
  idiomatic place to signal "canvas/JS API unavailable" or "encode threw" — consistent with how
  `ClipboardFailed`/`ShareFailed` already model other export-adjacent JS/OS-boundary failures.

**Consequence for the `expect`/`actual` signature**: today `encodeToJpeg` returns a bare
`ByteArray` with "empty array on failure" as the *documented* contract on all three real platforms
(`ImageEncoder.kt:17`, `.jvm.kt`, `.android.kt`) — this is exactly the anti-pattern the repo's
Arrow rule prohibits ("Do not use ... nullable returns ... for domain errors at repository
boundaries"), though `ImageEncoder` sits in `ui/annotate`, not `repository/`, so it's arguably
outside the letter of that rule but squarely inside its spirit. Fixing the wasmJs stub to
distinguish failure from success (acceptance criterion 3) most likely requires changing the
`expect` signature to `Either<DomainError, ByteArray>` — which is a breaking change across all
four `actual` implementations (JVM, Android, iOS stub, wasmJs) and their one caller in
`AnnotationExporter.bakeAndEncode`. That call site currently also returns bare `ByteArray` and its
doc says "or empty array on failure" (`AnnotationExporter.kt:60`) — the same anti-pattern
propagates up. Whether to fix the contract at the `ImageEncoder` boundary only, or thread `Either`
all the way through `bakeAndEncode` too, is a real design decision for Phase 3 (plan), not
something to default silently.

### JS interop convention for wasmJs (directly reusable for the Canvas 2D route)

`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt` is the cleanest existing
template for exactly this kind of work: small `private fun ...(): Promise<JsAny> =
js("...")` wrappers around each individual JS call, composed into an `internal suspend fun` that
`.await()`s them and wraps the whole sequence in `try { } catch (e: Throwable) { ... }` — on
failure it logs (`println("[SteleKit] OPFS write failed for $path: ${e.message}")`) and returns a
sentinel rather than propagating. `db/SqliteWorkerInterop.kt` shows the same low-level
`js("...")`-per-primitive style for building/reading raw JS message objects and arrays
(`buildExecMessage`, `getMessageRows`, `jsArrayPushDouble`, etc.) — this is the pattern to follow
for building the `ImageData`/`Uint8ClampedArray` pixel buffer and calling `canvas.toDataURL(...)`.

**No existing Canvas/Blob/toDataURL/ImageData usage anywhere in the repo** (`grep -rn
"canvas|Blob|toDataURL|getContext|createObjectURL|ImageData" kmp/src/wasmJsMain` — zero matches).
This is genuinely greenfield JS interop; there's no partial implementation to build on, only the
stub and the doc-comment's own suggestion ("Full implementation would use a Canvas 2D API or
wasm-based encoder").

**Worker-offload precedent** (directly relevant to acceptance criterion 2 — "completes without
blocking the UI thread for an unacceptable duration"): `db/SqliteWorkerInterop.kt` +
`db/WasmOpfsSqlDriver.kt` already run all SQLite work in a dedicated Web Worker
(`createSqliteWorker`, `workerPostMessage`) rather than on the main thread, precisely because
synchronous heavy work would jank the UI. `canvas.toDataURL('image/jpeg', ...)` on a 3000×2000
image is a similarly heavy synchronous main-thread call (`OffscreenCanvas` + a dedicated Worker is
the standard way to avoid blocking) — Phase 3 should decide explicitly whether to accept a main-
thread `toDataURL` call (simpler, likely "fast enough" for one-shot export, matches how `sqlite-
wasm`'s *query* path still runs sync operations relayed through a worker channel) or replicate the
worker-offload pattern for the encode step. There is no forced answer here in the codebase, just
precedent that "blocking-call-in-a-worker" is the established solution when this project has
previously hit the same problem shape.

**npm-dependency precedent** (relevant to Approach 2 in requirements.md — "JS JPEG encoder
library"): `kmp/build.gradle.kts` already adds `implementation(npm("@sqlite.org/sqlite-wasm",
"3.46.1-build1"))` to the `wasmJsMain` source set (line ~156). So bundling a JS library like
`jpeg-js` via `npm(...)` is not a novel pattern for this repo — it's the same mechanism already in
production use for `sqlite-wasm`. This lowers the practical risk/cost of Approach 2 relative to
what the requirements doc implies ("only if the npm-library route is chosen" reads as a bigger
lift than it actually is, given the existing precedent).

### Share/save UX convention (JVM/Android), for "download vs. share target on web"

- **JVM** (`ui/PlatformShareProvider.jvm.kt`): `saveToFile` shows a native AWT `FileDialog` on the
  EDT — i.e., "save" always means "user picks a destination path," no default silent-download
  behavior.
- **Android** (`ui/PlatformShareProvider.android.kt:55-90`): `saveToFile` writes to a temp file in
  `cacheDir`, then re-purposes the **share sheet** (`Intent.ACTION_SEND` + `FileProvider`) as the
  save mechanism — explicit comment explains this is "simpler than SAF ACTION_CREATE_DOCUMENT
  which requires Activity result callbacks." So even "save" on Android is implemented as "share,"
  reusing one code path for both intents.
- **wasmJs** (`ui/PlatformShareProvider.js.kt`): `WasmJsShareProvider` is **also entirely a no-op
  stub** today — `shareText`/`shareHtml` do nothing, `saveToFile` unconditionally returns
  `Right(false)` (silently reports "user cancelled" with a passing comment "No-op: WASM/JS
  target"). This is the second silently-broken web export path in the same feature area, and its
  `Right(false)` return is itself a minor smell: it overloads "cancelled" to also mean
  "unimplemented," which a caller cannot distinguish from a real user cancel.

**Implication**: on web, the standard/expected mechanism for "save this generated file" is
triggering a browser download via a synthesized `<a download>` + `URL.createObjectURL(blob)`
click, or opening a new tab/`window.open` to a data URL — neither exists anywhere in this
codebase yet. If this fix produces `ByteArray` and leaves delivery-to-user to a caller (matching
how JVM/Android separate `ImageEncoder` encode-only from `ShareProvider` save/share), then the
*this* backlog item's scope is genuinely encode-only and `WasmJsShareProvider.saveToFile` staying
a stub is a separate, pre-existing gap — worth flagging explicitly as related-but-out-of-scope
rather than silently expanding this fix to also wire browser downloads.

## 2. Edge cases and failure modes to handle

Ranked by how directly the codebase already signals each is a real concern:

1. **Alpha channel — JPEG has none.** The JVM implementation already handles this explicitly:
   `ImageEncoder.jvm.kt:19-26` converts to `BufferedImage.TYPE_INT_RGB` (not `TYPE_INT_ARGB`)
   specifically because "JPEG does not support alpha" (comment at line 18). `ImageData` written to
   a canvas is always RGBA (`Uint8ClampedArray` with 4 bytes/pixel); `canvas.toDataURL('image/jpeg')`
   already flattens alpha against an opaque (white, per spec) background automatically, so the
   canvas route gets this for free — but it's worth a regression test asserting a fully-transparent
   source `ImageBitmap` doesn't come back as pure black/corrupt (an easy mistake if pixels are
   pre-multiplied incorrectly when copied into the `Uint8ClampedArray`).
2. **Quality parameter edge values.** Both JVM (`quality.coerceIn(0, 100)` then `/ 100f`) and
   Android (`quality.coerceIn(0, 100)` passed straight to `Bitmap.compress`) clamp defensively even
   though the `expect` doc says "0–100." The wasmJs `actual` should do the same
   (`quality.coerceIn(0, 100)` before mapping into the `0.0–1.0` range `toDataURL` expects) —
   otherwise an out-of-range caller value silently produces browser-implementation-defined
   behavior instead of the same defensive clamp both sibling platforms apply.
3. **Zero-size / degenerate bitmaps.** Neither JVM nor Android's implementation has an explicit
   guard for `width == 0 || height == 0` — they rely on the underlying library throwing, caught by
   the blanket `catch (e: Exception) { ByteArray(0) }`. `AnnotationExporter.bakeAnnotations`
   (`AnnotationExporter.kt:37-39`) always sizes the output `ImageBitmap` to match `sourceImage`, so
   a zero-size source is possible only if upstream image loading already produced one — still worth
   a defensive check before the JS call, since a 0×0 `ImageData` is a JS `RangeError`/throws
   differently across browsers rather than a clean catchable exception.
4. **Huge images / memory pressure.** Requirement doc's own 3000×2000 example is ~24MB raw RGBA
   (3000×2000×4 bytes) that must round-trip through a `Uint8ClampedArray` copy into `ImageData`,
   then a second internal browser-side JPEG-encode buffer, then a base64-encoded data URL string
   (which is ~33% larger again) before being decoded back to `ByteArray` in Kotlin. That's at least
   3 large buffers alive simultaneously purely from the *encoding* step, on top of whatever the
   compositing step (`bakeAnnotations`) already allocated. No existing wasmJs code moves comparable
   volumes of binary data across the JS/Kotlin boundary in one call — `SqliteWorkerInterop`'s
   `postMessage` payloads are query results (rows/columns), not multi-megabyte binary blobs. This
   is the single largest unproven part of either proposed approach and should get an explicit perf
   check (matching this project's existing benchmark harness convention — see `./gradlew
   :kmp:jvmTestProfile` in `CLAUDE.md` — though that's JVM-only today, not wasmJs).
5. **Canvas 2D API unavailable/blocked.** Realistic in a WASM context that already needs
   cross-origin isolation for SQLite (`kmp/src/wasmJsMain/resources/coi-serviceworker.min.js` exists
   specifically to satisfy `SharedArrayBuffer` COOP/COEP requirements) — an environment locked down
   enough to break `SharedArrayBuffer` might plausibly also restrict canvas fingerprinting-surface
   APIs via browser extensions (e.g., privacy/anti-fingerprinting tools that patch
   `HTMLCanvasElement.prototype.toDataURL` to throw or return blank data). This must map to the new
   `ExportError.EncodingFailed`-style `Left`, not a silent empty array (the current bug).
6. **Concurrent export calls.** `ImageEncoder` is a stateless `expect object` — no shared mutable
   state exists in the JVM/Android encoders, so concurrent calls are already safe there. A
   canvas-based wasmJs implementation must preserve that: if it uses one shared hidden `<canvas>`
   element (simplest implementation), concurrent `bakeAndEncode` calls would race on that element's
   dimensions/content. Either create a fresh `<canvas>` per call (simple, some GC churn) or
   serialize access (e.g. a `Mutex`) — the per-call-fresh-canvas approach matches this codebase's
   general preference for stateless/pure functions in `ui/annotate/*` and avoids adding
   synchronization machinery for what should be a rare, user-initiated action.

## 3. Unstated user needs

- **Progress feedback for large exports.** `DriveExportService`'s own doc comment already
  anticipates this pattern one layer up: "For files > 5 MB: callers should show progress UI... the
  upload itself is a single blocking suspend call; WorkManager integration for large files is
  handled in the ViewModel" (`DriveExportService.kt:98-99`). The JPEG-encode step is the same shape
  of problem (a single suspend/blocking call with no intermediate progress signal) and sits
  upstream of that exact upload. If encoding itself takes long enough to need a Worker per edge
  case #4 above, the UI needs *some* "encoding..." indicator during that window — today there is no
  hook for that (encode returns a flat `ByteArray`/would-be `Either`, not a `Flow<Progress>`).
  Given the existing `AnnotationEditorScreen.kt` is the only realistic caller and there's no
  wired UI yet (see §1), this is a reasonable moment to decide whether progress reporting belongs
  in this fix's scope or is deferred — the requirements doc's acceptance criteria don't mention it,
  but criterion 2 ("without blocking the UI thread for an unacceptable duration") implicitly assumes
  *some* answer to "what does the user see while waiting."
- **Download vs. clipboard vs. share target on web.** As established in §1, `bakeAndEncode`
  returning a `ByteArray` is encode-only; delivering it to the user (browser download link, "Save
  As" dialog via the File System Access API, or copy-to-clipboard as an image) is
  `WasmJsShareProvider`'s job, and that provider is currently a total no-op on every method
  (`shareText`, `shareHtml`, `saveToFile`) — not just for images. Users on web genuinely have no way
  today to get *any* exported content out of the app, image or otherwise. This fix, scoped strictly
  to `ImageEncoder`, will make the byte array correct but still undeliverable end-to-end on wasmJs
  unless `WasmJsShareProvider` is also addressed — either in this same project or as an explicitly
  logged follow-up, since otherwise "fixed" JPEG bytes still can't reach the user and the bug will
  appear unresolved from a user's perspective even after this fix ships.
- **Distinguishing "unimplemented" from "user cancelled."** Called out in §1:
  `WasmJsShareProvider.saveToFile` returning `Right(false)` for "not implemented" is
  indistinguishable from a real cancel. If this project's scope does end up touching
  `WasmJsShareProvider` (e.g., to actually deliver the encoded JPEG), that return value should
  become a real `Left(DomainError.ExportError.ShareFailed(...))` rather than continuing to overload
  the cancel signal.

## Key files referenced

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.kt` — `expect` contract
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationExporter.kt` — caller,
  same "empty array on failure" contract propagates to `bakeAndEncode` (line 60)
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.jvm.kt` — reference
  implementation, alpha-channel handling (lines 18-26), quality clamping (line 31)
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.android.kt` — reference
  implementation, quality clamping (line 16)
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.wasmJs.kt` — the stub
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt:104-107` — `ExportError`
  sealed interface, where a new `EncodingFailed` case belongs
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ShareProvider.kt` — `Either<DomainError,
  Boolean>` three-way save contract to mirror
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/google/DriveExportService.kt` —
  `Either`-wrapping convention (lines 111, 128-136) and the "progress UI for >5MB" precedent
  (lines 98-99)
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.js.kt` — the second,
  fully no-op web export path (`saveToFile` returns `Right(false)` unconditionally)
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt` — JS interop style
  template (`private fun ... = js("...")` + suspend wrapper + try/catch)
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/SqliteWorkerInterop.kt` — low-level
  `js("...")`-per-primitive style, Worker-offload precedent for heavy work
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/WasmOpfsSqlDriver.kt` — pairs with
  `SqliteWorkerInterop.kt` to show the full Worker-based heavy-operation pattern
- `kmp/build.gradle.kts:34-38,155-165` — `wasmJs { browser() }` target config (browser()-mode test
  runner, relevant to criterion 5's regression test), existing `npm("@sqlite.org/sqlite-wasm", ...)`
  precedent for the Approach 2 npm-library route
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/annotate/AnnotationExporterTest.kt` — existing
  regression-test pattern for `bakeAndEncode`/`ImageEncoder` (JPEG magic-byte assertion, non-empty
  assertion) to mirror in a new wasmJsTest
- `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/benchmark/WasmBenchmarkTest.kt` — only existing
  wasmJsTest file; establishes that a wasmJs test source set and `kotlin("test-wasm-js")` dependency
  already exist and are exercised in CI via `browser()`
