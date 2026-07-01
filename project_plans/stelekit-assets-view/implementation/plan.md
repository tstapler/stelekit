# SteleKit Assets Viewer — Implementation Plan

## Overview

8 epics, 22 stories, 68 tasks. All work is confined to the `kmp/` module. No schema DDL
changes — only new named SQLDelight queries. `PlatformFileOpener` is the one new
expect/actual to add. All repository boundaries use Arrow `Either`.

---

## Epic 1 — Image Thumbnails

**Scope**: Replace type-icon placeholders in `AssetItemCard` with real `AsyncImage` for
IMAGE assets. Wire click and long-press gestures.

### Story 1.1 — Fix Coil model path for non-SAF assets

**Context**: `SteleKitAssetMapper` only maps `../assets/` prefix strings. `AssetEntry.filePath`
for non-SAF graphs is a bare POSIX path (`/storage/emulated/0/.../assets/photo.jpg`) that Coil
cannot load without `file://` wrapping.

**Tasks**:

1. **`AssetItemCard.kt`** — add helper val `coilModel` in the card composable scope:
   ```kotlin
   val coilModel = when {
       asset.relativePath.startsWith("../assets/") -> asset.relativePath  // SteleKitAssetMapper handles
       asset.filePath.startsWith("saf://") -> asset.filePath              // SteleKitSafPathMapper handles
       asset.filePath.startsWith("file://") || asset.filePath.startsWith("content://") -> asset.filePath
       else -> "file://${asset.filePath}"
   }
   ```
   Use `coilModel` everywhere `AsyncImage` is called in this file.

2. **`AssetItemCard.kt`** — add `val imageLoader = rememberSteleKitImageLoader()` at the top
   of the card composable, guarded by `LocalGraphRootPath.current != null` (same check as
   `GalleryCard`). Hoist above the `when(viewMode)` branch so it is computed once.

### Story 1.2 — Grid thumbnail

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetItemCard.kt`

**Tasks**:

3. In `AssetGridItem` composable: for `asset.mediaType == AssetMediaType.IMAGE`, replace
   the type-icon `Box` with:
   ```kotlin
   AsyncImage(
       model = coilModel,
       imageLoader = imageLoader,
       contentDescription = asset.filePath.substringAfterLast('/'),
       contentScale = ContentScale.Crop,
       modifier = Modifier.fillMaxSize().aspectRatio(1f),
       error = { Icon(assetIcon(asset.mediaType), contentDescription = null) },
       placeholder = { CircularProgressIndicator(modifier = Modifier.size(24.dp)) },
   )
   ```
   Non-IMAGE types keep the existing icon treatment.

4. Add `coil3.compose.AsyncImage` and `coil3.compose.LocalPlatformContext` imports.

### Story 1.3 — List thumbnail

**Tasks**:

5. In `AssetListItem` composable: for IMAGE assets, replace the leading icon `Box` with a
   48×48 dp `AsyncImage`:
   ```kotlin
   AsyncImage(
       model = coilModel,
       imageLoader = imageLoader,
       contentDescription = null,
       contentScale = ContentScale.Crop,
       modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
       error = { Icon(assetIcon(asset.mediaType), contentDescription = null) },
   )
   ```

### Story 1.4 — Click and long-press gestures

**Tasks**:

6. Add `Modifier.combinedClickable` to the root `Card`/`Box` in `AssetGridItem`:
   ```kotlin
   .combinedClickable(
       onClick = { onTap(asset) },
       onLongClick = { onLongPress?.invoke() },
   )
   ```
   Add `onTap: (AssetEntry) -> Unit` parameter to `AssetGridItem` and thread it through
   `AssetItemCard`.

7. Confirm `AssetListItem` already has `onLongPress` wired; if not, apply the same
   `combinedClickable` pattern.

8. Import `androidx.compose.foundation.combinedClickable`.

---

## Epic 2 — Asset Detail Screen

**Scope**: New `Screen.AssetDetail`, `AssetDetailViewModel`, and `AssetDetailScreen` composable.
Images get a full-screen pinch-to-zoom viewer; other types get a metadata card.

### Story 2.1 — Screen sealed class and navigation telemetry

**Files**: `AppState.kt`, `StelekitViewModel.kt`

**Tasks**:

9. **`AppState.kt`** — add import `dev.stapler.stelekit.asset.AssetUuid`. Add at the end of
   the `Screen` sealed class:
   ```kotlin
   @HelpExempt(reason = "Entered via asset tap in AssetBrowserScreen; not a primary nav destination")
   data class AssetDetail(val assetUuid: AssetUuid) : Screen()
   ```

10. **`StelekitViewModel.kt`** — add `when` branch in the `statusMessage` block inside
    `navigateTo()` (locate the existing `is Screen.AssetBrowser ->` branch and add after it):
    ```kotlin
    is Screen.AssetDetail -> "Opened Asset: ${screen.assetUuid.value}"
    ```
    The `when` is exhaustive on a sealed class — the build will not compile without this.

### Story 2.2 — AssetDetailViewModel

**File** (new): `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetDetailViewModel.kt`

**Tasks**:

11. Create `AssetDetailViewModel` implementing `RememberObserver`:
    ```kotlin
    class AssetDetailViewModel(
        private val assetRepository: AssetRepository,
    ) : RememberObserver {
        private val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> _state.update { it.copy(error = t.message) } }
        )
        private val _state = MutableStateFlow(AssetDetailUiState())
        val state: StateFlow<AssetDetailUiState> = _state.asStateFlow()

        private var loadJob: Job? = null

        fun load(assetUuid: AssetUuid) {
            loadJob?.cancel()
            loadJob = scope.launch {
                assetRepository.getAssetByUuid(assetUuid).collect { either ->
                    either.fold(
                        { err -> _state.update { it.copy(error = err.message) } },
                        { asset -> _state.update { it.copy(asset = asset, isLoading = false) } }
                    )
                }
            }
        }

        override fun onRemembered() {}
        override fun onForgotten() { scope.cancel() }
        override fun onAbandoned() { scope.cancel() }
    }
    ```

12. Create `AssetDetailUiState` data class in same file:
    ```kotlin
    data class AssetDetailUiState(
        val asset: AssetEntry? = null,
        val isLoading: Boolean = true,
        val error: String? = null,
    )
    ```

### Story 2.3 — AssetDetailScreen composable

**File** (new): `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetDetailScreen.kt`

**Tasks**:

13. Scaffold with `TopAppBar`:
    ```kotlin
    @Composable
    fun AssetDetailScreen(
        assetUuid: AssetUuid,
        viewModel: AssetDetailViewModel,
        onNavigateBack: () -> Unit,
        onNavigateToPage: (String) -> Unit,
    )
    ```
    `LaunchedEffect(assetUuid) { viewModel.load(assetUuid) }` at top level.
    Collect `viewModel.state.collectAsState()`.

14. **Image branch**: when `asset.mediaType == AssetMediaType.IMAGE`, render:
    - `AsyncImage` with `rememberSteleKitImageLoader()`, `ContentScale.Fit`,
      `Modifier.fillMaxSize()`
    - Pinch-to-zoom via `rememberTransformableState` + `Modifier.transformable(state)`
    - Scale clamped to `1f..5f`

15. **Non-image branch**: scrollable `Column` with `Card` containing:
    - Filename (`asset.filePath.substringAfterLast('/')`)
    - Size formatted: `formatFileSize(asset.sizeBytes)` — KB < 1 MB, else MB to 1 decimal
    - Media type display name (`asset.mediaType.name`)
    - Import date via `Instant.fromEpochMilliseconds(asset.importedAtMs).toLocalDateTime(...)`
    - Tags as `FilterChip` in a `FlowRow`
    - OCR text block (only if `asset.ocrText != null`)
    - "Open in…" `Button` at bottom — placeholder; wired in Epic 3

16. **"Go to page" chips**: `LazyRow` of `AssistChip` for each `uuid` in `asset.pageUuids`:
    ```kotlin
    AssistChip(
        onClick = { onNavigateToPage(uuid) },
        label = { Text("Page ${ uuid.take(8) }") },
        leadingIcon = { Icon(Icons.Default.Article, contentDescription = null) },
    )
    ```

17. Add private `formatFileSize(bytes: Long): String` helper function in the same file.

### Story 2.4 — ScreenRouter wiring

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ScreenRouter.kt`

