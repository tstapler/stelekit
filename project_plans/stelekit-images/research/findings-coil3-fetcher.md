# Findings: Coil 3 Custom Fetcher Pitfalls

## Summary
Coil 3 introduced a new API surface that diverges significantly from Coil 2. The `Fetcher`
interface returns a `FetchResult` (either `SourceFetchResult` with an `OkioSource` or
`DrawableFetchResult`). Common pitfalls include: incorrect `Keyer` implementation causing
cache misses or memory leaks, forgetting to close the `OkioSource`, returning the wrong
`DataSource` enum (causing cache policy violations), and not handling the `ImageRequest`
size parameters for sampling. Thread safety issues arise when the fetcher accesses
mutable platform state without synchronization.

## Options Surveyed
1. `Fetcher` + `Fetcher.Factory` — Coil 3 standard extension point
2. `Mapper` — transforms the request key before dispatching to an existing fetcher (simpler if the target is a known type like `Uri` or `File`)
3. `Keyer` — controls cache key generation; often needed alongside `Fetcher`

## Trade-off Matrix
| Approach | Implementation effort | Cache correctness | Memory safety | Platform isolation |
|-----------|-----------------------|------------------|---------------|--------------------|
| Custom `Fetcher` | High | Manual (need Keyer) | Manual (close source) | Full control |
| `Mapper` to existing type | Low | Automatic | Automatic | Relies on target type's fetcher |
| `Fetcher` + `Mapper` combo | Medium | Automatic | Manual | Flexible |

For `../assets/` paths, a `Mapper<String, File>` (or `Mapper<String, Path>`) that resolves
the relative path to an absolute `java.io.File` is simpler than a full custom `Fetcher`,
because Coil already has `FileFetcher` that handles `File` correctly.

## Risk and Failure Modes

### OkioSource not closed — resource leak
- **Failure mode**: File descriptor leak; on Android this causes `Too many open files`
  after repeated image loads.
- **Trigger**: Returning `SourceFetchResult(source = bufferedSource, ...)` where the
  source is never closed if Coil's pipeline throws before consuming it.
- **Mitigation**: Wrap in `use { }` or rely on Coil's `SourceFetchResult` contract —
  Coil 3 closes the source after decoding. Do NOT close the source yourself before
  returning it. [TRAINING_ONLY — verify Coil 3 source lifecycle contract]

### Wrong `DataSource` enum — unexpected caching behavior
- **Failure mode**: Returning `DataSource.MEMORY` for a file read causes Coil to skip
  disk cache writes; returning `DataSource.NETWORK` for a local file causes cache-control
  logic to re-fetch unnecessarily.
- **Trigger**: Copy-paste error from a network fetcher example.
- **Mitigation**: Use `DataSource.DISK` for local file reads.

### `Keyer` not registered — cache misses on every load
- **Failure mode**: All loads of the same image are cache-miss because Coil uses the raw
  `String` key (e.g., `"../assets/photo.jpg"`) which differs from the resolved absolute
  path used internally.
- **Trigger**: Registering a `Fetcher` without a corresponding `Keyer`; Coil cannot
  correlate the original request key to the fetcher's output.
- **Mitigation**: Register a `Keyer<String>` that returns the absolute resolved path as
  the cache key string.

### Accessing `LocalGraphRootPath` inside `Fetcher.Factory.create()`
- **Failure mode**: `Fetcher.Factory.create()` runs off the main thread; if
  `LocalGraphRootPath` is read from a `CompositionLocal`, it is only available on the
  composition thread.
- **Trigger**: Trying to read composition locals inside the fetcher.
- **Mitigation**: Resolve the graph root path at `ImageLoader` construction time (e.g.,
  pass a `() -> Path` lambda or a `StateFlow<Path?>` into the `Fetcher.Factory`
  constructor). The `LocalGraphRootPath` value should be captured and passed explicitly,
  not accessed from inside the fetcher.

### Memory cache size with large images — OOM
- **Failure mode**: Default Coil memory cache is 25% of available RAM. Loading many
  full-resolution images in a `LazyColumn` exhausts the cache and triggers GC pressure.
- **Trigger**: Page with 10+ multi-megapixel images.
- **Mitigation**: Use `size(Size.ORIGINAL)` only for full-screen display; use
  `ImageRequest.Builder.size(width, height)` for thumbnails in block list views.
  Coil samples images to the requested display size by default.

