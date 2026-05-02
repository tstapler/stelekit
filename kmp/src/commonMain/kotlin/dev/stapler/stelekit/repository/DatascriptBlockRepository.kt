package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.outliner.TreeOperations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

/**
 * Datalog-style in-memory repository that mirrors Datascript behavior.
 * Uses Datalog query patterns similar to Logseq's Clojure implementation.
 *
 * Updated to use UUID-native storage.
 */
@OptIn(DirectRepositoryWrite::class)
class DatascriptBlockRepository : BlockRepository {
    private val logger = Logger("BlockRepo")
    private val writeMutex = Mutex()

    private val blocks = MutableStateFlow<Map<String, Block>>(emptyMap())

    /**
     * Datalog-style index for fast lookups
     */
    private val byUuid = MutableStateFlow<Map<String, Block>>(emptyMap())
    private val byPageUuid = MutableStateFlow<Map<String, List<Block>>>(emptyMap())
    private val byParentUuid = MutableStateFlow<Map<String?, List<Block>>>(emptyMap())

    override fun getBlockByUuid(uuid: String): Flow<Either<DomainError, Block?>> {
        return blocks.map { map ->
            map[uuid].right()
        }
    }

    override fun getBlockChildren(blockUuid: String): Flow<Either<DomainError, List<Block>>> {
        return byParentUuid.map { map ->
            (map[blockUuid]?.sortedBy { it.position } ?: emptyList()).right()
        }
    }

    override fun getBlockHierarchy(rootUuid: String): Flow<Either<DomainError, List<BlockWithDepth>>> {
        return blocks.map { map ->
            val result = mutableListOf<BlockWithDepth>()
            collectHierarchy(map, rootUuid, 0, result)
            result.right()
        }
    }

    private fun collectHierarchy(
        allBlocks: Map<String, Block>,
        uuid: String,
        depth: Int,
        result: MutableList<BlockWithDepth>
    ) {
        val block = allBlocks[uuid] ?: return
        result.add(BlockWithDepth(block, depth))
        val children = byParentUuid.value[block.uuid]?.sortedBy { it.position } ?: emptyList()
        children.forEach { child ->
            collectHierarchy(allBlocks, child.uuid, depth + 1, result)
        }
    }

    override fun getBlockAncestors(blockUuid: String): Flow<Either<DomainError, List<Block>>> {
        return blocks.map { map ->
            val ancestors = mutableListOf<Block>()
            var currentUuid: String? = blockUuid
            while (currentUuid != null) {
                val block = map[currentUuid] ?: break
                if (block.parentUuid != null) {
                    val parent = map[block.parentUuid]
                    if (parent != null) {
                        ancestors.add(parent)
                        currentUuid = parent.uuid
                    } else {
                        break
                    }
                } else {
                    break
                }
            }
            ancestors.reversed().right()
        }
    }

    override fun getBlockParent(blockUuid: String): Flow<Either<DomainError, Block?>> {
        return blocks.map { map ->
            val block = map[blockUuid] ?: return@map null.right()
            val parent = block.parentUuid?.let { map[it] }
            parent.right()
        }
    }

    override fun getBlockSiblings(blockUuid: String): Flow<Either<DomainError, List<Block>>> {
        return blocks.map { map ->
            val block = map[blockUuid] ?: return@map emptyList<Block>().right()
            val siblings = if (block.parentUuid != null) {
                map.values.filter { it.parentUuid == block.parentUuid && it.pageUuid == block.pageUuid && it.uuid != blockUuid }
            } else {
                map.values.filter { it.parentUuid == null && it.pageUuid == block.pageUuid && it.uuid != blockUuid }
            }
            siblings.sortedBy { it.position }.right()
        }
    }

    override fun getBlocksForPage(pageUuid: String): Flow<Either<DomainError, List<Block>>> {
        return byPageUuid.map { map ->
            val blocks = map[pageUuid] ?: emptyList()
            blocks.sortedBy { it.position }.right()
        }
    }

    override fun getLinkedReferences(pageName: String): Flow<Either<DomainError, List<Block>>> {
        val wikiLinkPattern = "\\[\\[${Regex.escape(pageName)}\\]\\]".toRegex(RegexOption.IGNORE_CASE)
        return blocks.map { map ->
            val linkedBlocks = map.values.filter { block ->
                wikiLinkPattern.containsMatchIn(block.content)
            }
            linkedBlocks.sortedBy { it.pageUuid }.right()
        }
    }

