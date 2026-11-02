# Research: pitfalls and risks (web image management)

Each pitfall: statement, evidence label, design response (plan epic/task), and how it is tested. P-numbers are referenced by the plan, validation and pre-mortem.

## P1. Silent data loss: attached images are never synced or host-written (VERIFIED)
`persistAttachment` writes OPFS and registers a blob URL only (`WasmMediaAttachmentService.kt:98,101`). No `recordDirty`, no `scheduleHostWriteThrough`. A user who attaches images, clears site data or switches browsers loses them while the page markdown (which is dirty-tracked) syncs and shows broken links on every other device. Response: E1/E2 route every binary write through the store with dirty tracking; test `attachedImage_should_AppearInDirtySetAndHostWriteQueue_When_AttachSucceeds`.

## P2. Heap exhaustion from `bytesCache` and the base64 bridge (VERIFIED code, magnitude INFERRED)
The host-folder reconcile path reads every non-text host file fully into `bytesCache` (`HostDirectorySync.kt:1276-1292`) and mirrors it to OPFS; `writeFileBytes` keeps the array resident (`PlatformFileSystem.kt:559-563`). A graph with 2,000 photos of 3 MB is 6 GB if all were resident; even a few hundred exceed mobile Wasm limits. Android's own CLAUDE.md section on graph-scale reads documents the same class of failure (OOM on 8,000-page graphs). Response: JS-owned bytes (architecture section 1), stream copies, resident-bytes budget with instrumentation (E1.4), regression test mirroring `LargeGraphWarmStartCrashTest` for images (E3.5).

## P3. Derived thumbnails leak into git and the host folder (INFERRED from design, high probability)
`ImageDerivativeService` writes `<graph>/.stelekit/cache/images/*.jpg` (`ImageTiers.kt` doc). Once the web downscaler produces output, any write that goes through the dirty-tracking file system path would commit thumbnails to the user's repo, and host reconcile would write them to the user's folder. Response: a single `LocalOnlyPaths` predicate in commonMain (`.stelekit/cache/**`, `.stelekit/lfs/**`, `_inbox/**`) consulted by `recordDirty`, host write-through, reconcile, zip export and the git write service; a structural test (businessTest) that greps every write path call site (same style as `LfsStagingCallSitesStructuralTest`); a property test over generated paths.