**Tasks**:

18. After the `is Screen.AssetBrowser ->` branch, add:
    ```kotlin
    is Screen.AssetDetail -> {
        NavigationTracingEffect("AssetDetail")
        val assetDetailViewModel = remember(currentScreen.assetUuid) {
            AssetDetailViewModel(repos.assetRepository)
        }
        AssetDetailScreen(
            assetUuid = currentScreen.assetUuid,
            viewModel = assetDetailViewModel,
            onNavigateBack = { viewModel.goBack() },
            onNavigateToPage = { pageUuid -> viewModel.navigateToPageByUuid(pageUuid) },
        )
    }
    ```

19. In `AssetBrowserScreen` call site (inside `is Screen.AssetBrowser ->`), ensure `onTapAsset`
    callback is wired: `onTapAsset = { asset -> viewModel.navigateTo(Screen.AssetDetail(asset.uuid)) }`.
    Confirm the `AssetBrowserScreen` composable signature has `onTapAsset: (AssetEntry) -> Unit`.

---

## Epic 3 — Wired Action Menu

**Scope**: Create `PlatformFileOpener`; wire Delete, Copy Link, and Open In actions end-to-end.

### Story 3.1 — PlatformFileOpener expect/actual [ADR-needed]

**Context**: No `PlatformFileOpener` exists. Must create it. Android path needs copy-to-cache
for non-SAF assets because `file_provider_paths.xml` only covers `cache-path/share_export/`.

**Files** (new):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/PlatformFileOpener.kt`
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/PlatformFileOpener.jvm.kt`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileOpener.android.kt`

**Tasks**:

20. **commonMain** — define interface and expect function:
    ```kotlin
    interface PlatformFileOpener {
        suspend fun openFile(absolutePath: String, mimeType: String)
    }

    @Composable
    expect fun rememberPlatformFileOpener(): PlatformFileOpener
    ```

21. **jvmMain** — actual:
    ```kotlin
    @Composable
    actual fun rememberPlatformFileOpener(): PlatformFileOpener = remember {
        object : PlatformFileOpener {
            override suspend fun openFile(absolutePath: String, mimeType: String) =
                withContext(Dispatchers.IO) {
                    if (Desktop.isDesktopSupported() &&
                        Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                        Desktop.getDesktop().open(File(absolutePath))
                    }
                }
        }
    }
    ```

22. **androidMain** — actual with three-way branch (saf:// → content URI via
    `resolveAssetUri`; legacy path with direct access → FileProvider URI; legacy path without
    direct access → copy to `cacheDir/share_export/` then FileProvider URI):
    ```kotlin
    @Composable
    actual fun rememberPlatformFileOpener(): PlatformFileOpener {
        val context = LocalContext.current
        val fileSystem = LocalFileSystem.current
        return remember(context) {
            AndroidPlatformFileOpener(context, fileSystem)
        }
    }
    ```
    `AndroidPlatformFileOpener` is a class in the same file implementing the copy-to-cache
    strategy for non-SAF paths. Authority: `"${context.packageName}.fileprovider"` (already
    registered).

    [ADR-needed]: Whether to widen `file_provider_paths.xml` with `<external-path>` or keep
    the copy-to-cache approach. Copy-to-cache is safer (no wide filesystem exposure) but adds
    latency for large files. Recommend copy-to-cache with a 50 MB file size guard.

22a. **wasmJsMain** — no-op actual (required for `bazel build //kmp:web_app`; Web is a
    non-goal but every `expect fun` must have an `actual` in every compiled platform, including
    wasmJs, or the web build fails with "Expected function 'rememberPlatformFileOpener' has no
    actual declaration in module kmp.wasm"):
    ```kotlin
    // kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileOpener.wasmJs.kt
    @Composable
    actual fun rememberPlatformFileOpener(): PlatformFileOpener = remember {
        object : PlatformFileOpener {
            override suspend fun openFile(absolutePath: String, mimeType: String) { /* no-op */ }
        }
    }
    ```
    This file is marked required for `bazel build //kmp:web_app` to pass.

