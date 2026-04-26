package dev.stapler.stelekit.cache

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.BlockWithDepth
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.DuplicateGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn

/**
 * Cached wrapper around BlockRepository.
 * Provides progressive loading with LRU, TTL, and prefetch.
 * Updated to use UUID-native storage.
 */
@OptIn(DirectRepositoryWrite::class)
class CachedBlockRepository(
    private val delegate: BlockRepository,
    private val cache: BlockCache
) : BlockRepository {

    override fun getBlockByUuid(uuid: String): Flow<Either<DomainError, Block?>> {
        return cache.getBlockByUuid(uuid)
    }

    override fun getBlockChildren(blockUuid: String): Flow<Either<DomainError, List<Block>>> {
        return cache.getBlockChildren(blockUuid)
    }

    override fun getBlockHierarchy(rootUuid: String): Flow<Either<DomainError, List<BlockWithDepth>>> {
        return cache.getBlockHierarchy(rootUuid)
    }

    override fun getBlockAncestors(blockUuid: String): Flow<Either<DomainError, List<Block>>> {
        return cache.getBlockAncestors(blockUuid)
    }

    override fun getBlockParent(blockUuid: String): Flow<Either<DomainError, Block?>> {
        return cache.getBlockParent(blockUuid)
    }

    override fun getBlockSiblings(blockUuid: String): Flow<Either<DomainError, List<Block>>> {
        return delegate.getBlockSiblings(blockUuid)
    }

    override fun getBlocksForPage(pageUuid: String): Flow<Either<DomainError, List<Block>>> {
        return delegate.getBlocksForPage(pageUuid)
    }

    override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> {
        return delegate.searchBlocksByContent(query, limit, offset)
    }

    override suspend fun saveBlocks(blocks: List<Block>): Either<DomainError, Unit> {
        return try {
            blocks.forEach { cache.saveBlock(it) }
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun saveBlock(block: Block): Either<DomainError, Unit> {
        return cache.saveBlock(block)
    }

    override suspend fun deleteBlock(blockUuid: String, deleteChildren: Boolean): Either<DomainError, Unit> {
        return cache.deleteBlock(blockUuid, deleteChildren)
    }

    override suspend fun deleteBulk(blockUuids: List<String>, deleteChildren: Boolean): Either<DomainError, Unit> {
        return cache.deleteBulk(blockUuids, deleteChildren)
    }

    override suspend fun moveBlock(
        blockUuid: String,
        newParentUuid: String?,
        newPosition: Int
    ): Either<DomainError, Unit> {
        return cache.moveBlock(blockUuid, newParentUuid, newPosition)
    }

    override suspend fun indentBlock(blockUuid: String): Either<DomainError, Unit> {
        return delegate.indentBlock(blockUuid)
    }

    override suspend fun outdentBlock(blockUuid: String): Either<DomainError, Unit> {
        return delegate.outdentBlock(blockUuid)
    }

    override suspend fun moveBlockUp(blockUuid: String): Either<DomainError, Unit> {
        return delegate.moveBlockUp(blockUuid).also {
            cache.invalidateSiblings(blockUuid)
        }
    }

    override suspend fun moveBlockDown(blockUuid: String): Either<DomainError, Unit> {
        return delegate.moveBlockDown(blockUuid).also {
            cache.invalidateSiblings(blockUuid)
        }
    }

    override suspend fun mergeBlocks(blockUuid: String, nextBlockUuid: String, separator: String): Either<DomainError, Unit> {
        return delegate.mergeBlocks(blockUuid, nextBlockUuid, separator).also {
            cache.invalidateBlock(blockUuid)
            cache.invalidateBlock(nextBlockUuid)
            cache.invalidateSiblings(blockUuid)
        }
    }

    override suspend fun splitBlock(blockUuid: String, cursorPosition: Int): Either<DomainError, Block> {
        return delegate.splitBlock(blockUuid, cursorPosition).also {
            cache.invalidateBlock(blockUuid)
            cache.invalidateSiblings(blockUuid)
        }
    }

    override fun getLinkedReferences(pageName: String): Flow<Either<DomainError, List<Block>>> {
        return delegate.getLinkedReferences(pageName)
    }

    override fun getLinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> {
        return delegate.getLinkedReferences(pageName, limit, offset)
    }

    override fun getUnlinkedReferences(pageName: String): Flow<Either<DomainError, List<Block>>> {
        return delegate.getUnlinkedReferences(pageName)
    }

    override fun getUnlinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> {
        return delegate.getUnlinkedReferences(pageName, limit, offset)
    }

    fun getCacheMetrics(): CacheMetrics = cache.getMetrics()

    fun invalidateBlock(uuid: String) {
        cache.invalidateBlock(uuid)
    }

    fun clearCache() {
        cache.clear()
    }

    fun start() {
        cache.start()
    }

    override suspend fun deleteBlocksForPage(pageUuid: String): Either<DomainError, Unit> {
        return delegate.deleteBlocksForPage(pageUuid)
    }

    override suspend fun deleteBlocksForPages(pageUuids: List<String>): Either<DomainError, Unit> {
        return delegate.deleteBlocksForPages(pageUuids)
    }

    override suspend fun clear() {
        delegate.clear()
        cache.clear()
    }

    override fun countLinkedReferences(pageName: String): Flow<Either<DomainError, Long>> =
        delegate.countLinkedReferences(pageName)

    override fun findDuplicateBlocks(limit: Int): Flow<Either<DomainError, List<DuplicateGroup>>> =
        delegate.findDuplicateBlocks(limit)
}
