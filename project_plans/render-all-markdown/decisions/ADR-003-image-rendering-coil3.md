# ADR-003: Image Rendering Approach

**Status**: Accepted
**Date**: 2026-04-13
**Feature**: Render All Markdown Blocks

---

## Context

`ImageNode` in `MarkdownEngine.kt` currently appends an underlined annotation to the `AnnotatedString` — it never emits an actual image composable. This is a structural limitation: `AnnotatedString` cannot embed `Composable` content; real image loading requires an `AsyncImage` or similar Composable.

The fix requires two changes:
1. `ImageNode` cannot be rendered inline inside `BasicText` / `AnnotatedString`. Standalone image blocks must be routed to an `ImageBlock` composable via the `BlockItem` dispatch.
2. Logseq stores images as relative paths (`../assets/image.png`) relative to the graph root. A plain `AsyncImage(url)` call will not resolve these.

### Library options evaluated

**Option A — No library; `Image(bitmap = ...)` with manual HTTP and file loading**

Implement custom coroutine-based fetcher: read file bytes with `kotlinx-io`, decode with `skia`/`ImageBitmap.makeFromEncoded`. HTTP via Ktor manually managed.

Rejected: high implementation cost, no caching, no placeholder/error state, cross-platform `ImageBitmap` decoding from bytes has known pitfalls in CMP.

**Option B — Coil 3 (chosen)**

Coil 3 (`io.coil-kt.coil3`) is a full Kotlin Multiplatform rewrite of Coil 2. It targets JVM, Android, iOS, and JS. `coil-compose` provides `AsyncImage` composable directly. `coil-network-ktor3` adds Ktor-based HTTP support. Coil 3 ships with built-in caching, placeholder/error state, and `ContentScale` support.

**Option C — Kamel**

`media.kamel:kamel-image` is another KMP image library. Less widely adopted than Coil 3; Coil 3 has a larger community and aligns with the Android ecosystem that the team already uses.

---

## Decision

Use **Coil 3** with a custom `Fetcher` to resolve Logseq relative asset paths.

### Dependencies added to `commonMain`

```kotlin
implementation("io.coil-kt.coil3:coil-compose:3.2.0")
implementation("io.coil-kt.coil3:coil-network-ktor3:3.2.0")
```

Platform-specific Ktor engine entries if Ktor is not already present:
- `jvmMain` / `androidMain`: `io.ktor:ktor-client-okhttp:3.1.3`
- `iosMain`: `io.ktor:ktor-client-darwin:3.1.3`

### `SteleKitAssetFetcher`

Implement a `Fetcher<Uri>` that:
1. Checks if the URI path starts with `../assets/` or is otherwise relative (no scheme)
2. Resolves against `LocalGraphRootPath.current` (a `compositionLocalOf<String?>`) to produce an absolute file path
3. Delegates to Coil's built-in file fetcher for the resolved path

```kotlin
class SteleKitAssetFetcher(
    private val data: Uri,
    private val graphRoot: String,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val relative = data.path ?: throw IOException("null path")
        val resolved = File(graphRoot).resolve(relative).canonicalFile
        return FileImageSource(resolved, options.fileSystem).toFetchResult()
    }
    class Factory(private val graphRoot: String) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != null && data.scheme != "file") return null // let default handle http/https
            return SteleKitAssetFetcher(data, graphRoot, options)
        }
    }
}
```

### `LocalGraphRootPath`

```kotlin
val LocalGraphRootPath = compositionLocalOf<String?> { null }
```

Provided at the page screen composable level from `StelekitViewModel` state (the graph's root directory path is available via `GraphManager`).

### `ImageBlock` composable

```kotlin
@Composable
internal fun ImageBlock(url: String, altText: String, imageLoader: ImageLoader, onStartEditing: () -> Unit, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = altText,
        imageLoader = imageLoader,
        contentScale = ContentScale.FillWidth,
        modifier = modifier.fillMaxWidth().clickable { onStartEditing() },
        placeholder = ...,
        error = ...,
    )
}
```

### Handling `ImageNode` within inline content

`ImageNode` appearing inline (e.g., `text before ![alt](url) text after`) cannot be rendered as an actual image inside `AnnotatedString`. Phase 1 behavior: retain the existing underlined link annotation for inline images. Standalone image blocks (blocks where the only content is `![alt](url)`) are routed to `ImageBlock` via `BlockItem` dispatch. A standalone image block is a `ParagraphBlockNode` with a single `ImageNode` child — detect this in the dispatch or by checking `block.blockType` if `BlockParser` produces a distinct type.

---

## Consequences

**Positive**:
- Production-grade image loading with caching, placeholder, and error state out of the box
- KMP-native: one implementation covers all targets
- Custom fetcher cleanly separates Logseq path resolution from Coil internals

**Negative**:
- New dependency (Coil 3 + Ktor): adds ~800KB to binary. Acceptable given Coil 3's KMP maturity.
- Requires `LocalGraphRootPath` composition local threaded from `StelekitViewModel` — a small architecture change

**Neutral**:
- Inline images within mixed-content blocks still render as text links in Phase 1; this matches Logseq's own behavior on initial load and is acceptable
- Ktor engine must be pinned and version-aligned; see Known Issues in the implementation plan
