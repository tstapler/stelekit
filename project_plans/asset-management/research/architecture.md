# Asset Management — Architecture Research

Source files examined:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/` — GraphManager, GraphLoader, DatabaseWriteActor, MigrationRunner, ImageImportService, ImageStoragePathResolver
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/` — PageRepository, DbFlowExtensions, RepositoryFactory, RepositorySet
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/sidecar/` — ImageSidecarManager, ImageSidecarIndexer, ImageSidecarSchema
- `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/service/` — MediaAttachmentService, AttachmentFileNaming
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/coroutines/PlatformDispatcher.kt`

---

## A. SQLDelight Asset Index Table Design

### Columns

The existing `image_annotations` table (already in the schema) is a domain-specific image annotation store tied to blocks and measurement workflows. The new `asset_index` table is a general-purpose asset catalogue covering all media types, not just images with measurement sidecars.

```sql
CREATE TABLE IF NOT EXISTS asset_index (
    uuid             TEXT NOT NULL PRIMARY KEY,       -- UUID v7 (time-ordered)
    file_path        TEXT NOT NULL,                   -- Absolute path inside graph root
    relative_path    TEXT NOT NULL,                   -- Graph-root-relative (e.g. assets/images/foo.jpg)
    media_type       TEXT NOT NULL,                   -- Discriminator: IMAGE|PDF|AUDIO|VIDEO|DOCUMENT|FILE
    subfolder        TEXT NOT NULL DEFAULT 'files',   -- images|pdfs|audio|video|documents|files
    tags             TEXT NOT NULL DEFAULT '[]',      -- JSON array of user tags: ["tag1","tag2"]
    auto_labels      TEXT NOT NULL DEFAULT '[]',      -- JSON array from ML pipeline: ["cat","outdoor"]
    page_uuids       TEXT NOT NULL DEFAULT '[]',      -- JSON array of page UUID strings referencing this asset
    size_bytes       INTEGER NOT NULL DEFAULT 0,
    imported_at_ms   INTEGER NOT NULL,
    ml_processed     INTEGER NOT NULL DEFAULT 0,      -- 0=pending, 1=done (used for batch backfill)
    ml_attempted_at  INTEGER,                         -- epoch ms of last ML attempt (rate limit / retry guard)
    content_hash     TEXT                             -- SHA-256 of file bytes (dedup detection)
);

CREATE INDEX IF NOT EXISTS idx_asset_index_media_type
    ON asset_index(media_type, imported_at_ms DESC);

CREATE INDEX IF NOT EXISTS idx_asset_index_subfolder
    ON asset_index(subfolder, imported_at_ms DESC);

CREATE INDEX IF NOT EXISTS idx_asset_index_ml_processed
    ON asset_index(ml_processed, uuid)
    WHERE ml_processed = 0;                           -- Partial index: only unprocessed rows

CREATE INDEX IF NOT EXISTS idx_asset_index_imported_at
    ON asset_index(imported_at_ms DESC);

CREATE INDEX IF NOT EXISTS idx_asset_index_content_hash
    ON asset_index(content_hash);
```

### Tag Search — JSON vs Normalized Tables

**Decision: JSON strings for tags/autoLabels/pageUuids, with LIKE-based search.**

Rationale based on existing codebase patterns:
- `image_annotations.tags` is already stored as a JSON array (`TEXT NOT NULL DEFAULT '[]'`), using `LIKE '%"tag"%'` for lookup (`selectImageAnnotationsByTag` query in SteleDatabase.sq line 1033).
- SQLite does not have a native array type. Normalized junction tables add write complexity and require actor-serialized multi-table transactions.
- Tag cardinality is low (typically < 20 tags per asset). LIKE on a few hundred thousand rows is fast enough; the `imported_at_ms DESC` index already bounds full-table scans.
- The existing project deliberately avoids complex joins — `selectImageAnnotationsByTag` is the reference pattern.

