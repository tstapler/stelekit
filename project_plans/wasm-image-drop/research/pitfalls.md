# Pitfalls: wasm-image-drop

Research question: what commonly goes wrong with this class of feature (DOM drag/drop + async
JS interop + file bytes + repo write conventions), and what should the design explicitly guard
against.

## 1. Highest risk: fire-and-forget OPFS writes break the `Either` contract structurally

`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt:66-70` (existing
`writeFile`) is the only precedent in this codebase for a wasmJsMain byte/string write, and its
pattern is a real footgun if copied for the new bytes path:

```kotlin
actual override fun writeFile(path: String, content: String): Boolean {
    cache[path] = content
    scope.launch { opfsWriteFile(path, content) }   // fire-and-forget, own module-level scope
    return true                                      // returns success BEFORE the write lands
}
```

`opfsWriteFile` (`platform/OpfsInterop.kt:55-71`) swallows every failure into a `println` and
never surfaces it to the caller. `writeFile`'s `true` is a lie about durability — it means "the
in-memory cache was updated," not "OPFS accepted the bytes."

If `WasmMediaAttachmentService`/OPFS-`writeFileBytes` follows this precedent, the flow becomes:
attach returns `Right(AttachmentResult)` → `App.kt`'s `onFileDrop` handler
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt:1211-1223`) inserts the markdown
`![name](../assets/name)` into the block → the actual OPFS write (quota exceeded, permission
denied, `createWritable()` throws) fails silently in the background. The user sees a successful
attach and a broken image reference, with no error path that could ever produce a
`DomainError.AttachmentError` — because the `Either` was already returned before the failure was
known. This directly violates `kmp/CLAUDE.md`'s "never let exceptions propagate past a repository
boundary as a silent success" convention in spirit even though no exception literally escapes.

**Guard against this explicitly**: the new bytes-write path must `await()` the OPFS
`createWritable()`/`write()`/`close()` chain *before* returning `Either.Right`, and must convert a
caught `Throwable` into `DomainError.AttachmentError.CopyFailed` (or a new
`AttachmentError.AssetsDirectoryFailed`-style variant for OPFS-specific failures such as quota) —
not print-and-swallow like `opfsWriteFile` does today. Do not reuse `PlatformFileSystem.writeFile`
as the write path for attachments; it was designed for markdown content where eventual
async-write-with-console-log is tolerable (dirty-cache is source of truth), which is not true for
a one-shot "did this image actually land" operation.

## 2. Compose-for-Web + native DOM event interop

- **`dragover` must call `preventDefault()`, or `drop` never fires.** This is the classic HTML5
  DnD gotcha, confirmed via MDN
  ([HTMLElement: dragover event](https://developer.mozilla.org/en-US/docs/Web/API/HTMLElement/dragover_event)):
  the browser's default action for `dragover` is "reject as a drop target." Any raw JS listener
  attached to the canvas must call `event.preventDefault()` in its `dragover` handler.
- **Compose-for-Web drag-and-drop is explicitly unsupported by JetBrains as of this search** —
  the official docs ([Drag-and-drop operations | Kotlin Multiplatform](https://www.kotlinlang.org/docs/multiplatform/compose-drag-drop.html))
  state web is not covered by `dragAndDropSource`/`dragAndDropTarget`, confirming the ticket's own
  premise that raw JS interop on the canvas is the only path — there is no future Compose API to
  fall back on if the JS glue has bugs.
- **The canvas element is not queryable at `main()` time.** `browser/Main.kt:84` mounts
  asynchronously: `ComposeViewport(document.body!!) { StelekitApp(...) }`. No code anywhere in
  `wasmJsMain` currently references a `<canvas>` element (confirmed by grep — zero hits for
  `canvas`/`querySelector` in `kmp/src/wasmJsMain`). Attaching the drop listener must happen
  *after* Compose creates and inserts the canvas, not inline in `main()`. Likely needs either: a
  `MutationObserver` on `document.body`, a `LaunchedEffect`/`DisposableEffect` inside a composable
  that runs after first composition and does `document.querySelector("canvas")`, or attaching the
  listener to `document.body` itself (bubling covers the canvas) rather than the canvas
  specifically — the last option sidesteps the timing problem entirely and is worth strong
  consideration in the plan phase.
- **Listener lifecycle**: if the listener is attached once to `document`/`document.body` at
  startup it's fine, but if it's re-attached per-composition (e.g. inside a `remember` tied to
  page navigation) it risks duplicate listeners stacking up — each drop would fire the handler N
  times. Use `DisposableEffect` with a matching `removeEventListener` if scoping is anything other
  than "once, at app startup."
- **Event ordering vs. Compose pointer input**: Compose owns pointer/mouse events for its own hit
  testing within the canvas. Drag events (`dragenter`/`dragover`/`drop`) are a separate W3C event
  family from pointer events and Compose does not consume them, so there should be no conflict —
  but this is unverified against this specific Compose-for-Web version and worth a manual browser
  check in the validate/implement phase rather than assuming no interference.

## 3. Kotlin/Wasm JS interop pitfalls

- **`ArrayBuffer` → `ByteArray` has no cheap built-in conversion on the `wasmJs` target.** The
  `Int8Array(buffer) as ByteArray` unsafe-cast trick that works on the (legacy) Kotlin/JS target
  does **not** apply to `wasmJs` — Kotlin/Wasm's JS interop model uses `JsAny`/`external`
  boundaries, not JS-backed Kotlin arrays, per
  [Interoperability with JavaScript | Kotlin Docs](https://kotlinlang.org/docs/wasm-js-interop.html).
  This repo's own interop code (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/SqliteWorkerInterop.kt`)
  shows the established pattern for crossing this boundary: small, explicit `js("...")`-annotated
  functions (`jsArrayPushLong`, `getColumnValue`, etc.), each a single JS statement. There is no
  existing precedent in this repo for bulk-copying a large byte buffer this way. A naive
  implementation that loops over each byte with a `js("arr[index]")`-style per-element accessor
  (as `SqliteWorkerInterop.getRow`/`jsArrayGetString` do for small row/column counts) would issue
  one Wasm↔JS boundary call *per byte* — for a multi-MB image this is a severe performance cliff.
  **Guard**: use a bulk transfer helper (e.g. a single `js("new Uint8Array(buffer)")` handle plus
  a bulk-copy loop written in JS via one `js("...")` block that returns a `Uint8Array`, then
  convert with the Kotlin/Wasm-supported `toByteArray()`/`toUint8Array()` bridge if the stdlib
  version in use provides one) — confirm which conversion helper is actually available in this
  project's Kotlin version during planning rather than assuming.
