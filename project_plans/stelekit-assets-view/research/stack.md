# Stack Research — SteleKit Assets Viewer

## 1. Coil image loading — `rememberSteleKitImageLoader` / `SteleKitAssetFetcher`

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SteleKitAssetFetcher.kt`

`rememberSteleKitImageLoader()` builds a Coil 3 `ImageLoader` scoped to the active graph. It reads two composition locals:
- `LocalGraphRootPath` — the absolute graph root string (`compositionLocalOf<String?>` in `GraphRootPathProvider.kt`)
- `LocalFileSystem` — platform `FileSystem` for SAF-backed graphs (`compositionLocalOf<FileSystem?>` in same file)

Two `Mapper<String, Uri>` implementations are registered:
- **`SteleKitAssetMapper`**: rewrites `../assets/<filename>` paths to `file://<graphRoot>/assets/<filename>`, or delegates to `FileSystem.resolveAssetUri()` on Android SAF graphs (returns `content://` URI). Paths that don't start with `../assets/` pass through as `null`. Contains path-traversal guards.
- **`SteleKitSafPathMapper`**: rewrites `saf://<path>` to a loadable `content://` URI via `FileSystem.resolveLoadableUri()`.

**For REQ-1 (thumbnails)**: Pass `asset.relativePath` (e.g., `"../assets/photo.jpg"`) as the `model` to `AsyncImage`. The `filePath` field is the absolute path — Coil's built-in `FileUriFetcher` would also handle `"file://<filePath>"` as a fallback, but passing `relativePath` keeps SAF support intact. The loader is already wired to be provided as a parameter; no additional plumbing is needed.

```kotlin
// Usage in AssetItemCard:
val imageLoader = rememberSteleKitImageLoader()
AsyncImage(
    model = asset.relativePath,  // "../assets/photo.jpg"
    imageLoader = imageLoader,
    contentScale = ContentScale.Crop,
    ...
)
```

Both composition locals are populated in `App.kt` via `CompositionLocalProvider`. Any composable under the app root has access.

---

## 2. `PlatformClipboardProvider` — interface and usage

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/PlatformClipboardProvider.kt` — expect declaration
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ClipboardProvider.kt` — the interface
- `kmp/src/androidMain/.../PlatformClipboardProvider.android.kt` — Android actual
- `kmp/src/jvmMain/.../PlatformClipboardProvider.jvm.kt` — JVM actual
- `kmp/src/wasmJsMain/.../PlatformClipboardProvider.js.kt` — WASM actual

The expect function is:
```kotlin
@Composable
expect fun rememberClipboardProvider(clipboard: ClipboardManager): ClipboardProvider
```

`ClipboardProvider` interface:
```kotlin
interface ClipboardProvider {
    fun writeText(text: String)
    fun writeHtml(html: String, plainFallback: String)
}
```

Platform differences:
- **JVM**: `writeHtml` uses `java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(HtmlStringSelection(...), null)` for rich-paste. `writeText` uses Compose `ClipboardManager.setText()`.
- **Android**: Both methods use Compose `ClipboardManager.setText(AnnotatedString(text))` — no HTML flavor on Android.
- **WASM**: Plain text only.

**For REQ-3 (copy markdown link)**: Call `clipboardProvider.writeText(markdownLink)` where `markdownLink` is `"![${filename}](${relativePath})"` for images or `"[${filename}](${relativePath})"` for others. The `ClipboardManager` is obtained via `LocalClipboardManager.current` in a composable, then passed into `rememberClipboardProvider(clipboard)`.

---

## 3. SQLDelight sort queries — `SteleDatabase.sq` asset section

**File**: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq` (lines 1032–1114)

Existing asset queries:

| Query name | Signature | ORDER BY |
|---|---|---|
| `selectAssets` | `(limit, offset)` | `imported_at_ms DESC` |
| `selectAssetsByMediaType` | `(mediaType, limit, offset)` | `imported_at_ms DESC` |
| `searchAssets` | `(query, limit, offset)` | `imported_at_ms DESC` |
| `selectUnprocessedAssets` | `(limit, offset)` | `imported_at_ms ASC` |
| `countAssets` | none | — |
| `countUnprocessedAssets` | none | — |

**Key constraint**: SQLDelight does **not** support parameterized `ORDER BY` — the sort column must be baked into each named query. There is no `CASE WHEN :sort = 'NAME' THEN ... END` equivalent that SQLDelight's codegen supports with type safety.

**Existing indexes** (relevant to sort):
- `idx_asset_imported_at ON asset_index(imported_at_ms DESC)` — covers date-added sort
- `idx_asset_media_type ON asset_index(media_type)` — covers media type filter
- No index on `file_path` for name sort, no index on `size_bytes` for size sort

**Pattern for REQ-4 (sort dropdown)**: Must add separate named query variants per sort order. Minimum viable approach (one query per sort × two base queries = 6 new queries):

```sql
selectAssetsByDate:
SELECT * FROM asset_index ORDER BY imported_at_ms DESC LIMIT :limit OFFSET :offset;

selectAssetsByName:
SELECT * FROM asset_index ORDER BY file_path COLLATE NOCASE ASC LIMIT :limit OFFSET :offset;

selectAssetsBySize:
SELECT * FROM asset_index ORDER BY size_bytes DESC LIMIT :limit OFFSET :offset;

