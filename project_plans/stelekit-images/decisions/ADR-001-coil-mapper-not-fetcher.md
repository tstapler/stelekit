# ADR-001: Coil Asset Resolution via Mapper, Not Fetcher

**Status**: Accepted
**Date**: 2026-05-15

---

## Context

SteleKit stores attached images under `<graph_root>/assets/` and references them in Markdown as relative paths (`../assets/<filename>`). The `SteleKitAssetFetcher` stub currently exists with a TODO — the path is never resolved, so relative-path images do not render.

Coil 3 already ships a complete local-file loading pipeline:

- `StringMapper` converts any string (including `file://` URIs) into a `coil3.Uri`.
- `FileUriFetcher` reads bytes from a `file://` URI using okio, handling source lifecycle, threading, and the disk-cache layer automatically.
- `PathMapper` converts `okio.Path` to a `coil3.Uri`.

The only missing piece is **URL rewriting**: translating a Logseq-style relative path (`../assets/image.png`) into an absolute `file://` URI anchored at the graph root. Two implementation strategies exist:

1. **Custom `Fetcher.Factory<String>`** — intercepts strings matching `../assets/`, reads the file, and returns a `SourceFetchResult`. Requires re-implementing source lifecycle, `DataSource` tagging, disk-cache integration, and `Keyer` registration.
2. **Custom `Mapper<String, coil3.Uri>`** — rewrites the URL string. Coil's existing `FileUriFetcher` then handles all subsequent steps without modification.

Research confirmed that `SteleKitAssetMapper` requires approximately 15–20 lines in `commonMain`, and that the string itself serves as the cache key via Coil's built-in `StringKeyer` — no custom `Keyer` is needed.

The graph root path must be captured at `ImageLoader` construction time (via `LocalGraphRootPath`) and passed into the `Mapper` constructor. It is not accessible from inside fetcher/mapper `create()` calls because those run off the composition thread.

---

## Decision

Implement `SteleKitAssetMapper` as a `Mapper<String, coil3.Uri>` in `commonMain`. When the input string starts with `../assets/`, the mapper strips the prefix, appends the filename to the absolute graph root assets path, and returns a `file://` URI. For all other strings the mapper returns `null`, leaving Coil's default chain (network, `content://`, etc.) untouched.

Register the mapper in `rememberSteleKitImageLoader` by appending it to the `ComponentRegistry`:

```kotlin
ImageLoader.Builder(context)
    .components {
        add(SteleKitAssetMapper(graphRoot))
        // built-in fetchers (FileUriFetcher, ContentUriFetcher, NetworkFetcher) remain active
    }
    .build()
```

Do **not** implement a custom `Fetcher` for this use case. Do **not** disable the service loader.

---

## Consequences

**Positive**
- Implementation is ~20 lines in `commonMain`, no platform-specific code required for asset resolution.
- Built-in `FileUriFetcher` handles okio source lifecycle, `DataSource.DISK` tagging, disk-cache writes, and thread dispatch — no risk of getting those details wrong.
- Coil's `StringKeyer` uses the original `../assets/<name>` string as the cache key; no custom `Keyer` needed, no cache misses.
- Appending to the `ComponentRegistry` preserves all built-in fetchers (`ContentUriFetcher` for Android gallery, `NetworkFetcher` for http/https).
- Straightforward to test: call `mapper.map("../assets/photo.jpg", options)` and assert the returned URI.

**Negative / Trade-offs**
- The mapper runs for every `AsyncImage` call with a string model, including network URLs. The `startsWith("../assets/")` check is O(1) and has negligible overhead.
- The graph root is baked into the `ImageLoader` at construction time. If the active graph changes, the `ImageLoader` must be rebuilt (already required for other reasons — `rememberSteleKitImageLoader` recomputes when `LocalGraphRootPath` changes).

**Risks mitigated**
- Reading `LocalGraphRootPath` inside `Fetcher.Factory.create()` (which runs off-composition) is avoided by capturing at `ImageLoader` build time.
- `serviceLoaderEnabled(false)` is never called, so `ContentUriFetcher` and `NetworkFetcher` remain available.
