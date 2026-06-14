# Implementation Plan: asset-management

**Feature**: Asset organization, browser, ML tagging, and cloud enrichment for SteleKit graphs
**Date**: 2026-06-13
**Status**: Ready for implementation
**ADRs**: None

---

## Technology Validation Notes

| Choice | Verdict |
|---|---|
| ONNX Runtime (jvm + android) | OK — two artifacts, NOT commonMain. File-path loading API required on Android to avoid OOM. |
| ML Kit (android only) | OK — must stay behind service interface; never in commonMain. |
| PDFBox-Android (`com.tom-roush`) | ACCEPTABLE for sprint. Community-maintained, Apache 2.0, lags upstream. API 26 desugaring required. |
| iText 7 / MuPDF | BLOCKED — AGPL-3.0, do not use. |
| TFLite/LiteRT JVM | BLOCKED — poor JVM desktop support. Use ONNX Runtime instead. |
| Cloud API cost risk | MITIGATE — cap 20 images/session default. Explicit opt-in per graph. Idempotency guard via `ml_tags_source`. |
| Arrow Saga | OK — use for AtomicMove+refUpdate. Must combine with WAL table to survive process crash. |
| Okio FileSystem | OK — already on classpath transitively; add explicit declaration to commonMain. |

---

## Dependency Visualization

```
Phase 1
  Epic 1.1 Schema + Model
    1.1.1 SQLDelight asset_index table
    1.1.2 pending_asset_moves WAL table + MigrationRunner entries
    1.1.3 AssetEntry model + AssetMediaType + AssetUuid
    1.1.4 AssetRepository interface + InMemory impl
          │
          ▼
  Epic 1.2 Typed Routing
    1.2.1 AssetStoragePathResolver (extends ImageStoragePathResolver pattern)
    1.2.2 MimeTypeDetector (magic bytes + extension fallback)
    1.2.3 MediaAttachmentService update for subfolder routing
          │
          ▼
  Epic 1.3 Index + Move Service
    1.3.1 SqlDelightAssetRepository (bounded reads, Arrow Either)
    1.3.2 AssetIndexService (register + backfill)
    1.3.3 AssetMoveService (Arrow Saga + WAL replay)
    1.3.4 GraphLoader backfill hook
          │
          ▼
  Epic 1.4 Asset Browser UI
    1.4.1 AssetBrowserViewModel (StateFlow, no rememberCoroutineScope)
    1.4.2 AssetBrowserScreen (grid + list, filter chips)
    1.4.3 AssetBrowserSearchBar + debounce
    1.4.4 AssetItemCard + per-asset action menu
    1.4.5 Custom groups / tag management UI
    1.4.6 Navigation wiring (App.kt Screen + sidebar + command palette)
          │
          ▼
  Epic 1.5 Tests (Phase 1)
    1.5.1 AssetRepositoryTest
    1.5.2 AssetMoveServiceTest
    1.5.3 AssetIndexServiceTest
    1.5.4 MigrationRunnerSchemaSyncTest + MoveWalReplayTest

Phase 2 (depends on all of Phase 1)
  Epic 2.1 Plugin Architecture
    2.1.1 AssetPipelinePlugin interface + AssetPipelineResult
    2.1.2 PluginRegistry + AssetPipelineService (own CoroutineScope)
    2.1.3 Processing state tracking in asset_index
          │
          ▼
  Epic 2.2 Platform ML Implementations
    2.2.1 ImageLabeler + PdfTextExtractor interfaces (commonMain)
    2.2.2 Android ML Kit ImageLabeler + TextRecognizer
    2.2.3 JVM ONNX Runtime ImageLabeler
    2.2.4 iOS Core ML / Vision ImageLabeler + PDFKit extractor
    2.2.5 NoOp fallback impls
          │
          ▼
  Epic 2.3 Built-in Pipeline Plugins
    2.3.1 OnDeviceLabelingPlugin
    2.3.2 OcrPlugin (image OCR)
    2.3.3 PdfTextPlugin (PDF text extraction)
          │
          ▼
  Epic 2.4 Cloud-Optional Enrichment
    2.4.1 CloudEnrichmentConfig + graph settings UI
    2.4.2 CloudVisionPlugin (Google Vision API)
    2.4.3 CloudClaudePlugin (Anthropic API, tool use)
    2.4.4 Rate cap enforcement (20/session default, opt-in)
          │
          ▼
  Epic 2.5 Processing Triggers + UI
    2.5.1 Import trigger (post-copy background job)
    2.5.2 Backfill drain loop (10/batch, incremental)
    2.5.3 "Analyze" button in AssetBrowserScreen
    2.5.4 Processing state badges in asset grid
          │
          ▼
  Epic 2.6 Tests (Phase 2)
    2.6.1 PluginRegistryTest + AssetPipelineServiceTest
    2.6.2 OnDeviceLabelingPluginTest (stub ImageLabeler)
    2.6.3 CloudEnrichmentPluginTest (mock HTTP)
    2.6.4 BackfillThrottleTest
```

---

## Phase 1: Organization + Asset Browser

### Epic 1.1: SQLDelight Schema, Data Model, Repository Interface
**Goal**: Lay the persistence foundation — new tables in schema, MigrationRunner entries, domain model types, and an in-memory repository for test isolation.

---

#### Story 1.1.1: Add `asset_index` table to SQLDelight schema
**As a** SteleKit developer, **I want** a SQLDelight-backed `asset_index` table, **so that** assets have a durable, queryable metadata store.
**Acceptance Criteria**:
- `asset_index` exists in `SteleDatabase.sq`
- The table appears in `MigrationRunner.all` (enforced by `MigrationRunnerSchemaSyncTest`)
- `SteleDatabase.sq` generates valid queries for insert, paginated select, select-by-type, select-by-uuid, count, update-path, update-tags, update-auto-labels, mark-ml-processed, delete

##### Task 1.1.1a: Add asset_index DDL to SteleDatabase.sq (~3 min)
- Append `CREATE TABLE IF NOT EXISTS asset_index (...)` to `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`
- Columns: `uuid TEXT PK`, `file_path TEXT`, `relative_path TEXT`, `media_type TEXT`, `subfolder TEXT DEFAULT 'files'`, `tags TEXT DEFAULT '[]'`, `auto_labels TEXT DEFAULT '[]'`, `ocr_text TEXT`, `cloud_description TEXT`, `page_uuids TEXT DEFAULT '[]'`, `size_bytes INTEGER DEFAULT 0`, `imported_at_ms INTEGER`, `ml_processed INTEGER DEFAULT 0`, `ml_attempted_at INTEGER`, `ml_failed INTEGER NOT NULL DEFAULT 0`, `content_hash TEXT`, `is_orphan INTEGER DEFAULT 0`, `ml_tags_source TEXT NOT NULL DEFAULT 'NONE'`
  - `ml_tags_source` stores `'NONE'`, `'LOCAL'`, `'CLOUD_VISION'`, or `'CLAUDE'` — this is the **binding idempotency guard** for cloud enrichment: before calling any cloud API, check `ml_tags_source != 'NONE'`; if already set, skip. This column (not the in-memory session counter) is the primary defense against re-processing assets across graph switches.
  - `ml_failed INTEGER NOT NULL DEFAULT 0` — set to 1 after a processing attempt fails; excluded from `selectUnprocessedAssets` filter
