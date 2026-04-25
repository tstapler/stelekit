package dev.stapler.stelekit.ui.fixtures

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.repository.BlockWithDepth
import dev.stapler.stelekit.repository.DuplicateGroup
import dev.stapler.stelekit.repository.BlockReferences
import dev.stapler.stelekit.repository.BlockWithReferenceCount
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlin.Result

class InMemorySettings : Settings {
    private val store = mutableMapOf<String, String>()

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        store[key]?.toBoolean() ?: defaultValue

    override fun putBoolean(key: String, value: Boolean) {
        store[key] = value.toString()
    }

    override fun getString(key: String, defaultValue: String): String =
        store.getOrDefault(key, defaultValue)

    override fun putString(key: String, value: String) {
        store[key] = value
    }
}

open class FakeFileSystem : FileSystem {
    override fun getDefaultGraphPath(): String = "/tmp/graph"
    override fun expandTilde(path: String) = path
    override fun readFile(path: String): String? = ""
    override fun writeFile(path: String, content: String): Boolean = true
    override fun listFiles(path: String): List<String> = emptyList()
    override fun listDirectories(path: String): List<String> = emptyList()
    override fun fileExists(path: String): Boolean = true
    override fun directoryExists(path: String): Boolean = true
    override fun createDirectory(path: String) = true
    override fun deleteFile(path: String) = true
    override fun pickDirectory(): String? = null
    override fun getLastModifiedTime(path: String): Long? = null
}

@OptIn(DirectRepositoryWrite::class)
open class FakePageRepository(initialPages: List<Page> = emptyList()) : PageRepository {
    protected val _pages = MutableStateFlow<Map<String, Page>>(initialPages.associateBy { it.uuid })

    override fun getPageByUuid(uuid: String): Flow<Result<Page?>> =
        _pages.map { Result.success(it[uuid]) }

    override fun getPageByName(name: String): Flow<Result<Page?>> =
        _pages.map { pages -> Result.success(pages.values.find { it.name == name }) }

    override fun getAllPages(): Flow<Result<List<Page>>> =
        _pages.map { Result.success(it.values.toList()) }

    override fun getJournalPages(limit: Int, offset: Int): Flow<Result<List<Page>>> =
        _pages.map { pages ->
            Result.success(pages.values.filter { it.isJournal }.sortedByDescending { it.journalDate }.drop(offset).take(limit))
        }

    override fun getJournalPageByDate(date: LocalDate): Flow<Result<Page?>> =
        _pages.map { pages -> Result.success(pages.values.find { it.journalDate == date }) }

    override fun getPagesInNamespace(namespace: String): Flow<Result<List<Page>>> =
        _pages.map { pages -> Result.success(pages.values.filter { it.namespace == namespace }) }

    override fun getPages(limit: Int, offset: Int): Flow<Result<List<Page>>> =
        _pages.map { pages -> Result.success(pages.values.sortedBy { it.name }.drop(offset).take(limit)) }

    override fun searchPages(query: String, limit: Int, offset: Int): Flow<Result<List<Page>>> =
        _pages.map { pages ->
            val result = pages.values
                .filter { it.name.contains(query, ignoreCase = true) }
                .sortedBy { it.name }
                .drop(offset)
                .take(limit)
            Result.success(result)
        }

    override fun getRecentPages(limit: Int): Flow<Result<List<Page>>> =
        _pages.map { pages -> Result.success(pages.values.sortedByDescending { it.updatedAt }.take(limit)) }

    override fun getUnloadedPages(): Flow<Result<List<Page>>> =
        _pages.map { pages -> Result.success(pages.values.filter { !it.isContentLoaded }) }

    override suspend fun savePage(page: Page): Result<Unit> {
        _pages.value = _pages.value + (page.uuid to page)
        return Result.success(Unit)
    }

    override suspend fun savePages(pages: List<Page>): Result<Unit> {
        _pages.value = _pages.value + pages.associateBy { it.uuid }
        return Result.success(Unit)
    }

    override suspend fun toggleFavorite(pageUuid: String): Result<Unit> {
        val page = _pages.value[pageUuid] ?: return Result.success(Unit)
        _pages.value = _pages.value + (pageUuid to page.copy(isFavorite = !page.isFavorite))
        return Result.success(Unit)
    }

    override suspend fun renamePage(pageUuid: String, newName: String): Result<Unit> {
        val page = _pages.value[pageUuid] ?: return Result.success(Unit)
        _pages.value = _pages.value + (pageUuid to page.copy(name = newName))
        return Result.success(Unit)
    }