- **`Promise` ↔ coroutine bridging**: this repo already has the working pattern —
  `kotlinx.coroutines.await()` on a `Promise<JsAny>`, wrapped in `try/catch (e: Throwable)`, as
  seen in `WasmOpfsSqlDriver.kt:20-26` and `platform/OpfsInterop.kt`'s `readOpfsFile`/`opfsWriteFile`.
  Follow that same shape for `File.arrayBuffer()` (returns a `Promise`) — `.await()` it inside a
  `try/catch`, convert any thrown `Throwable` to a `DomainError`, never let it propagate raw past
  the service boundary. An **unhandled Promise rejection** (missing `.await()` or missing
  `try/catch` around it) will not crash the Wasm module but will silently drop the operation and
  log only to the browser console (`Uncaught (in promise)`), which is invisible to the user and to
  `graphContentLogger` — exactly the class of bug section 1 above describes.
- **`external`/`JsAny` object lifetime**: JS `File`/`Blob`/`DataTransfer` objects handed across
  the boundary at `drop`-time must be fully consumed (bytes read via `arrayBuffer()`) synchronously
  within the same event-handling turn or very shortly after — `DataTransfer.files` is only
  guaranteed valid for the duration of the drop event dispatch per the HTML spec. Deferring the
  `File` read behind an unrelated suspend point (e.g. awaiting a DB write first) before reading its
  bytes risks the browser having already invalidated the object. Read bytes eagerly at drop time,
  then do everything else (DB/markdown insertion) afterward with the already-extracted `ByteArray`.

## 4. Correctness risks specific to this repo's conventions

