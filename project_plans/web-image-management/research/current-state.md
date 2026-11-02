# Current state: image management by platform (code inventory)

All paths are relative to `kmp/src/` in this worktree at commit `bfd6eceae2`. VERIFIED = file opened and the cited line read. Files listed but not read in depth are marked "exists".

## 1. What "image management" means on Android/Desktop

| Capability | Where | Status |
|---|---|---|
| Attach port | `commonMain/.../service/MediaAttachmentService.kt:45` (`pickAndAttach`, `attachFilePath`, `attachBytes`, `hasClipboardImage:104`, `pasteFromClipboard`) | VERIFIED |
| Desktop attach | `jvmMain/.../service/JvmMediaAttachmentService.kt:31-112` (copies to `<notes>/assets` with a `.tmp-` then rename, clipboard paste at `:98,:112`) | VERIFIED |
| Android attach | `androidMain/.../service/AndroidMediaAttachmentService.kt` | exists |
| Attach decorator that registers into the asset index | `commonMain/.../asset/IndexingMediaAttachmentService.kt` (registers only when `indexing.isActive`) | VERIFIED |
| Asset index | `commonMain/.../asset/AssetIndexService.kt` (lazy walk of `<notes>/assets`, batch size bounded, hashes at most `MAX_HASHED_FILE_BYTES = 8 MiB` at `:245`, `directoryExists(assetsDir)` gate at `:190`; `registerAsset` at `:69` uses `fileSystem.getFileSize` and `readFileBytes` at `:102-106`) | VERIFIED |
| Index switch and crash-loop breaker | `commonMain/.../asset/AssetIndexingController.kt` (`isActive = toggleVisible && enabled` at `:73`); `asset/pipeline/AssetBuildFlags.kt:13,19` (`RELEASE_TOGGLE_VISIBLE = false`; `internalBuild` default false) | VERIFIED |
| Enrichment pipeline | `commonMain/.../asset/pipeline/*` (OCR, on-device labels, PDF text, cloud plugins); capabilities injected as `PlatformCaps` (`PlatformCaps.kt`: `imageLabeler`, `pdfText`, `textRecognizer`; `NONE` = no-ops) | VERIFIED |
| Assets browser UI | `commonMain/.../ui/assets/AssetBrowserScreen.kt:66`, `AssetBrowserViewModel.kt:39`, `AssetDetailScreen.kt`, `AssetItemCard.kt`, `AssetActionMenu.kt`; route at `ui/ScreenRouter.kt:360-370`; sidebar entry `ui/components/Sidebar.kt:335` (not gated by platform) | VERIFIED |
| Assets browser tests | `jvmTest/.../ui/assets/AssetBrowserScreenTest.kt`; `androidInstrumentedTest/.../AssetBrowserE2ETest.kt` (exist) | exists |
| Gallery of annotated images | `commonMain/.../ui/gallery/GalleryScreen.kt`; route `ScreenRouter.kt:340-358` | VERIFIED |
| Image tiers | `commonMain/.../platform/image/ImageTiers.kt:13` (`THUMBNAIL` 256 px, `DISPLAY` 1600 px, `ORIGINAL`; JPEG quality 85), `ImageDerivativeService.kt:25` (derivatives at `<graph>/.stelekit/cache/images/<hash>.thumb.jpg`), `ui/components/TieredImage.kt:30` | VERIFIED |
| Downscaler port | `ImageDownscaler.kt:7` `ImageDownscalerPort`; actuals: JVM `jvmMain/.../PlatformImageDownscaler.jvm.kt` (ImageIO, subsampling), Android `androidMain/.../PlatformImageDownscaler.android.kt` (BitmapFactory `inSampleSize`), iOS `iosMain/.../PlatformImageDownscaler.ios.kt` | JVM/Android read in part |
| Coil loader and asset path mapping | `commonMain/.../ui/components/SteleKitAssetFetcher.kt` (`SteleKitAssetMapper` maps `../assets/x` through `fileSystem.resolveAssetUri` else `file://<notes>/assets/x`; shared memory cache at 10 percent of heap) | VERIFIED |
| Image block rendering | `ui/components/ImageBlock.kt:40-120` (`rememberSteleKitImageLoader`, `rememberTieredImageModel`, lightbox) | VERIFIED |
| Annotation editor | `ui/annotate/AnnotationEditorScreen.kt:126` + ViewModel, exporter, `ImageEncoder` (expect/actual), `BleDevicePanel`, calibration, depth (`DepthEstimationCoordinator`) | partly read |
| Import and sidecar pipeline | `commonMain/.../db/ImageImportService.kt:49`, byte copy at `:233-262` uses `FileSystem.readFileBytes`/`writeFileBytes` (text fallback on `UnsupportedOperationException`) | VERIFIED |
| Camera abstractions | `platform/sensor/CameraProvider.kt`, `NoOpCameraProvider.kt` (docs say "JVM desktop, WASM"), `PlatformImageFile.kt` (path-based: `path` must be readable via `FileSystem.readFileBytes`); entry points gated by `GraphContentCameraCapture.kt:51` (`imageImportService != null && cameraProvider.isAvailable`) | VERIFIED |
| Cross-graph asset copy | `merge/AssetCopier.kt:104,132,141,151,154` (all `readFileBytes`/`writeFileBytes`), used by `PageMergeService`; web is pull-only (`merge/CopyDirection.kt:12`) | VERIFIED |
| Asset move/rename | `asset/AssetMoveService.kt` (`fileSystem.renameFile` at about `:98`) | VERIFIED |
| Git LFS on JVM/Android | PR #514 (`daceba333d`): `jvmCommonMain/.../git/lfs/{LfsBatchClient,LfsTransferService,LfsObjectStore,LfsPointerScanner,LfsRepositorySupport,LfsFilters}.kt`; `LfsBatchClient.kt` imports `java.io.File` and `io.ktor.utils.io.jvm.javaio` | VERIFIED |

