package dev.stapler.stelekit.editor.blocks

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.BlockWithDepth
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.Result

/**
 * Core interface for rich editor block operations.
 * Provides high-level CRUD and hierarchical operations for blocks.
 * 
 * This interface extends the basic BlockRepository with editor-specific
 * operations and enhanced functionality for rich text editing scenarios.
 * 
 * Updated to use UUID-native storage.
 */
interface IBlockOperations : BlockRepository {
    
    // ===== ENHANCED CRUD OPERATIONS =====
    
    /**
     * Create a new block with enhanced validation and positioning support.
     * 
     * @param pageId The page UUID where the block will be created
     * @param content The block content
     * @param parentId Optional parent block UUID for hierarchy
     * @param leftId Optional left sibling block UUID for positioning
     * @param position Position within siblings (calculated if not provided)
     * @param properties Optional block properties
     * @param uuid Optional UUID (generated if not provided)
     * @param createdAt Optional creation timestamp (current time if not provided)
     * @return Result containing the created block or error
     */
    suspend fun createBlock(
        pageId: String,
        content: String,
        parentId: String? = null,
        leftId: String? = null,
        position: Int? = null,
        properties: Map<String, String> = emptyMap(),
        uuid: String? = null,
        createdAt: Instant? = null
    ): Result<Block>
    
    /**
     * Update block content with validation and automatic timestamp update.
     * 
     * @param blockUuid The UUID of the block to update
     * @param content The new content
     * @param properties Optional properties to merge/replace
     * @return Result containing the updated block or error
     */
    suspend fun updateBlockContent(
        blockUuid: String,
        content: String,
        properties: Map<String, String>? = null
    ): Result<Block>
    
    /**
     * Update block properties with merge support.
     * 
     * @param blockUuid The UUID of the block to update
     * @param properties Properties to merge with existing ones
     * @param mergeMode Whether to merge with existing properties (true) or replace all (false)
     * @return Result containing the updated block or error
     */
    suspend fun updateBlockProperties(
        blockUuid: String,
        properties: Map<String, String>,
        mergeMode: Boolean = true
    ): Result<Block>
    
    /**
     * Delete a block with enhanced child handling options.
     * 
     * @param blockUuid The UUID of the block to delete
     * @param deleteStrategy How to handle child blocks
     * @return Result indicating success or error
     */
    suspend fun deleteBlockEnhanced(
        blockUuid: String,
        deleteStrategy: DeleteStrategy = DeleteStrategy.DELETE_CHILDREN
    ): Result<Unit>
    
    // ===== ENHANCED HIERARCHICAL OPERATIONS =====
    
    /**
     * Move a block to a new parent with enhanced positioning options.
     * 
     * @param blockUuid The UUID of the block to move
     * @param targetParentUuid Optional new parent UUID (null for root level)
     * @param positioning How to position the block in the new location
     * @param targetUuid Target block UUID for relative positioning (used with positioning modes)
     * @return Result indicating success or error
     */
    suspend fun moveBlockEnhanced(
        blockUuid: String,
        targetParentUuid: String? = null,
        positioning: PositioningMode = PositioningMode.END,
        targetUuid: String? = null
    ): Result<Unit>
    
    /**
     * Indent a block with enhanced validation and options.
     * 
     * @param blockUuid The UUID of the block to indent
     * @param indentMode How to handle the indentation
     * @return Result indicating success or error
     */
    suspend fun indentBlockEnhanced(
        blockUuid: String,
        indentMode: IndentMode = IndentMode.TO_PREVIOUS_SIBLING
    ): Result<Unit>
    
    /**
     * Outdent a block with enhanced validation and options.
     * 
     * @param blockUuid The UUID of the block to outdent
     * @param targetLevel Optional target level (calculated if not provided)
     * @return Result indicating success or error
     */
    suspend fun outdentBlockEnhanced(
        blockUuid: String,
        targetLevel: Int? = null
    ): Result<Unit>
    
    // ===== BLOCK DUPLICATION OPERATIONS =====
    
    /**
     * Duplicate a block with options for handling children.
     * 
     * @param blockUuid The UUID of the block to duplicate
     * @param includeChildren Whether to duplicate all descendants
     * @param targetPosition Where to place the duplicated block
     * @param targetUuid Target block UUID for relative positioning
     * @return Result containing the duplicated block or error
     */
    suspend fun duplicateBlock(
        blockUuid: String,
        includeChildren: Boolean = false,
        targetPosition: PositioningMode = PositioningMode.AFTER,
        targetUuid: String? = null
    ): Result<Block>
    
    /**
     * Duplicate an entire subtree.
     * 
     * @param rootBlockUuid The UUID of the root block to duplicate
     * @param targetParentUuid Optional new parent for the duplicated subtree
     * @param targetPosition How to position the duplicated subtree
     * @return Result containing the root of the duplicated subtree or error
     */
    suspend fun duplicateSubtree(
        rootBlockUuid: String,
        targetParentUuid: String? = null,
        targetPosition: PositioningMode = PositioningMode.END
    ): Result<Block>
    
    // ===== TEXT OPERATIONS =====
    
    /**
     * Split a block at the specified cursor position.
     * 
     * @param blockUuid The UUID of the block to split
     * @param cursorPosition Character position where to split
     * @param keepContentInOriginal Whether to keep content after cursor in original (true) or move to new block (false)
     * @return Result containing the new block created from the split or error
     */
    suspend fun splitBlock(
        blockUuid: String,
        cursorPosition: Int,
        keepContentInOriginal: Boolean = true
    ): Result<Block>
    
