# Architecture research: wasm-image-drop

Agent 3 (Architecture), SDD Phase 2. All line numbers below verified by `Read` against
`kmp/src/...` in this worktree on 2026-09-22; no prior hotspot/architecture doc exists for
this area.

## 1. Integration points — full call chain

```
[JS 'drop' event on <canvas>]                              (wasmJsMain, new)
  → PageDropTarget.kt actual pageDropTarget                 (wasmJsMain, rewrite)
      reads DataTransfer.files, filters imageExtensions,
      awaits File.arrayBuffer(), wraps as List<Any>
  → EditorCapabilities.onFileDrop: ((List<Any>) -> Unit)?    (commonMain, unchanged type)
  → App.kt onFileDrop handler (~1200-1228)                   (commonMain, small branch added)
      today: attachmentService.attachFilePath(file.toString(), graphRoot)
      new:   branch on payload type (see §2)
  → MediaAttachmentService                                   (commonMain, +1 default method)
  → WasmMediaAttachmentService                                (wasmJsMain, new file)
      writes bytes into OPFS assets/, returns AttachmentResult
  → blockStateManager.addBlockWithContent(pageUuid, "![alt](path)")  (unchanged)
```

Key files read (section, not full file, except where noted):

- `PageDropTargetModifier.kt:1-17` — `expect fun Modifier.pageDropTarget(onFilesDropped:
  (List<Any>) -> Unit): Modifier`. Doc comment (lines 8-16) currently states WASM is a no-op
  — must be corrected per acceptance criterion 5.
- `PageDropTarget.kt` (wasmJsMain) — full file is 8 lines: `actual fun ... = this`. Confirmed
  trivial no-op, no salvageable logic.
- `PageDropTarget.kt` (jvmMain) — full file read for pattern precedent (68 lines). Filters
  `DataFlavor.javaFileListFlavor` to `imageExtensions = setOf("jpg","jpeg","png","gif","webp",
  "heic","svg","bmp")` **before** calling `onFilesDropped`, so `MediaAttachmentService` never
  sees non-image drops. The wasm implementation must replicate this filter locally (JS has no
  shared filter helper to reuse across source sets).
- `PageView.kt:154` — `.let { m -> if (capabilities.onFileDrop != null) m.pageDropTarget(capabilities.onFileDrop) else m }`.
  No change needed here; it already gates correctly on the existing `Any`-erased signature.
- `EditorCapabilities.kt:19-23` — `onFileDrop: ((List<Any>) -> Unit)?`, doc comment already
  states "opaque `Any` in commonMain" — this is the repo's existing precedent for
  platform-specific payload types flowing through `List<Any>` (see §2).
- `App.kt:1200-1228` — the exact handler that must branch on payload type; `App.kt:165,334,367`
  — `attachmentService` param threading through `StelekitApp`, currently defaulted to `null`
  and never supplied a value on wasm because `browser/Main.kt` never constructs one.
- `MediaAttachmentService.kt` (full file, 87 lines) — interface already has three optional,
  default-`null`/`false` capability methods (`attachFilePath`, `hasClipboardImage`,
  `pasteFromClipboard`), each documented "Default implementation returns X so existing
  implementations need not override unless they support Y." This is a **live, applied
  pattern**, not a hypothetical — the interface was designed for exactly this kind of
  incremental platform-capability extension.
- `browser/Main.kt` (full file, 92 lines) — constructs `opfsFileSystem: PlatformFileSystem`
  or falls back to `DemoFileSystem` (lines 39-73, `useDemoFallback` flag), then calls
  `StelekitApp(fileSystem=..., graphPath=..., graphManager=...)` at line 84-90 with **no**
  `attachmentService` argument — confirms requirements.md's finding #1 exactly.
- `FileSystem.kt:56-66` — `readFileBytes`/`writeFileBytes` defaults throw
  `UnsupportedOperationException`, documented as existing for "paranoid-mode" encrypted-file
  IO, "Platforms that support paranoid mode must override this." wasmJsMain's
  `PlatformFileSystem` does not override either (confirmed below) — but see §"OPFS bytes" for
  why this project should likely *not* be the one to implement them.
