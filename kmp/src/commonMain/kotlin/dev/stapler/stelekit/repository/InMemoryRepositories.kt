package dev.stapler.stelekit.repository

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.Property
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlin.Result.Companion.success
import dev.stapler.stelekit.util.ContentHasher

/**
 * In-memory implementation of BlockRepository for testing purposes.
 * Updated to use UUID-native storage.
 */
@OptIn(DirectRepositoryWrite::class)
class InMemoryBlockRepository : BlockRepository {

    private val blocks = MutableStateFlow<Map<String, Block>>(emptyMap())

    override fun getBlockByUuid(uuid: String): Flow<Result<Block?>> {
        return blocks.map { map ->
            success(map[uuid])
        }
    }

    override fun getBlockChildren(blockUuid: String): Flow<Result<List<Block>>> {
        return blocks.map { map ->
            success(map.values.filter { it.parentUuid == blockUuid }.sortedBy { it.position })
        }
    }

    override fun getBlockHierarchy(rootUuid: String): Flow<Result<List<BlockWithDepth>>> {
        return blocks.map { map ->
            val result = mutableListOf<BlockWithDepth>()
            collectHierarchy(map, rootUuid, 0, result)
            success(result)
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
        val children = allBlocks.values.filter { it.parentUuid == block.uuid }.sortedBy { it.position }
        children.forEach { child ->
            collectHierarchy(allBlocks, child.uuid, depth + 1, result)
        }
    }

    override fun getBlockAncestors(blockUuid: String): Flow<Result<List<Block>>> {
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
            success(ancestors.reversed())
        }
    }

    override fun getBlockParent(blockUuid: String): Flow<Result<Block?>> {
        return blocks.map { map ->
            val block = map[blockUuid] ?: return@map success(null)
            val parent = block.parentUuid?.let { map[it] }
            success(parent)
        }
    }

    override fun getBlockSiblings(blockUuid: String): Flow<Result<List<Block>>> {
        return blocks.map { map ->
            val block = map[blockUuid] ?: return@map success(emptyList())
            val siblings = if (block.parentUuid != null) {
                map.values.filter { it.parentUuid == block.parentUuid && it.uuid != blockUuid }
            } else {
                map.values.filter { it.parentUuid == null && it.uuid != blockUuid && it.pageUuid == block.pageUuid }
            }
            success(siblings.sortedBy { it.position })
        }
    }

    override fun getBlocksForPage(pageUuid: String): Flow<Result<List<Block>>> {
        return blocks.map { map ->
            val pageBlocks = map.values.filter { it.pageUuid == pageUuid }.sortedBy { it.position }
            success(pageBlocks)
        }
    }

    override suspend fun deleteBlocksForPage(pageUuid: String): Result<Unit> {
        val current = this.blocks.value.toMutableMap()
        val toRemove = current.values.filter { it.pageUuid == pageUuid }.map { it.uuid }
        toRemove.forEach { current.remove(it) }
        this.blocks.value = current
        return success(Unit)
    }

    override suspend fun deleteBlocksForPages(pageUuids: List<String>): Result<Unit> {
        if (pageUuids.isEmpty()) return success(Unit)
        val uuidSet = pageUuids.toSet()
        val current = this.blocks.value.toMutableMap()
        val toRemove = current.values.filter { it.pageUuid in uuidSet }.map { it.uuid }
        toRemove.forEach { current.remove(it) }
        this.blocks.value = current
        return success(Unit)
    }

    override suspend fun clear() {
        blocks.value = emptyMap()
    }

    override suspend fun saveBlocks(blocks: List<Block>): Result<Unit> {
        val current = this.blocks.value.toMutableMap()
        blocks.forEach { current[it.uuid] = it }
        this.blocks.value = current
        return success(Unit)
    }

    override suspend fun saveBlock(block: Block): Result<Unit> {
        val current = blocks.value.toMutableMap()
        current[block.uuid] = block
        blocks.value = current
        return success(Unit)
    }