    override suspend fun deletePage(pageUuid: String): Result<Unit> {
        _pages.value = _pages.value - pageUuid
        return Result.success(Unit)
    }

    override fun countPages(): Flow<Result<Long>> =
        _pages.map { Result.success(it.size.toLong()) }

    override suspend fun clear() {
        _pages.value = emptyMap()
    }
}

@OptIn(DirectRepositoryWrite::class)
open class FakeBlockRepository(blocksByPage: Map<String, List<Block>> = emptyMap()) : BlockRepository {
    protected val _blocks = MutableStateFlow<Map<String, Block>>(blocksByPage.values.flatten().associateBy { it.uuid })

    override fun getBlockByUuid(uuid: String): Flow<Result<Block?>> =
        _blocks.map { Result.success(it[uuid]) }

    override fun getBlocksForPage(pageUuid: String): Flow<Result<List<Block>>> =
        _blocks.map { blocks ->
            Result.success(blocks.values.filter { it.pageUuid == pageUuid }.sortedBy { it.position })
        }

    override fun getBlockChildren(blockUuid: String): Flow<Result<List<Block>>> =
        _blocks.map { blocks ->
            Result.success(blocks.values.filter { it.parentUuid == blockUuid }.sortedBy { it.position })
        }

    override fun getBlockSiblings(blockUuid: String): Flow<Result<List<Block>>> =
        _blocks.map { blocks ->
            val block = blocks[blockUuid] ?: return@map Result.success(emptyList<Block>())
            Result.success(blocks.values.filter { it.parentUuid == block.parentUuid && it.uuid != blockUuid }.sortedBy { it.position })
        }

    override suspend fun saveBlock(block: Block): Result<Unit> {
        _blocks.value = _blocks.value + (block.uuid to block)
        return Result.success(Unit)
    }

    override suspend fun saveBlocks(blocks: List<Block>): Result<Unit> {
        _blocks.value = _blocks.value + blocks.associateBy { it.uuid }
        return Result.success(Unit)
    }

    override suspend fun deleteBlock(blockUuid: String, deleteChildren: Boolean): Result<Unit> {
        val current = _blocks.value.toMutableMap()
        if (deleteChildren) {
            val toDelete = mutableSetOf(blockUuid)
            fun collectChildren(uuid: String) {
                current.values.filter { it.parentUuid == uuid }.forEach {
                    toDelete.add(it.uuid)
                    collectChildren(it.uuid)
                }
            }
            collectChildren(blockUuid)
            _blocks.value = current - toDelete
        } else {
            // Orphan children
            val children = current.values.filter { it.parentUuid == blockUuid }
            for (child in children) {
                val levelDelta = 0 - child.level
                val updatedChild = child.copy(parentUuid = null, level = 0)
                current[child.uuid] = updatedChild
                val updates = mutableMapOf<String, Block>()
                adjustDescendantLevels(child.uuid, levelDelta, current, updates)
                updates.forEach { (u, b) -> current[u] = b }
            }
            _blocks.value = current - blockUuid
        }
        return Result.success(Unit)
    }

    override suspend fun deleteBulk(blockUuids: List<String>, deleteChildren: Boolean): Result<Unit> {
        blockUuids.forEach { uuid -> deleteBlock(uuid, deleteChildren) }
        return Result.success(Unit)
    }

    override suspend fun deleteBlocksForPage(pageUuid: String): Result<Unit> {
        _blocks.value = _blocks.value.filterValues { it.pageUuid != pageUuid }
        return Result.success(Unit)
    }

    override suspend fun deleteBlocksForPages(pageUuids: List<String>): Result<Unit> {
        val uuidSet = pageUuids.toSet()
        _blocks.value = _blocks.value.filterValues { it.pageUuid !in uuidSet }
        return Result.success(Unit)
    }

    override suspend fun moveBlock(blockUuid: String, newParentUuid: String?, newPosition: Int): Result<Unit> {
        val current = _blocks.value.toMutableMap()
        val block = current[blockUuid] ?: return Result.success(Unit)
        
        val newLevel = if (newParentUuid == null) 0 else (current[newParentUuid]?.level ?: -1) + 1
        val levelDelta = newLevel - block.level
        
        current[blockUuid] = block.copy(parentUuid = newParentUuid, position = newPosition, level = newLevel)
        
        if (levelDelta != 0) {
            val updates = mutableMapOf<String, Block>()
            adjustDescendantLevels(blockUuid, levelDelta, current, updates)
            updates.forEach { (u, b) -> current[u] = b }
        }
        
        _blocks.value = current
        return Result.success(Unit)
    }