- Files: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`

##### Task 1.1.1b: Add typed queries to SteleDatabase.sq + RestrictedDatabaseQueries stubs (~5 min)
- Append named queries: `insertAsset`, `selectAssetByUuid`, `selectAssets`, `selectAssetsByMediaType`, `searchAssetsByTag`, `selectAssetsByPageUuid`, `selectUnprocessedAssets`, `countUnprocessedAssets`, `countAssets`, `updateAssetFilePath`, `updateAssetTags`, `updateAssetAutoLabels`, `markAssetMlProcessed`, `updateAssetPageUuids`, `deleteAsset`
  - `selectAssets` (not "selectAllAssets") takes mandatory `LIMIT :limit OFFSET :offset` — no unbounded variant exists
  - `selectUnprocessedAssets` filters on `ml_processed = 0 AND (ml_failed = 0 OR ml_failed IS NULL)` to exclude permanently-failed assets
- Also add `ml_failed INTEGER NOT NULL DEFAULT 0` column to the `asset_index` DDL in Task 1.1.1a
- **Blocker fix (RestrictedDatabaseQueries)**: Add `@DirectSqlWrite`-annotated forwarding stubs for all asset mutating queries to `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`:
  - `insertAsset(...)`, `updateAssetFilePath(...)`, `updateAssetTags(...)`, `updateAssetAutoLabels(...)`, `markAssetMlProcessed(...)`, `markAssetMlFailed(...)`, `updateAssetMlTagsSource(...)`, `updateAssetPageUuids(...)`, `deleteAsset(...)`
- Files: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`

##### Task 1.1.1c: Register asset_index in MigrationRunner.all (~3 min)
- Open `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt`
- Append a new `SqlMigration` entry to `all` list that creates `asset_index` (idempotent `CREATE TABLE IF NOT EXISTS`)
- Do NOT edit or reorder existing entries
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt`

---

#### Story 1.1.2: Add `pending_asset_moves` WAL table
**As a** SteleKit developer, **I want** a write-ahead log table for in-flight asset moves, **so that** a crash mid-move is safely recoverable on next graph open.
**Acceptance Criteria**:
- `pending_asset_moves` in `SteleDatabase.sq` and `MigrationRunner.all`
- Schema: `id INTEGER PK AUTOINCREMENT`, `asset_uuid TEXT`, `old_file_path TEXT`, `new_file_path TEXT`, `old_relative_path TEXT`, `new_relative_path TEXT`, `created_at_ms INTEGER`
- Queries: `insertPendingMove`, `deletePendingMove`, `selectAllPendingMoves`

##### Task 1.1.2a: Add pending_asset_moves DDL + queries + RestrictedDatabaseQueries stubs (~4 min)
- Append DDL and three named queries to `SteleDatabase.sq`
- **Blocker fix (RestrictedDatabaseQueries)**: Add `@DirectSqlWrite`-annotated forwarding stubs for `insertPendingMove(...)` and `deletePendingMove(...)` to `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`
- `selectAllPendingMoves` is a read-only query (no stub needed); add a comment noting the table is expected to hold ≤ a handful of rows and an unbounded read is safe
- Files: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`

##### Task 1.1.2b: Register pending_asset_moves in MigrationRunner.all (~2 min)
- Append second `SqlMigration` to `MigrationRunner.all` creating `pending_asset_moves`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt`

---

#### Story 1.1.3: Domain model types
**As a** SteleKit developer, **I want** typed Kotlin model classes for assets, **so that** the repository and service layers have a stable domain API.
**Acceptance Criteria**:
- `AssetEntry` data class with all index fields as Kotlin types
- `AssetMediaType` sealed class/enum with Image, Pdf, Audio, Video, Document, File members
- `AssetUuid` value class (mirrors `PageUuid` / `BlockUuid` pattern)
- `AssetPendingMove` data class

##### Task 1.1.3a: Create AssetModels.kt (~4 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/AssetModels.kt`
- Define `@JvmInline value class AssetUuid(val value: String)`
- Define `enum class AssetMediaType { IMAGE, PDF, AUDIO, VIDEO, DOCUMENT, FILE }` with `fun fromMimeType(mime: String): AssetMediaType` companion factory
- Define `data class AssetEntry(uuid, filePath, relativePath, mediaType, subfolder, tags, autoLabels, ocrText, cloudDescription, pageUuids, sizeBytes, importedAtMs, mlProcessed, mlAttemptedAt, contentHash, isOrphan)`
- Define `data class AssetPendingMove(id, assetUuid, oldFilePath, newFilePath, oldRelativePath, newRelativePath, createdAtMs)`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/AssetModels.kt`

---

#### Story 1.1.4: AssetRepository interface + InMemory impl
**As a** SteleKit developer, **I want** an `AssetRepository` interface with a test-only in-memory implementation, **so that** service layer tests don't need SQLite.
**Acceptance Criteria**:
- Interface matches the bounded-reads contract (no `getAllAssets()`)
- `InMemoryAssetRepository` passes a basic CRUD smoke test

##### Task 1.1.4a: Create AssetRepository interface (~3 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/AssetRepository.kt`
- Define interface with all methods from the architecture research (paginated reads, count, save, update-tags, update-auto-labels, mark-ml-processed, update-file-path, update-page-uuids, delete, getUnprocessedAssets, countUnprocessedAssets)
- All Flow returns: `Flow<Either<DomainError, T>>`; all writes: `suspend fun ... : Either<DomainError, Unit>`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/AssetRepository.kt`

##### Task 1.1.4b: Create InMemoryAssetRepository (~4 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/InMemoryAssetRepository.kt`
- Backed by `ConcurrentHashMap<String, AssetEntry>` + `MutableStateFlow` emissions
- Implements full interface; no pagination (in-memory tests use small datasets)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/InMemoryAssetRepository.kt`

---

### Epic 1.2: Typed Subfolder Routing on Attach
**Goal**: When any file is attached (pick, drag-drop, paste), it lands in the correct `assets/<subfolder>/` directory with a Logseq-valid relative link.

---

#### Story 1.2.1: MIME detection + path resolver
**As a** user, **I want** attached files automatically placed in typed subfolders, **so that** my assets directory stays organized without manual work.
**Acceptance Criteria**:
- MIME detection uses first 16 bytes (magic bytes) with extension fallback
- `AssetStoragePathResolver.resolveSubfolder(mime)` returns one of six subfolder strings
- Resolved path is `<graphRoot>/assets/<subfolder>/<unique-name>`

##### Task 1.2.1a: Create MimeTypeDetector (~4 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/MimeTypeDetector.kt`
- `fun detect(bytes: ByteArray, filename: String): String` — check magic bytes for JPEG (FF D8), PNG (89 50), PDF (%PDF), MP4 (ftyp), ZIP (PK), then fall back to extension map
- No external library dependency
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/MimeTypeDetector.kt`

##### Task 1.2.1b: Create AssetStoragePathResolver (~3 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/AssetStoragePathResolver.kt`
- `fun resolveSubfolder(mimeType: String): String` — maps mime prefix to subfolder string per REQ-1.1 table
- `fun resolvePath(graphRoot: String, subfolder: String, filename: String): String` — returns `<graphRoot>/assets/<subfolder>/<filename>`
- `fun relativeMarkdownPath(subfolder: String, filename: String): String` — returns `../assets/<subfolder>/<filename>`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/AssetStoragePathResolver.kt`

---

#### Story 1.2.2: Attach service routing update
**As a** user, **I want** existing attach flows (pick, drag-drop, paste) to automatically use typed subfolders, **so that** no existing behavior breaks.
**Acceptance Criteria**:
- `pickAndAttach` and `attachFilePath` write to the correct subfolder
- `AttachmentResult.relativePath` uses `../assets/<subfolder>/...` form
- Existing image-camera path (`assets/images/`) is unchanged

##### Task 1.2.2a: Add subfolder routing to desktop MediaAttachmentService (~4 min)
- Locate `jvmMain` implementation of `MediaAttachmentService`
- After the file is read, call `MimeTypeDetector.detect(firstBytes, filename)` then `AssetStoragePathResolver.resolveSubfolder(mime)` to get the target dir
- Create subfolder if absent; call `AttachmentFileNaming.uniqueFileName` with new path
- Return `AttachmentResult(relativePath = AssetStoragePathResolver.relativeMarkdownPath(subfolder, name), ...)`
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/service/DesktopMediaAttachmentService.kt` (or equivalent)

