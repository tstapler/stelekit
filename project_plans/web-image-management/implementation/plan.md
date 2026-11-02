# Implementation Plan: web-image-management

**Feature**: make SteleKit image management (attach, store, index, browse, view at the right size, annotate, enrich, sync, Git LFS) work on the web (wasmJs) target.
**Date**: 2026-11-01
**Status**: Ready for implementation after the owner answers the decisions in section "Decisions needing the owner" (every decision has a recommended default, so work can start with Phase 0 spikes without waiting).
**ADRs**: `decisions/ADR-001-web-storage-backend.md`, `decisions/ADR-002-js-owned-bytes-and-binary-store.md`, `decisions/ADR-003-web-lfs-image-slice.md`
**Inputs**: `requirements.md`, `research/{current-state,stack,features,architecture,pitfalls}.md`
**Related plans**: `project_plans/git-lfs-support` (its Phase 3 / Epics E3 and E4 are the web LFS design; this plan consumes and executes the image-facing slice, see ADR-003), `project_plans/wasm-image-drop` (shipped picker/drop), `project_plans/asset-management`, `project_plans/image-pipeline`.

Evidence labels: VERIFIED / INFERRED / UNVERIFIED as in `requirements.md`. The plan's own assumptions about browsers are INFERRED until the Phase 0 spike that covers them has run.

---

## Cost classes (price-weighted, no human time)

Per `~/.claude/skills/sdd/skills/ESTIMATION.md`. One task = one worker-agent run in one focused context, at most 3 to 5 files. Class bands are INFERRED; recalibrate after the first implementer run.

| Class | Raw tokens per run | Shape here |
|---|---|---|
| XS | under 30k | one config, one small function, pattern exists |
| S | 30k to 80k | a component plus its test, 2 to 3 files |
| M | 80k to 180k | a feature across layers, browser-interop plus tests, 3 to 5 files |
| L | 180k to 300k | not used: every candidate L was split |

Task midpoints: XS 20k, S 55k, M 130k. Story cost = sum of midpoints times a verification multiplier of 1.75 (spec and quality review per story, `6-verify`, CI reruns, wasm browser test reruns). CU weights: fresh input 1, cache write 1.25, cache read 0.1, output 5 (INFERRED). Wall-clock blockers are named in their own section and are the only place calendar time appears.

---

## Domain glossary

| Term | Meaning |
|---|---|
| Binary asset | a non-text file in the graph, in practice an image (also PDF, audio) stored under `<notes>/assets/` |
| `WebBinaryStore` | new wasmJsMain collaborator that owns binary content in OPFS, the metadata index and blob URLs (ADR-002) |
| `BinaryIndex` | in-memory map path to `BinaryMeta(size, mtime, hash?, displayUrl?)`; no content bytes |
| `FileSource` | commonMain `sealed interface`: `Bytes(ByteArray)` or platform-opaque `Handle(Any)` (a JS `File` on web) |
| Local-only path | a path under the graph that is never dirty-tracked, pushed, host-written, zipped or reconciled: `.stelekit/cache/**`, `.stelekit/lfs/**`, `_inbox/**` |
| Tier | `THUMBNAIL` / `DISPLAY` / `ORIGINAL` (`ImageTiers.kt:13`) |
| Held file | a dirty file the LFS guard left out of a commit (`WasmGitWriteService.lfsHeldPaths()`) |
| Write mode | `Direct` / `Converter` / `ReadOnly` (git-lfs-support ADR-002 as amended: Proxy rejected) |

---

## Dependency visualization

```
E0 spikes (S1..S7) ─────────────┬───────────────────────────────┐
  S1 Coil blob   ──► E4.3        │                               │
  S2 paste       ──► E5.1        │                               │
  S3 Safari OPFS ──► E1.2, E10.3 │                               │
  S4 heap        ──► E1.4        │                               │
  S5 share/SW    ──► E5.3        │                               │
  S6 GH blob     ──► E8.1        │                               │
  S7 ML compat   ──► E7          │                               │
                                 ▼                               │
E1 binary store ──► E2 attach ──► E3 index/browser/move/copy     │
   │  │                 │            │                            │
   │  └────────────────►E4 tiers/display ◄── S1                  │
   │                    │                                         │
   ├──────► E5 paste/camera/share/folder import                   │
   ├──────► E6 annotation + import pipeline (needs E1, E4)        │
   ├──────► E8 sync: git, host, zip, remote fetch (needs E1)      │
   │            │                                                 │
   │            └──► E9 LFS (needs E8.3 remote fetch, E1, E8.1)   │
   ├──────► E10 persistence/quota/memory (needs E1; E10.3 needs S3)
   └──────► E7 on-device enrichment (needs E3, E4; last)          │
                                                                  │
E11 docs/QA/close-out ◄───────────────────────────────────────────┘
```
**Ordering hazard (VERIFIED, see task 8.1.1c)**: the moment images are dirty-tracked without bytes (E1.3.1a, E2.2.1a), the existing web push fails the whole batch with `CommitFailed` and the LFS guard skips them. Therefore binary dirty recording ships behind a build-time constant `WebBinaryDirtyTracking.enabled` (default false until E8.1.1a/c are merged), and no release containing E2 may omit E8.1. The flag is removed in task 11.1f.

Critical path: S3 and S4 → E1 → E2 → E8 → E9. Parallel after E1: E3, E4, E5, E6, E10 run as independent waves.

