# Research: Features & UX (Agent 2)

## 1. Existing drag-and-drop / file-attach implementations

### JVM — ground truth (ordinary, full-featured)

- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/PageDropTarget.kt` uses Compose's
  `dragAndDropTarget` + AWT `DropTargetDropEvent`/`DataFlavor.javaFileListFlavor`. It:
  - Gates `shouldStartDragAndDrop` on flavor support (so non-file drags — e.g. dragged text/links
    — never trigger the target at all).
  - Filters to `imageExtensions = {jpg, jpeg, png, gif, webp, heic, svg, bmp}` via
    `File.extension.lowercase()`.
  - Calls `dropEvent.dropComplete(imageFiles.isNotEmpty())` — tells the OS whether the drop
    "took," which changes the drop cursor. Non-image or empty file lists mark
    `dropComplete(false)` and return `false`.
  - Passes filtered `List<java.io.File>` (typed `List<Any>` in the common `expect fun`) to
    `onFilesDropped`.
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/service/JvmMediaAttachmentService.kt`
  (`attachExistingFile`, called via `attachFilePath(filePath: String, ...)`):
  1. `File("$graphRoot/assets").mkdirs()` — `Either.Left(AssetsDirectoryFailed)` on failure.
  2. `uniqueFileName(assetsDir.toOkioPath(), stem, ext, FileSystem.SYSTEM)` — suffix-counter
     dedup (`photo.jpg` → `photo-1.jpg`), synchronous `okio.FileSystem.exists()` check per
     candidate.
  3. Copies to `.tmp-<uuid>` then `Files.move(..., ATOMIC_MOVE)`, falling back to
     `REPLACE_EXISTING` if the filesystem doesn't support atomic move, with cleanup
     (`tmpFile.delete()`) on any failure path — `Either.Left(CopyFailed)`.
  4. Returns `AttachmentResult(relativePath = "../assets/$uniqueName", displayName)`.
  - All of this runs under `withContext(PlatformDispatcher.IO)`.