##### Task 1.2.2b: Add subfolder routing to Android MediaAttachmentService (~4 min)
- Same logic in `androidMain` implementation
- `ImageStoragePathResolver` already handles images — skip MIME detection when `ContentResolver` mime is `image/*` and path already goes via camera path
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/service/AndroidMediaAttachmentService.kt` (or equivalent)

---

### Epic 1.3: Asset Index Service + Move Service
**Goal**: A queryable SQLite-backed index of all graph assets; atomic move with markdown ref rewrite and crash recovery via WAL replay.

---

#### Story 1.3.1: SqlDelightAssetRepository
**As a** SteleKit developer, **I want** a SQLDelight-backed `AssetRepository`, **so that** asset metadata is durably stored and efficiently queryable.
**Acceptance Criteria**:
- All reads use `asDbFlowList` / `asDbFlowOrNull` or manual chain with `catchDbError()`
- All writes use `withContext(PlatformDispatcher.DB)` + `Either` error wrapping
- No unbounded queries (every list query has `LIMIT :limit OFFSET :offset`)

##### Task 1.3.1a: Create SqlDelightAssetRepository (~5 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightAssetRepository.kt`
- Constructor takes `SteleDatabase`; queries via `database.steleDatabase.assetIndexQueries`
- Implement all interface methods; use `asDbFlowList(PlatformDispatcher.DB)` for reads
- JSON serialization for `tags`/`autoLabels`/`pageUuids` fields via `kotlinx.serialization.json.Json`
- Add `toModel()` extension on generated SQL row type
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightAssetRepository.kt`

##### Task 1.3.1b: Wire SqlDelightAssetRepository into RepositorySet (~3 min)
- Add `assetRepository: AssetRepository` field to `RepositorySet` in `RepositoryFactory.kt` defaulting to `InMemoryAssetRepository()`
- In `createRepositorySet()`, when `backend == SQLDELIGHT`, set `assetRepository = SqlDelightAssetRepository(database)`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt`

---

#### Story 1.3.2: AssetIndexService (registration + backfill)
**As a** user, **I want** every attached file auto-registered in the asset index, **so that** the browser always reflects current disk state.
**Acceptance Criteria**:
- `registerAsset(filePath, graphRoot, pageUuid)` creates an `AssetEntry` and saves it via the write actor
- `backfillGraph(graphRoot)` scans `assets/` recursively in bounded batches (≤50 per batch), skips already-indexed paths (by `content_hash`/mtime), registers new ones
- Service owns its `CoroutineScope` with `SupervisorJob + Dispatchers.Default + CoroutineExceptionHandler`; catches `Throwable`

##### Task 1.3.2a: Create AssetIndexService (~5 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/AssetIndexService.kt`
- `suspend fun registerAsset(filePath, graphRoot, mimeHint, pageUuid, writeActor)`: builds `AssetEntry`, calls `writeActor.execute { assetRepository.saveAsset(entry) }`
- **Blocker fix (Okio not available in commonMain iOS)**: `startBackfill()` must use `dev.stapler.stelekit.platform.FileSystem` (the existing `platform.FileSystem` interface already in the codebase) instead of `okio.FileSystem.listRecursively()` directly. Add a `listFilesRecursive(dir: String): List<String>` method to `platform.FileSystem` interface (or use the existing `listDirectory`/`directoryExists` methods in a recursive helper function that compiles in commonMain). Do NOT call `okio.FileSystem.listRecursively()` from commonMain — it is not available in `iosMain`.
- Does NOT use `rememberCoroutineScope()` — scope passed from `GraphLoader` or service's own scope
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/AssetIndexService.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt`

##### Task 1.3.2b: Hook backfill into GraphLoader (~3 min)
- In `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`, after `onBulkImportComplete()` is called (or at the end of `loadDirectory()`), call `assetIndexService.startBackfill(graphRoot, writeActor, scope)` on a background scope
- Ensure this is non-blocking (launched, not awaited)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`

---

#### Story 1.3.3: AssetMoveService (atomic move + WAL replay)
**As a** user, **I want** moving an asset from the browser to atomically update the file, all markdown references, and the index, **so that** no links break after a move.
**Acceptance Criteria**:
- Uses Arrow Saga for compensation (file move → ref rewrite → DB update)
- WAL entry inserted BEFORE file move; deleted AFTER DB update
- `replayPendingMoves(graphRoot)` called on graph open to recover from mid-move crashes
- Concurrent moves on the same UUID are serialized via `Mutex`

##### Task 1.3.3a: Create AssetMoveService (~5 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/AssetMoveService.kt`
- `private val mutexMap = ConcurrentHashMap<String, Mutex>()` for per-UUID serialization
- `suspend fun moveAsset(asset, newSubfolder, graphRoot, fileSystem, graphWriter, writeActor)`:
  1. Insert WAL entry via `writeActor.execute { insertPendingMove(...) }`
  2. Arrow Saga: `saga({ fileSystem.rename(old, new) }) { fileSystem.rename(new, old) }` → `saga({ graphWriter.rewriteAssetReference(old, new) }) { revert }` → `saga({ writeActor.execute { assetRepository.updateFilePath(...) } }) { revert }`
  3. `.transact()`
  4. Delete WAL entry
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/AssetMoveService.kt`

##### Task 1.3.3b: Implement WAL replay on graph open (~5 min)
- Add `suspend fun replayPendingMoves(graphRoot, fileSystem, graphWriter, writeActor, assetRepository)` to `AssetMoveService`
- **Blocker fix (idempotent replay)**: For each WAL row, apply this decision tree before taking any action:
  1. If `old_file_path` exists AND `new_file_path` does not exist → move is incomplete; proceed with rename + ref rewrite + DB update + WAL delete
  2. If `old_file_path` does NOT exist AND `new_file_path` exists → move already completed (crash between DB update and WAL delete); skip to DB update (in case DB row wasn't updated) + WAL delete
  3. If both exist → ambiguous (crash between rename and WAL insert on a re-run, or two files at both paths); skip this row, log a warning, do NOT delete the WAL row
  4. If neither exists → file is gone; delete WAL row, log warning
- Replay must hold the write actor lock (via `writeActor.execute { ... }` wrapping the WAL delete + DB update) so concurrent replay invocations cannot race
- Call from `GraphManager.addGraph()` before returning the graph to the caller
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/AssetMoveService.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`