    override suspend fun deleteBlock(blockUuid: String, deleteChildren: Boolean): Result<Unit> {
        val current = blocks.value.toMutableMap()
        val block = current[blockUuid] ?: return success(Unit)

        if (deleteChildren) {
            val uuidsToDelete = mutableListOf(blockUuid)
            var index = 0
            while (index < uuidsToDelete.size) {
                val currentUuid = uuidsToDelete[index]
                val childBlocks = current.values.filter { it.parentUuid == currentUuid }
                childBlocks.forEach { child ->
                    uuidsToDelete.add(child.uuid)
                }
                index++
            }
            uuidsToDelete.forEach { current.remove(it) }
            // Repair sibling chain: fix positions of remaining siblings after deletion
            repairSiblingPositions(current, block.pageUuid, block.parentUuid)
        } else {
            // Orphan children - they become root blocks (parent = null, level = 0)
            val children = current.values.filter { it.parentUuid == blockUuid }
            for (child in children) {
                val levelDelta = 0 - child.level
                val updatedChild = child.copy(parentUuid = null, level = 0)
                current[child.uuid] = updatedChild
                
                // Recursively adjust levels for descendants
                adjustDescendantLevels(child.uuid, levelDelta, current)
            }
            current.remove(blockUuid)
            // Repair sibling chain: fix positions of remaining siblings after deletion
            repairSiblingPositions(current, block.pageUuid, block.parentUuid)
        }
        blocks.value = current
        return success(Unit)
    }

    override suspend fun deleteBulk(blockUuids: List<String>, deleteChildren: Boolean): Result<Unit> {
        blockUuids.forEach { uuid ->
            deleteBlock(uuid, deleteChildren)
        }
        return success(Unit)
    }

    private fun repairSiblingPositions(
        current: MutableMap<String, Block>,
        pageUuid: String,
        parentUuid: String?
    ) {
        val siblings = current.values
            .filter { it.pageUuid == pageUuid && it.parentUuid == parentUuid }
            .sortedBy { it.position }
        
        var expectedPosition = 0
        for (sibling in siblings) {
            if (sibling.position != expectedPosition) {
                current[sibling.uuid] = sibling.copy(position = expectedPosition)
            }
            expectedPosition++
        }
    }

    override suspend fun moveBlock(
        blockUuid: String,
        newParentUuid: String?,
        newPosition: Int
    ): Result<Unit> {
        val current = blocks.value.toMutableMap()
        val block = current[blockUuid] ?: return success(Unit)

        val newLevel = if (newParentUuid == null) 0 else (current[newParentUuid]?.level ?: -1) + 1
        val levelDelta = newLevel - block.level

        val updatedBlock = block.copy(
            parentUuid = newParentUuid,
            position = newPosition,
            level = newLevel
        )
        current[blockUuid] = updatedBlock
        
        if (levelDelta != 0) {
            adjustDescendantLevels(block.uuid, levelDelta, current)
        }
        
        blocks.value = current
        return success(Unit)
    }

    override suspend fun indentBlock(blockUuid: String): Result<Unit> {
        val current = blocks.value.toMutableMap()
        val block = current[blockUuid] ?: return success(Unit)

        val siblings = current.values
            .filter { it.pageUuid == block.pageUuid && it.parentUuid == block.parentUuid }
            .sortedBy { it.position }

        val blockIndex = siblings.indexOfFirst { it.uuid == blockUuid }
        if (blockIndex <= 0) return success(Unit) // No previous sibling

        val prevSibling = siblings[blockIndex - 1]
        val prevSiblingChildren = current.values.filter { it.parentUuid == prevSibling.uuid }
        val newPosition = if (prevSiblingChildren.isEmpty()) 0 else (prevSiblingChildren.maxOfOrNull { it.position } ?: -1) + 1

        val updatedBlock = block.copy(parentUuid = prevSibling.uuid, level = block.level + 1, position = newPosition)
        current[block.uuid] = updatedBlock
        
        adjustDescendantLevels(block.uuid, +1, current)

        blocks.value = current
        return success(Unit)
    }

