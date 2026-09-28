# Build vs. Buy: wasm-image-drop

Agent 6 — Phase 2 research. Evaluates each of the three pieces the requirements scope
identifies: (1) JS drag-and-drop capture on the Compose-for-Web canvas, (2) reading dropped
`File` bytes into Kotlin, (3) writing those bytes to OPFS via a new `WasmMediaAttachmentService`.

## 1. Existing OSS library/framework

### 1a. Compose Multiplatform's own `dragAndDropTarget`/`dragAndDropSource` for wasmJs

**Pinned version in this repo:** `org.jetbrains.compose` plugin `1.10.3`
(`settings.gradle.kts:17`), Kotlin `2.3.21` (`settings.gradle.kts:9`).

Findings, most-recent-first:

- The official Kotlin docs page [Drag-and-drop operations](https://kotlinlang.org/docs/multiplatform/compose-drag-drop.html)
  (dated 22 June 2026, i.e. after this repo's pin) documents a Web code path: wrap outbound
  data in a `DataTransfer` via `createDataTransfer()`. But the "Creating a drop target"
  section — the half this project actually needs — only shows a **Desktop** example
  (`event.awtTransferable`, AWT `DataFlavor`). There is no Web/wasmJs example anywhere on
  that page for receiving a drop. That silence is itself evidence: JetBrains documents Web
  drag-*source* (dragging Compose content out) but not Web drop-*target* (receiving an OS
  file drop) in their own current-as-of-June-2026 reference doc.
- A firsthand report — ["How to implement Drag and Drop in Kotlin Multiplatform"](https://medium.com/google-developer-experts/how-to-implement-drag-and-drop-in-kotlin-multiplatform-5f00937545de)
  by a Kotlin Google Developer Expert, dated **30 March 2026**, using **compose.ui 1.10.1**
  (one patch below this repo's pinned 1.10.3, released ~19 March 2026 per the
  ["What's new in 1.10.3"](https://kotlinlang.org/docs/multiplatform/whats-new-compose-110.html)
  page) — states plainly: "The official documentation ... serves as a starting guide, but
  you'll quickly note that it simply doesn't compile," and that `DragAndDropTransferData`
  and `DragAndDropEvent` are `expect`-only types requiring hand-written platform `actual`s.
  The article walks through JVM, Android, and iOS/JS `actual` implementations — **it never
  mentions wasmJs** as a working target.
- The original tracking issue,
  [`JetBrains/compose-multiplatform#4235`](https://github.com/JetBrains/compose-multiplatform/issues/4235)
  ("Support `Modifier.dragAndDropSource` and `Modifier.dragAndDropTarget`"), was closed in
  favor of `CMP-4235` on YouTrack; the GitHub thread's own history shows the modifiers landed
  for **Desktop first** (targeted for the CMP 1.7 release, 2024), with Android/iOS following.
  No comment in that thread claims Web/wasmJs drop-target parity.
- This repo's own `PageDropTargetModifier.kt:9-16` doc comment and the requirements.md
  root-cause section independently concluded the same thing: "there is no supported Compose
  WASM API to intercept these events."

**Conclusion:** as of Compose Multiplatform 1.10.3 (this repo's pin), a working, documented
`dragAndDropTarget` implementation for wasmJs that captures **OS-external file drops** (as
opposed to in-app composable-to-composable drag) does not exist. The common-API surface
exists (`expect fun Modifier.dragAndDropTarget(...)`), but the wasmJs `actual` either doesn't
receive OS file drops, isn't documented, or (per the March 2026 firsthand account) doesn't
compile against the official sample. Revisit at the next Compose Multiplatform upgrade — the
June 2026 docs update suggests JetBrains is actively filling this gap — but don't block this
project on it.

- **Pros:** if/when it lands, it's the "correct" cross-platform abstraction — no manual JS
  interop, same `Modifier.pageDropTarget` call site as JVM.
- **Cons:** not proven to work for the OS-file-drop-into-web-canvas case at the repo's pinned
  version; the one hands-on report found says the vendor's own sample doesn't compile at a
  version one patch below the pin.
- **Verdict: Not recommended** (for this project, at this pin). Hand-roll the JS `drop`
  listener per the requirements' original proposal instead.

### 1b. General-purpose Kotlin/Wasm DnD interop libraries

No dedicated "drag-and-drop-to-Kotlin/Wasm" helper library was found (searched GitHub code
search and general web search). `MohamedRejeb/compose-dnd` exists but implements in-app
reorderable/draggable-item DnD via `pointerInput`/`detectDragGestures` for Compose UI, not
OS-file-drop capture — wrong problem shape.

- **Verdict: Not recommended** — nothing purpose-built exists to adopt.

### 1c. `kotlinx-browser` for DOM typing

[`Kotlin/kotlinx-browser`](https://github.com/Kotlin/kotlinx-browser) (JetBrains-owned,
currently at 0.5.0, requires Kotlin ≥2.2.20-Beta2 — compatible with this repo's Kotlin
2.3.21) provides typed `external`/`expect`-`actual` bindings for the exact DOM surface this
feature needs, confirmed by reading the library's source directly:

- `org.w3c.dom.DataTransfer` (`wasmJsMain/kotlin/org.w3c/org.w3c.dom.kt`)
- `org.w3c.files.File`, `FileList`, `FileReader` including
  `FileReader.readAsArrayBuffer(blob: Blob): ArrayBuffer` and `Blob.arrayBuffer(): Promise<ArrayBuffer>`
  (`wasmJsMain/kotlin/org.w3c/org.w3c.files.kt`)
- `HTMLElement.ondrop: ((DragEvent) -> Unit)?` typed event property
  (`wasmJsMain/kotlin/org.w3c/org.w3c.dom.kt`)
- `org.khronos.webgl.Int8Array.toByteArray(): ByteArray` — a ready-made, tested
  `ArrayBuffer`→`ByteArray` conversion (`wasmJsMain/kotlin/arrayCopy.wasm.kt`), better than a
  hand-rolled loop for that specific step.

What it does **not** have: any Origin Private File System (OPFS) bindings.
`FileSystemDirectoryHandle`, `getDirectoryHandle`, `createWritable`, etc. do not appear
anywhere in the repo (`gh search code` for `FileSystemDirectoryHandle` / `getDirectory`
against `Kotlin/kotlinx-browser` returns zero hits). This matches why this repo's own
`OpfsInterop.kt` hand-rolls those calls via raw `js("...")` strings — there's nothing to
adopt there.

**This repo is not currently a `kotlinx-browser` consumer** — no reference to it exists in
`kmp/build.gradle.kts` or anywhere in the tree; all existing wasmJs interop
(`OpfsInterop.kt`, `SqliteWorkerInterop.kt`) is hand-rolled `external fun ... = js("...")`.
Adopting `kotlinx-browser` would be a net-new dependency plus a style departure from the
existing pattern, for a library whose own docs mark it experimental/API-subject-to-change.

- **Pros:** typed `DataTransfer`/`File`/`FileReader`/`Int8Array.toByteArray()` bindings save
  hand-writing `external fun` declarations for the drop-event and byte-read halves of this
  feature (pieces 1 and 2); JetBrains-maintained, matches the Kotlin stdlib's own package
  namespacing (`org.w3c.dom`, `org.w3c.files`) so it reads as "the standard bindings," not a
  third-party dependency.
  - Cons: new dependency for a repo that currently has zero `kotlinx-browser` usage; explicitly
  experimental/unstable API surface; doesn't cover OPFS at all, so the repo's own hand-rolled
  `js()` pattern is still required for piece 3 regardless — using `kotlinx-browser` for pieces
  1–2 only would leave two different interop styles (typed library calls vs. raw `js()`
  strings) side by side in the same feature.
- **Verdict: Viable, but marginal.** The existing repo convention (raw `external fun ... =
  js("...")`, as in `OpfsInterop.kt` / `SqliteWorkerInterop.kt`) already covers everything
  `kotlinx-browser` would provide for this feature's scope (a handful of `DataTransfer`/`File`
  reads and one `ArrayBuffer`→`ByteArray` conversion), with zero new dependencies and full
  consistency with the surrounding code. Pulling in `kotlinx-browser` for ~5 typed
  declarations, when OPFS still needs hand-rolling anyway, is not clearly worth the
  inconsistency. Lean toward the existing hand-rolled pattern; note `kotlinx-browser` as an
  option the plan phase can pick up if the hand-rolled interop surface grows larger than
  expected.

## 2. SaaS/managed API (Uppy.js or similar upload widget)

Not applicable in the traditional SaaS sense (no network service to call), but the ticket
implicitly raises the question of using an existing JS upload-widget library instead of raw
DOM events.

- Uppy's core bundle alone is ~500KB minified (per
  [transloadit/uppy#2558](https://github.com/transloadit/uppy/issues/2558) and Bundlephobia
  listings for `@uppy/core`); it ships a full UI (drop zones, progress bars, dashboards,
  plugin system for remote sources like Google Drive/Instagram) — none of which this feature
  needs. It's designed to be *the* upload UI, not a drop-event-capture primitive to wrap.
- This app already renders its own UI via Compose canvas; there is no DOM to mount an Uppy
  dashboard into without fighting the single-canvas rendering model Compose-for-Web uses.
  Wrapping Uppy would mean interop with its event-emitter API and either hiding its UI
  entirely (using only its file-acquisition internals) or accepting a second, non-Compose UI
  layer bolted onto the canvas — both add substantially more complexity than the ~30-40 lines
  of `drop`/`dragover`/`dragenter` listener code the requirements doc's own proposed fix
  describes.
- The actual problem — listen for `drop`, read `event.dataTransfer.files`, call
  `file.arrayBuffer()` — is three native browser APIs with broad, stable support; it doesn't
  need a library to normalize cross-browser quirks the way Uppy exists to normalize upload
  *protocols* (tus, S3 multipart, XHR).

- **Verdict: Not recommended.** Bundle size and architectural mismatch (full upload-widget UI
  vs. a canvas-only Compose app) far outweigh any convenience; this is a small, well-scoped
  native-browser-API problem that a managed widget is the wrong shape for.

## 3. LLM-generated implementation vs. battle-tested pattern (ArrayBuffer→ByteArray, OPFS write)

This repo already solved the general "call an async JS API from Kotlin/Wasm and get data
back" problem twice:

- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt` — `Promise<JsAny>`-returning
  `external fun ... = js(...)` helpers, `.await()`ed from `suspend fun`s
  (`getOpfsRoot`, `getDirectoryHandle`, `getFileHandle`, `opfsWriteFile`, `opfsDeleteFile`).
  `opfsWriteFile` already does root→directory-walk→`getFileHandle(create=true)`→
  `createWritable()`→`write()`→`close()`, string-content only today.
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/SqliteWorkerInterop.kt` — same
  `external fun = js(...)` + `Promise.await()` pattern, plus hand-rolled `Promise` construction
  via `js("""new Promise(...)""")` for worker-message round-trips.

Both are **in-repo, tested-in-production patterns** (this app ships with them today) for
exactly the shape of problem pieces 2 and 3 present: call a JS async API, get a result back
into Kotlin coroutines. Reusing this pattern for (a) `File.arrayBuffer()` →
`.await()` → `Int8Array`/raw bytes, and (b) extending `opfsWriteFile` (or adding a sibling
`opfsWriteFileBytes`) to accept a `ByteArray`/`Uint8Array` instead of a `String`, is "adopt
battle-tested" even though the battle-tested thing is in-repo rather than an external
dependency — it's the same risk profile as using a library, minus the version-pinning and
supply-chain surface.

For the `ArrayBuffer`→`ByteArray` conversion specifically: a hand-written loop
(`ByteArray(int8Array.length) { int8Array[it] }`) is ~3 lines and trivially correct, matching
what `kotlinx-browser`'s own `Int8Array.toByteArray()` does internally (confirmed by reading
its source, section 1c above) — so even without adding the dependency, the "battle-tested
pattern" is just copying that one function's implementation, which is small enough that
LLM-assisted authorship carries negligible risk as long as it's covered by a unit test
(a `businessTest`/`jvmTest` exercising round-trip `ByteArray → Int8Array → ByteArray` isn't
directly meaningful off-JVM, but a test against the real `writeFileBytes`/`readFileBytes`
round-trip on wasmJs, if a wasm test task exists, would catch a wrong-endianness or
off-by-one bug).

- **Verdict: Recommended** — extend `OpfsInterop.kt` (or add a narrowly-scoped sibling file)
  following its existing `external fun = js(...)` + `suspend/.await()` pattern rather than
  inventing a new interop style. Treat this as "reuse the in-repo battle-tested pattern," not
  "write fresh interop code from scratch." The `ArrayBuffer`→`ByteArray` conversion itself is
  small enough (and mirrors `kotlinx-browser`'s own implementation) that hand-writing it is
  low-risk, provided it gets a round-trip test.

## 4. Fork or adapt an existing reference implementation

- The closest public reference found is Manel Martos Roldán's **LottieViewer**
  ([blog post](https://medium.com/@mmartosdev/web-based-drag-and-drop-in-compose-multiplatform-b4d7e2a0529d),
  repo `manuel-martos/LottieViewer` on GitHub), a Compose Multiplatform desktop app "ported"
  to add Kotlin/Wasm web support with drag-and-drop for Lottie animation files. The blog post
  (2024, Medium member-only past the intro) covers adding the wasmJs target and using "Web
  APIs" for drag-and-drop, which is directionally the same problem (drop a file onto a
  Compose-for-Web canvas, read its bytes). It predates the more recent (2026) Compose
  Multiplatform `dragAndDropTarget` Web attempts referenced in section 1a, so it likely also
  hand-rolls the JS `drop` listener rather than using the (at-the-time-nonexistent) common API
  — consistent with what this project needs to do.
- No other close, actively-maintained Kotlin/Wasm HTML5-drag-drop reference repo turned up in
  GitHub code search (`ondrop` + Kotlin) beyond in-app Compose-DnD libraries
  (`MohamedRejeb/compose-dnd`) and unrelated desktop AWT `DragAndDropTarget` usages
  (`tangshimin/MuJing`, `wkbin/AdbFileManager`) — both JVM/desktop, not wasmJs.
- Given the implementation surface is small (~30-40 lines of JS interop per the requirements
  doc's own estimate) and the exact shape (attach `drop`/`dragover`/`dragenter` listeners to
  the canvas element, read `dataTransfer.files`, call `arrayBuffer()`) is standard MDN-level
  DOM API usage with no framework-specific subtlety beyond "find the canvas element," forking
  a full external repo (LottieViewer) for reference would cost more time (cloning, reading
  unrelated Lottie-parsing code, extracting the relevant ~40 lines) than writing the interop
  directly against this repo's own `OpfsInterop.kt` pattern (section 3) and MDN's `DataTransfer`/
  `File` docs.

- **Verdict: Not recommended to fork/adapt an external repo.** Worth a five-minute skim of
  LottieViewer's wasmJs source if the plan/implementation phase wants a second data point on
  canvas-element lookup (`document.querySelector("canvas")` or similar), but not worth
  vendoring or closely modeling the implementation on it.

## Summary table

| Piece | Option | Verdict |
|---|---|---|
| 1. JS drag-and-drop capture | Compose Multiplatform native `dragAndDropTarget` (wasmJs) | Not recommended (unproven/undocumented for OS-file-drop at this pin) |
| 1. JS drag-and-drop capture | Hand-rolled JS interop (`external fun = js(...)`, per requirements' own proposal) | **Recommended** |
| 1/2. DOM typing | `kotlinx-browser` | Viable but marginal — skip unless hand-rolled surface grows |
| 2. Upload widget | Uppy.js wrapped via interop | Not recommended (bundle size, architectural mismatch) |
| 2/3. Async-JS-to-Kotlin pattern | Reuse `OpfsInterop.kt`/`SqliteWorkerInterop.kt` pattern | **Recommended** |
| 3. ArrayBuffer→ByteArray | Hand-written conversion (LLM-assisted, small, tested) | **Recommended**, low risk |
| 4. Reference implementation | Fork/adapt LottieViewer or similar | Not recommended — skim only if useful |

**Overall build/adopt recommendation:** build it, following this repo's own established
wasmJs interop pattern. No external library cleanly solves the OS-file-drop-into-Compose-Web
problem at this repo's pinned Compose Multiplatform version; `kotlinx-browser` could shave a
little boilerplate off pieces 1–2 but isn't compelling enough to introduce as a new dependency
when the existing raw-`js()` convention already covers the need; Uppy.js is the wrong shape
entirely. The lowest-risk path is hand-written JS interop for the drop listener and
`ArrayBuffer` read, modeled directly on `OpfsInterop.kt`'s `external fun = js(...)` +
`suspend`/`.await()` pattern, extended to accept bytes for the OPFS write.
