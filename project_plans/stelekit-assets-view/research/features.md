# Features Research — SteleKit Assets Viewer

## 1. GalleryScreen Patterns to Match

### Sort Dropdown (REQ-4)
`GalleryScreen.kt` implements `SortDropdownButton` as a private composable:
- Trigger: `IconButton(Icons.AutoMirrored.Filled.Sort)` → `DropdownMenu`
- Each `GallerySortOrder.entries` rendered as `DropdownMenuItem`
- `leadingIcon = { RadioButton(selected = order == current, onClick = null) }` marks the current selection visually
- `viewModel.setSortOrder(order)` re-sorts the **in-memory** list only — no repository reload

Mapping to AssetBrowser: add an `AssetSortOrder` enum (`BY_DATE_ADDED`, `BY_NAME`, `BY_SIZE`) to `AssetBrowserUiState`, and a `SortDropdownButton` in `AssetBrowserScreen` placed in the `TopAppBar` actions row (next to the grid/list toggle). `AssetBrowserViewModel.setSortOrder()` should update state and re-trigger `loadAssets()` so the SQLDelight query carries the correct `ORDER BY`.

### Tag Filter Row (REQ-6)
`GalleryScreen.kt` uses a `LazyRow` of `FilterChip` composables:
- "All" chip first (`selectedTag == null`)
- Tags from `state.availableTags` populated by `images.flatMap { it.tags }.distinct().sorted()`
- Toggle logic: `viewModel.selectTag(if (state.selectedTag == tag) null else tag)`

For `AssetBrowserScreen`, the existing `ScrollableFilterChipRow` (using a `Row` with `horizontalScroll`) should be extended to append tag chips after the type-filter chips. Tags must come from a new `AssetRepository.getDistinctTags()` query. REQ-6 caps display at 10 tags with a "+ N more" expander chip.

### Empty State (REQ-8)
`GalleryEmptyState.kt` pattern: `Box(contentAlignment = Center)` → `Column` with:
1. `Icon(modifier = Modifier.size(72.dp), tint = primary.copy(alpha = 0.5f))`
2. `Text(style = headlineSmall, color = onSurfaceVariant)` — headline
3. `Text(style = bodyMedium, color = onSurfaceVariant, textAlign = Center)` — sub-text
4. Optional CTA buttons

`AssetBrowserEmptyState` should use the same structure. Context-sensitive copy:
- `ALL`: icon = `Icons.Default.Attachment`, headline "No assets yet", body "Attach a file to any page to see it here"
- `IMAGES` / `PDFS` / etc.: icon = type-specific (reuse `assetIcon(mediaType)`), headline "No images found" / "No PDFs found" / etc.
- `ORPHANED`: headline "No orphaned assets", body "All assets are referenced by at least one page"

---

## 2. AnnotationEditor Pattern for AssetDetail Screen (REQ-2)

`Screen.AnnotationEditor` in `AppState.kt` is a `data class` carrying a UUID. The same pattern applies to `Screen.AssetDetail`:

```kotlin
data class AssetDetail(val assetUuid: AssetUuid) : Screen()
```

In `ScreenRouter.kt` (around line 255, after the `AssetBrowser` branch), wire it as:

```kotlin
is Screen.AssetDetail -> {
    NavigationTracingEffect("AssetDetail")
    val uuid = currentScreen.assetUuid
    val assetDetailViewModel = remember(uuid) {
        AssetDetailViewModel(repos.assetRepository)
    }
    DisposableEffect(assetDetailViewModel) {
        onDispose { assetDetailViewModel.close() }
    }
    var resolvedAsset by remember(uuid) { mutableStateOf<AssetEntry?>(null) }
    LaunchedEffect(uuid) {
        repos.assetRepository.getAssetByUuid(uuid).collect { either ->
            either.onRight { asset -> resolvedAsset = asset }
        }
    }
    resolvedAsset?.let { asset ->
        AssetDetailScreen(
            viewModel = assetDetailViewModel,
            asset = asset,
            onNavigateBack = { viewModel.goBack() },
            onNavigateToPage = { pageUuid -> viewModel.navigateToPageByUuid(pageUuid) },
        )
    }
}
```

