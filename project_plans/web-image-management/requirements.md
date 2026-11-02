# Requirements: web-image-management

**Date**: 2026-11-01
**Type**: feature addition (platform parity: wasmJs target of the `kmp/` module)
**Source of answers**: the Phase 1 interview was answered by the owner's brief in the task prompt (no interactive questions were asked, per the owner's standing "do not ask appetite/downscale questions" rule). Anything the brief did not decide is an explicit Decision in `implementation/plan.md` ("Decisions needing the owner") with a recommended default.

Evidence labels used throughout this directory: **VERIFIED** = the code was opened (file:line cited) or a command was run; **INFERRED** = reasoned from docs, naming or analogy; **UNVERIFIED** = needs a browser/device run (listed as a spike in the plan).

## Problem Statement

"Image management" on Android and Desktop is a coherent set of capabilities: attach images to pages (picker, drop, paste, camera, share), browse and organize them (Assets browser, Gallery), view them at the right size (derived thumbnail/display tiers), annotate and measure them (annotation editor), index and enrich them (asset index, OCR/labels), store them in the graph's `assets/` folder, and sync them (git, including Git LFS). On the web build (wasmJs) a slice of this exists (file picker and drag-and-drop attach into OPFS) but the rest is missing, stubbed, or wired so that it silently does nothing. Evidence is in `research/current-state.md`. In short, on web today:

1. Attached images are written straight to OPFS and shown via a blob URL, but never enter the dirty set, so they are never committed by web git sync nor written through to a connected host folder (VERIFIED: `WasmMediaAttachmentService.kt:81-101` calls only `opfsWriteFileBytes` and `registerBlobUrl`).
2. The Assets browser is reachable from the sidebar but is always empty on web: the index switch is hidden (`AssetBuildFlags.kt:13,19`), and even if on, the web `FileSystem` cannot list, size, read or rename binary files (`PlatformFileSystem.kt:484-496,584`; no `readFileBytes` override).
3. Paste, camera capture, share-to-app, derivative tiers, the annotation/import pipeline, OCR/labels/depth, and LFS-tracked images do not work on web.
4. Images are held in the Kotlin/Wasm heap through a base64 bridge in several paths (ADR-002 of `wasm-image-drop`), so large images or many images risk tab OOM.

The owner wants image management to "work on web" at feature parity with the capabilities a browser can physically provide, and wants it to be safe (no data loss, no raw binaries pushed to LFS-tracked paths, bounded memory, honest persistence messaging).

## Users / Consumers

- Human users of the hosted PWA on desktop browsers (Chromium, Firefox, Safari) and mobile browsers (Android Chrome, iOS Safari).
- The web git write-back service (`WasmGitWriteService`) and the host-folder sync (`HostDirectorySync`) as consumers of image bytes.
- Shared commonMain consumers that today assume a working `FileSystem` for binaries: `AssetIndexService`, `AssetMoveService`, `ImageDerivativeService`, `ImageImportService`, `AssetCopier`, annotation exporter.

## Success Metrics

1. Parity matrix (see `research/features.md` section 1): every row marked "web: possible" reaches "works" with a passing automated test; every row marked "web: impossible or gated" shows an explicit, tested disabled state with a reason (not a silent no-op).
2. An image attached on web (any ingress) appears: in the page, in Assets and Gallery, survives a reload, is included in the next git push (or held with a visible reason if LFS-tracked), is written through to a connected host folder, and is in the graph zip export. Each is a named automated test.
3. Memory: attaching, indexing, and displaying a 50 MiB image never holds more than one full copy of it in the Kotlin/Wasm heap at a time; indexing a graph with 2,000 images keeps resident Kotlin-heap image bytes under a stated cap (default 64 MiB, measured in a `wasmJsBrowserTest`).
4. No regression: existing `WasmMediaAttachmentServiceTest`, drop tests, LFS guard tests, `LfsStagingCallSitesStructuralTest`, and the businessTest gates stay green.
5. LFS: an LFS-tracked image in a remote repo displays on web (read path) on GitHub/GitLab; a locally added image at an LFS-tracked path is never committed raw (existing guarantee), and gets a real write path in the modes the owner approved (ADR-002 of `git-lfs-support`: Direct, Converter, ReadOnly; Proxy rejected).

## Constraints (from repo CLAUDE.md and the owner brief)