For `page_uuids`, the same JSON approach applies. The query pattern is a point lookup: "give me all assets for page X." An index on `page_uuids` LIKE is impractical for large graphs, but in practice, a composable can collect via `getAssetsByPageUuid(pageUuid)` which uses the JSON LIKE pattern on a bounded result set. If this becomes a hotspot, a separate `asset_page_links(asset_uuid TEXT, page_uuid TEXT)` junction table can be added via a migration later.

**Auto-labels** follow the same JSON pattern since they are ML-generated and structurally identical to user tags.

### Migration Entry

Add to `MigrationRunner.all` (append at the end; never reorder existing entries):

```kotlin
Migration(
    name = "asset_index_table",
    statements = listOf(
        """
        CREATE TABLE IF NOT EXISTS asset_index (
            uuid             TEXT NOT NULL PRIMARY KEY,
            file_path        TEXT NOT NULL,
            relative_path    TEXT NOT NULL,
            media_type       TEXT NOT NULL,
            subfolder        TEXT NOT NULL DEFAULT 'files',
            tags             TEXT NOT NULL DEFAULT '[]',
            auto_labels      TEXT NOT NULL DEFAULT '[]',
            page_uuids       TEXT NOT NULL DEFAULT '[]',
            size_bytes       INTEGER NOT NULL DEFAULT 0,
            imported_at_ms   INTEGER NOT NULL,
            ml_processed     INTEGER NOT NULL DEFAULT 0,
            ml_attempted_at  INTEGER,
            content_hash     TEXT
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_asset_index_media_type ON asset_index(media_type, imported_at_ms DESC)",
        "CREATE INDEX IF NOT EXISTS idx_asset_index_subfolder ON asset_index(subfolder, imported_at_ms DESC)",
        "CREATE INDEX IF NOT EXISTS idx_asset_index_ml_processed ON asset_index(ml_processed, uuid) WHERE ml_processed = 0",
        "CREATE INDEX IF NOT EXISTS idx_asset_index_imported_at ON asset_index(imported_at_ms DESC)",
        "CREATE INDEX IF NOT EXISTS idx_asset_index_content_hash ON asset_index(content_hash)"
    )
)
```

Also add the table name to `MigrationRunnerSchemaSyncTest` validation (it reads `SteleDatabase.sq` for `IF NOT EXISTS` names and asserts each appears in `MigrationRunner.all`).

The `asset_index` table must **also** appear in `SteleDatabase.sq` with `CREATE TABLE IF NOT EXISTS asset_index (...)` so `SteleDatabase.Schema.create(driver)` creates it on fresh installs.

---

## B. AssetRepository Interface

### Interface — follows PageRepository pattern exactly

```kotlin
/**
 * There is deliberately no getAllAssets() on this interface — see PageRepository KDoc
 * for the unbounded-read OOM rationale. All reads are paginated, filtered, or projected.
 */
interface AssetRepository {
    // --- Point lookups ---
    fun getAssetByUuid(uuid: AssetUuid): Flow<Either<DomainError, AssetEntry?>>

    // --- Paginated browsing ---
    fun getAssets(limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>
    fun getAssetsByMediaType(
        mediaType: MediaType,
        limit: Int,
        offset: Int,
    ): Flow<Either<DomainError, List<AssetEntry>>>
    fun getAssetsBySubfolder(
        subfolder: String,
        limit: Int,
        offset: Int,
    ): Flow<Either<DomainError, List<AssetEntry>>>

    // --- Search ---
    fun searchAssetsByTag(tag: String, limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>
    fun searchAssetsByLabel(label: String, limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>
    fun getAssetsByPageUuid(pageUuid: PageUuid, limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>

    // --- ML backfill drain ---
    fun getUnprocessedAssets(limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>
    suspend fun countUnprocessedAssets(): Either<DomainError, Long>

    // --- Count ---
    fun countAssets(): Flow<Either<DomainError, Long>>

    // --- Writes ---
    @DirectRepositoryWrite
    suspend fun saveAsset(asset: AssetEntry): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun saveAssets(assets: List<AssetEntry>): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun updateTags(uuid: AssetUuid, tags: List<String>): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun updateAutoLabels(uuid: AssetUuid, labels: List<String>): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun markMlProcessed(uuid: AssetUuid): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun updateFilePath(uuid: AssetUuid, newFilePath: String, newRelativePath: String): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun updatePageUuids(uuid: AssetUuid, pageUuids: List<String>): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun deleteAsset(uuid: AssetUuid): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun clear()

    companion object {
        const val BROWSE_PAGE_SIZE = 50
        const val BACKFILL_BATCH_SIZE = 10    // Requirements spec: 10 at a time
        const val SNAPSHOT_BATCH_SIZE = 500
    }
}
```

