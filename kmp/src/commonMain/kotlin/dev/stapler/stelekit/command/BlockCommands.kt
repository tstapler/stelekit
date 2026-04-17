@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.command

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import kotlinx.coroutines.flow.first

class AddBlockCommand(
    private val repository: BlockRepository,
    private val newBlock: Block,
    private val siblingsToShift: List<Block>
) : Command<Unit> {
    override val description = "Add Block"

    override suspend fun execute(): Result<Unit> {
        // Shift siblings down (position + 1)
        val updatedSiblings = siblingsToShift.map { it.copy(position = it.position + 1) }
        val blocksToSave = updatedSiblings + newBlock
        return repository.saveBlocks(blocksToSave)
    }

    override suspend fun undo(): Result<Unit> {
        // Delete the new block
        val deleteResult = repository.deleteBlock(newBlock.uuid, deleteChildren = true)
        if (deleteResult.isFailure) return deleteResult

        // Restore siblings to original positions
        return repository.saveBlocks(siblingsToShift)
    }
}

class DeleteBlockCommand(
    private val repository: BlockRepository,
    private val blockUuid: String,
    private val siblingsToShiftUp: List<Block>
) : Command<Unit> {
    override val description = "Delete Block"
    
    private var deletedBlock: Block? = null
    private var deletedChildren: List<Block> = emptyList()

    override suspend fun execute(): Result<Unit> {
        // Capture state before deletion
        // We need to fetch the block and its hierarchy to restore it later
        val hierarchyResult = repository.getBlockHierarchy(blockUuid).first()
        val hierarchy = hierarchyResult.getOrNull() ?: return Result.failure(Exception("Block not found"))
        
        val blocks = hierarchy.map { it.block }
        deletedBlock = blocks.find { it.uuid == blockUuid }
        deletedChildren = blocks.filter { it.uuid != blockUuid }
        
        if (deletedBlock == null) return Result.failure(Exception("Block not found"))

        // Delete the block (and children)
        val deleteResult = repository.deleteBlock(blockUuid, deleteChildren = true)
        if (deleteResult.isFailure) return deleteResult
        
        // Shift siblings up (position - 1)
        val shiftedSiblings = siblingsToShiftUp.map { it.copy(position = it.position - 1) }
        if (shiftedSiblings.isNotEmpty()) {
            return repository.saveBlocks(shiftedSiblings)
        }
        
        return Result.success(Unit)
    }

    override suspend fun undo(): Result<Unit> {
        val block = deletedBlock ?: return Result.failure(Exception("No block to restore"))
        
        // Restore siblings to original positions (move them back down)
        if (siblingsToShiftUp.isNotEmpty()) {
            val restoreSiblingsResult = repository.saveBlocks(siblingsToShiftUp)
            if (restoreSiblingsResult.isFailure) return restoreSiblingsResult
        }
        
        // Restore block and children
        val allToRestore = listOf(block) + deletedChildren
        return repository.saveBlocks(allToRestore)
    }
}

class UpdateBlockContentCommand(
    private val repository: BlockRepository,
    private val blockUuid: String,
    private val oldContent: String,
    private val newContent: String
) : Command<Unit> {
    override val description = "Update Block Content"

    override suspend fun execute(): Result<Unit> {
        return updateContent(newContent)
    }

    override suspend fun undo(): Result<Unit> {
        return updateContent(oldContent)
    }
    
    private suspend fun updateContent(content: String): Result<Unit> {
        val blockResult = repository.getBlockByUuid(blockUuid).first()
        val block = blockResult.getOrNull() ?: return Result.failure(Exception("Block not found"))
        return repository.saveBlock(block.copy(content = content))
    }
}

class SplitBlockCommand(
    private val repository: BlockRepository,
    private val originalBlockUuid: String,
    private val originalContent: String,
    private val newContentForOriginal: String,
    private val newBlock: Block,
    private val siblingsToShift: List<Block>
) : Command<Unit> {
    override val description = "Split Block"

    override suspend fun execute(): Result<Unit> {
        // 1. Update original block
        val originalBlockResult = repository.getBlockByUuid(originalBlockUuid).first()
        val originalBlock = originalBlockResult.getOrNull() ?: return Result.failure(Exception("Original block not found"))
        
        val updateResult = repository.saveBlock(originalBlock.copy(content = newContentForOriginal))
        if (updateResult.isFailure) return updateResult
        
        // 2. Shift siblings and add new block
        val updatedSiblings = siblingsToShift.map { it.copy(position = it.position + 1) }
        val blocksToSave = updatedSiblings + newBlock
        return repository.saveBlocks(blocksToSave)
    }

    override suspend fun undo(): Result<Unit> {
        // 1. Delete new block
        val deleteResult = repository.deleteBlock(newBlock.uuid, deleteChildren = true)
        if (deleteResult.isFailure) return deleteResult

        // 2. Restore siblings
        val restoreSiblingsResult = repository.saveBlocks(siblingsToShift)
        if (restoreSiblingsResult.isFailure) return restoreSiblingsResult
        
        // 3. Restore original block content
        val originalBlockResult = repository.getBlockByUuid(originalBlockUuid).first()
        val originalBlock = originalBlockResult.getOrNull() ?: return Result.failure(Exception("Original block not found"))
        
        return repository.saveBlock(originalBlock.copy(content = originalContent))
    }
}