- `PlatformFileSystem.kt` (wasmJsMain, full file, 83 lines) — only overrides `writeFile`
  (String) and `readFile` (String, served from an in-memory `cache: MutableMap<String,
  String>`, line 10). No `writeFileBytes`/`readFileBytes` override exists (matches requirements
  finding #4). `writeFile` (lines 66-70) is fire-and-forget: updates `cache` synchronously,
  then `scope.launch { opfsWriteFile(path, content) }` on an internally-owned
  `CoroutineScope(SupervisorJob() + Dispatchers.Default)` (line 11) — **not** a
  `rememberCoroutineScope()` leak; this class is instantiated once in `Main.kt`, outside
  composition, so the scope-ownership rule in `kmp/CLAUDE.md` is already satisfied here.
- `OpfsInterop.kt` (wasmJsMain, full file, 87 lines) — the only OPFS surface today. Every
  function is **string-based**: `readOpfsFile` calls `file.text()` (line 40, 45),
  `opfsWriteFile` calls `writable.write(content: String)` (lines 51, 66). There is no
  `ArrayBuffer`/`Uint8Array`/byte path anywhere in this file. Confirms requirements finding #4
  precisely: OPFS byte writes are wholly unimplemented, not just unwired.

## 2. Design the seam — how bytes get from the browser `File` to `AttachmentResult`

**Both sub-questions in the prompt collapse to one answer**, because they're not actually
alternatives — they're two halves of the same change:

- The interface **must** grow a bytes-capable method. `App.kt`'s `onFileDrop` handler is
  commonMain and can only call through `MediaAttachmentService`'s declared interface — it has
  no way to invoke a wasm-only method on a concrete `WasmMediaAttachmentService` without an
  `is WasmMediaAttachmentService` cast in commonMain, which would violate the same
  platform-agnostic-common-code discipline the `Any`-erasure in `onFileDrop` exists to
  preserve, and would silently break if a `commonMain` unit test ever exercised the handler
  with a fake service. So: **add `attachBytes` to `MediaAttachmentService`**, following the
  exact `attachFilePath` precedent — default `= null`, no signature change for JVM/Android/iOS,
  zero risk of regressing them (satisfies acceptance criterion 6).

  ```kotlin
  suspend fun attachBytes(
      bytes: ByteArray,
      suggestedName: String,
      graphRoot: String
  ): Either<DomainError, AttachmentResult>? = null
  ```

- `onFileDrop`'s signature does **not** need to change (`(List<Any>) -> Unit)` stays exactly
  as-is). The doc comment on `EditorCapabilities.onFileDrop` (line 20-21) already establishes
  the precedent this project should follow: "Each entry is a platform file handle (opaque
  `Any` in commonMain; a `java.io.File` on JVM)." Add a wasm case to that same sentence rather
  than introducing a new callback shape. Concretely: define a small commonMain data class
  (it must live in commonMain, not wasmJsMain, because `App.kt`'s `when`/`is` check on it is
  commonMain code and commonMain cannot reference a wasmJsMain-only type):

  ```kotlin
  // commonMain, e.g. service/DroppedFileBytes.kt
  /** An in-memory dropped file with no filesystem path (browser File/Blob). */
  data class DroppedFileBytes(val suggestedName: String, val bytes: ByteArray)
  ```

  wasm's `pageDropTarget` actual emits `List<DroppedFileBytes>` typed as `List<Any>`. `App.kt`'s
  handler (line ~1206) becomes a two-way branch:

  ```kotlin
  files.forEach { file ->
      val result = when (file) {
          is DroppedFileBytes -> attachmentService.attachBytes(file.bytes, file.suggestedName, graphRoot)
          else -> attachmentService.attachFilePath(filePath = file.toString(), graphRoot = graphRoot)
      } ?: return@forEach
      // ...unchanged fold...
  }
  ```

**Evaluation of the two options as framed in the prompt:**

| | New interface method + `Any`-erased wrapper (recommended) | `List<Any>` alone, no interface change |
|---|---|---|
| commonMain touch | 1 new data class (~5 lines) + 1 default interface method (~6 lines) + 1 `when` branch in App.kt (~4 lines) | Not achievable — see below |
| platform-only touch | wasm: new service file, new `pageDropTarget` actual, new OPFS byte interop | N/A |
| type safety | `attachBytes` has a real typed signature; call sites get compiler-checked arg names | N/A — this option isn't viable standalone |
| coupling | `MediaAttachmentService` implementations still don't know about each other's platforms; `DroppedFileBytes` is inert data, not behavior | N/A |

