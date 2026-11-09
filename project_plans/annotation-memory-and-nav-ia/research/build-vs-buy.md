# Build vs Buy: annotation memory, image loading, loupe, navigation

Date: 2026-11-08. Labels: VERIFIED (primary source opened this session, URL given) / INFERRED (reasoning, not confirmed).

## Repo baseline (VERIFIED by reading files)

- No `gradle/libs.versions.toml` exists; deps are inline strings in `kmp/build.gradle.kts`. Relevant: Coil `io.coil-kt.coil3:coil-compose:3.2.0` (line 118), `androidx.benchmark:benchmark-junit4:1.3.4` (line 395, androidInstrumentedTest only; no `benchmark-macro`, no LeakCanary, no UI Automator), `androidx.compose.material3:material3:1.4.0` (android), `compose.material3` via JetBrains plugin 1.10.3 (`settings.gradle.kts`), `androidx.exifinterface:1.3.7`.
- Existing memory gate: `kmp/src/androidInstrumentedTest/kotlin/dev/stapler/stelekit/performance/AssetPipelineHeapGateTest.kt` — a hand-rolled `PeakHeapSampler` (Runtime.totalMemory-freeMemory sampling), asserts `peakMb < min(100MB, 0.85*maxMemory)`, and asserts preconditions (`maxMemory <= 512MB`, `largeHeap` off). This is already the "small custom harness" pattern.
- Reporting: `.github/workflows/android-benchmark.yml` writes `benchmarks/android-history/<ts>_<sha>.json`, `scripts/benchdiff-export.js --kind android` maps named fields to benchdiff canonical JSON (`SPECS.android` list: group, name, unit, better), then `tstapler/benchdiff@3921c94 (v0.1.0)` posts a sticky PR comment (tag `android-load-benchmark`). Adding memory metrics = adding rows to `SPECS` + fields to the summary. `ci.yml` "ci-memory" comments are CI-runner memory only (`mem-sampler`).
- Loupe cost: `loupeDecodeSize` caps at `MAX_LOUPE_EDGE_PX = 2048` (`kmp/src/commonMain/.../platform/image/ImageSampling.kt:14,45-49`). A 2048x1536 ARGB_8888 bitmap is ~12.6 MB (2048*1536*4); one per press (INFERRED that decodes are not reused/recycled between presses — needs the crash-investigation agent to confirm).
- Sidebar (`kmp/src/commonMain/.../ui/components/Sidebar.kt` ~L320-350): flat list with only `Spacer`-separated groups, no headers or descriptions; Gallery/Assets adjacent under a "Media / Library" comment.
- CI emulator: API 30 x86_64, `-memory 2048` (`ci.yml:348`, smoke job).

---

