# Architecture Research — SteleKit Assets Viewer

## 1. AssetRepository Interface

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/AssetRepository.kt`

### Existing read methods
| Method | Signature |
|---|---|
| `getAssetByUuid` | `(uuid: AssetUuid): Flow<Either<DomainError, AssetEntry?>>` |
| `getAssets` | `(limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>` |
| `getAssetsByMediaType` | `(mediaType: AssetMediaType, limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>` |
| `searchAssets` | `(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>` |
| `getUnprocessedAssets` | `(limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>` |
| `countUnprocessedAssets` | `(): Either<DomainError, Long>` |
| `countAssets` | `(): Either<DomainError, Long>` |

### Existing write methods (all `@DirectRepositoryWrite`)
`saveAsset`, `updateFilePath`, `updateTags`, `updateAutoLabels`, `updateOcrText`,
`updateCloudDescription`, `markMlProcessed`, `markMlFailed`, `updatePageUuids`, `deleteAsset`

### `@DirectRepositoryWrite` annotation
Located at `repository/DirectRepositoryWrite.kt`. It is `@RequiresOptIn(level = ERROR)` — any
caller that does not have `@OptIn(DirectRepositoryWrite::class)` gets a compile-time error. Both
`SqlDelightAssetRepository` and `InMemoryAssetRepository` carry `@OptIn(DirectRepositoryWrite::class)`
at class level, which is the approved pattern here (matching `SqlDelightImageAnnotationRepository`).
Unlike `@DirectSqlWrite` (which gates DB queries and is strictly actor-only), `@DirectRepositoryWrite`
is the repository-layer gate; callers go through `DatabaseWriteActor` which opts in internally.

### What is missing (must be added for this feature)

| Requirement | Missing method | Notes |
|---|---|---|
| REQ-4 Sort order | `getSortedAssets(mediaType: AssetMediaType?, query: String?, sortOrder: AssetSortOrder, limit: Int, offset: Int)` | Unified query covering ALL/mediaType/search+sort |
| REQ-6 Tag grouping | `getDistinctTags(): Flow<Either<DomainError, List<String>>>` | Tags are stored as JSON — no SQLite DISTINCT possible; must read all `tags` columns and merge in Kotlin, or use SQLite JSON extension |
| REQ-7 Orphaned filter | `getOrphanedAssets(limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>` | Column `is_orphan INTEGER` already exists in schema; query: `SELECT * FROM asset_index WHERE is_orphan = 1 ORDER BY imported_at_ms DESC LIMIT ? OFFSET ?` |

---

## 2. SqlDelightAssetRepository

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightAssetRepository.kt`

### Read pattern used
Every read uses `asDbFlowList` or `asDbFlowOrNull`:
```kotlin
override fun getAssets(limit: Int, offset: Int) =
    queries.selectAssets(limit.toLong(), offset.toLong())
        .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }
```
All `offset` parameters are cast to `Long` before passing to SQLDelight.

### Write pattern used
```kotlin
withContext(PlatformDispatcher.DB) {
    try { queries.xxx(...); Unit.right() }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { DomainError.DatabaseError.WriteFailed(...).left() }
}
```
The class holds `@OptIn(DirectRepositoryWrite::class)` at class level and each write override is
additionally annotated `@DirectRepositoryWrite`.

### Existing SQL queries in SteleDatabase.sq (lines 1059–1113)
| Query name | SQL summary |
|---|---|
| `insertAsset` | INSERT all columns |
| `insertAssetOrIgnore` | INSERT OR IGNORE all columns |
| `selectAssetByUuid` | `SELECT * WHERE uuid = ?` |
| `selectAssets` | `SELECT * ORDER BY imported_at_ms DESC LIMIT ? OFFSET ?` |
| `selectAssetsByMediaType` | `SELECT * WHERE media_type = ? ORDER BY imported_at_ms DESC LIMIT ? OFFSET ?` |
| `searchAssets` | `SELECT * WHERE (file_path/tags/auto_labels/ocr_text LIKE ?) ORDER BY imported_at_ms DESC LIMIT ? OFFSET ?` |
| `selectUnprocessedAssets` | `SELECT * WHERE ml_processed = 0 AND ml_failed = 0 ORDER BY imported_at_ms ASC LIMIT ? OFFSET ?` |
| `countUnprocessedAssets` | `SELECT COUNT(*) WHERE ml_processed = 0 AND ml_failed = 0` |
| `countAssets` | `SELECT COUNT(*)` |
| update/delete variants | 9 targeted UPDATE/DELETE queries |

### Schema: `asset_index` table (line 1033)
All needed columns are present:
- `is_orphan INTEGER NOT NULL DEFAULT 0` — wires REQ-7 with a simple `WHERE is_orphan = 1`
- `tags TEXT NOT NULL DEFAULT '[]'` — JSON array; REQ-6 tag extraction must be done at Kotlin layer
- `imported_at_ms`, `size_bytes`, `file_path` — cover all three REQ-4 sort options

**No schema DDL changes are needed** — all required columns exist. Only new named queries in
`SteleDatabase.sq` are required (no migration entry needed per the CLAUDE.md rule about DDL-only
migrations).

### What SQLDelight queries need to be added

| Query name | SQL |
|---|---|
| `selectAssetsSortedByName` | `SELECT * FROM asset_index ORDER BY file_path ASC LIMIT :limit OFFSET :offset` |
| `selectAssetsSortedBySize` | `SELECT * FROM asset_index ORDER BY size_bytes DESC LIMIT :limit OFFSET :offset` |
| `selectAssetsByMediaTypeSortedByName` | `SELECT * FROM asset_index WHERE media_type = :mediaType ORDER BY file_path ASC LIMIT :limit OFFSET :offset` |
| `selectAssetsByMediaTypeSortedBySize` | `SELECT * FROM asset_index WHERE media_type = :mediaType ORDER BY size_bytes DESC LIMIT :limit OFFSET :offset` |
| `selectOrphanedAssets` | `SELECT * FROM asset_index WHERE is_orphan = 1 ORDER BY imported_at_ms DESC LIMIT :limit OFFSET :offset` |
| `countOrphanedAssets` | `SELECT COUNT(*) FROM asset_index WHERE is_orphan = 1` |
| `selectAllTagsJson` | `SELECT tags FROM asset_index` (returns `Flow<List<String>>` of JSON strings for Kotlin-side merge) |

Note: SQLite's JSON functions (`json_each`) are available on Android API ≥ 30 and SQLite ≥ 3.38.
For safety, the `getDistinctTags()` implementation should read all `tags` JSON columns via
`selectAllTagsJson` and merge+deduplicate in Kotlin, avoiding any `json_each` dependency.

---

## 3. InMemoryAssetRepository

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/InMemoryAssetRepository.kt`

Backed by `MutableStateFlow<Map<String, AssetEntry>>`. Every read method is annotated
`@Suppress("InMemoryPagination")` to document that in-memory pagination is fake (sorts + drops,
not a real bounded query).

Every method on the interface has a corresponding override. **Any new interface method must get a
matching override here** or the project will not compile. Required additions:

| New method | In-memory implementation |
|---|---|
| `getOrphanedAssets(limit, offset)` | `store.map { map -> map.values.filter { it.pageUuids.isEmpty() }.sortedByDescending { it.importedAtMs }.drop(offset).take(limit).right() }` |
| `getDistinctTags()` | `store.map { map -> map.values.flatMap { it.tags }.distinct().sorted().right() }` |
| Sort-aware variant | Add sort enum dispatch wrapping the existing `.sortedByDescending { it.importedAtMs }` logic |

---

## 4. Screen.AssetDetail — AppState.kt

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`

The `Screen` sealed class currently ends at line 82. `Screen.AssetBrowser` (line 68) is the last
asset-related screen. Adding `Screen.AssetDetail` follows the `AnnotationEditor` pattern (line 78):

```kotlin
@HelpExempt(reason = "Entered via asset tap in AssetBrowserScreen; not a primary nav destination")
data class AssetDetail(
    val assetUuid: AssetUuid,
) : Screen()
```

**Required import**: `dev.stapler.stelekit.asset.AssetUuid` — not currently imported in `AppState.kt`.
The file currently imports from `dev.stapler.stelekit.model`, `dev.stapler.stelekit.vault`,
`dev.stapler.stelekit.git`, etc. — asset types are in the `dev.stapler.stelekit.asset` package.

---

## 5. StelekitViewModel — Navigation Telemetry

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

The `navigateTo(screen)` function (around line 930–997):
1. Records `navStart` timestamp
2. Updates `AppState.currentScreen` and sets `statusMessage` via `when(screen)` (lines 950–965)
3. Emits a span via `spanEmitter.emit("navigation", ...)` using `screen::class.simpleName` — this
   auto-handles any new `Screen` subclass with no code change needed in the emit block
4. For `Screen.PageView` only: triggers `graphLoader.loadFullPage` and `searchRepository.recordPageVisit`

**What must be added for `Screen.AssetDetail`**:
- One `when` branch in the `statusMessage` block (line 963 area):
  ```kotlin
  is Screen.AssetDetail -> "Opened Asset: ${screen.assetUuid.value}"
  ```
  Without this branch, the Kotlin `when` expression is exhaustive-checked — the build will fail
  at compile time once `Screen.AssetDetail` is added to the sealed class, forcing the developer to
  add the branch. No other changes are needed in `navigateTo`.

---

## 6. DbFlowExtensions.kt

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DbFlowExtensions.kt`

Three functions, all `internal`:

| Function | Signature | Built-in guard |
|---|---|---|
| `catchDbError()` | `Flow<Either<DomainError, T>>.catchDbError()` | IS the guard — converts closed-DB exceptions to `Left(ReadFailed)` |
| `asDbFlowList(dispatcher, mapper)` | `Query<SqlRow>.asDbFlowList(CoroutineDispatcher, (SqlRow)->Domain)` | Calls `catchDbError()` automatically |
| `asDbFlowOrNull(dispatcher, mapper)` | `Query<SqlRow>.asDbFlowOrNull(CoroutineDispatcher, (SqlRow)->Domain?)` | Calls `catchDbError()` automatically |

**Rule**: All new `SqlDelightAssetRepository` read methods must use `asDbFlowList` or `asDbFlowOrNull`.
Raw `asFlow()` chains are forbidden unless `catchDbError()` is explicitly appended. The functions are
`internal` to the `repository` package — they are accessible to `SqlDelightAssetRepository` directly.

---

## 7. RepositorySet / RepositoryFactory — AssetRepository wiring

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt`

### RepositorySet (line 47–75)
```kotlin
data class RepositorySet(
    ...
    val assetRepository: dev.stapler.stelekit.repository.AssetRepository = InMemoryAssetRepository(),
)
```
Default is `InMemoryAssetRepository()`. For production, `createRepositorySet()` sets:
```kotlin
assetRepository = if (backend == GraphBackend.SQLDELIGHT)
    SqlDelightAssetRepository(database)
else InMemoryAssetRepository(),
```

### How screens access it — ScreenRouter.kt (line 255–263)
```kotlin
is Screen.AssetBrowser -> {
    val assetBrowserViewModel = remember {
        dev.stapler.stelekit.ui.assets.AssetBrowserViewModel(repos.assetRepository)
    }
    ...
}
```
`repos` is the `RepositorySet` passed into the composable. No factory method exists — `repos.assetRepository` is accessed directly.

### AssetDetailViewModel wiring pattern
For `Screen.AssetDetail`, follow the same pattern in `ScreenRouter.kt`:
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
Key: `remember(currentScreen.assetUuid)` — keyed on the UUID so a new ViewModel is created if the
user navigates from one detail to another. `AssetDetailViewModel` must own its own `CoroutineScope`
(same pattern as `AssetBrowserViewModel`) — no `rememberCoroutineScope()`.

---

## Key Findings Summary

1. **Three new interface methods required**: `getOrphanedAssets(limit, offset)`, `getDistinctTags()`,
   and a sort-aware query variant. The `is_orphan` column already exists in the schema. Tags are
   stored as JSON arrays, so `getDistinctTags()` must merge at the Kotlin layer (not SQL DISTINCT).
   Both `InMemoryAssetRepository` and `SqlDelightAssetRepository` must implement all three.

2. **`Screen.AssetDetail(assetUuid: AssetUuid)` insertion**: Requires one import in `AppState.kt`
   (`dev.stapler.stelekit.asset.AssetUuid`), one `@HelpExempt` annotation, and one `when` branch
   in `StelekitViewModel.navigateTo` for `statusMessage`. The span telemetry in `navigateTo` requires
   no change (uses `screen::class.simpleName` dynamically). The `ScreenRouter.kt` needs a new
   `is Screen.AssetDetail ->` branch constructing `AssetDetailViewModel(repos.assetRepository)`.

3. **All new SQLDelight queries are query-only (no DDL)**: No `MigrationRunner.all` entry is needed.
   Required new queries: `selectOrphanedAssets`, `countOrphanedAssets`, sort-variant selects
   (`selectAssets*SortedByName`, `*SortedBySize`), and `selectAllTagsJson`. The `asDbFlowList` /
   `asDbFlowOrNull` / `catchDbError` helpers in `DbFlowExtensions.kt` cover all new read methods
   with closed-DB protection built in.