    override suspend fun indentBlock(blockUuid: String): Result<Unit> {
        val current = _blocks.value.toMutableMap()
        val block = current[blockUuid] ?: return Result.success(Unit)

        val siblings = current.values
            .filter { it.pageUuid == block.pageUuid && it.parentUuid == block.parentUuid }
            .sortedBy { it.position }

        val blockIndex = siblings.indexOfFirst { it.uuid == blockUuid }
        if (blockIndex <= 0) return Result.success(Unit)

        val prevSibling = siblings[blockIndex - 1]
        val prevSiblingChildren = current.values.filter { it.parentUuid == prevSibling.uuid }
        val newPosition = if (prevSiblingChildren.isEmpty()) 0 else prevSiblingChildren.maxOf { it.position } + 1

        current[block.uuid] = block.copy(parentUuid = prevSibling.uuid, level = block.level + 1, position = newPosition)
        
        val updates = mutableMapOf<String, Block>()
        adjustDescendantLevels(block.uuid, +1, current, updates)
        updates.forEach { (u, b) -> current[u] = b }

        _blocks.value = current
        return Result.success(Unit)
    }

    override suspend fun outdentBlock(blockUuid: String): Result<Unit> {
        val current = _blocks.value.toMutableMap()
        val block = current[blockUuid] ?: return Result.success(Unit)
        val parentUuid = block.parentUuid ?: return Result.success(Unit)

        val parent = current[parentUuid] ?: return Result.success(Unit)
        val grandParentUuid = parent.parentUuid

        val grandparentChildren = current.values
            .filter { it.pageUuid == block.pageUuid && it.parentUuid == grandParentUuid }
            .sortedBy { it.position }

        val parentInGrandchildren = grandparentChildren.find { it.uuid == parentUuid }
        val newPosition = (parentInGrandchildren?.position ?: -1) + 1

        for (sibling in grandparentChildren) {
            if (sibling.position >= newPosition) {
                current[sibling.uuid] = sibling.copy(position = sibling.position + 1)
            }
        }
        
        current[block.uuid] = block.copy(parentUuid = grandParentUuid, level = parent.level, position = newPosition)
        
        val levelDelta = parent.level - block.level
        if (levelDelta != 0) {
            val updates = mutableMapOf<String, Block>()
            adjustDescendantLevels(block.uuid, levelDelta, current, updates)
            updates.forEach { (u, b) -> current[u] = b }
        }

        _blocks.value = current
        return Result.success(Unit)
    }

    override suspend fun moveBlockUp(blockUuid: String): Result<Unit> {
        val current = _blocks.value.toMutableMap()
        val block = current[blockUuid] ?: return Result.success(Unit)
        val siblings = current.values.filter { it.parentUuid == block.parentUuid && it.pageUuid == block.pageUuid }.sortedBy { it.position }
        val idx = siblings.indexOfFirst { it.uuid == blockUuid }
        if (idx <= 0) return Result.success(Unit)
        
        val prev = siblings[idx - 1]
        current[block.uuid] = block.copy(position = prev.position)
        current[prev.uuid] = prev.copy(position = block.position)
        
        _blocks.value = current
        return Result.success(Unit)
    }

    override suspend fun moveBlockDown(blockUuid: String): Result<Unit> {
        val current = _blocks.value.toMutableMap()
        val block = current[blockUuid] ?: return Result.success(Unit)
        val siblings = current.values.filter { it.parentUuid == block.parentUuid && it.pageUuid == block.pageUuid }.sortedBy { it.position }
        val idx = siblings.indexOfFirst { it.uuid == blockUuid }
        if (idx < 0 || idx >= siblings.size - 1) return Result.success(Unit)
        
        val next = siblings[idx + 1]
        current[block.uuid] = block.copy(position = next.position)
        current[next.uuid] = next.copy(position = block.position)
        
        _blocks.value = current
        return Result.success(Unit)
    }

    override suspend fun mergeBlocks(blockUuid: String, nextBlockUuid: String, separator: String): Result<Unit> {
        val current = _blocks.value.toMutableMap()
        val blockA = current[blockUuid] ?: return Result.success(Unit)
        val blockB = current[nextBlockUuid] ?: return Result.success(Unit)
        
        current[blockUuid] = blockA.copy(content = blockA.content + separator + blockB.content)
        
        val childrenOfB = current.values.filter { it.parentUuid == blockB.uuid }.sortedBy { it.position }
        val lastChildOfA = current.values.filter { it.parentUuid == blockA.uuid }.maxByOrNull { it.position }
        var nextPos = (lastChildOfA?.position ?: -1) + 1
        val levelDelta = blockA.level + 1 - blockB.level
        
        for (child in childrenOfB) {
            val updated = child.copy(parentUuid = blockA.uuid, position = nextPos++, level = child.level + levelDelta)
            current[child.uuid] = updated
            val updates = mutableMapOf<String, Block>()
            adjustDescendantLevels(child.uuid, levelDelta, current, updates)
            updates.forEach { (u, b) -> current[u] = b }
        }
        
        _blocks.value = current - nextBlockUuid
        return Result.success(Unit)
    }