### Story 3.2 — Wire AssetBrowserViewModel actions

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserViewModel.kt`

**Tasks**:

23. Add `fun deleteAsset(asset: AssetEntry)` method:
    ```kotlin
    fun deleteAsset(asset: AssetEntry) {
        scope.launch {
            // Optimistic remove
            _state.update { it.copy(assets = it.assets.filterNot { a -> a.uuid == asset.uuid }) }
            val result = if (writeActor != null) {
                writeActor.execute {
                    @OptIn(DirectRepositoryWrite::class)
                    assetRepository.deleteAsset(asset.uuid)
                }
            } else {
                @OptIn(DirectRepositoryWrite::class)
                assetRepository.deleteAsset(asset.uuid)
            }
            result.onLeft { err ->
                // Rollback: reload current page
                _state.update { it.copy(error = err.message) }
                loadAssets()
            }
        }
    }
    ```
    Delete file from disk via `fileSystem.deleteFile(asset.filePath)` inside the same
    `scope.launch`, before the repository call. `fileSystem` must be injected into the
    ViewModel (nullable, null in tests) following the existing pattern.

24. Add `fun copyMarkdownLink(asset: AssetEntry, clipboardProvider: ClipboardProvider)`:
    ```kotlin
    fun copyMarkdownLink(asset: AssetEntry, clipboardProvider: ClipboardProvider) {
        val filename = asset.filePath.substringAfterLast('/')
        val link = if (asset.mediaType == AssetMediaType.IMAGE)
            "![$filename](${asset.relativePath})"
        else
            "[$filename](${asset.relativePath})"
        clipboardProvider.writeText(link)
    }
    ```

### Story 3.3 — Wire AssetActionMenu in AssetBrowserScreen

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserScreen.kt`

**Tasks**:

25. Add state:
    ```kotlin
    var actionMenuAsset by remember { mutableStateOf<AssetEntry?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    ```

26. Call `AssetActionMenu` conditionally:
    ```kotlin
    actionMenuAsset?.let { asset ->
        AssetActionMenu(
            expanded = true,
            onDismiss = { actionMenuAsset = null },
            onAction = { action ->
                actionMenuAsset = null
                when (action) {
                    AssetAction.OPEN_IN -> scope.launch { fileOpener.openFile(asset.filePath, asset.mediaType.mimeType) }
                    AssetAction.COPY_LINK -> assetBrowserViewModel.copyMarkdownLink(asset, clipboardProvider)
                    AssetAction.DELETE -> {
                        pendingDeleteAsset = asset
                        showDeleteConfirm = true
                    }
                }
            }
        )
    }
    ```

27. Add delete confirmation `AlertDialog`:
    ```kotlin
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete asset?") },
            text = { Text("This will remove the file from disk and all page references.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    pendingDeleteAsset?.let { assetBrowserViewModel.deleteAsset(it) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
    ```

28. Obtain `fileOpener = rememberPlatformFileOpener()` and
    `clipboardProvider = rememberClipboardProvider(LocalClipboardManager.current)` at
    composable top level. Pass `onLongPress = { actionMenuAsset = asset }` into each
    `AssetItemCard` call.

---

## Epic 4 — Sort Order

**Scope**: Sort enum, new SQLDelight queries per sort, dropdown UI in `AssetBrowserScreen`.

### Story 4.1 — SQLDelight sort queries

**File**: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`

**Tasks**:

29. Add after existing asset queries:
    ```sql
    selectAssetsSortedByName:
    SELECT * FROM asset_index ORDER BY file_path COLLATE NOCASE ASC LIMIT :limit OFFSET :offset;

    selectAssetsSortedBySize:
    SELECT * FROM asset_index ORDER BY size_bytes DESC LIMIT :limit OFFSET :offset;

    selectAssetsByMediaTypeSortedByName:
    SELECT * FROM asset_index WHERE media_type = :mediaType ORDER BY file_path COLLATE NOCASE ASC LIMIT :limit OFFSET :offset;

    selectAssetsByMediaTypeSortedBySize:
    SELECT * FROM asset_index WHERE media_type = :mediaType ORDER BY size_bytes DESC LIMIT :limit OFFSET :offset;

    searchAssetsSortedByName:
    SELECT * FROM asset_index
    WHERE (file_path LIKE :query OR tags LIKE :query OR auto_labels LIKE :query OR ocr_text LIKE :query)
    ORDER BY file_path COLLATE NOCASE ASC LIMIT :limit OFFSET :offset;

    searchAssetsSortedBySize:
    SELECT * FROM asset_index
    WHERE (file_path LIKE :query OR tags LIKE :query OR auto_labels LIKE :query OR ocr_text LIKE :query)
    ORDER BY size_bytes DESC LIMIT :limit OFFSET :offset;
    ```
    No migration entry needed (SELECT-only, no DDL).

### Story 4.2 — AssetSortOrder enum + UiState fields

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserUiState.kt`

**Tasks**:

30. Add:
    ```kotlin
    enum class AssetSortOrder { BY_DATE_ADDED, BY_NAME, BY_SIZE }
    ```

31. Add `sortOrder: AssetSortOrder = AssetSortOrder.BY_DATE_ADDED` to `AssetBrowserUiState`.