##### Task 1.3.3c: Add rewriteAssetReference to GraphWriter (~4 min)
- Add `suspend fun rewriteAssetReference(oldRelativePath, newRelativePath, pageUuids, graphRoot)` to `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt`
- Rewrite `![...](<oldRelativePath>)` and `[[<oldRelativePath>]]` patterns in all referencing page files
- **Blocker fix (file watcher spurious DiskConflict)**: Before writing each page file, call `onPreWrite(pageFilePath)` (the same hook that `savePageInternal` calls) to suppress the file-watcher's external-change detection. Call `onClearPendingWrite(pageFilePath)` in the saga compensation step when reverting the rewrite. Without this, every asset move triggers a "DiskConflict" dialog for each page that references the asset.
- Use semaphore of 4 concurrent file rewrites (mirrors `BacklinkRenamer` pattern)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt`

---

#### Story 1.3.4: Register attach in index
**As a** user, **I want** every newly attached file automatically appearing in the asset browser immediately, **so that** the browser is always up to date.
**Acceptance Criteria**:
- After `MediaAttachmentService` succeeds, `AssetIndexService.registerAsset()` is called with the new path and current page UUID
- This happens in the ViewModel/call site, not inside the service itself

##### Task 1.3.4a: Wire asset registration to attach call site (~3 min)
- In `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (or wherever `pickAndAttach` is called), after a successful `AttachmentResult`, call `assetIndexService.registerAsset(absolutePath, graphRoot, currentPageUuid, writeActor)`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

---

### Epic 1.4: Asset Browser UI
**Goal**: A navigable screen showing all graph assets with filtering, search, and per-asset actions.

---

#### Story 1.4.1: AssetBrowserViewModel
**As a** developer, **I want** a ViewModel that provides paginated, filtered, searchable asset state via `StateFlow`, **so that** the UI is reactive without owning coroutine scopes.
**Acceptance Criteria**:
- Owns its `CoroutineScope` with `SupervisorJob + Dispatchers.Default + CoroutineExceptionHandler`
- Never accepts `rememberCoroutineScope()` as a parameter
- Exposes `StateFlow<AssetBrowserUiState>` with paged results, filter, search query, loading flag, error
- Search debounced 300ms

##### Task 1.4.1a: Define AssetBrowserUiState (~2 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserUiState.kt`
- `data class AssetBrowserUiState(assets, selectedFilter, searchQuery, isLoading, error, totalCount, viewMode)`
- `enum class AssetFilter { ALL, IMAGES, PDFS, AUDIO, VIDEO, DOCUMENTS, FILES, ORPHANED }`
- `enum class ViewMode { GRID, LIST }`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserUiState.kt`

##### Task 1.4.1b: Create AssetBrowserViewModel (~5 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserViewModel.kt`
- Constructor takes `assetRepository: AssetRepository`, `assetMoveService: AssetMoveService`, `writeActor: DatabaseWriteActor?`
- `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { ... })`
- `val uiState: StateFlow<AssetBrowserUiState>` backed by `MutableStateFlow`
- `fun setFilter(filter)`, `fun setSearch(query)` — debounced via `scope.launch` + `delay(300)`
- Loads via `assetRepository.getAssets(limit=50, offset=0)` with filter mapping
- **Concern fix (scope lifecycle)**: Implement `RememberObserver` interface (`onRemembered`, `onForgotten`, `onAbandoned`) — in `onForgotten`/`onAbandoned`, cancel the scope via `scope.cancel()`. This ensures Compose cancels the ViewModel scope when `AssetBrowserScreen` leaves the composition. Document in KDoc that callers must store the ViewModel only inside `remember { }` (not a held reference outside composition).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserViewModel.kt`

---

#### Story 1.4.2: AssetBrowserScreen and grid/list
**As a** user, **I want** to see all my assets in a browsable screen, **so that** I can find and manage files without leaving the app.
**Acceptance Criteria**:
- `LazyVerticalGrid(GridCells.Adaptive(120.dp))` for image/grid view
- `LazyColumn` for list view
- Toggle button in toolbar
- Filter chip bar below toolbar
- `AssetBrowserScreen` route registered as `Screen.AssetBrowser` in `AppState.kt`

##### Task 1.4.2a: Add Screen.AssetBrowser to AppState.kt (~2 min)
- Open `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`
- Add `data object AssetBrowser : Screen()` to the `Screen` sealed class
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`

##### Task 1.4.2b: Create AssetBrowserScreen composable (~5 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserScreen.kt`
- `@Composable fun AssetBrowserScreen(viewModel: AssetBrowserViewModel, onNavigateBack: () -> Unit)`
- Scaffold: TopAppBar (title "Assets", back button, view-mode toggle), filter chips row (`ScrollableTabRow` or `LazyRow`), search bar, content area
- `if (uiState.viewMode == GRID)` → `LazyVerticalGrid(GridCells.Adaptive(120.dp))` else `LazyColumn`
- Each item delegates to `AssetItemCard`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserScreen.kt`

##### Task 1.4.2c: Create AssetItemCard composable (~4 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetItemCard.kt`
- Grid mode: thumbnail (Coil `AsyncImage` for images; icon for others), filename truncated, type badge
- List mode: icon + filename + size + date + tags row
- Long-press enters multi-select mode (pass `onLongPress` callback)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetItemCard.kt`

---

#### Story 1.4.3: Search, per-asset actions, and custom groups
**As a** user, **I want** to search, rename, move, delete, and tag assets from the browser, **so that** I can manage assets without leaving SteleKit.
**Acceptance Criteria**:
- Search bar debounces 300ms, queries filename + tag + auto-label
- Per-asset dropdown: Open, Copy Link, Rename, Move to folder, Delete, Edit tags
- Custom groups shown as filter chips under a "Groups" section in the filter bar
- Group = tag stored in asset index

##### Task 1.4.3a: Create AssetBrowserSearchBar composable (~3 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserSearchBar.kt`
- Standard `OutlinedTextField` with clear button; `onValueChange` delegates to `viewModel.setSearch()`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserSearchBar.kt`

##### Task 1.4.3b: Create AssetActionMenu composable (~4 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetActionMenu.kt`
- `DropdownMenu` with items: Open (platform open-with), Copy link (clipboard), Rename (inline dialog), Move (subfolder picker dialog), Delete (confirm dialog), Edit tags (tag chip editor dialog)
- All actions trigger ViewModel methods that call the appropriate service
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetActionMenu.kt`

##### Task 1.4.3c: Add custom group creation UI (~3 min)
- In `AssetBrowserScreen`, add "New Group" chip at end of filter bar → `AlertDialog` with `TextField` for group name → calls `viewModel.createGroup(name)` which calls `writeActor.execute { assetRepository.updateTags(uuid, tags + newGroup) }`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserScreen.kt`

---