Key points from the AnnotationEditor pattern:
- `remember(uuid)` keys the ViewModel to the asset UUID — ensures a new VM on navigation to a different asset
- `DisposableEffect` + `close()` lifecycle (matches `GalleryViewModel`; note `AssetBrowserViewModel` uses `RememberObserver` instead — either works, but `close()` requires explicit wiring)
- `getAssetByUuid()` already exists in `AssetRepository` — no new query needed for the basic detail load
- Back navigation uses `viewModel.goBack()` (pushes into `navigationHistory`)

`AssetDetailViewModel` should:
- Own `CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { ... })`
- Expose a `close()` method that cancels the scope
- Not accept any externally supplied `CoroutineScope` (CLAUDE.md constraint)

### Detail Screen Layout (REQ-2)
- **Images**: `AsyncImage` with `rememberSteleKitImageLoader()`, `ContentScale.Fit` (not Crop), `Modifier.fillMaxSize()`. Add pinch-to-zoom via `rememberTransformableState`. Title bar via `TopAppBar(title = { Text(asset.filePath.substringAfterLast('/')) }, navigationIcon = { back arrow })`.
- **Non-image types**: `Scaffold` with `TopAppBar` + scrollable `Column` containing a metadata `Card` (filename, size formatted from `sizeBytes`, `mediaType.name`, date from `importedAtMs`, tags as `FilterChip`, `ocrText` in a text block). "Open in…" `Button` at bottom.
- **"Go to page" chips**: `LazyRow` of `AssistChip` for each `asset.pageUuids`. `AssetEntry.pageUuids` is `List<String>` (raw UUID strings, not `PageUuid` wrappers). Tapping calls `onNavigateToPage(pageUuid)`.

---

## 3. What's Already There vs. What's Missing in AssetBrowser

### AssetBrowserUiState — gaps
| Field | Present? | Missing for |
|---|---|---|
| `assets: List<AssetEntry>` | Yes | — |
| `selectedFilter: AssetFilter` | Yes | — |
| `searchQuery: String` | Yes | — |
| `isLoading: Boolean` | Yes | — |
| `error: String?` | Yes | — |
| `totalCount: Long` | Yes | Used for `hasMore` calc (REQ-5), but not yet wired |
| `viewMode: ViewMode` | Yes | — |
| `sortOrder: AssetSortOrder` | **No** | REQ-4 sort dropdown |
| `isLoadingMore: Boolean` | **No** | REQ-5 infinite scroll |
| `hasMore: Boolean` | **No** | REQ-5 infinite scroll |
| `availableTags: List<String>` | **No** | REQ-6 tag chips |
| `selectedTag: String?` | **No** | REQ-6 tag filter |

### AssetBrowserViewModel — gaps
| Method | Present? | Missing for |
|---|---|---|
| `setFilter(AssetFilter)` | Yes | — |
| `setSearch(String)` | Yes | — |
| `setViewMode(ViewMode)` | Yes | — |
| `refresh()` | Yes | — |
| `setSortOrder(AssetSortOrder)` | **No** | REQ-4 |
| `loadMore()` | **No** | REQ-5 |
| `selectTag(String?)` | **No** | REQ-6 |
| ORPHANED filter wired | **No** | REQ-7 (falls through to `getAssets()`) |
| Pagination (offset > 0) | **No** | REQ-5 (hard-coded `offset = 0`) |

### AssetItemCard — gaps
- `AssetGridItem`: shows type icon only — no `AsyncImage`, no `Modifier.clickable`, no long-press
- `AssetListItem`: shows type icon only — no thumbnail, no `Modifier.clickable`, no long-press
- `onLongPress` parameter exists in the composable signature but is not wired to any gesture handler
- `onAction` is passed to `AssetListItem` but not to `AssetGridItem`
- No `AsyncImage` import in the file at all

### AssetBrowserScreen — gaps
- `ScrollableFilterChipRow`: uses a plain `Row` with `horizontalScroll`, not `LazyRow`; iterates only `AssetFilter.entries` (no tag chips, no "select all" → None toggle)
- No sort dropdown in `TopAppBar`
- No empty state composable call
- `LazyVerticalGrid` / `LazyColumn` items don't carry `onClick` or `onLongClick` handlers
- No scroll-end detection for pagination (`itemIndex >= items.size - 5`)
- `LinearProgressIndicator` for initial load only — no "loading more" indicator at bottom