Waves (agent fan-out): W0 spikes (7 parallel, browser-run ones serialized on the owner's devices); W1 E1; W2 E2 + E4.1; W3 E3, E4.2/4.3, E5, E6, E10 in parallel; W4 E8; W5 E9; W6 E7; W7 E11.

---

## Phase 0: Spikes (riskiest first; each produces a short note appended to the relevant research file and a go/no-go on a decision)

### Epic E0: Browser capability spikes
**Goal**: convert the INFERRED browser claims that gate design choices into VERIFIED facts before building on them. Each spike is a throwaway `wasmJsTest` or a manual page; results recorded in `research/spike-results.md` (created by the spike, not by this plan).

- Task E0.S1 (S): Coil renders a `blob:` URL on wasmJs? Add `wasmJsTest/kotlin/dev/stapler/stelekit/ui/components/BlobUrlImageRenderTest.kt`: create a PNG `Blob` in JS, `URL.createObjectURL`, compose `AsyncImage(model = blobUrl, imageLoader = buildSteleKitImageLoader(...))`, assert the image painter reaches Success. Decides D3 and E4.3.
- Task E0.S2 (S): does the DOM `paste` event fire on `document` while Compose/Wasm has focus, and does `clipboardData.files` carry an `image/png`? Page + `wasmJsTest/.../PasteEventSpikeTest.kt` dispatching a synthetic `ClipboardEvent`; real Ctrl+V run is manual (wall-clock blocker B2). Decides E5.1.
- Task E0.S3 (M): `FileSystemFileHandle.createWritable()` on OPFS in Safari (desktop and iOS), Firefox, Chromium; and `createSyncAccessHandle` in the existing module worker. Manual matrix (blocker B1) plus `OpfsInteropTest` additions. Decides D2.
- Task E0.S4 (S): heap measurement: attach a 50 MiB file through the current `persistAttachment` path and the JS-owned path; record `performance.memory.usedJSHeapSize` and Wasm memory pages. Files: `wasmJsTest/.../MemoryProfileSpikeTest.kt`. Sets D13's budget.
- Task E0.S5 (M): PWA share target: manifest `share_target` + service-worker POST interception writing to OPFS from the SW; check OPFS access inside a SW. Manual on Android Chrome (blocker B3). Decides D8 mechanism.
- Task E0.S6 (S): GitHub `POST /git/blobs` with a 20 MiB and a 70 MiB base64 body from the browser (private repo, PAT): time, memory, secondary rate limit behavior. Manual (blocker B4). Tunes E8.1.
- Task E0.S7 (M): ML engine compatibility: can tesseract.js and ONNX Runtime Web (WASM EP, no SharedArrayBuffer) load from same-origin files inside the Kotlin/Wasm Vite/webpack output, under the app's CSP? Measure model sizes, first-run latency. Decides D6.

---

## Phase 1: Foundation

### Epic E1: Binary store, index, dirty tracking, memory budget
**Goal**: the web `FileSystem` can list, size, rename, delete, hash, stream and sync binary files without holding their bytes in the Kotlin heap.

#### Story 1.1.1: commonMain contracts and pure logic
**As a** maintainer, **I want** the binary-file contract and path rules in commonMain, **so that** one test run covers all targets.
**Acceptance criteria**: pure functions classify binary vs text paths, local-only paths, and image types; `FileSystem` gains suspend binary reads/writes with defaults that preserve current JVM/Android/iOS behavior; new `DomainError` variants have display strings.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/binary/{FileSource,BinaryClassifier,LocalOnlyPaths,ImageTypes}.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`
- Task 1.1.1a (S): create `FileSource`, `BinaryClassifier.isBinaryAssetPath`, `LocalOnlyPaths.isLocalOnly`, `ImageTypes` (one table: extension, MIME, decodable-in-browser, animated-possible, tier-eligible). Tests: `commonTest/.../platform/binary/{LocalOnlyPathsTest,ImageTypesTest}.kt` incl. a property test (`checkAll` over generated paths: nothing under `.stelekit/cache/` is ever dirty-eligible).
- Task 1.1.1b (M): add to `FileSystem`: `suspend fun readFileHead(path, n): ByteArray?`, `suspend fun readFileBytesBounded(path, maxBytes): ByteArray?`, `suspend fun contentHashHex(path): String?`, `suspend fun writeFileStream(path, source: FileSource): Boolean`, all defaulting to the existing sync methods so other platforms are unchanged. Test: `commonTest/.../platform/FileSystemBinaryDefaultsTest.kt` against `businessTest`'s `MemoryFileSystem` pattern (copy a commonTest fake).
- Task 1.1.1c (XS): `DomainError.AttachmentError.{QuotaExceeded,TooLarge,UnsupportedType,StorageUnavailable}` + `displayMessage` arms (`DomainError.kt:381-383` region); test that the `when` is exhaustive (compile) and strings non-blank.

#### Story 1.2.1: `WebBinaryStore` and index
**Acceptance criteria**: after preload, `listFiles("<notes>/assets")` includes images; `directoryExists`, `fileExists`, `getFileSize`, `getLastModifiedTime` answer for images; no image content is read at preload (only `getFile()` metadata).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/binary/{WebBinaryStore,BinaryIndex,OpfsBinaryInterop}.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`
- Task 1.2.1a (M): `OpfsBinaryInterop.kt`: `statEntry`, `fileFromHandle`, `sliceHead`, `sha256Hex(file)` (JS `crypto.subtle`), `copyFile`, `moveFile` (copy then delete), `listBinary(dir)`. Test: `wasmJsTest/.../platform/binary/OpfsBinaryInteropTest.kt` (real OPFS in Karma Chrome).
- Task 1.2.1b (M): `BinaryIndex` (path to `BinaryMeta`) + `WebBinaryStore.preload(graphPath)` walking OPFS once; replaces the image branch in `loadFilesRecursive` (`PlatformFileSystem.kt:439-444`) and `importUserDirToCache` (`:758-770`). Test: `wasmJsTest/.../BinaryStorePreloadTest.kt` asserts zero `arrayBuffer()` calls via a counting shim.
- Task 1.2.1c (M): `PlatformFileSystem` one-line delegations for `listFiles`, `listDirectories`, `directoryExists`, `fileExists`, `getFileSize`, `getLastModifiedTime`, `renameFile`, `deleteFile`, `resolveAssetUri`, `registerBlobUrl` for binary paths (text paths unchanged). Tests: extend `PlatformFileSystemDirtyTrackingIntegrationTest` and add `PlatformFileSystemBinaryDelegationTest.kt`.
- Task 1.2.1d (S): blob URL lifecycle in the store: lazy create, LRU cap (default 256), revoke on delete, rename, graph switch (`switchActiveGraph`). Test: `BlobUrlLifecycleTest.kt` with a `revokeObjectURL` spy.

#### Story 1.3.1: write path, dirty tracking and local-only exclusion
**Acceptance criteria**: a binary write records a dirty WRITE entry with no bytes retained, schedules host write-through lazily, and local-only paths are never recorded, written through or reconciled.
**Files**: `PlatformFileSystem.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`, `.../platform/binary/WebBinaryStore.kt`
- Task 1.3.1a (M): `WebBinaryStore.writeStream(path, source)`: `createWritable().write(file)` for `Handle`, base64-bridge for `Bytes`; atomic (write to OPFS swap, rename on close semantic), tracked in `opfsWriteInFlight`; `recordDirty(path, WRITE)`; `HostWritePayload.FromStore(path)`. Test: `wasmJsTest/.../BinaryWriteDirtyTrackingTest.kt`.
- Task 1.3.1b (S): apply `LocalOnlyPaths` in `recordDirty` (`PlatformFileSystem.kt:304`), `scheduleHostWriteThrough`, reconcile walk, `GraphZipExporter`. Test: `businessTest/.../binary/LocalOnlyCallSitesStructuralTest.kt` (text scan, same style as `LfsStagingCallSitesStructuralTest`) plus `commonTest` property test from 1.1.1a.
- Task 1.3.1c (M): `HostDirectorySync` reconcile (`:1276-1292`) and relink import copy non-text files handle-to-handle and store metadata only (no `setBytes`). Test: update `HostDirectorySyncReconciliationTest` + new `HostDirectorySyncBinaryReconcileTest.kt` asserting `bytesCache` size stays 0 for images.
- Task 1.3.1d (S): `FileSystem.writeFileBytes` for images remains for small paranoid payloads, but routes images over a size threshold (default 256 KiB) to the store instead of `bytesCache`. Test: `BinaryWriteThresholdTest.kt`.

#### Story 1.4.1: memory budget and instrumentation
**Acceptance criteria**: resident Kotlin-heap image bytes are measurable and capped (D13 default 64 MiB); exceeding the cap fails the operation with a typed error instead of crashing.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/binary/ResidentBytesMeter.kt`, `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/binary/ResidentBytesBudgetTest.kt`
- Task 1.4.1a (S): `ResidentBytesMeter` (acquire/release with budget; pure, commonTest with `runTest`).
- Task 1.4.1b (S): wasm test: attach 20 images of 5 MiB, assert peak resident Kotlin bytes at most one image plus slack; uses S4's measurement helper.

### Epic E2: Attach pipeline (picker and drop)
**Goal**: picker and drop attach streams into the store, is dirty-tracked, hardened, and reports typed errors and progress.

#### Story 2.1.1: `AttachmentPolicy` (pure)
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/service/AttachmentPolicy.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/service/AttachmentPolicyTest.kt`
- Task 2.1.1a (S): allow-list via `ImageTypes`, size caps (per-file default 50 MiB matching `PageDropTarget.kt:43`, configurable), name sanitization (unsafe chars `# ? % \ /` and control chars; keep Unicode letters), unique-name choice given an existing-name predicate. Returns `Either<DomainError.AttachmentError, PlannedAttachment>`.
- Task 2.1.1b (S): property tests: sanitized name has no unsafe chars; `toMarkdown(sanitize(n))` re-parses to the same path; uniqueness never collides under any existing-name set; idempotence.

#### Story 2.2.1: web attach service on the store
**Acceptance criteria**: attaching via picker or drop leaves the image in OPFS, in the index, in the dirty set, queued for host write-through, and renderable; a failure leaves no partial file, no index row, and a toast.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/service/WasmMediaAttachmentService.kt`, `.../ui/components/{DropZoneInterop,PageDropTarget}.kt`, `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/service/WasmMediaAttachmentServiceTest.kt`
- Task 2.2.1a (M): rewrite `persistAttachment` (`:81-111`) to take a `FileSource`, plan with `AttachmentPolicy`, write through `WebBinaryStore.writeStream`, register blob URL only after `close()`. Keep the `writeMutex` (`:41`). `attachBytes(ByteArray)` stays (Google Photos path) via `FileSource.Bytes`.
- Task 2.2.1b (S): multi-select picker (`input.multiple = true`), returning a list; the `MediaAttachmentService.pickAndAttach` single-result contract is kept by adding `pickAndAttachMany` to the interface with a default delegating to `pickAndAttach` (other platforms untouched).
- Task 2.2.1c (S): drop: use `ImageTypes` instead of `IMAGE_EXTENSIONS` (`DropZoneInterop.kt:44`), pass `File` handles (not bytes) to the handler, keep 50 MiB cap, and show a snackbar when no page is open (`GraphContentCameraCapture.kt:189-196` currently returns silently).
- Task 2.2.1d (S): content hash via JS for dedupe hint ("this image is already attached as X"), non-blocking.
- Task 2.2.1e (M): update `WasmMediaAttachmentServiceTest` and add `AttachDirtyAndHostQueueTest.kt`, `AttachQuotaExceededTest.kt` (monkeypatch `createWritable`, pattern at test `:18-56`), `AttachConcurrentSameNameTest.kt`.
- Task 2.2.1f (S): typed errors replace the generic `CopyFailed` where applicable; toast text from `displayMessage`.

---

## Phase 2: Index, browser, view

### Epic E3: Asset index, browser, move, copy
**Goal**: the Assets browser shows, searches, moves, renames, deletes web images; cross-graph copy brings images across.

#### Story 3.1.1: index on web
**Acceptance criteria**: after attach and after reload, Assets lists the image with correct size, MIME and hash; a graph of 2,000 images indexes within the memory budget.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/{AssetIndexService,AssetIndexingController}.kt`, `asset/pipeline/AssetBuildFlags.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`
- Task 3.1.1a (XS): web build exposes the switch per D5: `AssetBuildFlags.internalBuild = true` equivalent for web set in `Main.kt` before `ComposeViewport` (or a `webEnabled` flag), default ON.
- Task 3.1.1b (M): `AssetIndexService.buildAssetEntry` (`:94-130`) uses `readFileHead` (MIME sniff, 4 KiB) and `contentHashHex` (store hashes files of any size in JS) instead of `readFileBytes`; the `MAX_HASHED_FILE_BYTES` cap (`:245`) then applies only to platforms whose `contentHashHex` default reads bytes. Tests: `commonTest/.../asset/AssetIndexBinaryReadsTest.kt` with a fake `FileSystem`; update `businessTest/.../AssetIndexingControllerTest.kt` as needed.
- Task 3.1.1c (S): `IndexingMediaAttachmentService` registers web attachments (already generic); add `wasmJsTest/.../AttachRegistersAssetTest.kt`.

#### Story 3.2.1: move, rename, delete
**Files**: `asset/AssetMoveService.kt`, `ui/assets/{AssetActionMenu,AssetDetailViewModel}.kt`, `.../platform/PlatformFileSystem.kt`
- Task 3.2.1a (M): `AssetMoveService` rename path (`~:98`) uses the store's `moveFile`; WAL replay (`replayPendingMoves`) verified after a simulated crash between copy and delete. Test: `wasmJsTest/.../AssetMoveWebTest.kt`, `commonTest` for ordering logic with a fake.
- Task 3.2.1b (S): delete asset from the detail screen removes OPFS file, index row, blob URL, records DELETE dirty; page references are not rewritten (show a "referenced by N pages" confirmation using existing `pageUuids`). Test in `androidUnitTest/.../ui/assets/AssetDeleteConfirmTest.kt` (Robolectric, headless) since the logic is commonMain Compose.

- Task 3.2.1c (S): "Open / download original" on web: `WasmJsPlatformFileOpener.openFile` is a no-op today (`platform/PlatformFileOpener.wasmJs.kt`, VERIFIED); implement as a download (anchor with `download` attribute, MIME forced to `application/octet-stream` for SVG/HTML-capable types), never `window.open(blobUrl)` (pitfall P17: a navigated `blob:` SVG/HTML runs script in the app origin, which holds the git token). Test: `wasmJsTest/.../PlatformFileOpenerWebTest.kt` asserts no navigation for `.svg`.

#### Story 3.3.1: cross-graph copy of images (web is the pull target)
**Files**: `merge/AssetCopier.kt`, `merge/PageMergeService.kt` (unchanged public API), `ui/screens/copy/CopyServices.kt`
- Task 3.3.1a (M): `AssetCopier` (`:104-154`) uses the bounded suspend reads/stream write; writes go through the existing `TargetWriterRouter` active-graph writer; no second writer class. Tests: `commonTest`/`businessTest` `AssetCopierBinaryApiTest.kt` using `TargetWriterContractSuite`-style fake; `CopyEditRecopyPropertyTest` stays green.

#### Story 3.4.1: web end-to-end and scale
- Task 3.4.1a (M): `wasmJsTest/.../ui/assets/AssetBrowserWebTest.kt`: compose `AssetBrowserScreen` over the real web store and real SQLDelight-on-wasm (or the in-memory repository if the OPFS SQLite driver is unavailable in Karma: pick whichever `WasmBenchmarkTest` uses), seed 5 images, assert cards render with names.
- Task 3.4.1b (M): `LargeGraphImageIndexWebTest.kt`: 2,000 small images, assert bounded batches (<=100 rows per query, mirroring `LargeGraphWarmStartCrashTest`), resident bytes under budget, index completes. Registered in the wasm test list.

### Epic E4: Tiers and display
**Goal**: images render at the right size; originals are loaded only for the lightbox/editor.

#### Story 4.1.1: async derivative backend (commonMain)
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/image/{ImageDerivativeBackend,ImageDerivativeService}.kt`
- Task 4.1.1a (M): `ImageDerivativeBackend { suspend fun generate(fs, original, targetEdges, quality): Either<DomainError, List<String?>> }` + `SyncPortBackend(ImageDownscalerPort)` adapter preserving current behavior for JVM/Android/iOS. Tests: `commonTest/.../platform/image/ImageDerivativeBackendTest.kt` with a fake backend; existing `PlatformImageDownscalerJvmTest`/Android test stay green.
- Task 4.1.1b (S): `ImageDerivativeService` takes the backend; `ImageDerivativeService.shared` default unchanged on non-web.

#### Story 4.2.1: web derivative backend
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/image/{WebImageDerivativeBackend,PlatformImageDownscaler.wasmJs.kt}`
- Task 4.2.1a (M): `createImageBitmap(file, {resizeWidth|Height, resizeQuality:'high', imageOrientation:'from-image'})` then `OffscreenCanvas.convertToBlob({type:'image/jpeg', quality: 0.85})` written via the store to `.stelekit/cache/images/<hash>.{thumb,display}.jpg` (local-only). Test: `wasmJsTest/.../WebImageDerivativeBackendTest.kt` with generated PNG fixtures (portrait EXIF-rotated, 4000x3000).
- Task 4.2.1b (S): rules from `ImageTypes`: animated/GIF/SVG/HEIC-on-non-Safari use original; decode failure returns `Right(nulls)` and remembers give-up (existing `giveUp` set); never throws. Test: `WebImageDerivativeEdgeCasesTest.kt`.
- Task 4.2.1c (XS): `PlatformImageDownscaler.wasmJs.kt` keeps returning null with an updated comment pointing to the backend (it is the sync port, which web cannot implement).

#### Story 4.3.1: render blob URLs reliably (conditional on S1)
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/BlobUrlFetcher.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SteleKitAssetFetcher.kt`, `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/ui/components/ImageBlockRenderTest.kt`
- Task 4.3.1a (M): if S1 fails, add a Coil `Fetcher` for `blob:` registered through a platform hook `expect fun platformImageComponents(): ComponentRegistry.Builder.() -> Unit` consumed by `buildSteleKitImageLoader` (`:67-79`). If S1 passes, skip and record the evidence.
- Task 4.3.1b (S): `ImageBlockRenderTest` (wasmJsTest) composes `ImageBlock` with a real blob URL and a tiered model, asserts success; also asserts a pointer-file blob shows the placeholder, not a crash.

---

## Phase 3: Ingress and editor

### Epic E5: Paste, camera, share, folder and zip import
**Goal**: every browser-possible way to get an image in works, with graceful disabled states elsewhere.

#### Story 5.1.1: paste
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/PasteInterop.kt`, `.../ui/components/PageDropTarget.kt` (shared listener plumbing), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/EditorCapabilities.kt` (read only), `GraphContentCameraCapture.kt`
- Task 5.1.1a (M): body-level `paste` listener (same install-once pattern as `installBodyDropListener`) that, when the clipboard carries image files, forwards `File` handles to `EditorCapabilities.onPasteImage`-equivalent web entry; preserves text paste when no image is present (never `preventDefault` for text). Needs S2.
- Task 5.1.1b (S): HTML-only clipboard (`text/html` with `<img src>`): insert markdown image link to the URL, no fetch.
- Task 5.1.1c (S): tests `wasmJsTest/.../PasteInteropTest.kt` (synthetic `ClipboardEvent` with `DataTransfer` files; text paste untouched) and `commonTest` for the link-extraction pure function `HtmlImageLinkExtractorTest.kt`.

#### Story 5.2.1: camera
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/sensor/WebCameraProvider.kt`, `.../ui/components/CameraViewfinderDialog.wasmJs.kt`, `kmp/src/businessTest/kotlin/dev/stapler/stelekit/wiring/WiringRoots.kt`, `kmp/src/businessTest/resources/wiring-catalog.txt`
- Task 5.2.1a (M): `WebCameraProvider : CameraProvider` using an `<input capture=environment>` for mobile and `getUserMedia` support probe; staged capture file written to OPFS `_inbox/` (local-only) and returned as `PlatformImageFile(path = ...)`; `isAvailable` true only in a secure context with a camera or `capture` support. Test: `wasmJsTest/.../WebCameraProviderTest.kt` (fake `mediaDevices`).
- Task 5.2.1b (M): `CameraViewfinderDialog` actual (desktop browsers): `<video>` overlay hosted in a DOM element above the canvas, capture to canvas, `toBlob`, permission-denied and no-device states via `onError`. Test: `CameraViewfinderDialogWasmTest.kt` using a fake stream.
- Task 5.2.1c (S): wiring: add `wasmJsMain/$PKG/browser/Main.kt` to `WiringRoots.kt:13-17`, add `CameraProvider  WebCameraProviderEntryPointTest  effect:web-camera-capture` to `wiring-catalog.txt`, entry-point test asserting `ObservableEffect`. (VERIFIED: `CameraProvider` is a seam, `WiringAudit.kt:24`.)

#### Story 5.3.1: Web Share Target
**Files**: `kmp/src/wasmJsMain/resources/{manifest.webmanifest,stelekit-service-worker.js,share-target.js}`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/service/ShareInboxConsumer.kt`
- Task 5.3.1a (XS): manifest `share_target` (POST multipart, `images` file param, accept `image/*`). Test: JS unit test `manifest.share-target.test.js` validating the JSON.
- Task 5.3.1b (M): SW branch for `POST ./share-target`: store files to OPFS `_inbox/<id>/`, 303 redirect to `./?share=<id>`; never cache POST; existing app-shell logic untouched. Extend `looks-like-app-shell.test.js` neighbors with `service-worker-share.test.js`. Needs S5.
- Task 5.3.1c (M): `ShareInboxConsumer` reads the inbox on boot, shows "Add N shared images to today's journal / choose page" via existing snackbar/dialog patterns, attaches via the store (move, not copy), clears the inbox. Test: `wasmJsTest/.../ShareInboxConsumerTest.kt`.
- Task 5.3.1d (XS): hide the "share to SteleKit" hint where unsupported (iOS Safari, non-installed).

#### Story 5.4.1: bulk import (decision D9)
- Task 5.4.1a (M): folder import: `showDirectoryPicker` where supported else `<input webkitdirectory>`; stream images into the store with progress and a cap on concurrency; skip non-images. Files: `.../service/WebFolderImageImport.kt`, test `FolderImageImportTest.kt`.
- Task 5.4.1b (S): zip import via `DecompressionStream`/central-directory reader (the repo has a zip writer, `export/GraphZipExporter.wasmJs.kt`, and `Crc32.wasmJs.kt`): `.../service/WebZipImageImport.kt`, test with a zip produced by the exporter (round trip).

### Epic E6: Annotation editor and import pipeline on web
**Goal**: annotate and measure what a browser can; clear disabled states for what it cannot.

#### Story 6.1.1: import service on the store
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/ImageImportService.kt`, `db/sidecar/ImageSidecarManager.kt`
- Task 6.1.1a (M): `ImageImportService.copyImageBytes` (`:233-262`) uses `FileSource`/`writeFileStream` (stream copy store-to-store on web, `readFileBytes`/`writeFileBytes` unchanged on other platforms through the defaults); remove the silent text fallback for known-binary paths (return `Left(UnsupportedType)` rather than corrupt/NotFound). Tests: `businessTest`/`commonTest` `ImageImportBinaryCopyTest.kt` with fakes; existing import tests stay green.
- Task 6.1.1b (S): sidecar JSON writes via `writeFile` (text) stay; verify they hit dirty tracking on web (`wasmJsTest/.../ImageSidecarWebTest.kt`).

#### Story 6.2.1: editor on web
**Files**: `ui/annotate/{AnnotationEditorViewModel,AnnotationEditorScreen,AnnotationToolbar}.kt`, `ui/annotate/ImageEncoder.wasmJs.kt`
- Task 6.2.1a (M): editor loads the original via `readFileBytesBounded(path, maxBytes = budget)`; images over `MAX_CANVAS_AREA_PX` or the byte cap open a "too large to edit, view only" state rather than a blank canvas (encoder returns an empty array today, `ImageEncoder.wasmJs.kt:23-31`). Tests: `androidUnitTest/.../AnnotationTooLargeStateTest.kt` (Robolectric) + `wasmJsTest` encode path test exists (`ImageEncoderWasmJsTest`).
- Task 6.2.1b (S): hide BLE panel entry (D7), sensor-calibration nudges and depth tools when `SensorModule` / `PlatformCaps` report unavailable; each hidden control has a one-line "not available in the browser" tooltip/disabled text. Tests: `androidUnitTest/.../AnnotationCapabilityGatingTest.kt`.
- Task 6.2.1c (S): export annotated JPEG: encode then `writeFileBytes` through the store; confirm the sidecar and exported file are dirty-tracked and the exported file path is not local-only.

#### Story 6.3.1: gallery and import entry
- Task 6.3.1a (S): enable `onImportImage` on web through the picker (not camera) so the Gallery import button works without a camera; adjust `cameraImportEnabled` (`GraphContentCameraCapture.kt:51`) to separate "camera available" from "file import available". Test: `androidUnitTest/.../GalleryImportEntryTest.kt`.
- Task 6.3.1b (M): `wasmJsTest/.../ui/annotate/WebImportAnnotateExportTest.kt`: import a PNG, open the editor, add a label, export, assert files, sidecar and dirty entries.

---

## Phase 4: Sync

### Epic E8: Git, host folder, zip and remote images
**Goal**: images ride the same sync paths as notes, safely.

#### Story 8.1.1: git blob creation from the store
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`, `.../git/JsBlobUpload.kt`, `.../platform/PlatformFileSystem.kt`
- Task 8.1.1a (M): `BlobContent` sealed type (`Text`/`Bytes`/`Store(path)`) and one `createBlob(..., gate)` function taking it (keeps the single call site that `LfsStagingCallSitesStructuralTest.kt:110-114` guards); `Store` variant calls `JsBlobUpload.createBlob(path, apiPath, authHeader, url)` which base64-encodes in JS (`FileReader.readAsDataURL`) and `fetch`es, returning the sha. Needs S6.
- Task 8.1.1b (S): byte-bounded concurrency in `createBlobsBounded` (cap in-flight base64 bytes, default 96 MiB, plus the existing count bound); keep `MAX_BLOB_BYTES` (`:1324`) check using index size.
- Task 8.1.1c (S): FAIL-CLOSED guard for store-resident files. VERIFIED hazard: `lfsVerdict` skips a dirty WRITE whose bytes are not in `getContentBytes` (`WasmGitWriteService.kt:172-173`, `?: continue`), and the commit path returns `CommitFailed("No cached content for dirty path")` for the whole batch (`:509-513`). Required: `lfsVerdict` builds the probe from the store (size from index, `readHead` for pointer sniff) for every dirty WRITE that is store-resident, and a dirty WRITE whose content cannot be located at all returns `Left` (never `continue`). `WasmLfsContentProbe` gets a store-backed variant; `requireNotRawAtTrackedPath` still runs before upload. Structural test updated only if its regex needs the new type name (do not weaken it). Test: `WasmGitWriteServiceLfsGuardTest` case "dirty image absent from bytesCache at an LFS-tracked path is held, not skipped".
- Task 8.1.1d (M): tests: extend `WasmGitWriteServiceMockedIntegrationTest` with a store-backed image (MockEngine intercept proves one blob POST, correct base64, correct path), `WasmGitWriteServiceLfsGuardTest` case: image at LFS-tracked path is held and not uploaded; progress/FileTooLarge case.

#### Story 8.2.1: host-folder write-through and zip
- Task 8.2.1a (M): `HostDirectorySync.flushHostWrite` handles `HostWritePayload.FromStore` by streaming `File` to the host handle (`createWritable().write(file)`); stuck/failed handling unchanged. Files: `HostDirectorySync.kt`, `HostWritePayload` definition.
- Task 8.2.1b (S): tests in `HostDirectorySyncWriteThroughTest` style: image written to a fake host dir; failure sets `hostWriteStuckFlow`.
- Task 8.2.1c (S): `GraphZipExporter.wasmJs.kt` reads images from the store (`:175` path) and excludes local-only paths; test extends `GraphZipExporterWasmJsTest.kt`: zip contains attached image bytes equal to source, excludes `.stelekit/cache`.

#### Story 8.3.1: remote-originated images
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/remote/RemoteAssetSource.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WebRemoteAssetSource.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TieredImage.kt`
- Task 8.3.1a (M): `RemoteAssetSource` port (`suspend fun fetchInto(path, ref, sink): Either<DomainError.GitError, RemoteAssetResult>`) in commonMain; web actual: GitHub via `api.github.com/repos/o/r/contents/<path>?ref=` with `Accept: application/vnd.github.raw`, raw.githubusercontent for public, GitLab `repository/files/:path/raw`; streams response `Blob` into the store (not `bodyAsText`). Tests: `commonTest` result-mapping tests; `wasmJsTest/.../WebRemoteAssetSourceTest.kt` with `MockEngine` (like `LfsTolerantMockEngine`).
- Task 8.3.1b (M): lazy trigger: when a block references `../assets/x` that is absent locally and a remote is configured, fetch on view (policy D12: lazy default), show a loading/failed/retry tile, never block page render, bounded concurrency 3, no fetch for `ephemeral` sessions beyond cap. Hook in `rememberTieredImageModel` (`TieredImage.kt:30`) via an injected `RemoteAssetSource` from `LocalRemoteAssetSource` (CompositionLocal default `null`, so other platforms are unchanged).
- Task 8.3.1c (M): fetch/merge policy for remote-changed binaries (`WasmGitWriteService.mergeAndCommit` region `:781-870`): remote image changed and local unchanged: mark stale and refetch lazily; both changed: keep both (rename local as `name-conflict-<hash8>`) rather than overwrite; never text-decode. Tests: `WasmGitWriteServiceBinaryMergeTest.kt`.

---

## Phase 5: Git LFS on web (image-facing slice; see ADR-003)

### Epic E9: LFS read, write modes, image UX
**Goal**: LFS-tracked images display; images added at LFS-tracked paths have a real, honest write path; the safety floor never weakens.
**Prerequisite facts**: VERIFIED CORS matrix and owner decisions O1 (no proxy) and O2 (Converter approved, opt-in) from `git-lfs-support` ADR-002 header; VERIFIED the commonMain pieces that exist today (`LfsPointer`, `LfsGuard`, `LfsPathPolicy`, `GitAttributes*`, `LfsRulesLoader`) and that the transfer client is JVM-only.

#### Story 9.1.1: extract the LFS protocol to commonMain
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/lfs/client/{LfsProtocol,LfsTransport,LfsErrorMapper,LfsRetryPolicy}.kt`, `kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/git/lfs/LfsBatchClient.kt`
- Task 9.1.1a (M): move request/response models (`BatchRequest/Response/Action/Error`, `LfsBatchClient.kt:36-50` region), status-to-`DomainError.GitError` mapping and retry policy to commonMain; JVM client delegates, `java.io.File` and `javaio` stay in `jvmCommonMain`. Tests: existing `LfsBatchClientStatusTest` and `JvmGitRepositoryLfsTransferTest` stay green; new `commonTest/.../git/lfs/client/LfsErrorMapperTest.kt`.
- Task 9.1.1b (M): `LfsTransport` port (download to `Sink`/`FileSource`, upload from `FileSource`, verify) with JVM adapter and web adapter stub. Test: `commonTest` against a fake transport; JVM `FakeLfsServer` contract run unchanged.
- Task 9.1.1c (S): `commonTest` batch protocol tests over Ktor `MockEngine` (works on all targets).

#### Story 9.2.1: read path (display LFS-tracked images)
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/lfs/LfsPointerResolver.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/lfs/{WebLfsObjectStore,WebLfsReadService}.kt`, `ui/components/ImageBlock.kt`, `ui/assets/AssetItemCard.kt`
- Task 9.2.1a (S): `LfsPointerResolver` (pure): given head bytes, return `NotPointer`/`Pointer(oid,size)`/`Malformed`; reuses `LfsPointerCodec`. Tests: `commonTest` incl. property test: any valid pointer encodes/parses round trip; a real 1x1 PNG is `NotPointer`.
- Task 9.2.1b (M): `WebLfsObjectStore`: content-addressed `.stelekit/lfs/objects/<oid>` (local-only), streaming SHA-256 + size verify before commit, quota-aware. Test: `wasmJsTest/.../WebLfsObjectStoreTest.kt` (corrupt body rejected, oid mismatch rejected).
- Task 9.2.1c (M): `WebLfsReadService`: GitHub `https://media.githubusercontent.com/media/<o>/<r>/<ref>/<path>`, GitLab `.../repository/files/<path>/raw?lfs=true&ref=`, others through batch if `WasmLfsCorsProbe` passes; private repos send the token (preflight matrix says `media.githubusercontent.com` allows `Authorization`, VERIFIED 2026-10-11). Tests with `MockEngine`.
- Task 9.2.1d (M): display integration: `rememberTieredImageModel` resolves pointer to object-store blob URL; states `LfsNotDownloaded(size)` (placeholder with size and a Download button, auto-download below a size threshold, D12), `LfsDownloading(progress)`, `LfsFailed(reason, retry)`. Components in `ui/components/LfsImagePlaceholder.kt`. Tests `androidUnitTest/.../LfsImagePlaceholderTest.kt` (Robolectric) and `ImageBlockLfsRenderTest.kt` (wasmJsTest).

#### Story 9.3.1: write modes for images at LFS-tracked paths
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/lfs/{WasmLfsCorsProbe,LfsWriteCapabilityResolver,WebLfsUploader,LfsConverterMode}.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/git/LfsHeldFilesSheet.kt`
- Task 9.3.1a (S): `WasmLfsCorsProbe` (`POST <lfs>/objects/batch`, `operation=download`, `objects=[]`, with `Authorization`; TypeError = blocked), cached per host with 1-day TTL in settings (`git_lfs_cors_probe_<host>`), per ADR-002 (git-lfs-support). Test with `MockEngine` and a monkeypatched `fetch`.
- Task 9.3.1b (M): `LfsWriteCapabilityResolver` picks `Direct` then `Converter` (if enabled) then `ReadOnly`; `Direct` uploads the image object (batch, PUT, verify) then commits the pointer (generated via `LfsPointerCodec`) in place of the raw blob; the gate asserts the committed blob is a valid pointer. Test: web uploader against `MockEngine` and a JVM-run contract test of the shared protocol with `FakeLfsServer`.
- Task 9.3.1c (M): `Converter` mode (owner-approved opt-in, O2): staging ref `refs/heads/stelekit/lfs-inbox/<id>`, `workflow_dispatch` (preflight VERIFIED `ACAO: *`), workflow template `docs/reference/lfs-converter/stelekit-lfs.yml`; polls for completion with injected clock (no real-clock delay in tests). Updates the single permitted staging exception per git-lfs-support ADR-003.
- Task 9.3.1d (M): second half of Converter: GitLab variant (`.gitlab-ci.yml` template + pipeline trigger) and failure handling (rebase conflict leaves ref, surfaced with a link).
- Task 9.3.1e (S): UX: pre-attach hint when the target path is LFS-tracked ("This repo stores images with Git LFS; push uses <mode>"); `LfsHeldFilesSheet` rows show thumbnails from the store; ReadOnly mode wording.
- Task 9.3.1f (S): update `LfsStagingCallSitesStructuralTest` and `LfsRawBinaryNeverCommitted*Test` deliberately for the Converter exception (allow-list of one call site, with the test asserting that exactly one staging-ref call site exists and it is behind the opt-in flag).
- Task 9.3.1g (S): `WebLfsManualValidation.md` checklist (owner-run real-host CORS on private repos for Bitbucket/Azure object-storage hrefs, the Converter workflow on GitHub and GitLab). Wall-clock blocker B4/B5.

---

## Phase 6: On-device enrichment

### Epic E7: OCR, labels, depth on web (additive, last)
**Goal**: the `PlatformCaps` slots that are `NONE` today get real browser implementations behind explicit opt-in and quota checks. Decision D6 selects engines; if the owner declines an engine its tasks are skipped, nothing else changes.

#### Story 7.1.1: engine plumbing
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/ml/{WebEngineLoader,WebPlatformCaps}.kt`, `kmp/src/wasmJsMain/resources/ml/` (vendored, same-origin)
- Task 7.1.1a (S): `WebPlatformCaps.build()` from capability probes (WASM SIMD, WebGPU, isolation); returns `PlatformCaps` with only the engines that are enabled and present; wired into `Main.kt:366,515` replacing `PlatformCaps.NONE`.
- Task 7.1.1b (M): `WebEngineLoader`: same-origin script/model fetch into a versioned SW cache or OPFS `.stelekit/models/` (local-only), progress and cancel integrated with `InProcessJobs`/`BackgroundJobs` (VERIFIED present in PR #514's file list), quota pre-check from `StorageHealth`. Tests: `wasmJsTest` with a fake model file; `commonTest` for progress math.

#### Story 7.2.1: engines
- Task 7.2.1a (M): `WebTextRecognizer : TextRecognizer` (tesseract.js worker; language data download opt-in; image bytes passed as `Blob` not `ByteArray`, so the interface `recognize(imageBytes: ByteArray)` needs a `FileSource` overload added with a default delegating). Test: fixture image with known text (`commonTest` golden text, `wasmJsTest` runs the engine).
- Task 7.2.1b (M): `WebImageLabeler : ImageLabeler` (ORT-web classifier). Test with a fixture and label-set assertion.
- Task 7.2.1c (M): `WebMonocularDepthEstimator : MonocularDepthEstimator` (depth model behind explicit download; WebGPU preferred). Test: output tensor shape and bounds on a fixture; `DepthEstimationCoordinator` flow unchanged.
- Task 7.2.1d (S): download UX: size, quota check, cancel, "model stored in your browser, N MB" in Settings > Plugins (`ui/components/settings/PluginsSettings.kt`), Dialog surface so no `first-open-screens` entry is needed (P13).

#### Story 7.3.1: integration and safety
- Task 7.3.1a (S): feed `MemoryPressureListeners` on web from `visibilitychange`, `performance.memory` (Chromium) and `navigator.deviceMemory` heuristics; pipeline pauses (`GraphContentAssetSetup.kt:85-94`). Test `wasmJsTest/.../WebMemoryPressureTest.kt`.
- Task 7.3.1b (S): crash-loop breaker (`PipelineRunMarker`) verified on web settings; test.
- Task 7.3.1c (M): real-engine smoke tests in `wasmJsTest` (one per engine, tiny fixtures) and fake-engine unit tests in `commonTest` for plugin behavior (`OcrPlugin` with `FakeTextRecognizer`).

---

## Phase 7: Persistence, quota, memory

### Epic E10
**Goal**: users know where their images are, are asked for durable storage at the right time, and hit clear errors at limits.

#### Story 10.1.1: storage health
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/storage/StorageHealth.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/storage/StorageHealth.wasmJs.kt`, `ui/components/settings/StoragePanel.kt`
- Task 10.1.1a (S): `StorageHealth` model + pure classification (`Persisted`, `BestEffort`, `NearQuota(used, quota, percent>=85)`, `Unavailable`), wasm actual (`estimate`, `persisted`, `persist`). Tests: `commonTest/.../storage/StorageHealthClassifierTest.kt` (property over used/quota), wasm fake `navigator.storage`.
- Task 10.1.1b (XS): request `persist()` on the first successful attach in a user-gesture context; remember the answer.
- Task 10.1.1c (M): Settings > Storage panel (Dialog surface): OPFS usage by category (images, derivatives, models, LFS objects), host-folder state, last push, "clear caches" for local-only data, "download all images (zip)". Test `androidUnitTest/.../StoragePanelTest.kt` (Robolectric).

#### Story 10.2.1: messaging
- Task 10.2.1a (XS): `BrowserOnlySyncBanner` copy mentions images and links to the storage panel (`BrowserOnlySyncBanner.kt:43,58`); update its test.
- Task 10.2.1b (S): near-quota snackbar and typed `QuotaExceeded` handling in attach, fetch, LFS object store, model download.

#### Story 10.3.1: browser-specific write path and ephemeral limits
- Task 10.3.1a (M): per D2, if Safari lacks main-thread `createWritable`, implement the worker write path (sync access handle) in the existing module worker pattern (`sqlite-stelekit-worker.js` is the template) and select by capability probe at startup. Needs S3. Tests: manual matrix + `wasmJsTest` for the probe.
- Task 10.3.1b (S): ephemeral and demo-fallback sessions: in-memory-only images with a hard cap (default 20 MiB total) and a visible "temporary session" notice on attach (`Main.kt:370` hides attach in demo-fallback today; ephemeral keeps it).
- Task 10.3.1c (S): `StorageUnavailable` typed state when OPFS throws on first use (Safari private mode): disable attach controls with reason.

---

## Phase 8: Close-out

### Epic E11
- Task 11.1a (S): user docs: `docs/` page "Images on the web" (what works where, storage, LFS modes), and `commonMain/.../docs/ScreenDocs.kt` entries for any changed screen.
- Task 11.1b (S): demo graph / help feature registry entry for web image features (`project_plans/demo-help-feature-registry` conventions).
- Task 11.1c (XS): `docs/coverage/coverage-tiers.yml` entries for the new packages.
- Task 11.1d (S): CI: confirm `ci.yml` runs `:kmp:wasmJsBrowserTest` with the new tests and the wasm test list registration (`AllWasm...` list if one exists; verify), `scripts/ci/reachability-baseline.txt` shrink rule.
- Task 11.1e (XS): ADR index updates; mark `git-lfs-support` Phase 3 as executed-by this plan (ADR-003).
- Task 11.1f (S): full regression: `scripts/jvm-display-check.sh -- ./gradlew ciCheck`, `./gradlew :kmp:wasmJsBrowserTest`, `bazel test //kmp:business_tests`; paste outputs into the PR.

---

## Decisions needing the owner (each has a recommended default; work proceeds on the default)

| ID | Decision | Options | Recommended default | Decided by spike | Blocks |
|---|---|---|---|---|---|
| D1 | Browser storage backend for images | OPFS working store + optional host-folder mirror + git (A); IndexedDB blobs (B); host folder only (C) | A (ADR-001) | S3 for Safari | E1 |
| D2 | Write path where `createWritable` is unavailable (Safari) | main thread only and show unsupported; worker sync-access-handle path; disable attach on Safari | worker path chosen by capability probe | S3 | E1.3, E10.3 |
| D3 | How images render from blob URLs | Coil fetcher for `blob:` (A); DOM `<img>` overlay (B) | A, only if S1 shows Coil cannot do it natively | S1 | E4.3 |
| D4 | Shape of the binary read API | suspend methods on `FileSystem` with defaults (A); separate commonMain `BinaryAssetStore` port (B) | A (smallest change to existing services; ADR-002) | none | E1.1 |
| D5 | Asset index switch on web | default ON for web with the 2,000-image memory test as the gate (A); follow Android's hidden-until-heap-gate (B) | A (otherwise Assets stays empty on web) | S4 | E3.1 |
| D6 | On-device engines | tesseract.js OCR + ORT-web labels + depth behind explicit download (A); OCR only (B); none (C) | A, vendored same-origin | S7 | E7 |
| D7 | Web Bluetooth measurement devices | hide (A); implement Web Bluetooth (B) | A | none | E6.2 |
| D8 | Share Target | build with SW stash (A); skip | A | S5 | E5.3 |
| D9 | Bulk import | folder + zip (A); folder only (B) | A | none | E5.4 |
| D10 | HEIC | store as-is, preview only on Safari (A); convert to JPEG on Safari ingest, optional toggle (B) | A | none | E4.2 |
| D11 | Who owns web LFS | this plan executes the web LFS slice and supersedes `git-lfs-support` Phase 3 for web (A); keep two plans and sequence git-lfs-support first (B) | A (one client, one set of tests; ADR-003) | none | E9 |
| D12 | Remote image download policy | lazy on view, auto below 8 MiB, ask above (A); eager on pull; never auto | A | none | E8.3, E9.2 |
| D13 | Resident image bytes budget in the Kotlin heap | 64 MiB (A); 32 MiB; 128 MiB | A | S4 | E1.4 |
| D14 | When to ask for persistent storage | first attach (A); at graph creation; never | A | none | E10.1 |

---

## Wall-clock blockers (the only calendar-time items)

| ID | Blocker | Owner | Needed before |
|---|---|---|---|
| B1 | Manual run of the OPFS/createWritable matrix on Safari desktop, Safari iOS, Firefox, Chromium (S3) | repo owner (devices) | E1.3, E10.3 |
| B2 | Manual Ctrl+V / paste from screenshot tools on 3 browsers (S2) | repo owner | E5.1 |
| B3 | Android Chrome installed-PWA share-sheet run (S5, E5.3) | repo owner (Android device) | E5.3 |
| B4 | GitHub/GitLab private-repo run: blob upload at 20/70 MiB (S6) and LFS Direct/media endpoints with PAT | repo owner (credentials) | E8.1, E9 |
| B5 | Converter workflow end to end on a real GitHub and GitLab repo | repo owner | E9.3 |
| B6 | Owner answers to D1 to D14 (defaults permit starting) | repo owner | per table |
| B7 | `wasmJsBrowserTest` needs Chrome headless and `~/.yarnrc.yml nodeLinker: node-modules` (repo memory) on every executing machine | implementer env | all wasm tests |

---

## Pitfall to task mapping

| Pitfall | Tasks |
|---|---|
| P1 attach not synced | 1.3.1a, 2.2.1a, 2.2.1e, 8.1.1a, 8.2.1a |
| P2 heap exhaustion | 1.2.1b, 1.3.1c, 1.4.1a/b, 3.4.1b, 8.1.1b |
| P3 derivatives leak | 1.1.1a, 1.3.1b, 4.2.1a, 8.2.1c |
| P4 Coil blob | E0.S1, 4.3.1a/b |
| P5 Safari OPFS | E0.S3, 10.3.1a, 10.3.1c |
| P6 quota | 1.1.1c, 2.2.1e, 10.2.1b |
| P7 eviction | 10.1.1b/c, 10.2.1a |
| P8 push size and rate | E0.S6, 8.1.1b, 8.1.1d |
| P9 ML engines | E0.S7, 7.1.1b, 7.2.1d |
| P10 LFS | 9.1.1a, 9.2.1a, 9.2.1d, 9.3.1a-f |
| P11 flaky wasm tests | all wasm tasks; 11.1d |
| P12 hygiene gates | all test tasks; 11.1f |
| P13 wiring and first-open | 5.2.1c, 10.1.1c (Dialog), 7.2.1d |
| P14 service worker | 5.3.1b |
| P15 name encoding | 2.1.1a/b |
| P16 type lists | 1.1.1a, 2.2.1c |
| P17 script from blob SVG | 3.2.1c |
| P18 push-path ordering hazard | ordering note in Dependency visualization, 8.1.1a/c, 11.1f |

---

## Effort tally

Computed by a script over this file's `- Task N (class)` lines (midpoints XS 20k, S 55k, M 130k; story multiplier 1.75; E11 is the Close-out section). Raw tokens, not dollars.

| Epic | XS | S | M | Tasks | Task midpoints | With 1.75x verification |
|---|---|---|---|---|---|---|
| E0 spikes | 0 | 4 | 3 | 7 | 610k | 1,068k |
| E1 binary store | 1 | 6 | 6 | 13 | 1,130k | 1,978k |
| E2 attach | 0 | 6 | 2 | 8 | 590k | 1,032k |
| E3 index/browser/copy | 1 | 3 | 5 | 9 | 835k | 1,461k |
| E4 tiers/display | 1 | 3 | 3 | 7 | 575k | 1,006k |
| E5 paste/camera/share/bulk | 2 | 4 | 6 | 12 | 1,040k | 1,820k |
| E6 annotation/import | 0 | 4 | 3 | 7 | 610k | 1,068k |
| E7 ML | 0 | 4 | 5 | 9 | 870k | 1,522k |
| E8 sync | 0 | 4 | 6 | 10 | 1,000k | 1,750k |
| E9 LFS | 0 | 6 | 8 | 14 | 1,370k | 2,398k |
| E10 persistence | 2 | 4 | 2 | 8 | 520k | 910k |
| E11 close-out | 2 | 4 | 0 | 6 | 260k | 455k |
| **Total** | **9** | **52** | **49** | **110** | **9,410k** | **about 16.5M raw tokens** |

Band: Large to XL boundary (the ESTIMATION.md XL line is 15M CU; at an assumed 12 percent output mix CU is roughly equal to raw tokens, INFERRED). Per the owner's standing rule this is a tracking figure, not a scope gate: no cut lines are proposed. Planning overhead (this document set) is excluded. Parallelism: after E1, about five independent epics can run as concurrent waves, so wall-clock is dominated by the blockers above, not by token volume. Overrun checkpoint: if completed stories exceed the table by more than 25 percent, pause and report the delta and cause (informational). Highest-variance items: E0.S3/E10.3.1a (Safari behavior), E9.3.1c/d (Converter), E7 (engine packaging), E1.3.1c (reconcile rewrite).

## Requirements traceability

| Success metric (requirements.md) | Verified by |
|---|---|
| 1 parity matrix | `research/features.md` section 1 rows 1-25 each mapped to an epic; validation.md matrix |
| 2 attach appears everywhere | tests 2.2.1e, 3.1.1c, 8.1.1d, 8.2.1b, 8.2.1c, 1.3.1a |
| 3 memory | 1.4.1b, 3.4.1b, E0.S4 |
| 4 no regression | 11.1f |
| 5 LFS | 9.1.1 to 9.3.1, `LfsStagingCallSitesStructuralTest`, `LfsRawBinaryNeverCommitted*Test` |
