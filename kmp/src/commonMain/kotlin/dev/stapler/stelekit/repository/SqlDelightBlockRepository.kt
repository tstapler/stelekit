package dev.stapler.stelekit.repository

import arrow.atomic.AtomicInt
import arrow.atomic.value
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlin.concurrent.Volatile

import dev.stapler.stelekit.cache.LruCache
import dev.stapler.stelekit.cache.SteleLruCache
import dev.stapler.stelekit.cache.RepoCacheConfig
import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.blockTypeFromString
import dev.stapler.stelekit.model.toDiscriminatorString
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.util.ContentHasher
import dev.stapler.stelekit.util.FractionalIndexing
import dev.stapler.stelekit.util.UuidGenerator
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * SQLDelight implementation of BlockRepository.
 * Uses the generated SteleDatabaseQueries for all operations.
 * Optimized with local caching for hierarchical queries to avoid N+1 problem.
 *
 * Updated to use UUID-native storage for all references.
 */
@OptIn(DirectRepositoryWrite::class)
class SqlDelightBlockRepository(
    private val database: SteleDatabase,
    private val driver: SqlDriver? = null,
) : BlockRepository {

    private val queries = database.steleDatabaseQueries
    private val restricted = RestrictedDatabaseQueries(queries, driver)

    @OptIn(DirectSqlWrite::class)
    private suspend fun recomputeBacklinkCount(name: String) =
        restricted.recomputeBacklinkCountForPage(name)

    @OptIn(DirectSqlWrite::class)
    private suspend fun recomputeBacklinkCountFromIndex(name: String) =
        restricted.recomputeBacklinkCountFromIndex(name)

    @OptIn(DirectSqlWrite::class)
    private suspend fun recomputeBacklinkCountsFromIndex(names: Collection<String>) {
        if (names.isEmpty()) return
        restricted.recomputeBacklinkCountsForPages(names)
    }

    /** Inserts all wikilink refs for [blockUuid] into wikilink_references using a single
     *  multi-row INSERT OR IGNORE per chunk. Caller is responsible for deleting stale refs
     *  first when updating existing content. */
    @OptIn(DirectSqlWrite::class)
    private suspend fun addWikilinkRefs(blockUuid: String, pageNames: Set<String>) {
        restricted.insertWikilinkReferencesBatch(blockUuid, pageNames)
    }

    /** Replaces all wikilink refs for [blockUuid] with those derived from [content].
     *  Returns the new set of page names (used to determine which counts need updating). */
    @OptIn(DirectSqlWrite::class)
    private suspend fun replaceWikilinkRefs(blockUuid: String, content: String): Set<String> {
        val pageNames = extractWikilinks(content)
        restricted.deleteWikilinkReferencesForBlock(blockUuid)
        restricted.insertWikilinkReferencesBatch(blockUuid, pageNames)
        return pageNames
    }

    /** Set after construction by RepositoryFactory. */
    @Volatile var histogramWriter: dev.stapler.stelekit.performance.HistogramWriter? = null
    private val _pendingBlockReads = AtomicInt(0)

    private val cacheConfig = RepoCacheConfig.fromPlatform()

    // Block LRU: 60% of platform cache budget.
    // Stores the already-converted Block domain model so cache hits need no further transformation.
    // Weigher: 300 bytes fixed overhead + 2 bytes/char of content (JVM compact strings are 1 byte/char
    // for Latin-1, but we use 2 to be conservative and account for object header + field refs).
    private val blockCache = LruCache<String, Block>(
        maxWeight = cacheConfig.blockCacheBytes,
        weigher = { _, b -> 300L + b.content.length * 2L }
    )
    // Hierarchy and ancestors caches are bounded by entry count (lists — size varies too much to weigh accurately).
    // HierarchyCacheEntry bundles the TTL timestamp with the cached list under the same LruCache Mutex,
    // eliminating the separate mutableMapOf<String, Long> that was a data race on Dispatchers.IO threads.
    private data class HierarchyCacheEntry(val blocks: List<BlockWithDepth>, val cachedAtMs: Long)
    private val hierarchyCache = LruCache<String, HierarchyCacheEntry>(maxWeight = 500L)
    private val ancestorsCache = LruCache<String, List<Block>>(maxWeight = 500L)

    // Reverse index: pageUuid → set of rootBlockUuids stored in hierarchyCache for that page.
    // Allows targeted per-page eviction without invalidating unrelated pages.
    // Protected by its own Mutex because put/evict can run on concurrent Dispatchers.IO threads.
    private val hierarchyIndexMutex = Mutex()
    private val hierarchyPageIndex = mutableMapOf<String, MutableSet<String>>()

    private val hierarchyTtlMs = 120_000L // 2 minutes

    override fun getBlockByUuid(uuid: BlockUuid): Flow<Either<DomainError, Block?>> =
        queries.selectBlockByUuid(uuid.value)
            .asDbFlowOrNull(PlatformDispatcher.DB) { row ->
                row.toBlockModel().also { blockCache.put(it.uuid.value, it) }
            }

    override fun getBlockChildren(blockUuid: BlockUuid): Flow<Either<DomainError, List<Block>>> =
        queries.selectBlockChildren(blockUuid.value, Long.MAX_VALUE, 0L)
            .asDbFlowList(PlatformDispatcher.DB) { row ->
                row.toBlockModel().also { blockCache.put(it.uuid.value, it) }
            }

    override fun getBlockHierarchy(rootUuid: BlockUuid): Flow<Either<DomainError, List<BlockWithDepth>>> = flow {
        try {
            val entry = hierarchyCache.get(rootUuid.value)
            if (entry != null && !isHierarchyCacheExpired(entry)) {
                emit(entry.blocks.right())
                return@flow
            }

            val rows = queries.selectBlockHierarchyRecursive(rootUuid.value).executeAsList()
            val resultList = rows.map { row ->
                val block = row.toBlockModel()
                blockCache.put(block.uuid.value, block)
                BlockWithDepth(block, row.depth.toInt())
            }

            hierarchyCache.put(rootUuid.value, HierarchyCacheEntry(resultList, Clock.System.now().toEpochMilliseconds()))
            val pageUuid = resultList.firstOrNull()?.block?.pageUuid
            if (pageUuid != null) {
                hierarchyIndexMutex.withLock {
                    val set = hierarchyPageIndex.getOrPut(pageUuid.value) { mutableSetOf() }
                    set.removeAll { it != rootUuid.value && !hierarchyCache.containsKey(it) }
                    set.add(rootUuid.value)
                }
            }
            emit(resultList.right())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getBlockAncestors(blockUuid: BlockUuid): Flow<Either<DomainError, List<Block>>> = flow {
        try {
            val cached = ancestorsCache.get(blockUuid.value)
            if (cached != null) {
                emit(cached.right())
                return@flow
            }

            val row = queries.selectBlockByUuid(blockUuid.value).executeAsOneOrNull()
            if (row == null) {
                emit(emptyList<Block>().right())
            } else {
                val ancestors = mutableListOf<Block>()
                var currentParentUuid: String? = row.parent_uuid
                while (currentParentUuid != null) {
                    val parent = blockCache.get(currentParentUuid)
                        ?: queries.selectBlockByUuid(currentParentUuid).executeAsOneOrNull()?.toBlockModel()
                    if (parent != null) {
                        blockCache.put(parent.uuid.value, parent)
                        ancestors.add(parent)
                        currentParentUuid = parent.parentUuid
                    } else {
                        break
                    }
                }
                val result = ancestors.reversed()
                ancestorsCache.put(blockUuid.value, result)
                emit(result.right())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getBlockParent(blockUuid: BlockUuid): Flow<Either<DomainError, Block?>> = flow {
        try {
            val block = queries.selectBlockByUuid(blockUuid.value).executeAsOneOrNull()
            val parentUuid = block?.parent_uuid
            if (block == null || parentUuid == null) {
                emit(null.right())
            } else {
                val parent = queries.selectBlockByUuid(parentUuid).executeAsOneOrNull()
                emit(parent?.toBlockModel().right())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getBlockSiblings(blockUuid: BlockUuid): Flow<Either<DomainError, List<Block>>> = flow {
        try {
            val block = queries.selectBlockByUuid(blockUuid.value).executeAsOneOrNull()
            if (block == null) {
                emit(emptyList<Block>().right())
            } else {
                val siblings = queries.selectBlockSiblings(
                    uuid = block.uuid,
                    uuid_ = block.uuid,
                    uuid__ = block.uuid
                )
                    .executeAsList()
                    .map { it.toBlockModel() }
                emit(siblings.right())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getBlocksForPage(pageUuid: PageUuid): Flow<Either<DomainError, List<Block>>> {
        val depth = _pendingBlockReads.incrementAndGet()
        return queries.selectBlocksByPageUuidUnpaginated(pageUuid.value)
            .asFlow()
            .onStart { histogramWriter?.record("db.read_queue_depth", depth.toLong()) }
            .mapToList(PlatformDispatcher.DB)
            .conflate()
            .onCompletion { _pendingBlockReads.decrementAndGet() }
            .map { list -> list.map { it.toBlockModel() }.right() }
            .catchDbError()
    }

    override suspend fun getBlocksByUuids(uuids: List<BlockUuid>): Either<DomainError, List<Block>> =
        withContext(PlatformDispatcher.DB) {
            if (uuids.isEmpty()) return@withContext emptyList<Block>().right()
            try {
                // Chunk to stay below SQLite's per-statement bind-variable limit (999 on
                // Android API < 30 / SQLite < 3.32). 500 is a safe ceiling that also keeps
                // each round-trip small — a 1000-block page issues two queries instead of one
                // massive IN list, still far fewer than the old N individual lookups.
                val blocks = uuids.map { it.value }.chunked(BATCH_UUID_CHUNK_SIZE).flatMap { chunk ->
                    queries.selectBlocksByUuids(chunk).executeAsList()
                }.map { it.toBlockModel() }
                blocks.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    override suspend fun saveBlocks(blocks: List<Block>): Either<DomainError, Unit> {
        if (blocks.isEmpty()) return Unit.right()
        return withContext(PlatformDispatcher.DB) {
            try {
                ftsAutomergeOff()
                // Single transaction for all block inserts — fewer fsyncs, atomic on-disk state.
                // Reads are never blocked because ReadWriteRouterDriver routes them to a separate
                // WAL read connection that is independent of this write transaction.
                queries.transaction {
                    blocks.forEach { block -> insertBlockRow(block) }
                }
                // Wikilink pass: separate transaction to keep the block-insert transaction tight.
                // Chunked to stay below SQLite's per-statement bind-variable limit (999 on API < 30).
                for (chunk in blocks.chunked(WRITE_CHUNK_SIZE)) {
                    queries.transaction {
                        chunk.forEach { block ->
                            val pageNames = extractWikilinks(block.content)
                            for (name in pageNames) {
                                @OptIn(DirectSqlWrite::class)
                                restricted.insertWikilinkReference(block.uuid.value, name)
                            }
                        }
                    }
                }
                // ftsMerge() is intentionally NOT called here — merge=-200 on a large index adds
                // hundreds of ms. Callers do bulk compactFtsIndex() once per session; per-save
                // compaction is handled by automerge=8.
                ftsAutomergeDefault()
                Unit.right()
            } catch (e: CancellationException) {
                runCatching { ftsAutomergeDefault() }
                throw e
            } catch (e: Exception) {
                runCatching { ftsAutomergeDefault() }
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override suspend fun saveBlocksDiff(toInsert: List<Block>, toUpdate: List<Block>): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        if (toInsert.isEmpty() && toUpdate.isEmpty()) return@withContext Unit.right()
        try {
            // toInsert contract: these UUIDs must be genuinely absent from the blocks table.
            // INSERT OR REPLACE is used here (via insertBlock) — if a UUID already exists, it
            // fires blocks_ad+blocks_ai (double FTS trigger) rather than blocks_au, negating the
            // FTS optimization. This is safe because:
            // 1. DatabaseWriteActor serializes all writes — no concurrent insert races from this actor.
            // 2. The diff is computed from getBlocksForPage() immediately before dispatch, so
            //    toInsert reflects the current DB state at diff-computation time.
            // A future improvement: use INSERT OR IGNORE and fall back to UPDATE on affected rows.
            toInsert.chunked(WRITE_CHUNK_SIZE).forEach { chunk ->
                queries.transaction {
                    chunk.forEach { block -> insertBlockRow(block) }
                }
            }
            // UPDATE instead of INSERT OR REPLACE so the blocks_au trigger fires (AFTER UPDATE OF
            // content) rather than blocks_ad+blocks_ai. blocks_au only fires when content is in the
            // SET clause, and does not cascade-delete child blocks the way REPLACE's implicit
            // DELETE step does.
            toUpdate.chunked(WRITE_CHUNK_SIZE).forEach { chunk ->
                queries.transaction {
                    chunk.forEach { block ->
                        queries.updateBlockForSave(
                            block.pageUuid.value,
                            block.parentUuid,
                            block.leftUuid,
                            block.content,
                            block.level.toLong(),
                            block.position,
                            block.updatedAt.toEpochMilliseconds(),
                            block.properties.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { null },
                            block.version,
                            block.contentHash ?: ContentHasher.sha256ForContent(block.content),
                            block.blockType.toDiscriminatorString(),
                            block.uuid.value,
                        )
                    }
                }
            }
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun insertBlockRow(block: Block) {
        queries.insertBlock(
            block.uuid.value,
            block.pageUuid.value,
            block.parentUuid,
            block.leftUuid,
            block.content,
            block.level.toLong(),
            block.position,
            block.createdAt.toEpochMilliseconds(),
            block.updatedAt.toEpochMilliseconds(),
            block.properties.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { null },
            block.version,
            block.contentHash ?: ContentHasher.sha256ForContent(block.content),
            block.blockType.toDiscriminatorString(),
        )
    }

    // FTS5 special-command INSERTs cannot be expressed in SQLDelight .sq files (the parser
    // rejects inserting into a virtual table's config pseudo-column). Execute via raw driver.
    // driver is null only in unit tests that construct the repo directly without a driver arg.
    // All three helpers are best-effort: an exception here must never abort saveBlocks —
    // the worst outcome is that automerge isn't controlled (old behavior), not data loss.
    private fun ftsAutomergeOff() = runCatching { driver?.execute(null, "INSERT INTO blocks_fts(blocks_fts) VALUES('automerge=0')", 0) }
    private fun ftsAutomergeDefault() = runCatching { driver?.execute(null, "INSERT INTO blocks_fts(blocks_fts) VALUES('automerge=8')", 0) }
    private fun ftsMerge() = runCatching { driver?.execute(null, "INSERT INTO blocks_fts(blocks_fts) VALUES('merge=-200')", 0) }

    /** Called once at DB open to restore FTS5 automerge in case a prior session was killed
     *  between ftsAutomergeOff() and ftsAutomergeDefault(). Setting automerge=8 when it is
     *  already 8 is a no-op in SQLite FTS5. merge=-200 is intentionally NOT called here —
     *  full index compaction at every startup adds hundreds of ms on large graphs. */
    fun ftsStartupHeal() {
        runCatching { driver?.execute(null, "INSERT INTO blocks_fts(blocks_fts) VALUES('automerge=8')", 0) }
    }

    override suspend fun walCheckpoint(): Unit = withContext(PlatformDispatcher.DB) {
        try {
            queries.pragmaWalCheckpointTruncate()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Non-critical — WAL will be checkpointed automatically on next DB open
        }
    }

    override suspend fun compactFtsIndex(): Unit = withContext(PlatformDispatcher.DB) {
        ftsMerge()
    }

    override suspend fun saveBlocksUpdate(blocks: List<Block>): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            blocks.chunked(WRITE_CHUNK_SIZE).forEach { chunk ->
                queries.transaction {
                    chunk.forEach { block ->
                        queries.updateBlockFull(
                            block.pageUuid.value,
                            block.parentUuid,
                            block.leftUuid,
                            block.content,
                            block.level.toLong(),
                            block.position,
                            block.updatedAt.toEpochMilliseconds(),
                            block.properties.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { null },
                            block.contentHash ?: ContentHasher.sha256ForContent(block.content),
                            block.blockType.toDiscriminatorString(),
                            block.uuid.value,
                        )
                    }
                }
            }
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun saveBlocksAtomicWithChainRepair(
        toInsert: List<Block>,
        chainRepair: List<Block>,
    ): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        if (toInsert.isEmpty() && chainRepair.isEmpty()) return@withContext Unit.right()
        try {
            ftsAutomergeOff()
            queries.transaction {
                toInsert.forEach { block -> insertBlockRow(block) }
                chainRepair.forEach { block ->
                    queries.updateBlockFull(
                        block.pageUuid.value, block.parentUuid, block.leftUuid,
                        block.content, block.level.toLong(), block.position,
                        block.updatedAt.toEpochMilliseconds(),
                        block.properties.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { null },
                        block.contentHash ?: ContentHasher.sha256ForContent(block.content),
                        block.blockType.toDiscriminatorString(),
                        block.uuid.value,
                    )
                }
            }
            // Wikilink pass: separate transaction to keep the block-insert transaction tight.
            // Only iterate over toInsert — chainRepair updates existing blocks whose wikilinks don't change.
            for (chunk in toInsert.chunked(WRITE_CHUNK_SIZE)) {
                queries.transaction {
                    chunk.forEach { block ->
                        val pageNames = extractWikilinks(block.content)
                        for (name in pageNames) {
                            @OptIn(DirectSqlWrite::class)
                            restricted.insertWikilinkReference(block.uuid.value, name)
                        }
                    }
                }
            }
            ftsAutomergeDefault()
            Unit.right()
        } catch (e: CancellationException) {
            runCatching { ftsAutomergeDefault() }
            throw e
        } catch (e: Exception) {
            runCatching { ftsAutomergeDefault() }
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun saveBlock(block: Block): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            queries.insertBlock(
                block.uuid.value,
                block.pageUuid.value,
                block.parentUuid,
                block.leftUuid,
                block.content,
                block.level.toLong(),
                block.position,
                block.createdAt.toEpochMilliseconds(),
                block.updatedAt.toEpochMilliseconds(),
                block.properties.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { null },
                block.version,
                block.contentHash ?: ContentHasher.sha256ForContent(block.content),
                block.blockType.toDiscriminatorString()
            )
            val pageNames = extractWikilinks(block.content)
            addWikilinkRefs(block.uuid.value, pageNames)
            recomputeBacklinkCountsFromIndex(pageNames)
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun updateBlockContentOnly(blockUuid: BlockUuid, content: String): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                // Read old page names from the index (O(1) lookup) — no content extraction needed.
                val oldPageNames = queries.selectWikilinkPageNamesForBlock(blockUuid.value).executeAsList().toSet()
                queries.updateBlockContent(content, Clock.System.now().toEpochMilliseconds(), ContentHasher.sha256ForContent(content), blockUuid.value)
                blockCache.remove(blockUuid.value)
                // Replace all wikilink refs and recompute counts only for changed pages.
                val newPageNames = replaceWikilinkRefs(blockUuid.value, content)
                recomputeBacklinkCountsFromIndex(oldPageNames + newPageNames)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    override suspend fun updateBlockContentsForRename(
        updates: List<Pair<BlockUuid, String>>,
        oldPageName: String,
        newPageName: String,
    ): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val now = Clock.System.now().toEpochMilliseconds()
            queries.transaction {
                for ((uuid, content) in updates) {
                    queries.updateBlockContent(content, now, ContentHasher.sha256ForContent(content), uuid.value)
                    blockCache.remove(uuid.value)
                }
            }
            // Move all wikilink refs from oldPageName to newPageName in one UPDATE.
            // UPDATE OR IGNORE skips rows where (block_uuid, newPageName) already exists
            // (blocks that referenced both pages); leftover oldPageName rows are cleaned up below.
            @OptIn(DirectSqlWrite::class)
            restricted.updateWikilinkPageNameForRename(newName = newPageName, oldName = oldPageName)
            // Clean up any ignored rows (blocks that had both [[oldPage]] and [[newPage]]).
            @OptIn(DirectSqlWrite::class)
            restricted.deleteWikilinkReferencesForPageName(oldPageName)
            // Recompute both counts from the index — no LIKE scan needed.
            recomputeBacklinkCountsFromIndex(listOf(oldPageName, newPageName))
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun updateBlockPropertiesOnly(blockUuid: BlockUuid, properties: Map<String, String>): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                val serialized = properties.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { null }
                queries.updateBlockProperties(serialized, blockUuid.value)
                blockCache.remove(blockUuid.value)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    override suspend fun deleteBlock(blockUuid: BlockUuid, deleteChildren: Boolean): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val block = queries.selectBlockByUuid(blockUuid.value).executeAsOneOrNull()
            if (block != null) {
                if (deleteChildren) {
                    // BFS to collect all descendant UUIDs. Wikilink collection is deferred to a single
                    // batch query below — replaces per-node selectWikilinkPageNamesForBlock in the loop.
                    val uuidsToDelete = mutableListOf<String>(block.uuid)
                    var index = 0
                    while (index < uuidsToDelete.size) {
                        queries.selectBlockChildren(uuidsToDelete[index], Long.MAX_VALUE, 0L).executeAsList()
                            .forEach { child -> uuidsToDelete.add(child.uuid) }
                        index++
                    }
                    // Collect wikilink pages for all affected blocks in one IN-clause batch.
                    val wikilinkPages = uuidsToDelete.chunked(BATCH_UUID_CHUNK_SIZE).flatMap { chunk ->
                        queries.selectWikilinkPageNamesForBlocks(chunk).executeAsList()
                    }.toSet()

                    // Chain repair before deletion — use firstOrNull because duplicate
                    // left_uuid values indicate data corruption; we repair what we can.
                    val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsList().firstOrNull()
                    if (nextSibling != null) {
                        queries.updateBlockLeftUuid(block.left_uuid, nextSibling.uuid)
                    }
                    // Deletion cascades to wikilink_references automatically.
                    uuidsToDelete.forEach { queries.deleteBlockByUuid(it) }
                    recomputeBacklinkCountsFromIndex(wikilinkPages)
                } else {
                    // Collect affected page names from index BEFORE deletion (CASCADE removes refs).
                    val wikilinkPages = queries.selectWikilinkPageNamesForBlock(block.uuid).executeAsList().toSet()
                    // Chain repair before deletion
                    val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsList().firstOrNull()
                    if (nextSibling != null) {
                        queries.updateBlockLeftUuid(block.left_uuid, nextSibling.uuid)
                    }
                    queries.deleteBlockByUuid(block.uuid)
                    recomputeBacklinkCountsFromIndex(wikilinkPages)
                }
            }
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun deleteBulk(blockUuids: List<BlockUuid>, deleteChildren: Boolean): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        if (blockUuids.isEmpty()) return@withContext Unit.right()
        try {
            val wikilinkPages = mutableSetOf<String>()
            queries.transaction {
                // Process in chunks to avoid materializing all blocks at once (peak memory = one chunk).
                blockUuids.map { it.value }.chunked(BATCH_UUID_CHUNK_SIZE).forEach { chunkValues ->
                    val blocksByUuid = queries.selectBlocksByUuids(chunkValues).executeAsList().associateBy { it.uuid }
                    chunkValues.forEach { uuidValue ->
                        val block = blocksByUuid[uuidValue] ?: return@forEach
                        if (deleteChildren) {
                            // BFS to collect all descendant UUIDs. Wikilink collection is deferred to a
                            // batch query below — replaces per-node selectWikilinkPageNamesForBlock calls.
                            val uuidsToDelete = mutableListOf(block.uuid)
                            var index = 0
                            while (index < uuidsToDelete.size) {
                                queries.selectBlockChildren(uuidsToDelete[index], Long.MAX_VALUE, 0L).executeAsList()
                                    .forEach { child -> uuidsToDelete.add(child.uuid) }
                                index++
                            }
                            // Collect wikilink pages for the whole subtree in one IN-clause batch.
                            uuidsToDelete.chunked(BATCH_UUID_CHUNK_SIZE).forEach { subtreeChunk ->
                                wikilinkPages.addAll(queries.selectWikilinkPageNamesForBlocks(subtreeChunk).executeAsList())
                            }
                            // Chain repair for the top-level block being deleted.
                            // Re-read left_uuid live: prior iterations in this batch may have updated a
                            // sibling's left_uuid, making the pre-fetched blocksByUuid entry stale.
                            val liveLeftUuid = queries.selectBlockByUuid(block.uuid).executeAsOneOrNull()?.left_uuid
                                ?: block.left_uuid
                            val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsOneOrNull()
                            if (nextSibling != null) {
                                queries.updateBlockLeftUuid(liveLeftUuid, nextSibling.uuid)
                            }
                            uuidsToDelete.forEach { queries.deleteBlockByUuid(it) }
                        } else {
                            // Collect affected page names from index BEFORE deletion (CASCADE removes refs).
                            wikilinkPages.addAll(queries.selectWikilinkPageNamesForBlock(block.uuid).executeAsList())
                            // Chain repair before deletion. Re-read left_uuid live: prior iterations
                            // may have updated a sibling's left_uuid, making the pre-fetched map stale.
                            val liveLeftUuid = queries.selectBlockByUuid(block.uuid).executeAsOneOrNull()?.left_uuid
                                ?: block.left_uuid
                            val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsOneOrNull()
                            if (nextSibling != null) {
                                queries.updateBlockLeftUuid(liveLeftUuid, nextSibling.uuid)
                            }
                            queries.deleteBlockByUuid(block.uuid)
                        }
                    }
                }
            }
            recomputeBacklinkCountsFromIndex(wikilinkPages)
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun moveBlock(
        blockUuid: BlockUuid,
        newParentUuid: BlockUuid?,
        newPosition: String
    ): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            queries.transaction {
                val block = queries.selectBlockByUuid(blockUuid.value).executeAsOneOrNull() ?: return@transaction

                // 1. Repair OLD chain: the block that followed us now follows our old left sibling
                val blockFollowingOld = queries.selectBlockByLeftUuid(block.uuid).executeAsOneOrNull()
                if (blockFollowingOld != null) {
                    queries.updateBlockLeftUuid(block.left_uuid, blockFollowingOld.uuid)
                }

                // 2. Resolve NEW parent and level
                val newParent = newParentUuid?.let { queries.selectBlockByUuid(it.value).executeAsOneOrNull() }
                val newParentUuidResolved = newParent?.uuid
                val newLevel = (newParent?.level ?: -1L) + 1L

                // 3. Find NEW left sibling: the sibling whose position is largest but < newPosition
                val siblings = if (newParentUuidResolved == null) {
                    queries.selectRootBlocksByPageUuidOrdered(block.page_uuid).executeAsList()
                } else {
                    queries.selectBlocksByParentUuidOrdered(newParentUuidResolved).executeAsList()
                }
                val otherSiblings = siblings.filter { it.uuid != block.uuid }.sortedBy { it.position }
                val leftSibling = otherSiblings.lastOrNull { it.position < newPosition }
                val newLeftUuid = leftSibling?.uuid ?: (newParentUuidResolved ?: block.page_uuid)

                // 4. Repair NEW chain: the block that will now follow us must point to us
                val rightSibling = otherSiblings.firstOrNull { it.position >= newPosition }
                if (rightSibling != null) {
                    queries.updateBlockLeftUuid(block.uuid, rightSibling.uuid)
                }

                // 5. Update block hierarchy
                queries.updateBlockHierarchy(
                    newParentUuidResolved,
                    newLeftUuid,
                    newPosition,
                    newLevel,
                    block.uuid
                )
            }
            
            // Invalidate caches
            blockCache.remove(blockUuid.value)
            hierarchyCache.invalidateAll()
            ancestorsCache.invalidateAll()
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun indentBlock(blockUuid: BlockUuid): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            queries.transaction {
                val block = queries.selectBlockByUuid(blockUuid.value).executeAsOneOrNull()
                    ?: return@transaction
                
                // 1. New parent is the previous sibling.
                val prevSibling = block.left_uuid?.let { queries.selectBlockByUuid(it).executeAsOneOrNull() }
                if (prevSibling == null || prevSibling.parent_uuid != block.parent_uuid) {
                    return@transaction // No previous sibling at the same level, cannot indent
                }
                
                // 3. Chain Repair: The block that was to the right of the moved block 
                // must have its leftUuid updated to the moved block's old leftUuid.
                val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsOneOrNull()
                if (nextSibling != null) {
                    queries.updateBlockLeftUuid(block.left_uuid, nextSibling.uuid)
                }
                
                // 3. New hierarchy calculation
                // New parent is prevSibling.
                val lastChildOfNewParent = queries.selectLastChild(prevSibling.uuid).executeAsOneOrNull()
                val newLeftUuid = lastChildOfNewParent?.uuid ?: prevSibling.uuid
                val newPosition = FractionalIndexing.generateKeyBetween(lastChildOfNewParent?.position, null)
                val newLevel = block.level + 1L
                
                // Update current block hierarchy in one shot
                queries.updateBlockHierarchy(prevSibling.uuid, newLeftUuid, newPosition, newLevel, block.uuid)
            }

            hierarchyCache.invalidateAll()
            ancestorsCache.invalidateAll()
            blockCache.invalidateAll()
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun outdentBlock(blockUuid: BlockUuid): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            queries.transaction {
                val block = queries.selectBlockByUuid(blockUuid.value).executeAsOneOrNull()
                    ?: return@transaction
                
                val currentParentUuid = block.parent_uuid ?: return@transaction // Already at root
                val currentParent = queries.selectBlockByUuid(currentParentUuid).executeAsOneOrNull()
                    ?: return@transaction
                
                // 1. New parent is the grandparent.
                val grandParentUuid = currentParent.parent_uuid
                
                // 3. Chain Repair: The block that was to the right of the moved block 
                // must have its leftUuid updated to the moved block's old leftUuid.
                val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsOneOrNull()
                if (nextSibling != null) {
                    queries.updateBlockLeftUuid(block.left_uuid, nextSibling.uuid)
                }
                
                // 3. New hierarchy calculation: New leftUuid is the old parent's UUID.
                val newLeftUuid = currentParent.uuid
                // Place immediately after the parent using fractional indexing (no sibling shifting).
                val blockFollowingOldParent = queries.selectBlockByLeftUuid(currentParent.uuid).executeAsOneOrNull()
                val newPosition = FractionalIndexing.generateKeyBetween(
                    currentParent.position,
                    blockFollowingOldParent?.position
                )
                val newLevel = block.level - 1L

                // Repair new sibling chain: the block that followed parent now follows us.
                if (blockFollowingOldParent != null) {
                    queries.updateBlockLeftUuid(block.uuid, blockFollowingOldParent.uuid)
                }

                // Update current block hierarchy in one shot
                queries.updateBlockHierarchy(grandParentUuid, newLeftUuid, newPosition, newLevel, block.uuid)
            }

            hierarchyCache.invalidateAll()
            ancestorsCache.invalidateAll()
            blockCache.invalidateAll()
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun moveBlockUp(blockUuid: BlockUuid): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val block = queries.selectBlockByUuid(blockUuid.value).executeAsOneOrNull()
                ?: return@withContext Unit.right()

            // Use left_uuid linked-list to find adjacent siblings in O(1) queries —
            // avoids loading all N siblings just to locate the predecessor and successor.
            // block.left_uuid is a page/parent UUID sentinel when block is first; selectBlockByUuid
            // returns null (pages not in blocks table) or a different-level block — both filtered out.
            val prevSibling = block.left_uuid
                ?.let { queries.selectBlockByUuid(it).executeAsOneOrNull() }
                ?.takeIf { it.parent_uuid == block.parent_uuid }
                ?: return@withContext Unit.right() // block is first in its sibling list
            val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsList()
                .firstOrNull { it.parent_uuid == block.parent_uuid }

            queries.transaction {
                // Swap positions and leftUuids
                // Current block (B) takes previous sibling's (A) leftUuid and position
                queries.updateBlockHierarchy(block.parent_uuid, prevSibling.left_uuid, prevSibling.position, block.level.toLong(), block.uuid)

                // Previous sibling (A) now follows current block (B)
                queries.updateBlockHierarchy(prevSibling.parent_uuid, block.uuid, block.position, prevSibling.level.toLong(), prevSibling.uuid)

                // If there was a next sibling (C) following B, it now follows A
                if (nextSibling != null) {
                    queries.updateBlockLeftUuid(prevSibling.uuid, nextSibling.uuid)
                }
            }

            blockCache.remove(block.uuid)
            blockCache.remove(prevSibling.uuid)
            nextSibling?.let { blockCache.remove(it.uuid) }
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun moveBlockDown(blockUuid: BlockUuid): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val block = queries.selectBlockByUuid(blockUuid.value).executeAsOneOrNull()
                ?: return@withContext Unit.right()

            // Use left_uuid linked-list to find adjacent siblings in O(1) queries —
            // avoids loading all N siblings just to locate the successor and the block after it.
            val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsList()
                .firstOrNull { it.parent_uuid == block.parent_uuid }
                ?: return@withContext Unit.right() // block is last in its sibling list
            val afterNextSibling = queries.selectBlockByLeftUuid(nextSibling.uuid).executeAsList()
                .firstOrNull { it.parent_uuid == block.parent_uuid }

            queries.transaction {
                // Swap positions and leftUuids
                // Next sibling (B) takes current block's (A) leftUuid and position
                queries.updateBlockHierarchy(nextSibling.parent_uuid, block.left_uuid, block.position, nextSibling.level.toLong(), nextSibling.uuid)

                // Current block (A) now follows next sibling (B)
                queries.updateBlockHierarchy(block.parent_uuid, nextSibling.uuid, nextSibling.position, block.level.toLong(), block.uuid)

                // If there was a block (C) following B, it now follows A
                if (afterNextSibling != null) {
                    queries.updateBlockLeftUuid(block.uuid, afterNextSibling.uuid)
                }
            }

            blockCache.remove(block.uuid)
            blockCache.remove(nextSibling.uuid)
            afterNextSibling?.let { blockCache.remove(it.uuid) }
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun mergeBlocks(
        blockUuid: BlockUuid,
        nextBlockUuid: BlockUuid,
        separator: String
    ): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            queries.transaction {
                val blockA = queries.selectBlockByUuid(blockUuid.value).executeAsOneOrNull()
                    ?: return@transaction
                val blockB = queries.selectBlockByUuid(nextBlockUuid.value).executeAsOneOrNull()
                    ?: return@transaction
                
                // 1. Update content of block A
                val mergedContent = blockA.content + separator + blockB.content
                queries.updateBlockContent(
                    mergedContent,
                    Clock.System.now().toEpochMilliseconds(),
                    ContentHasher.sha256ForContent(mergedContent),
                    blockA.uuid
                )
                
                // 2. Reparent all children of block B to block A
                val childrenOfB = queries.selectBlocksByParentUuidOrdered(blockB.uuid).executeAsList()
                if (childrenOfB.isNotEmpty()) {
                    // Query selectLastChild once before the loop and maintain position/leftUuid locally —
                    // replaces N individual selectLastChild calls (one per child of B).
                    val initialLastChild = queries.selectLastChild(blockA.uuid).executeAsOneOrNull()
                    var nextPrevPosition: String? = initialLastChild?.position
                    var nextLeftUuid: String = initialLastChild?.uuid ?: blockA.uuid
                    childrenOfB.forEach { child ->
                        val nextPosition = FractionalIndexing.generateKeyBetween(nextPrevPosition, null)
                        queries.updateBlockHierarchy(blockA.uuid, nextLeftUuid, nextPosition, (blockA.level + 1L), child.uuid)
                        nextLeftUuid = child.uuid
                        nextPrevPosition = nextPosition
                    }
                }
                
                // 3. Chain repair for block B (B is being deleted)
                val blockAfterB = queries.selectBlockByLeftUuid(blockB.uuid).executeAsOneOrNull()
                if (blockAfterB != null) {
                    queries.updateBlockLeftUuid(blockB.left_uuid, blockAfterB.uuid)
                }
                
                // 4. Delete block B
                queries.deleteBlockByUuid(blockB.uuid)
            }
            
            blockCache.remove(nextBlockUuid.value)
            hierarchyCache.invalidateAll()
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun splitBlock(
        blockUuid: BlockUuid,
        cursorPosition: Int,
        newBlockUuid: BlockUuid?,
    ): Either<DomainError, Block> = withContext(PlatformDispatcher.DB) {
        try {
            var newBlock: Block? = null
            queries.transaction {
                val block = queries.selectBlockByUuid(blockUuid.value).executeAsOneOrNull()
                    ?: return@transaction

                val content = block.content
                val firstPart = content.substring(0, cursorPosition).trim()
                val secondPart = content.substring(cursorPosition).trim()

                // 1. Update original block
                queries.updateBlockContent(firstPart, Clock.System.now().toEpochMilliseconds(), ContentHasher.sha256ForContent(firstPart), block.uuid)

                // 2. Create new block — use caller-supplied UUID when provided so that the
                //    optimistic in-memory block and the DB block share the same UUID, eliminating
                //    the UUID-correction pass in BlockStateManager.
                val newUuid = newBlockUuid?.value ?: UuidGenerator.generateV7()

                // Use fractional indexing: new block's position sits between 'block' and its next sibling.
                // No sibling shifting required — O(0) UPDATE statements.
                val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsOneOrNull()
                val newPosition = FractionalIndexing.generateKeyBetween(block.position, nextSibling?.position)

                val now = Clock.System.now().toEpochMilliseconds()
                queries.insertBlock(
                    uuid = newUuid,
                    page_uuid = block.page_uuid,
                    parent_uuid = block.parent_uuid,
                    left_uuid = block.uuid,
                    content = secondPart,
                    level = block.level,
                    position = newPosition,
                    created_at = now,
                    updated_at = now,
                    properties = null,
                    version = 0L,
                    content_hash = ContentHasher.sha256ForContent(secondPart),
                    block_type = block.block_type
                )

                if (nextSibling != null) {
                    queries.updateBlockLeftUuid(newUuid, nextSibling.uuid)
                }

                // Construct from known insert parameters — avoids a redundant SELECT after INSERT.
                newBlock = Block(
                    uuid = BlockUuid(newUuid),
                    pageUuid = PageUuid(block.page_uuid),
                    parentUuid = block.parent_uuid,
                    leftUuid = block.uuid,
                    content = secondPart,
                    level = block.level.toInt(),
                    position = newPosition,
                    createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(now),
                    updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(now),
                    properties = emptyMap(),
                    version = 0L,
                    contentHash = ContentHasher.sha256ForContent(secondPart),
                    blockType = blockTypeFromString(block.block_type),
                )
            }
            
            hierarchyCache.invalidateAll()
            newBlock?.right() ?: DomainError.DatabaseError.WriteFailed("Failed to create new block during split").left()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override fun getLinkedReferences(pageName: String): Flow<Either<DomainError, List<Block>>> = flow {
        try {
            // Two LIKE passes run concurrently — each acquires its own pool connection.
            // On JVM, PooledJdbcSqliteDriver provides 8 connections so parallel reads are safe.
            val (wikiCandidates, hashCandidates) = coroutineScope {
                val wiki = async {
                    queries.selectBlocksWithContentLike("%[[${pageName}%")
                        .executeAsList().map { it.toBlockModel() }
                }
                val hash = async {
                    queries.selectBlocksWithContentLike("%#${pageName}%")
                        .executeAsList().map { it.toBlockModel() }
                }
                wiki.await() to hash.await()
            }
            val candidates = (wikiCandidates + hashCandidates).distinctBy { it.uuid }

            val patterns = compileLinkPatterns(pageName)
            val linked = candidates.filter { isLinkedReference(it.content, patterns) }
            emit(linked.right())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getLinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> = flow {
        try {
            // Iterative overfetch: load SQL pages until we have enough filtered results.
            // Avoids scanning and loading the entire block table into memory for UI pagination.
            // Batch size starts at 4x the needed window; grows by 2x each round if yield is poor.
            val need = offset + limit
            val patterns = compileLinkPatterns(pageName)
            val seen = mutableSetOf<String>()
            val accumulated = mutableListOf<Block>()
            var sqlOffset = 0
            var batchSize = maxOf(need * 4, 100)
            var iterations = 0

            while (accumulated.size < need && iterations++ < MAX_LINKED_REF_ITERATIONS) {
                val wikiPage = queries.selectBlocksWithContentLikePaginated(
                    "%[[${pageName}%", batchSize.toLong(), sqlOffset.toLong()
                ).executeAsList().map { it.toBlockModel() }
                val hashPage = queries.selectBlocksWithContentLikePaginated(
                    "%#${pageName}%", batchSize.toLong(), sqlOffset.toLong()
                ).executeAsList().map { it.toBlockModel() }

                val batch = (wikiPage + hashPage)
                    .filter { seen.add(it.uuid.value) }
                    .filter { isLinkedReference(it.content, patterns) }
                accumulated.addAll(batch)

                val exhausted = wikiPage.size < batchSize && hashPage.size < batchSize
                if (exhausted) break

                sqlOffset += batchSize
                // If yield was low (<25%), double the batch to reduce round-trips next iteration.
                if (batch.size < batchSize / 4) batchSize = minOf(batchSize * 2, MAX_LINKED_REF_BATCH)
            }

            // accumulated is already bounded to ≤ offset+limit items by the loop above —
            // this drop/take is on a small pre-limited list, not an unbounded SQL result.
            @Suppress("InMemoryPagination")
            emit(accumulated.drop(offset).take(limit).right())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getUnlinkedReferences(pageName: String): Flow<Either<DomainError, List<Block>>> = flow {
        try {
            val allBlocks = queries.selectAllBlocks().executeAsList().map { it.toBlockModel() }
            // Matches [[name]] and [[name|alias]] — used to exclude already-linked mentions
            val wikiLinkPattern = "\\[\\[${Regex.escape(pageName)}(\\|[^\\]]*)?\\]\\]".toRegex(RegexOption.IGNORE_CASE)
            val plainTextPattern = "\\b${Regex.escape(pageName)}\\b".toRegex(RegexOption.IGNORE_CASE)

            val unlinked = allBlocks.filter { block ->
                plainTextPattern.containsMatchIn(block.content) &&
                        !wikiLinkPattern.containsMatchIn(block.content)
            }
            emit(unlinked.right())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getUnlinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> = flow {
        try {
            // More efficient for large graphs: only query blocks with similar content
            val candidates = queries.selectBlocksWithContentLikePaginated("%$pageName%", limit.toLong(), offset.toLong())
                .executeAsList()
                .map { it.toBlockModel() }

            // Matches [[name]] and [[name|alias]] — used to exclude already-linked mentions
            val wikiLinkPattern = "\\[\\[${Regex.escape(pageName)}(\\|[^\\]]*)?\\]\\]".toRegex(RegexOption.IGNORE_CASE)
            val plainTextPattern = "\\b${Regex.escape(pageName)}\\b".toRegex(RegexOption.IGNORE_CASE)

            val unlinked = candidates.filter { block ->
                plainTextPattern.containsMatchIn(block.content) &&
                        !wikiLinkPattern.containsMatchIn(block.content)
            }
            emit(unlinked.right())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun countLinkedReferences(pageName: String): Flow<Either<DomainError, Long>> = flow {
        try {
            val wikiCandidates = queries.selectBlocksWithContentLike("%[[${pageName}%")
                .executeAsList().map { it.toBlockModel() }
            val hashCandidates = queries.selectBlocksWithContentLike("%#${pageName}%")
                .executeAsList().map { it.toBlockModel() }
            val patterns = compileLinkPatterns(pageName)
            val count = (wikiCandidates + hashCandidates)
                .distinctBy { it.uuid }
                .count { isLinkedReference(it.content, patterns) }
            emit(count.toLong().right())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    private data class LinkPatterns(val wikiLink: Regex, val simpleHashtag: Regex)

    private fun compileLinkPatterns(pageName: String): LinkPatterns {
        val escaped = Regex.escape(pageName)
        return LinkPatterns(
            wikiLink = "\\[\\[$escaped(\\|[^\\]]*)?\\]\\]".toRegex(RegexOption.IGNORE_CASE),
            simpleHashtag = "#$escaped(?=[\\s,\\.!?;\"\\[\\]]|\$)".toRegex(),
        )
    }

    /**
     * Returns true if [content] contains a linked reference to [pageName].
     * Matches:
     * - `[[pageName]]` and `[[pageName|alias]]` (wikilink forms)
     * - `#[[pageName]]` (bracket hashtag form — contained in wikilink match above)
     * - `#pageName` followed by whitespace, punctuation, or end-of-string (simple hashtag)
     */
    private fun isLinkedReference(content: String, patterns: LinkPatterns): Boolean =
        patterns.wikiLink.containsMatchIn(content) || patterns.simpleHashtag.containsMatchIn(content)

    // Results are ordered by created_at DESC (most recent first) — a change from the
    // previous unbounded scan which had no guaranteed order. Recency-first matches typical
    // search UX expectations (recent blocks surface before older ones).
    override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> =
        queries.selectBlocksWithContentLikePaginated("%$query%", limit.toLong(), offset.toLong())
            .asDbFlowList(PlatformDispatcher.DB) { it.toBlockModel() }

    override fun findDuplicateBlocks(limit: Int): Flow<Either<DomainError, List<DuplicateGroup>>> = flow {
        try {
            val duplicateHashes = queries.selectDuplicateBlockHashes(limit.toLong()).executeAsList()
            val groups = mutableListOf<DuplicateGroup>()

            for (row in duplicateHashes) {
                val hash = row.content_hash ?: continue

                // Retrieve all blocks sharing this hash
                val candidates = queries.selectBlocksByContentHash(hash)
                    .executeAsList()
                    .map { it.toBlockModel() }

                candidates.groupBy { it.content }.forEach { (_, trueGroup) ->
                    if (trueGroup.size > 1) {
                        groups.add(DuplicateGroup(contentHash = hash, blocks = trueGroup))
                    }
                }
            }

            emit(groups.right())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    private fun dev.stapler.stelekit.db.Blocks.toBlockModel(): Block {
        return Block(
            uuid = BlockUuid(this.uuid),
            pageUuid = PageUuid(this.page_uuid),
            parentUuid = this.parent_uuid,
            leftUuid = this.left_uuid,
            content = this.content,
            level = this.level.toInt(),
            position = this.position,
            createdAt = Instant.fromEpochMilliseconds(this.created_at),
            updatedAt = Instant.fromEpochMilliseconds(this.updated_at),
            version = this.version,
            properties = parseProperties(this.properties),
            contentHash = this.content_hash,
            blockType = blockTypeFromString(this.block_type)
        )
    }


    private fun dev.stapler.stelekit.db.SelectBlockHierarchyRecursive.toBlockModel(): Block {
        return Block(
            uuid = BlockUuid(this.uuid),
            pageUuid = PageUuid(this.page_uuid),
            parentUuid = this.parent_uuid,
            leftUuid = this.left_uuid,
            content = this.content,
            level = this.level.toInt(),
            position = this.position,
            createdAt = Instant.fromEpochMilliseconds(this.created_at),
            updatedAt = Instant.fromEpochMilliseconds(this.updated_at),
            version = this.version,
            properties = parseProperties(this.properties),
            contentHash = this.content_hash,
            blockType = blockTypeFromString(this.block_type)
        )
    }


    private fun parseProperties(propertiesString: String?): Map<String, String> {
        return propertiesString?.split(",")?.filter { it.isNotBlank() }?.associate {
            val parts = it.split(":", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else "" to ""
        }?.filter { it.key.isNotBlank() } ?: emptyMap()
    }

    private fun isHierarchyCacheExpired(entry: HierarchyCacheEntry): Boolean =
        Clock.System.now().toEpochMilliseconds() - entry.cachedAtMs > hierarchyTtlMs

    /** Snapshot and reset hit/miss/eviction counters for all cache tiers. */
    suspend fun cacheStats(): Map<String, SteleLruCache.CacheStats> = mapOf(
        "block" to blockCache.snapshotAndReset(),
        "hierarchy" to hierarchyCache.snapshotAndReset(),
        "ancestors" to ancestorsCache.snapshotAndReset(),
    )

    suspend fun evictBlock(uuid: String) { blockCache.remove(uuid) }

    suspend fun evictHierarchyForPage(pageUuid: String) {
        val rootUuids = hierarchyIndexMutex.withLock { hierarchyPageIndex.remove(pageUuid) }
        rootUuids?.forEach { hierarchyCache.remove(it) }
    }

    override suspend fun cacheEvictPage(pageUuid: PageUuid) {
        evictHierarchyForPage(pageUuid.value)
    }

    override suspend fun cacheEvictAll() {
        blockCache.invalidateAll()
        hierarchyCache.invalidateAll()
        ancestorsCache.invalidateAll()
        hierarchyIndexMutex.withLock { hierarchyPageIndex.clear() }
    }

    override suspend fun deleteBlocksForPage(pageUuid: PageUuid): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            // Collect affected page names BEFORE deletion (CASCADE removes wikilink refs).
            val affectedPageNames = queries.selectWikilinkPageNamesForPage(pageUuid.value).executeAsList().toSet()
            // Disable FTS5 automerge before bulk delete — mirrors saveBlocks. Without this,
            // the N blocks_ad triggers can each trigger an automerge pass that scans the full
            // FTS index, making large page clears take seconds instead of milliseconds.
            // ftsMerge() is intentionally NOT called here — same rationale as saveBlocks():
            // it scans the full index and is prohibitively expensive on large graphs.
            // compactFtsIndex() is invoked once after a full bulk-indexing session instead.
            ftsAutomergeOff()
            queries.deleteBlocksByPageUuid(pageUuid.value)
            ftsAutomergeDefault()
            recomputeBacklinkCountsFromIndex(affectedPageNames)
            Unit.right()
        } catch (e: CancellationException) {
            runCatching { ftsAutomergeDefault() }
            throw e
        } catch (e: Exception) {
            runCatching { ftsAutomergeDefault() }
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun deleteBlocksForPages(pageUuids: List<PageUuid>): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        if (pageUuids.isEmpty()) return@withContext Unit.right()
        try {
            // Collect affected page names BEFORE deletion (CASCADE removes wikilink refs).
            val affectedPageNames = mutableSetOf<String>()
            for (pageUuid in pageUuids) {
                affectedPageNames.addAll(queries.selectWikilinkPageNamesForPage(pageUuid.value).executeAsList())
            }
            ftsAutomergeOff()
            queries.deleteBlocksByPageUuids(pageUuids.map { it.value })
            ftsAutomergeDefault()
            recomputeBacklinkCountsFromIndex(affectedPageNames)
            Unit.right()
        } catch (e: CancellationException) {
            runCatching { ftsAutomergeDefault() }
            throw e
        } catch (e: Exception) {
            runCatching { ftsAutomergeDefault() }
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun clear(): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            queries.deleteAllBlocks()
            blockCache.invalidateAll()
            hierarchyCache.invalidateAll()
            ancestorsCache.invalidateAll()
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    companion object {
        /**
         * Maximum blocks per SQLite transaction in [saveBlocks]. Limits write-lock hold time
         * to ~WRITE_CHUNK_SIZE * (insert_cost + FTS5_trigger_cost) per transaction.
         * 50 rows ≈ 25–75ms on mid-range Android hardware — short enough that concurrent user
         * edits (addBlockToPage, splitBlock) wait at most one chunk before acquiring the lock.
         */
        private const val WRITE_CHUNK_SIZE = 50

        /**
         * Maximum UUIDs per [getBlocksByUuids] IN-clause chunk.
         * SQLite's SQLITE_MAX_VARIABLE_NUMBER is 999 on Android API < 30 (SQLite < 3.32).
         * 500 stays well below that limit while keeping round-trips to ≤ 2 for most pages.
         */
        private const val BATCH_UUID_CHUNK_SIZE = 500

        /**
         * Maximum batch size for linked reference queries in [getLinkedReferences].
         * Prevents unbounded growth when pageName is a common word on large graphs.
         */
        private const val MAX_LINKED_REF_BATCH = 2_000
        // Hard upper bound on overfetch loop iterations: 50 × 2000 = 100k candidates max.
        private const val MAX_LINKED_REF_ITERATIONS = 50
    }
}

// Converts any non-cancellation exception from a DB flow (e.g. "attempt to re-open a closed
// catchDbError() is defined in DbFlowExtensions.kt (shared across all SqlDelight repositories).