#### Story 1.4.4: Navigation wiring
**As a** user, **I want** to reach the Asset Browser from the sidebar and command palette, **so that** I can access it in one tap.
**Acceptance Criteria**:
- Sidebar shows "Assets" entry that navigates to `Screen.AssetBrowser`
- Command palette has an "Open asset browser" command
- `App.kt` renders `AssetBrowserScreen` when current screen is `Screen.AssetBrowser`

##### Task 1.4.4a: Wire AssetBrowserScreen into App.kt navigation (~4 min)
- Open `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`
- In the `AnimatedContent` screen routing block, add a branch for `Screen.AssetBrowser` that renders `AssetBrowserScreen(viewModel = remember { AssetBrowserViewModel(repoSet.assetRepository, ...) }, onNavigateBack = { viewModel.navigateTo(previousScreen) })`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 1.4.4b: Add sidebar entry and command palette command (~3 min)
- In the sidebar composable (locate the sidebar nav items list in `App.kt` or a dedicated sidebar component), add an "Assets" item with `Icons.Default.Photo` that calls `viewModel.navigateTo(Screen.AssetBrowser)`
- In `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/Command.kt` (or `BlockCommands.kt`), add `OpenAssetBrowser` command
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/Command.kt`

---

### Epic 1.5: Phase 1 Tests
**Goal**: ≥80% coverage on domain logic; regression guards on schema sync, move atomicity, and backfill.

---

#### Story 1.5.1: Repository and domain model tests
**As a** developer, **I want** unit tests for `SqlDelightAssetRepository` and `InMemoryAssetRepository`, **so that** the persistence layer is validated independently.
**Acceptance Criteria**:
- CRUD round-trip, pagination, filter-by-type, tag update, ml-processed flag, delete

##### Task 1.5.1a: AssetRepositoryTest (~4 min)
- Create `kmp/src/businessTest/kotlin/dev/stapler/stelekit/repository/AssetRepositoryTest.kt`
- Tests for save, getByUuid, getAssets pagination, getAssetsByMediaType, updateTags, markMlProcessed, delete using `InMemoryAssetRepository`
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/repository/AssetRepositoryTest.kt`

##### Task 1.5.1b: AssetMediaTypeTest + MimeTypeDetectorTest (~4 min)
- Create `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/MimeTypeDetectorTest.kt`
- Test magic-bytes detection for JPEG (FF D8), PNG (89 50), PDF (%PDF), extension fallback
- **Concern fix (truncated file edge cases)**: Must include explicit test cases for:
  - 0-byte file (empty `ByteArray()`) → falls back to extension; no exception
  - 4-byte file (`byteArrayOf(0,0,0,0)`) → falls back to extension; no exception
  - 7-byte file → falls back to extension; no exception
  - File shorter than longest magic-byte pattern (8+ bytes for MP4 ftyp) → no `IndexOutOfBoundsException`
- `MimeTypeDetector.detect()` must bounds-check all magic-byte slices before comparison
- Create `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/AssetMediaTypeTest.kt`
- Test `fromMimeType()` for all six media types
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/MimeTypeDetectorTest.kt`, `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/AssetMediaTypeTest.kt`

---

#### Story 1.5.2: AssetMoveService tests
**As a** developer, **I want** tests for `AssetMoveService`, **so that** the compensation logic and WAL replay are verified without a real filesystem.
**Acceptance Criteria**:
- Happy path: file moved, refs updated, DB updated, WAL entry deleted
- Compensation: if file move fails, nothing else changes
- WAL replay: surviving WAL entry replayed on `replayPendingMoves()`

##### Task 1.5.2a: AssetMoveServiceTest (~5 min)
- Create `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/AssetMoveServiceTest.kt`
- Use `okio.fakefilesystem.FakeFileSystem` and `InMemoryAssetRepository`
- Stub `GraphWriter.rewriteAssetReference` via an interface/test double
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/AssetMoveServiceTest.kt`

---

#### Story 1.5.3: AssetIndexService backfill test
**As a** developer, **I want** tests for bounded backfill, **so that** we confirm it never issues unbounded reads and terminates cleanly.
**Acceptance Criteria**:
- 200-file fake filesystem → backfill indexes all in batches of ≤50
- Already-indexed files (same content_hash) are skipped
- Permanently-unreadable files do not block forward progress

##### Task 1.5.3a: AssetIndexServiceTest (~5 min)
- Create `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/AssetIndexServiceTest.kt`
- Use `FakeFileSystem`, `InMemoryAssetRepository`, verify batch count ≤50 per pass, verify termination
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/AssetIndexServiceTest.kt`

---

#### Story 1.5.4: MigrationRunner schema sync test coverage
**As a** developer, **I want** `MigrationRunnerSchemaSyncTest` to cover the new tables, **so that** CI fails if they are missing from `MigrationRunner.all`.
**Acceptance Criteria**:
- Existing test auto-detects `asset_index` and `pending_asset_moves` in `SteleDatabase.sq` and asserts they are in `MigrationRunner.all` — no code change needed if the table names follow the existing pattern
- Verify the test passes after tasks 1.1.1c and 1.1.2b

##### Task 1.5.4a: Confirm MigrationRunnerSchemaSyncTest coverage (~2 min)
- Run `./gradlew jvmTest --tests "*MigrationRunnerSchemaSyncTest"` and verify both tables are caught
- If the test uses explicit allowlists rather than auto-detection, add `asset_index` and `pending_asset_moves` to it
- Files: Find and open `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/MigrationRunnerSchemaSyncTest.kt`

---

## Phase 2: ML Pipeline + Cloud-Optional Enrichment

### Epic 2.1: Plugin Architecture
**Goal**: A pluggable pipeline with a stable `AssetPipelinePlugin` interface, a `PluginRegistry`, and `AssetPipelineService` that owns its lifecycle and enforces throttling.

---

#### Story 2.1.1: AssetPipelinePlugin interface and registry
**As a** developer, **I want** a stable plugin interface, **so that** built-in and third-party ML plugins can be registered without coupling to a specific implementation.
**Acceptance Criteria**:
- `AssetPipelinePlugin` interface in commonMain with `id`, `canProcess(asset)`, `suspend processAsset(asset): Either<DomainError, AssetPipelineResult>`
- `PluginRegistry` holds an ordered list; plugins processed in registration order
- `AssetPipelineResult` is a sealed type (Labels, OcrText, CloudDescription, Combined)

##### Task 2.1.1a: Create plugin interface types (~3 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/AssetPipelinePlugin.kt`
- Define `interface AssetPipelinePlugin { val id: String; fun canProcess(asset: AssetEntry): Boolean; suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> }`
- Define `sealed class AssetPipelineResult` with `Labels(list)`, `OcrText(text)`, `CloudDescription(desc, labelsAdded)`, `Combined(labels, ocrText, cloudDescription)` subtypes
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/AssetPipelinePlugin.kt`

##### Task 2.1.1b: Create PluginRegistry (~2 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/PluginRegistry.kt`
- `class PluginRegistry` with `private val plugins = mutableListOf<AssetPipelinePlugin>()`, `fun register(plugin)`, `val all: List<AssetPipelinePlugin> get() = plugins.toList()`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/PluginRegistry.kt`

---

#### Story 2.1.2: AssetPipelineService
**As a** developer, **I want** a service that processes assets through the plugin pipeline, **so that** ML results are stored without blocking the UI.
**Acceptance Criteria**:
- Owns `CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { catch Throwable })`
- `suspend fun processAsset(asset, writeActor)` runs each applicable plugin, merges results, calls `writeActor.execute { updateAutoLabels(...); markMlProcessed(...) }`
- `fun scheduleBackfill(graphRoot, writeActor)` drains unprocessed assets in batches of 10 with `yield()` between batches
- Sets `ml_attempted_at` even on failure so failed assets don't loop forever

##### Task 2.1.2a: Create AssetPipelineService (~5 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/AssetPipelineService.kt`
- Fields: `private val scope`, `private val registry: PluginRegistry`, `private val assetRepository: AssetRepository`
- `suspend fun processAsset(asset, writeActor)`: iterate `registry.all.filter { it.canProcess(asset) }`, collect results, merge into `autoLabels` list + `ocrText` string, call repository updates through actor
- On failure: call `writeActor.execute { assetRepository.markMlFailed(uuid) }` to set `ml_failed = 1` so the asset is excluded from future drain iterations (preventing infinite retry loop)
- **Blocker fix (drain infinite-loop on permanently-failing assets)**: `scheduleBackfill()` must maintain an in-session `attemptedUuids: MutableSet<String>` that accumulates all UUIDs processed in the current session (both successful and failed). Pass this set to `getUnprocessedAssets()` or filter the returned list client-side, skipping any UUID already in `attemptedUuids`. After `ml_failed = 1` is set for an asset, it is also excluded by the `selectUnprocessedAssets` query's `ml_failed = 0` filter, so the protection persists across process restarts. This mirrors the `attempted` set pattern in `GraphLoader.indexRemainingPages`.
- Catches `Throwable` (not just `Exception`) per Android crash-guard rule
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/AssetPipelineService.kt`

##### Task 2.1.2b: Add processing-state update queries to SteleDatabase.sq (~3 min)
- Ensure `updateAssetOcrText`, `updateAssetCloudDescription`, and `updateAssetMlAttemptedAt` queries exist in `SteleDatabase.sq`
- Add corresponding `RestrictedDatabaseQueries` stubs with `@DirectSqlWrite`
- Files: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`