## P4. Coil cannot load `blob:` URLs (INFERRED, UNVERIFIED)
`coil-network-ktor3` applies to http(s) only (INFERRED from Coil 3 design). Images attached on web may already render through some other path (the feature was shipped in PR #361 and presumably manually verified) but there is no wasm test of rendering. Response: spike S1 first; if it fails, wasm `BlobUrlFetcher` (architecture 2.7); regardless add a `wasmJsBrowserTest` that composes `ImageBlock` with a real blob URL and asserts non-empty pixels.

## P5. Safari / Firefox OPFS differences (INFERRED)
`createWritable` for OPFS file handles is not available on the Safari main thread (INFERRED); iOS Safari also limits storage and may evict. The current code uses `createWritable` on the main thread (`OpfsInterop.kt:191`), so attach may already fail on Safari with only a console message (`:196`) and, for the picker path, a toast. Response: spike S3; D2 (worker-based write path) with a capability probe at startup; typed `StorageUnavailable` so the UI says why.

## P6. Quota exceeded mid-write leaves partial state (INFERRED)
`QuotaExceededError` can fire at `write()` or `close()`. Response: remove the partial entry on failure; never register the index row or blob URL before `close()` resolves; surface `QuotaExceeded` with `estimate()` numbers; test with a monkeypatched `createWritable` rejection (pattern already used in `WasmMediaAttachmentServiceTest.kt:18-56`).

## P7. Eviction and "browser only" users (INFERRED, product risk)
For graphs with no host folder and no remote, images (and notes) live only in origin storage, which Safari may evict after inactivity and any browser may clear. Existing banner text covers notes generically (`BrowserOnlySyncBanner.kt:43,58`). Response: images make the loss larger and more visible; copy update + `persist()` prompt on first attach + Settings storage panel (E10); a "download all images (zip)" shortcut using the existing exporter.

## P8. Git push size and rate (INFERRED)
A new graph with 200 images pushed at once means 200 sequential-ish blob creations of up to 100 MB each, risking secondary rate limits and long pushes; base64 inflates 33 percent. Response: byte-bounded concurrency, resumable push (dirty entries stay dirty until the commit succeeds, existing behavior), per-file `FileTooLarge` skip with list, progress UI; for oversized files suggest LFS (if the repo supports it) or exclude. Test with `MockEngine` like `WasmGitWriteServiceMockedIntegrationTest`.

## P9. ML engines in a browser (INFERRED)
Multithreaded WASM needs `SharedArrayBuffer`, which needs COOP/COEP headers that static hosting often cannot set; single-thread fallbacks are slow. Model files are large (depth 25-100 MB) and count against the origin quota; downloading on mobile data is hostile. CSP and third-party CDN loads break "local-first". Response: lazy, opt-in, vendored/same-origin, quota pre-check, cancelable, progress via the existing `jobs` infrastructure added in PR #514 (`InProcessJobs`/`BackgroundJobs`, VERIFIED present in that commit's file list); capability probes gate `PlatformCaps`.

## P10. LFS interplay (VERIFIED guard behavior, INFERRED the rest)
- Guard holds back raw files at LFS-tracked paths (`WasmGitWriteService.kt:189-213`). A user attaching a photo to a repo that tracks `*.jpg` via LFS will see the image held forever on web unless a write mode exists. Response: pre-attach hint (rules known from `WasmLfsRulesSource`, cached) and the held sheet (`LfsHeldFilesSheet.kt`) with per-image thumbnails; write modes from `git-lfs-support` ADR-002 amended.
- Pointer files displayed as images (edge case 14). Response: pointer detection before decode.
- Authenticated LFS batch is CORS-blocked on github.com/gitlab.com (VERIFIED 2026-10-11 in `git-lfs-support/research/web-lfs.md`), so only reads via media/raw endpoints work for them, and writes need the Converter workflow or are read-only.
- The JVM `LfsBatchClient` cannot be reused as is (`java.io.File`, `javaio` imports, `LfsBatchClient.kt:36`), so extraction to commonMain is real work, not a copy.
- Converter mode transiently puts a raw blob on a staging ref, which is the single permitted exception to the "no raw blob" floor (ADR-003 of `git-lfs-support`); `LfsStagingCallSitesStructuralTest` must be updated deliberately, not bypassed.

## P11. Flaky browser tests (INFERRED from repo history)
Memory notes record recurring wasm test breakage (JVM-only coroutine APIs in `commonTest`; stale `continue-on-error`). Response: every new pure test in `commonTest` uses `runTest` only; wasm-only tests in `wasmJsTest`; run `:kmp:wasmJsBrowserTest` in the verification step and show output; Karma needs `~/.yarnrc.yml` `nodeLinker: node-modules` (repo memory).

## P12. Test-hygiene gates (VERIFIED rule)
New tests must not use `Thread.sleep`/real `delay`/`GlobalScope`; use `runTest` virtual time or `TestScope`. Browser-event tests that need to wait for a Promise must `await` it, not sleep. Compose tests that import `androidx.compose.ui.test` in `jvmTest` need `// jvmTest-reason:`; the plan keeps Compose-behavior tests in `androidUnitTest` (Robolectric) or `wasmJsTest`.

## P13. Wiring and first-open gates (VERIFIED)
`WiringRoots` does not include the wasm `Main.kt` (see architecture section 3), so adding `WebCameraProvider` fails `WiringCompletenessTest` unless handled. A new screen (e.g., a "Storage and images" panel as a screen rather than a dialog) needs `first-open-screens.txt` + `FIRST_OPEN_SCREENS`; the plan puts the storage panel in Settings (a Dialog; the file explains popup windows are not queryable in the first-open harness) to avoid that, with its own composed test.

## P14. Service worker risk (INFERRED)
The SW currently caches the app shell only and has strict validation (`looks-like-app-shell.js`). Adding a POST handler for share target can break updates if mishandled (the SW comments at `stelekit-service-worker.js:20-50` document prior stale-bundle incidents). Response: isolate the share handler behind an early branch on `request.method === 'POST' && url.pathname.endsWith('/share-target')`, extend the JS tests, and never cache POST.

## P15. Name/URL encoding mismatches (INFERRED)
A file named `Screenshot 2026 #1.png` produces a markdown link that breaks at `#`. Existing `AttachmentResult.toMarkdown` behavior was not audited. Response: sanitization in `AttachmentPolicy` (replace unsafe characters, keep Unicode letters), property test: `sanitize(name)` round-trips through markdown parse to the same path.

## P17. Script execution from user-supplied SVG/HTML blobs (INFERRED, security)
`blob:` URLs inherit the creating origin. If a user-attached `.svg` (which can contain `<script>`) were opened by navigating to its blob URL, it would run with the app's origin and could read `localStorage`/IndexedDB (git tokens, `PlatformFileSystem.githubToken`). Displaying inside `<img>`/Skia is inert. The web `PlatformFileOpener` is a no-op today (VERIFIED), so the risk appears only when "open original" is implemented. Response: task 3.2.1c (download with forced MIME, never navigate), plus a rule that no code path sets `location`/`window.open` to a blob URL; structural test greps `wasmJsMain` for `window.open(` with a blob variable.

## P18. Ordering hazard with the existing push path (VERIFIED)
`lfsVerdict` skips dirty writes without bytes (`WasmGitWriteService.kt:172-173`, fail-open for the LFS guard) and the commit path fails the whole batch with `CommitFailed` when bytes are absent (`:509-513`). Dirty-tracking images before the store-backed blob path exists would (a) block all pushes and (b) bypass the guard on the first change that loosens the failure. Response: feature flag + fail-closed rewrite (tasks 8.1.1a/c), see plan ordering note.

## P16. Selection of "image file" is inconsistent across three lists (VERIFIED)
`DropZoneInterop.kt:44` (jpg jpeg png gif webp heic svg bmp), `PlatformFileSystem.kt:452-455` (adds avif), `WasmMediaAttachmentService.mimeTypeForExt` (has avif, no heic). Response: one commonMain `ImageTypes` table consumed by all; test that the three consumers agree.