    override suspend fun splitBlock(blockUuid: String, cursorPosition: Int): Result<Block> {
        val current = _blocks.value.toMutableMap()
        val block = current[blockUuid] ?: return Result.failure(Exception("Not found"))
        val firstPart = block.content.substring(0, cursorPosition).trim()
        val secondPart = block.content.substring(cursorPosition).trim()
        
        val newPos = block.position + 1
        current.values.filter { it.parentUuid == block.parentUuid && it.pageUuid == block.pageUuid && it.position >= newPos }.forEach {
            current[it.uuid] = it.copy(position = it.position + 1)
        }
        
        current[blockUuid] = block.copy(content = firstPart)
        val newBlock = block.copy(uuid = dev.stapler.stelekit.util.UuidGenerator.generateV7(), content = secondPart, position = newPos)
        current[newBlock.uuid] = newBlock
        _blocks.value = current
        return Result.success(newBlock)
    }

    override fun getBlockHierarchy(rootUuid: String): Flow<Result<List<BlockWithDepth>>> = flowOf(Result.success(emptyList()))
    override fun getBlockAncestors(blockUuid: String): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
    override fun getBlockParent(blockUuid: String): Flow<Result<Block?>> = flowOf(Result.success(null))
    override fun getLinkedReferences(pageName: String): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
    override fun getLinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
    override fun countLinkedReferences(pageName: String): Flow<Result<Long>> = flowOf(Result.success(0L))
    override fun getUnlinkedReferences(pageName: String): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
    override fun getUnlinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
    override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
    override fun findDuplicateBlocks(limit: Int): Flow<Result<List<DuplicateGroup>>> = flowOf(Result.success(emptyList()))
    override suspend fun clear() { _blocks.value = emptyMap() }

    private fun adjustDescendantLevels(
        parentUuid: String,
        delta: Int,
        snapshot: Map<String, Block>,
        updates: MutableMap<String, Block>,
        visited: MutableSet<String> = mutableSetOf()
    ) {
        if (parentUuid in visited) return
        visited.add(parentUuid)
        
        val currentBlocks = snapshot + updates
        val children = currentBlocks.values.filter { it.parentUuid == parentUuid }
        
        for (child in children) {
            val newLevel = child.level + delta
            if (newLevel >= 0) {
                updates[child.uuid] = child.copy(level = newLevel)
                adjustDescendantLevels(child.uuid, delta, snapshot, updates, visited)
            }
        }
    }
}

/**
 * Pre-populated PageRepository for testing.
 */
class PopulatedFakePageRepository : FakePageRepository() {
    init {
        val now = Clock.System.now()
        _pages.value = mapOf(
            "page-1" to Page(
                uuid = "page-1",
                name = "Test Page",
                createdAt = now,
                updatedAt = now,
                isJournal = false
            ),
            "journal-1" to Page(
                uuid = "journal-1",
                name = "2026-03-28",
                createdAt = now,
                updatedAt = now,
                isJournal = true,
                journalDate = LocalDate(2026, 3, 28)
            )
        )
    }
}

/**
 * Pre-populated BlockRepository for testing.
 */
class PopulatedFakeBlockRepository : FakeBlockRepository() {
    init {
        val now = Clock.System.now()
        _blocks.value = mapOf(
            "block-1" to Block(
                uuid = "block-1",
                pageUuid = "page-1",
                content = "Block 1",
                position = 0,
                createdAt = now,
                updatedAt = now
            ),
            "block-2" to Block(
                uuid = "block-2",
                pageUuid = "page-1",
                content = "Block 2",
                position = 1,
                createdAt = now,
                updatedAt = now
            )
        )
    }
}

/**
 * Factory method to create a populated page repository.
 */
suspend fun createPopulatedPageRepository(): FakePageRepository {
    return PopulatedFakePageRepository()
}

/**
 * Factory method to create a populated block repository.
 */
suspend fun createPopulatedBlockRepository(): FakeBlockRepository {
    return PopulatedFakeBlockRepository()
}
