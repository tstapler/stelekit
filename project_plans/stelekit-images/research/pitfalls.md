# Pitfalls: Image/File Attachment in KMP + Compose Multiplatform

Research date: 2026-05-15
Source findings files: findings-android-saf.md, findings-coil3-fetcher.md,
findings-image-perf.md, findings-file-copy-race.md, findings-drag-drop.md,
findings-ios-picker.md, findings-wasm-web.md

---

## 1. Android SAF — Use PickVisualMedia; copy immediately; no persistent URI needed

**Risk level**: HIGH — wrong approach causes runtime crashes on API 29+.

### Key facts
- `file://` URIs to external storage are blocked on Android 10+ (API 29+); using them throws
  `FileUriExposedException` or silently returns empty data.
- `ActivityResultContracts.PickVisualMedia` requires **no manifest permission**, is backported
  to API 19 via Google Play services, and automatically falls back to `ACTION_OPEN_DOCUMENT`
  when unavailable.
- Coil 3 adds `ContentUriFetcher` (and all other platform fetchers) via **service loader** by
  default — registering a custom asset fetcher with `.components { add(...) }` **appends** to,
  not replaces, those defaults. Safe to add the custom `SteleKitAssetFetcher` without losing
  `content://` URI support.

### Critical pitfalls
| Pitfall | Consequence | Mitigation |
|---------|-------------|------------|
| Storing `content://` URI without `takePersistableUriPermission` | URI invalid after process restart → FileNotFoundException | Copy file immediately on pick; discard URI after |
| Passing `content://` string to `java.io.File()` | Silently creates invalid path | Always use `ContentResolver.openInputStream(uri)` |
| `openInputStream(uri)` returns null for offline cloud content | NullPointerException | Null-check; return `DomainError.Left` |
| Targeting API 33+ with only `READ_EXTERNAL_STORAGE` in manifest | Permission silently rejected | Use `PickVisualMedia` (no permission needed) |

### Recommended approach
`PickVisualMedia` → `openInputStream` → copy to `assets/` → discard URI. No persistent
URI storage. No manifest permissions required for image picking.

---

## 2. Compose Drag-and-Drop — `onExternalDrag` is removed in CMP 1.8.0; use `dragAndDropTarget`

**Risk level**: HIGH — if the project uses any CMP version ≥ 1.8.0, code using
`onExternalDrag` will not compile.

### Key facts
- `Modifier.onExternalDrag` was **deprecated** in CMP 1.7.0 and **removed** in CMP 1.8.0.
- The replacement is `Modifier.dragAndDropTarget` (common code, Desktop + Android only for now).
- Both `dragAndDropSource` and `dragAndDropTarget` are still **experimental** (`@OptIn` required).
- `onExternalDrag` had a known Linux bug: app process did not close after window closed
  (25–33% failure rate). Unknown if `dragAndDropTarget` inherits this.
- External files from the OS file manager are accessed via `dragInfo.transferData?.getNativeFileList()`.

### Critical pitfalls
| Pitfall | Consequence | Mitigation |
|---------|-------------|------------|
| Using `onExternalDrag` on CMP ≥ 1.8 | Compile error | Use `dragAndDropTarget` |
| Using `dragAndDropTarget` without `@OptIn(ExperimentalComposeUiApi::class)` | Compile error | Add opt-in |
| `getNativeFileList()` returns null for non-file drag (e.g., text) | NPE | Null-check before processing |
| Linux app-not-closing regression | Process hangs after window close | Test specifically on Linux; file bug report if reproduced with `dragAndDropTarget` |

### Recommended approach
Use `Modifier.dragAndDropTarget` in `jvmMain` (CMP 1.7+). Wrap in `@OptIn`. Check project's
current CMP version — if already on 1.7.0+, `dragAndDropTarget` is the only viable path.

---

## 3. Coil 3 Custom Fetcher — Use `Mapper` not full `Fetcher`; `DataSource.DISK` not `MEMORY`

**Risk level**: MEDIUM — wrong approach causes cache misses, memory leaks, or slow loads.