- Arrow `Either<DomainError, T>` at repository/service boundaries; no thrown domain errors.
- commonMain-first: pure logic in commonMain with tests in `commonTest`; `commonTest` must not use JVM-only coroutine APIs (`runBlocking`); `wasmJsTest` runs via `wasmJsBrowserTest` (Karma, works per repo notes).
- Test-hygiene gates: no `Thread.sleep`, no real-clock `delay`, no `GlobalScope`; `jvmTest` Compose tests need a `// jvmTest-reason:` header; baselines may only shrink.
- Wiring gates: any new seam interface implementation must be constructed in a composition root or allowlisted (`WiringCompletenessTest`); new screens need `first-open-screens.txt` plus `FIRST_OPEN_SCREENS` in `FirstOpenSmokeTest`.
- Every cross-graph write goes through `TargetWriterRouter`; web copy is pull-only (`CopyDirection.kt`), so web is always the active-graph target.
- Writes never bypass `DatabaseWriteActor`; reads are paginated, projected or chunked (never O(graph) standing observers). `getAllPages`-style unbounded reads are forbidden; the same discipline applies to any new asset query.
- A new web platform capability is a dedicated collaborator class, not a new field on `PlatformFileSystem` (repo memory: wasmJs platform-layer extraction pattern).
- Bazel is canonical but the wasm build delegates to Gradle; wasm tests are Gradle (`:kmp:wasmJsBrowserTest`).
- Uncaught `Throwable` guard on long-lived scopes; `catch (e: CancellationException) { throw e }` first.
- Never ask to downscale scope. Estimates are price-weighted cost classes plus named wall-clock blockers.

## Scope

### In Scope
- Web ingress: file picker (multi-select), drag-and-drop (hardened), clipboard paste, camera capture (mobile `capture` input and desktop `getUserMedia` viewfinder), Web Share Target, and a drop of folders/zips if the owner approves (decision D9).
- A binary asset layer on web: listing, sizing, hashing, reading, renaming, deleting images without retaining bytes in the Kotlin heap; correct dirty tracking; host-folder write-through; zip export; reconciliation that does not load images into `bytesCache`.
- Asset index + Assets browser + detail + move/rename/delete working on web; enabling the index switch on web (decision D5).
- Image tiers: a web `PlatformImageDownscaler` (thumbnail/display derivatives), cached outside git and host sync.
- Annotation editor and import pipeline on web (bytes via real `readFileBytes`, JPEG export via the existing canvas encoder), with BLE/depth/sensor features explicitly gated.
- On-device enrichment on web (OCR, labeling, depth) as additive capability plugins behind `PlatformCaps`, lazily downloaded (decision D6 selects engines; sequenced last).
- Git sync of images on web including size caps, rate limits, held files UI, and Git LFS: read path (pointer resolution to display), write path per the approved modes, and moving the LFS protocol client to commonMain (ADR-004 of `git-lfs-support` was never executed; the client is in `jvmCommonMain`).
- Persistence and quota: storage backend choice, `navigator.storage.persist()`/`estimate()`, quota errors, eviction messaging, browser-only banner copy for images.
- Memory budget and instrumentation for images in Wasm.
- Docs, demo-graph/help registry entries, coverage tier entries.

### Out of Scope
- iOS native and Android behavior changes beyond shared-code edits needed to keep their tests green.
- SteleKit-hosted infrastructure of any kind (LFS proxy rejected by the owner in `git-lfs-support` O1).
- Bluetooth measurement devices on web (Web Bluetooth) unless the owner reverses D7; ARKit/LiDAR depth sensors (hardware-only).
- Replacing the Compose/Coil image stack with DOM `<img>` overlays, unless spike S1 proves Coil cannot render blob URLs (then decision D3 applies).
- Server-side anything.

## Open Questions (carried to research and the plan's decisions)

1. Does Coil 3.2.0 on wasmJs render `blob:` URLs produced by `registerBlobUrl`? (spike S1; INFERRED: the network fetcher only handles http/https)
2. Does the DOM `paste` event reach the page when Compose/Wasm owns focus? (spike S2)
3. Can OPFS `createWritable` be used from the main thread in Safari, or must writes go through a worker sync access handle? (spike S3; INFERRED: not supported on Safari)
4. What is the real Wasm heap peak of the base64 bridge for a 50 MiB image? (spike S4)
5. Which engines for OCR/labeling/depth, if any? (decision D6)
6. Share Target: is POST-to-service-worker acceptable given the app's service worker is shell-only today? (decision D8)
