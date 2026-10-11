# Requirements: wasm-image-drop

item_id: 9fbfb834-b891-41c7-a61a-123999a47b83

## Title

bug(wasm): pageDropTarget is a no-op — drag-and-drop image attachment disabled on web

## Background (from backlog item)

`pageDropTarget` (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/PageDropTarget.kt`)
returns the unmodified `Modifier` on WASM:

```kotlin
actual fun Modifier.pageDropTarget(onFilesDropped: (List<Any>) -> Unit): Modifier = this
```

Dragging an image file onto a page on the web target does nothing. Compose Multiplatform
for WASM renders entirely into a single `<canvas>` element, so HTML5 drag events
(`dragenter`, `dragover`, `drop`) fire on the canvas DOM element, not on individual Compose
composables — there is no supported Compose WASM API to intercept these events and route
them into `onFilesDropped: (List<Any>) -> Unit`. The JVM implementation uses AWT
`DropTarget`, which has no WASM equivalent.

The backlog item's proposed fix: attach a JS `drop` listener to the canvas element, extract
`DataTransfer.files`, read each as an `ArrayBuffer`, and post results to Kotlin via a
`Channel`/`CompositionLocal` wired through `Main.kt`.

## Root-cause investigation (this session, beyond the ticket's own root-cause section)

Read before scoping the fix — the ticket undercounts what's missing:

1. **No `MediaAttachmentService` exists for wasmJsMain at all.** JVM
   (`JvmMediaAttachmentService`), Android (`AndroidMediaAttachmentService`), and iOS
   (`IosMediaAttachmentService`) each have one; wasmJsMain has none. `browser/Main.kt` never
   constructs one and never passes `attachmentService` to `StelekitApp`
   (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt:165`), so it defaults to `null`.
2. **Consequence: all three image-attachment entry points are already dead on web**, not
   just drag-and-drop — `onAttachImage` (toolbar picker button), `onFileDrop` (drag-and-drop),
   and `onPasteImage` (clipboard paste) are each gated behind
   `if (attachmentService != null)` in `App.kt` (~lines 1176, 1200, 1229) and are all `null`
   on wasmJsMain today.
3. **`MediaAttachmentService.attachFilePath(filePath: String, ...)` assumes a filesystem
   path.** JVM's drop handler passes `java.io.File.toString()`. Browser-dropped files are
   `File`/`Blob` JS objects with in-memory bytes and no filesystem path — there is nothing to
   pass as `filePath`. Wiring the JS drop event alone (the ticket's proposed fix) is not
   sufficient; the attach path itself needs a bytes-capable entry point.
4. **wasmJsMain's `PlatformFileSystem` does not override `writeFileBytes`/`readFileBytes`.**
   The common `FileSystem` interface's defaults throw `UnsupportedOperationException`
   (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt:56-66`) —
   these exist for paranoid-mode byte I/O and were never implemented for OPFS. Writing a
   dropped image's bytes into `<graphRoot>/assets/` on web requires this to work.

So a fix that only wires the JS `drop` event (per the ticket's literal proposal) would call
into a `MediaAttachmentService` that doesn't exist, via a `filePath`-shaped contract that
dropped browser files can't satisfy, ultimately writing bytes through a `FileSystem` method
that isn't implemented on this platform. The real gap is "web has no image-attachment
pipeline," and drag-and-drop is one of three dead entry points into it.

## Scope decision

Given the above, this project scopes the fix to what's necessary to make drag-and-drop
attachment work end-to-end on web, which is a strict superset of the ticket's literal ask:

- JS interop to capture dropped files on the Compose canvas (ticket's original ask).
- A minimal `WasmMediaAttachmentService` (new file, wasmJsMain) implementing
  `MediaAttachmentService`, wired into `browser/Main.kt` and passed to `StelekitApp`.
- OPFS byte-write support in wasmJsMain's `PlatformFileSystem` (`writeFileBytes` at minimum;
  `readFileBytes` if needed for the attach-then-verify path), or an equivalent bytes-in path
  that bypasses the `FileSystem` abstraction if that proves simpler — a call the plan phase
  should make explicitly rather than assuming.
- A bytes-based entry point on the attach path (either a new `MediaAttachmentService` method
  taking `ByteArray` + suggested filename, or a wasm-specific internal path) since
  `attachFilePath(filePath: String, ...)` cannot represent a browser-dropped `Blob`.

Explicitly out of scope unless the plan phase finds it's unavoidable to reach the above:
- Toolbar file-picker (`onAttachImage`) and clipboard paste (`onPasteImage`) on web. Wiring
  a real `MediaAttachmentService` will likely make these easy follow-ups (same service, no
  drag/JS-interop plumbing needed), but implementing them is not required to close this bug
  and should be called out as a suggested follow-up, not silently rolled in.
- Non-image file types (ticket and existing platforms both filter to
  `imageExtensions`/`IMAGE_EXTENSIONS`; keep that filter behavior on web too).

## Affected files (ticket-listed, confirmed by reading)

- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/PageDropTarget.kt` — the no-op `actual`.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/PageDropTargetModifier.kt` — `expect` declaration + doc comment claiming WASM is a no-op (needs updating once fixed).
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt` — entry point; needs to construct and pass the new service.

## Additional affected files (found during investigation, not in ticket)

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/service/MediaAttachmentService.kt` — interface; likely needs a bytes-capable method.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (~line 1200-1220) — `onFileDrop` handler currently calls `attachFilePath(filePath = file.toString(), ...)`, which won't work for wasm's dropped-object representation.
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` — needs `writeFileBytes` (OPFS) support.
- New file: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/service/WasmMediaAttachmentService.kt` (or similar).

## Acceptance criteria (draft — refined by validate phase)

1. Dragging an image file (jpg/jpeg/png/gif/webp/heic/svg/bmp) from the OS onto an open page
   in the WASM/web build inserts a `![name](../assets/name)` markdown reference into the
   focused/target block, matching JVM behavior for the same interaction.
2. The dropped file's bytes are written into `<graphRoot>/assets/` in OPFS, using the
   existing duplicate-filename suffix convention (`photo.jpg` → `photo-1.jpg`).
3. Dropping a non-image file is ignored (no markdown inserted, no crash), matching JVM's
   `imageExtensions` filter behavior.
4. Dropping outside of a page-view / outside the canvas does nothing (no JS error in the
   browser console).
5. The `expect`/`actual` `pageDropTarget` no-op comment in
   `PageDropTargetModifier.kt` is corrected to no longer claim WASM is unsupported.
6. No regression to JVM/Android/iOS `pageDropTarget` or `MediaAttachmentService` behavior.
7. `./gradlew ciCheck` passes; if a wasm-specific test task exists it is included in the
   verification command captured in validation.md.

## Non-functional / constraints

- Must follow the repo's `Either<DomainError, T>` error convention for the new service
  (`kmp/CLAUDE.md` "Error handling — Arrow Either" section) — no thrown exceptions or
  nullable-as-error at the service boundary.
- Must not block the main/UI thread on large file reads; use the dispatcher matrix in
  `kmp/CLAUDE.md` (`PlatformDispatcher.IO` for non-DB file I/O; WASM's `PlatformDispatcher.IO`
  mapping should be confirmed in research, not assumed).
- JS interop code (event listener, `ArrayBuffer` → `ByteArray` conversion) is new surface
  area on wasmJsMain with no existing test coverage pattern in this repo for DOM event glue
  — research/plan should identify how (or whether) it can be meaningfully tested.