class MergeBlockCommand(
    private val repository: BlockRepository,
    private val targetBlockUuid: String, // The block that remains (previous block)
    private val targetOldContent: String,
    private val targetNewContent: String,
    private val mergedBlockUuid: String, // The block being deleted (current block)
    private val siblingsToShiftUp: List<Block>
) : Command<Unit> {
    override val description = "Merge Block"
    
    private var deletedBlock: Block? = null
    private var deletedChildren: List<Block> = emptyList()

    override suspend fun execute(): Result<Unit> {
        // 1. Update target block content
        val targetBlockResult = repository.getBlockByUuid(targetBlockUuid).first()
        val targetBlock = targetBlockResult.getOrNull() ?: return Result.failure(Exception("Target block not found"))
        
        val updateResult = repository.saveBlock(targetBlock.copy(content = targetNewContent))
        if (updateResult.isFailure) return updateResult

        // 2. Delete merged block (capture state first)
        val hierarchyResult = repository.getBlockHierarchy(mergedBlockUuid).first()
        val hierarchy = hierarchyResult.getOrNull() ?: return Result.failure(Exception("Merged block not found"))
        
        val blocks = hierarchy.map { it.block }
        deletedBlock = blocks.find { it.uuid == mergedBlockUuid }
        deletedChildren = blocks.filter { it.uuid != mergedBlockUuid }
        
        val deleteResult = repository.deleteBlock(mergedBlockUuid, deleteChildren = true)
        if (deleteResult.isFailure) return deleteResult
        
        // 3. Shift siblings up
        val shiftedSiblings = siblingsToShiftUp.map { it.copy(position = it.position - 1) }
        if (shiftedSiblings.isNotEmpty()) {
            return repository.saveBlocks(shiftedSiblings)
        }
        
        return Result.success(Unit)
    }

    override suspend fun undo(): Result<Unit> {
        // 1. Restore siblings
        if (siblingsToShiftUp.isNotEmpty()) {
            val restoreSiblingsResult = repository.saveBlocks(siblingsToShiftUp)
            if (restoreSiblingsResult.isFailure) return restoreSiblingsResult
        }
        
        // 2. Restore deleted block
        val block = deletedBlock ?: return Result.failure(Exception("No block to restore"))
        val allToRestore = listOf(block) + deletedChildren
        val restoreBlockResult = repository.saveBlocks(allToRestore)
        if (restoreBlockResult.isFailure) return restoreBlockResult
        
        // 3. Restore target block content
        val targetBlockResult = repository.getBlockByUuid(targetBlockUuid).first()
        val targetBlock = targetBlockResult.getOrNull() ?: return Result.failure(Exception("Target block not found"))
        
        return repository.saveBlock(targetBlock.copy(content = targetOldContent))
    }
}

class IndentBlockCommand(
    private val repository: BlockRepository,
    private val blockUuid: String
) : Command<Unit> {
    override val description = "Indent Block"

    override suspend fun execute(): Result<Unit> {
        return repository.indentBlock(blockUuid)
    }

    override suspend fun undo(): Result<Unit> {
        return repository.outdentBlock(blockUuid)
    }
}

class OutdentBlockCommand(
    private val repository: BlockRepository,
    private val blockUuid: String
) : Command<Unit> {
    override val description = "Outdent Block"

    override suspend fun execute(): Result<Unit> {
        return repository.outdentBlock(blockUuid)
    }

    override suspend fun undo(): Result<Unit> {
        return repository.indentBlock(blockUuid)
    }
}

class MoveBlockUpCommand(
    private val repository: BlockRepository,
    private val blockUuid: String
) : Command<Unit> {
    override val description = "Move Block Up"

    override suspend fun execute(): Result<Unit> {
        return repository.moveBlockUp(blockUuid)
    }

    override suspend fun undo(): Result<Unit> {
        return repository.moveBlockDown(blockUuid)
    }
}

class MoveBlockDownCommand(
    private val repository: BlockRepository,
    private val blockUuid: String
) : Command<Unit> {
    override val description = "Move Block Down"

    override suspend fun execute(): Result<Unit> {
        return repository.moveBlockDown(blockUuid)
    }

    override suspend fun undo(): Result<Unit> {
        return repository.moveBlockUp(blockUuid)
    }
}
