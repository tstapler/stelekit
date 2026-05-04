@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.editor.blocks

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.repository.BlockWithDepth
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

import dev.stapler.stelekit.editor.blocks.IBlockOperations
import dev.stapler.stelekit.editor.blocks.DeleteStrategy
import dev.stapler.stelekit.editor.blocks.PositioningMode
import dev.stapler.stelekit.editor.blocks.IndentMode
import dev.stapler.stelekit.editor.blocks.BlockOperation
import dev.stapler.stelekit.editor.blocks.ValidationResult
import dev.stapler.stelekit.editor.blocks.BulkOperation
import dev.stapler.stelekit.editor.blocks.HistoricalOperation

/**
 * Enhanced block operations with tree traversal and manipulation capabilities.
 * Provides efficient algorithms for hierarchical block structures.
 * 
 * Updated to use UUID-native storage.
 */
class BlockTreeOperations(
    private val blockOperations: IBlockOperations
) {
    
    // ===== ENHANCED TRAVERSAL OPERATIONS =====
    
    /**
     * Get a subtree starting from the specified root block.
     * Returns all blocks in the subtree with their depth information.
     */
    suspend fun getSubtree(
        rootUuid: String,
        includeCollapsed: Boolean = true,
        maxDepth: Int? = null
    ): Either<DomainError, List<BlockWithDepth>> {
        return try {
            val allBlocks = mutableListOf<BlockWithDepth>()
            collectSubtree(rootUuid, 0, allBlocks, includeCollapsed, maxDepth)
            allBlocks.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }
    
    /**
     * Get all visible blocks in a page (respecting collapsed states).
     */
    suspend fun getVisibleBlocks(
        pageUuid: String,
        includeCollapsed: Boolean = false
    ): Either<DomainError, List<BlockWithDepth>> {
        return try {
            // Get current blocks snapshot
            val pageBlocksResult = blockOperations.getBlocksForPage(pageUuid).first()
            val pageBlocks = pageBlocksResult.getOrNull() ?: emptyList()
            
            val rootBlocks = pageBlocks.filter { it.parentUuid == null }
            val visibleBlocks = mutableListOf<BlockWithDepth>()
            
            rootBlocks.forEach { rootBlock ->
                collectSubtree(
                    rootBlock.uuid, 
                    0, 
                    visibleBlocks, 
                    includeCollapsed,
                    null
                )
            }
            
            visibleBlocks.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }
    
    /**
     * Find the common ancestor of multiple blocks.
     */
    suspend fun findCommonAncestor(
        blockUuids: List<String>
    ): Either<DomainError, String?> {
        return try {
            if (blockUuids.isEmpty()) return null.right()
            if (blockUuids.size == 1) return blockUuids.first().right()
            
            val ancestorPaths = blockUuids.map { uuid ->
                getAncestorPath(uuid).getOrNull() ?: emptyList()
            }
            
            // Find the intersection of all paths
            val commonPath = ancestorPaths.reduce { acc, path ->
                acc.intersect(path.toSet()).toList()
            }
            
            // Return the deepest common ancestor (last in the list)
            commonPath.lastOrNull().right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }
    
    /**
     * Get the path from root to the specified block.
     */
    suspend fun getAncestorPath(blockUuid: String): Either<DomainError, List<String>> {
        return try {
            val ancestorsResult = blockOperations.getBlockAncestors(blockUuid).first()
            val ancestors = ancestorsResult.getOrNull() ?: emptyList()
            (ancestors.map { it.uuid } + blockUuid).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }
    
    /**
     * Count the total number of blocks in a subtree.
     */
    suspend fun countSubtreeBlocks(rootUuid: String): Either<DomainError, Int> {
        return getSubtree(rootUuid).map { it.size }
    }
    
    /**
     * Get the maximum depth of a subtree.
     */
    suspend fun getSubtreeMaxDepth(rootUuid: String): Either<DomainError, Int> {
        return getSubtree(rootUuid).map { blocks ->
            blocks.maxOfOrNull { it.depth } ?: 0
        }
    }
    
    // ===== COLLAPSE/EXPAND STATE MANAGEMENT =====
    
    /**
     * Get all collapsed blocks in a page.
     */
    suspend fun getCollapsedBlocks(pageUuid: String): Either<DomainError, List<String>> {
        return try {
            val pageBlocksResult = blockOperations.getBlocksForPage(pageUuid).first()
            val pageBlocks = pageBlocksResult.getOrNull() ?: emptyList()
            
            val collapsed = pageBlocks.filter { block ->
                block.properties.containsKey("collapsed") && 
                block.properties["collapsed"] == "true"
            }
            
            collapsed.map { it.uuid }.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }
    
    /**
     * Toggle the collapse state of a block.
     */
    suspend fun toggleCollapseState(blockUuid: String): Either<DomainError, Unit> {
        return try {
            val blockResult = blockOperations.getBlockByUuid(blockUuid).first()
            val block = blockResult.getOrNull() ?: return DomainError.DatabaseError.NotFound("block", blockUuid).left()

            val isCurrentlyCollapsed = block.properties["collapsed"] == "true"
            val newProperties = block.properties.toMutableMap().apply {
                if (isCurrentlyCollapsed) {
                    remove("collapsed")
                } else {
                    put("collapsed", "true")
                }
            }
            
            blockOperations.updateBlockPropertiesOnly(block.uuid, newProperties)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }
    
    // ===== ADVANCED MANIPULATION OPERATIONS =====
    
    /**
     * Duplicate a subtree starting from the specified root block.
     * Returns the root of the new subtree.
     */
    suspend fun duplicateSubtree(
        rootBlockUuid: String,
        targetParentUuid: String? = null,
        _targetPosition: PositioningMode = PositioningMode.END
    ): Either<DomainError, Block> {
        return try {
            // 1. Get entire subtree
            val subtree = getSubtree(rootBlockUuid).getOrNull()
                ?: return DomainError.DatabaseError.NotFound("block", rootBlockUuid).left()
            
            // 2. Prepare mapping and new blocks list
            val oldToNewUuid = mutableMapOf<String, String>()
            val newBlocks = mutableListOf<Block>()
            
            // Generate all new UUIDs first
            subtree.forEach { 
                oldToNewUuid[it.block.uuid] = UuidGenerator.generateV7()
            }
            
            // 3. Resolve target root level
            val targetLevel = if (targetParentUuid == null) {
                0
            } else {
                val targetParent = blockOperations.getBlockByUuid(targetParentUuid).first().getOrNull()
                    ?: return DomainError.DatabaseError.NotFound("block", targetParentUuid!!).left()
                targetParent.level + 1
            }

            // 4. Create new blocks with adjusted parent pointers and levels
            subtree.forEach { item ->
                val oldBlock = item.block
                val newUuid = oldToNewUuid[oldBlock.uuid]!!
                
                val newParentUuid = if (oldBlock.uuid == rootBlockUuid) {
                    targetParentUuid
                } else {
                    oldToNewUuid[oldBlock.parentUuid]
                }
                
                val itemTargetLevel = targetLevel + item.depth
                
                val newBlock = oldBlock.copy(
                    uuid = newUuid,
                    pageUuid = oldBlock.pageUuid, // Remains on same page
                    parentUuid = newParentUuid,
                    level = itemTargetLevel,
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now(),
                    version = 0L
                )
                newBlocks.add(newBlock)
            }
            
            // 5. Batch save the new blocks
            blockOperations.saveBlocks(newBlocks)
            
            // 6. Return the new root
            newBlocks.first { it.uuid == oldToNewUuid[rootBlockUuid] }.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    /**
     * Move a subtree to a new location with comprehensive validation.
     */
    suspend fun moveSubtree(
        rootUuid: String,
        targetParentUuid: String?,
        positioning: PositioningMode,
        targetUuid: String? = null
    ): Either<DomainError, Unit> {
        return try {
            // Basic validation - check if trying to move block into its own subtree
            if (targetParentUuid != null) {
                val subtree = getSubtree(rootUuid).getOrNull() ?: return DomainError.DatabaseError.NotFound("block", rootUuid).left()
                if (subtree.any { it.block.uuid == targetParentUuid }) {
                    return DomainError.DatabaseError.WriteFailed("Cannot move a block into its own subtree").left()
                }
            }

            // Get all blocks in the subtree
            val subtree = getSubtree(rootUuid).getOrNull() ?: return DomainError.DatabaseError.NotFound("block", rootUuid).left()

            val targetLevel = if (targetParentUuid == null) {
                0
            } else {
                val targetParent = blockOperations.getBlockByUuid(targetParentUuid).first().getOrNull()
                    ?: return DomainError.DatabaseError.NotFound("block", targetParentUuid).left()
                targetParent.level + 1
            }
            
            // Move the root block first
            val moveResult = blockOperations.moveBlockEnhanced(rootUuid, targetParentUuid, positioning, targetUuid)
            if (moveResult.isLeft()) return moveResult
            
            // Update levels for all descendants
            val descendants = subtree.filter { it.block.uuid != rootUuid }
            val descendantsToUpdate = descendants.map { item ->
                val newLevel = targetLevel + item.depth
                item.block.copy(level = newLevel)
            }
            if (descendantsToUpdate.isNotEmpty()) {
                blockOperations.saveBlocks(descendantsToUpdate)
            }
            
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }
    
    /**
     * Promote a subtree by moving it up the hierarchy.
     */
    suspend fun promoteSubtree(
        rootUuid: String,
        levels: Int = 1
    ): Either<DomainError, Unit> {
        return try {
            val block = blockOperations.getBlockByUuid(rootUuid).first().getOrNull()
                ?: return DomainError.DatabaseError.NotFound("block", rootUuid).left()

            if (block.parentUuid == null) {
                return DomainError.DatabaseError.WriteFailed("Cannot promote root-level block").left()
            }

            // Get the current parent and its parent
            val parent = blockOperations.getBlockParent(block.uuid).first().getOrNull()
                ?: return DomainError.DatabaseError.NotFound("block", block.parentUuid!!).left()
            
            val targetParent = if (levels == 1) {
                blockOperations.getBlockParent(parent.uuid).first().getOrNull()
            } else {
                // Need to traverse up multiple levels
                var currentParent = parent
                repeat(levels) {
                    currentParent = blockOperations.getBlockParent(currentParent.uuid).first().getOrNull()
                        ?: return DomainError.DatabaseError.WriteFailed("Cannot promote that many levels").left()
                }
                currentParent
            }
            
            moveSubtree(
                rootUuid,
                targetParent?.uuid,
                PositioningMode.AFTER,
                parent.uuid
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }
    
    /**
     * Demote a subtree by moving it down the hierarchy.
     */
    suspend fun demoteSubtree(
        rootUuid: String,
        levels: Int = 1
    ): Either<DomainError, Unit> {
        return try {
            val block = blockOperations.getBlockByUuid(rootUuid).first().getOrNull()
                ?: return DomainError.DatabaseError.NotFound("block", rootUuid).left()

            // Find the target parent by looking at siblings
            val siblings = blockOperations.getBlockSiblings(rootUuid).first().getOrNull() ?: emptyList()
            val precedingSiblings = siblings.filter { it.position < block.position }
            
            if (precedingSiblings.isEmpty()) {
                return DomainError.DatabaseError.WriteFailed("No preceding sibling to demote into").left()
            }
            
            val targetParent = precedingSiblings.last()
            
            // For multiple levels, we'd need to find the deepest descendant
            var finalTargetParent = targetParent
            repeat(levels - 1) {
                val children = blockOperations.getBlockChildren(finalTargetParent.uuid).first().getOrNull() ?: emptyList()
                if (children.isNotEmpty()) {
                    finalTargetParent = children.last()
                } else {
                    return DomainError.DatabaseError.WriteFailed("Cannot demote that many levels").left()
                }
            }
            
            moveSubtree(
                rootUuid,
                finalTargetParent.uuid,
                PositioningMode.END
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }
    
    // ===== PRIVATE HELPER METHODS =====
    
    private suspend fun collectSubtree(
        blockUuid: String,
        depth: Int,
        result: MutableList<BlockWithDepth>,
        includeCollapsed: Boolean,
        maxDepth: Int?
    ) {
        if (maxDepth != null && depth > maxDepth) return
        
        val blockResult = blockOperations.getBlockByUuid(blockUuid).first()
        val block = blockResult.getOrNull() ?: return
        result.add(BlockWithDepth(block, depth))
        
        // Check if this block is collapsed
        val isCollapsed = block.properties["collapsed"] == "true"
        if (!includeCollapsed && isCollapsed) return
        
        // Recursively collect children
        val childrenResult = blockOperations.getBlockChildren(blockUuid).first()
        val children = childrenResult.getOrNull() ?: emptyList()
        children.sortedBy { it.position }.forEach { child ->
            collectSubtree(child.uuid, depth + 1, result, includeCollapsed, maxDepth)
        }
    }
}

// ===== EXTENSIONS AND UTILITIES =====

/**
 * Extension functions for enhanced block operations.
 */
fun IBlockOperations.create() = BlockTreeOperations(this)

/**
 * Tree state for UI components.
 */
data class TreeState(
    val collapsedBlocks: Set<String> = emptySet(),
    val selectedBlocks: Set<String> = emptySet(),
    val focusedBlock: String? = null,
    val dragState: DragState? = null
)

/**
 * Drag state for block reordering.
 */
data class DragState(
    val draggedBlockUuid: String,
    val targetBlockUuid: String?,
    val positioning: PositioningMode
)

// ===== VALIDATION HELPERS =====

object BlockOperationValidator {
    
    /**
     * Validate that moving a block won't create cycles.
     */
    suspend fun validateMoveOperation(
        blockUuid: String,
        targetParentUuid: String?,
        blockOperations: IBlockOperations
    ): Either<DomainError, ValidationResult> {
        return try {
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            
            // Check if trying to move a block into its own subtree
            if (targetParentUuid != null) {
                // Use BlockTreeOperations extension
                val subtree = blockOperations.create().getSubtree(blockUuid).getOrNull()
                if (subtree?.any { it.block.uuid == targetParentUuid } == true) {
                    errors.add("Cannot move a block into its own subtree")
                }
            }
            
            // Check if target parent exists
            if (targetParentUuid != null) {
                val targetExists = blockOperations.getBlockByUuid(targetParentUuid).first().getOrNull() != null
                if (!targetExists) {
                    errors.add("Target parent block does not exist")
                }
            }
            
            ValidationResult(
                isValid = errors.isEmpty(),
                errors = errors,
                warnings = warnings
            ).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    /**
     * Validate that delete operation won't orphan important blocks.
     */
    suspend fun validateDeleteOperation(
        blockUuid: String,
        deleteStrategy: DeleteStrategy,
        blockOperations: IBlockOperations
    ): Either<DomainError, ValidationResult> {
        return try {
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            
            when (deleteStrategy) {
                DeleteStrategy.DELETE_CHILDREN -> {
                    val childCount = blockOperations.create().countSubtreeBlocks(blockUuid).getOrNull() ?: 0
                    if (childCount > 10) {
                        warnings.add("Deleting $childCount blocks in subtree")
                    }
                }
                DeleteStrategy.ORPHAN_CHILDREN -> {
                    warnings.add("Children will become orphaned (no parent)")
                }
                else -> { /* Other strategies are generally safe */ }
            }
            
            ValidationResult(
                isValid = errors.isEmpty(),
                errors = errors,
                warnings = warnings
            ).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }
}