The "or does `List<Any>` already accommodate this... letting `onFileDrop`'s consumer
pattern-match on it" framing in the prompt is correct **as a description of the payload
transport**, but it cannot be the *entire* answer, because pattern-matching in `App.kt` still
has to call *something* to turn bytes into an `AttachmentResult`, and that something must be
declared on the interface. So the recommendation is: use `Any`-erasure for the payload
(cheap, consistent with existing precedent, zero risk to other platforms) **and** add the
one new interface method it needs to reach (cheap, same default-null pattern already used
three times in this file). This is the smallest change that keeps `App.kt`'s handler as the
single dispatch point it already is today.

## 3. Tech debt disposition

**Extend-as-is.** This is not a SOLID/Clean Architecture violation to refactor away, and not
a case needing a new seam — the seam already exists. `MediaAttachmentService` was already
built for incremental per-platform capability rollout: `attachFilePath`, `hasClipboardImage`,
and `pasteFromClipboard` are all optional, default-no-op interface members with doc comments
explicitly inviting future overrides ("Default implementation returns X so existing
implementations need not override unless they support Y" — `MediaAttachmentService.kt:58-59,
71,83`). wasmJsMain simply never got an implementation, the same way a new platform target
starts with zero widgets. The correct move is to add the missing `actual`/implementation
(`WasmMediaAttachmentService`) and one more optional interface member (`attachBytes`) that
continues the exact pattern already in place — not to redesign the interface or introduce an
adapter/facade layer.

## 4. Dispatcher correctness

`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/coroutines/PlatformDispatcher.js.kt:11-16`:

```kotlin
actual object PlatformDispatcher {
    actual val IO: CoroutineDispatcher = Dispatchers.Default
    actual val Main: CoroutineDispatcher = Dispatchers.Main
    actual val Default: CoroutineDispatcher = Dispatchers.Default
    actual val DB: CoroutineDispatcher = Dispatchers.Default
}
```

`PlatformDispatcher.IO` maps to `Dispatchers.Default` on wasmJs — same underlying dispatcher
as `Default` and `DB`. The file's own doc comment (lines 6-9) states why: "JS is
single-threaded, so IO and Default are equivalent. For true parallelism, you'd need Web
Workers."

**Is `withContext(PlatformDispatcher.IO)` a no-op on wasmJs?** Not literally a no-op — it's
still a real dispatcher hop (the continuation is re-scheduled through
`Dispatchers.Default`'s queue rather than executing inline), which matters for yielding
control back to the browser event loop between suspension points. But it provides **no
thread-level isolation**: unlike JVM's `Dispatchers.IO` (bounded thread pool off the UI
thread), wasmJs has exactly one JS thread, so `PlatformDispatcher.IO` and running on
`Dispatchers.Main` share that same thread. `WasmMediaAttachmentService.attachBytes` should
still wrap its body in `withContext(PlatformDispatcher.IO)` per `kmp/CLAUDE.md`'s dispatcher
matrix and for consistency with `JvmMediaAttachmentService.pickAndAttach` (which does the
same at line 33) — but the team should not expect it to prevent UI jank from a large
synchronous byte-copy the way it does on JVM/Android. Any long CPU-bound work (e.g. no
current plan for this, but worth flagging) would need chunking with intermittent `yield()`
calls, not just a dispatcher switch.

**Does `File.arrayBuffer()` need a coroutine `await()` bridge regardless?** Yes, unconditionally
— confirmed by the existing pattern in `OpfsInterop.kt`, where every OPFS operation (a
`Promise`-returning browser API) is bridged with `kotlinx.coroutines.await()` (e.g. lines 11,
14, 17, 28, 44, 65-67, 82). `File.arrayBuffer()` is exactly the same shape (JS `Promise<ArrayBuffer>`)
and must be bridged the same way — a small `private fun fileArrayBufferPromise(file: JsAny):
Promise<JsAny> = js("file.arrayBuffer()")` plus `.await()`, matching the file's established
`js("...")` micro-function style rather than introducing a new interop pattern.

## Additional finding: OPFS byte-write path — recommend bypassing `FileSystem.writeFileBytes`

Requirements.md correctly leaves this decision to the plan phase. Recommendation: **do not**
implement `FileSystem.writeFileBytes`/`readFileBytes` generically; have
`WasmMediaAttachmentService` call new low-level byte interop functions directly, mirroring how
`JvmMediaAttachmentService` (`kmp/src/jvmMain/.../JvmMediaAttachmentService.kt:28-70`) already
bypasses the app's `FileSystem` abstraction entirely for attachment writes — it uses raw
`java.io.File` + `okio.FileSystem.SYSTEM` directly, not the app's `FileSystem` interface.
Reasons this matters more on wasm than it looks:

1. `PlatformFileSystem`'s `cache: MutableMap<String, String>` (line 10) is **String**-valued —
   it exists for markdown text content. Routing binary image bytes through it would require
   either a second binary cache (parallel bookkeeping, duplicate invalidation logic) or lossy
   String encoding (the exact bug `FileSystem.kt:62-63`'s doc comment warns
   `writeFileBytes`'s default exists to prevent).
2. The repo's own commonMain `uniqueFileName` helper (`AttachmentFileNaming.kt:33-49`), used by
   `JvmMediaAttachmentService`, takes an `okio.FileSystem` and does **synchronous**
   `fileSystem.exists()` checks in a `while` loop. OPFS has no synchronous existence check
   available from the main thread (only from a Worker via sync access handles, which
   `WasmOpfsSqlDriver.kt` uses for SQLite but nothing here proposes adopting for this feature).
   `WasmMediaAttachmentService` cannot reuse `uniqueFileName` as-is; it needs its own
   **async** duplicate-suffix loop (e.g. try `getFileHandle(dir, candidate, create=false)` and
   treat the browser's `NotFoundError` as "available" — `OpfsInterop.kt` already has this
   exact `getDirectoryHandle`/`getFileHandle` primitive at lines 13-17, just needs a
   non-creating variant or a try/catch wrapper). Flag this explicitly for plan.md — it is new
   logic, not a reuse of existing dedup code, despite the acceptance criteria wording implying
   parity with "the existing duplicate-filename suffix convention."
3. Bypassing `FileSystem.writeFileBytes` keeps this fix's diff scoped to the attachment path
   and OPFS interop, and leaves paranoid-mode byte IO on wasm exactly as unimplemented as it is
   today (still throws `UnsupportedOperationException`) — no regression, no new promise made
   about a capability (encrypted-graph byte IO) this project isn't testing.

Concretely, new interop needed in `OpfsInterop.kt` (following its existing `js("...")` +
`.await()` idiom, not a new one): a byte-accepting variant of `opfsWriteFile` (`writable.write`
already accepts `BufferSource | Blob | string` per the File System Access API spec, so this is
additive, not a rewrite of the existing string path) and a `getFileHandle(..., create=false)`
existence probe for the async dedup loop in point 2.

## Summary of files touched (for plan.md scoping)

- **commonMain, additive only:** `MediaAttachmentService.kt` (+1 default method),
  `PageDropTargetModifier.kt` (doc comment fix — acceptance criterion 5), `App.kt` (~4-line
  `when` branch at the existing `onFileDrop` handler), new `DroppedFileBytes` data class.
- **wasmJsMain, new/rewritten:** `PageDropTarget.kt` (full rewrite: JS drop-event wiring +
  `File.arrayBuffer()` bridge + image-extension filter), new `WasmMediaAttachmentService.kt`,
  `OpfsInterop.kt` (+byte write function, +existence-probe variant), `browser/Main.kt`
  (construct and pass `attachmentService`, with a decision needed on whether to suppress it
  when `useDemoFallback` is true since the demo graph isn't OPFS-backed).
- **Not touched:** `FileSystem.kt` interface, `PlatformFileSystem.kt`'s existing
  String-content methods, JVM/Android/iOS `MediaAttachmentService` implementations,
  `PageView.kt`.