    /**
     * Merge a block with its next sibling.
     * 
     * @param blockUuid The UUID of the block to merge (will absorb next sibling's content)
     * @param separator Text to insert between the merged contents
     * @return Result containing the updated block or error
     */
    suspend fun mergeWithNext(
        blockUuid: String,
        separator: String = " "
    ): Result<Block>
    
    /**
     * Merge a block with its previous sibling.
     * 
     * @param blockUuid The UUID of the block to merge (will be merged into previous sibling)
     * @param separator Text to insert between the merged contents
     * @return Result containing the updated previous block or error
     */
    suspend fun mergeWithPrevious(
        blockUuid: String,
        separator: String = " "
    ): Result<Block>
    
    // ===== TREE OPERATIONS =====
    
    /**
     * Collapse a subtree (mark as collapsed in UI state).
     * 
     * @param blockUuid The UUID of the root block to collapse
     * @param recursive Whether to collapse all nested subtrees
     * @return Result indicating success or error
     */
    suspend fun collapseSubtree(
        blockUuid: String,
        recursive: Boolean = false
    ): Result<Unit>
    
    /**
     * Expand a collapsed subtree.
     * 
     * @param blockUuid The UUID of the root block to expand
     * @param recursive Whether to expand all nested subtrees
     * @return Result indicating success or error
     */
    suspend fun expandSubtree(
        blockUuid: String,
        recursive: Boolean = false
    ): Result<Unit>
    
    /**
     * Promote a subtree (move all blocks up one level in hierarchy).
     * 
     * @param blockUuid The UUID of the root block to promote
     * @param levels Number of levels to promote (default: 1)
     * @return Result indicating success or error
     */
    suspend fun promoteSubtree(
        blockUuid: String,
        levels: Int = 1
    ): Result<Unit>
    
    /**
     * Demote a subtree (move all blocks down one level in hierarchy).
     * 
     * @param blockUuid The UUID of the root block to demote
     * @param levels Number of levels to demote (default: 1)
     * @return Result indicating success or error
     */
    suspend fun demoteSubtree(
        blockUuid: String,
        levels: Int = 1
    ): Result<Unit>
    
    // ===== BULK OPERATIONS =====
    
    /**
     * Apply multiple operations in a single transaction.
     * 
     * @param operations List of operations to apply
     * @return Result indicating success or error
     */
    suspend fun applyBulkOperations(
        operations: List<BulkOperation>
    ): Result<Unit>
    
    /**
     * Reorder multiple blocks at once.
     * 
     * @param blockUuids List of block UUIDs in their new order
     * @return Result indicating success or error
     */
    suspend fun reorderBlocks(
        blockUuids: List<String>
    ): Result<Unit>
    
    // ===== VALIDATION AND UTILITIES =====
    
    /**
     * Validate that a block operation would maintain tree integrity.
     * 
     * @param operation The operation to validate
     * @return Result containing validation result or error
     */
    suspend fun validateOperation(
        operation: BlockOperation
    ): Result<ValidationResult>
    
    /**
     * Get operation history for undo/redo functionality.
     */
    fun getOperationHistory(): Flow<Result<List<HistoricalOperation>>>
    
    /**
     * Undo the last operation.
     */
    suspend fun undo(): Result<Unit>
    
    /**
     * Redo the last undone operation.
     */
    suspend fun redo(): Result<Unit>
}

// ===== ENUMS AND DATA CLASSES =====

/**
 * Strategy for handling child blocks when deleting a block.
 */
enum class DeleteStrategy {
    /** Delete only the specified block, re-parent children to parent */
    DELETE_ONLY,
    /** Delete the block and all its descendants */
    DELETE_CHILDREN,
    /** Orphan children (set parentId to null) */
    ORPHAN_CHILDREN,
    /** Re-parent children to the deleted block's parent */
    REPARENT_CHILDREN
}

/**
 * How to position a block when moving or creating.
 */
enum class PositioningMode {
    /** Place at the end of the sibling list */
    END,
    /** Place at the beginning of the sibling list */
    START,
    /** Place before the specified target block */
    BEFORE,
    /** Place after the specified target block */
    AFTER,
    /** Replace the specified target block */
    REPLACE
}

/**
 * How to handle indentation operations.
 */
enum class IndentMode {
    /** Indent to become a child of the previous sibling */
    TO_PREVIOUS_SIBLING,
    /** Indent by one level generically */
    ONE_LEVEL,
    /** Indent to match the previous sibling's level */
    MATCH_PREVIOUS_LEVEL
}

/**
 * Result of validation operations.
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

/**
 * A bulk operation that can be applied to multiple blocks.
 */
sealed class BulkOperation {
    data class Move(
        val blockUuids: List<String>,
        val targetParentUuid: String?,
        val positioning: PositioningMode,
        val targetUuid: String? = null
    ) : BulkOperation()
    
    data class Delete(
        val blockUuids: List<String>,
        val deleteStrategy: DeleteStrategy
    ) : BulkOperation()
    
    data class UpdateProperties(
        val blockUuids: List<String>,
        val properties: Map<String, String>,
        val mergeMode: Boolean = true
    ) : BulkOperation()
    
    data class ChangeLevel(
        val blockUuids: List<String>,
        val levelDelta: Int
    ) : BulkOperation()
}

/**
 * Base class for block operations that can be validated and undone.
 */
sealed class BlockOperation {
    abstract val blockUuid: String
    abstract val timestamp: Instant
}

/**
 * Historical operation for undo/redo functionality.
 */
data class HistoricalOperation(
    val operation: BlockOperation,
    val inverseOperation: BlockOperation,
    val description: String,
    val timestamp: Instant
)