    override suspend fun outdentBlock(blockUuid: String): Result<Unit> {
        val current = blocks.value.toMutableMap()
        val block = current[blockUuid] ?: return success(Unit)
        val parentUuid = block.parentUuid ?: return success(Unit) // Already at root

        val parent = current[parentUuid] ?: return success(Unit)
        val grandParentUuid = parent.parentUuid

        val grandparentChildren = current.values
            .filter { it.pageUuid == block.pageUuid && it.parentUuid == grandParentUuid }
            .sortedBy { it.position }

        val parentInGrandchildren = grandparentChildren.find { it.uuid == parentUuid }
        val newPosition = (parentInGrandchildren?.position ?: -1) + 1

        // Shift grandparent's children at or after newPosition to make room
        for (sibling in grandparentChildren) {
            if (sibling.position >= newPosition) {
                current[sibling.uuid] = sibling.copy(position = sibling.position + 1)
            }
        }
        
        val updatedBlock = block.copy(parentUuid = grandParentUuid, level = parent.level, position = newPosition)
        current[block.uuid] = updatedBlock
        
        val levelDelta = parent.level - block.level
        if (levelDelta != 0) {
            adjustDescendantLevels(block.uuid, levelDelta, current)
        }

        blocks.value = current
        return success(Unit)
    }

    override suspend fun moveBlockUp(blockUuid: String): Result<Unit> {
        val current = blocks.value.toMutableMap()
        val block = current[blockUuid] ?: return success(Unit)

        val siblings = current.values
            .filter { it.pageUuid == block.pageUuid && it.parentUuid == block.parentUuid }
            .sortedBy { it.position }

        val blockIndex = siblings.indexOfFirst { it.uuid == blockUuid }
        if (blockIndex <= 0) return success(Unit)

        val prevSibling = siblings[blockIndex - 1]
        current[block.uuid] = block.copy(position = prevSibling.position)
        current[prevSibling.uuid] = prevSibling.copy(position = block.position)
        
        blocks.value = current
        return success(Unit)
    }

    override suspend fun moveBlockDown(blockUuid: String): Result<Unit> {
        val current = blocks.value.toMutableMap()
        val block = current[blockUuid] ?: return success(Unit)

        val siblings = current.values
            .filter { it.pageUuid == block.pageUuid && it.parentUuid == block.parentUuid }
            .sortedBy { it.position }

        val blockIndex = siblings.indexOfFirst { it.uuid == blockUuid }
        if (blockIndex < 0 || blockIndex >= siblings.size - 1) return success(Unit)

        val nextSibling = siblings[blockIndex + 1]
        current[block.uuid] = block.copy(position = nextSibling.position)
        current[nextSibling.uuid] = nextSibling.copy(position = block.position)
        
        blocks.value = current
        return success(Unit)
    }

    override suspend fun mergeBlocks(blockUuid: String, nextBlockUuid: String, separator: String): Result<Unit> {
        val current = blocks.value.toMutableMap()
        val blockA = current[blockUuid] ?: return success(Unit)
        val blockB = current[nextBlockUuid] ?: return success(Unit)
        
        // 1. Update content of block A
        current[blockUuid] = blockA.copy(content = blockA.content + separator + blockB.content)
        
        // 2. Reparent children of block B to block A
        val childrenOfB = current.values.filter { it.parentUuid == blockB.uuid }.sortedBy { it.position }
        val lastChildOfA = current.values.filter { it.parentUuid == blockA.uuid }.maxByOrNull { it.position }
        var nextPosition = (lastChildOfA?.position ?: -1) + 1
        
        val targetLevelForChildren = blockA.level + 1
        
        for (child in childrenOfB) {
            val levelDelta = targetLevelForChildren - child.level
            val updatedChild = child.copy(
                parentUuid = blockA.uuid,
                position = nextPosition++,
                level = targetLevelForChildren
            )
            current[child.uuid] = updatedChild
            // Recursively adjust levels for this child's descendants
            if (levelDelta != 0) {
                adjustDescendantLevels(child.uuid, levelDelta, current)
            }
        }
        
        // 3. Remove block B
        current.remove(nextBlockUuid)
        
        // Repair sibling positions after deletion
        repairSiblingPositions(current, blockA.pageUuid, blockA.parentUuid)
        
        blocks.value = current
        return success(Unit)
    }