### Key facts
- For `../assets/<name>` paths, a `Mapper<String, okio.Path>` that resolves to an absolute
  path is simpler and safer than a full custom `Fetcher`. Coil's built-in `OkioFetcher`
  handles source lifecycle, caching, and thread dispatch automatically.
- `ImageSource` in Coil 3 accepts `okio.Path` with `FileSystem.SYSTEM` directly:
  `ImageSource(file = resolvedPath, fileSystem = FileSystem.SYSTEM)`.
- `LocalGraphRootPath` (a `CompositionLocal`) must be captured at `ImageLoader` construction
  time and passed into the `Mapper`/`Fetcher` factory — it is **not accessible from inside
  the fetcher** (runs off composition thread).
- Loading from `ByteArray` via custom fetcher is measurably slow (GitHub issue #2770) — always
  prefer file-backed path over in-memory bytes.

### Critical pitfalls
| Pitfall | Consequence | Mitigation |
|---------|-------------|------------|
| No `Keyer` registered for custom data type | Every load is a cache miss | Register `Keyer<String>` returning resolved absolute path |
| `DataSource.MEMORY` for a disk file | Disk cache not written → cache thrash on app restart | Use `DataSource.DISK` |
| Reading `CompositionLocal` inside `Fetcher.Factory.create()` | Crash (not on composition thread) | Pass graph root as `() -> Path` lambda into factory constructor |
| `serviceLoaderEnabled(false)` in custom `ImageLoader` | All platform fetchers lost (ContentUriFetcher, NetworkFetcher, etc.) | Never disable service loader unless registering all components manually |

### Recommended approach
`Mapper<String, okio.Path>` per platform. Capture graph root path at `ImageLoader` build
time. Register with `.components { add(SteleKitAssetMapper.Factory(graphRootProvider)) }`.

---

## 4. File Copy — Temp-then-rename for atomicity; serialize writes to eliminate TOCTOU

**Risk level**: MEDIUM — race conditions cause silent file overwrites; partial writes cause
corrupt images visible to Coil.

### Key facts
- The requirements call for sequential suffix naming (`photo.jpg` → `photo-1.jpg`).
  This is a TOCTOU race if two coroutines run concurrently — **serialize all file copy
  operations through `DatabaseWriteActor` or a dedicated single-coroutine IO scope**
  to eliminate the race without changing the naming scheme.
- Write to `<graphRoot>/assets/.tmp-<uuid>` first, then `Files.move(src, dst, ATOMIC_MOVE)`.
  This prevents Coil from observing a partial file.
- Always write the temp file **inside** `<graphRoot>/assets/` (same directory as destination)
  to guarantee same filesystem for atomic rename. Writing to `System.getProperty("java.io.tmpdir")`
  risks a cross-filesystem move where `renameTo()` silently returns `false` (no exception).
- `okio.FileSystem.atomicMove()` is available on JVM/Android but **fails if target already
  exists** — use JVM `Files.move(ATOMIC_MOVE)` instead, which throws a descriptive exception.

### Critical pitfalls
| Pitfall | Consequence | Mitigation |
|---------|-------------|------------|
| Concurrent suffix-scan check-then-copy | Silent file overwrite | Serialize through single coroutine |
| Writing final file directly (no temp) | Coil decodes partial file → broken image | Write to `.tmp-<uuid>`, then rename |
| Temp file in `/tmp` (different filesystem) | `renameTo()` silently returns false | Temp file inside same `assets/` dir |
| Not creating `assets/` dir before copy | FileNotFoundException | `Files.createDirectories(assetsPath)` first |

---

## 5. iOS File Picker — `PHPickerViewController` for gallery; use KMP library if available

**Risk level**: MEDIUM — incorrect API choice, callback threading, and plist omissions cause
crashes.

### Key facts
- `UIImagePickerController` is deprecated for **gallery** use since iOS 14; the camera
  source type is **not deprecated**.
- `PHPickerViewController` (iOS 14+) requires no permission for reading from photo library
  (runs in a separate process; privacy is enforced by the system).
- `NSItemProvider.loadDataRepresentation()` callback runs on a background queue — bridge to
  Kotlin using `suspendCancellableCoroutine` (not `suspendCoroutine`); call
  `continuation.resume()` from within the ObjC callback. Always dispatch presenter call via
  `Dispatchers.Main`.
- **ImagePickerKMP** (`io.github.ismoy:imagepickerkmp`) is an actively maintained open-source
  KMP library (updated 2025) that wraps Android + iOS pickers. Evaluate before writing from
  scratch — if it covers the use case, it eliminates the ObjC interop complexity.

### Critical pitfalls
| Pitfall | Consequence | Mitigation |
|---------|-------------|------------|
| Missing `NSCameraUsageDescription` in Info.plist | Crash on first camera access | Add to `iosMain` Info.plist |
| Presenting `PHPickerViewController` off main thread | "UI updates must be on main thread" crash | `withContext(Dispatchers.Main)` before `presentViewController` |
| Using `suspendCoroutine` (not cancellable) for ObjC callback | Coroutine hangs on cancellation | Use `suspendCancellableCoroutine` |
| Security-scoped URL not accessed | Empty read / permission error | `url.startAccessingSecurityScopedResource()` before reading |

---

## 6. Image Performance — Bound `ImageBlock` size; never use `Size.ORIGINAL` in list views

**Risk level**: LOW-MEDIUM — easy to avoid once known; causes OOM on image-heavy pages.

### Key facts
- Coil 3 samples images to the **composable's layout constraints**. If `ImageBlock` uses
  `Modifier.wrapContentSize()` without bounds, Coil has no target size and may decode at
  full resolution → OOM on 12MP+ images.
- Use `Modifier.fillMaxWidth().heightIn(max = 400.dp)` with `ContentScale.Fit` — standard
  for Logseq/Obsidian-style note UIs, prevents aspect ratio distortion.
- `LazyColumn` with many large images causes memory cache thrashing — bounded display size
  keeps more decoded bitmaps in the cache simultaneously.

---

## 7. WASM / Web — Defer file attachment; display-only is achievable now

**Risk level**: LOW for Phase 1 (if deferred).

### Key facts
- Coil 3 fully supports `wasmJs` — artifacts exist (`coil-compose-wasm-js`, `coil-network-ktor-wasm-js`), and image decoding uses a web worker to avoid blocking the main thread.
- File attachment on Web requires a virtual filesystem (IndexedDB) because the browser sandbox
  has no persistent writable local filesystem — out of scope per requirements ("always local graph folder").
- `<input type="file">` is the cross-browser approach for picking; File System Access API
  (`showOpenFilePicker`) is Chrome/Edge only (not Firefox, not Safari as of 2025).
- **Recommended**: implement display-only for Web/WASM (render `http(s)://` URLs via Coil);
  defer file attachment to a future iteration.

---

## Top 3 Risks (Summary)

### Risk 1 — Drag-and-drop API removed (breaking compile error)
`Modifier.onExternalDrag` was removed in Compose Multiplatform 1.8.0. If the project is on
CMP ≥ 1.8.0 and the implementation uses `onExternalDrag`, it will not compile. The replacement
`Modifier.dragAndDropTarget` is available but still experimental. **Action**: check the project's
CMP version before writing drag-drop code; use `dragAndDropTarget` unconditionally.

### Risk 2 — Coil `Fetcher` / `Mapper` misconfiguration causing cache misses or ContentUri breakage
Two failure modes compound: (a) not registering a `Keyer` means every image load is a cache
miss; (b) accidentally disabling the service loader or replacing components instead of appending
breaks Coil's built-in `ContentUriFetcher` (needed for Android gallery images). **Action**: use
`Mapper<String, okio.Path>` (not full `Fetcher`) to keep Coil's built-in machinery intact;
always append custom components, never disable the service loader.

### Risk 3 — iOS `PHPickerViewController` callback bridging deadlock or crash
`NSItemProvider.loadDataRepresentation()` runs its completion handler on a background OS queue.
If the Kotlin/Native coroutine continuation is resumed incorrectly (wrong thread, or using
non-cancellable `suspendCoroutine`), the app deadlocks or crashes on iOS. **Action**: use
`suspendCancellableCoroutine`; resume on Dispatchers.Main; evaluate **ImagePickerKMP** library
to avoid writing this interop from scratch.
