# Findings: WASM / Web File Input and Coil ByteArray Loading

## Summary
Browser security prevents web pages from accessing the filesystem directly — all file
access is user-initiated via `<input type="file">` or drag-and-drop, returning `File`
objects accessible only through the `FileReader` API or the newer `File.arrayBuffer()`
promise. Kotlin/WASM (wasmJs target) can call these browser APIs via `@JsName` interop.
Coil 3 has experimental WASM support; loading from a `ByteArray` is possible via a
custom `Fetcher` that wraps the in-memory data. There is no persistent filesystem —
"copying to assets" on Web means storing in IndexedDB or downloading.

## Options Surveyed
1. `<input type="file">` — standard browser file picker, no permissions needed
2. File System Access API (`showOpenFilePicker()`) — modern Chrome/Edge only; not in Firefox/Safari as of 2024
3. Drag-and-drop HTML events (`ondragover`, `ondrop`) — all browsers, `DataTransfer.files`
4. Clipboard API (`navigator.clipboard.read()`) — requires user gesture; returns `ClipboardItem`
5. Coil `ByteArray` custom fetcher — load image from in-memory bytes on WASM

## Trade-off Matrix
| Approach | Browser support | Permissions | Persistent access | KMP/WASM interop effort |
|-----------|----------------|-------------|-------------------|------------------------|
| `<input type="file">` | All | None | No | Medium (DOM interop) |
| File System Access API | Chrome/Edge only | Required (prompt) | Yes | Medium |
| Drag-and-drop HTML | All | None | No | Medium (DOM events) |
| Clipboard API | All modern | User gesture | No | Medium |
| Coil `ByteArray` fetcher | WASM only | N/A | N/A | Medium |

## Risk and Failure Modes

### File System Access API — limited browser support
- **Failure mode**: `showOpenFilePicker()` is undefined in Firefox and Safari; calling it
  throws `ReferenceError`.
- **Trigger**: Using File System Access API as the primary picker without fallback.
- **Mitigation**: Use `<input type="file">` as the universal fallback; detect `showOpenFilePicker` availability with `typeof window.showOpenFilePicker !== 'undefined'`.
  [TRAINING_ONLY — verify Safari 2024 status]

### `FileReader.readAsArrayBuffer()` callback not bridged to Kotlin coroutines
- **Failure mode**: `FileReader` is callback-based; bridging to `suspendCoroutine` in
  Kotlin/WASM requires careful `JsAny` interop.
- **Trigger**: Using `FileReader` directly without a coroutine wrapper.
- **Mitigation**: Use `File.arrayBuffer()` (returns a `Promise<ArrayBuffer>`) with Kotlin's
  `await()` extension for JS promises. [TRAINING_ONLY — verify Kotlin/WASM Promise interop]

### Coil WASM support maturity
- **Failure mode**: Coil 3.2.0 WASM support may be incomplete — some `Decoder` or `Fetcher`
  implementations may throw `UnsupportedOperationException` on WASM.
- **Trigger**: Using Coil features that rely on JVM-specific image decoders (e.g., JPEG XL).
- **Mitigation**: Test Coil on the WASM target early. PNG/JPEG/WebP are supported via
  browser's built-in image decoding in Coil's WASM backend. [TRAINING_ONLY — verify]

### "Copying to assets" not possible in browser sandbox
- **Failure mode**: There is no persistent writable filesystem in the browser sandbox —
  writing to `<graphRoot>/assets/` is not possible from a pure browser context.
- **Trigger**: Attempting to use `okio.FileSystem.SYSTEM` on WASM.
- **Mitigation**: On Web, the "graph" is either not applicable (browser-only mode) or uses
  a virtual filesystem (IndexedDB via a JS filesystem abstraction). If Web is in scope,
  the `assets/` write must go through a custom `WebFileSystem` expect/actual backed by
  IndexedDB. Mark Web as "display only" (render URLs) in the initial implementation;
  deferred for a later iteration. This is consistent with the requirements' non-goal of
  "cloud/remote storage."

### Large `ByteArray` in WASM memory
- **Failure mode**: Loading a 10MB image as a `ByteArray` in WASM heap doubles memory
  usage during decoding (original bytes + decoded bitmap). WASM memory is limited and
  cannot exceed the `memory.grow` limit (default often 256MB).
- **Trigger**: User picks a large image file in the browser.
- **Mitigation**: Display images via object URLs (`URL.createObjectURL(File)`) rather than
  decoding into `ByteArray`; pass the object URL string to Coil's network fetcher.
  [TRAINING_ONLY — verify Coil WASM object URL support]