---

### Epic 2.2: Platform ML Interfaces + Implementations
**Goal**: Stable `ImageLabeler` and `PdfTextExtractor` service interfaces in commonMain; platform-specific implementations injected at startup.

---

#### Story 2.2.1: Define platform ML service interfaces in commonMain
**As a** developer, **I want** common ML service interfaces, **so that** the pipeline plugins are testable with stubs.
**Acceptance Criteria**:
- `interface ImageLabeler { suspend fun labelImage(imageBytes: ByteArray): Either<DomainError, List<Label>> }`
- `interface PdfTextExtractor { suspend fun extractText(absoluteFilePath: String): Either<DomainError, String> }`
- `data class Label(text: String, confidence: Float)`
- `NoOpImageLabeler` and `NoOpPdfTextExtractor` stub implementations

##### Task 2.2.1a: Create ML service interfaces (~3 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ml/ImageLabeler.kt`
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ml/PdfTextExtractor.kt`
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ml/MlServiceStubs.kt` with `NoOp*` impls
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ml/ImageLabeler.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ml/PdfTextExtractor.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ml/MlServiceStubs.kt`

---

#### Story 2.2.2: Android ML Kit implementation
**As a** user on Android, **I want** on-device image labeling via ML Kit, **so that** images are tagged without internet connectivity.
**Acceptance Criteria**:
- `MlKitImageLabeler` implements `ImageLabeler`; uses `ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)`
- Images downscaled to 512px before labeling to prevent OOM; catches `Throwable`
- `MlKitOcrLabeler` uses `TextRecognition`; downscales to 1280px for OCR
- `AndroidPdfTextExtractor` uses `com.tom-roush:pdfbox-android`; processes one page at a time, `bitmap.recycle()` after each page; `limitedParallelism(1)`

##### Task 2.2.2a: Create MlKitImageLabeler (~5 min)
- Create `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/MlKitImageLabeler.kt`
- Downscale `ByteArray` → `Bitmap` at max 512px (preserve aspect ratio)
- Call `labeler.process(InputImage.fromBitmap(bmp, 0)).await()`, map to `Label` list, catch `Throwable`
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/MlKitImageLabeler.kt`

##### Task 2.2.2b: Create AndroidPdfTextExtractor (~5 min)
- Create `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/AndroidPdfTextExtractor.kt`
- Open PDF with `PdfRenderer`, iterate pages, for each: render to bitmap at 1x scale, run OCR via `TextRecognition`, recycle bitmap, concatenate text
- Wrap in `withContext(Dispatchers.IO.limitedParallelism(1))` to avoid OOM
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/AndroidPdfTextExtractor.kt`

---

#### Story 2.2.3: JVM ONNX Runtime implementation
**As a** user on Desktop, **I want** on-device image labeling via ONNX Runtime, **so that** desktop graphs also get auto-labels.
**Acceptance Criteria**:
- `OnnxImageLabeler` loads model from bundled resource via file path (not ByteArray) to avoid heap OOM
- Downscales to 512px, runs inference, maps output to `Label` list
- Falls back to `NoOpImageLabeler` if ONNX Runtime not available

##### Task 2.2.3a: Create OnnxImageLabeler stub (model loading + inference) (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/ml/OnnxImageLabeler.kt`
- `private val session: OrtSession` loaded lazily from `OrtEnvironment.getEnvironment().createSession(modelFilePath)`
- `override suspend fun labelImage(imageBytes)`: decode → resize 512px → create tensor → run session → parse output scores → map top-5 to `Label`
- `object OnnxImageLabelerFactory { fun create(modelPath: String): ImageLabeler }` so `App.kt` wires it up
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/ml/OnnxImageLabeler.kt`

##### Task 2.2.3b: Create JvmPdfTextExtractor (~4 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/ml/JvmPdfTextExtractor.kt`
- Use `org.apache.pdfbox.pdmodel.PDDocument.load(File(filePath))` → `PDFTextStripper().getText(doc)` → close doc
- Wrap in `withContext(Dispatchers.IO)`
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/ml/JvmPdfTextExtractor.kt`

---

#### Story 2.2.4: iOS Core ML / PDFKit implementation
**As a** user on iOS, **I want** on-device image labeling via Core ML and PDF text extraction via PDFKit, **so that** iOS parity is maintained.
**Acceptance Criteria**:
- `CoreMlImageLabeler` uses `VNImageRequestHandler` + `VNClassifyImageRequest`
- `IosPdfTextExtractor` uses `PDFDocument` → iterate pages → `page.string`

##### Task 2.2.4a: Create CoreMlImageLabeler + IosPdfTextExtractor (~5 min)
- Create `kmp/src/iosMain/kotlin/dev/stapler/stelekit/platform/ml/CoreMlImageLabeler.kt`
- Create `kmp/src/iosMain/kotlin/dev/stapler/stelekit/platform/ml/IosPdfTextExtractor.kt`
- Use Kotlin/Native interop with Vision framework (`VNImageRequestHandler`, `VNClassifyImageRequest`) and PDFKit (`PDFDocument`)
- Files: `kmp/src/iosMain/kotlin/dev/stapler/stelekit/platform/ml/CoreMlImageLabeler.kt`, `kmp/src/iosMain/kotlin/dev/stapler/stelekit/platform/ml/IosPdfTextExtractor.kt`

---

### Epic 2.3: Built-in Pipeline Plugins
**Goal**: Three concrete plugins that wrap the platform ML interfaces — image labeling, image OCR, and PDF text extraction.

---

#### Story 2.3.1: Implement built-in plugins
**As a** developer, **I want** concrete plugin implementations, **so that** the pipeline has default behaviors out-of-the-box.
**Acceptance Criteria**:
- `OnDeviceLabelingPlugin`: `canProcess` returns true for IMAGE; calls `imageLabeler.labelImage()`; stores result as `AssetPipelineResult.Labels`
- `OcrPlugin`: `canProcess` IMAGE; calls `imageLabeler.labelImage()` in OCR mode or a dedicated OCR labeler; stores `OcrText`
- `PdfTextPlugin`: `canProcess` PDF; calls `pdfTextExtractor.extractText()`; stores `OcrText`

##### Task 2.3.1a: Create OnDeviceLabelingPlugin + OcrPlugin (~4 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/OnDeviceLabelingPlugin.kt`
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/OcrPlugin.kt`
- Both take injected `ImageLabeler` constructor parameter
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/OnDeviceLabelingPlugin.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/OcrPlugin.kt`

