# Research: features (parity matrix, edge cases, unstated needs)

Evidence labels as in `requirements.md`. Rows reference `current-state.md`.

## 1. Parity matrix: capability by capability

"Web verdict": **Possible** = a browser can do it, the plan builds it; **Gated** = possible only with a precondition (browser support, user opt-in, download) and the UI must show a disabled/explained state otherwise; **Impossible** = physically unavailable in a browser, shown disabled with a reason.

| # | Capability | Desktop / Android today | Web today | Web verdict | Plan epic |
|---|---|---|---|---|---|
| 1 | Attach via file picker | yes | single file only (`WasmMediaAttachmentService.kt:128-140`); bypasses dirty set | Possible (multi-select) | E2 |
| 2 | Attach via drag-and-drop | yes | works; 50 MiB cap, extension filter lacks `avif`, `heic` is accepted but has no MIME mapping (`WasmMediaAttachmentService.kt:115-125`) | Possible (harden) | E2, E5 |
| 3 | Attach via paste | yes (`hasClipboardImage`, `pasteFromClipboard`) | not implemented (`MediaAttachmentService.kt:104` default `false`) | Possible (DOM `paste`), needs spike S2 | E5 |
| 4 | Camera capture | Android camera provider; Desktop uses file picker | stub dialog (`CameraViewfinderDialog.wasmJs.kt`); provider `isAvailable=false` | Possible on mobile (`capture` input) and desktop (`getUserMedia`); Gated by permission and secure context | E5 |
| 5 | Share into app (OS share sheet) | Android share target (INFERRED from `project_plans/android-share-capture-whitespace`) | no `share_target` in manifest | Gated: installed PWA on Chromium/Android only; not iOS | E5 |
| 6 | Persist to graph `assets/` | yes | OPFS only; not in dirty set; not host-written | Possible | E1 |
| 7 | Image shown in page | yes (Coil, tiers) | blob URL via `resolveAssetUri`; Coil rendering of `blob:` UNVERIFIED (S1) | Possible | E4 |
| 8 | Thumbnails and display tier | yes (`ImageDerivativeService`) | `downscale` returns null (`PlatformImageDownscaler.wasmJs.kt:8`), so original is always used | Possible (`createImageBitmap` + `OffscreenCanvas`) | E4 |
| 9 | Asset index and browser (search, filter, tags) | yes (when switch is on) | switch hidden; FS cannot list/size/read binaries | Possible | E3 |
| 10 | Asset detail, move, rename, delete | yes | `renameFile` only handles text `cache` | Possible | E3 |
| 11 | Gallery of annotated images | yes | reachable; empty because import is unreachable | Possible | E6 |
| 12 | Import to annotation pipeline (sidecar, dedupe by hash) | yes | `ImageImportService` constructed on web; `readFileBytes` throws | Possible | E6 |
| 13 | Annotation editor (draw, label, export JPEG) | yes | editor is commonMain; encoder actual exists; bytes loading blocked by (12) | Possible | E6 |
| 14 | Measurement via BLE device | Android (`BleDevicePanel`) | n/a | Gated: Web Bluetooth Chromium-only; recommend hide (D7) | E6 |
| 15 | Depth estimation / calibration via sensors | Android ML + motion sensors | `NoOp*` / no sensors | Gated: ONNX/WebGPU model download (D6); motion via `DeviceMotionEvent` on mobile only; LiDAR/ARKit impossible | E7 |
| 16 | OCR | Android ML Kit (`MlKitTextRecognizerTest`) | `PlatformCaps.NONE` | Gated: tesseract.js lazy (D6) | E7 |
| 17 | Image labels | Android ML Kit | none | Gated: ORT-web/Transformers.js (D6) | E7 |
| 18 | Google Photos / Drive import | yes (commonMain `GooglePhotosImportController`) | uses `attachBytes`; OAuth device flow is browser-launching | Possible; verify CORS for Google endpoints (INFERRED risky) | E5 (verify only) |
| 19 | Git sync of images | yes (JGit) | image not visible to write service (it reads `bytesCache`/`cache` only) | Possible | E8 |
| 20 | Host-folder write-through | n/a (native FS) | images skipped (never reach `scheduleHostWriteThrough`) | Possible (Chromium) | E8 |
| 21 | Zip export includes images | yes | OPFS images included? INFERRED yes via JS read path (`GraphZipExporter.wasmJs.kt:175`) | Possible; add test | E8 |
| 22 | Git LFS read (display LFS-tracked image) | JVM/Android: transfer service (PR #514) | none (pointer file is shown as a broken image) | Possible for GitHub/GitLab via CORS-open media endpoints; private repos need token | E9 |
| 23 | Git LFS write (add image at LFS-tracked path) | JVM/Android upload | guard holds the file back (`WasmGitWriteService.kt:189-213`) | Gated by host CORS: Direct (Bitbucket/Azure/Giftless), Converter workflow (opt-in), else ReadOnly | E9 |
| 24 | Cross-graph copy with assets | Push on Desktop/Android | Web is pull-only; `AssetCopier` needs `readFileBytes`/`writeFileBytes` | Possible (web as target) | E3 |
| 25 | Storage visibility and quota handling | n/a | none for images (`estimate()` only for relocation) | Possible | E10 |

## 2. Edge cases the design must handle (each becomes a test or a UI state)

1. Duplicate content, different names; same name, different content (existing `uniqueFileName`, mutex `writeMutex` at `WasmMediaAttachmentService.kt:41`). Content-hash dedupe is index-level only (`AssetIndexService.registerAsset:77-85`).
2. HEIC/HEIF from iPhone uploads: stored fine, but only Safari can decode. Show a typed "preview unavailable" tile; do not crash the tier generator; consider optional conversion to JPEG on ingest on Safari only (decision D10).
3. Animated GIF/WebP/APNG: downscaling to JPEG would flatten them. Rule: never derive tiers for GIF/animated images; use original.
4. SVG: renders as an image; scripts inert in `<img>`; but Coil/Skia on wasm may not rasterize SVG (INFERRED). Treat as original-only; sanitize nothing (not injected as DOM).
5. EXIF orientation: portrait phone photos; use `imageOrientation:'from-image'` and verify the derivative is upright; the original is never modified.
6. Very large images (> `MAX_CANVAS_AREA_PX` 16.7 Mpx, e.g. 48 Mpx phone photos): decode may fail or exceed canvas limits (Safari canvas limits are lower, INFERRED); tiers must fall back to original and the annotation editor must show "too large to edit" rather than a blank canvas (encoder returns an empty array on this path, `ImageEncoder.wasmJs.kt:23-31`).
7. Multi-file drop with one failure: continue with the rest (existing per-file try/catch, `PageDropTarget.kt:46-62`).
8. Drop while no page is open (`onFileDrop` returns when current screen is not a PageView, `GraphContentCameraCapture.kt:189-196`): show feedback instead of silently ignoring.
9. Offline: attach, index, annotate must work with no network; sync defers. SW already serves the shell.
10. Quota exceeded during write: partial OPFS file must be removed (existing abort handling at `OpfsInterop.kt:195-210` covers the writable; the file entry may remain empty); surface a typed error; do not register a blob URL.
11. Tab closed mid-write: orphan temp file; use write-to-temp-then-rename pattern equivalent (`.tmp-` in Jvm). In OPFS `createWritable` already writes to a swap file and commits on `close()` (INFERRED from spec), so atomic by default; sync access handles are not, so the worker path (D2) needs its own temp+move.
12. Two tabs: `WebLock.kt` exists; image writes must take the per-graph lock used for graph writes (INFERRED to be required; verify in `GraphSyncSession` usage).
13. Graph switch while images load: blob URLs belong to the previous graph's path namespace; revoke on switch and on delete (`blobUrlCache` has no revoke path today: VERIFIED: `grep revokeObjectURL` over wasmJsMain finds only the text-download helper at `PlatformFileSystem.kt:894` and share-provider helpers, none for `blobUrlCache` entries written at `:441,:773,:874`).
14. LFS pointer files that happen to be named `.png`: display must detect pointer content (spec-strict `LfsPointer` exists in commonMain, `git/lfs/LfsPointer.kt`) before handing bytes to the decoder.
15. File names: Unicode, spaces, `#`, `?`, `%` in names flow into markdown `![](../assets/<name>)`; existing mapper rejects `%2e` and `..` (`SteleKitAssetFetcher.kt:37-48`); URL-encoding must be consistent end to end (test).
16. Demo-fallback mode has no persistent storage and hides attachment (`Main.kt:370`); ephemeral session (`markEphemeral`) must keep images in memory-only with a hard cap.
17. Safari private mode / storage disabled: OPFS may throw on first use; typed error, banner "storage unavailable".

## 3. Unstated needs (what users will expect beyond the brief)

- A progress indicator and cancel for multi-megabyte attaches, and a "no page open" hint on drop.
- "Where are my images stored?" in Settings: OPFS size, host-folder status, last pushed, LFS status; one place that answers "are my images safe".
- Persistent storage prompt at the right moment (first attach) with plain-language copy.
- Bulk import of a folder of images (Chromium `showDirectoryPicker`, or `<input webkitdirectory>` elsewhere) (decision D9).
- Rotate/crop (not in Android/Desktop today? INFERRED not present; out of scope unless owner asks).
- Accessibility: alt text prompt on attach; existing `AsyncImage(contentDescription = altText)` shows it is supported.
- Paste from screenshot tools and "copy image" from web pages (clipboard may deliver `image/png` file, or an HTML `<img src>` only; the HTML-only case should insert the URL as a markdown image link, not fetch it, to avoid CORS surprises).
