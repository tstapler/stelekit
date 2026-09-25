# ADR-001: Native Canvas `toDataURL('image/jpeg')` for wasmJs JPEG encoding

**Status**: Accepted
**Date**: 2026-09-22
**Context project**: wasm-jpeg-export

## Context

`ImageEncoder.encodeToJpeg` on the `wasmJs` target
([`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.wasmJs.kt`](../../../kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/annotate/ImageEncoder.wasmJs.kt))
is a stub returning `ByteArray(0)`. Compose Multiplatform for wasmJs does not
expose Skia's JPEG encoder to user code, so any fix must cross the
Kotlin/Wasm ↔ JS interop boundary. Three candidate routes were evaluated in
`research/build-vs-buy.md` and `research/stack.md`:

1. Native `canvas.toDataURL('image/jpeg', quality)` (zero new dependency).
2. Bundle the `jpeg-js` npm library (precedent: `@sqlite.org/sqlite-wasm` in
   `kmp/build.gradle.kts:157`).
3. `OffscreenCanvas` + dedicated Web Worker (avoids main-thread blocking,
   mirrors the existing `SqliteWorkerInterop.kt` worker-offload pattern).

This is the first place in the repo that needs a `ByteArray`↔JS-typed-array
bridge — no existing `wasmJsMain` interop file (`OpfsInterop.kt`,
`SqliteWorkerInterop.kt`) marshals raw binary data, only `String`/`JsAny`
handles/primitives.

## Decision

Use **native `canvas.toDataURL('image/jpeg', quality)`**, with pixels pushed
via a single batched `putImageData`-style call (not per-pixel), following the
existing `js("...")` interop convention (`OpfsInterop.kt`). Decode the
resulting data-URL's base64 payload via `kotlin.io.encoding.Base64`
(stable stdlib since Kotlin 2.2, no new dependency).

Rejected: `jpeg-js` — the library's own README tells browser consumers to
prefer the Canvas API; it is synchronous/CPU-blocking pure JS (bad fit for a
3000×2000 export on the main thread) and has had no npm release in ~4 years.

Rejected (for this project): `OffscreenCanvas` + Worker — the repo already
has the COOP/COEP prerequisite (`index.html:18-20`) and the worker-offload
precedent (`SqliteWorkerInterop.kt`), so this remains a viable escalation if
`toDataURL`'s synchronous main-thread call is measured to jank the UI
unacceptably at realistic image sizes — but it is materially more
architecture (message-passing, transferable buffers) than this bugfix's
acceptance criteria require up front. Treated as a documented follow-up, not
built now.

## Consequences

- No new Gradle/npm dependency for the encoding backend itself.
- First-ever `ByteArray`↔JS bulk-binary-data interop in this repo — no
  existing pattern to copy exactly; requires a small spike (plan Task 3.1.1a)
  to confirm whether Kotlin/Wasm 2.3.21 supports direct `ByteArray` parameter
  marshaling across `js("...")` or requires a hand-rolled JS-side bulk-copy
  loop. If direct marshaling is unsupported, the fallback is a JS-side loop
  (not `kotlinx-browser` — see below) to avoid the per-element `JsAny`-boxing
  anti-pattern flagged in `research/pitfalls.md`.
- `toDataURL()`'s exact JPEG output (compression internals) is
  implementation-defined per browser engine — acceptable per acceptance
  criteria (decodable JPEG, not byte-identical output).
- If a future measurement shows `toDataURL` blocking the main thread
  unacceptably at large image sizes, escalate to Worker + `OffscreenCanvas`
  as a separate follow-up (flagged in plan.md Unresolved Questions), not a
  silent scope expansion of this fix.