##### Task 2.3.1b: Create PdfTextPlugin (~3 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/PdfTextPlugin.kt`
- Takes injected `PdfTextExtractor`; `canProcess` checks `asset.mediaType == AssetMediaType.PDF`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/PdfTextPlugin.kt`

##### Task 2.3.1c: Wire plugins into PluginRegistry at platform entry points (~4 min)
- **Concern fix (RepositoryFactory is commonMain — cannot instantiate platform ML impls)**: Platform ML implementations (`MlKitImageLabeler`, `OnnxImageLabeler`, `CoreMlImageLabeler`) live in `androidMain`/`jvmMain`/`iosMain` and CANNOT be referenced from `RepositoryFactory` (commonMain).
- The `PluginRegistry` singleton must be constructed and populated at **platform-specific entry points**, then passed into the app initialization chain:
  - **Android**: `SteleKitApplication.onCreate()` — construct `PluginRegistry()`, register `MlKitImageLabeler()`, `MlKitOcrLabeler()`, `AndroidPdfTextExtractor()` via `OnDeviceLabelingPlugin`, `OcrPlugin`, `PdfTextPlugin`; pass registry to `GraphManager` or equivalent.
  - **JVM Desktop**: `kmp/src/jvmMain/.../desktop/Main.kt` — same pattern with `OnnxImageLabeler`, `JvmPdfTextExtractor`.
  - **iOS**: Swift `AppDelegate` / `@main` — construct `PluginRegistry` in Kotlin/Native init, register `CoreMlImageLabeler`, `IosPdfTextExtractor`.
  - `RepositoryFactory` receives an already-constructed `PluginRegistry` as a constructor parameter (injected from the platform entry point, NOT created internally).
- `PluginRegistry` is then stored on `RepositorySet` or passed directly to `AssetPipelineService`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/SteleKitApplication.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt`

---

### Epic 2.4: Cloud-Optional Enrichment
**Goal**: Optional Google Vision and Claude API enrichment, enabled per-graph, capped at 20 assets/session by default, idempotent.

---

#### Story 2.4.1: Cloud enrichment config + graph settings UI
**As a** user, **I want** to enable cloud enrichment per graph with a configurable API key, **so that** I control costs and data sharing.
**Acceptance Criteria**:
- `CloudEnrichmentConfig` stored in graph settings (plugin_data table or a dedicated settings table)
- Settings dialog shows cloud enrichment toggle + provider selector + API key field + session cap field
- Config is validated before saving (non-empty key required if enabled)

##### Task 2.4.1a: Create CloudEnrichmentConfig model + persistence (~4 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/CloudEnrichmentConfig.kt`
- `data class CloudEnrichmentConfig(provider: CloudProvider, apiKey: String, sessionCap: Int = 20, enabled: Boolean)`
- `enum class CloudProvider { GOOGLE_VISION, CLAUDE }`
- Store/retrieve via `plugin_data` table (existing) with `plugin_id = "cloud_enrichment"`, `entity_type = "graph"`, `entity_uuid = graphId`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/CloudEnrichmentConfig.kt`

##### Task 2.4.1b: Add cloud enrichment UI to SettingsDialog (~4 min)
- In `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialog.kt`, add a "Cloud Enrichment" section
- Toggle + provider dropdown + API key field + session cap field + "Test connection" button
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialog.kt`

---

#### Story 2.4.2: Cloud enrichment plugins
**As a** user, **I want** cloud labels and descriptions merged into asset metadata, **so that** I get richer auto-tags when I opt in.
**Acceptance Criteria**:
- `CloudVisionPlugin` calls Google Vision API; `canProcess` returns true only if config is enabled + provider = GOOGLE_VISION + session cap not exhausted
- `CloudClaudePlugin` calls Anthropic API with `tool_use`; same guard
- Both are additive — raw on-device results are kept; cloud results merged into `autoLabels` + stored in `cloudDescription`
- Idempotency: skip if `ml_tags_source` already set to cloud provider value for this asset

##### Task 2.4.2a: Create CloudVisionPlugin (~5 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/CloudVisionPlugin.kt`
- HTTP call via `ktor-client` (already on classpath); annotate-image API; parse label annotations + text annotations
- Session counter via `AtomicInteger` in plugin instance; reset on plugin re-registration at graph open
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/CloudVisionPlugin.kt`

##### Task 2.4.2b: Create CloudClaudePlugin (~5 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/CloudClaudePlugin.kt`
- Send image as base64 in messages API with a tool definition requesting structured tag output
- Parse `tool_use` response; merge labels into `autoLabels`; store description in `cloudDescription`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/CloudClaudePlugin.kt`

---

### Epic 2.5: Processing Triggers, Backfill, and UI Feedback
**Goal**: Automatic on-import processing, incremental backfill, "Analyze" on-demand button, and processing-state badges in the browser.

---

#### Story 2.5.1: Import trigger and backfill drain
**As a** user, **I want** newly imported files processed automatically in the background, **so that** tags appear within seconds without any manual action.
**Acceptance Criteria**:
- After `AssetIndexService.registerAsset()` succeeds, `AssetPipelineService.processAsset()` is launched as a background job
- Backfill drain processes 10 unprocessed assets at a time, yields between batches, is cancellable
- Backfill is triggered after `GraphLoader.onBulkImportComplete()`

##### Task 2.5.1a: Add post-register pipeline trigger (~3 min)
- In `AssetIndexService.registerAsset()`, after `writeActor.execute { saveAsset(...) }` succeeds, call `pipelineService.scheduleProcess(asset)` which launches `processAsset()` in pipelineService's scope
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/AssetIndexService.kt`