    override fun getLinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> {
        val wikiLinkPattern = "\\[\\[${Regex.escape(pageName)}\\]\\]".toRegex(RegexOption.IGNORE_CASE)
        return blocks.map { map ->
            val linkedBlocks = map.values.filter { block ->
                wikiLinkPattern.containsMatchIn(block.content)
            }
            linkedBlocks.sortedBy { it.pageUuid }.drop(offset).take(limit).right()
        }
    }

    override fun getUnlinkedReferences(pageName: String): Flow<Either<DomainError, List<Block>>> {
        val wikiLinkPattern = "\\[\\[${Regex.escape(pageName)}\\]\\]".toRegex(RegexOption.IGNORE_CASE)
        val plainTextPattern = "\\b${Regex.escape(pageName)}\\b".toRegex(RegexOption.IGNORE_CASE)
        return blocks.map { map ->
            val unlinkedBlocks = map.values.filter { block ->
                plainTextPattern.containsMatchIn(block.content) &&
                    !wikiLinkPattern.containsMatchIn(block.content)
            }
            unlinkedBlocks.sortedBy { it.pageUuid }.right()
        }
    }

    override fun getUnlinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> {
        val wikiLinkPattern = "\\[\\[${Regex.escape(pageName)}\\]\\]".toRegex(RegexOption.IGNORE_CASE)
        val plainTextPattern = "\\b${Regex.escape(pageName)}\\b".toRegex(RegexOption.IGNORE_CASE)
        return blocks.map { map ->
            val unlinkedBlocks = map.values.filter { block ->
                plainTextPattern.containsMatchIn(block.content) &&
                    !wikiLinkPattern.containsMatchIn(block.content)
            }
            unlinkedBlocks.sortedBy { it.pageUuid }.drop(offset).take(limit).right()
        }
    }

    override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> {
        return byUuid.map { map ->
            val matching = map.values.filter { it.content.contains(query, ignoreCase = true) }
                .drop(offset)
                .take(limit)
            matching.right()
        }
    }