### `<input type="file">` — no programmatic trigger without user gesture
- **Failure mode**: Calling `inputElement.click()` programmatically from a non-user-gesture
  context (e.g., from a coroutine after an async delay) is blocked by browsers as a popup.
- **Trigger**: Triggering the file picker from a non-synchronous user event handler.
- **Mitigation**: Always trigger `input.click()` directly in a synchronous DOM event handler
  (e.g., a Compose `onClick` callback that is on the main thread and synchronous).

## Migration and Adoption Cost
- Kotlin/WASM DOM interop requires `@JsName` annotations and `external` declarations for
  browser APIs, or use of `kotlinx-browser` library.
- `kotlinx-browser` provides `document`, `window`, `FileReader` wrappers.
- WASM file access is a `wasmJsMain` platform-specific implementation — no `commonMain` changes.
- Coil WASM dependency: `coil-compose:3.2.0` with `wasmJs()` target — verify artifact availability.

## Operational Concerns
- Web file access has no logging hooks equivalent to Android's ContentResolver.
- Test in Chrome, Firefox, Safari. Drag-and-drop behavior differs between browsers.
- Memory profiling in Chrome DevTools for large image loads.

## Prior Art and Lessons Learned
- Logseq Web (Electron renderer) uses Node.js `fs` module — not applicable to pure browser WASM.
- Most KMP WASM web apps treat file storage as out-of-scope for the initial implementation.
- The File System Access API is the future but has poor cross-browser support — `<input type="file">` is the safe choice for 2024/2025.

## Open Questions
- [ ] Does Coil 3.2.0 publish WASM artifacts? — blocks: whether Coil is usable on WASM target at all
- [ ] Is `kotlinx-browser`'s `File.arrayBuffer()` accessible from Kotlin/WASM with `await()`? — blocks: coroutine-safe file reading on WASM
- [ ] Is Web/WASM in scope for the initial image attachment implementation? — blocks: whether to implement WASM at all or defer

## Recommendation
**Recommended option**: Defer Web/WASM file attachment to a later iteration. Implement display-only (render `http://` URLs inline via Coil) for the Web target in Phase 1.

**Reasoning**: The browser sandbox makes "copy to local assets folder" semantically inapplicable; implementing a virtual filesystem via IndexedDB is a significant effort orthogonal to the core feature. The requirements say "always local graph folder" — on Web, this concept doesn't apply. Display-only is achievable and useful; attachment can be deferred.

**Conditions that would change this recommendation**: If SteleKit Web is intended to work with a remote graph server (not local files), the attachment flow would upload to the server instead, which is a different architecture.

## Pending Web Searches
1. `Coil 3 wasmJs WASM support image loading 2024` — verify WASM artifact availability
2. `kotlin wasm "File" "arrayBuffer" "await" browser file picker` — verify Promise/await interop
3. `"File System Access API" Safari support 2024` — check current browser support table
4. `kotlinx-browser wasm file input "input type file" kotlin` — find working example

## Web Search Results (verified 2026-05-15)

### Coil 3 WASM support — VERIFIED
- Coil 3 officially supports `wasmJs` target. Artifacts: `io.coil-kt.coil3:coil-compose-wasm-js` and `io.coil-kt.coil3:coil-network-ktor-wasm-js`.
- WASM image decoding uses a **web worker** to avoid blocking the browser main thread.
- Source: [Coil 3.0 release post](https://colinwhite.me/post/coil_3_release), [Maven Central — coil-wasm-js](https://central.sonatype.com/artifact/io.coil-kt.coil3/coil-wasm-js)

### Kotlin WASM file input
- `kotlinx-browser` provides DOM access including `document`, `window`, `HTMLInputElement`.
- `File.arrayBuffer()` returns a `Promise<ArrayBuffer>` which can be `await()`ed in Kotlin/WASM with `kotlinx.coroutines` JS/WASM integration.
- Source: community Slack thread [#compose-web](https://slack-chats.kotlinlang.org/t/18860380/hello-what-is-the-best-way-to-load-images-from-urls-in-compo)

### File System Access API browser support
- As of 2024/2025: Chrome/Edge support it; **Firefox and Safari do not** (Firefox has it behind a flag).
- `<input type="file">` remains the universal cross-browser approach.

### REVISED RECOMMENDATION (confirmed)
Proceed with display-only (render `http://` and `https://` URLs via Coil) on WASM/Web in Phase 1. File attachment on Web requires either virtual filesystem (IndexedDB) or a server — both are out of scope per requirements ("always local graph folder"). `<input type="file">` can be added as a future enhancement for Web once virtual filesystem support exists.