-- Same pattern for media-type-filtered variants...
```

**Alternative**: The Gallery screen (`GalleryViewModel.kt`) handles sorting entirely **in-memory** after a single DB fetch — `images.sortedByDescending { it.importedAtMs }` etc. Since asset pages are bounded (50 per page), in-memory sort within a page is feasible but breaks cross-page ordering (items on page 2 might sort before page 1 items). For correct cross-page sort, the DB query must embed the ORDER BY.

**REQ-7 (orphaned assets)**: No `getOrphanedAssets` query exists. The `is_orphan` column exists in the schema (`INTEGER NOT NULL DEFAULT 0`). Need to add:
```sql
selectOrphanedAssets:
SELECT * FROM asset_index WHERE is_orphan = 1 ORDER BY imported_at_ms DESC LIMIT :limit OFFSET :offset;

countOrphanedAssets:
SELECT COUNT(*) FROM asset_index WHERE is_orphan = 1;
```

**REQ-6 (distinct tags)**: No tag query exists. Tags are stored as JSON arrays in `TEXT` columns. SQLDelight/SQLite cannot query inside JSON arrays natively. Options:
1. Load all `tags` columns in a projection query, decode JSON in Kotlin, and collect distinct values.
2. Use `json_each()` (available in SQLite 3.38+) — Android API 33+ and Desktop support it, but earlier Android APIs may not.
The safest approach is a projection query returning all `tags` JSON strings, decoded in Kotlin.

---

## 4. Platform file opener — `java.awt.Desktop` and Android `Intent.ACTION_VIEW`

**No `PlatformFileOpener` expect/actual exists yet.** The feature requires creating it.

**Closest existing pattern**: `openInBrowser` (`platform/OpenInBrowser.kt` + actuals):

| Source set | File | Implementation |
|---|---|---|
| commonMain | `platform/OpenInBrowser.kt` | `expect fun openInBrowser(url: String)` |
| jvmMain | `platform/OpenInBrowser.jvm.kt` | `Desktop.isDesktopSupported() && Desktop.getDesktop().browse(URI(url))` |
| androidMain | `platform/OpenInBrowser.android.kt` | `Intent(ACTION_VIEW, Uri.parse(url)) + SteleKitContext.context.startActivity(intent)` |

**JVM actual for file opening** (`Desktop.getDesktop().open(File)`): `java.awt.Desktop.open(File)` is available on all JVM platforms. Must guard with `Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)`. The call is synchronous and may block — wrap in `withContext(Dispatchers.IO)`.

**Android actual for file opening**: Use `Intent.ACTION_VIEW` with a `FileProvider` URI. The authority `${context.packageName}.fileprovider` is already registered — confirmed by `PlatformShareProvider.android.kt` line 74: `FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)`. This means a `file_provider_paths.xml` is already configured. The `PlatformFileOpener` Android actual can follow the same pattern:

```kotlin
val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(absolutePath))
val intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, mimeType)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}
context.startActivity(Intent.createChooser(intent, "Open with").apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
})
```

**Note**: `PlatformFileOpener` must be an interface (not a top-level function) because the Android side needs the MIME type to route to the right app. This differs from `openInBrowser` which takes only a URL string.

**Proposed interface** (commonMain):
```kotlin
interface PlatformFileOpener {
    suspend fun openFile(absolutePath: String, mimeType: String)
}

@Composable
expect fun rememberPlatformFileOpener(): PlatformFileOpener
```

This mirrors `PlatformShareProvider`/`PlatformClipboardProvider` pattern — a `@Composable expect fun remember*()` returning an interface.

---

## 5. `SqlDelightAssetRepository` — current structure gaps for new requirements

Missing from `AssetRepository` interface and `SqlDelightAssetRepository`:

| Required by REQ | Missing method | Notes |
|---|---|---|
| REQ-4 | `getAssets(limit, offset, sortOrder)` | New sort-parameterized overloads |
| REQ-4 | `getAssetsByMediaType(type, limit, offset, sortOrder)` | Same |
| REQ-5 | None — `countAssets()` already exists | `hasMore = offset + page.size < totalCount` |
| REQ-6 | `getDistinctTags()` | New — projects all tags JSON, decoded in Kotlin |
| REQ-7 | `getOrphanedAssets(limit, offset)` | New — `WHERE is_orphan = 1` |
| REQ-7 | `countOrphanedAssets()` | New — needed for `hasMore` on orphaned filter |

The `@DirectRepositoryWrite` annotation is required for all write methods. Read methods (returning `Flow<Either<...>>`) use `asDbFlowList`/`asDbFlowOrNull`. Count methods use `withContext(PlatformDispatcher.DB) { queries.countX().executeAsOne() }`.

---

## Key Findings Summary

1. **Coil is fully wired**: `rememberSteleKitImageLoader()` handles `../assets/` relative paths and SAF `content://` URIs via `SteleKitAssetMapper`. Pass `asset.relativePath` to `AsyncImage` — the mapper resolves it correctly on all platforms without any new plumbing.

2. **`PlatformFileOpener` does not exist yet**: Must create it as a `@Composable expect fun rememberPlatformFileOpener(): PlatformFileOpener` pattern. The JVM actual uses `java.awt.Desktop.getDesktop().open(File(path))`. The Android actual uses `Intent.ACTION_VIEW` + `FileProvider.getUriForFile(...)` with the authority `${packageName}.fileprovider` (already registered). `PlatformClipboardProvider` (for copy-link) is already complete — just call `writeText(markdownLink)`.

3. **SQLDelight cannot parameterize ORDER BY**: Separate named query variants are needed per sort order. The `asset_index` table has `is_orphan`, `size_bytes`, `file_path`, and `imported_at_ms` columns to support all three sort orders (date/name/size) and the orphaned filter. Tag filtering requires Kotlin-side JSON decoding since SQLite JSON path functions may not be available on older Android. The `countAssets()` and `countOrphanedAssets()` (new) queries are needed for REQ-5 `hasMore` pagination.