### SQLDelight Query Design (representative queries to add to SteleDatabase.sq)

```sql
-- Bounded browse
selectAssetsPaginated:
SELECT * FROM asset_index ORDER BY imported_at_ms DESC LIMIT ? OFFSET ?;

selectAssetsByMediaType:
SELECT * FROM asset_index WHERE media_type = ? ORDER BY imported_at_ms DESC LIMIT ? OFFSET ?;

selectAssetsBySubfolder:
SELECT * FROM asset_index WHERE subfolder = ? ORDER BY imported_at_ms DESC LIMIT ? OFFSET ?;

selectAssetByUuid:
SELECT * FROM asset_index WHERE uuid = ?;

-- Tag/label search using the same LIKE pattern as selectImageAnnotationsByTag
selectAssetsByTag:
SELECT * FROM asset_index WHERE tags LIKE '%"' || ? || '"%' ORDER BY imported_at_ms DESC LIMIT ? OFFSET ?;

selectAssetsByAutoLabel:
SELECT * FROM asset_index WHERE auto_labels LIKE '%"' || ? || '"%' ORDER BY imported_at_ms DESC LIMIT ? OFFSET ?;

selectAssetsByPageUuid:
SELECT * FROM asset_index WHERE page_uuids LIKE '%"' || ? || '"%' ORDER BY imported_at_ms DESC LIMIT ? OFFSET ?;

-- ML drain (uses partial index idx_asset_index_ml_processed)
selectUnprocessedAssetsPaginated:
SELECT * FROM asset_index WHERE ml_processed = 0 ORDER BY uuid LIMIT ? OFFSET ?;

countUnprocessedAssets:
SELECT COUNT(*) FROM asset_index WHERE ml_processed = 0;

countAssets:
SELECT COUNT(*) FROM asset_index;

-- Writes
upsertAsset:
INSERT OR REPLACE INTO asset_index (uuid, file_path, relative_path, media_type, subfolder,
    tags, auto_labels, page_uuids, size_bytes, imported_at_ms, ml_processed, ml_attempted_at, content_hash)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

updateAssetTags:
UPDATE asset_index SET tags = ? WHERE uuid = ?;

updateAssetAutoLabels:
UPDATE asset_index SET auto_labels = ?, ml_processed = 1, ml_attempted_at = ? WHERE uuid = ?;

markAssetMlProcessed:
UPDATE asset_index SET ml_processed = 1, ml_attempted_at = ? WHERE uuid = ?;

updateAssetFilePath:
UPDATE asset_index SET file_path = ?, relative_path = ? WHERE uuid = ?;

updateAssetPageUuids:
UPDATE asset_index SET page_uuids = ? WHERE uuid = ?;

deleteAsset:
DELETE FROM asset_index WHERE uuid = ?;
```

### Read Pattern in SqlDelightAssetRepository

Follow `DbFlowExtensions` exactly — `asDbFlowList` / `asDbFlowOrNull` are mandatory:

```kotlin
override fun getAssets(limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>> =
    queries.selectAssetsPaginated(limit.toLong(), offset.toLong())
        .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }
```

Write pattern follows `SqlDelightPageRepository.savePage`:

```kotlin
override suspend fun saveAsset(asset: AssetEntry): Either<DomainError, Unit> =
    withContext(PlatformDispatcher.DB) {
        try {
            queries.upsertAsset(...)
            Unit.right()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left() }
    }
```

All write calls must be routed through `DatabaseWriteActor.execute { ... }` from callers above the repository layer.

### RepositorySet Extension

Add `assetRepository` to `RepositorySet` in `RepositoryFactory.kt`:

```kotlin
data class RepositorySet(
    ...
    val assetRepository: AssetRepository = InMemoryAssetRepository(),
)
```

In `RepositoryFactoryImpl.createRepositorySet()`:

```kotlin
assetRepository = if (backend == GraphBackend.SQLDELIGHT)
    SqlDelightAssetRepository(database)
else InMemoryAssetRepository(),
```

---

## C. AssetPipelinePlugin Architecture

### Interface (commonMain)

```kotlin
/**
 * A processing stage applied to an asset after import.
 * Implementations live in platform source sets and are registered at startup.
 *
 * [processAsset] is called on [PlatformDispatcher.Default] (not DB dispatcher).
 * Implementations must not perform blocking IO — use [PlatformDispatcher.IO] internally.
 */
interface AssetPipelinePlugin {
    /** Human-readable identifier for logging and progress display. */
    val id: String

    /**
     * Returns true if this plugin can handle [asset] (e.g. ImagePlugin returns false for audio).
     */
    fun canProcess(asset: AssetEntry): Boolean

    /**
     * Process [asset] and return updated metadata (tags, labels, etc.).
     * Returns [Either.Left] on failure — the pipeline will log and continue to the next asset.
     * Must not throw — wrap all exceptions in [DomainError].
     */
    suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult>
}

data class AssetPipelineResult(
    val autoLabels: List<String> = emptyList(),
    val tags: List<String> = emptyList(),     // merged with user tags, not replaced
)
```

### DI Approach: Dependency Injection, Not expect/actual

**Rationale:** The existing codebase uses `expect/actual` only for thin cross-cutting concerns like `PlatformDispatcher` and `DriverFactory` — infrastructure primitives with identical call sites across platforms. ML plugins have completely different implementations per platform (different libraries, different APIs, different initialization sequences), which means `expect/actual` would produce one actual per platform that is essentially a full class, not a thin shim.

The preferred approach is **constructor injection via `PluginRegistry`**:

```kotlin
/**
 * Holds the list of plugins available for the current platform.
 * Populated at application startup before GraphManager opens any graph.
 * Immutable after registration — no graph-switch lifecycle needed.
 */
class PluginRegistry {
    private val _plugins = mutableListOf<AssetPipelinePlugin>()
    val plugins: List<AssetPipelinePlugin> get() = _plugins.toList()

    fun register(plugin: AssetPipelinePlugin) {
        _plugins += plugin
    }
}
```

Platform startup wiring (e.g. `Application.onCreate` on Android, `Main.kt` on JVM):

```kotlin
// Android
val registry = PluginRegistry()
registry.register(MlKitImageLabelPlugin())
// JVM  
registry.register(OnnxImageLabelPlugin())
// iOS (called from Swift/AppDelegate)
registry.register(CoreMlImageLabelPlugin())
```

The `PluginRegistry` is passed to `AssetPipelineService` (see below) which is Compose-managed alongside `GraphLoader`/`GraphWriter`.

This mirrors how `MediaAttachmentService` is wired: one interface in `commonMain`, three platform implementations in `androidMain`/`jvmMain`/`iosMain`, injected at the app entry point.

### AssetPipelineService (commonMain)