### Story 4.3 — Repository sort overloads

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/AssetRepository.kt`

**Tasks**:

32. Add to `AssetRepository` interface:
    ```kotlin
    fun getSortedAssets(
        mediaType: AssetMediaType?,
        query: String?,
        sortOrder: AssetSortOrder,
        limit: Int,
        offset: Int,
    ): Flow<Either<DomainError, List<AssetEntry>>>
    ```

33. **`SqlDelightAssetRepository.kt`** — implement by dispatching to the right named query:
    ```kotlin
    override fun getSortedAssets(
        mediaType: AssetMediaType?,
        query: String?,
        sortOrder: AssetSortOrder,
        limit: Int,
        offset: Int,
    ): Flow<Either<DomainError, List<AssetEntry>>> {
        val q = if (query.isNullOrBlank()) null else "%$query%"
        return when {
            q != null -> when (sortOrder) {
                BY_DATE_ADDED -> queries.searchAssets(q, limit.toLong(), offset.toLong())
                BY_NAME       -> queries.searchAssetsSortedByName(q, limit.toLong(), offset.toLong())
                BY_SIZE       -> queries.searchAssetsSortedBySize(q, limit.toLong(), offset.toLong())
            }
            mediaType != null -> when (sortOrder) {
                BY_DATE_ADDED -> queries.selectAssetsByMediaType(mediaType.name, limit.toLong(), offset.toLong())
                BY_NAME       -> queries.selectAssetsByMediaTypeSortedByName(mediaType.name, limit.toLong(), offset.toLong())
                BY_SIZE       -> queries.selectAssetsByMediaTypeSortedBySize(mediaType.name, limit.toLong(), offset.toLong())
            }
            else -> when (sortOrder) {
                BY_DATE_ADDED -> queries.selectAssets(limit.toLong(), offset.toLong())
                BY_NAME       -> queries.selectAssetsSortedByName(limit.toLong(), offset.toLong())
                BY_SIZE       -> queries.selectAssetsSortedBySize(limit.toLong(), offset.toLong())
            }
        }.asDbFlowList(PlatformDispatcher.DB) { it.toModel() }
    }
    ```

34. **`InMemoryAssetRepository.kt`** — implement with in-memory sort:
    ```kotlin
    override fun getSortedAssets(mediaType, query, sortOrder, limit, offset) = store.map { map ->
        var list = map.values.toList()
        if (mediaType != null) list = list.filter { it.mediaType == mediaType }
        if (!query.isNullOrBlank()) list = list.filter { it.filePath.contains(query, ignoreCase = true) }
        list = when (sortOrder) {
            BY_DATE_ADDED -> list.sortedByDescending { it.importedAtMs }
            BY_NAME       -> list.sortedBy { it.filePath.lowercase() }
            BY_SIZE       -> list.sortedByDescending { it.sizeBytes }
        }
        list.drop(offset).take(limit).right()
    }
    ```

### Story 4.4 — ViewModel + UI wiring

**Tasks**:

35. **`AssetBrowserViewModel.kt`** — add `fun setSortOrder(order: AssetSortOrder)`:
    ```kotlin
    fun setSortOrder(order: AssetSortOrder) {
        _state.update { it.copy(sortOrder = order) }
        loadAssets(reset = true)
    }
    ```
    Update `loadAssets()` to call `getSortedAssets(...)` instead of the type-specific methods,
    passing `uiState.sortOrder`.

36. **`AssetBrowserScreen.kt`** — add `SortDropdownButton` composable (private) following the
    `GalleryScreen` pattern: `IconButton(Icons.AutoMirrored.Filled.Sort)` → `DropdownMenu` with
    `AssetSortOrder.entries` as `DropdownMenuItem`, `RadioButton` leading icon for current
    selection. Place in `TopAppBar` actions row next to the grid/list toggle.

---

## Epic 5 — Keyset Pagination

**Scope**: Replace `OFFSET`-based pagination with cursor-based (`WHERE imported_at_ms < :cursor`)
to prevent drift under background ML writes. Add infinite scroll trigger.

**ADR-001 alignment**: Keyset pagination is used for **all** sort orders per ADR-001. BY_DATE_ADDED
uses a single `imported_at_ms` cursor. BY_NAME and BY_SIZE use compound `(sortKey, uuid)` cursors
(see Stories 5.2 and 5.3). OFFSET is not used anywhere in the pagination implementation. Passing
`cursor = null` in the relevant cursor field means "start from the top" — the initial page load
calls `getAssetPage` with all cursor fields null, so keyset applies from page 1 onward.

### Story 5.1 — SQLDelight keyset queries

**File**: `SteleDatabase.sq`

**Tasks**:

37. Add keyset variants for all sort orders. Date-sorted paths use a single `imported_at_ms`
    cursor; name- and size-sorted paths use a compound `(sortKey, uuid)` cursor per ADR-001:
    ```sql
    -- BY_DATE_ADDED keyset queries (single cursor)
    selectAssetsKeyset:
    SELECT * FROM asset_index
    WHERE imported_at_ms < :cursorMs OR :cursorMs IS NULL
    ORDER BY imported_at_ms DESC LIMIT :limit;

    selectAssetsByMediaTypeKeyset:
    SELECT * FROM asset_index
    WHERE media_type = :mediaType AND (imported_at_ms < :cursorMs OR :cursorMs IS NULL)
    ORDER BY imported_at_ms DESC LIMIT :limit;

    selectOrphanedAssetsKeyset:
    SELECT * FROM asset_index
    WHERE is_orphan = 1 AND (imported_at_ms < :cursorMs OR :cursorMs IS NULL)
    ORDER BY imported_at_ms DESC LIMIT :limit;

    -- BY_NAME keyset queries — compound (file_path, uuid) cursor
    -- :cursorName IS NULL means first page (no predicate applied)
    selectAssetsByNameKeyset:
    SELECT * FROM asset_index
    WHERE :cursorName IS NULL OR (file_path, uuid) > (:cursorName, :cursorUuid)
    ORDER BY file_path COLLATE NOCASE ASC, uuid ASC LIMIT :limit;

    selectAssetsByMediaTypeByNameKeyset:
    SELECT * FROM asset_index
    WHERE media_type = :mediaType
      AND (:cursorName IS NULL OR (file_path, uuid) > (:cursorName, :cursorUuid))
    ORDER BY file_path COLLATE NOCASE ASC, uuid ASC LIMIT :limit;

    -- BY_SIZE keyset queries — compound (size_bytes, uuid) cursor (both DESC for tie-break)
    -- :cursorSize IS NULL means first page (no predicate applied)
    selectAssetsBySizeKeyset:
    SELECT * FROM asset_index
    WHERE :cursorSize IS NULL OR (size_bytes, uuid) < (:cursorSize, :cursorUuid)
    ORDER BY size_bytes DESC, uuid DESC LIMIT :limit;

    selectAssetsByMediaTypeBySizeKeyset:
    SELECT * FROM asset_index
    WHERE media_type = :mediaType
      AND (:cursorSize IS NULL OR (size_bytes, uuid) < (:cursorSize, :cursorUuid))
    ORDER BY size_bytes DESC, uuid DESC LIMIT :limit;
    ```
    No migration entry needed (SELECT-only, no DDL).

### Story 5.2 — UiState pagination fields

**File**: `AssetBrowserUiState.kt`

**Tasks**:

38. Add to `AssetBrowserUiState`:
    ```kotlin
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val nextCursorMs: Long? = null,      // BY_DATE_ADDED cursor; null = first page (start from top)
    val cursorSortKey: String? = null,   // BY_NAME cursor (last file_path); BY_SIZE cursor (last size_bytes.toString()); null = first page
    val cursorUuid: String? = null,      // Secondary uuid tie-break for BY_NAME and BY_SIZE; null = first page
    ```
    `cursor = null` in any cursor field means "start from the top" for that sort dimension.
    All three cursor fields are reset to `null` when `loadAssets(reset = true)` is called (e.g.,
    on sort order change or filter change).

### Story 5.3 — Repository keyset method

**File**: `AssetRepository.kt`

**Tasks**:

39. Add to interface:
    ```kotlin
    fun getAssetPage(
        mediaType: AssetMediaType?,
        sortOrder: AssetSortOrder,
        cursorMs: Long?,        // BY_DATE_ADDED cursor; null = first page
        cursorSortKey: String?, // BY_NAME: last file_path; BY_SIZE: last size_bytes.toString(); null = first page
        cursorUuid: String?,    // Secondary uuid tie-break for BY_NAME and BY_SIZE; null = first page
        limit: Int,
    ): Flow<Either<DomainError, List<AssetEntry>>>
    ```
    All sort orders use keyset pagination — OFFSET is never used. Passing `null` for the
    relevant cursor field means "start from the top"; the SQLDelight queries handle this via
    `:cursorX IS NULL` sentinels so no separate initial-load code path is needed.

    Keyset dispatch in `SqlDelightAssetRepository`:
    - `BY_DATE_ADDED` → `selectAssetsKeyset` / `selectAssetsByMediaTypeKeyset` (cursorMs)
    - `BY_NAME` → `selectAssetsByNameKeyset` / `selectAssetsByMediaTypeByNameKeyset`
      (compound cursorSortKey, cursorUuid; update `cursorSortKey` to `page.last().filePath`
      and `cursorUuid` to `page.last().uuid.value` after each page)
    - `BY_SIZE` → `selectAssetsBySizeKeyset` / `selectAssetsByMediaTypeBySizeKeyset`
      (compound cursorSortKey = size as string, cursorUuid; update after each page similarly)

    `loadAssets()` in the ViewModel calls `getAssetPage` with all cursor fields null for the
    initial load — keyset applies from page 1 onward with no special first-page handling.

40. Implement in `SqlDelightAssetRepository` dispatching to the keyset queries added in 5.1.
    Implement in `InMemoryAssetRepository` with `@Suppress("InMemoryPagination")` (existing
    suppress pattern for fake in-memory pagination).

### Story 5.4 — ViewModel loadMore

**File**: `AssetBrowserViewModel.kt`

**Tasks**:

41. Add `fun loadMore()`:
    ```kotlin
    fun loadMore() {
        val s = _state.value
        if (s.isLoadingMore || !s.hasMore) return
        _state.update { it.copy(isLoadingMore = true) }
        scope.launch {
            assetRepository.getAssetPage(
                mediaType = s.selectedFilter.toMediaType(),
                sortOrder = s.sortOrder,
                cursorMs = s.nextCursorMs,
                limit = PAGE_SIZE,
            ).first().fold(
                { err -> _state.update { it.copy(isLoadingMore = false, error = err.message) } },
                { page ->
                    _state.update { state ->
                        state.copy(
                            assets = state.assets + page,
                            isLoadingMore = false,
                            hasMore = page.size == PAGE_SIZE,
                            nextCursorMs = page.lastOrNull()?.importedAtMs,
                        )
                    }
                }
            )
        }
    }
    ```
    `PAGE_SIZE = 50` companion const (already exists as hard-coded 50; extract to const).

### Story 5.5 — Scroll listener + loading footer in AssetBrowserScreen

**File**: `AssetBrowserScreen.kt`

**Tasks**:

42. In `LazyVerticalGrid` and `LazyColumn`, detect near-end and call `loadMore()`:
    ```kotlin
    val listState = rememberLazyGridState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect { layout ->
                val total = layout.totalItemsCount
                val visible = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (total > 0 && visible >= total - 5) {
                    assetBrowserViewModel.loadMore()
                }
            }
    }
    ```

43. Add a loading footer item at the end of the `LazyVerticalGrid`/`LazyColumn`:
    ```kotlin
    if (uiState.isLoadingMore) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
    }
    ```

---

## Epic 6 — Tag Grouping

**Scope**: `AssetFilter.TAG(name)` data class variant; `getDistinctTags()` repository method;
dynamic chip row in `AssetBrowserScreen`.

### Story 6.1 — AssetFilter.TAG data class

**File**: `AssetBrowserUiState.kt`

**Tasks**:

44. Change `AssetFilter` from a plain `enum class` to a sealed class. `FILES` must be preserved
    from the existing enum — do not drop it. Add a companion `all` list to replace `.entries`:
    ```kotlin
    sealed class AssetFilter {
        object ALL : AssetFilter()
        object IMAGES : AssetFilter()
        object PDFS : AssetFilter()
        object AUDIO : AssetFilter()
        object VIDEO : AssetFilter()
        object DOCUMENTS : AssetFilter()
        object FILES : AssetFilter()
        object ORPHANED : AssetFilter()
        data class TAG(val name: String) : AssetFilter()

        companion object {
            /** Ordered list of all static variants for UI chip rows.
             *  Replaces `AssetFilter.entries` (enum API; unavailable on sealed classes). */
            val all: List<AssetFilter> = listOf(ALL, IMAGES, PDFS, AUDIO, VIDEO, DOCUMENTS, FILES, ORPHANED)
        }
    }
    ```

    **Required call-site updates (beyond exhaustive `when` expressions)**:

    44a. **`ScrollableFilterChipRow` in `AssetBrowserScreen.kt`** — `.entries` is enum-only and
    will not compile after migration. Replace:
    ```kotlin
    val filters = AssetFilter.entries   // compile error after sealed migration
    ```
    with:
    ```kotlin
    val filters = AssetFilter.all
    ```

    44b. **`ScrollableFilterChipRow` chip label** — `.name` is an enum-only property and will not
    compile after migration. Add the following extension to `AssetBrowserUiState.kt`:
    ```kotlin
    fun AssetFilter.displayName(): String = when (this) {
        AssetFilter.ALL       -> "All"
        AssetFilter.IMAGES    -> "Images"
        AssetFilter.PDFS      -> "PDFs"
        AssetFilter.AUDIO     -> "Audio"
        AssetFilter.VIDEO     -> "Video"
        AssetFilter.DOCUMENTS -> "Documents"
        AssetFilter.FILES     -> "Files"
        AssetFilter.ORPHANED  -> "Orphaned"
        is AssetFilter.TAG    -> name
    }
    ```
    Then update the chip label from `Text(filter.name.lowercase()...)` to `Text(filter.displayName())`.
    This `when` is exhaustive — the compiler enforces complete coverage of all sealed variants.

    44c. **`AssetBrowserViewModel.loadAssets()`** — the existing `when { state.selectedFilter == ... }`
    uses a boolean subject and will NOT fail to compile on sealed migration; it must be audited
    manually. Add an explicit branch for `AssetFilter.FILES`:
    ```kotlin
    state.selectedFilter == AssetFilter.FILES ->
        assetRepository.getAssetsByMediaType(AssetMediaType.FILE, limit, offset)
    ```
    Do not let `FILES` fall through to the `else` branch (which returns all assets). Any unhandled
    sealed variant in the `else` branch should at minimum log a warning in tests.

    Update all remaining `when(selectedFilter)` call sites — `AssetBrowserViewModel`,
    `AssetBrowserScreen`, `AssetBrowserUiState` — to handle the full sealed hierarchy including
    `FILES` and `is TAG`.

    [ADR-needed]: Migrating `AssetFilter` from `enum` to `sealed class` breaks any serialization
    (e.g., `rememberSaveable`) of the filter. Confirm whether `selectedFilter` is currently in any
    serialized/saved state. If so, add a custom `Saver`.

45. Add `availableTags: List<String> = emptyList()` to `AssetBrowserUiState`.

### Story 6.2 — getDistinctTags repository method

**File**: `SteleDatabase.sq`

**Tasks**:

46. Add:
    ```sql
    selectAllTagsJson:
    SELECT tags FROM asset_index;
    ```

**File**: `AssetRepository.kt`

47. Add to interface:
    ```kotlin
    fun getDistinctTags(): Flow<Either<DomainError, List<String>>>
    ```

**File**: `SqlDelightAssetRepository.kt`

48. Implement:
    ```kotlin
    override fun getDistinctTags(): Flow<Either<DomainError, List<String>>> =
        queries.selectAllTagsJson()
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { jsonList ->
                jsonList
                    .flatMap { json -> Json.decodeFromString<List<String>>(json) }
                    .distinct()
                    .sorted()
                    .right()
            }
            .catchDbError()
    ```
    Import `kotlinx.serialization.json.Json`. The `tags` column stores `'[]'`-format JSON arrays.

**File**: `InMemoryAssetRepository.kt`

49. Add:
    ```kotlin
    override fun getDistinctTags() = store.map { map ->
        map.values.flatMap { it.tags }.distinct().sorted().right()
    }
    ```

### Story 6.3 — ViewModel tag loading + filter

**File**: `AssetBrowserViewModel.kt`

**Tasks**:

50. On init, collect `assetRepository.getDistinctTags()` and update `availableTags` in state.

51. Add `fun selectTag(name: String?)` (null = deselect all tags):
    ```kotlin
    fun selectTag(name: String?) {
        _state.update { it.copy(
            selectedFilter = if (name != null) AssetFilter.TAG(name) else AssetFilter.ALL
        ) }
        loadAssets(reset = true)
    }
    ```

52. In `loadAssets()`, add a branch for `AssetFilter.TAG(name)`: filter results client-side
    (since the repository returns assets and tags are stored as JSON, filtering at Kotlin layer
    is simplest for the first pass — call `getSortedAssets(mediaType = null, query = null, ...)`
    then filter by `asset.tags.contains(name)` before updating state).

    [ADR-needed]: Tag filtering at Kotlin layer vs. SQLite layer. Kotlin-layer filtering
    requires loading all pages to count results; SQLite `LIKE` on the JSON text column works
    for simple tag names but is not exact (a tag "foo" would match "foobar"). For v1, Kotlin
    filtering is acceptable given the 10-tag display cap.

### Story 6.4 — Tag chip row UI

**File**: `AssetBrowserScreen.kt`

**Tasks**:

53. Extend `ScrollableFilterChipRow` (or replace with `LazyRow`) to append tag chips after
    the existing type filter chips. Show up to 10 tags:
    ```kotlin
    val visibleTags = uiState.availableTags.take(10)
    val hiddenCount = uiState.availableTags.size - visibleTags.size
    visibleTags.forEach { tag ->
        FilterChip(
            selected = uiState.selectedFilter == AssetFilter.TAG(tag),
            onClick = { assetBrowserViewModel.selectTag(
                if (uiState.selectedFilter == AssetFilter.TAG(tag)) null else tag
            ) },
            label = { Text(tag) },
        )
    }
    if (hiddenCount > 0) {
        var expanded by remember { mutableStateOf(false) }
        FilterChip(
            selected = false,
            onClick = { expanded = !expanded },
            label = { Text("+$hiddenCount more") },
        )
        // When expanded, show remaining tags (use DropdownMenu or re-render all)
    }
    ```

---

## Epic 7 — Orphaned Assets Filter

**Scope**: Wire `AssetFilter.ORPHANED` → `getOrphanedAssets()` repository method.

### Story 7.1 — SQLDelight orphaned queries

**File**: `SteleDatabase.sq`

**Tasks**:

54. Add:
    ```sql
    selectOrphanedAssets:
    SELECT * FROM asset_index WHERE is_orphan = 1 ORDER BY imported_at_ms DESC LIMIT :limit OFFSET :offset;

    countOrphanedAssets:
    SELECT COUNT(*) FROM asset_index WHERE is_orphan = 1;
    ```

### Story 7.2 — Repository method

**File**: `AssetRepository.kt`

**Tasks**:

55. Add to interface:
    ```kotlin
    fun getOrphanedAssets(limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>
    suspend fun countOrphanedAssets(): Either<DomainError, Long>
    ```

**File**: `SqlDelightAssetRepository.kt`

56. Implement:
    ```kotlin
    override fun getOrphanedAssets(limit: Int, offset: Int) =
        queries.selectOrphanedAssets(limit.toLong(), offset.toLong())
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override suspend fun countOrphanedAssets(): Either<DomainError, Long> =
        withContext(PlatformDispatcher.DB) {
            try { queries.countOrphanedAssets().executeAsOne().right() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left() }
        }
    ```

**File**: `InMemoryAssetRepository.kt`

57. Implement:
    ```kotlin
    override fun getOrphanedAssets(limit: Int, offset: Int) = store.map { map ->
        map.values.filter { it.pageUuids.isEmpty() }
            .sortedByDescending { it.importedAtMs }
            .drop(offset).take(limit).right()
    }

    override suspend fun countOrphanedAssets() = store.value.values
        .count { it.pageUuids.isEmpty() }.toLong().right()
    ```

### Story 7.3 — ViewModel ORPHANED branch

**File**: `AssetBrowserViewModel.kt`

**Tasks**:

58. In `loadAssets()`, add branch for `AssetFilter.ORPHANED`:
    ```kotlin
    AssetFilter.ORPHANED -> assetRepository.getOrphanedAssets(limit, offset)
    ```
    Use `countOrphanedAssets()` for `hasMore` calculation (same pattern as `countAssets()`).

---

## Epic 8 — Empty State

**Scope**: New `AssetBrowserEmptyState` composable shown when `assets.isEmpty && !isLoading`.

### Story 8.1 — AssetBrowserEmptyState composable

**File** (new): `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserEmptyState.kt`

**Tasks**:

59. Create composable following `GalleryEmptyState` layout:
    ```kotlin
    @Composable
    fun AssetBrowserEmptyState(filter: AssetFilter, modifier: Modifier = Modifier) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = emptyStateIcon(filter),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                )
                Text(emptyStateHeadline(filter), style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(emptyStateBody(filter), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
            }
        }
    }
    ```

60. Add private helpers:
    ```kotlin
    private fun emptyStateIcon(filter: AssetFilter) = when (filter) {
        AssetFilter.ALL       -> Icons.Default.Attachment
        AssetFilter.IMAGES    -> Icons.Default.Image
        AssetFilter.PDFS      -> Icons.Default.PictureAsPdf
        AssetFilter.AUDIO     -> Icons.Default.AudioFile
        AssetFilter.VIDEO     -> Icons.Default.VideoFile
        AssetFilter.DOCUMENTS -> Icons.Default.Description
        AssetFilter.FILES     -> Icons.Default.InsertDriveFile
        AssetFilter.ORPHANED  -> Icons.Default.LinkOff
        is AssetFilter.TAG    -> Icons.Default.Label
    }

    private fun emptyStateHeadline(filter: AssetFilter) = when (filter) {
        AssetFilter.ALL       -> "No assets yet"
        AssetFilter.IMAGES    -> "No images found"
        AssetFilter.PDFS      -> "No PDFs found"
        AssetFilter.AUDIO     -> "No audio files found"
        AssetFilter.VIDEO     -> "No video files found"
        AssetFilter.DOCUMENTS -> "No documents found"
        AssetFilter.FILES     -> "No files found"
        AssetFilter.ORPHANED  -> "No orphaned assets"
        is AssetFilter.TAG    -> "No assets tagged \"${filter.name}\""
    }

    private fun emptyStateBody(filter: AssetFilter) = when (filter) {
        AssetFilter.ALL      -> "Attach a file to any page to see it here"
        AssetFilter.ORPHANED -> "All assets are referenced by at least one page"
        else                 -> "Try a different filter or search term"
    }
    ```

### Story 8.2 — Wire into AssetBrowserScreen

**File**: `AssetBrowserScreen.kt`

**Tasks**:

61. Replace the current (nonexistent) empty-state placeholder with:
    ```kotlin
    if (uiState.assets.isEmpty() && !uiState.isLoading) {
        AssetBrowserEmptyState(filter = uiState.selectedFilter)
    } else {
        // existing grid/list
    }
    ```

---

## Cross-cutting Tasks

62. **`AssetMediaType.mimeType` extension** — add to `AssetMediaType` (or a companion object)
    a `val mimeType: String` property mapping each type to a MIME string (e.g., `IMAGE → "image/*"`,
    `PDFS → "application/pdf"`, etc.). Used by `PlatformFileOpener`.

63. **`AssetFilter.toMediaType()` extension** — add extension function mapping `AssetFilter`
    sealed variants to `AssetMediaType?` (null for ALL/ORPHANED/TAG):
    ```kotlin
    fun AssetFilter.toMediaType(): AssetMediaType? = when (this) {
        AssetFilter.IMAGES    -> AssetMediaType.IMAGE
        AssetFilter.PDFS      -> AssetMediaType.PDF
        AssetFilter.AUDIO     -> AssetMediaType.AUDIO
        AssetFilter.VIDEO     -> AssetMediaType.VIDEO
        AssetFilter.DOCUMENTS -> AssetMediaType.DOCUMENT
        AssetFilter.FILES     -> AssetMediaType.FILE
        else                  -> null  // ALL, ORPHANED, TAG — handled by dedicated branches in loadAssets()
    }
    ```

64. **`AssetBrowserScreen` `onTapAsset` parameter** — confirm `AssetBrowserScreen` composable
    already has `onTapAsset: (AssetEntry) -> Unit` in its signature; add it if missing. Thread
    it from `ScreenRouter.kt` to each `AssetItemCard` call.

65. **`AssetDetailViewModel` implements `RememberObserver`** — confirm `RememberObserver` is
    imported from `androidx.compose.runtime`. Use Pattern B (no `DisposableEffect` in
    `ScreenRouter`); `onForgotten` cancels the scope.

66. **Test coverage** — for each new repository method, add a corresponding test in
    `InMemoryAssetRepository` tests (or `businessTest` scope) verifying:
    - `getOrphanedAssets` returns only entries where `pageUuids.isEmpty()`
    - `getDistinctTags` returns sorted distinct list
    - `getSortedAssets` returns correct ordering for each `AssetSortOrder`
    - `deleteAsset` via writeActor removes the entry

67. **`file_provider_paths.xml` update** (Android) — add comment block explaining that
    non-SAF assets are copy-to-cache before sharing; no path entry change needed if
    copy-to-cache strategy is chosen in ADR-1. If `<external-path>` strategy is chosen,
    add `<external-path name="assets" path="." />`.

68. **`MigrationRunner` audit** — confirm no new `CREATE TABLE` was added; run
    `MigrationRunnerSchemaSyncTest` in CI. Expected: green with no changes.

---

## Flagged ADR Decisions

| ID | Decision | Options | Recommendation |
|----|----------|---------|----------------|
| ADR-1 | `PlatformFileOpener` Android strategy for non-SAF legacy paths | (a) copy-to-cache (`cacheDir/share_export/`) before FileProvider; (b) add `<external-path>` to `file_provider_paths.xml` | (a) copy-to-cache — avoids exposing full external storage tree; cap at 50 MB |
| ADR-2 | `AssetFilter` migration from `enum` to `sealed class` | (a) sealed class with `object` singletons (breaks `when` exhaustiveness for non-sealed callers); (b) keep `enum` and add `TAG` as a separate filter field in UiState | (a) sealed class is the correct Kotlin representation; audit all serialized state |
| ADR-3 | Pagination strategy for concurrent writes | (a) keyset for all sorts with compound `(sortKey, uuid)` cursor for BY_NAME/BY_SIZE (ADR-001); (b) keyset for BY_DATE_ADDED only + OFFSET for other sorts | (a) compound keyset for all sorts per ADR-001 — eliminates insert drift under ML writes on all sort orders; `cursor = null` is the sentinel for the first page |
| ADR-4 | Tag filtering strategy | (a) Kotlin-layer filtering after full page load; (b) SQLite `LIKE '%"tagname"%'` on `tags` column; (c) separate `asset_tags` junction table (new DDL, future) | (a) Kotlin-layer for v1 — exact match, no SQL JSON extension dependency, acceptable given 10-tag cap |

---

## File Change Summary

| File | Change type |
|------|-------------|
| `ui/assets/AssetItemCard.kt` | Modify — add `AsyncImage`, `combinedClickable`, `coilModel` |
| `ui/assets/AssetBrowserScreen.kt` | Modify — sort dropdown, tag chips, action menu, empty state, pagination scroll |
| `ui/assets/AssetBrowserViewModel.kt` | Modify — `setSortOrder`, `loadMore`, `selectTag`, `deleteAsset`, `copyMarkdownLink` |
| `ui/assets/AssetBrowserUiState.kt` | Modify — `AssetSortOrder`, `AssetFilter` → sealed class, pagination fields, `availableTags` |
| `ui/assets/AssetDetailScreen.kt` | New |
| `ui/assets/AssetDetailViewModel.kt` | New |
| `ui/assets/AssetBrowserEmptyState.kt` | New |
| `ui/AppState.kt` | Modify — add `Screen.AssetDetail` |
| `ui/ScreenRouter.kt` | Modify — add `is Screen.AssetDetail ->` branch, wire `onTapAsset` |
| `ui/StelekitViewModel.kt` | Modify — add `Screen.AssetDetail` status message branch |
| `platform/PlatformFileOpener.kt` | New (commonMain expect) |
| `platform/PlatformFileOpener.jvm.kt` | New (jvmMain actual) |
| `platform/PlatformFileOpener.android.kt` | New (androidMain actual) |
| `platform/PlatformFileOpener.wasmJs.kt` | New (wasmJsMain no-op actual — required for `bazel build //kmp:web_app`) |
| `repository/AssetRepository.kt` | Modify — add `getSortedAssets`, `getDistinctTags`, `getOrphanedAssets`, `countOrphanedAssets` |
| `repository/SqlDelightAssetRepository.kt` | Modify — implement all new interface methods |
| `repository/InMemoryAssetRepository.kt` | Modify — implement all new interface methods |
| `SteleDatabase.sq` | Modify — 11 new named queries; no DDL |
| `androidApp/.../file_provider_paths.xml` | Possibly modify (pending ADR-1) |