##### Task 2.5.1b: Implement backfill drain in AssetPipelineService (~4 min)
- Add `fun scheduleBackfill(writeActor)` to `AssetPipelineService`
- Drain loop: call `assetRepository.getUnprocessedAssets(limit=10, offset=0)` in a loop, process each, call `yield()`, stop when count returns 0
- Cancel previous backfill job before starting a new one
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/AssetPipelineService.kt`

---

#### Story 2.5.2: Processing state badges in AssetBrowserScreen
**As a** user, **I want** to see which assets have been analyzed and which are pending, **so that** I understand the ML status at a glance.
**Acceptance Criteria**:
- `AssetItemCard` shows a small badge: spinner for PROCESSING, checkmark for DONE, warning icon for FAILED, nothing for PENDING
- "Analyze" button in AssetBrowserScreen's top bar triggers `viewModel.analyzeAll()` → `pipelineService.scheduleBackfill(writeActor)`

##### Task 2.5.2a: Add processing-state badge to AssetItemCard (~3 min)
- Update `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetItemCard.kt`
- `when (asset.mlProcessed)` → render badge icon in bottom-right corner of grid tile or in the status column of list row
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetItemCard.kt`

##### Task 2.5.2b: Add "Analyze" button and ViewModel method (~3 min)
- Add `fun analyzeAll()` to `AssetBrowserViewModel` that calls `pipelineService.scheduleBackfill(writeActor)` in the ViewModel's scope
- Add icon button to `AssetBrowserScreen` TopAppBar
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserViewModel.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserScreen.kt`

---

### Epic 2.6: Phase 2 Tests
**Goal**: Cover plugin dispatch, throttling, cloud plugin mocking, and backfill termination.

---

#### Story 2.6.1: Plugin architecture tests
**As a** developer, **I want** tests for `PluginRegistry` and `AssetPipelineService`, **so that** plugin dispatch and result merging are verified.
**Acceptance Criteria**:
- `PluginRegistryTest`: register multiple plugins; verify only applicable ones are called per asset type
- `AssetPipelineServiceTest`: stub plugins; verify results merged into repository; verify `mlProcessed` flag set

##### Task 2.6.1a: PluginRegistryTest + AssetPipelineServiceTest (~5 min)
- Create `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/PluginRegistryTest.kt`
- Create `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/AssetPipelineServiceTest.kt`
- Use stub `AssetPipelinePlugin` implementations and `InMemoryAssetRepository`
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/PluginRegistryTest.kt`, `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/AssetPipelineServiceTest.kt`

---

#### Story 2.6.2: On-device labeling plugin tests
**As a** developer, **I want** tests for `OnDeviceLabelingPlugin` and `OcrPlugin` using stub `ImageLabeler`, **so that** plugin logic is verified independently of platform ML.
**Acceptance Criteria**:
- Stub returns fixed labels; plugin stores them in result
- `canProcess` returns false for non-IMAGE assets

##### Task 2.6.2a: OnDeviceLabelingPluginTest + OcrPluginTest (~4 min)
- Create `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/OnDeviceLabelingPluginTest.kt`
- Create `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/OcrPluginTest.kt`
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/OnDeviceLabelingPluginTest.kt`, `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/OcrPluginTest.kt`

---

#### Story 2.6.3: Cloud plugin and backfill throttle tests
**As a** developer, **I want** tests for cloud plugins (mocked HTTP) and backfill session cap, **so that** runaway cost and unbounded processing are prevented.
**Acceptance Criteria**:
- `CloudVisionPluginTest`: mock HTTP; verify `canProcess` returns false after session cap
- `BackfillThrottleTest`: 100 unprocessed assets; verify at most 10 processed per batch; verify loop terminates

##### Task 2.6.3a: CloudEnrichmentPluginTest (~4 min)
- Create `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/CloudEnrichmentPluginTest.kt`
- Use mock `HttpClient` (ktor-client mock engine); assert idempotency guard; assert session cap
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/CloudEnrichmentPluginTest.kt`

##### Task 2.6.3b: BackfillThrottleTest (~4 min)
- Create `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/BackfillThrottleTest.kt`
- Seed 100 unprocessed assets into `InMemoryAssetRepository`; run `scheduleBackfill()`; assert batch sizes ≤10; assert loop terminates
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/BackfillThrottleTest.kt`

---

## New Files Summary

### commonMain
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/AssetModels.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/AssetStoragePathResolver.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/MimeTypeDetector.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/AssetIndexService.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/AssetMoveService.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/AssetPipelinePlugin.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/PluginRegistry.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/AssetPipelineService.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/OnDeviceLabelingPlugin.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/OcrPlugin.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/PdfTextPlugin.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/CloudEnrichmentConfig.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/CloudVisionPlugin.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/asset/pipeline/CloudClaudePlugin.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ml/ImageLabeler.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ml/PdfTextExtractor.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ml/MlServiceStubs.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/AssetRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/InMemoryAssetRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightAssetRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserUiState.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserViewModel.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserScreen.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserSearchBar.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetItemCard.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetActionMenu.kt`

### androidMain
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/MlKitImageLabeler.kt`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/AndroidPdfTextExtractor.kt`

### jvmMain
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/ml/OnnxImageLabeler.kt`
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/ml/JvmPdfTextExtractor.kt`

### iosMain
- `kmp/src/iosMain/kotlin/dev/stapler/stelekit/platform/ml/CoreMlImageLabeler.kt`
- `kmp/src/iosMain/kotlin/dev/stapler/stelekit/platform/ml/IosPdfTextExtractor.kt`

### businessTest
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/repository/AssetRepositoryTest.kt`
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/MimeTypeDetectorTest.kt`
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/AssetMediaTypeTest.kt`
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/AssetMoveServiceTest.kt`
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/AssetIndexServiceTest.kt`
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/PluginRegistryTest.kt`
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/AssetPipelineServiceTest.kt`
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/OnDeviceLabelingPluginTest.kt`
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/OcrPluginTest.kt`
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/CloudEnrichmentPluginTest.kt`
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/asset/pipeline/BackfillThrottleTest.kt`

### Modified files
- `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq` — new tables + queries
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt` — two new SqlMigration entries
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt` — new `@DirectSqlWrite` stubs for asset writes
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt` — add `assetRepository` to `RepositorySet`; wire `SqlDelightAssetRepository`; register plugins
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` — hook backfill after bulk import
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` — call `replayPendingMoves()` on graph open
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt` — add `rewriteAssetReference()`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` — add `Screen.AssetBrowser`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` — route `Screen.AssetBrowser`; add sidebar entry
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` — call `registerAsset()` after attach
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/command/Command.kt` — add `OpenAssetBrowser` command
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialog.kt` — cloud enrichment section
- Platform-specific `MediaAttachmentService` implementations (jvmMain + androidMain) — add subfolder routing

---

## New Build Dependencies

```kotlin
// commonMain
implementation("com.squareup.okio:okio:3.17.0")

// jvmMain
implementation("org.apache.pdfbox:pdfbox:3.0.4")
implementation("com.microsoft.onnxruntime:onnxruntime:1.26.0")

// androidMain
implementation("com.tom-roush:pdfbox-android:2.0.27.0")
implementation("com.google.mlkit:image-labeling:17.0.9")
implementation("com.google.mlkit:text-recognition:16.0.1")

// commonTest
testImplementation("com.squareup.okio:okio-fakefilesystem:3.17.0")
```

File to edit: `kmp/build.gradle.kts`