    override suspend fun splitBlock(blockUuid: String, cursorPosition: Int): Result<Block> {
        val current = blocks.value.toMutableMap()
        val block = current[blockUuid] ?: return Result.failure(Exception("Block not found"))
        
        val firstPart = block.content.substring(0, cursorPosition).trim()
        val secondPart = block.content.substring(cursorPosition).trim()
        
        val updatedBlock = block.copy(content = firstPart)
        val newPosition = block.position + 1
        
        // Shift following siblings
        val siblings = current.values.filter { it.pageUuid == block.pageUuid && it.parentUuid == block.parentUuid }
        for (sibling in siblings) {
            if (sibling.position >= newPosition) {
                current[sibling.uuid] = sibling.copy(position = sibling.position + 1)
            }
        }

        val newBlock = block.copy(
            uuid = dev.stapler.stelekit.util.UuidGenerator.generateV7(),
            content = secondPart,
            position = newPosition,
            level = block.level // New block must be at the same level
        )
        
        current[blockUuid] = updatedBlock
        current[newBlock.uuid] = newBlock
        blocks.value = current
        return Result.success(newBlock)
    }

    private fun adjustDescendantLevels(
        parentUuid: String,
        delta: Int,
        current: MutableMap<String, Block>,
        visited: MutableSet<String> = mutableSetOf()
    ) {
        if (parentUuid in visited) return
        visited.add(parentUuid)
        
        val children = current.values.filter { it.parentUuid == parentUuid }
        for (child in children) {
            val newLevel = child.level + delta
            if (newLevel >= 0) {
                current[child.uuid] = child.copy(level = newLevel)
                adjustDescendantLevels(child.uuid, delta, current, visited)
            }
        }
    }

    override fun getLinkedReferences(pageName: String): Flow<Result<List<Block>>> {
        // Matches [[name]] and [[name|alias]] forms
        val wikiLinkPattern = "\\[\\[${Regex.escape(pageName)}(\\|[^\\]]*)?\\]\\]".toRegex(RegexOption.IGNORE_CASE)
        return blocks.map { map ->
            val linkedBlocks = map.values.filter { block ->
                wikiLinkPattern.containsMatchIn(block.content)
            }
            success(linkedBlocks.sortedBy { it.pageUuid })
        }
    }

    override fun getLinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Result<List<Block>>> {
        // Matches [[name]] and [[name|alias]] forms
        val wikiLinkPattern = "\\[\\[${Regex.escape(pageName)}(\\|[^\\]]*)?\\]\\]".toRegex(RegexOption.IGNORE_CASE)
        return blocks.map { map ->
            val linkedBlocks = map.values.filter { block ->
                wikiLinkPattern.containsMatchIn(block.content)
            }
            success(linkedBlocks.sortedBy { it.pageUuid }.drop(offset).take(limit))
        }
    }

    override fun getUnlinkedReferences(pageName: String): Flow<Result<List<Block>>> {
        // Matches [[name]] and [[name|alias]] forms
        val wikiLinkPattern = "\\[\\[${Regex.escape(pageName)}(\\|[^\\]]*)?\\]\\]".toRegex(RegexOption.IGNORE_CASE)
        val plainTextPattern = "\\b${Regex.escape(pageName)}\\b".toRegex(RegexOption.IGNORE_CASE)
        return blocks.map { map ->
            val unlinkedBlocks = map.values.filter { block ->
                plainTextPattern.containsMatchIn(block.content) &&
                    !wikiLinkPattern.containsMatchIn(block.content)
            }
            success(unlinkedBlocks.sortedBy { it.pageUuid })
        }
    }

    override fun getUnlinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Result<List<Block>>> {
        // Matches [[name]] and [[name|alias]] forms
        val wikiLinkPattern = "\\[\\[${Regex.escape(pageName)}(\\|[^\\]]*)?\\]\\]".toRegex(RegexOption.IGNORE_CASE)
        val plainTextPattern = "\\b${Regex.escape(pageName)}\\b".toRegex(RegexOption.IGNORE_CASE)
        return blocks.map { map ->
            val unlinkedBlocks = map.values.filter { block ->
                plainTextPattern.containsMatchIn(block.content) &&
                    !wikiLinkPattern.containsMatchIn(block.content)
            }
            success(unlinkedBlocks.sortedBy { it.pageUuid }.drop(offset).take(limit))
        }
    }