## 2. What exists on wasmJs

| Capability | Where | Verdict |
|---|---|---|
| Attach by picker and drop | `wasmJsMain/.../service/WasmMediaAttachmentService.kt`: `pickAndAttach` (hidden `<input type=file accept=image/*>`, single file, `:128-140`), `attachBytes`; `persistAttachment:81-111` writes with `opfsWriteFileBytes` (`:98`) then `registerBlobUrl` (`:101`) | works, but bypasses dirty tracking and the file-system byte store |
| Drop listener | `ui/components/DropZoneInterop.kt` (body-level `drop`, `isImageFileName` extension list at `:44` includes heic, excludes avif), `PageDropTarget.kt` (50 MiB per-file cap at `:43`, bytes read via `File.arrayBuffer()` + base64 bridge) | works |
| Clipboard paste of images | `MediaAttachmentService.hasClipboardImage` default `false` (`MediaAttachmentService.kt:104`); web service does not override | not implemented |
| Camera | `ui/components/CameraViewfinderDialog.wasmJs.kt:7-11` empty body; `NoOpCameraProvider` (`isAvailable=false`); `ui/transfer/PlatformCameraPreview.js.kt` no-op ("Web is out of scope for QR receive in v1") | stubbed |
| Downscaler | `platform/image/PlatformImageDownscaler.wasmJs.kt:7-8` returns `null` ("browser decodes and caches images itself") | stub: no tiers on web |
| JPEG encode (annotation export) | `ui/annotate/ImageEncoder.wasmJs.kt` via hidden canvas `toDataURL`; guards `MAX_CANVAS_AREA_PX = 16,777,216` (`ImageEncodeGuards.kt:12`); test `wasmJsTest/.../annotate/ImageEncoderWasmJsTest.kt` | works |
| Platform capabilities | `browser/Main.kt:366,515` `PlatformCaps.NONE`; `Main.kt:370,516` attachment service (null in demo-fallback, present otherwise) | no ML |
| Asset index on web | no setter for `AssetBuildFlags.internalBuild` in wasmJs (grep of `kmp/src`: only commonMain definitions); `RELEASE_TOGGLE_VISIBLE=false` | index never runs, Assets browser empty |
| File system | `platform/PlatformFileSystem.kt`: text in `cache` (`:28`), bytes in `bytesCache` (`:29`), image blob URLs in `blobUrlCache` (`:30`); `listFiles` (`:492`) and `directoryExists` (`:584`) read only `cache`; `fileExists` (`:491`) also reads `blobUrlCache`; no `readFileBytes`/`getFileSize` override (default throws, `FileSystem.kt:186`); `writeFileBytes` (`:559`) puts the whole array in `bytesCache` + OPFS + dirty + host write-through; `renameFile` (`:614` region) only handles text `cache`; `resolveAssetUri` (`:875`) = `blobUrlCache[...]` | gaps listed in architecture.md |
| Load-time image handling | `PlatformFileSystem.loadFilesRecursive` (`:439-444`): image files become `blobUrlCache` entries via `readOpfsFileAsObjectUrl` (`OpfsInterop.kt:145`, `URL.createObjectURL(File)`), not bytes | cheap (File-backed) |
| Host-folder reconcile | `HostDirectorySync.kt:1276-1292`: non-page host files are read fully (`readOpfsFileAsBytes`) into `bytesCache` and mirrored to OPFS | heap-heavy for images |
| Host-folder import (relink) | `PlatformFileSystem.importUserDirToCache` (`:758-790`): images become blob URLs only | inconsistent with reconcile |
| Git write-back | `git/WasmGitWriteService.kt`: reads content with `fileSystem.getContentBytes` (`bytesCache` then `cache`; `PlatformFileSystem.kt:474`), `MAX_BLOB_BYTES = 75_000_000` (`:1324`), `Base64.Default.encode` into the blob request (`:575`) | an image only in OPFS + `blobUrlCache` is invisible |
| LFS safety floor | `git/WasmGitWriteService.kt:129-213,497-523` + `git/lfs/WasmLfsRulesSource.kt` (rules fetched from the remote at the base sha; fail closed), `WasmLfsBlobGate`, structural test `businessTest/.../LfsStagingCallSitesStructuralTest.kt:110-114` | guard only; no read or write transfer |
| Share target / PWA | `resources/manifest.webmanifest` (no `share_target`), `resources/stelekit-service-worker.js` (app shell only) | absent |
| Zip export | `export/GraphZipExporter.wasmJs.kt` (reads binary through a JS path, comment `:175`) | includes assets (INFERRED for OPFS-only images; verify in test) |
| Storage APIs | `navigator.storage.persist()` best effort (`platform/HostDirectoryInterop.kt:234-256`); `navigator.storage.estimate()` in `db/BulkCopyVerifier.wasmJs.kt:11-18` (relocation pre-flight only) | partial |
| Browser-only banner | `ui/components/BrowserOnlySyncBanner.kt:43,58` ("stored in your browser only") | text only; no images-at-risk wording |
| Tests that already exist | `wasmJsTest/.../service/WasmMediaAttachmentServiceTest.kt`, `ui/components/{DropZoneInteropTest,PageDropTargetListenerLifecycleTest}.kt`, `annotate/ImageEncoderWasmJsTest.kt`, `git/{WasmGitWriteServiceLfsGuardTest,WasmLfsRulesSourceTest,LfsTolerantMockEngine}.kt`, `platform/OpfsInteropTest.kt`, `export/GraphZipExporterWasmJsTest.kt` | exist |