```kotlin
/**
 * Orchestrates ML processing for a batch of assets.
 * Not graph-aware — receives assets from the caller (AssetBackfillJob or on-import hook).
 *
 * Scope: owns its own [CoroutineScope] internally (SupervisorJob + Default). Never accepts
 * rememberCoroutineScope() — see CLAUDE.md scope ownership rules.
 */
class AssetPipelineService(
    private val registry: PluginRegistry,
    private val assetRepository: AssetRepository,
    private val writeActor: DatabaseWriteActor,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, t -> logger.error("Uncaught in pipeline", t) }
    )

    /**
     * Process [assets] through all registered plugins that [canProcess] them.
     * Writes results to the repository via [writeActor]. Emits progress via [onProgress].
     */
    suspend fun processBatch(
        assets: List<AssetEntry>,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        assets.forEachIndexed { i, asset ->
            val applicablePlugins = registry.plugins.filter { it.canProcess(asset) }
            if (applicablePlugins.isEmpty()) {
                writeActor.execute(DatabaseWriteActor.Priority.LOW) {
                    assetRepository.markMlProcessed(AssetUuid(asset.uuid))
                }
                return@forEachIndexed
            }
            val mergedLabels = mutableListOf<String>()
            val mergedTags = mutableListOf<String>()
            for (plugin in applicablePlugins) {
                plugin.processAsset(asset)
                    .onRight { result ->
                        mergedLabels += result.autoLabels
                        mergedTags += result.tags
                    }
                    .onLeft { err -> logger.warn("Plugin ${plugin.id} failed for ${asset.uuid}: ${err.message}") }
            }
            writeActor.execute(DatabaseWriteActor.Priority.LOW) {
                assetRepository.updateAutoLabels(AssetUuid(asset.uuid), mergedLabels.distinct())
            }
            onProgress(i + 1, assets.size)
        }
    }

    fun shutdown() { scope.cancel() }
}
```

### Integration with GraphManager / RepositorySet

`AssetPipelineService` is **not** added to `RepositorySet` — it is a graph-aware service like `GraphLoader` and `GraphWriter`, wired in the Compose layer (`GraphContent`). It receives the `RepositorySet.assetRepository` and `RepositorySet.writeActor` when the active graph switches.

The backfill job (`AssetBackfillJob`) is a fire-and-forget coroutine launched on `graphScope` inside `GraphManager.switchGraph` — after `_activeRepositorySet.value = repoSet` is set, analogous to how `MigrationRunner` is invoked.

### Backpressure and Cancellation

- Backfill runs with `DatabaseWriteActor.Priority.LOW` so editor saves always preempt it.
- The drain loop mirrors `GraphLoader.indexRemainingPages`: bounded batches of `BACKFILL_BATCH_SIZE = 10`, `attempted` set to skip permanently-failing assets, offset advances past stuck rows.
- Cancellation: `graphScope` is cancelled in `GraphManager.switchGraph` before the new graph launches, which cancels any in-flight backfill.
- The backfill job reference is stored in a `@Volatile var backfillJob: Job?` on `GraphLoader`-equivalent, so `cancelBackgroundWork()` can stop it under memory pressure.

---

## D. Asset Move + Reference Update

### Transaction Boundary Design

An asset move involves three steps that must be treated as a saga, not a single atomic transaction, because they cross process boundaries (filesystem + DB + markdown text):

```
Step 1: File move on disk
Step 2: Markdown reference update in all pages (GraphWriter)
Step 3: Asset index update in DB (AssetRepository)
Optional: Sidecar path update (if asset is an image with a sidecar)
```

The correct ordering — matching `ImageImportService.import()` and `ImageSidecarManager.writeSidecar()` conventions — is **file-system-first, DB-last**. This ensures the sidecar/file is the authoritative record that `ImageSidecarIndexer.rebuildFromSidecars` can recover from if the DB is lost.

### Saga Implementation

Use Arrow's `saga {}` / `transact {}` pattern (already in `GraphWriter.kt` imports: `arrow.resilience.saga`, `arrow.resilience.transact`):

