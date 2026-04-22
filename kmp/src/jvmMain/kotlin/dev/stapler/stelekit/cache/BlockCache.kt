package dev.stapler.stelekit.cache

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.BlockWithDepth
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.Result.Companion.success
import kotlin.random.Random

/**
 * Block cache with LRU, TTL, and prefetch capabilities.
 * Wraps a BlockRepository to provide progressive loading.
 * Updated to use UUID-native storage.
 */
@OptIn(DirectRepositoryWrite::class)
class BlockCache(
    private val config: CacheConfig,
    private val delegate: BlockRepository
) {
    private val cache = LRUCache<String, CachedBlock>(config, "blocks")
    private val childrenIndex = ConcurrentHashMap<String, MutableList<String>>()
    private val hierarchyCache = LRUCache<String, CachedHierarchy>(config, "hierarchies")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val metrics = MutableStateFlow(CacheMetrics())

    fun start() {
        cache.start()
        hierarchyCache.start()
    }

    fun stop() {
        cache.stop()
        hierarchyCache.stop()
        scope.cancel()
    }

    /**
     * Get block by UUID with cache.
     */
    fun getBlockByUuid(uuid: String): Flow<Result<Block?>> = flow {
        try {
            val cached = cache.get(uuid)
            if (cached != null) {
                metrics.value = metrics.value.withBlockHit()
                emit(success(cached.block))
            } else {
                metrics.value = metrics.value.withBlockMiss()
                delegate.getBlockByUuid(uuid).collect { result ->
                    result.getOrNull()?.let { block ->
                        val cachedBlock = CachedBlock(
                            block = block,
                            childrenUuids = emptyList()
                        )
                        cache.put(uuid, cachedBlock)
                        if (config.enablePrefetch && config.prefetchDepth > 0) {
                            prefetchBlockChildren(block.uuid, 1)
                        }
                    }
                    emit(result)
                }
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Get block children with cache and prefetch.
     */
    fun getBlockChildren(blockUuid: String): Flow<Result<List<Block>>> = flow {
        try {
            var parentBlock: Block? = null

            cache.get(blockUuid)?.let { cached ->
                parentBlock = cached.block
            }

            if (parentBlock == null) {
                parentBlock = delegate.getBlockByUuid(blockUuid).first().getOrNull()
            }

            if (parentBlock == null) {
                emit(success(emptyList()))
                return@flow
            }

            // Check children index cache
            val cachedChildrenUuids = childrenIndex[blockUuid]
            if (cachedChildrenUuids != null) {
                val cachedChildren = cachedChildrenUuids.mapNotNull { uuid ->
                    cache.get(uuid)?.block
                }
                if (cachedChildren.size == cachedChildrenUuids.size) {
                    metrics.value = metrics.value.withBlockHit()
                    emit(success(cachedChildren))
                    return@flow
                }
            }

            // Cache miss - load from delegate
            metrics.value = metrics.value.withBlockMiss()
            delegate.getBlockChildren(blockUuid).collect { result ->
                result.getOrNull()?.let { children ->
                    val childrenUuids = mutableListOf<String>()
                    val cachedBlocks = children.map { child ->
                        childrenUuids.add(child.uuid)
                        val cached = CachedBlock(
                            block = child,
                            childrenUuids = emptyList(),
                            parentId = blockUuid
                        )
                        cache.put(child.uuid, cached)
                        child
                    }

                    // Update children index
                    childrenIndex[blockUuid] = childrenUuids.toMutableList()

                    // Prefetch grandchildren
                    if (config.enablePrefetch && config.prefetchDepth > 1) {
                        children.forEach { child ->
                            scope.launch {
                                prefetchBlockChildren(child.uuid, 2)
                            }
                        }
                    }

                    emit(success(cachedBlocks))
                } ?: emit(result)
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Get block hierarchy with cache.
     */
    fun getBlockHierarchy(rootUuid: String): Flow<Result<List<BlockWithDepth>>> = flow {
        try {
            val cached = hierarchyCache.get(rootUuid)
            if (cached != null && !isHierarchyExpired(cached.timestamp)) {
                metrics.value = metrics.value.withHierarchyHit()
                emit(success(cached.blocks))
            } else {
                metrics.value = metrics.value.withHierarchyMiss()
                delegate.getBlockHierarchy(rootUuid).collect { result ->
                    result.getOrNull()?.let { hierarchy ->
                        val cachedHierarchy = CachedHierarchy(
                            rootUuid = rootUuid,
                            blocks = hierarchy
                        )
                        hierarchyCache.put(rootUuid, cachedHierarchy)

                        // Cache individual blocks
                        hierarchy.forEach { (block, _) ->
                            cache.put(block.uuid, CachedBlock(block))
                        }

                        emit(success(hierarchy))
                    } ?: emit(result)
                }
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Get block parent with cache.
     */
    fun getBlockParent(blockUuid: String): Flow<Result<Block?>> = flow {
        try {
            val cached = cache.get(blockUuid)
            if (cached != null && cached.parentId != null) {
                val parent = cache.get(cached.parentId)
                if (parent != null) {
                    metrics.value = metrics.value.withBlockHit()
                    emit(success(parent.block))
                    return@flow
                }
            }

            metrics.value = metrics.value.withBlockMiss()
            delegate.getBlockParent(blockUuid).collect { emit(it) }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Get block ancestors with cache.
     */
    fun getBlockAncestors(blockUuid: String): Flow<Result<List<Block>>> = flow {
        try {
            // Try to build from cache
            val cached = cache.get(blockUuid)
            if (cached != null) {
                val ancestors = mutableListOf<Block>()
                var currentParentUuid: String? = cached.parentId
                while (currentParentUuid != null) {
                    val parent = cache.get(currentParentUuid)
                    if (parent != null) {
                        ancestors.add(parent.block)
                        currentParentUuid = parent.parentId
                    } else {
                        break
                    }
                }
                if (ancestors.isNotEmpty()) {
                    metrics.value = metrics.value.withBlockHit()
                    emit(success(ancestors.reversed()))
                    return@flow
                }
            }

            metrics.value = metrics.value.withBlockMiss()
            delegate.getBlockAncestors(blockUuid).collect { emit(it) }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Save block - invalidates cache.
     */
    suspend fun saveBlock(block: Block): Result<Unit> {
        invalidateBlock(block.uuid)
        invalidateBlockHierarchy(block.uuid)
        return delegate.saveBlock(block)
    }

    /**
     * Delete block - invalidates cache.
     */
    suspend fun deleteBlock(blockUuid: String, deleteChildren: Boolean): Result<Unit> {
        invalidateBlockHierarchy(blockUuid)
        childrenIndex.remove(blockUuid)
        return delegate.deleteBlock(blockUuid, deleteChildren)
    }

    suspend fun deleteBulk(blockUuids: List<String>, deleteChildren: Boolean): Result<Unit> {
        blockUuids.forEach { uuid ->
            invalidateBlockHierarchy(uuid)
            childrenIndex.remove(uuid)
        }
        return delegate.deleteBulk(blockUuids, deleteChildren)
    }

    /**
     * Move block - invalidates affected hierarchies.
     */
    suspend fun moveBlock(
        blockUuid: String,
        newParentUuid: String?,
        newPosition: Int
    ): Result<Unit> {
        invalidateBlockHierarchy(blockUuid)
        newParentUuid?.let { invalidateBlockHierarchy(it) }
        return delegate.moveBlock(blockUuid, newParentUuid, newPosition)
    }

    /**
     * Invalidate a specific block from cache.
     */
    fun invalidateBlock(uuid: String) {
        val cached = cache.get(uuid)
        cached?.let {
            cache.remove(uuid)
            it.block.parentUuid?.let { pid ->
                childrenIndex[pid]?.remove(uuid)
            }
        }
    }

    /**
     * Invalidate siblings (children of the same parent).
     * Useful when reordering blocks.
     */
    fun invalidateSiblings(blockUuid: String) {
        val cached = cache.get(blockUuid)
        cached?.block?.parentUuid?.let { pid ->
            childrenIndex.remove(pid)
        }
        // Also invalidate the block itself to ensure fresh state
        invalidateBlock(blockUuid)
    }

    /**
     * Invalidate a hierarchy cache entry.
     */
    fun invalidateBlockHierarchy(uuid: String) {
        hierarchyCache.remove(uuid)
    }

    /**
     * Clear all caches.
     */
    fun clear() {
        cache.clear()
        hierarchyCache.clear()
        childrenIndex.clear()
    }

    /**
     * Get cache metrics.
     */
    fun getMetrics(): CacheMetrics = metrics.value

    private fun prefetchBlockChildren(blockUuid: String, depth: Int) {
        if (depth <= 0 || !config.enablePrefetch) return

        scope.launch {
            try {
                val block = cache.get(blockUuid)
                if (block != null) {
                    val childrenUuids = childrenIndex[blockUuid]
                    if (childrenUuids == null) {
                        // Load children silently
                        delegate.getBlockChildren(block.block.uuid).first()
                    }
                    metrics.value = metrics.value.withPrefetch()
                }
            } catch (_: Exception) {
                // Silently ignore prefetch errors
            }
        }
    }

    private fun isHierarchyExpired(timestamp: Long): Boolean {
        return System.currentTimeMillis() - timestamp > config.hierarchyTtlMs
    }
}