    override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Result<List<Block>>> {
        return blocks.map { map ->
            val matchingBlocks = map.values.filter { block ->
                block.content.contains(query, ignoreCase = true)
            }
            success(matchingBlocks.sortedBy { it.pageUuid }.drop(offset).take(limit))
        }
    }

    override fun countLinkedReferences(pageName: String): Flow<Result<Long>> =
        flowOf(success(0L))

    override fun findDuplicateBlocks(limit: Int): Flow<Result<List<DuplicateGroup>>> {
        return blocks.map { map ->
            val groups = map.values
                .groupBy { ContentHasher.sha256ForContent(it.content) }
                .filter { it.value.size > 1 }
                .map { (hash, candidates) ->
                    candidates.groupBy { it.content }
                        .filter { it.value.size > 1 }
                        .map { (_, trueGroup) ->
                            DuplicateGroup(contentHash = hash, blocks = trueGroup, count = trueGroup.size)
                        }
                }
                .flatten()
                .take(limit)
            success(groups)
        }
    }
}

@OptIn(DirectRepositoryWrite::class)
class InMemoryPageRepository : PageRepository {
    private val pages = MutableStateFlow<Map<String, Page>>(emptyMap())

    override fun getAllPages(): Flow<Result<List<Page>>> {
        return pages.map { map ->
            success(map.values.toList())
        }
    }

    override fun getPages(limit: Int, offset: Int): Flow<Result<List<Page>>> {
        return pages.map { map ->
            val result = map.values.sortedBy { it.name }.drop(offset).take(limit)
            success(result)
        }
    }

    override fun searchPages(query: String, limit: Int, offset: Int): Flow<Result<List<Page>>> {
        return pages.map { map ->
            val result = map.values
                .filter { it.name.contains(query, ignoreCase = true) }
                .sortedBy { it.name }
                .drop(offset)
                .take(limit)
            success(result)
        }
    }

    override fun getRecentPages(limit: Int): Flow<Result<List<Page>>> {
        return pages.map { map ->
            success(map.values.sortedByDescending { it.updatedAt }.take(limit))
        }
    }

    override fun getJournalPages(limit: Int, offset: Int): Flow<Result<List<Page>>> {
        return pages.map { map ->
            val journals = map.values
                .filter { it.isJournal && it.journalDate != null }
                .sortedByDescending { it.journalDate }
                .drop(offset)
                .take(limit)
            success(journals)
        }
    }

    override fun getJournalPageByDate(date: kotlinx.datetime.LocalDate): Flow<Result<Page?>> {
        return pages.map { map ->
            success(map.values.find { it.journalDate == date })
        }
    }

    override fun getPageByUuid(uuid: String): Flow<Result<Page?>> {
        return pages.map { map ->
            success(map[uuid])
        }
    }

    override fun getPageByName(name: String): Flow<Result<Page?>> {
        return pages.map { map ->
            val page = map.values.find { page ->
                page.name.equals(name, ignoreCase = true) ||
                    page.properties["alias"]?.split(",")?.any { it.trim().equals(name, ignoreCase = true) } == true
            }
            success(page)
        }
    }

    override fun getPagesInNamespace(namespace: String): Flow<Result<List<Page>>> {
        return pages.map { map ->
            success(map.values.filter { it.namespace == namespace })
        }
    }

    override fun getUnloadedPages(): Flow<Result<List<Page>>> {
        return pages.map { map ->
            success(map.values.filter { !it.isContentLoaded })
        }
    }

    override suspend fun savePage(page: Page): Result<Unit> {
        val current = pages.value.toMutableMap()
        current[page.uuid] = page
        pages.value = current
        return success(Unit)
    }

    override suspend fun savePages(pageList: List<Page>): Result<Unit> {
        val current = pages.value.toMutableMap()
        pageList.forEach { current[it.uuid] = it }
        pages.value = current
        return success(Unit)
    }

    override suspend fun deletePage(pageUuid: String): Result<Unit> {
        val current = pages.value.toMutableMap()
        current.remove(pageUuid)
        pages.value = current
        return success(Unit)
    }