```kotlin
suspend fun moveAsset(
    asset: AssetEntry,
    newSubfolder: String,
    assetRepository: AssetRepository,
    writeActor: DatabaseWriteActor,
    fileSystem: FileSystem,
    graphWriter: GraphWriter,
    blockRepository: BlockRepository,
    graphPath: String,
): Either<DomainError, Unit> = saga {
    val oldPath = asset.filePath
    val newRelative = "assets/$newSubfolder/${oldPath.substringAfterLast('/')}"
    val newAbsolute = "$graphPath/$newRelative"

    // Step 1: Move file — compensate by moving it back
    saga({ fileSystem.renameFile(oldPath, newAbsolute) }) {
        fileSystem.renameFile(newAbsolute, oldPath)
    }

    // Step 2: Update markdown references in all pages — GraphWriter.rewriteAssetReference
    // compensate by reverting the edit (GraphWriter already has undo support via UndoManager)
    saga({ graphWriter.rewriteAssetReference(asset.relativePath, newRelative, graphPath) }) {
        graphWriter.rewriteAssetReference(newRelative, asset.relativePath, graphPath)
    }

    // Step 3: DB update — compensate by restoring old path
    saga({
        writeActor.execute(DatabaseWriteActor.Priority.HIGH) {
            assetRepository.updateFilePath(AssetUuid(asset.uuid), newAbsolute, newRelative)
        }
    }) {
        writeActor.execute(DatabaseWriteActor.Priority.HIGH) {
            assetRepository.updateFilePath(AssetUuid(asset.uuid), oldPath, asset.relativePath)
        }
    }
}.transact()
```

### Interaction with GraphWriter and File Watching

The file watcher (`GraphFileWatcher`) detects changes in `pages/` and `journals/`, not in `assets/`. Moving a file within `assets/` does NOT trigger a file-watch event. However, the markdown reference rewrite (Step 2) touches page files inside `pages/` or `journals/`, which the watcher WILL detect as an external write.

**Mitigation (same pattern as existing GraphWriter saves):**
- Call `fileRegistry.markWrittenByUs(pageFilePath)` (via `onPreWrite` callback) before rewriting each page file.
- This causes `GraphFileWatcher.isOwnWrite(filePath)` to return `true`, suppressing the change from emitting on `externalFileChanges`.

### Rollback Strategy

If Step 2 or Step 3 fails:
- Arrow's `saga.transact()` runs compensating actions in reverse order.
- Step 3 failure: DB was not updated, file is already at new location → compensate by moving file back (Step 1 compensation).
- Step 2 failure: Some pages may be partially rewritten → the rewrite is idempotent (path string replacement), so compensating by running the inverse rewrite restores the original text. However, if only some pages were updated before the failure, the compensation must track which pages succeeded and revert only those.

**Practical simplification:** For the first implementation, rewrite all page references in a single DB transaction (read all block UUIDs containing the old path, update them all at once via `DatabaseWriteActor.execute`), which makes partial-update failure impossible. This sacrifices the ability to do fine-grained compensation but eliminates the partial-update problem entirely.

---

## E. Backfill Integration with GraphLoader

### Where to Hook In

The backfill scan should hook in **after** `onFullyLoaded()` and `onBulkImportComplete?.invoke()`, at the same point that `startWatching(graphPath)` is called. This is Phase 2 completion in `GraphLoader.loadGraph()` (around line 580-586 of GraphLoader.kt).

The backfill runs on the same `graphScope` (from `GraphManager.switchGraph`) as the UUID migration and `MigrationRunner`, so it is automatically cancelled when the graph switches.

### Synchronous vs Background

**Background (emit progress) is required.** Rationale:
- The asset backfill must scan `<graphRoot>/assets/` recursively and stat every file. On a graph with thousands of assets, this is I/O-bound and can take seconds on Android.
- Making it synchronous would block `onFullyLoaded()`, which unblocks the UI. The existing architecture deliberately makes all I/O after `onPhase1Complete()` background so the UI is interactive immediately.
- The same pattern already exists for `indexRemainingPages` (background indexing with progress callback).

