# Image pipeline framework — plan

Goal: one typed, streaming, budgeted path for every image SteleKit shows, with explicit
downscale (derivatives) and magnify (region decode at native resolution) operations. The
existing tier work (#480, #481) is the foundation; this plan generalizes it.

## Evidence base (verified at `main`, 2026-10-22)

| Fact | Source |
|---|---|
| Tiers THUMBNAIL 256 / DISPLAY 1600 / ORIGINAL exist; `maxEdgePx: Int?` (null = original) | `platform/image/ImageTiers.kt` |
| `rememberTieredImageModel` used by ImageBlock, ImageAnnotationBlockItem, GalleryScreen only | grep of call sites |
| `AssetItemCard.kt:79,139`, `AssetDetailScreen.kt:263`, `AnnotationEditorScreen.kt:407` pass a raw string to Coil `AsyncImage` | read |
| Derivative generation reads the whole file into a `ByteArray` (`fs.readFileBytes`) and downscalers take `ByteArray` | `ImageDerivativeService.generate`, `ImageDownscalerPort` |
| iOS and wasmJs downscaler returns null (originals shown) | `ImageDownscalerPort` KDoc |
| Traversal checks (`..`, `\`, `%2e`) implemented twice: `ImageTierResolver.localOriginalPath` and `SteleKitAssetMapper.map` | both files |
| `okio` 3.17.0 is already a commonMain dependency | `kmp/build.gradle.kts:130` |
| Coil 3.2.0 | `kmp/build.gradle.kts:118` |

UNVERIFIED (Phase 0 measures these before design is frozen): how much heap/CPU the asset-browser
cards actually cost today. Coil on Android sub-samples to the target size, so the cost there may be
CPU and IO rather than heap; the Skia path on desktop may decode at full size. Do not claim an OOM
fix for the cards until measured.

## Target design

All in `commonMain/.../platform/image/` unless noted.

### Types (make illegal states unrepresentable)

- `ImageSource` — sealed: `GraphAsset`, `AbsoluteFile`, `Saf`, `Remote`. Built only by
  `ImageSource.parse(reference, graph): Either<ImageSourceError, ImageSource>`. The traversal check
  lives here once; `SteleKitAssetMapper` and `ImageTierResolver.localOriginalPath` call it.
  `Derivative` paths are a separate type (`DerivativeFile`) that cannot be passed where an
  `ImageSource` is expected, so a cache file can never be re-derived from.
- `PixelSize(width, height)` and `value class Px(Int)` (positive only); `Rect` in source pixels.
- `Resolution` — sealed: `Thumbnail`, `Display`, `Original`, `FitWithin(PixelSize)`,
  `Region(Rect, target: PixelSize)`. Replaces the nullable `ImageTier.maxEdgePx`.
- `ScalePlan` — sealed: `Identity`, `Down(sampleSize, finalSize)`, `Magnify(Region at native res)`.
  Constructed from (source info, `Resolution`, `DecodeBudget`) by one pure function; it is where the
  "never upscale a persisted derivative" rule lives. Pixel upscaling beyond native resolution is a
  GPU concern (draw scaled), never a decode.
- `DecodeError` — sealed (`Unsupported`, `Corrupt`, `OverBudget`, `Io`), returned as `Either`,
  per the repo's repository-boundary convention. No `Throwable` swallowing in the happy path.

### Ports

- `ImageReader` (streaming, platform): `open(source: okio.Source factory)` -> `ImageInfo(size,
  orientation)`; `decode(plan: ScalePlan): Either<DecodeError, Raster>`; `close()`. No full-file
  `ByteArray`. Android: `BitmapRegionDecoder` + `BitmapFactory`; JVM: `ImageIO` reader with
  `sourceRegion`/subsampling; iOS: `CGImageSource` thumbnail/region APIs; wasmJs: `createImageBitmap`
  with resize options (or explicit `Unsupported`, which the loader maps to "show original").
- `ImageSink` for derivative writes: encode to an `okio.Sink`, not a `ByteArrayOutputStream`.
- `FileSystem` gains `openSource(path): okio.Source?` / `openSink(path)`, implemented for SAF via
  `ContentResolver`. `readFileBytes` stays for encrypted files only.

### Single budgeted loader

- `DecodeBudget` — weighted semaphore over in-flight decoded pixels (derived from heap on Android,
  fixed on desktop). Every decode, including Coil's and the annotation editor's, acquires from it.
  Replaces the per-class mutex in `ImageDerivativeService`.
- `ImageLoaderCore.load(source, resolution): Flow<Frame>` emits progressively: thumbnail, then
  display, then region tiles. This is the "upscale" path in the user-visible sense: a coarse frame
  is stretched while the sharper one decodes.
- One Coil `Fetcher`/`Decoder` pair (not a Mapper; supersedes `stelekit-images` ADR-001, which chose
  a Mapper before tiers existed) takes `ImageSource` + `Resolution` as the request data and
  delegates to `ImageLoaderCore`.
- `SteleKitImage(source, resolution, ...)` composable is the only sanctioned UI entry.

### Tiles for click-in viewing

- `TileGrid` — pure function: (source size, viewport, zoom) -> list of `Region` tiles with sample
  sizes. Property-tested in `commonTest` (coverage, no overlap, budget respected).
- Viewer scheduler cancels off-screen tile decodes; tile cache is bounded by the shared memory cache.

### Derivative cache

- Key = (`ImageSource`, `Resolution`, source stamp = mtime + size) instead of comparing mtimes at
  read time. Existing `<hash>.thumb.jpg` / `.display.jpg` layout is kept so current caches stay valid.

## Phases (cost classes, not durations; each phase ends green on CI and ships as its own PR)

| # | Phase | Cost | Blocker |
|---|---|---|---|
| 0 | Measure: Pixel_Fold AVD heap/CPU for asset grid and detail; desktop decode size via Coil Skia path | S (agent + emulator run) | emulator available |
| 1 | `ImageSource` + `Resolution` + `DecodeError` types, consolidate the duplicated traversal check, property tests | M | none |
| 2 | Move asset browser card, list row, and detail screen onto `rememberTieredImageModel` (quick win; no new types needed) | S | none — can ship before 1 |
| 3 | `FileSystem.openSource/openSink` (+ SAF), streaming `ImageReader`/`ImageSink` on Android + JVM; migrate `ImageDerivativeService` off `ByteArray` | L | Phase 1 |
| 4 | `DecodeBudget` + `ImageLoaderCore`; route Coil fetcher/decoder through it; annotation editor and exporter acquire from the same budget | L | Phase 3 |
| 5 | `TileGrid` + region decode + click-in viewer in `AssetDetailScreen`; replace loupe's stretch hack | L | Phase 4 |
| 6 | iOS (`CGImageSource`) and wasmJs readers; remove "returns null" asymmetry | M | Phase 3 |
| 7 | Enforcement: source-scan test failing on `AsyncImage(` / `rememberAsyncImagePainter(` outside `ui/components/image/` (precedent: `MigrationRunnerSchemaSyncTest`); delete `ImageDownscalerPort` ByteArray API | S | Phases 4–6 |

Phase 2 is independent and should go first: it is the user-visible gap and needs no new design.

## Tests

- Commonmain property tests (`kotest-property`): `ImageSource.parse` rejects every traversal variant
  and round-trips valid refs; `ScalePlan` never exceeds budget and never magnifies a derivative;
  `TileGrid` tiles cover the viewport exactly.
- Robolectric (`androidUnitTest`) for the Android reader; JVM tests for the ImageIO reader; a
  large-image fixture (50 MP synthetic) asserting peak decoded pixels stays within `DecodeBudget`.
- On-device: extend `AssetBrowserE2ETest` to scroll a grid of large images on the Android driver.
- Every enforcement test must be shown to fail against the pre-change code.

## Risks

- `BitmapRegionDecoder` cannot decode some formats (e.g. HEIC on older API levels) -> must map to
  `Unsupported` and fall back, covered by a test per format.
- SAF streams are not seekable on all providers; region decode may need a cache copy. Phase 3
  must spike this on the `Pixel_Fold` AVD before committing to `okio.Source` as the only input.
- Coil 3.2.0 custom `Decoder` API differences across platforms; spike in Phase 4.

## Decisions to record as ADRs when each phase lands

1. Fetcher/Decoder replaces Mapper (supersedes `stelekit-images` ADR-001).
2. Streaming `okio.Source` as the input type vs. platform file handles.
3. Persisted derivatives are downscale-only; magnification is region decode + GPU scale.