- **Extension-only image filtering is intentional, not a gap — preserve it as-is.** Both
  JVM's `pageDropTarget` (`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/PageDropTarget.kt:16,44`)
  and `JvmMediaAttachmentService` (`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/service/JvmMediaAttachmentService.kt:25-26`)
  filter by extension (`setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "svg", "bmp")`), not
  MIME sniffing, on every existing platform. The requirements doc explicitly scopes "keep the
  filter behavior" as in-scope, matching JVM. A renamed non-image file with an image extension
  will be attached; this is consistent existing behavior across platforms, not a wasm-specific
  regression to fix. Don't add MIME-type validation on wasm alone — that would make web behavior
  diverge from the other three platforms, which acceptance criterion 6 forbids ("no regression to
  JVM/Android/iOS behavior" implies behavioral parity, not stricter web-only validation).
- **`attachFilePath(filePath: String, ...)` cannot represent a dropped `Blob`.** Already identified
  in requirements.md — flagging here that whatever bytes-capable method gets added to
  `MediaAttachmentService` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/service/MediaAttachmentService.kt`)
  must still return `Either<DomainError, AttachmentResult>?` matching the existing three methods'
  contract shape (nullable-outer for "not applicable/cancelled", `Either` for real success/failure)
  — introducing a different failure-signaling shape for just the wasm entry point would fragment
  the convention `App.kt`'s three call sites already rely on (`result?.fold(...)`).
- **Uncaught `Throwable` at the service boundary**: every existing wasmJsMain async helper in this
  repo that talks to OPFS/JS (`OpfsInterop.kt`, `WasmOpfsSqlDriver.kt`) wraps its `await()` calls in
  `try/catch (e: Throwable)`. Any new drop-handling code must do the same at the
  `WasmMediaAttachmentService` boundary specifically (not just deeper in `OpfsInterop`-style
  helpers) so a raw JS exception from `File.arrayBuffer()` rejecting, or OPFS
  `QuotaExceededError`, becomes `DomainError.AttachmentError.CopyFailed` rather than an unhandled
  Wasm exception that Compose's top-level `CoroutineExceptionHandler`
  (`browser/Main.kt:28-30`) would catch far too late (after the loading overlay, unrelated to this
  code path) or not at all if it happens inside a detached JS callback.

## 5. Concurrency/lifecycle risk: drop racing `switchGraph()`/`shutdown()`

`GraphManager.switchGraph()` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt:327-353`)
nulls `_activeRepositorySet.value` and closes `currentFactory` *before* the new graph finishes
initializing, specifically to stop in-flight `Flow` collectors before the DB connection tears down
(see the comment at lines 348-350). `kmp/CLAUDE.md`'s "Repository Flow resilience" section
documents the analogous crash class for `Flow` collectors and how `catchDbError()` guards it.

The image-attach path is **not** a `Flow` collector, so that guard does not apply, and there is a
real but different-shaped risk:

- `App.kt`'s `onFileDrop` handler (lines 1200-1227) captures `graphRoot = appState.currentGraphPath`
  and `pageUuid` **once**, at drop time, then launches a coroutine that `await`s the (now
  async/OPFS-backed) attach operation across a suspend point. If the user switches graphs while
  that write is in flight, the write completes against the *old* `graphRoot` (a stale but still
  syntactically valid OPFS path — this does not corrupt the new graph, but silently writes the
  image into a graph the user is no longer viewing) and the subsequent
  `blockStateManager.addBlockWithContent(pageUuid = pageUuid, ...)` call targets a `pageUuid` that
  belonged to the old graph's now-torn-down `RepositorySet` — this write **does** go through
  `DatabaseWriteActor`, which lives in a per-graph `graphScope` that `switchGraph()` cancels
  (`activeGraphJobs[id]?.cancel()`, line 341), so the block insert will likely just silently fail
  as a cancelled coroutine (no crash, but the image is written to OPFS with no markdown reference
  ever inserted anywhere — an orphaned asset file, and no error surfaced to the user).
- This is not a hypothetical: `PlatformFileSystem`'s `scope` (the one `writeFile`/`opfsWriteFile`
  fire into) is a module-level `CoroutineScope(SupervisorJob() + Dispatchers.Default)` owned by
  the single `PlatformFileSystem` instance created once in `Main.kt:35`, **not** the per-graph
  `graphScope`. It is never cancelled by `switchGraph()`/`shutdown()`, so an in-flight OPFS write
  survives a graph switch and completes into whatever path was captured, orphaned from the
  now-inactive graph's UI state.

**Guard against this explicitly**: before inserting the markdown reference (the
`blockStateManager.addBlockWithContent`/`insertTextAtCursor` call after a successful attach),
re-check that the graph/page context is still current — e.g. compare captured `pageUuid`/`graphRoot`
against `appState.currentGraphPath` at completion time, or check whether `graphScope` for the
originating graph is still active — and drop the result (log, don't insert) if the context has
moved on. This is a pre-existing latent race for the `onAttachImage`/`onPasteImage` JVM paths too
(same `scope.launch` + captured `graphRoot` pattern at lines 1176-1198, 1229-1253), so it is not
strictly wasm-specific, but wasm's async-OPFS-write path is slower and more likely to overlap a
graph switch than JVM's synchronous `Files.move`, making the window meaningfully wider on web.

## 6. Testing gap

No wasm-specific test task or DOM-event-glue test pattern exists in this repo today (confirmed:
`kmp/TESTING_README.md` has zero mentions of "wasm"; no `wasmJsTest` source set was found under
`kmp/src/`). The new JS `dragover`/`drop` listener code is therefore new, untested surface area.
Recommend the plan phase either (a) scope the drop-event wiring as thin as possible and push all
non-trivial logic (extension filtering, bytes→attach orchestration) into `commonMain`-testable
functions that take a `ByteArray` + filename and can be unit-tested without a browser, keeping only
the unavoidable `js("...")` glue (attach listener, read `DataTransfer.files`, call
`arrayBuffer()`) untested, or (b) add a Playwright-driven manual/e2e check as the verification
step for the glue itself, since no other mechanism in this repo currently exercises live DOM
events against the wasm build.