**`uniqueFileName`** (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/service/AttachmentFileNaming.kt`)
is shared common code, parameterized over an `okio.FileSystem` — this is reusable for wasm
*if* an `okio.FileSystem` (or equivalent synchronous-`exists()` shim) can be backed by wasm's
file model. See §2 for why this is non-trivial on wasm.

### Android — second real implementation, different shape

`AndroidMediaAttachmentService` (`kmp/src/androidMain/.../service/AndroidMediaAttachmentService.kt`)
only implements `pickAndAttach` (Photo Picker via `ActivityResultContracts.PickVisualMedia`) and
`pasteFromClipboard`/`hasClipboardImage`. It does **not** override `attachFilePath` — Android has
no drag-and-drop entry point today, so it inherits the interface's `null` default. Copy logic
(assets dir creation, `uniqueFileName`, temp-file + `ATOMIC_MOVE`/`REPLACE_EXISTING` fallback) is
duplicated near-verbatim from JVM, reading bytes via `ContentResolver.openInputStream(uri)`
instead of a direct `File`.

### iOS — stub, useful as "minimal legal implementation" precedent

`IosMediaAttachmentService` (`kmp/src/iosMain/.../service/IosMediaAttachmentService.kt`) only
implements `pickAndAttach`, returning `null` unconditionally (real picker deferred). This confirms
the interface's default methods (`attachFilePath`, `hasClipboardImage` → false,
`pasteFromClipboard` → null) are the correct "not supported yet" shape — a `WasmMediaAttachmentService`
does not need to touch `pickAndAttach`/`pasteFromClipboard` if those stay out of scope; it only
needs to override `attachFilePath` (or add a new bytes-based method, per requirements.md §Scope).

### `MediaAttachmentService` interface (commonMain)

`kmp/src/commonMain/kotlin/dev/stapler/stelekit/service/MediaAttachmentService.kt` documents the
contract every implementation must follow (IO dispatcher, `assets/` creation, dedup suffixing,
temp-then-atomic-rename, `Either<DomainError, T>` — never throw). Critically,
**`attachFilePath(filePath: String, ...)` is doc'd as "must be an absolute path readable by the
platform"** — this is unsatisfiable for a browser `File`/`Blob`, confirming requirements.md's
finding. `DomainError.AttachmentError` currently has three variants: `CopyFailed`, `PickerFailed`,
`AssetsDirectoryFailed` — none map cleanly to "OPFS quota exceeded" or "OPFS write silently
swallowed" (see §2); the plan phase will likely need a new variant or should reuse `CopyFailed`
with a descriptive message.

### `EditorCapabilities` / `PageView.kt` — how the callback reaches the UI

`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/EditorCapabilities.kt` bundles
`onAttachImage` / `onFileDrop` / `onPasteImage` as nullable lambdas (`onFileDrop: ((List<Any>) ->
Unit)?`). `PageView.kt:154` conditionally applies `.pageDropTarget(capabilities.onFileDrop)` only
`if (capabilities.onFileDrop != null)` — so once `App.kt` passes a non-null `attachmentService`,
`onFileDrop` becomes non-null automatically (it's built as
`if (attachmentService != null) { ... } else null` at `App.kt:1200`) and the modifier attaches
itself with no other wiring needed. This means the wasm `WasmMediaAttachmentService` +
`browser/Main.kt` wiring alone is sufficient to activate the whole `App.kt` drop-handling path —
no changes needed in `PageView.kt` or `EditorCapabilities.kt`.

## 2. WASM-specific edge cases and failure modes

### The load-bearing finding: wasmJsMain's `PlatformFileSystem` is an in-memory `String` cache, not a real filesystem

`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` is **not** a thin
OPFS wrapper — it's a `mutableMapOf<String, String>()` (`cache`) that is:
- Populated once at startup via `preload()` → `loadOpfsDirectory()` (async, recursive, reads every
  file's *text* content via `readOpfsFile` → `file.text()`).
- Read synchronously thereafter (`readFile`, `fileExists`, `listFiles`, `listDirectories` all hit
  `cache` directly — no I/O on the read path).
- Written synchronously to `cache` on `writeFile()`, then **fire-and-forget** persisted to real
  OPFS: `scope.launch { opfsWriteFile(path, content) }` — the call returns `true` immediately,
  before the async OPFS write has even started, let alone completed or been confirmed.

Consequences for wasm-image-drop, none of which exist on JVM/Android:

1. **No bytes anywhere in this pipeline.** `cache: MutableMap<String, String>`, `readFile`/
   `writeFile` are `String`-typed, and `opfsWriteFile`/`readOpfsFile`
   (`kmp/src/wasmJsMain/.../platform/OpfsInterop.kt`) call `file.text()` /
   `writable.write(content: String)` — real JS `File.text()` / `FileSystemWritableFileStream
   .write(string)`. There is no `ArrayBuffer`/`Uint8Array` path today. Adding `writeFileBytes`
   means adding new JS interop (`file.arrayBuffer()`, `write(Uint8Array)`) — not reusing existing
   `opfsWriteFile`, and **not** reusing the `cache: MutableMap<String, String>` (a `ByteArray`
   can't round-trip through a JVM/Kotlin `String` losslessly, e.g. arbitrary binary with invalid
   UTF-8 sequences — same rationale `FileSystem.kt`'s `writeFileBytes` doc comment already gives
   for why paranoid-mode bytes need true byte I/O). A parallel `MutableMap<String, ByteArray>`
   binary cache (or bypassing the cache for assets entirely, per requirements.md's suggested
   "equivalent bytes-in path that bypasses the FileSystem abstraction") is the likely shape.
2. **OPFS writes are fire-and-forget with swallowed errors.** `opfsWriteFile`'s catch block does
   `println("[SteleKit] OPFS write failed for $path: ${e.message}")` and returns — the caller
   (`PlatformFileSystem.writeFile`) never sees the failure; it already returned `true`. For text
   files this is a "your journal entry didn't actually persist to disk but the UI thinks it did"
   bug that pre-dates this project. For **image bytes specifically**, requirements.md's
   acceptance criterion 2 ("bytes are written into OPFS") and the non-functional "must follow
   Either convention" constraint both mean the new bytes-write path must NOT copy this
   fire-and-forget pattern — it needs to `await` the OPFS write (the underlying
   `fileHandleCreateWritable(...).await()` / `writableWrite(...).await()` /
   `writableClose(...).await()` calls are already suspend-and-awaitable; `opfsWriteFile` just
   chooses not to propagate them) and return `Either.Left` on failure, e.g. quota exceeded
   (`QuotaExceededError` from `createWritable()`/`write()` is a real, common OPFS failure mode
   for large image files that this repo has no error-handling precedent for — no existing
   `DomainError.AttachmentError` variant fits "quota exceeded" today).
3. **`uniqueFileName`'s dedup check is synchronous against `cache`, not against live OPFS.** JVM
   passes `FileSystem.SYSTEM` (real filesystem); a wasm equivalent would need to pass an
   `okio.FileSystem`-shaped (or hand-rolled) adapter over `cache`'s keys — which only contains
   *text* files loaded at `preload()` time. Since dropped-image bytes won't live in the text
   `cache` (per point 1), a naive reuse of `uniqueFileName(assetsDir, stem, ext,
   /* text cache-backed FS */)` would not see previously-dropped images that used the binary path,
   risking filename collisions (`photo.jpg` overwriting `photo.jpg`) after the first dropped
   image. The plan phase needs to pick one registry of existing asset filenames (extend `cache`'s
   keyset to track binary filenames too, even if content isn't cached, or query OPFS directly via
   `listOpfsEntries` on the assets dir) — reusing `uniqueFileName` naively is a footgun here, not
   a free win.
4. **Multiple files dropped at once.** `DataTransfer.files` is a `FileList`; nothing in the ticket
   or existing common code (`App.kt:1206` already does `files.forEach { file -> ... }`) prevents
   multiple files — this part of the pipeline is inherited unchanged from JVM and needs no new
   wasm-specific handling, *except* that each file needs its own `File.arrayBuffer()` read, and
   `uniqueFileName` collision-checking across files in the *same* drop batch (two same-named files
   dropped together) needs the dedup check to see prior files in the batch, not just
   previously-persisted ones — JVM's loop calls `attachFilePath` per file sequentially through the
   same synchronous `okio.FileSystem.exists()` check so later files in a batch correctly see
   earlier files' names; a wasm implementation must preserve this sequential-not-parallel
   processing for the same reason.
5. **Very large files / browser memory limits.** `File.arrayBuffer()` (or the older
   `FileReader.readAsArrayBuffer`) reads the *entire* file into memory as one contiguous
   `ArrayBuffer` — no streaming API is used anywhere in this codebase's JS interop today. For
   typical dropped images (a few MB) this is a non-issue; the requirements.md doesn't set a size
   ceiling, so no chunked/streaming write is in scope, but a large file (e.g. accidentally
   dropping a multi-GB video misnamed `.png`) could OOM the WASM heap or the browser tab. Given
   the existing `imageExtensions` filter, this is bounded in practice, but note there's no
   explicit file-size guard anywhere in the JVM implementation either — this is consistent with
   existing behavior, not a new gap the wasm build must close.
6. **Dropping a folder.** `DataTransfer.items[i].webkitGetAsEntry()` distinguishes files from
   directories; plain `DataTransfer.files`/`FileList` iteration (the simpler API) either omits
   directories or (in some browsers) includes a `File` entry with `size: 0` and an empty/unknown
   type for a dropped folder. JVM's AWT `javaFileListFlavor` returns real `File` objects where
   `file.extension` naturally excludes directories (a directory has no meaningful extension, so it
   fails the `imageExtensions` filter and is silently dropped) — the wasm JS glue needs an
   equivalent: filter on `file.type.startsWith("image/")` and/or extension before attempting
   `arrayBuffer()`, so a dropped folder is a no-op rather than an attempted (and failing) read.
7. **Dropping non-file drag data (text/link).** JVM's `shouldStartDragAndDrop` checks
   `isDataFlavorSupported(DataFlavor.javaFileListFlavor)` *before* accepting the drag at all — the
   drop target visually never activates for a dragged link/text selection. The JS equivalent is
   checking `event.dataTransfer.types.includes('Files')` in the `dragenter`/`dragover` handler
   (not just in `drop`) — if the ticket's proposed implementation only wires a `drop` listener (no
   `dragover`/`dragenter`), the browser's default "not-allowed" cursor never shows correctly and
   `event.preventDefault()` must still be called in `dragover` for `drop` to fire at all (an easy
   miss — without `preventDefault()` on `dragover`, browsers reject the drop entirely and `drop`
   never fires).
8. **In-flight graph switch / DB close.** The wasm `kmp/CLAUDE.md`-documented closed-DB-crash risk
   (`GraphManager.shutdown()`/`switchGraph()` racing a `Flow` collector) is about SQLDelight
   `Flow`s, not file writes — an asset write itself doesn't touch the DB. But the *second half* of
   the attach flow (`App.kt:1218`, `blockStateManager.addBlockWithContent(pageUuid, content)`)
   does write to the DB through `DatabaseWriteActor`. `App.kt:1202-1203` reads
   `appState.currentGraphPath` and `(appState.currentScreen as? Screen.PageView)?.page?.uuid`
   **synchronously at drop-callback time**, then loops `files.forEach` inside one `scope.launch`
   — if the user switches graphs mid-loop (multiple files, slow reads), the captured `graphRoot`
   and `pageUuid` are stale: bytes could be written into the *old* graph's OPFS `assets/`
   directory while the UI has already navigated to a new graph, and the subsequent
   `addBlockWithContent(pageUuid, ...)` call targets a page UUID that may not exist in the newly
   active graph's DB. **This is pre-existing behavior already shared by JVM** (same `App.kt` code
   path) — not a new wasm-specific bug to fix, but worth flagging to the plan phase as a
   known limitation inherited unchanged, since wasm's `PlatformFileSystem` additionally only ever
   constructs a single hardcoded graph (`graphId = "default"`, `browser/Main.kt:32-33`) — there is
   effectively only one graph in the current wasm build, so the multi-graph race is currently
   unreachable on web even though the code path exists.
9. **`PlatformDispatcher.IO` on wasm is `Dispatchers.Default`**, confirmed in
   `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/coroutines/PlatformDispatcher.js.kt` (JS is
   single-threaded; `IO`, `Default`, and `DB` are all aliases for `Dispatchers.Default`). This
   matches the `kmp/CLAUDE.md` dispatcher-matrix table already, so `withContext(PlatformDispatcher.IO)`
   in a new `WasmMediaAttachmentService` is correct and requires no new mapping — it just doesn't
   buy real off-main-thread execution the way JVM's `Dispatchers.IO` thread pool does. Practically:
   large `arrayBuffer()` reads and `await()`-chained OPFS writes will still cooperate with the
   single JS event loop; nothing here blocks it synchronously (everything is `Promise`-based), so
   the UI won't freeze, but there's no true parallelism for multiple simultaneous large files.

## 3. Unstated user needs / UX gaps

### No visual drag-over feedback anywhere — not wasm-specific, pre-existing on JVM too

Grepped `EditorCapabilities.kt`, `PageView.kt`, and `App.kt` for any `isDragging`/hover state
tied to `pageDropTarget` / `dragAndDropTarget`: there is none. `PageView.kt:154` applies
`.pageDropTarget(capabilities.onFileDrop)` with no accompanying `Modifier.border`/background
change, and `PageDropTarget.kt` (JVM) implements only `onDrop` — it doesn't override
`onStarted`/`onEntered`/`onExited`/`onEnded` on `DragAndDropTarget` (the interface has default
no-op implementations for all of these; JVM overrides none of them). **Today, dragging a file over
a page in the desktop app gives zero visual indication that a drop target exists** — the user
finds out only by dropping. This is an existing gap the wasm-image-drop project inherits rather
than introduces; requirements.md's acceptance criteria don't mention hover feedback, so it's
reasonable to match JVM's (lack of) behavior rather than scope-creep into adding it — but it's
worth flagging as a "likely quick follow-up" in the same vein as paste/toolbar-picker, since a
browser `dragenter`/`dragover` listener is required anyway for the wasm fix (per §2 point 7) and
adding a `border`/`background` toggle driven by that listener would be nearly free once the JS
glue exists — cheaper on wasm than on JVM, where it would require adopting `onStarted`/`onEnded`
override that isn't there today.

### Drop-failure error surface: exists, but silent (no toast)

`App.kt:1213`, `graphContentLogger.warn("Drag-and-drop attachment failed: $err")` — confirmed this
is a `Logger.warn` call, not a user-visible toast/snackbar. Checked for a `NotificationManager`/
toast call anywhere in the `onFileDrop`/`onAttachImage`/`onPasteImage` failure branches
(`ifLeft = { err -> graphContentLogger.warn(...) } `, all three at lines 1186, 1213, 1238) — **none
of the three failure paths surface anything to the user today**, on any platform. A failed
drag-and-drop attach (e.g. OPFS quota exceeded, per §2 point 2) silently does nothing from the
user's perspective — no markdown inserted, no error shown, only a console/log-file line. This
matches acceptance criterion 3's "no crash" bar but is a weaker UX bar than "no crash AND the user
understands nothing happened." Since this silent-failure behavior is identical across JVM/Android/
wasm (same `App.kt` code, same `graphContentLogger.warn` pattern), fixing it is a cross-platform UX
improvement, not a wasm-specific requirement — out of scope for this bug fix unless the plan phase
decides wasm's new (more failure-prone, per §2 point 2's quota/permission errors) attach path
makes silent failure meaningfully worse on web than elsewhere.

### Paste-from-clipboard / toolbar-picker as a "likely quick follow-up" — assessment

requirements.md explicitly excludes `onAttachImage` (toolbar) and `onPasteImage` (clipboard) from
scope but predicts they'll be an easy follow-up once a real `MediaAttachmentService` exists. This
assessment is **directionally right for the toolbar picker, more work than implied for clipboard
paste**:

- **Toolbar picker (`onAttachImage` → `pickAndAttach`)**: genuinely cheap once
  `WasmMediaAttachmentService` exists. The browser equivalent is a hidden
  `<input type="file" accept="image/*">` triggered programmatically (`input.click()`) with a
  `change` listener reading `input.files[0]` — same `File` → `arrayBuffer()` → bytes-write path
  this project already has to build for drag-and-drop. No new OPFS/bytes work, just one more JS
  interop function + wiring in `browser/Main.kt`/`App.kt`. Confirms requirements.md's framing.
- **Clipboard paste (`onPasteImage` → `hasClipboardImage`/`pasteFromClipboard`)**: more work than
  "same service, no new plumbing" suggests, for two reasons not visible without reading the JVM
  implementation closely: (a) `hasClipboardImage()` is a **synchronous** call in the interface
  (`fun hasClipboardImage(): Boolean = false`, called directly from a key-event handler per its
  own doc comment) — but the browser Clipboard API (`navigator.clipboard.read()`) is
  **Promise-only**, and reading clipboard contents in most browsers additionally requires the page
  to have focus and (depending on browser/permissions-policy) a `clipboard-read` permission grant,
  possibly prompting the user. There is no synchronous "does the clipboard have an image" check
  available in a browser the way AWT's `Clipboard.isDataFlavorAvailable` provides one. Satisfying
  the interface's current synchronous contract on wasm is not straightforward — it would need
  either a different interface shape (async `hasClipboardImage`) or a cached/optimistic
  best-effort answer, which is real design work, not "same service" reuse. (b) JVM's paste path
  also handles the *raw bitmap* clipboard flavor (screenshots, `DataFlavor.imageFlavor`) by
  re-encoding to PNG via `javax.imageio.ImageIO` — the browser equivalent
  (`ClipboardItem.types` containing `"image/png"` blobs) is more directly analogous and easier, but
  still needs the same bytes-write plumbing this project is building, *plus* new permission-prompt
  UX that doesn't exist in this codebase today. Net: toolbar picker is indeed a quick follow-up;
  clipboard paste is a legitimate quick *within a few days* follow-up but not the same triviality
  level, and should be scoped/estimated separately rather than assumed free.
