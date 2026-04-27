package dev.stapler.stelekit.repository

import dev.stapler.stelekit.cache.LruCache
import dev.stapler.stelekit.cache.RepoCacheConfig
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.util.ContentHasher
import dev.stapler.stelekit.util.UuidGenerator
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.Result.Companion.success

/**
 * SQLDelight implementation of BlockRepository.
 * Uses the generated SteleDatabaseQueries for all operations.
 * Optimized with local caching for hierarchical queries to avoid N+1 problem.
 *
 * Updated to use UUID-native storage for all references.
 */
@OptIn(DirectRepositoryWrite::class)
class SqlDelightBlockRepository(
    private val database: SteleDatabase
) : BlockRepository {

    private val logger = Logger("SqlDelightBlockRepository")
    private val queries = database.steleDatabaseQueries

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
    private val hierarchyCache = LruCache<String, List<BlockWithDepth>>(maxWeight = 500L)
    private val ancestorsCache = LruCache<String, List<Block>>(maxWeight = 500L)

    private val hierarchyTtlMs = 120_000L // 2 minutes

    override fun getBlockByUuid(uuid: String): Flow<Result<Block?>> =
        queries.selectBlockByUuid(uuid)
            .asFlow()
            .mapToOneOrNull(PlatformDispatcher.DB)
            .map { row ->
                val block = row?.toBlockModel()
                if (block != null) blockCache.put(block.uuid, block)
                success(block)
            }

    override fun getBlockChildren(blockUuid: String): Flow<Result<List<Block>>> =
        queries.selectBlockChildren(blockUuid, Long.MAX_VALUE, 0L)
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list ->
                val blocks = list.map { it.toBlockModel() }
                blocks.forEach { blockCache.put(it.uuid, it) }
                success(blocks)
            }

    override fun getBlockHierarchy(rootUuid: String): Flow<Result<List<BlockWithDepth>>> = flow {
        try {
            val cached = hierarchyCache.get(rootUuid)
            if (cached != null && !isHierarchyCacheExpired(rootUuid)) {
                emit(success(cached))
                return@flow
            }

            val rootRow = queries.selectBlockByUuid(rootUuid).executeAsOneOrNull()
            if (rootRow == null) {
                emit(success(emptyList()))
            } else {
                val visitedUuids = mutableSetOf<String>()
                val resultList = mutableListOf<BlockWithDepth>()
                // BFS: convert raw rows to Block models per level; cache each converted block.
                var currentLevel = listOf(rootRow.toBlockModel())
                var currentDepth = 0

                while (currentLevel.isNotEmpty()) {
                    val nextLevelUuids = mutableListOf<String>()
                    currentLevel.forEach { block ->
                        if (block.uuid !in visitedUuids) {
                            visitedUuids.add(block.uuid)
                            blockCache.put(block.uuid, block)
                            resultList.add(BlockWithDepth(block, currentDepth))
                            nextLevelUuids.add(block.uuid)
                        }
                    }
                    if (nextLevelUuids.isEmpty()) break
                    val childRows = queries.selectBlocksByParentUuids(nextLevelUuids).executeAsList()
                    if (childRows.isEmpty()) break
                    currentDepth++
                    currentLevel = childRows.map { it.toBlockModel() }
                    if (currentDepth > 100) break
                }

                hierarchyCache.put(rootUuid, resultList)
                hierarchyCacheTimestamps[rootUuid] = Clock.System.now().toEpochMilliseconds()
                emit(success(resultList))
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getBlockAncestors(blockUuid: String): Flow<Result<List<Block>>> = flow {
        try {
            val cached = ancestorsCache.get(blockUuid)
            if (cached != null) {
                emit(success(cached))
                return@flow
            }

            val row = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()
            if (row == null) {
                emit(success(emptyList()))
            } else {
                val ancestors = mutableListOf<Block>()
                var currentParentUuid: String? = row.parent_uuid
                while (currentParentUuid != null) {
                    val parent = blockCache.get(currentParentUuid)
                        ?: queries.selectBlockByUuid(currentParentUuid).executeAsOneOrNull()?.toBlockModel()
                    if (parent != null) {
                        blockCache.put(parent.uuid, parent)
                        ancestors.add(parent)
                        currentParentUuid = parent.parentUuid
                    } else {
                        break
                    }
                }
                val result = ancestors.reversed()
                ancestorsCache.put(blockUuid, result)
                emit(success(result))
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getBlockParent(blockUuid: String): Flow<Result<Block?>> = flow {
        try {
            val block = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()
            if (block == null || block.parent_uuid == null) {
                emit(success(null))
            } else {
                val parent = queries.selectBlockByUuid(block.parent_uuid).executeAsOneOrNull()
                emit(success(parent?.toBlockModel()))
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getBlockSiblings(blockUuid: String): Flow<Result<List<Block>>> = flow {
        try {
            val block = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()
            if (block == null) {
                emit(success(emptyList()))
            } else {
                val siblings = queries.selectBlockSiblings(
                    uuid = block.uuid,
                    uuid_ = block.uuid,
                    uuid__ = block.uuid
                )
                    .executeAsList()
                    .map { it.toBlockModel() }
                emit(success(siblings))
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getBlocksForPage(pageUuid: String): Flow<Result<List<Block>>> = 
        queries.selectBlocksByPageUuidUnpaginated(pageUuid)
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list -> success(list.map { it.toBlockModel() }) }

    override suspend fun saveBlocks(blocks: List<Block>): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            queries.transaction {
                blocks.forEach { block ->
                    queries.insertBlock(
                        block.uuid,
                        block.pageUuid,
                        block.parentUuid,
                        block.leftUuid,
                        block.content,
                        block.level.toLong(),
                        block.position.toLong(),
                        block.createdAt.toEpochMilliseconds(),
                        block.updatedAt.toEpochMilliseconds(),
                        block.properties.entries.joinToString(",") { "${it.key}:${it.value}" },
                        block.version,
                        block.contentHash ?: ContentHasher.sha256ForContent(block.content),
                        block.blockType
                    )
                }
            }
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveBlock(block: Block): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            queries.insertBlock(
                block.uuid,
                block.pageUuid,
                block.parentUuid,
                block.leftUuid,
                block.content,
                block.level.toLong(),
                block.position.toLong(),
                block.createdAt.toEpochMilliseconds(),
                block.updatedAt.toEpochMilliseconds(),
                block.properties.entries.joinToString(",") { "${it.key}:${it.value}" },
                block.version,
                block.contentHash ?: ContentHasher.sha256ForContent(block.content),
                block.blockType
            )
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteBlock(blockUuid: String, deleteChildren: Boolean): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val block = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()
            if (block != null) {
                if (deleteChildren) {
                    val uuidsToDelete = mutableListOf<String>(block.uuid)
                    var index = 0
                    while (index < uuidsToDelete.size) {
                        val currentUuid = uuidsToDelete[index]
                        val children = queries.selectBlockChildren(currentUuid, Long.MAX_VALUE, 0L).executeAsList()
                        children.forEach { uuidsToDelete.add(it.uuid) }
                        index++
                    }

                    // Chain repair before deletion — use firstOrNull because duplicate
                    // left_uuid values indicate data corruption; we repair what we can.
                    val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsList().firstOrNull()
                    if (nextSibling != null) {
                        queries.updateBlockLeftUuid(block.left_uuid, nextSibling.uuid)
                    }

                    uuidsToDelete.forEach { queries.deleteBlockByUuid(it) }
                } else {
                    // Chain repair before deletion
                    val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsList().firstOrNull()
                    if (nextSibling != null) {
                        queries.updateBlockLeftUuid(block.left_uuid, nextSibling.uuid)
                    }
                    queries.deleteBlockByUuid(block.uuid)
                }

            }
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteBulk(blockUuids: List<String>, deleteChildren: Boolean): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            queries.transaction {
                blockUuids.forEach { uuid ->
                    val block = queries.selectBlockByUuid(uuid).executeAsOneOrNull() ?: return@forEach
                    if (deleteChildren) {
                        // Collect the full subtree
                        val uuidsToDelete = mutableListOf(block.uuid)
                        var index = 0
                        while (index < uuidsToDelete.size) {
                            val currentUuid = uuidsToDelete[index]
                            val children = queries.selectBlockChildren(currentUuid, Long.MAX_VALUE, 0L).executeAsList()
                            children.forEach { uuidsToDelete.add(it.uuid) }
                            index++
                        }
                        // Chain repair for the top-level block being deleted
                        val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsOneOrNull()
                        if (nextSibling != null) {
                            queries.updateBlockLeftUuid(block.left_uuid, nextSibling.uuid)
                        }
                        uuidsToDelete.forEach { queries.deleteBlockByUuid(it) }
                    } else {
                        // Chain repair before deletion
                        val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsOneOrNull()
                        if (nextSibling != null) {
                            queries.updateBlockLeftUuid(block.left_uuid, nextSibling.uuid)
                        }
                        queries.deleteBlockByUuid(block.uuid)
                    }
                }
            }
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun moveBlock(
        blockUuid: String,
        newParentUuid: String?,
        newPosition: Int
    ): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            queries.transaction {
                val block = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull() ?: return@transaction
                
                // 1. Repair OLD chain: the block that followed us now follows our old left sibling
                val blockFollowingOld = queries.selectBlockByLeftUuid(block.uuid).executeAsOneOrNull()
                if (blockFollowingOld != null) {
                    queries.updateBlockLeftUuid(block.left_uuid, blockFollowingOld.uuid)
                }
                
                // 2. Resolve NEW parent and level
                val newParent = newParentUuid?.let { queries.selectBlockByUuid(it).executeAsOneOrNull() }
                val newParentUuidResolved = newParent?.uuid
                val newLevel = (newParent?.level ?: -1L) + 1L
                
                // 3. Find NEW left sibling (or parent)
                val siblings = if (newParentUuidResolved == null) {
                    queries.selectRootBlocksByPageUuidOrdered(block.page_uuid).executeAsList()
                } else {
                    queries.selectBlocksByParentUuidOrdered(newParentUuidResolved).executeAsList()
                }
                
                // Exclude the block itself if it was already a sibling
                val otherSiblings = siblings.filter { it.uuid != block.uuid }.sortedBy { it.position }
                
                val newLeftUuid = if (newPosition <= 0 || otherSiblings.isEmpty()) {
                    newParentUuidResolved ?: block.page_uuid // Use parent or pageUuid (for root)
                } else {
                    val prevIdx = (newPosition - 1).coerceAtMost(otherSiblings.size - 1)
                    otherSiblings[prevIdx].uuid
                }
                
                // 4. Repair NEW chain: the block that will follow us now follows us
                // If there's a block at the new position, its left_uuid should become ours
                val targetBlockAtPosition = otherSiblings.getOrNull(newPosition)
                if (targetBlockAtPosition != null) {
                    queries.updateBlockLeftUuid(block.uuid, targetBlockAtPosition.uuid)
                }
                
                // 5. Update block hierarchy
                queries.updateBlockHierarchy(
                    newParentUuidResolved,
                    newLeftUuid,
                    newPosition.toLong(),
                    newLevel,
                    block.uuid
                )
            }
            
            // Invalidate caches
            blockCache.remove(blockUuid)
            hierarchyCache.invalidateAll()
            ancestorsCache.invalidateAll()
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun indentBlock(blockUuid: String): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            queries.transaction {
                val block = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()
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
                val newPosition = (lastChildOfNewParent?.position ?: -1L) + 1L
                val newLevel = block.level + 1L
                
                // Update current block hierarchy in one shot
                queries.updateBlockHierarchy(prevSibling.uuid, newLeftUuid, newPosition, newLevel, block.uuid)
            }

            hierarchyCache.invalidateAll()
            ancestorsCache.invalidateAll()
            blockCache.invalidateAll()
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun outdentBlock(blockUuid: String): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            queries.transaction {
                val block = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()
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
                val newPosition = currentParent.position + 1L
                val newLevel = block.level - 1L
                
                // Shift positions of siblings that come after the new position to make room
                val siblingsToShift = if (grandParentUuid == null) {
                    queries.selectRootBlocksByPageUuidOrdered(block.page_uuid).executeAsList()
                } else {
                    queries.selectBlocksByParentUuidOrdered(grandParentUuid).executeAsList()
                }
                siblingsToShift.forEach { sibling ->
                    if (sibling.position >= newPosition) {
                        queries.updateBlockPositionOnly(sibling.position + 1L, sibling.uuid)
                    }
                }

                // Repair new sibling chain: Any block that followed currentParent at the grandparent level 
                // now must follow the moved block.
                val blockFollowingOldParent = queries.selectBlockByLeftUuid(currentParent.uuid).executeAsOneOrNull()
                if (blockFollowingOldParent != null) {
                    queries.updateBlockLeftUuid(block.uuid, blockFollowingOldParent.uuid)
                }
                
                // Update current block hierarchy in one shot
                queries.updateBlockHierarchy(grandParentUuid, newLeftUuid, newPosition, newLevel, block.uuid)
            }

            hierarchyCache.invalidateAll()
            ancestorsCache.invalidateAll()
            blockCache.invalidateAll()
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun moveBlockUp(blockUuid: String): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val block = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()
                ?: return@withContext success(Unit)

            val siblings = if (block.parent_uuid == null) {
                queries.selectRootBlocksByPageUuidOrdered(block.page_uuid).executeAsList()
            } else {
                queries.selectBlocksByParentUuidOrdered(block.parent_uuid).executeAsList()
            }

            val blockIndex = siblings.indexOfFirst { it.uuid == block.uuid }
            if (blockIndex <= 0) return@withContext success(Unit) // Already first

            val prevSibling = siblings[blockIndex - 1]
            val nextSibling = siblings.getOrNull(blockIndex + 1)

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
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun moveBlockDown(blockUuid: String): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val block = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()
                ?: return@withContext success(Unit)

            val siblings = if (block.parent_uuid == null) {
                queries.selectRootBlocksByPageUuidOrdered(block.page_uuid).executeAsList()
            } else {
                queries.selectBlocksByParentUuidOrdered(block.parent_uuid).executeAsList()
            }

            val blockIndex = siblings.indexOfFirst { it.uuid == block.uuid }
            if (blockIndex >= siblings.size - 1) return@withContext success(Unit) // Already last

            val nextSibling = siblings[blockIndex + 1]
            val afterNextSibling = siblings.getOrNull(blockIndex + 2)

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
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun mergeBlocks(
        blockUuid: String,
        nextBlockUuid: String,
        separator: String
    ): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            queries.transaction {
                val blockA = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()
                    ?: return@transaction
                val blockB = queries.selectBlockByUuid(nextBlockUuid).executeAsOneOrNull()
                    ?: return@transaction
                
                // 1. Update content of block A
                val mergedContent = blockA.content + separator + blockB.content
                queries.updateBlockContent(
                    mergedContent, 
                    Clock.System.now().toEpochMilliseconds(), 
                    blockA.uuid
                )
                
                // 2. Reparent all children of block B to block A
                val childrenOfB = queries.selectBlocksByParentUuidOrdered(blockB.uuid).executeAsList()
                childrenOfB.forEach { child ->
                    // For each child, we need to update parent_uuid AND potentially recalculate position
                    // To keep it simple for now, we just append them to A's children
                    val lastChildOfA = queries.selectLastChild(blockA.uuid).executeAsOneOrNull()
                    val newPosition = (lastChildOfA?.position ?: -1L) + 1L
                    val newLeftUuid = lastChildOfA?.uuid ?: blockA.uuid
                    
                    queries.updateBlockHierarchy(blockA.uuid, newLeftUuid, newPosition, (blockA.level + 1L), child.uuid)
                }
                
                // 3. Chain repair for block B (B is being deleted)
                val blockAfterB = queries.selectBlockByLeftUuid(blockB.uuid).executeAsOneOrNull()
                if (blockAfterB != null) {
                    queries.updateBlockLeftUuid(blockB.left_uuid, blockAfterB.uuid)
                }
                
                // 4. Delete block B
                queries.deleteBlockByUuid(blockB.uuid)
            }
            
            blockCache.remove(nextBlockUuid)
            hierarchyCache.invalidateAll()
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun splitBlock(
        blockUuid: String, 
        cursorPosition: Int
    ): Result<Block> = withContext(PlatformDispatcher.DB) {
        try {
            var newBlock: Block? = null
            queries.transaction {
                val block = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()
                    ?: return@transaction
                
                val content = block.content
                val firstPart = content.substring(0, cursorPosition).trim()
                val secondPart = content.substring(cursorPosition).trim()
                
                // 1. Update original block
                queries.updateBlockContent(firstPart, Clock.System.now().toEpochMilliseconds(), block.uuid)
                
                // 2. Create new block
                val newUuid = UuidGenerator.generateV7()
                val newPosition = block.position + 1L
                
                // Shift siblings' positions
                val siblings = if (block.parent_uuid == null) {
                    queries.selectRootBlocksByPageUuidOrdered(block.page_uuid).executeAsList()
                } else {
                    queries.selectBlocksByParentUuidOrdered(block.parent_uuid).executeAsList()
                }
                
                siblings.forEach { sibling ->
                    if (sibling.position >= newPosition) {
                        queries.updateBlockPositionOnly(sibling.position + 1L, sibling.uuid)
                    }
                }
                
                // Repair chain: block that followed 'block' now follows 'newBlock'
                val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsOneOrNull()
                
                queries.insertBlock(
                    uuid = newUuid,
                    page_uuid = block.page_uuid,
                    parent_uuid = block.parent_uuid,
                    left_uuid = block.uuid,
                    content = secondPart,
                    level = block.level,
                    position = newPosition,
                    created_at = Clock.System.now().toEpochMilliseconds(),
                    updated_at = Clock.System.now().toEpochMilliseconds(),
                    properties = null,
                    version = 0L,
                    content_hash = ContentHasher.sha256ForContent(secondPart),
                    block_type = block.block_type
                )
                
                val insertedBlock = queries.selectBlockByUuid(newUuid).executeAsOne()
                if (nextSibling != null) {
                    queries.updateBlockLeftUuid(insertedBlock.uuid, nextSibling.uuid)
                }
                
                newBlock = insertedBlock.toBlockModel()
            }
            
            hierarchyCache.invalidateAll()
            Result.success(newBlock ?: error("Failed to create new block during split"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getLinkedReferences(pageName: String): Flow<Result<List<Block>>> = flow {
        try {
            // Two LIKE passes: one for [[name]] wikilinks, one for #name simple hashtags.
            // Results are merged and deduplicated by UUID before in-memory refinement.
            val wikiCandidates = queries.selectBlocksWithContentLike("%[[${pageName}%")
                .executeAsList().map { it.toBlockModel() }
            val hashCandidates = queries.selectBlocksWithContentLike("%#${pageName}%")
                .executeAsList().map { it.toBlockModel() }
            val candidates = (wikiCandidates + hashCandidates).distinctBy { it.uuid }

            val patterns = compileLinkPatterns(pageName)
            val linked = candidates.filter { isLinkedReference(it.content, patterns) }
            emit(success(linked))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getLinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Result<List<Block>>> = flow {
        try {
            val wikiCandidates = queries.selectBlocksWithContentLike("%[[${pageName}%")
                .executeAsList().map { it.toBlockModel() }
            val hashCandidates = queries.selectBlocksWithContentLike("%#${pageName}%")
                .executeAsList().map { it.toBlockModel() }
            val patterns = compileLinkPatterns(pageName)
            val allLinked = (wikiCandidates + hashCandidates)
                .distinctBy { it.uuid }
                .filter { isLinkedReference(it.content, patterns) }

            emit(success(allLinked.drop(offset).take(limit)))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getUnlinkedReferences(pageName: String): Flow<Result<List<Block>>> = flow {
        try {
            val allBlocks = queries.selectAllBlocks().executeAsList().map { it.toBlockModel() }
            // Matches [[name]] and [[name|alias]] — used to exclude already-linked mentions
            val wikiLinkPattern = "\\[\\[${Regex.escape(pageName)}(\\|[^\\]]*)?\\]\\]".toRegex(RegexOption.IGNORE_CASE)
            val plainTextPattern = "\\b${Regex.escape(pageName)}\\b".toRegex(RegexOption.IGNORE_CASE)

            val unlinked = allBlocks.filter { block ->
                plainTextPattern.containsMatchIn(block.content) &&
                        !wikiLinkPattern.containsMatchIn(block.content)
            }
            emit(success(unlinked))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getUnlinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Result<List<Block>>> = flow {
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
            emit(success(unlinked))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun countLinkedReferences(pageName: String): Flow<Result<Long>> = flow {
        try {
            val wikiCandidates = queries.selectBlocksWithContentLike("%[[${pageName}%")
                .executeAsList().map { it.toBlockModel() }
            val hashCandidates = queries.selectBlocksWithContentLike("%#${pageName}%")
                .executeAsList().map { it.toBlockModel() }
            val patterns = compileLinkPatterns(pageName)
            val count = (wikiCandidates + hashCandidates)
                .distinctBy { it.uuid }
                .count { isLinkedReference(it.content, patterns) }
            emit(success(count.toLong()))
        } catch (e: Exception) {
            emit(Result.failure(e))
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

    override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Result<List<Block>>> =
        queries.selectBlocksWithContentLike("%$query%")
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list ->
                success(list.drop(offset).take(limit).map { it.toBlockModel() })
            }

    override fun findDuplicateBlocks(limit: Int): Flow<Result<List<DuplicateGroup>>> = flow {
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
                        groups.add(DuplicateGroup(contentHash = hash, blocks = trueGroup, count = trueGroup.size))
                    }
                }
            }

            emit(success(groups))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.DB)

    private fun dev.stapler.stelekit.db.Blocks.toBlockModel(): Block {
        return Block(
            uuid = this.uuid,
            pageUuid = this.page_uuid,
            parentUuid = this.parent_uuid,
            leftUuid = this.left_uuid,
            content = this.content,
            level = this.level.toInt(),
            position = this.position.toInt(),
            createdAt = Instant.fromEpochMilliseconds(this.created_at),
            updatedAt = Instant.fromEpochMilliseconds(this.updated_at),
            version = this.version,
            properties = parseProperties(this.properties),
            contentHash = this.content_hash,
            blockType = knownBlockTypeOrDefault(this.block_type, this.uuid)
        )
    }

    private val knownBlockTypes = setOf(
        "bullet", "paragraph", "heading", "code_fence", "blockquote",
        "ordered_list_item", "thematic_break", "table", "raw_html"
    )

    private fun knownBlockTypeOrDefault(blockType: String, uuid: String): String {
        if (blockType in knownBlockTypes) return blockType
        logger.warn("Unknown block_type '$blockType' for block $uuid — falling back to 'bullet'")
        return "bullet"
    }

    private fun parseProperties(propertiesString: String?): Map<String, String> {
        return propertiesString?.split(",")?.filter { it.isNotBlank() }?.associate {
            val parts = it.split(":", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else "" to ""
        }?.filter { it.key.isNotBlank() } ?: emptyMap()
    }

    private val hierarchyCacheTimestamps = mutableMapOf<String, Long>()

    private fun isHierarchyCacheExpired(rootUuid: String): Boolean {
        val timestamp = hierarchyCacheTimestamps[rootUuid] ?: return true
        return Clock.System.now().toEpochMilliseconds() - timestamp > hierarchyTtlMs
    }

    override suspend fun deleteBlocksForPage(pageUuid: String): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            queries.deleteBlocksByPageUuid(pageUuid)
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteBlocksForPages(pageUuids: List<String>): Result<Unit> = withContext(PlatformDispatcher.DB) {
        if (pageUuids.isEmpty()) return@withContext success(Unit)
        try {
            queries.deleteBlocksByPageUuids(pageUuids)
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clear(): Unit = withContext(PlatformDispatcher.DB) {
        queries.deleteAllBlocks()
        blockCache.invalidateAll()
        hierarchyCache.invalidateAll()
        ancestorsCache.invalidateAll()
    }
}
