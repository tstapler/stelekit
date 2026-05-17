# Findings: Image Loading Performance (Many Large Images)

## Summary
The primary risk for a page with many large images is OOM from decoding full-resolution
bitmaps, and jank from synchronous disk reads on the main thread. Coil 3 solves both
problems when used correctly: it samples images to the display size (not original size)
and loads asynchronously. The main pitfall is forgetting to constrain image display size
in `ImageBlock`, which causes Coil to decode at full resolution. `ContentScale.Fit` with
a bounded container is the correct UX pattern for block-level images.

## Options Surveyed
1. Coil 3 with default settings — samples to composable size if `size` is specified
2. Coil 3 with `size(Size.ORIGINAL)` — decodes at full resolution (dangerous for large images)
3. Manual `BitmapFactory.decodeStream` with `inSampleSize` — manual downsampling, no async
4. Coil 3 + explicit thumbnail size — pre-configured small `ImageRequest` for list view

## Trade-off Matrix
| Approach | Memory use | Decode time | Quality | Implementation effort |
|-----------|-----------|-------------|---------|----------------------|
| Coil default (display size) | Low-Med | Fast | Good for display | Zero extra work |
| `Size.ORIGINAL` | High | Slow | Perfect | Zero extra work (but wrong) |
| Manual BitmapFactory | Manual | Manual | Manual | High |
| Explicit thumbnail | Low | Fast | Reduced | Low (set size in request) |

## Risk and Failure Modes

### `size(Size.ORIGINAL)` causing OOM on large images
- **Failure mode**: A 12MP phone photo decoded at original size = ~36MB per image in RAM.
  10 such images in a `LazyColumn` = 360MB → OOM crash on 32-bit devices or low-RAM phones.
- **Trigger**: Using `size(Size.ORIGINAL)` in `ImageRequest` or not constraining the
  `Image()` composable to a bounded size.
- **Mitigation**: Always wrap `ImageBlock` in a bounded `Box` or use `fillMaxWidth()` with
  a fixed `height` — Coil reads the layout constraints to determine sample size.

### Unbounded `Image()` composable — no size hints for Coil
- **Failure mode**: If `ImageBlock` uses `Image(modifier = Modifier.wrapContentSize())`,
  Coil cannot determine a target size and may fall back to `Size.ORIGINAL`.
- **Trigger**: Using `wrapContentSize()` without explicit dimensions.
- **Mitigation**: Use `Modifier.fillMaxWidth().heightIn(max = 400.dp)` or similar bounds.
  `ContentScale.Fit` prevents distortion while respecting the container.

### `LazyColumn` with many loaded images — memory cache eviction thrashing
- **Failure mode**: Scrolling rapidly through 50+ image blocks causes continuous cache
  eviction and re-decode; visible as jank or white flicker frames.
- **Trigger**: More images loaded simultaneously than Coil's memory cache can hold.
- **Mitigation**: Coil's disk cache (enabled by default) prevents re-download but not
  re-decode. For very image-heavy pages, reduce the display size of non-focused images
  (e.g., 200dp thumbnail height) to fit more in the memory cache.

### Disk I/O on main thread (non-Coil path)
- **Failure mode**: If the custom `Fetcher` or `Mapper` for `../assets/` paths does
  synchronous file existence checks on the main thread, it blocks the UI thread.
- **Trigger**: `File.exists()` or `Path.exists()` in a composable or in a non-dispatched
  context.
- **Mitigation**: All fetcher code runs in `Dispatchers.IO` in Coil's pipeline by default.
  Do not call file APIs outside of Coil's or a `withContext(IO)` block.

### `ContentScale` choice and aspect ratio distortion
- **Failure mode**: `ContentScale.FillBounds` stretches non-square images; `ContentScale.Crop`
  clips content; `ContentScale.Inside` may make small images appear at native size with
  surrounding whitespace.
- **Mitigation**: Use `ContentScale.Fit` for block images — preserves aspect ratio,
  fits within container bounds. Acceptable for Logseq-style note-taking UI.

## Migration and Adoption Cost
- No additional dependencies.
- `ImageBlock` needs a bounded `Modifier` — trivial change.
- Coil's memory/disk cache is already enabled by default in `coil-compose`.

## Operational Concerns
- Coil 3 provides cache statistics via `ImageLoader.memoryCache?.size` and `.diskCache?.size`.
- On Android, use Android Studio Memory Profiler to detect bitmap allocation spikes.
- On Desktop JVM, JFR allocation profiling (already in the project's benchmark tooling) can trace large bitmap allocations.

## Prior Art and Lessons Learned
- Logseq desktop uses lazy loading with fixed-height thumbnails in the block list — click to expand full resolution.
- Obsidian mobile limits inline image height to ~400px by default.
- Notion shows images at full width but capped height — consistent with `ContentScale.Fit` + `fillMaxWidth().heightIn(max=N)`.

## Open Questions
- [ ] Does Coil 3 use composable layout constraints for size sampling on WASM/JS? — blocks: whether WASM needs explicit size hints
- [ ] Is there a minimum Coil version where automatic layout-size sampling works in `LazyColumn` without explicit `size()` calls? — blocks: whether to set size explicitly

## Recommendation
**Recommended option**: Use `ContentScale.Fit` with `Modifier.fillMaxWidth().heightIn(max = 400.dp)` in `ImageBlock`. Let Coil sample automatically.

**Reasoning**: Zero extra code; Coil handles downsampling to display size. Bounded container guarantees Coil receives a finite target size. `ContentScale.Fit` is standard for Logseq-style note UIs.

**Conditions that would change this recommendation**: If users want a full-screen lightbox view, add a tap-to-expand flow with `Size.ORIGINAL` only for the expanded view.

## Pending Web Searches
1. `Coil 3 "LazyColumn" image sampling size constraints compose multiplatform` — verify automatic size detection in lazy lists
2. `Compose image loading OOM "wrapContentSize" Coil sampling` — verify unbounded size pitfall
