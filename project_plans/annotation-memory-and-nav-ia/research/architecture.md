# Architecture research: annotation memory paths + navigation IA

Project: annotation-memory-and-nav-ia. Date: 2026-11-08. Branch: plan/annotation-memory-and-nav-ia.
Labels: VERIFIED = source opened this session (path:line); INFERRED = reasoned, not run.
Builds on (not re-derived): `project_plans/image-meter/research/architecture.md`, `project_plans/asset-management/research/architecture.md`, `project_plans/stelekit-assets-view/research/architecture.md`, `project_plans/android-ux-overhaul/research/architecture.md`.
Paths below are relative to `kmp/src/` unless prefixed. `cm` = `commonMain/kotlin/dev/stapler/stelekit`, `am` = `androidMain/kotlin/dev/stapler/stelekit`.

## 1. Headline findings

1. **The editor ViewModel scope has no `CoroutineExceptionHandler`** (VERIFIED `cm/ui/annotate/AnnotationEditorViewModel.kt:~196`: `CoroutineScope(SupervisorJob() + Dispatchers.Default)`). CLAUDE.md requires one on every long-lived scope hosting fire-and-forget launches. Each placement does `scope.launch { saveMeasurementAnnotation }` (`:~571`). Any `Throwable` (OOM thrown in whichever coroutine allocates next) escaping there reaches the default handler and kills the process on Android, matching "abrupt close, no error screen". Candidate root cause for the process death mode; the allocation that triggers the OOM is a separate question.
2. **Loupe decode is bounded but large, and the cache is tiny**. Loupe request is `canvas x zoom`, capped to a 2048 px long edge (`cm/platform/image/ImageSampling.kt:14,46-49`): up to 2048x1536 = 12.6 MB ARGB per distinct size. Shared Coil cache is 10 % of the app heap budget (`cm/ui/components/SteleKitAssetFetcher.kt:69,79-82`), about 25 MB if memoryClass is 256 MB (INFERRED; Coil `maxSizePercent` uses memoryClass). Main image (about 6 MB) + loupe (12.6 MB) + any Gallery/Assets thumbnails contend for that, so eviction and re-decode on each press is plausible (INFERRED, must be measured via cache stats).
3. **Undo history is a minor suspect.** `pushUndo` stores up to `maxHistory = 50` full `AnnotationEditorState` copies (`AnnotationEditorViewModel.kt:264-293`), but states share immutable lists and the 518x518 float `depthMap` by reference (data-class `copy`), so cost is O(50 x annotations) pointers plus list copies: kilobytes, not MB (INFERRED). Only `depthMap` (about 1 MB, `518*518*4`) is large and it is shared.
4. **Gallery reads unbounded**: `GalleryViewModel` collects `imageAnnotationRepository.getAllImageAnnotations()` as a standing flow (`cm/ui/gallery/GalleryViewModel.kt:~77`), which contradicts the CLAUDE.md "graph-scale reads must be paginated" rule. Assets is paged (`AssetBrowserViewModel` PAGE_SIZE). Row cost is small but each DB write re-materialises the list; thumbnails are what matter for bitmap memory.
5. **None of the heap/E2E instrumented tests run in CI**. `AssetPipelineHeapGateTest`, `AssetBrowserE2ETest`, `AssetBrowserIndexedE2ETest` are not referenced by any workflow (VERIFIED by `grep -rn "HeapGate\|AssetBrowserE2E" .github` returning nothing). `LoupeAlignmentE2ETest` exists only as a stale build output on this branch (PR #560 open; `kmp/build/tmp/kotlin-classes/debugAndroidTest/.../LoupeAlignmentE2ETest.class`, no source in tree).

## 2. Data-flow diagram (image bytes to memory, Android)

```mermaid
flowchart TD
  CAM[CameraX capture\nAndroidCameraProvider / CameraViewfinderDialog.android] -->|file path| EXIF[ExifOrientationFixer\nBitmapFactory.decodeFile sampled to 16MP,\nrotate = 2 bitmaps, JPEG q95]
  EXIF --> FILE[(JPEG on disk\n graph assets/)]
  PICK[Import / share / Drive] --> FILE
  FILE --> DERIV[PlatformImageDownscaler\ndecodeByteArray sampled, thumb+display tiers\nImageTier THUMBNAIL/DISPLAY]
  DERIV --> TIERFILES[(thumb/ display/ derivatives)]
  FILE -->|source bytes ByteArray| RENDER[AndroidAnnotatedImageRenderer\ndecodeBounded long edge 4096\nasImageBitmap -> bakeAndEncode -> JPEG]
  FILE -->|saf/content URI| COILMAIN[Coil AsyncImage main image\nsize = layout box, no explicit cap]
  FILE --> COILLOUPE[Coil AsyncImage loupe\nImageRequest.size = loupeDecodeSize, cap 2048]
  TIERFILES --> COILTHUMB[Coil AsyncImage thumbnails\nGalleryCard / AssetItemCard / ImageBlock]
  COILMAIN & COILLOUPE & COILTHUMB --> MC[(sharedImageMemoryCache\n10% of heap budget\none MemoryCache, N ImageLoaders)]
  MC --> COMPOSE[Compose painters / GPU textures]
  COILMAIN --> VM[AnnotationEditorViewModel\nstate + undo/redo + persist]
  VM -->|saveMeasurementAnnotation / saveMeasurements| REPO[MeasurementAnnotationRepository -> DatabaseWriteActor -> SQLite]
  VM -.->|runDepthEstimation(ImageBitmap)\nno UI caller found| DEPTH[OnnxMonocularDepthEstimator\ncreateScaledBitmap 518x518 -> FloatArray]
  RENDER --> DRIVE[AnnotationDriveExporter / share]
```

## 3. Allocation sites and worst-case bytes

ARGB_8888 = 4 B/px. Phone canvas example: 1080x2400 screen; image-aligned box for a 4:3 portrait photo is about 1080x1440 (`AnnotationEditorScreen.kt:~416-421` aspect-ratio box).

| # | Site (file:line) | Bound | Worst-case bytes | Notes |
|---|---|---|---|---|
| A1 | Camera -> `ExifOrientationFixer.decodeAndRotateBounded` (`am/platform/sensor/ExifOrientationFixer.kt:131-142`) | `inSampleSizeFor` to 16 MP (`ImageSampling.kt:10,26-33`), OOM retry x3 doubling | decode 64 MB + rotated copy 64 MB = **128 MB transient** | Only when EXIF needs rotation. Output JPEG still full-res on disk. |
| A2 | `PlatformImageDownscaler.downscale` (`am/platform/image/PlatformImageDownscaler.android.kt:12-60`) | sampled decode to largest target edge; OOM retry x3; recycles | decoded (<= ~2x target edge)^2 x4, plus scaled + oriented copies: about 3 x edge^2 x 4 B. Edge 2048: **about 50 MB** | Input is a full `ByteArray` of the source file (48 MP JPEG about 15-25 MB heap) held for the call. |
| A3 | Coil main image (`AnnotationEditorScreen.kt:430-` ) | size = layout box (Coil default `SizeResolver`), Fit, no `size()` | box w x h x 4: 1080x1440 = **6.2 MB**; landscape/tablet/foldable (Pixel_Fold 2208x1840 inner) up to **about 16 MB** | Plus whatever the memory cache holds for this key; hardware bitmaps possible (Coil default allowHardware on API 26+; INFERRED). |
| A4 | Coil loupe (`AnnotationEditorScreen.kt:1632-1650`, `ImageSampling.kt:46-49`) | `capLongestEdge(canvas*zoom, 2048)` | 2048x1536 = **12.6 MB**; tall phone 3x of 1080x1440 = 3240x4320 capped to 1536x2048 = 12.6 MB. Comment at `:1631` says uncapped 3x was about 85 MB | `remember` keys include `canvasSize, zoomFactor`; same key across presses so memory-cache hit if not evicted; `LoupeOverlay` is created per press (`:476-480`), so each press re-enters composition and a miss means a fresh decode. Rendered at `requiredSize` 3x canvas dp (`:1640-1655`), so GPU texture scaling cost is separate. |
| A5 | Shared memory cache (`SteleKitAssetFetcher.kt:69-86`) | `maxSizePercent(context, 0.10)` | about 10 % of memoryClass (INFERRED about 25 MB at 256 MB); unit-tested `< heap/4` (`jvmTest/.../SteleKitImageLoaderMemoryTest.kt`) | One cache, but `rememberSteleKitImageLoader()` builds **a new `ImageLoader` per call site/key** (`:111-118`, `remember(notesPath, fileSystem, context)`), each with its own disk cache + Coil `ComponentCallbacks` registration. No `onTrimMemory` hook clears this cache (`MemoryPressureListeners.kt` fan-out has no Coil listener; INFERRED from grep, no Coil references in `androidMain`). |
| A6 | Gallery/Assets thumbnails (`GalleryScreen.kt:173-190`, `AssetItemCard.kt:80-90`) | `rememberTieredImageModel(..., ImageTier.THUMBNAIL)` falls back to display/original if derivative missing (`ImageTiers.kt:58-60`) | per visible card: 120 dp (about 360 px) square = 0.5 MB; **fallback to original = full-grid-cell-size decode** (Coil sizes to the composable, not the file, so still bounded by cell size); lazy grids bound live count | Risk is not per-bitmap size but cache churn and a missing-derivative storm for thousands of assets. |
| A7 | `AndroidAnnotatedImageRenderer.decodeBounded` (`am/platform/google/AndroidAnnotatedImageRenderer.kt:48-56`) | long edge <= 4096 via power-of-two sample (`MAX_EDGE_PX`) | decode up to 4096x3072 = **50 MB**, then `bakeAndEncode` creates `ImageBitmap(w,h)` output (`AnnotationExporter.kt:39`) = another **50 MB**, plus `ByteArray` source and JPEG bytes: **about 110-130 MB** | Catches Throwable (`:~38`). Note bound is edge-based (4096) not pixel-based; a 4096x4096 sensor crop = 64 MB x2. |
| A8 | `ImageEncoder.encodeToJpeg` (`cm/ui/annotate/ImageEncoder.kt`, android actual) | input size | `Bitmap.compress` output JPEG, small vs bitmap | `ImageEncodeGuards.kt` only guards wasm canvas (16,777,216 px). |
| A9 | Depth estimation (`am/platform/ml/OnnxMonocularDepthEstimator.kt:~165-175`) | `createScaledBitmap` to 518x518 | source `ImageBitmap` (caller-owned, unbounded by this class) + 1 MB scaled + 3x518x518 float input (3.2 MB) + 1 MB output; ONNX session/model about 100 MB+ native (needs >= 3 GB RAM check `:97`) | `runDepthEstimation(ImageBitmap)` has no UI caller in `commonMain`/`androidMain` (grep), so the input bitmap provenance is undefined. Native, not Java heap. |
| A10 | ML Kit labeler (`am/platform/ml/MlKitImageLabeler.kt:45-50`) | sampled decode | pipeline-scale, covered by the existing heap gate | Out of scope for annotation, in scope for cache/heap interplay. |
| A11 | Editor state (`AnnotationEditorViewModel.kt:81-110,264-293`) | `maxHistory = 50` undo + 50 redo | O(100 x annotations) list copies + shared `depthMap` 1.07 MB | Never holds image bitmaps. |
| A12 | `ImageLoader.Builder(context).build()` in `CapturePreviewDialog.kt:40` | **does not** use shared cache (`:40`) | default Coil cache (about 25 % heap budget, INFERRED) | Bypasses `buildSteleKitImageLoader`: a second unbounded-share cache. Candidate to route through the shared builder. |

Worst-case concurrent editor session (phone, INFERRED sum): A3 6 MB + A4 12.6 MB + cache residual up to 25 MB + A7 about 120 MB on export. Peak is the export; the crash-on-placement path involves only A3/A4/A5 and the VM scope, so the loupe/cache/eviction hypothesis and the missing CEH must be tested separately by the planned benchmark (rabbit hole in requirements.md).

## 4. Editor state and persistence

- State: `AnnotationEditorState` (`AnnotationEditorViewModel.kt:81-110`) is immutable; `_state.update { copy }` for each placement; `pushUndo(before)` also clears redo (`:289-295`). `commitAnnotation` (`:~508-590`): guard, `pushUndo`, haptic, optimistic list append, `scope.launch { saveMeasurementAnnotation }`, rollback on `Left`.
- Persistence: single-annotation saves (`:~571`) are **not** under `persistLock`; `persistCommittedSet` / `commitAnnotationEdit` / `updateCalibration` use `persistLock` (`:~704-725`). A fast sequence of placements launches N concurrent `Dispatchers.Default` coroutines (each racing `DatabaseWriteActor`), and an undo mid-flight can interleave with an in-flight single save (correctness gap, INFERRED; belongs in the annotation review).
- Write path obeys the `DatabaseWriteActor` rule via `MeasurementAnnotationRepository` (`@OptIn(DirectRepositoryWrite::class)` on the VM methods).
- Scope: internal, no `CoroutineExceptionHandler` (see headline 1). `DepthEstimationCoordinator` has its own scope (`cm/ui/annotate/DepthEstimationCoordinator.kt`); not read in full, check it for the same rule.

## 5. Benchmark harness integration points

| Layer | Hook | Gives | Cost / caveat |
|---|---|---|---|
| Coil | `ImageLoader.Builder.eventListenerFactory(...)` / `EventListener` in `buildSteleKitImageLoader` (`SteleKitAssetFetcher.kt:87-100`) gated by a test-only parameter | per-request `onSuccess` with `DataSource` (MEMORY_CACHE vs DISK vs NETWORK), decoded `Image.size`, request `Size` | One-line seam; no prod behaviour change. Count decodes per press and bitmap bytes. |
| Coil cache | expose `sharedImageMemoryCache(context)` (internal already, `:79`) to the test: `.size`, `.maxSize`, `.keys` | exact bytes retained by cache | Resettable only via new test seam (`sharedMemoryCache` is a private top-level var, `:76`). Add `internal fun resetSharedImageMemoryCacheForTest()`. |
| JVM heap | `Runtime.totalMemory()-freeMemory()` with `System.gc()` first; poll thread like `PeakHeapSampler` (`AssetPipelineHeapGateTest.kt` sampler, 20 ms) | peak and post-GC retained Java heap | Java heap only; misses native/hardware bitmap memory. |
| Native / PSS | `Debug.getNativeHeapAllocatedSize()`, `Debug.MemoryInfo.getMemoryStat("summary.graphics")`, `ActivityManager.getProcessMemoryInfo` | graphics + native PSS | Noisy on emulator (swiftshader); use as report-only. |
| Crash class | `ActivityManager.getHistoricalProcessExitReasons` (API 30+) after the run; existing `PreviousSessionLog` replay | proves "no process death" without logcat | API 30 emulator matches `android-benchmark.yml` runner. |
| Editor | test seam to build `AnnotationEditorViewModel` with `InMemory` repo + pass `undo` depth; expose `undoDepth` read-only (`internal`) | undo/redo sizes in logs (requirements Observability) | Currently private; add `internal val undoDepth`. |
| UI driving | Compose Android test over real `AnnotationEditorScreen` (precedent: #560 `LoupeAlignmentE2ETest`), `performTouchInput { down; moveTo; up }` x N | N back-to-back placements with real loupe | Needs on-device APK test in `kmp/src/androidInstrumentedTest`. |
| Reporting | `AndroidSpikeSupport.record(spike,key,value)` -> `SPIKE-RESULT` lines to Downloads (`console/spike/AndroidSpikeSupport.kt:76-95`) or `Log.i("ANDROID_BENCH", json)` + `benchdiff-export.js` | machine-readable records | CI today parses `ANDROID_BENCH:` JSON from **CI-side** `adb logcat -d` (`android-benchmark.yml:~52-54`); this is the CI runner, not the owner. Prefer writing a results file to app storage and `adb pull` to avoid logcat dependence. |

Recommended metrics (answering requirements Q2, INFERRED pending calibration): gate on **Coil decode count/bytes per scenario and cache `size <= maxSize`** (deterministic), post-GC Java heap delta (moderate), "no process death / OOM" (binary). Report-only: PSS, native heap, `summary.graphics`.

## 6. Existing heap-gate test and CI wiring

- **`AssetPipelineHeapGateTest`** (`androidInstrumentedTest/kotlin/dev/stapler/stelekit/performance/AssetPipelineHeapGateTest.kt`): `@RunWith(AndroidJUnit4)`, `@FixMethodOrder(NAME_ASCENDING)`, two ordered runs (indexing only, then all plugins). `PeakHeapSampler` polls `totalMemory-freeMemory` every 20 ms on a daemon thread; `measured()` calls `System.gc()` before and asserts `peakMb < min(HEAP_BUDGET_MB=100, 0.85*maxMemory)`. Preconditions are asserted so a bigger heap cannot pass: `maxMemory <= 512 MB`, `FLAG_LARGE_HEAP` off, DB >= 451 MB, >= 9,500 `asset_index` rows, ten real 7000x5714 JPEGs. Results go through `AndroidSpikeSupport.record`. Run command is in its KDoc: `./gradlew :kmp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...`.
- **Where instrumented tests run**:
  - `.github/workflows/android-benchmark.yml` (job `android-benchmark`, `ubuntu-latest`, `reactivecircus/android-emulator-runner@v2`, **api-level 30, x86_64, profile pixel_3a**, KVM udev step). Runs exactly two classes via `-Pandroid.testInstrumentationRunnerArguments.class=`: `SqliteCapabilityTest` and `AndroidGraphBenchmark`; summary -> `benchmarks/android-history/*.json` -> `scripts/benchdiff-export.js --kind android` -> `tstapler/benchdiff` PR comment (`:~144-158`). Runs on PR and push to main/master. This is the natural host for new memory scenarios (adds a third `gradlew` line + metrics in the Python summarizer).
  - `.github/workflows/ci.yml` job `android-smoke` (needs `android`; **api-level 29**, `target: default`, `-memory 2048`, swiftshader): `./gradlew :androidApp:connectedDebugAndroidTest` over `androidApp/src/androidTest` (`SmokeTest`, `PerfExportAndroidTest`, `ImageMeterAndroidTest`, `PhotoInsertAndroidTest`). Different module and API level; a good home for a crash-class pass/fail test, with JUnit report via `mikepenz/action-junit-report` (`fail_on_failure: true`).
  - **Not run anywhere**: `AssetPipelineHeapGateTest`, `AssetBrowserE2ETest`, `AssetBrowserIndexedE2ETest`, `console/*AndroidTest`, `MlKitTextRecognizerTest` (not in any workflow `class=` filter).
- **Bazel vs Gradle**: `bazel-ci.yml` job `bazel-android` builds `//kmp:android_app --config=android` with `mem-sampler` (CI-runner memory, not app memory) and `bazel-android-tests` runs Robolectric `androidUnitTest`. Instrumented/emulator work is **Gradle only** (Epic 7 not done); new device benchmarks must be Gradle `connectedDebugAndroidTest`. JVM-side checks (cache sizing test, VM unit tests) can be Bazel `//kmp:business_tests`/`jvm_tests`.
- Emulator memory gotcha: the smoke job pins `-memory 2048`; the benchmark job does not set `-memory` (default AVD RAM). A crash-class test wants an explicit low-RAM profile (`-memory 1024`/heap cap) in whichever job hosts it (INFERRED; owner's phone RAM unknown, open question Q1).

## 7. Navigation architecture

- **Screen model**: `sealed class Screen` at `cm/ui/AppState.kt:37-110`. Gallery (`:77`) and AssetBrowser(initialSearch) (`:83`) are both `@HelpExempt` ("not primary nav"), as are AssetDetail, AnnotationEditor, DriveBrowser. Primary `@HelpPage` screens: Journals, Flashcards, AllPages, GlobalUnlinkedReferences, PageView.
- **Routing**: `cm/ui/ScreenRouter.kt` `when(currentScreen)`: Gallery `:342-360` (creates `GalleryViewModel(repos.imageAnnotationRepository)`, opens editor via `navigateToAnnotationEditor`), AssetBrowser `:362-378` (creates `AssetBrowserViewModel`, applies `initialSearch`). `NavigationTracingEffect("Gallery"|"AssetBrowser")`. `StelekitViewModel.kt:1215-1216` maps screens to history/a11y labels; `navigateToGallery()` (`:1368`) has no callers found outside `ui/gallery` and `ui/assets` dirs.
- **Sidebar (desktop and Android drawer)**: `cm/ui/components/Sidebar.kt` (1,283 lines). Flat list under one "Navigation" header (`:~315-355`): Journals, [New Section Journal], All Pages, Flashcards | Unlinked References, Notifications | **Gallery** (`:340`, PhotoLibrary), **Assets** (`:341`, FolderOpen) | Library Stats, Git Setup | Logs/Performance only when already on them. The visual groups are just `Spacer(4.dp)`; **no section headers, no descriptions**. Composed by `cm/ui/GraphContentLeftSidebar.kt` (268 lines; wires `onNavigate`).
- **Android bottom bar**: `am/ui/PlatformBottomBar.android.kt` enum `BottomNavItem` = Journals, All Pages, Search, "More" (`:26-31`); Search and More are actions; More calls `onToggleSidebar` (`GraphContentStatusAndBottomBar.kt:93` -> `viewModel.toggleSidebar()`), i.e. opens the same drawer Sidebar. So **Gallery/Assets exist only in the sidebar/drawer**, one tap deeper on Android. Mobile detection and drawer logic: `GraphContentActiveShell.kt:227,290-345`.
- **Other entry points to the two screens**: `GraphDialogLayer.kt:130` (global search "see in Assets" -> `Screen.AssetBrowser(q)`); image blocks / annotation editor "back"; Gallery `onImportImage`. Command palette: `EssentialCommands.kt` navigation group only has "Go to Page" (`:589`); no commands for Gallery/Assets/AllPages (INFERRED from grep; palette is `ui/components/CommandPalette.kt`). Deep links: Android `MainActivity` handles `MainActivityGitSetupDeepLink` (git setup only); no Gallery/Asset deep link found.
- **Data semantics for the IA decision** (VERIFIED from code): Gallery = `ImageAnnotationRepository` rows (images that were imported/annotated; tag filter, sort, import button; tap opens annotation editor); Assets = `AssetRepository` index of everything in the graph assets folder (images, PDFs, files; search, filters, pipeline/OCR plugins; tap opens AssetDetail). Overlap: images in both; only Gallery items have annotation data. Prior detail: `project_plans/stelekit-assets-view/research/architecture.md`, `asset-management/research/architecture.md`.

### What changing nav grouping/descriptions or merging touches

| Change | Touch points | Gates / tests that break or must be updated |
|---|---|---|
| Section headers + one-line descriptions in sidebar | `Sidebar.kt:~315-355` (`NavigationItem` gets optional `description`; new `SectionHeader`); strings via `ui/i18n/I18n.kt` if i18n keys are used | `sidebar` Compose tests (`androidUnitTest`/`jvmTest` using `hasText`), screenshot goldens (Roborazzi, Gradle-only) for sidebar |
| Rename "Assets"/"Gallery" labels | `Sidebar.kt:340-341`, `ScreenRouter` titles, screen top bars | `FirstOpenSmokeTest.kt:~113` `tap("Assets")`; `first-open-screens.txt` entry "Assets browser" (`FirstOpenSmokeCatalogTest` fails if lists drift); `AssetResultsSurfaceTest.kt:239` `hasText("Assets")`; `AssetBrowserE2ETest.kt:110` |
| Add description/section to Android bottom bar / More | `PlatformBottomBar.android.kt` `BottomNavItem`; drawer is shared with Sidebar | `PlatformBottomBar` tests; bottom bar item count affects label-fit on 411 dp (`FirstOpenSmokeTest` uses `w411dp-h891dp`) |
| Command-palette navigation commands (Open Gallery/Assets) | `editor/commands/EssentialCommands.kt` + `I18n.kt` | `WiringCompletenessTest` (`businessTest/.../wiring/`) checks wiring of registered features; command registry tests |
| **Merge Gallery into Assets** (one destination, filter "Annotated") | delete `Screen.Gallery` (`AppState.kt:77`), router branch `:342-360`, `StelekitViewModel.navigateToGallery`+`:1215`, `GalleryViewModel` data source -> asset query joined to annotations (new `ImageAnnotation` view in Assets), `AssetItemCard` needs annotation badge, editor back-navigation target | `DemoGraphCoverageTest.kt:90` lists "Gallery","AssetBrowser" as help-exempt names (update); `HelpPage`/`HelpExempt` annotation check on `Screen` members; Gallery tests; `AssetBrowserViewModel` paging must absorb annotation filter (bounded, per CLAUDE.md); no `.sq` change needed if joined in Kotlin, but a join query would need `RestrictedDatabaseQueries`/MigrationRunner rules |
| Keep both, add sections + descriptions ("Photos: annotated images" / "Files: everything in assets") | Sidebar only | Lowest risk: only label tests above |
| New top-level destination | `Screen` subtype + `@HelpPage|@HelpExempt`, router branch, `first-open-screens.txt` + `FirstOpenSmokeTest.FIRST_OPEN_SCREENS` | the three hygiene gates named in requirements |

## 8. Hotspot disposition (input for plan.md Tech Debt Disposition)

| Area | Size / state | Recommendation |
|---|---|---|
| `AnnotationEditorViewModel.kt` | 1,049 lines, `@Suppress("LargeClass")` | **Isolate via seam.** Add the `CoroutineExceptionHandler`, bounded persistence queue (single `persistLock` for all saves) and an `internal` undo-depth/test seam; extract undo history into a small `UndoHistory<T>` class only if the seam needs it. A full split stays out of scope. |
| `AnnotationEditorScreen.kt` | 1,684 lines | **Isolate via seam.** Move `LoupeOverlay` (`:1607-1684`) and its image-request construction into its own file with an injectable `ImageLoader`/request seam for benchmarks; leave the rest as-is. |
| `SteleKitAssetFetcher.kt` (`buildSteleKitImageLoader`, shared cache) | small | **Extend as-is** (add `EventListener` hook + cache reset seam; route `CapturePreviewDialog` through it). |
| `GalleryViewModel` / `getAllImageAnnotations` | standing unbounded flow | **Refactor first** if Gallery survives (paginate with `limit/offset` per CLAUDE.md); moot if merged into Assets. Decide IA before touching it. |
| `Sidebar.kt` | 1,283 lines | **Extend as-is** for sections/descriptions (add a small `NavSection` composable); do not refactor in this project. |
| `PlatformBottomBar.android.kt`, `ScreenRouter.kt` | 106 / 544 lines | **Extend as-is.** |
| CI wiring (`android-benchmark.yml`) | Python inline summarizer | **Extend as-is**, but if scenario count grows, extract the summarizer into `scripts/` (isolate). |

## 9. Gaps / unverified

- Owner's device RAM and heap class unknown; Coil cache byte size and hardware-bitmap behaviour not measured (all A3-A5 sizes are formulas).
- `LoupeAlignmentE2ETest` source is not in this tree (PR #560); its harness details unreviewed.
- `DepthEstimationCoordinator` scope/CEH and the `runDepthEstimation` real caller not traced.
- Bazel instrumented support, and exact line numbers marked `~`, should be re-checked at implementation time.

## 10. Allocation-path audit (Task B.2e, 2026-11-09)

Read-only audit of the three paths named in requirements In Scope. Sizes are formulas from the code read, not measurements (the probe scenarios measure the real peaks). "Bounded" means a code-level ceiling exists, independent of input size.

| Path | Owner class | Peak bytes or bound | Bounded |
|---|---|---|---|
| Per-capture downscale | `PlatformImageDownscaler.downscale` (`androidMain/.../platform/image/PlatformImageDownscaler.android.kt`) | Decodes once at `inSampleSize` chosen for the largest requested edge (`decodeSampled`), so about `edge^2 * 4` bytes for the sampled bitmap, plus one `createScaledBitmap` copy and one oriented copy when EXIF needs a transform (up to 3 bitmaps of that size live). The input `ByteArray` is held whole. OOM retry doubles the sample size up to 3 times. | Yes for the bitmap (edge-bound); the encoded source bytes are not bounded by this class |
| Coil disk cache | `buildSteleKitImageLoader` (`commonMain/.../ui/components/SteleKitAssetFetcher.kt`) | No `diskCache` is configured, so Coil's platform default applies; no SteleKit-owned ceiling. Memory cache is bounded: one shared `MemoryCache` at `maxSizePercent(0.10)`, now gated by `cacheOverflowBytes` in the probe. | Memory: yes. Disk: not set by SteleKit (finding for A.4: set an explicit `DiskCache` max size or confirm Coil's default) |
| Depth-estimation input bitmaps | `OnnxMonocularDepthEstimator` (`androidMain/.../platform/ml/OnnxMonocularDepthEstimator.kt:141-200`), driven by `DepthEstimationCoordinator.runDepthEstimation` | Resizes the caller's `ImageBitmap` to 518x518 (`createScaledBitmap`), then `IntArray(518*518)` (1.07 MB) and `FloatBuffer(518*518*3)` (3.2 MB) plus the `[1,1,518,518]` output (1.07 MB); about 5.4 MB of owned buffers independent of source size. The caller's full-resolution `ImageBitmap` is the unbounded part: it is whatever the editor already holds (a full decode of the source, 48 MB at 4000x3000 ARGB_8888). | Owned buffers: yes (fixed 518x518). Input bitmap: bounded only by the editor's own decode, see C.7b / A.5 |

Findings handed on: (1) A.4 or C.7b should confirm the Coil disk-cache ceiling; (2) the depth input is the editor's full-size bitmap, so A.5's loupe-decode ceiling does not bound it.
