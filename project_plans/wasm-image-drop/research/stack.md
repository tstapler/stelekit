# Stack Research: wasm-image-drop

Agent 1 (Stack), SDD Phase 2. Scope: JS interop, byte conversion, and OPFS-write
technology choices for implementing drag-and-drop image attachment on wasmJsMain.

## 1. Current pins in this repo

- Kotlin `2.3.21` (`kotlin("multiplatform") version "2.3.21"` etc., `settings.gradle.kts:9-13`).
- Compose Multiplatform `1.10.3` (`id("org.jetbrains.compose") version "1.10.3"`, `settings.gradle.kts:17`).
- `kotlinx-coroutines-core:1.10.2` declared once in `commonMain` (`kmp/build.gradle.kts:60`) —
  Gradle resolves the `-wasm-js` KMP variant automatically for the wasmJs target; there is no
  separate `kotlinx-coroutines-core-wasm-js` artifact to add.
- wasmJsMain's only extra dependency today is the sqlite-wasm npm package
  (`implementation(npm("@sqlite.org/sqlite-wasm", "3.46.1-build1"))`, `kmp/build.gradle.kts:156`).
- **No `kotlinx-browser` dependency anywhere in the repo** (confirmed via grep across `kmp/`).
- `wasmJs { browser(); binaries.executable() }` is gated behind `-PenableJs=true`
  (`kmp/build.gradle.kts:33-38`).

## 2. JS interop style: hand-rolled `external`/`js()`, not `org.w3c.dom`

Answering research question 1 directly: **use `external` declarations / `js("...")` snippets
against `JsAny`, not `org.w3c.dom` bindings** — and this is both the current Kotlin-recommended
approach *and* the existing house style in this repo.

- Per the official Kotlin docs (kotlinlang.org/docs/wasm-js-interop.html, dated 2026-03-16,
  fetched live for this research), Kotlin/Wasm interop only allows a restricted type surface in
  `external`/`js("...")`/`@JsExport` signatures: numeric primitives → `Number`, `Long`/`ULong` →
  `BigInt`, `Boolean`, `String`, `Unit`, function types, and `JsAny`/its subtypes. Full
  `org.w3c.dom.*` typed DOM bindings (events, `DataTransfer`, `File`, `Blob`) and
  `org.khronos.webgl.*` typed arrays (`Int8Array` etc.) are **not** part of the Kotlin/Wasm
  stdlib — they live in the separate `kotlinx-browser` library
  (`implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")`), which this repo does not
  currently depend on.
- Only a minimal `kotlinx.browser` shim (`document`, `window`, `localStorage`,
  `sessionStorage`) is bundled directly into the Kotlin/Wasm stdlib — confirmed by
  `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt:9,17` using
  `kotlinx.browser.document` / `kotlinx.browser.localStorage` with no extra dependency declared.
  Everything beyond that (OPFS directory/file handles, the SQLite worker message protocol,
  `performance.now()`) is hand-written in this repo as private `external fun ... : JsAny` /
  `js("...")` declarations, awaited via `kotlinx.coroutines.await()` on `kotlin.js.Promise<JsAny>`
  return types. See `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt:5-17`,
  `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/SqliteWorkerInterop.kt` (whole file — includes
  a JS-snippet-constructed `Promise` wrapping a `worker.addEventListener('message', ...)` callback,
  the same pattern a drop-event listener would need), and the single-expression `@JsFun` form in
  `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/util/Time.js.kt:4-5`.
- **Plan-phase decision needed:** add `kotlinx-browser:0.3` (gets typed `File`/`Blob`/
  `DataTransfer`/`Int8Array` bindings and adapter functions "for free") vs. continue the
  no-new-dependency, hand-rolled-external style used everywhere else on wasmJsMain. Both are
  viable; the second is more consistent with existing code but requires writing a handful of new
  `external fun ... : JsAny` declarations for `event.dataTransfer.files`, `files.length`,
  `files[i]`, `file.name`, `file.arrayBuffer()`, and an `Int8Array`-backed byte accessor.