### Thread safety in `Fetcher.Factory.create()`
- **Failure mode**: `Fetcher.Factory.create()` may be called concurrently from multiple
  coroutines. Any mutable state in `Factory` must be thread-safe.
- **Trigger**: Storing a mutable `var` in `Factory` that is updated per-create call.
- **Mitigation**: Make `Factory` stateless or use `@Synchronized` / `AtomicReference` for
  any shared mutable state.

## Migration and Adoption Cost
- Coil 3 API is a breaking change from Coil 2 (different package, different `Fetcher` interface).
- The project already uses Coil 3.2.0 — no migration needed.
- Custom `Fetcher` requires ~50-100 lines of Kotlin per platform.
- Alternative: `Mapper<String, okio.Path>` pattern is ~20 lines and leverages existing `OkioFetcher`.

## Operational Concerns
- Enable Coil's debug logging: `ImageLoader.Builder.logger(DebugLogger())` in dev builds.
- Coil 3 provides `EventListener` hooks for cache hit/miss monitoring.
- Memory pressure is visible via Android Studio's Memory Profiler when scrolling image-heavy pages.

## Prior Art and Lessons Learned
- Coil docs recommend `Mapper` over custom `Fetcher` for URI transformation use cases.
- The `SteleKitAssetFetcher` stub already exists — the simplest implementation resolves
  `../assets/<name>` to `<graphRoot>/assets/<name>` as a `java.io.File` or `okio.Path`
  and delegates to Coil's built-in file/path fetcher via a `Mapper`.
- Coil 2 → 3 migration guide: `BitmapFactory` → `ImageDecoder` path changed; `Target`
  interface changed; `Fetcher.fetch()` return type changed.

## Open Questions
- [ ] Does Coil 3.2.0 correctly close `OkioSource` from `SourceFetchResult` after decoding, even on cancellation? — blocks: whether explicit close is needed in the fetcher
- [ ] Is `Mapper<String, okio.Path>` supported in Coil 3.2.0 for the `../assets/` use case? — blocks: choosing Mapper vs full Fetcher

## Recommendation
**Recommended option**: `Mapper<String, okio.Path>` per platform, resolving `../assets/<name>` to absolute `okio.Path`, delegating to Coil's built-in `OkioFetcher`.

**Reasoning**: Simpler than a full `Fetcher`; Coil handles source lifecycle, caching, and sampling automatically. The only platform-specific logic is path resolution.

**Conditions that would change this recommendation**: If path resolution requires async I/O (e.g., checking file existence), a full `Fetcher` with coroutine support would be needed.

## Pending Web Searches
1. `Coil 3 custom Fetcher "SourceFetchResult" "DataSource.DISK" example site:github.com` — find real implementation examples
2. `Coil 3 "Mapper" "okio.Path" custom fetcher vs mapper` — confirm Mapper approach viability
3. `Coil 3 "Keyer" cache miss "fetcher" registration pitfall` — verify Keyer requirement

## Web Search Results (verified 2026-05-15)

### Coil 3 service loader — VERIFIED
- Custom `.components { add(...) }` appends to, not replaces, platform defaults loaded via service loader.
- Service loader can be disabled with `serviceLoaderEnabled(false)` — do not do this unless you want to register ALL components manually.
- Source: [Coil — Extending the Image Pipeline](https://coil-kt.github.io/coil/image_pipeline/)

### Mapper approach — VERIFIED
- Coil 3 `Mapper` is the correct approach when the source data type (e.g., `String` path) needs to be converted to a type Coil already has a `Fetcher` for (e.g., `okio.Path` or `java.io.File`).
- `ImageSource` in Coil 3 supports `okio.FileSystem` directly — use `ImageSource(file = path, fileSystem = FileSystem.SYSTEM)` for local file reads.
- Source: [Extending the Image Pipeline - Coil](https://coil-kt.github.io/coil/image_pipeline/), [Extending Coil — Ryan Harter](https://ryanharter.com/blog/2024/04/extending-coil/)

### Known issue: custom fetcher memory loading slow — VERIFIED
- GitHub issue #2770: "Custom Fetcher Memory Loading is really slow" — loading from `ByteArray` via custom fetcher can be slow due to no disk cache bypass.
- Recommendation: for file-backed images, always use `DataSource.DISK` + file path, not `ByteArray` in memory.
- Source: [coil-kt/coil Issue #2770](https://github.com/coil-kt/coil/issues/2770)