### AssetRepository — gaps for new features
| Method | Present? | Missing for |
|---|---|---|
| `getAssetByUuid(uuid)` | Yes | REQ-2 detail load |
| `getAssets(limit, offset)` | Yes | — |
| `getAssetsByMediaType(type, limit, offset)` | Yes | — |
| `searchAssets(query, limit, offset)` | Yes | — |
| `countAssets()` | Yes | REQ-5 `hasMore` |
| `deleteAsset(uuid)` | Yes | REQ-3 delete |
| `getOrphanedAssets(limit, offset)` | **No** | REQ-7 |
| `getDistinctTags()` | **No** | REQ-6 |
| Sort-aware query variants | **No** | REQ-4 (need `ORDER BY` in SQLDelight) |

### AssetActionMenu — gaps
`AssetActionMenu.kt` renders Open / Copy Link / Delete `DropdownMenuItem` entries but is never shown. The `AssetItemCard` signature has `onLongPress: (() -> Unit)?` and `onAction: ((AssetAction) -> Unit)?` that are wired only to `AssetListItem` (partially) — but no gesture handler activates the menu. Wiring requires:
1. `Modifier.combinedClickable(onLongClick = { /* show menu */ })` on both grid and list items
2. State in `AssetBrowserScreen`: `var actionMenuAsset by remember { mutableStateOf<AssetEntry?>(null) }`
3. `AssetActionMenu` called conditionally with `expanded = actionMenuAsset != null`

---

## 4. AssetEntry Fields Available for Detail Screen

All REQ-2 display fields are present on `AssetEntry`:
- Filename: `filePath.substringAfterLast('/')` (no dedicated `filename` field)
- File size: `sizeBytes: Long` — format as KB/MB
- Media type: `mediaType: AssetMediaType` — use `AssetMediaType.name` or a display mapping
- Import date: `importedAtMs: Long` — format via `kotlinx-datetime` or `Instant.fromEpochMilliseconds`
- Tags: `tags: List<String>` — user-defined strings
- OCR text: `ocrText: String?` — nullable, hide section when null
- Page references: `pageUuids: List<String>` — raw UUID strings for "Go to page" chips
- Image path for AsyncImage: `filePath: String` (absolute path, already handled by `rememberSteleKitImageLoader()`)
- Relative path for markdown link: `relativePath: String`

Notable: there is NO `filename` field — callers must parse from `filePath`. No `mimeType` field — use `mediaType` enum. No `title` or `displayName` field.

---

## 5. ViewModel Lifecycle Patterns in Detail Screens

Two patterns in use:

**Pattern A — `close()` + `DisposableEffect` (GalleryViewModel, AnnotationEditorViewModel)**
- ViewModel owns scope internally
- ScreenRouter calls `DisposableEffect { onDispose { vm.close() } }`
- Explicit; slightly more boilerplate in ScreenRouter

**Pattern B — `RememberObserver` (AssetBrowserViewModel)**
- ViewModel implements `RememberObserver`; `onForgotten()` cancels the scope
- ScreenRouter just does `remember { AssetBrowserViewModel(...) }` — no `DisposableEffect` needed
- Cleaner ScreenRouter wiring

Recommendation for `AssetDetailViewModel`: use **Pattern B** (`RememberObserver`) to stay consistent with `AssetBrowserViewModel` and reduce boilerplate in `ScreenRouter`. If `close()` is needed for explicit teardown (e.g. cancelling a Coil prefetch), add both.

---

## 6. Coil Image Loading — Already Wired

`rememberSteleKitImageLoader()` (in `ui/components/SteleKitAssetFetcher.kt`) is already used in `GalleryCard`. It handles `file://` paths. For `AssetItemCard` image thumbnails and the `AssetDetailScreen` full-size viewer, reuse this loader directly — no additional setup required.

In grid thumbnails: `AsyncImage(model = asset.filePath, imageLoader = loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().aspectRatio(1f))`

In detail view: `AsyncImage(model = asset.filePath, imageLoader = loader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())`

Fallback for missing path: `if (asset.filePath.isBlank()) Icon(...) else AsyncImage(...)`