## 3. `ArrayBuffer` → Kotlin `ByteArray`

No existing precedent in this repo (grep for `ArrayBuffer|Int8Array|Uint8Array|toByteArray` in
`kmp/src/wasmJsMain/` returns nothing). Per Kotlin docs and community threads (kotlinlang Slack,
JetBrains/kotlin-web-site, `Kotlin/kotlinx-browser` repo):

- The **documented, current path** is `kotlinx-browser`'s `org.khronos.webgl.Int8Array` plus its
  typed-array adapter functions (`arrayCopy.kt` in the `kotlinx-browser` repo) — the docs show the
  `IntArray ↔ Int32Array` round trip explicitly (`toInt32Array()` / `toIntArray()`) and state
  "similar adapter functions are available" for other typed arrays, which includes
  `Int8Array ↔ ByteArray`. This requires the `kotlinx-browser` dependency from §2.
- Without that dependency, the fallback is a hand-declared `external class Int8Array : JsAny`
  wrapping the `ArrayBuffer`, with an unsafe/manual byte-by-byte or bulk-copy accessor — the
  `Int8Array(buffer).unsafeCast<ByteArray>()` pattern surfaced in Kotlin Slack threads is an
  **unofficial workaround**, not documented Kotlin API, and its safety under `WasmGC` should not
  be assumed stable without a compiler-version-pinned test. Given `kotlinx-browser` is a small,
  JetBrains-maintained, purpose-built dependency for exactly this gap, it is the lower-risk choice
  if any raw byte conversion is needed — recommend the plan phase take the dependency for this one
  conversion rather than hand-rolling an unsafe cast.

## 4. Reading a dropped `File`'s bytes asynchronously

`File.arrayBuffer()` (standard, stable, no vendor prefixes — `File` inherits it from `Blob`) is
the correct API: it returns a `Promise<ArrayBuffer>`. This matches the async-read pattern
already used in this repo for OPFS (`OpfsInterop.kt:11`, `:16-17`, `:28`, etc.) — a private
`js("...")` function returning `kotlin.js.Promise<JsAny>`, awaited with
`kotlinx.coroutines.await()` from a `suspend fun`. No new coroutine machinery is needed; the
existing `await()` extension (from `kotlinx-coroutines-core`, already a dependency) covers it.

One interop wrinkle worth flagging for the plan phase (not fully resolved by this research —
DOM-specifics belong to whichever agent covers UI/JS glue in more depth): MDN documents that
`DataTransferItem.getAsFileSystemHandle()` must be called synchronously within the same
event-handler tick as the `drop` event, before any `await`. `DataTransfer.files` (a `FileList`,
the simpler and sufficient API here per the requirements — no need for
`getAsFileSystemHandle()`/directory drops) should be read out synchronously in the JS-side event
handler and only the resulting `File` objects/array handed across the Kotlin boundary before any
`await` point, to avoid the browser invalidating the `DataTransfer` object.

## 5. OPFS byte I/O — extend existing interop, no new library needed

`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt` already implements the
full OPFS handle-acquisition chain (`getOpfsRoot`, `getDirectoryHandle`, `getFileHandle`) and a
text-based write path (`opfsWriteFile`, `readOpfsFile`) via `FileSystemWritableFileStream.write()`
/ `.close()` and `File.text()`. The File System Access API's `write()` method accepts
`BufferSource | Blob | string | ...` — i.e. it already accepts an `ArrayBuffer`/typed array
directly, and `FileSystemFileHandle.getFile()` → `File.arrayBuffer()` is the byte-read
equivalent of the existing `file.text()` call. This means:

- **The existing OPFS write path can be extended in place**, not replaced. Add
  `opfsWriteFileBytes(path: String, bytes: ByteArray)` beside `opfsWriteFile`, converting the
  `ByteArray` to an `Int8Array`/`ArrayBuffer` (§3) before calling the same
  `fileHandleCreateWritable` → `writable.write(...)` → `writable.close()` sequence, just passing
  the typed array instead of a `String` to `writableWrite`.
- Symmetrically, a `readOpfsFileBytes` analogous to `readOpfsFile` (swap `file.text()` for
  `file.arrayBuffer()` + the `ArrayBuffer → ByteArray` conversion from §3) covers
  `PlatformFileSystem.readFileBytes`.
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` currently only
  implements the `FileSystem` interface's string-based methods (`readFile`/`writeFile`, backed by
  an in-memory `cache: MutableMap<String, String>` plus a fire-and-forget `scope.launch { opfsWriteFile(...) }`)
  and does not override `writeFileBytes`/`readFileBytes` at all, so the common `FileSystem`
  interface's defaults (which throw `UnsupportedOperationException` per
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt:56-66`, per the
  requirements doc) are what's currently hit. No architectural change is needed to close this —
  it is a straightforward "implement the missing override using the byte-capable OPFS primitives
  above," symmetric with the existing string-based override pattern in the same file
  (`PlatformFileSystem.kt:66-70`).
- No separate library is needed for OPFS itself — `navigator.storage.getDirectory()` and the File
  System Access API surface used here are plain Web Platform APIs, already reached via
  hand-rolled `external`/`js()` declarations with no third-party wrapper.

## 6. Dispatcher mapping — confirms `PlatformDispatcher.IO`, with a caveat

`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/coroutines/PlatformDispatcher.js.kt:12` maps
`PlatformDispatcher.IO = Dispatchers.Default`, and the file's own comment states: "JS is
single-threaded, so IO and Default are equivalent. For true parallelism, you'd need Web
Workers." Per `kmp/CLAUDE.md`'s dispatcher matrix, new file-I/O-bound suspend functions
(`writeFileBytes`, the drop-triggered attach flow) should still be written as
`withContext(PlatformDispatcher.IO) { ... }` for cross-platform consistency, but on wasmJs this
does not move work off the single JS thread — it only cooperates with the event loop via
suspension points (`await()` on the OPFS/File Promises). This matters for the "must not block
the main/UI thread" requirement: on web, non-blocking is achieved by the *Promise-based async
APIs themselves* (`arrayBuffer()`, OPFS's async handle/write APIs), not by dispatcher-driven
parallelism — a large synchronous loop (e.g. a hand-rolled byte-by-byte `ArrayBuffer` copy without
using `Int8Array`'s bulk operations) would still block the single thread regardless of which
`PlatformDispatcher` wraps it.

## Summary of stack recommendations for the plan phase

1. JS interop: continue the repo's existing `external fun ... : JsAny` / `js("...")` +
   `kotlinx.coroutines.await()` pattern (matches `OpfsInterop.kt`/`SqliteWorkerInterop.kt`); do
   not reach for `org.w3c.dom` (not available without an extra dependency, and not used
   elsewhere in this codebase).
2. Take a new, narrow dependency on `kotlinx-browser:0.3` in `wasmJsMain` *only* for its
   `Int8Array`/typed-array ↔ `ByteArray` adapter functions, rather than hand-rolling an
   `unsafeCast` — the plan phase should confirm this trade-off explicitly since it's the one new
   third-party surface this feature would introduce.
3. `File.arrayBuffer()` (standard Blob API) + existing `await()` is the read path; no new
   coroutine or Promise-handling infrastructure needed.
4. OPFS byte I/O is an additive extension of `OpfsInterop.kt`/`PlatformFileSystem.kt`'s existing
   text-based read/write functions, not a new subsystem.
5. Kotlin `2.3.21` / Compose Multiplatform `1.10.3` / `kotlinx-coroutines-core:1.10.2` are all
   current enough to need no version bumps for this feature.
