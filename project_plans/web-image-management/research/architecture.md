# Research: architecture (target design for web image management)

Evidence labels as in `requirements.md`. File:line facts are in `current-state.md`; this file derives the design from them.

## 0. Root-cause statement

"Image management does not work on web" has one structural root cause and several leaf gaps.

**Root cause (VERIFIED)**: the web `FileSystem` treats a graph as text. Text lives in `cache`; the only places binary can live are `bytesCache` (whole arrays in the Wasm heap, written by `writeFileBytes`), `blobUrlCache` (a path to blob-URL map for images), or raw OPFS (written by the attach service, invisible to everything else). The shared commonMain services (`AssetIndexService`, `AssetMoveService`, `ImageDerivativeService`, `ImageImportService`, `AssetCopier`, git write-back, host sync) all assume a `FileSystem` that can list, size, read, rename and write binary files synchronously. Web satisfies that for text only. Each service therefore fails or silently no-ops on web (details: `current-state.md` section 2), and the one path that works (attach) works by bypassing the file system, which is why it also bypasses dirty tracking and sync.

**Leaf gaps (VERIFIED)**: no paste/camera/share ingress; `PlatformImageDownscaler` returns null; asset index switch hidden; remote-originated images are never downloaded (web fetches remote content with `bodyAsText()` only: `WasmGitWriteService.kt:440-480`, and the section sync filters `.md`: `WasmSectionSyncService.kt:56`); LFS has only the guard; ML capabilities are `NONE`.

## 1. Design principle: JS owns the bytes, Kotlin owns the metadata

Kotlin/Wasm cannot share memory with JS (`ByteBufferInterop.kt:6-9`), so every Kotlin `ByteArray` of an image costs a multi-copy bridge. The design therefore keeps image content in JS objects and OPFS, and lets Kotlin hold only:
- a **metadata index** (path, size, mtime, content hash if known, display URL), small and synchronous;
- **opaque handles** (`JsAny`) to `File`/`Blob` during an operation;
- **bounded byte slices** (mime sniff head of 4 KiB; whole `ByteArray` only on explicit, capped, suspending reads, e.g. the annotation editor).

## 2. Components

### 2.1 `WebBinaryStore` (new, wasmJsMain; collaborator, not a `PlatformFileSystem` field)