    override suspend fun renamePage(pageUuid: String, newName: String): Result<Unit> {
        val current = pages.value.toMutableMap()
        val page = current[pageUuid] ?: return Result.failure(Exception("Page not found"))
        current[pageUuid] = page.copy(name = newName, updatedAt = kotlin.time.Clock.System.now())
        pages.value = current
        return success(Unit)
    }

    override suspend fun toggleFavorite(pageUuid: String): Result<Unit> {
        val current = pages.value.toMutableMap()
        val page = current[pageUuid] ?: return Result.failure(Exception("Page not found"))
        current[pageUuid] = page.copy(isFavorite = !page.isFavorite)
        pages.value = current
        return success(Unit)
    }

    override fun countPages(): Flow<Result<Long>> {
        return pages.map { success(it.size.toLong()) }
    }

    override suspend fun clear() {
        pages.value = emptyMap()
    }
}

class InMemoryPropertyRepository : PropertyRepository {
    override fun getPropertiesForBlock(blockUuid: String): Flow<Result<List<Property>>> = flowOf(success(emptyList()))
    override fun getProperty(blockUuid: String, key: String): Flow<Result<Property?>> = flowOf(success(null))
    override suspend fun saveProperty(property: Property): Result<Unit> = success(Unit)
    override suspend fun deleteProperty(blockUuid: String, key: String): Result<Unit> = success(Unit)
    override fun getBlocksWithPropertyKey(key: String): Flow<Result<List<Block>>> = flowOf(success(emptyList()))
    override fun getBlocksWithPropertyValue(key: String, value: String): Flow<Result<List<Block>>> = flowOf(success(emptyList()))
}

class InMemoryReferenceRepository : ReferenceRepository {
    override fun getOutgoingReferences(blockUuid: String): Flow<Result<List<Block>>> = flowOf(success(emptyList()))
    override fun getIncomingReferences(blockUuid: String): Flow<Result<List<Block>>> = flowOf(success(emptyList()))
    override fun getAllReferences(blockUuid: String): Flow<Result<BlockReferences>> = flowOf(success(BlockReferences(emptyList(), emptyList())))
    override suspend fun addReference(fromBlockUuid: String, toBlockUuid: String): Result<Unit> = success(Unit)
    override suspend fun removeReference(fromBlockUuid: String, toBlockUuid: String): Result<Unit> = success(Unit)
    override fun getOrphanedBlocks(): Flow<Result<List<Block>>> = flowOf(success(emptyList()))
    override fun getMostConnectedBlocks(limit: Int): Flow<Result<List<BlockWithReferenceCount>>> = flowOf(success(emptyList()))
}

class InMemorySearchRepository(
    private val pageRepository: PageRepository? = null,
    private val blockRepository: BlockRepository? = null
) : SearchRepository {
    override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Result<List<Block>>> {
        if (blockRepository == null || query.isEmpty()) return flowOf(success(emptyList()))
        return blockRepository.searchBlocksByContent(query, limit, offset)
    }

    override fun searchPagesByTitle(query: String, limit: Int): Flow<Result<List<Page>>> {
        if (pageRepository == null || query.isEmpty()) return flowOf(success(emptyList()))
        return pageRepository.getAllPages().map { res ->
            res.map { pages ->
                pages.filter { it.name.contains(query, ignoreCase = true) || it.properties["alias"]?.contains(query, ignoreCase = true) == true }
                    .take(limit)
            }
        }
    }

    override fun findBlocksReferencing(blockUuid: String): Flow<Result<List<Block>>> = flowOf(success(emptyList()))
    
    override fun searchWithFilters(searchRequest: SearchRequest): Flow<Result<SearchResult>> {
        if (searchRequest.query == null) return flowOf(success(SearchResult(emptyList(), emptyList(), totalCount = 0, hasMore = false)))

        return searchPagesByTitle(searchRequest.query, searchRequest.limit).map { pagesRes ->
            val pages = pagesRes.getOrNull() ?: emptyList()
            success(SearchResult(emptyList(), pages, totalCount = pages.size, hasMore = false))
        }
    }
}
