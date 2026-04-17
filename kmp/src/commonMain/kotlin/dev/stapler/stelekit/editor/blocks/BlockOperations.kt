@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.editor.blocks

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.performance.PerformanceMonitor
import dev.stapler.stelekit.repository.BlockWithDepth
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant
import kotlin.Result

/**
 * Implementation of block operations for Logseq KMP editor.
 * Updated to use UUID-native storage.
 */
class BlockOperations(
    private val blockRepository: BlockRepository,
    private val graphWriter: GraphWriter
) : IBlockOperations, BlockRepository by blockRepository {

    private val operationMutex = Mutex()
    private val treeOps by lazy { BlockTreeOperations(this) }
    
    // Helper functions
    private fun generateBlockUuid(): String {
        return dev.stapler.stelekit.util.UuidGenerator.generateV7()
    }

    override suspend fun createBlock(
        pageId: String,
        content: String,
        parentId: String?,
        leftId: String?,
        position: Int?,
        properties: Map<String, String>,
        uuid: String?,
        createdAt: Instant?
    ): Result<Block> = operationMutex.withLock {
        try {
            val traceId = PerformanceMonitor.startTrace("create-block")
            
            val newBlock = Block(
                uuid = uuid ?: generateBlockUuid(),
                content = content,
                pageUuid = pageId,
                parentUuid = parentId,
                leftUuid = leftId,
                position = position ?: 0,
                level = 0, // Level should be determined based on parent, but keeping it simple for now
                createdAt = createdAt ?: kotlin.time.Clock.System.now(),
                updatedAt = kotlin.time.Clock.System.now(),
                properties = properties
            )
            
            val result = blockRepository.saveBlock(newBlock)
            
            PerformanceMonitor.endTrace(traceId)
            if (result.isSuccess) Result.success(newBlock) else Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateBlockContent(
        blockUuid: String,
        content: String,
        properties: Map<String, String>?
    ): Result<Block> = operationMutex.withLock {
        try {
            val traceId = PerformanceMonitor.startTrace("update-block")
            
            val block = blockRepository.getBlockByUuid(blockUuid).first().getOrNull()
                ?: return Result.failure(IllegalArgumentException("Block not found: $blockUuid"))
            
            val updatedBlock = block.copy(
                content = content,
                properties = properties ?: block.properties,
                updatedAt = kotlin.time.Clock.System.now()
            )
            
            val result = blockRepository.saveBlock(updatedBlock)
            
            PerformanceMonitor.endTrace(traceId)
            if (result.isSuccess) Result.success(updatedBlock) else Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateBlockProperties(
        blockUuid: String,
        properties: Map<String, String>,
        mergeMode: Boolean
    ): Result<Block> = operationMutex.withLock {
        try {
            val block = blockRepository.getBlockByUuid(blockUuid).first().getOrNull()
                ?: return Result.failure(IllegalArgumentException("Block not found: $blockUuid"))
                
            val newProperties = if (mergeMode) {
                block.properties + properties
            } else {
                properties
            }
            
            val updatedBlock = block.copy(
                properties = newProperties,
                updatedAt = kotlin.time.Clock.System.now()
            )
            
            val result = blockRepository.saveBlock(updatedBlock)
            if (result.isSuccess) Result.success(updatedBlock) else Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteBlockEnhanced(
        blockUuid: String,
        deleteStrategy: DeleteStrategy
    ): Result<Unit> = operationMutex.withLock {
        val deleteChildren = deleteStrategy == DeleteStrategy.DELETE_CHILDREN
        blockRepository.deleteBlock(blockUuid, deleteChildren)
    }

    override suspend fun moveBlockEnhanced(
        blockUuid: String,
        targetParentUuid: String?,
        positioning: PositioningMode,
        targetUuid: String?
    ): Result<Unit> = operationMutex.withLock {
        try {
            val block = blockRepository.getBlockByUuid(blockUuid).first().getOrNull()
                ?: return@withLock Result.failure(IllegalArgumentException("Block not found: $blockUuid"))
            
            // 1. Determine siblings and their positions
            val siblingsResult = if (targetParentUuid == null) {
                blockRepository.getBlocksForPage(block.pageUuid).first()
            } else {
                blockRepository.getBlockChildren(targetParentUuid).first()
            }
            
            val siblings = siblingsResult.getOrNull()
                ?.let { if (targetParentUuid == null) it.filter { b -> b.parentUuid == null } else it }
                ?.sortedBy { it.position }
                ?: emptyList()
            
            // 2. Calculate new position
            val newPosition = when (positioning) {
                PositioningMode.START -> 0
                PositioningMode.END -> (siblings.maxOfOrNull { it.position } ?: -1) + 1
                PositioningMode.BEFORE -> {
                    val targetBlock = siblings.find { it.uuid == targetUuid }
                    targetBlock?.position ?: ((siblings.maxOfOrNull { it.position } ?: -1) + 1)
                }
                PositioningMode.AFTER -> {
                    val targetBlock = siblings.find { it.uuid == targetUuid }
                    (targetBlock?.position ?: (siblings.maxOfOrNull { it.position } ?: -1)) + 1
                }
                PositioningMode.REPLACE -> {
                    val targetBlock = siblings.find { it.uuid == targetUuid }
                    targetBlock?.position ?: ((siblings.maxOfOrNull { it.position } ?: -1) + 1)
                }
            }
            
            // 3. Shift siblings if inserting in between
            val siblingsToShift = siblings.filter { it.position >= newPosition && it.uuid != blockUuid }
            if (siblingsToShift.isNotEmpty()) {
                val shifted = siblingsToShift.map { it.copy(position = it.position + 1) }
                blockRepository.saveBlocks(shifted)
            }
            
            // 4. Perform the move
            blockRepository.moveBlock(blockUuid, targetParentUuid, newPosition)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun indentBlockEnhanced(
        blockUuid: String,
        indentMode: IndentMode
    ): Result<Unit> = operationMutex.withLock {
        blockRepository.indentBlock(blockUuid)
    }

    override suspend fun outdentBlockEnhanced(
        blockUuid: String,
        targetLevel: Int?
    ): Result<Unit> = operationMutex.withLock {
        blockRepository.outdentBlock(blockUuid)
    }

    override suspend fun duplicateBlock(
        blockUuid: String,
        includeChildren: Boolean,
        targetPosition: PositioningMode,
        targetUuid: String?
    ): Result<Block> = operationMutex.withLock {
        try {
            val originalBlock = blockRepository.getBlockByUuid(blockUuid).first().getOrNull()
                ?: return Result.failure(IllegalArgumentException("Block not found: $blockUuid"))
            
            val duplicate = originalBlock.copy(
                uuid = generateBlockUuid(),
                content = originalBlock.content + " (copy)",
                createdAt = kotlin.time.Clock.System.now(),
                updatedAt = kotlin.time.Clock.System.now(),
                version = 0L
            )
            
            val result = blockRepository.saveBlock(duplicate)
            
            if (result.isSuccess) Result.success(duplicate) else Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun duplicateSubtree(
        rootBlockUuid: String,
        targetParentUuid: String?,
        targetPosition: PositioningMode
    ): Result<Block> = treeOps.duplicateSubtree(rootBlockUuid, targetParentUuid, targetPosition)

    override suspend fun splitBlock(
        blockUuid: String,
        cursorPosition: Int,
        keepContentInOriginal: Boolean
    ): Result<Block> = operationMutex.withLock {
        blockRepository.splitBlock(blockUuid, cursorPosition)
    }

    override suspend fun mergeWithNext(
        blockUuid: String,
        separator: String
    ): Result<Block> = operationMutex.withLock {
        try {
            val currentBlock = blockRepository.getBlockByUuid(blockUuid).first().getOrNull()
                ?: return Result.failure(IllegalArgumentException("Block not found: $blockUuid"))
            
            val siblings = if (currentBlock.parentUuid == null) {
                blockRepository.getBlocksForPage(currentBlock.pageUuid).first().getOrNull()?.filter { it.parentUuid == null } ?: emptyList()
            } else {
                blockRepository.getBlockSiblings(currentBlock.uuid).first().getOrNull() ?: emptyList()
            }

            val currentIndex = siblings.indexOfFirst { it.uuid == blockUuid }
            if (currentIndex < 0 || currentIndex >= siblings.size - 1) {
                return Result.failure(IllegalStateException("No next sibling to merge with"))
            }
            
            val nextBlock = siblings[currentIndex + 1]
            val mergeResult = blockRepository.mergeBlocks(blockUuid, nextBlock.uuid, separator)
            
            if (mergeResult.isSuccess) {
                blockRepository.getBlockByUuid(blockUuid).first()
                    .map { it ?: throw IllegalStateException("Block disappeared after merge") }
            } else {
                Result.failure(mergeResult.exceptionOrNull() ?: Exception("Merge failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun mergeWithPrevious(
        blockUuid: String,
        separator: String
    ): Result<Block> = operationMutex.withLock {
        try {
            val currentBlock = blockRepository.getBlockByUuid(blockUuid).first().getOrNull()
                ?: return Result.failure(IllegalArgumentException("Block not found: $blockUuid"))
            
            val siblings = if (currentBlock.parentUuid == null) {
                blockRepository.getBlocksForPage(currentBlock.pageUuid).first().getOrNull()?.filter { it.parentUuid == null } ?: emptyList()
            } else {
                blockRepository.getBlockSiblings(currentBlock.uuid).first().getOrNull() ?: emptyList()
            }

            val currentIndex = siblings.indexOfFirst { it.uuid == blockUuid }
            if (currentIndex <= 0) {
                return Result.failure(IllegalStateException("No previous sibling to merge with"))
            }
            
            val prevBlock = siblings[currentIndex - 1]
            val mergeResult = blockRepository.mergeBlocks(prevBlock.uuid, blockUuid, separator)
            
            if (mergeResult.isSuccess) {
                blockRepository.getBlockByUuid(prevBlock.uuid).first()
                    .map { it ?: throw IllegalStateException("Block disappeared after merge") }
            } else {
                Result.failure(mergeResult.exceptionOrNull() ?: Exception("Merge failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun collapseSubtree(blockUuid: String, recursive: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun expandSubtree(blockUuid: String, recursive: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun promoteSubtree(blockUuid: String, levels: Int): Result<Unit> = treeOps.promoteSubtree(blockUuid, levels)
    override suspend fun demoteSubtree(blockUuid: String, levels: Int): Result<Unit> = treeOps.demoteSubtree(blockUuid, levels)
    
    override suspend fun applyBulkOperations(operations: List<BulkOperation>): Result<Unit> = Result.success(Unit)
    override suspend fun reorderBlocks(blockUuids: List<String>): Result<Unit> = Result.success(Unit)
    
    override suspend fun validateOperation(operation: BlockOperation): Result<ValidationResult> {
        return Result.success(ValidationResult(true))
    }
    
    override fun getOperationHistory(): Flow<Result<List<HistoricalOperation>>> {
        return flowOf(Result.success(emptyList()))
    }
    
    override suspend fun undo(): Result<Unit> = Result.success(Unit)
    override suspend fun redo(): Result<Unit> = Result.success(Unit)
}