Owns, per graph: the OPFS directory handles for binary content, the in-memory `BinaryIndex` (replaces `blobUrlCache` and the image half of `bytesCache`), and blob-URL lifecycle (create lazily, revoke on delete/graph switch/LRU). `PlatformFileSystem` delegates to it with one-line overrides (the repo's established pattern: `hostDirectorySync`, `GraphSyncSession`). It fixes, by construction, the verified gaps:
- `listFiles`, `listDirectories`, `directoryExists`, `fileExists`, `getFileSize`, `getLastModifiedTime` answer for binaries from the index (sync, no content read). Today `listFiles` and `directoryExists` look only at `cache` (`PlatformFileSystem.kt:492,584`).
- `renameFile` and `deleteFile` handle binaries (OPFS move = copy + delete since `FileSystemHandle.move()` is not universal, INFERRED; keep the index and blob URL consistent).
- `resolveAssetUri` returns the blob URL (`PlatformFileSystem.kt:875`).
- Content reads: new suspend methods on the commonMain `FileSystem` interface, defaulting to the existing sync behavior so other platforms are untouched (precedent: `readFileSuspend`, `FileSystem.kt:9`):
  - `suspend fun readFileBytesSuspend(path): Either<DomainError, ByteArray?>`-shaped; to keep the interface style of that file (nullable returns), the plan specifies `suspend fun readFileBytesBounded(path, maxBytes): ByteArray?` and `suspend fun readFileHead(path, n): ByteArray?` and `suspend fun contentHashHex(path): String?` as the three binary reads commonMain callers need. Final shapes are Task E1.1a's decision; Either wrapping happens in the service layer that already uses Either (`AssetIndexService.registerAsset` returns `Either`).
  - Writes: `writeFileBytes` (sync, returns Boolean, existing contract) is kept for small/paranoid payloads. A new `suspend fun writeFileStream(path, source: FileSource)` carries large images, where `FileSource` is a commonMain `sealed interface` (`Bytes(ByteArray)` and a platform-opaque `Handle(Any)`), so JVM/Android implement it trivially and web streams a `File` into OPFS with no Kotlin copy.

### 2.2 Unified binary dirty tracking

A binary write calls the same `recordDirty(path, WRITE)` as text (`PlatformFileSystem.kt:304`), but the dirty entry carries no bytes. At push time the write service asks the store for the content (section 2.6). This makes a single source of truth for "what changed" across git write-back and host write-through; today the attach path records nothing (`WasmMediaAttachmentService.kt:81-101`).

### 2.3 Ingress layer (new commonMain port + wasm actuals)

`ImageIngress` is not new UI; it is a set of event sources feeding the existing `MediaAttachmentService`:
- picker (multi-select `<input multiple>`), drop (existing body listener, extended), paste (DOM `paste` listener sharing the same machinery as the drop listener), camera (`capture` input + `getUserMedia` dialog actual for `CameraViewfinderDialog`), share target (SW stash, section 2.9).
- All five produce a list of `WebImageFile(handle, name, mime, size)` and call one `WasmMediaAttachmentService.attachFiles(...)` that validates (pure commonMain `AttachmentPolicy`: extension/mime allow-list, size caps, name sanitization, unique-name selection) then streams into the store. `AttachmentPolicy` is pure, lives in commonMain, and is tested in `commonTest` (property tests for name sanitization and uniqueness).
- Replace the pure `hasClipboardImage()` sync probe with an event-driven path: the paste event delivers files directly to `onPasteImage`. `MediaAttachmentService.hasClipboardImage` stays false on web; `EditorCapabilities.onPasteImage` is fed by the DOM event (the capability is only invoked on a user paste gesture, so no polling is needed).

### 2.4 Asset index on web

No code change to `AssetIndexService` logic beyond calling the new suspend reads (it already bounds memory: lazy walk, 8 MiB hash cap). Required: (a) make `walkFiles`/`listFiles` see binaries (2.1); (b) replace `fileSystem.readFileBytes` in `buildAssetEntry` (`AssetIndexService.kt:102-106`) with head-read + store-computed hash so files above the cap are still hashed in JS, not skipped (decision: index-level dedupe for large files is nice-to-have); (c) turn the switch on for web (decision D5). The `AssetIndexingController` crash-loop breaker and `PipelineRunMarker` are settings-backed and work on web `PlatformSettings` (INFERRED; test).

### 2.5 Tiers

`ImageDerivativeService.generate` runs a synchronous `ImageDownscalerPort.downscale(ByteArray...)` inside `withContext(IO)` (`ImageDerivativeService.kt:40-60,75`). A browser decode is async and the input should not be a `ByteArray`. Add a commonMain path-based async port, `ImageDerivativeBackend.generate(fs, original, targets): Either<..>`, with the existing sync-port-based implementation wrapped for JVM/Android/iOS and a new web implementation that does `createImageBitmap(file, {resize*})` → `OffscreenCanvas.convertToBlob('image/jpeg', 0.85)` → store write, entirely in JS. Derivatives go in `<graph>/.stelekit/cache/images/` (existing layout) and are marked **local-only**: excluded from dirty tracking, git, host write-through, zip export, and reconciliation. The exclusion list must reuse/extend `isIgnoredHostEntryName` (used in `PlatformFileSystem.kt:434`). If this is missed, thumbnails are pushed to the remote: it is the highest-probability silent bug of the design (pitfalls P3).

### 2.6 Git write-back and host sync for binaries

- Write service: `getContentBytes` stays for text and paranoid bytes; add a binary branch: for a dirty WRITE under a binary classification, obtain a **JS-side blob creation** call: `createBlobFromStore(path)` which (in JS) reads the OPFS `File`, produces the base64 string (`FileReader.readAsDataURL` then strip prefix, or chunked `btoa`), and `fetch`es the host blob endpoint with the same auth header; Kotlin receives only the returned sha. The LFS gate (`WasmLfsBlobGate.requireNotRawAtTrackedPath`) must still run before this call, with the content probe `WasmLfsContentProbe` fed from the index (size) and `readHead` (pointer sniff), so the safety floor is unchanged and `LfsStagingCallSitesStructuralTest` keeps passing (the structural test greps for `createBlob(` guarded by `lfs.gate`, so the JS-side call must be reached through the same `createBlob` function; implement as an overload that takes a `BlobContent` sealed type rather than adding a second call site: INFERRED requirement, verify the regexes at `LfsStagingCallSitesStructuralTest.kt:110-114`).
- Size/rate: honor `MAX_BLOB_BYTES` (`WasmGitWriteService.kt:1324`); bound in-flight base64 bytes (not just request count); typed `FileTooLarge` already exists and surfaces the path.
- Host write-through: `HostWritePayload` gets a `FromStore(path)` variant; `flushHostWrite` streams `File` → host handle `createWritable().write(file)`. Reconciliation (`HostDirectorySync.kt:1276-1292`) stops calling `readOpfsFileAsBytes` + `setBytes` for non-text files and instead copies handle-to-handle and records metadata only.
- Remote to local: binary raw fetch for remote-originated images (section 2.8).

### 2.7 Display

`resolveAssetUri` returns `blob:` URLs. Whether Coil renders them is spike S1. If not, add a wasm `BlobUrlFetcher` registered by `buildSteleKitImageLoader` (in `components {}`; the factory is common code, so it needs an `expect`/`actual` hook or a platform-provided `ComponentRegistry` contributor, in the style of `rememberSteleKitImageLoader`), which `fetch(blobUrl)` and hands the bytes to Coil's decoder. With tiers in place the bytes are small (display tier about 300 KB). The lightbox/original path loads one full image at a time; the shared memory cache is already bounded to 10 percent of the heap (`SteleKitAssetFetcher.kt:78-86`).
Blob-URL lifecycle: today never revoked (VERIFIED in features.md edge case 13); the store revokes on delete, on graph switch, and via an LRU cap (default 256 live URLs) re-creating lazily from the OPFS `File`.

### 2.8 Remote-originated images and LFS (read)

- Plain git images: on demand, when `rememberTieredImageModel` finds no local file for `../assets/x`, a `RemoteAssetFetcher` (commonMain interface `RemoteAssetSource`, web actual using the Data API / raw endpoints) downloads bytes into OPFS via the store, then the index/blob URL appear. GitHub: `api.github.com/repos/o/r/contents/<path>?ref=` with `Accept: application/vnd.github.raw` (CORS-open per `git-lfs-support/research/web-lfs.md` matrix, INFERRED to return raw bytes up to 100 MB; verify) or `raw.githubusercontent.com` for public repos; GitLab `api/v4/.../repository/files/:path/raw`.
- LFS-tracked images: after fetch, the bytes are a pointer (`LfsPointer` parse, commonMain `git/lfs/LfsPointer.kt`). `LfsPointerResolver` (pure, commonMain) decides; `WebLfsReadService` fetches the object via `media.githubusercontent.com/media/<o>/<r>/<ref>/<path>` (GitHub) or `.../raw?lfs=true` (GitLab) (both CORS-open, VERIFIED 2026-10-11 in `web-lfs.md`), or the batch endpoint when the CORS probe passes (Bitbucket/Azure). Objects are stored content-addressed at `.stelekit/lfs/objects/<oid>` (local-only), verified by SHA-256 and size before use, and the image path in the working tree stays the pointer (so git sees no change) while display resolves pointer → object. This is the same cache layout `git-lfs-support` ADR-004 designed (`OPFS LfsObjectStore`).
- The LFS protocol client currently exists only in `jvmCommonMain` using `java.io.File` and ktor's JVM channel adapters (`LfsBatchClient.kt:36`, imports verified). ADR-004 of `git-lfs-support` intended commonMain; it was not executed. This plan includes extracting the protocol core (request/response types, error mapping, retry policy) to commonMain with a transfer port, leaving `java.io` glue in `jvmCommonMain` (epic E9).

### 2.9 Share target

`manifest.webmanifest` gains `share_target { action: "./share-target", method: "POST", enctype: "multipart/form-data", params: { files: [{name: "images", accept: ["image/*"]}] } }`. The service worker (`stelekit-service-worker.js`) intercepts POST `./share-target`, writes the files to OPFS `/_inbox/` (SW can use OPFS in Chromium; if not, `Cache` API; INFERRED, spike S5), and redirects (303) to `./?share=<id>`. App boot reads the inbox, offers "Add N shared images to <today's journal / chosen page>", then clears it. The SW has existing tests (`looks-like-app-shell.test.js`); extend them. The share target is a Chromium/Android/desktop-PWA feature; no iOS Safari (INFERRED); UI hides "install to share" hints there.

### 2.10 Persistence and quota

`StorageHealth` (commonMain pure model + wasm actual reading `navigator.storage.estimate()/persist()/persisted()`): states `Persisted`, `BestEffort`, `NearQuota(used, quota)`, `Unavailable`. Surfaced in (a) the Assets browser header, (b) the browser-only banner text, (c) a typed `DomainError.AttachmentError.QuotaExceeded` returned by the store on `QuotaExceededError`. `persist()` is requested on the first successful attach (user gesture context), not at startup. The storage backend decision (OPFS working store + optional host-folder mirror + git) is ADR-001.

### 2.11 Annotation editor and ML

- Editor: needs `readFileBytesBounded` for decode and `writeFileStream`/`writeFileBytes` for sidecar and exported JPEG. `ImageEncoder.wasmJs.kt` exists (canvas JPEG, area-guarded). Pixel-buffer handoff already single-string (`ImageEncoderInterop.kt`).
- Camera-dependent features (`SensorModule.cameraProvider`) get a `WebCameraProvider` returning a `PlatformImageFile` whose `path` is a staged OPFS file (satisfies the "path readable via `FileSystem.readFileBytes`" contract in `PlatformImageFile.kt` doc once reads are real).
- BLE, ARKit/LiDAR: capability-gated off (`ExternalMeasurementDevice` entry points not rendered when unavailable; D7).
- ML plugins: implement `TextRecognizer`, `ImageLabeler`, `MonocularDepthEstimator` over lazy-loaded engines; `PlatformCaps` for web is built from capability probes (WebGPU/WASM threads, SharedArrayBuffer needs cross-origin isolation headers, INFERRED: static hosting like GitHub Pages cannot set COOP/COEP, so multithreaded ORT is unavailable; single-threaded WASM is slower: pitfalls P9).
- All pipeline plugins already run under `AssetPipelineService` with pause on memory pressure (`GraphContentAssetSetup.kt:85-94`); web must feed `MemoryPressureListeners` (INFERRED: `performance.memory`/`navigator.deviceMemory` heuristics only; Chromium-only; also `visibilitychange`).

## 3. Layering and constraint compliance

```
commonMain (pure, tested in commonTest)        wasmJsMain (JS interop, tested in wasmJsTest)
-----------------------------------------      ---------------------------------------------
AttachmentPolicy, UniqueName, MimeSniffer      WebBinaryStore (OPFS, index, blob URLs)
BinaryIndex model, BinaryClassifier            WebImageIngress (picker/drop/paste/camera/share)
FileSource, FileSystem suspend reads/writes    WebImageDerivativeBackend (createImageBitmap)
ImageDerivativeBackend (port) + adapters       JsBlobUpload (JS-side base64 + fetch)
LfsPointerResolver, LfsProtocolClient          WebLfsObjectStore (OPFS), RemoteAssetSource actual
StorageHealth model, error mapping             StorageHealth actual, CameraViewfinder actual
RemoteAssetSource (port)                       Service worker share-target handler (JS)
```
- Errors: all new service-level methods return `Either<DomainError, T>`; new `DomainError` variants (`AttachmentError.QuotaExceeded`, `.TooLarge`, `.UnsupportedType`, `.StorageUnavailable`) each get a `displayMessage` arm in `DomainError.kt` (the `when` at `:381-383` must stay exhaustive).
- DB writes: asset rows only via `AssetIndexService` through `DatabaseWriteActor` (already so). No new tables are planned; if a table is added it must also appear in `MigrationRunner.all` (rule enforced by `MigrationRunnerSchemaSyncTest`) and the generated SQLDelight sources must be regenerated.
- Cross-graph: `AssetCopier` is called by `PageMergeService` through `TargetWriterRouter`'s active-graph writer on web (web is `Pull` only). Its `readFileBytes`/`writeFileBytes` uses (`AssetCopier.kt:104-154`) move to the new suspend, bounded API; large assets still go through the router's lock; no web-specific second writer is introduced.
- Wiring (VERIFIED): `WiringAudit.SEAMS` = BackgroundSyncScheduler, AssetPipelinePlugin, ConsoleCommand, MeasurementDeviceFactory, WaybackMachineService, CameraProvider (`businessTest/.../wiring/WiringAudit.kt:18-25`); `WiringRoots` lists `jvmMain/.../desktop/Main.kt`, the Android app roots and a few commonMain roots, and does NOT list `wasmJsMain/.../browser/Main.kt` (`WiringRoots.kt:13-17`). A `WebCameraProvider : CameraProvider` constructed only in the web `Main.kt` would fail `WiringCompletenessTest`; the fix is one line adding the wasm `Main.kt` to `WiringRoots` plus a `wiring-catalog.txt` line with an entry-point test and `ObservableEffect.assert`, or a dated allowlist entry (CLAUDE.md rule). Web ML plugins should wrap the existing commonMain `OcrPlugin`/`OnDeviceLabelingPlugin` over new `TextRecognizer`/`ImageLabeler` implementations (those interfaces are not seams), avoiding new `AssetPipelinePlugin` implementations.
- Coroutines: wasm has a single-threaded `Dispatchers.Default`; JS promises are awaited with `kotlinx.coroutines.await`; no `runBlocking` in `commonTest`; uncaught-handler on every long-lived scope (`WebBinaryStore` owns one with `CoroutineExceptionHandler`, matching `dropScope` in `PageDropTarget.kt:31-35`).

## 4. Alternatives considered

| Alternative | Verdict |
|---|---|
| Keep `bytesCache` for images, just add `readFileBytes` returning it | Rejected: whole-graph image set in the Wasm heap (EXISTING reconcile path already does this, `HostDirectorySync.kt:1276-1292`); the main OOM risk. |
| Store images in IndexedDB | Rejected in ADR-001 (same quota, extra copies, second persistence story). |
| Overlay DOM `<img>` elements instead of Coil | Only if S1 fails and the Coil fetcher is not viable; DOM overlays fight Compose layout and accessibility (D3). |
| Do everything in a Web Worker with `createSyncAccessHandle` | Kept as the Safari fallback (D2) and for derivative generation off the main thread; not the default for Chromium/Firefox because `createWritable` is simpler. |
| Convert all images to JPEG/WebP on ingest | Rejected as default: lossy, changes user data, breaks content-hash dedupe against desktop; offered only for HEIC on Safari (D10). |
| Hosted LFS proxy for web writes | Already rejected by the owner (`git-lfs-support` O1). |