## 1. Android memory benchmarking and regression detection

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Hand-rolled instrumented harness extending `AssetPipelineHeapGateTest` + benchdiff** | Already works in this repo; runs in the existing API 30 emulator job; metrics are exactly what we choose (post-GC heap, bitmap bytes via Coil listener, `Debug.getNativeHeapAllocatedSize`, `Debug.MemoryInfo.getMemoryStat("summary.graphics")`); zero new deps; plugs into `benchdiff-export.js` | We own noise handling (force GC, N repeats, median); sampling thread race risk; no trace for diagnosis | **Recommended** (primary) |
| **AndroidX Macrobenchmark `MemoryUsageMetric`** | Official; reports `memoryRssAnonKb`, `memoryHeapSizeKb`, `memoryGpuKb` from Perfetto trace; trace artifact helps diagnosis. Added in benchmark 1.2.0, `@ExperimentalMetricApi` (VERIFIED: https://developer.android.com/reference/androidx/benchmark/macro/MemoryUsageMetric) | Needs separate `com.android.test` module, `profileable`, non-debuggable `benchmark` build type; docs say it errors on emulators and "We discourage running the benchmarks on an emulator" (VERIFIED: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview) — suppressible via `androidx.benchmark.suppressErrors` but then numbers are explicitly not trustworthy; UI Automator-driven (drag gestures on a Compose canvas over Skia = awkward); repo has no such module and Bazel/Gradle split makes it a big structural add | Not recommended as gate; **Viable** later for a one-off Perfetto trace on a physical device |
| **Perfetto traces directly** (`PerfettoTrace.record`, heap profile / `perfetto` cmd) | Best forensic tool for native/graphics growth | Manual analysis; not a pass/fail gate; heavy artifacts on a 16 GB runner | Viable for diagnosis only |
| **LeakCanary in androidTest** (`leakcanary-android-instrumentation`) | Catches retained Activities/ViewModels — relevant to Q "switching Assets/Gallery/editor repeatedly" leak check | Detects *leaks*, not peak/bitmap pressure (the suspected crash class); v3 line still alpha: latest release `v3.0-alpha-9`, 2026-06-25 (VERIFIED: https://api.github.com/repos/square/leakcanary/releases?per_page=5; prior alpha-8 was 2024-06) — slow release cadence; Compose-state leaks inside a single Activity mostly invisible to it (INFERRED) | Viable for scenario (g) only, as report-only |
| **Firebase Test Lab** | Real devices, real (low) RAM; Robo/instrumentation | Spark plan: 15 runs/day (10 virtual, 5 physical); Blaze: 60 free virtual-device min/day then ~$1/device-hour (page last updated Aug 2025 per search result; re-check: https://firebase.google.com/docs/test-lab/usage-quotas-pricing); needs GCP credential in CI; no built-in memory pass/fail, only what our test logs | Viable as a later, scheduled (not per-PR) physical-device confirmation; not for the PR gate |
| **Play Console / Android Vitals** | Real-user OOM/LMK/crash clusters, free | Only for Play-distributed builds; app ships via GitHub APK + F-Droid per CLAUDE.md release process, so coverage is partial (INFERRED); lagging indicator | Not recommended as regression gate |
| **Commercial APM (Sentry / Datadog / Embrace)** | Production OOM, exit-reason, breadcrumbs | Violates the privacy constraint (user photos, "benchmark artifacts must not upload"); SDK footprint; single-owner app, no on-call; repo already persists logs + `PreviousSessionLog` exit-reason replay | Not recommended |

Recommendation: extend the hand-rolled harness (new `AnnotationMemoryGateTest` in `androidInstrumentedTest/.../performance/`), and emit a JSON summary into `benchmarks/android-history` consumed by `benchdiff-export.js`. Metrics with acceptable variance (INFERRED, must be calibrated): peak Java heap after forced GC, Coil memory-cache bytes and peak decoded-bitmap bytes (counted via an `ImageLoader` `EventListener`/own wrapper — deterministic, no GC noise), native heap delta, and `ActivityManager.getHistoricalProcessExitReasons` / process-survived boolean for the crash-class scenario. Use hard gate for deterministic counts (bitmap bytes, process survives N placements), report-only for heap/PSS until calibrated. Existing precondition assertions (heap cap, `largeHeap` off) should be copied so the emulator resembles a low-RAM phone.

Caveat: Macrobenchmark forbids emulators by default; a CI emulator is the repo's only Android runner, which is the decisive reason against it.

## 2. Image loading / bitmap management

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Keep Coil 3, tune** (bump 3.2.0 → 3.6.3, set `maxSizePercent`/`maxSizeBytes`, `allowHardware`, explicit `size()`/`Precision`, bitmap config RGB_565 for thumbnails, `crossfade(false)` in grids) | Already KMP (Android, JVM, iOS, wasm) and wired (`SteleKitImageLoaderMemoryTest`); latest is 3.6.3, 2026-09-18 (VERIFIED: https://api.github.com/repos/coil-kt/coil/releases/latest); repo is 4 minor versions behind (3.2.0) — upgrade is cheap | Does not itself solve decoding a >48 MP image for a 3x loupe | **Recommended** |
| **Glide / Fresco / Picasso** | Mature bitmap pools (Glide, Fresco ashmem/native heap) | Android-only (no KMP, would need expect/actual and duplicates Coil layer); Compose integration is secondary; migrating contradicts the "Coil stays" scope line | Not recommended |
| **telephoto (`zoomable-image-coil3`, saket)** — sub-sampled zoomable image | Compose-native, Coil-3 integration, uses `BitmapRegionDecoder`-style tile decoding so full-res memory is bounded; Android+JVM(desktop)+iOS support claimed (INFERRED — confirm module matrix in README); latest 0.19.0, 2026-04-02, actively maintained (VERIFIED: https://api.github.com/repos/saket/telephoto/releases/latest; licence Apache-2.0 INFERRED, check repo) | Targets view/zoom of a single image, not an annotation canvas where our own gestures own pan/zoom; coupling a third-party gesture model to our point-placement/select-move gestures risks conflict; the editor currently shows Fit image + overlay | Viable if editor gains pinch-zoom; not needed for the crash fix |
| **subsampling-scale-image-view (davemorrissey)** | Classic tiled viewer | View-based (needs AndroidView), latest release v3.10.0 dated **2018-03-15** (VERIFIED: https://api.github.com/repos/davemorrissey/subsampling-scale-image-view/releases/latest) — effectively unmaintained | Not recommended |
| **Custom `BitmapRegionDecoder` regional decode for the loupe** (Android actual; JVM actual via `ImageIO` reader `setSourceRegion`) | Loupe needs only a ~120dp window at N× zoom: decode just that source region (e.g. 120dp*3 ≈ 360 px² ≈ 0.5 MB) instead of a whole 2048-px canvas (~12.6 MB) → ~25x less per press, and it can be reused across a drag; fidelity is *higher* (real source pixels, not a downscaled canvas) | Region decode per drag-frame costs CPU (mitigate: decode a tile once on press, slide within it, re-decode when leaving); `BitmapRegionDecoder` can't decode all formats (e.g. some HEIC) and not on every EXIF orientation without manual rotate math (INFERRED); needs expect/actual in `commonMain`; small custom code | **Recommended** (the build that pays off) |

Alternative cheap variant: one cached loupe bitmap per image session (decode at press 1, reuse, release in `onDispose`), capped by device `memoryClass`. Compare against region-decode in the plan; region-decode is the more bounded design.

## 3. Loupe: `Modifier.magnifier` vs custom

`Modifier.magnifier(sourceCenter, magnifierCenter, onSizeChanged, zoom, size, cornerRadius, elevation, clip)` is **android-only** in foundation (page lists "android" platform, not Cmn) and is a no-op below API 28; `zoom`, `size`, `cornerRadius`, `elevation`, `clip` need API 29+ (VERIFIED: https://developer.android.com/reference/kotlin/androidx/compose/foundation/magnifier.modifier). It wraps `android.widget.Magnifier`, which copies pixels from the window surface.

| Criterion | `Modifier.magnifier` | Custom (decode-per-press) | Custom (region-decode) |
|---|---|---|---|
| Memory | ~zero app-side (framework copies from surface) | up to ~12.6 MB bitmap per press | ~0.5 MB window |
| Fidelity | Magnifies *rendered screen pixels* (the Fit-downscaled image), so no extra detail beyond screen resolution; positioning in layout coordinates via `sourceCenter` (px from node top-left) | Re-decoded at higher res (extra detail) | Real source pixels |
| API level | 28 (basic), 29+ for size/zoom | Any | Any |
| Desktop/KMP | Not available in commonMain; JVM desktop would need a custom actual anyway | Common | Needs expect/actual region reader |
| Gesture integration | Takes a state-driven center; works with `detectDragGestures` per the official sample, so aim-then-release fits. But it draws on a system overlay window, cannot be styled with our crosshair/measure overlay or captured in Roborazzi/compose tests (INFERRED), and `magnifierCenter` default offset is system-chosen | Full control; already tested by `LoupeAlignmentE2ETest` (PR #560) | Full control |

Verdict: `Modifier.magnifier` is **Viable** as an Android-only fast path if "screen-resolution zoom" is acceptable for aiming, and would eliminate loupe memory entirely; but it makes measurement-precision claims weaker (aiming at sub-screen-pixel detail needs real source pixels), forks behaviour between Android and desktop, and sits outside the testable Compose tree. **Recommended**: custom loupe, converted to bounded region decode with reuse. Note as option for owner: a magnifier-backed Android path only if the region-decode proves too slow.

## 4. Navigation: M3 adaptive navigation suite vs custom Sidebar

- Jetpack: `androidx.compose.material3:material3-adaptive-navigation-suite` provides `NavigationSuiteScaffold` that switches bar/rail/drawer by window size class (VERIFIED existence: https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation). Material3 Adaptive library stable 1.3.0, 1.4.0-alpha03 (VERIFIED: https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive, updated 2026-10-07).
- KMP status is **unclear**: JetBrains' Compose Multiplatform 1.10 docs list only `org.jetbrains.compose.material3.adaptive:adaptive*:1.3.0-alpha02` and an `adaptive-navigation3` artifact — no mention of navigation-suite or `NavigationSuiteScaffold` (VERIFIED absence: https://kotlinlang.org/docs/multiplatform/whats-new-compose-110.html). A third-party tutorial claims it works on Android/iOS/Desktop under `org.jetbrains.compose.material3` (could not open: 403, https://proandroiddev.com/building-a-fully-adaptive-navigation-system-in-compose-multiplatform-kmp-e1a8921ca792) and a Kotlin Slack thread asks where it is for wasm/iOS (https://slack-chats.kotlinlang.org/t/29739210/another-question-where-s-material3-adaptive-navigation-suite). INFERRED: not reliably available on wasm/iOS; this project targets all four.
- Fit: the suite gives only a flat list of (icon, label) destinations; it has **no section headers or per-item descriptions**, which are the actual requirement. The current Sidebar already has many destinations (Journals, All Pages, Flashcards, Unlinked References, Notifications, Gallery, Assets, Library Stats, Git Setup) plus dynamic items, which exceed what a bottom bar/rail can show; the drawer variant would still need custom item composables for sections/descriptions.

| Option | Verdict |
|---|---|
| Keep custom `Sidebar` and add labeled sections + one-line descriptions (per-item `supportingText`, section header composable; reuse same section model for the Android "More" menu/command palette) | **Recommended** — smallest change, works on all 4 targets, solves the stated problem |
| `NavigationSuiteScaffold` | Viable only on Android/desktop if KMP availability is confirmed; low value because no sections/descriptions; Not recommended for this scope |
| M3 `ModalNavigationDrawer`/`PermanentNavigationDrawer` + `NavigationDrawerItem` (in `compose.material3`, common) | Viable as the container for sectioned drawer; items support only label/badge, descriptions via custom content |

Also lighter IA fix noted: a single "Media" section header with descriptions ("Gallery — photos you captured and annotated", "Assets — every file in this graph's assets folder") answers the owner's metric without merging destinations; merging is the ADR decision.

## 5. LLM-generated bespoke measurement/memory harness vs library

Risks of a hand-rolled/LLM-written harness (INFERRED unless noted):
- **GC/measurement artifacts**: `Runtime.totalMemory - freeMemory` includes garbage; without `System.gc()`+settle it measures GC timing, not retention. `AssetPipelineHeapGateTest` samples peak heap on a thread — peak includes transient garbage and thus is noisy; fine for a loose "<100 MB" cap, poor for regression deltas.
- **Wrong metric**: Bitmap pixel data is native heap on API 26+ (not counted in Java heap), so a Java-heap-only gate can pass while native/graphics memory explodes. Must add `Debug.getNativeHeapAllocatedSize` / `summary.graphics` and a Coil-side bitmap byte count.
- **Hardware bitmaps** live in GPU memory and are invisible to heap counters.
- **Self-validating tests**: an LLM-authored gate that has never been seen to fail proves nothing. Mitigation: mandatory "fails on pre-fix commit" proof (matches success metric 1), plus mutation check (deliberately allocate in the loop and confirm the gate trips).
- **Gesture injection correctness**: simulated placements must go through the same pointer path (Compose test `performTouchInput`) or the crash path (e.g. loupe decode on press) is skipped.
- **Flake/noise**: calibrate on N≥10 runs on the real runner; use median and tolerance bands; keep report-only until stable.

Library does not remove these risks (Macrobenchmark still needs scenario code and has its own emulator limits); it removes only trace plumbing. Mitigation lies in the validation protocol, not the tool choice. Verdict: hand-rolled + validated = **Recommended**, with the above checks written into the plan's validation phase.

## 6. Fork/adapt existing annotation/measurement editors

I did not find (and did not exhaustively search) a maintained KMP annotation + calibration measurement editor to fork; this part is UNVERIFIED. Useful references to *read*, not adopt:
- telephoto (https://github.com/saket/telephoto): sub-sampling and gesture/transform state design for large images (Apache-2.0, INFERRED).
- AOSP `android.widget.Magnifier` and Compose foundation `Magnifier.android.kt` (https://cs.android.com/search?q=file:androidx/compose/foundation/Magnifier.android.kt) for window-based loupe positioning.
- Android `BitmapRegionDecoder` docs (https://developer.android.com/reference/android/graphics/BitmapRegionDecoder) for the tile-decoding contract.
- Open-source Android markup tools (e.g., ucrop for crop, various "image annotator" libraries) are View-based single-platform and do not model scale calibration; judged Not recommended to fork (INFERRED, not individually verified).

Verdict: Not recommended to fork; build on the existing editor.

---

## Summary of verdicts

| Need | Recommended | Viable | Not recommended |
|---|---|---|---|
| Memory regression | Hand-rolled instrumented harness + benchdiff | Macrobenchmark (physical device, one-off), LeakCanary (leak scenario only), Firebase Test Lab (scheduled), Perfetto (diagnosis) | Commercial APM, Play vitals as gate |
| Image loading | Coil 3 (upgrade to 3.6.3 + tune) + region-decode loupe | telephoto if pinch-zoom is added | Glide, Fresco, subsampling-scale-image-view |
| Loupe | Custom with bounded region decode and reuse | `Modifier.magnifier` Android-only fast path | Per-press 2048-px full decode (current) |
| Navigation | Keep Sidebar, add sections/descriptions | M3 drawers as container | `NavigationSuiteScaffold` (no sections; KMP unclear) |

## Open items for follow-up (not resolved here)
- Confirm telephoto platform matrix and licence from its README.
- Confirm whether NavigationSuiteScaffold ships in `org.jetbrains.compose.material3` for desktop/wasm/iOS at CMP 1.10.3 (Maven Central listing).
- Confirm loupe bitmaps are not recycled/reused between presses (code read of `AnnotationEditorScreen.kt` ~L476, `LoupeOverlay` L1610).