    override suspend fun saveBlocks(blocks: List<Block>): Either<DomainError, Unit> {
        return writeMutex.withLock {
            try {
                val updateMap = blocks.associateBy { it.uuid }
                batchUpdateBlocks(updateMap)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to save batch blocks", e)
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override suspend fun saveBlock(block: Block): Either<DomainError, Unit> {
        return writeMutex.withLock {
            try {
                val updateMap = mapOf(block.uuid to block)
                batchUpdateBlocks(updateMap)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to save block ${block.uuid}", e)
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override suspend fun updateBlockContentOnly(blockUuid: String, content: String): Either<DomainError, Unit> {
        return writeMutex.withLock {
            try {
                val current = blocks.value.toMutableMap()
                val existing = current[blockUuid] ?: return@withLock Unit.right()
                val updated = existing.copy(content = content, version = existing.version + 1, updatedAt = kotlin.time.Clock.System.now())
                batchUpdateBlocks(mapOf(blockUuid to updated))
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override suspend fun updateBlockPropertiesOnly(blockUuid: String, properties: Map<String, String>): Either<DomainError, Unit> {
        return writeMutex.withLock {
            try {
                val current = blocks.value.toMutableMap()
                val existing = current[blockUuid] ?: return@withLock Unit.right()
                batchUpdateBlocks(mapOf(blockUuid to existing.copy(properties = properties)))
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override suspend fun deleteBlock(blockUuid: String, deleteChildren: Boolean): Either<DomainError, Unit> {
        return writeMutex.withLock {
            try {
                val current = blocks.value.toMutableMap()
                if (!current.containsKey(blockUuid)) return@withLock Unit.right()

                if (deleteChildren) {
                    val uuidsToDelete = mutableListOf(blockUuid)
                    var index = 0
                    while (index < uuidsToDelete.size) {
                        val currentUuid = uuidsToDelete[index]
                        val children = current.values.filter { it.parentUuid == currentUuid }
                        children.forEach { child ->
                            uuidsToDelete.add(child.uuid)
                        }
                        index++
                    }
                    uuidsToDelete.forEach { current.remove(it) }
                } else {
                    current.remove(blockUuid)
                }
                
                blocks.value = current
                refreshIndexes(current)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override suspend fun deleteBulk(blockUuids: List<String>, deleteChildren: Boolean): Either<DomainError, Unit> {
        blockUuids.forEach { uuid ->
            deleteBlock(uuid, deleteChildren)
        }
        return Unit.right()
    }

    override suspend fun moveBlock(
        blockUuid: String,
        newParentUuid: String?,
        newPosition: Int
    ): Either<DomainError, Unit> {
        return writeMutex.withLock {
            try {
                val currentBlocks = blocks.value
                val block = currentBlocks[blockUuid] ?: return@withLock Unit.right()

                if (block.parentUuid == newParentUuid && block.position == newPosition) {
                    return@withLock Unit.right()
                }

                val oldParentUuid = block.parentUuid
                val oldSiblings = currentBlocks.values
                    .filter { it.parentUuid == oldParentUuid && it.uuid != blockUuid }
                    .sortedBy { it.position }

                val newSiblings = if (oldParentUuid == newParentUuid) {
                    oldSiblings.toMutableList().apply { add(newPosition.coerceIn(0, size), block) }
                } else {
                    currentBlocks.values
                        .filter { it.parentUuid == newParentUuid }
                        .sortedBy { it.position }
                        .toMutableList().apply { add(newPosition.coerceIn(0, size), block) }
                }

                val updatedBlocks = mutableMapOf<String, Block>()

                // Update moved block and its descendants
                val newLevel = if (newParentUuid == null) 0 else (currentBlocks[newParentUuid]?.level ?: -1) + 1
                val levelOffset = newLevel - block.level
                val hierarchy = mutableListOf<BlockWithDepth>()
                collectHierarchy(currentBlocks, block.uuid, block.level, hierarchy)

                hierarchy.forEach { (b, _) ->
                    updatedBlocks[b.uuid] = b.copy(
                        parentUuid = if (b.uuid == blockUuid) newParentUuid else b.parentUuid,
                        level = b.level + levelOffset
                    )
                }

                // Update siblings in old parent
                if (oldParentUuid != newParentUuid) {
                    TreeOperations.reorderSiblings(oldSiblings).forEach { updatedBlocks[it.uuid] = it }
                }

                // Update siblings in new parent
                TreeOperations.reorderSiblings(newSiblings).forEach {
                    val existing = updatedBlocks[it.uuid]
                    updatedBlocks[it.uuid] = existing?.copy(position = it.position, leftUuid = it.leftUuid) ?: it
                }

                batchUpdateBlocks(updatedBlocks)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override suspend fun indentBlock(blockUuid: String): Either<DomainError, Unit> {
        return writeMutex.withLock {
            try {
                val currentBlocks = blocks.value
                val block = currentBlocks[blockUuid] ?: return@withLock Unit.right()
                val siblings = currentBlocks.values
                    .filter { it.parentUuid == block.parentUuid && it.pageUuid == block.pageUuid }
                    .sortedBy { it.position }

                val index = siblings.indexOfFirst { it.uuid == blockUuid }
                if (index <= 0) return@withLock Unit.right()

                val newParent = siblings[index - 1]
                val newParentChildren = currentBlocks.values
                    .filter { it.parentUuid == newParent.uuid && it.pageUuid == block.pageUuid }
                    .sortedBy { it.position }

                val result = TreeOperations.indent(block, siblings, newParentChildren.lastOrNull())
                
                if (result != null) {
                    val updates = result.associateBy { it.uuid }.toMutableMap()
                    
                    val remainingSiblings = siblings.filter { it.uuid != blockUuid }.toMutableList()
                    result.forEach { updated -> 
                        val idx = remainingSiblings.indexOfFirst { it.uuid == updated.uuid }
                        if (idx != -1) remainingSiblings[idx] = updated
                    }
                    
                    val movedBlock = result.find { it.uuid == blockUuid }!!
                    val newSiblings = newParentChildren + movedBlock
                    
                    TreeOperations.reorderSiblings(remainingSiblings).forEach { updates[it.uuid] = it }
                    TreeOperations.reorderSiblings(newSiblings).forEach { updates[it.uuid] = it }

                    batchUpdateBlocks(updates)
                    Unit.right()
                } else {
                    Unit.right()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override suspend fun outdentBlock(blockUuid: String): Either<DomainError, Unit> {
        return writeMutex.withLock {
            try {
                val currentBlocks = blocks.value
                val block = currentBlocks[blockUuid] ?: return@withLock Unit.right()
                val parentUuid = block.parentUuid ?: return@withLock Unit.right()

                val parent = currentBlocks[parentUuid]

                val siblings = currentBlocks.values
                    .filter { it.parentUuid == block.parentUuid && it.pageUuid == block.pageUuid }
                    .sortedBy { it.position }

                val parentSiblings = currentBlocks.values
                    .filter { it.parentUuid == parent?.parentUuid && it.pageUuid == block.pageUuid }
                    .sortedBy { it.position }

                val result = TreeOperations.outdent(block, parent, siblings, parentSiblings)
                if (result != null) {
                    val updates = result.associateBy { it.uuid }.toMutableMap()

                    val movedBlock = result.find { it.uuid == blockUuid }!!

                    val remainingOldSiblings = siblings.filter { it.uuid != blockUuid }
                    TreeOperations.reorderSiblings(remainingOldSiblings).forEach { updates[it.uuid] = it }

                    val parentIndex = parentSiblings.indexOfFirst { it.uuid == parentUuid }
                    val newSiblingsList = parentSiblings.toMutableList()
                    newSiblingsList.add(parentIndex + 1, movedBlock)
                    TreeOperations.reorderSiblings(newSiblingsList).forEach { updates[it.uuid] = it }

                    batchUpdateBlocks(updates)
                    Unit.right()
                } else {
                    Unit.right()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override suspend fun moveBlockUp(blockUuid: String): Either<DomainError, Unit> {
        return writeMutex.withLock {
            try {
                val currentBlocks = blocks.value
                val block = currentBlocks[blockUuid] ?: return@withLock Unit.right()
                val siblings = currentBlocks.values
                    .filter { it.parentUuid == block.parentUuid && it.pageUuid == block.pageUuid }
                    .sortedBy { it.position }
                    .toMutableList()

                val index = siblings.indexOfFirst { it.uuid == blockUuid }
                if (index <= 0) return@withLock Unit.right()

                val prev = siblings[index - 1]
                siblings[index - 1] = siblings[index]
                siblings[index] = prev

                val updates = TreeOperations.reorderSiblings(siblings).associateBy { it.uuid }
                batchUpdateBlocks(updates)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override suspend fun moveBlockDown(blockUuid: String): Either<DomainError, Unit> {
        return writeMutex.withLock {
            try {
                val currentBlocks = blocks.value
                val block = currentBlocks[blockUuid] ?: return@withLock Unit.right()
                val siblings = currentBlocks.values
                    .filter { it.parentUuid == block.parentUuid && it.pageUuid == block.pageUuid }
                    .sortedBy { it.position }
                    .toMutableList()

                val index = siblings.indexOfFirst { it.uuid == blockUuid }
                if (index < 0 || index >= siblings.size - 1) return@withLock Unit.right()

                val next = siblings[index + 1]
                siblings[index + 1] = siblings[index]
                siblings[index] = next

                val updates = TreeOperations.reorderSiblings(siblings).associateBy { it.uuid }
                batchUpdateBlocks(updates)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override suspend fun mergeBlocks(blockUuid: String, nextBlockUuid: String, separator: String): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun splitBlock(blockUuid: String, cursorPosition: Int): Either<DomainError, Block> {
        return DomainError.DatabaseError.WriteFailed("splitBlock not implemented").left()
    }

    override suspend fun deleteBlocksForPage(pageUuid: String): Either<DomainError, Unit> {
        return writeMutex.withLock {
            try {
                val current = blocks.value.toMutableMap()
                val toRemove = current.values.filter { it.pageUuid == pageUuid }.map { it.uuid }
                toRemove.forEach { current.remove(it) }
                blocks.value = current
                refreshIndexes(current)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override suspend fun deleteBlocksForPages(pageUuids: List<String>): Either<DomainError, Unit> {
        if (pageUuids.isEmpty()) return Unit.right()
        return writeMutex.withLock {
            try {
                val uuidSet = pageUuids.toSet()
                val current = blocks.value.toMutableMap()
                val toRemove = current.values.filter { it.pageUuid in uuidSet }.map { it.uuid }
                toRemove.forEach { current.remove(it) }
                blocks.value = current
                refreshIndexes(current)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override suspend fun clear() {
        writeMutex.withLock {
            blocks.value = emptyMap()
            byUuid.value = emptyMap()
            byPageUuid.value = emptyMap()
            byParentUuid.value = emptyMap()
        }
    }

    private fun batchUpdateBlocks(updatedBlocks: Map<String, Block>) {
        val current = blocks.value.toMutableMap()
        updatedBlocks.forEach { (uuid, block) -> current[uuid] = block }
        blocks.value = current
        refreshIndexes(current)
    }
    
    override fun countLinkedReferences(pageName: String): Flow<Either<DomainError, Long>> =
        flowOf(0L.right())

    override fun findDuplicateBlocks(limit: Int): Flow<Either<DomainError, List<DuplicateGroup>>> =
        flowOf(emptyList<DuplicateGroup>().right())

    private fun refreshIndexes(currentBlocks: Map<String, Block>) {
        val allBlocks = currentBlocks.values
        byUuid.value = currentBlocks
        byPageUuid.value = allBlocks.groupBy { it.pageUuid }
        byParentUuid.value = allBlocks.groupBy { it.parentUuid }
    }
}