### Backfill Implementation

```kotlin
// Inside GraphLoader or a new AssetBackfillService (preferred: separate class)
suspend fun backfillAssets(
    graphPath: String,
    assetRepository: AssetRepository,
    writeActor: DatabaseWriteActor,
    onProgress: (String) -> Unit = {},
) {
    val assetsDir = "$graphPath/assets"
    if (!fileSystem.directoryExists(assetsDir)) return

    // Walk the assets directory recursively, chunking into batches of 100
    // to avoid materializing the full file list (same bounded-read principle as page drain).
    val allFiles = fileSystem.listFilesRecursive(assetsDir)
    allFiles.chunked(100).forEach { chunk ->
        val existingPaths = chunk.map { it }
        // Check which files are already indexed (in-clause lookup, chunked ≤500)
        // ... upsert only new or changed files based on content_hash comparison
        val toUpsert = chunk.mapNotNull { filePath ->
            buildAssetEntry(filePath, graphPath)
        }
        if (toUpsert.isNotEmpty()) {
            writeActor.execute(DatabaseWriteActor.Priority.LOW) {
                assetRepository.saveAssets(toUpsert)
            }
        }
    }
    onProgress("Asset index complete (${allFiles.size} files)")
}
```

**Typed subfolder routing** (during backfill and on-import):

```kotlin
fun mediaTypeFrom(filePath: String): Pair<MediaType, String> {
    val ext = filePath.substringAfterLast('.').lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg" ->
            MediaType.IMAGE to "images"
        "pdf" ->
            MediaType.PDF to "pdfs"
        "mp3", "wav", "ogg", "m4a", "flac", "aac" ->
            MediaType.AUDIO to "audio"
        "mp4", "mov", "avi", "mkv", "webm", "m4v" ->
            MediaType.VIDEO to "video"
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "odt", "csv" ->
            MediaType.DOCUMENT to "documents"
        else ->
            MediaType.FILE to "files"
    }
}
```

### Incremental Backfill (warm start)

On warm start (graph already known, `is_content_loaded = 1` for pages), backfill should be incremental: compare `content_hash` of existing `asset_index` rows against actual file hashes, and only update rows where the hash changed. This mirrors the `selectBlocksHashByPageUuid` / `content_hash` deduplication pattern already used for block writes.

The `countUnprocessedAssets()` method serves the same role as `countUnloadedPages()` for progress reporting and drain-loop termination.

---

## Summary of Key Constraints and Decisions

### Must-Follow (enforced by existing tests)
1. Every `CREATE TABLE IF NOT EXISTS asset_index` in `SteleDatabase.sq` MUST appear in `MigrationRunner.all` — `MigrationRunnerSchemaSyncTest` will fail CI otherwise.
2. All writes go through `DatabaseWriteActor.execute { ... }` — never call `queries.upsertAsset(...)` directly outside `RestrictedDatabaseQueries`.
3. All Flow-backed reads end with `catchDbError()` or use `asDbFlowList` / `asDbFlowOrNull` — `UpgradeResilienceTest` covers this.
4. No unbounded `getAllAssets()` query — bounded reads only (paginated, filtered, or chunked).
5. `AssetPipelineService` owns its own `CoroutineScope` — never accept `rememberCoroutineScope()`.

### Design Decisions
- **Tags/pageUuids stored as JSON arrays** (TEXT columns), same as `image_annotations.tags`. LIKE-based search is sufficient for expected cardinality.
- **DI injection for ML plugins**, not `expect/actual` — mirrors `MediaAttachmentService` pattern.
- **File-system-first write order** for asset moves — sidecar/file is authoritative, DB is a cache.
- **Background backfill only** — synchronous would block UI after `onFullyLoaded()`.
- **Backfill hooks in after `onBulkImportComplete` / `startWatching`** — at Phase 2 completion, on `graphScope`.
- **Arrow Saga for asset move** — already imported in `GraphWriter`, provides rollback semantics.
