# Research: stack (web image management)

Labels: VERIFIED (read in this repo), INFERRED (browser/platform knowledge reasoned from docs, not run here), UNVERIFIED (needs a spike).

## 1. What the repo already uses (VERIFIED)

- Compose Multiplatform for Web (wasmJs), Kotlin/Wasm; Skia backs `ImageBitmap` (`ImageEncoder.wasmJs.kt` doc). Entry: `browser/Main.kt` `ComposeViewport`.
- Coil 3.2.0 `coil-compose` + `coil-network-ktor3` (`kmp/build.gradle.kts:118-119`); Ktor client with the JS engine for wasmJs (`:247-250`).
- Hand-rolled JS interop with `js("...")` (ADR-001 of `wasm-image-drop`); no `kotlinx-browser`. Byte marshaling is a base64 string bridge: `ByteBufferInterop.kt:21,36` (`toJsUint8Array`, `jsArrayBufferToByteArray`), per ADR-002 of `wasm-image-drop`, because Kotlin/Wasm has no zero-copy view between `ByteArray` and `Uint8Array`.
- OPFS access through `OpfsInterop.kt` (`opfsWriteFileBytes:181` uses `createWritable` on the main thread), File System Access host folders through `HostDirectoryInterop.kt`/`HostDirectorySync.kt`.
- SQLite via `@sqlite.org/sqlite-wasm` 3.46.1 in a module worker (`resources/sqlite-stelekit-worker.js`, `db/WasmOpfsSqlDriver.kt`). There is already a worker in the build, which is the natural home for any sync-access-handle file work (decision D2).
- Git write-back by the GitHub/GitLab REST Data API (ADR-015 of `docs/adr`), not a git protocol client.
- `kotlin.test` + `kotest-property` in `commonTest`; `kotlin("test-wasm-js")` in `wasmJsTest` (`build.gradle.kts:255-257`).
- Excluded from wasmJs: OpenTelemetry, ktoml (`build.gradle.kts:138-141`), i.e. dependencies must be wasm-bytecode safe; any new library needs a spike.

## 2. Browser APIs the feature needs (INFERRED unless noted)

| Need | API | Support | Notes |
|---|---|---|---|
| Stream a `File` into OPFS without a Kotlin copy | `FileSystemWritableFileStream.write(Blob)` | `createWritable` on OPFS handles: Chromium, Firefox 111+; Safari: INFERRED not available on the main thread, only `createSyncAccessHandle` inside a worker | Spike S3. Decision D2. |
| Cheap file metadata | `FileSystemFileHandle.getFile()` returns a disk-backed `File` (`size`, `lastModified`, `slice`) | all OPFS-capable browsers | already used (`readOpfsFileAsObjectUrl`) |
| Hash without Kotlin copy | `crypto.subtle.digest('SHA-256', await file.arrayBuffer())` | all; `crypto.subtle` needs a secure context (https or localhost) | the buffer lives in JS memory, only a hex string crosses |
| Decode and downscale | `createImageBitmap(blob, {resizeWidth, resizeHeight, resizeQuality, imageOrientation:'from-image'})` + `OffscreenCanvas.convertToBlob({type:'image/jpeg', quality})` | Chromium, Firefox, Safari 16.4+ for OffscreenCanvas 2D (INFERRED) | resize-at-decode avoids full-size decode in many engines; HEIC decodes only in Safari |
| Blob URLs | `URL.createObjectURL(File)` / `revokeObjectURL` | all | existing |
| Clipboard images | DOM `paste` event, `ClipboardEvent.clipboardData.files`; or async `navigator.clipboard.read()` (permission prompt, Chromium/Safari) | `paste` event is universal | Spike S2: does Compose/Wasm let the event reach `document`? |
| Camera | `<input type=file accept=image/* capture=environment>` on mobile; `navigator.mediaDevices.getUserMedia({video})` for a desktop viewfinder | secure context required | `capture` on desktop just opens a picker |
| Share Target | `manifest.webmanifest` `share_target` (`method: POST`, `enctype: multipart/form-data`, `params.files`) + service worker `fetch` handler for the POST | installed PWA on Android Chrome, desktop Chrome/Edge; not iOS Safari (INFERRED) | needs SW to stash files then redirect (decision D8) |
| Quota | `navigator.storage.estimate()`, `navigator.storage.persist()`, `QuotaExceededError` | all modern | Safari and Firefox policy differs (section 4) |
| Direct host-folder write | File System Access `showDirectoryPicker`, `createWritable` | Chromium only (repo already gates with `showDirectoryPickerSupported()`, `Main.kt` `markGraphDialogCapable`) | existing |
| EXIF | none needed if `imageOrientation:'from-image'` is honored; `ExifOrientationReader.kt` exists in commonMain for sidecar metadata | | |

## 3. Candidate engines for on-device enrichment (decision D6)

All INFERRED/UNVERIFIED; each needs a compatibility spike before commitment, and each is lazy-loaded so it never adds to first paint.

| Feature | Option A (recommended) | Option B | Notes |
|---|---|---|---|
| OCR (`TextRecognizer`) | `tesseract.js` (WASM Tesseract, worker, traineddata ~ 2-15 MB per language, downloaded and cached) | Shape Detection API `TextDetector` (Chromium behind a flag; unreliable) | Option A is the realistic one. |
| Image labeling (`ImageLabeler`) | ONNX Runtime Web or Transformers.js running a small classifier (MobileNet/CLIP-tiny, ~ 5-30 MB) | TF.js MobileNet | ORT-web supports WebGPU/WASM EP. |
| Monocular depth (`MonocularDepthEstimator`) | Transformers.js Depth-Anything-small (~ 25-100 MB model, WebGPU strongly preferred) | none | Large download; gate behind explicit user action and quota check. |
| PDF text (`PdfTextExtractor`) | `pdf.js` | n/a | Not image management; out of this project unless cheap. |

Script origins: the app is a hosted PWA; loading scripts from a CDN at runtime contradicts "local-first, no cloud" and may conflict with a CSP. Recommended default: vendor the library and model files into the build output or load them from the same origin, cached by a versioned SW cache; never from a third-party CDN. (INFERRED; the repo's current CSP, if any, was not audited: see pitfalls.)

## 4. Persistence and quota facts (INFERRED from public documentation; verify in spikes)

- OPFS is origin-private storage subject to the origin quota. Chromium: a large fraction of free disk shared per origin (best-effort mode can be evicted under pressure unless `persist()` is granted). Firefox: quota is a fraction of disk with group limits; `persist()` shows a prompt. Safari: stricter quotas, and storage for sites not added to the Home Screen can be evicted after about seven days without interaction (ITP-style). Exact numbers change; the app must read `estimate()` rather than hard-code.
- IndexedDB shares the same origin quota as OPFS. The repo already uses IndexedDB for host-folder handles (`HostDirectoryInterop*`). Storing image bytes in IndexedDB buys nothing over OPFS for large binaries (more copies, slower) and splits the persistence story; rejected in ADR-001.
- File System Access host folder (Chromium only) is the only store that survives "clear site data" and eviction; the repo's web sync design treats it as the durable mirror (`HostDirectorySync`, `HostReconnectBanner`, `BrowserOnlySyncBanner`).
- Git remote (GitHub/GitLab) is the third durable copy and the only cross-device one.

## 5. Memory in Wasm (INFERRED, spike S4 measures)

- Kotlin/Wasm `ByteArray` lives in the Wasm GC heap; V8 limits the total Wasm GC heap per tab (order of a few GiB on desktop, much lower on mobile and iOS). A 50 MiB image through the existing bridge needs, simultaneously: the JS `ArrayBuffer` (50 MiB), the base64 JS string (about 67 MB, 1 byte/char if Latin-1 internal repr else 2x), the Kotlin string copy across the boundary, then the decoded `ByteArray` (50 MiB). Peak is a multiple of the file size; the 50 MiB drop cap was chosen with this in mind (`PageDropTarget.kt:38-43`).
- `Base64.encode` in Kotlin/Wasm was measured slow in this repo (about 3.3 s for 3000x2000 RGBA, per `ImageEncoderInterop.kt` doc), so any design that base64-encodes images in Kotlin should be avoided; do the base64 in JS (`FileReader.readAsDataURL` or `btoa` over chunks) and pass only references.
- The principle adopted by the plan: images stay in JS-owned objects (`File`/`Blob`/`ArrayBuffer`) referenced from Kotlin by opaque `JsAny` handles; Kotlin sees metadata (size, hash, mime head up to 4 KiB). Full `ByteArray` materialization happens only for features that must (annotation editor decode is the main one; it needs the pixels anyway and is bounded by `MAX_CANVAS_AREA_PX`).

## 6. Git hosts API limits relevant to images (INFERRED from docs)

- GitHub `POST /git/blobs`: up to 100 MB per blob; body is JSON with base64 content (so a 75 MB image is a 100 MB request). Repo sets `MAX_BLOB_BYTES = 75_000_000` (`WasmGitWriteService.kt:1324`) which keeps base64 under 100 MB. Secondary rate limits apply to content-creating requests; the service already bounds concurrency (`createBlobsBounded`).
- GitLab Commits API accepts base64 or text actions in one commit call; request-size limits are instance-configured.
- Git LFS: see `web-lfs.md` in `project_plans/git-lfs-support/research/` (CORS matrix VERIFIED on 2026-10-11): authenticated batch calls from the browser are blocked on github.com and gitlab.com by preflight; media/raw read endpoints are CORS-open; Bitbucket/Azure preflights pass.

## 7. Dependencies decision summary

- No new Gradle dependency for Phases 1 to 5 (all browser APIs through the existing `js()` interop).
- Phase 6 (ML) adds npm/vendored assets only after spikes; each addition is its own ADR.
- Optional: `kotlinx-browser` would give typed `File`/`Blob`/`DataTransfer` but ADR-001 of `wasm-image-drop` rejected it; this plan keeps that decision and adds interop in one new file per concern (see architecture.md).
