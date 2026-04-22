@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.screens

import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.BlockWithDepth
import dev.stapler.stelekit.repository.DuplicateGroup
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for JournalsViewModel editor operations:
 * - mergeBlock: Merge current block content into previous block
 * - handleBackspace: Delete empty block and navigate
 * - splitBlock: Split block at cursor position
 * - addNewBlock: Create new block after current
 */
class JournalsViewModelEditorTest {

    // ============================================================
    // TEST INFRASTRUCTURE
    // ============================================================

    class FakeFileSystem : FileSystem {
        override fun getDefaultGraphPath(): String = "/tmp/graph"
        override fun expandTilde(path: String): String = path
        override fun readFile(path: String): String? = ""
        override fun writeFile(path: String, content: String): Boolean = true
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String): Boolean = true
        override fun directoryExists(path: String): Boolean = true
        override fun createDirectory(path: String): Boolean = true
        override fun deleteFile(path: String): Boolean = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    /**
     * A functional fake BlockRepository that actually stores and manipulates blocks.
     * This allows us to test ViewModel logic that depends on repository state.
     */
    class InMemoryBlockRepository : BlockRepository {
        private val blocks = MutableStateFlow<Map<String, Block>>(emptyMap())

        fun addBlock(block: Block) {
            blocks.value = blocks.value + (block.uuid to block)
        }

        fun getBlock(uuid: String): Block? = blocks.value[uuid]

        fun getAllBlocks(): List<Block> = blocks.value.values.toList()

        override fun getBlocksForPage(pageUuid: String): Flow<Result<List<Block>>> {
            return blocks.map { map ->
                val pageBlocks = map.values.filter { it.pageUuid == pageUuid }.sortedBy { it.position }
                Result.success(pageBlocks)
            }
        }

        override fun getBlockByUuid(uuid: String): Flow<Result<Block?>> {
            return blocks.map { map -> Result.success(map[uuid]) }
        }

        override fun getBlockChildren(blockUuid: String): Flow<Result<List<Block>>> {
            return blocks.map { map ->
                val block = map[blockUuid]
                if (block == null) {
                    Result.success(emptyList())
                } else {
                    val children = map.values.filter { it.parentUuid == block.uuid }.sortedBy { it.position }
                    Result.success(children)
                }
            }
        }

        override fun getBlockHierarchy(rootUuid: String): Flow<Result<List<BlockWithDepth>>> =
            flowOf(Result.success(emptyList()))

        override fun getBlockAncestors(blockUuid: String): Flow<Result<List<Block>>> =
            flowOf(Result.success(emptyList()))

        override fun getBlockParent(blockUuid: String): Flow<Result<Block?>> {
            return blocks.map { map ->
                val block = map[blockUuid]
                if (block?.parentUuid == null) {
                    Result.success(null)
                } else {
                    val parent = map.values.find { it.uuid == block.parentUuid }
                    Result.success(parent)
                }
            }
        }

        override fun getBlockSiblings(blockUuid: String): Flow<Result<List<Block>>> {
            return blocks.map { map ->
                val block = map[blockUuid] ?: return@map Result.success(emptyList<Block>())
                val siblings = map.values
                    .filter { it.parentUuid == block.parentUuid && it.pageUuid == block.pageUuid && it.uuid != blockUuid }
                    .sortedBy { it.position }
                Result.success(siblings)
            }
        }

        override fun getLinkedReferences(pageName: String): Flow<Result<List<Block>>> =
            flowOf(Result.success(emptyList()))

        override fun getLinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Result<List<Block>>> =
            flowOf(Result.success(emptyList()))

        override fun countLinkedReferences(pageName: String): Flow<Result<Long>> = flowOf(Result.success(0L))

        override fun getUnlinkedReferences(pageName: String): Flow<Result<List<Block>>> =
            flowOf(Result.success(emptyList()))

        override fun getUnlinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Result<List<Block>>> =
            flowOf(Result.success(emptyList()))

        override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Result<List<Block>>> =
            flowOf(Result.success(emptyList()))

        override suspend fun saveBlock(block: Block): Result<Unit> {
            blocks.value = blocks.value + (block.uuid to block)
            return Result.success(Unit)
        }

        override suspend fun saveBlocks(blockList: List<Block>): Result<Unit> {
            val newMap = blocks.value.toMutableMap()
            blockList.forEach { newMap[it.uuid] = it }
            blocks.value = newMap
            return Result.success(Unit)
        }

        override suspend fun deleteBlock(blockUuid: String, deleteChildren: Boolean): Result<Unit> {
            val block = blocks.value[blockUuid] ?: return Result.success(Unit)
            val newMap = blocks.value.toMutableMap()

            if (deleteChildren) {
                // Recursively delete children
                fun deleteRecursive(uuid: String) {
                    val b = newMap[uuid] ?: return
                    newMap.values.filter { it.parentUuid == b.uuid }.forEach { deleteRecursive(it.uuid) }
                    newMap.remove(uuid)
                }
                deleteRecursive(blockUuid)
            } else {
                newMap.remove(blockUuid)
            }

            blocks.value = newMap
            return Result.success(Unit)
        }

        override suspend fun deleteBulk(blockUuids: List<String>, deleteChildren: Boolean): Result<Unit> {
            blockUuids.forEach { uuid -> deleteBlock(uuid, deleteChildren) }
            return Result.success(Unit)
        }

        override suspend fun deleteBlocksForPage(pageUuid: String): Result<Unit> {
            blocks.value = blocks.value.filterValues { it.pageUuid != pageUuid }
            return Result.success(Unit)
        }

        override suspend fun deleteBlocksForPages(pageUuids: List<String>): Result<Unit> {
            val uuidSet = pageUuids.toSet()
            blocks.value = blocks.value.filterValues { it.pageUuid !in uuidSet }
            return Result.success(Unit)
        }

        override suspend fun moveBlock(blockUuid: String, newParentUuid: String?, newPosition: Int): Result<Unit> =
            Result.success(Unit)

        override suspend fun indentBlock(blockUuid: String): Result<Unit> = Result.success(Unit)
        override suspend fun outdentBlock(blockUuid: String): Result<Unit> = Result.success(Unit)
        override suspend fun moveBlockUp(blockUuid: String): Result<Unit> = Result.success(Unit)
        override suspend fun moveBlockDown(blockUuid: String): Result<Unit> = Result.success(Unit)

        override suspend fun mergeBlocks(blockUuid: String, nextBlockUuid: String, separator: String): Result<Unit> {
            val currentMap = blocks.value.toMutableMap()
            val blockA = currentMap[blockUuid] ?: return Result.success(Unit)
            val blockB = currentMap[nextBlockUuid] ?: return Result.success(Unit)
            
            currentMap[blockUuid] = blockA.copy(content = blockA.content + separator + blockB.content)
            currentMap.remove(nextBlockUuid)
            blocks.value = currentMap
            return Result.success(Unit)
        }

        override suspend fun splitBlock(blockUuid: String, cursorPosition: Int): Result<Block> {
            val currentMap = blocks.value.toMutableMap()
            val block = currentMap[blockUuid] ?: return Result.failure(Exception("Block not found"))
            
            val fullContent = block.content
            val safeSplitIndex = cursorPosition.coerceIn(0, fullContent.length)
            
            val firstPart = fullContent.substring(0, safeSplitIndex).trim()
            val secondPart = fullContent.substring(safeSplitIndex).trim()
            
            val updatedBlock = block.copy(content = firstPart)
            val newPosition = block.position + 1
            
            // Shift siblings
            val siblingsToShift = currentMap.values.filter { 
                it.pageUuid == block.pageUuid && it.parentUuid == block.parentUuid && it.position >= newPosition 
            }
            siblingsToShift.forEach { sibling ->
                currentMap[sibling.uuid] = sibling.copy(position = sibling.position + 1)
            }
            
            val newBlock = block.copy(
                uuid = java.util.UUID.randomUUID().toString(),
                content = secondPart,
                position = newPosition
            )
            
            currentMap[blockUuid] = updatedBlock
            currentMap[newBlock.uuid] = newBlock
            blocks.value = currentMap
            return Result.success(newBlock)
        }

        override fun findDuplicateBlocks(limit: Int): Flow<Result<List<DuplicateGroup>>> =
            flowOf(Result.success(emptyList()))

        override suspend fun clear() { blocks.value = emptyMap() }
    }

    class FakePageRepository : PageRepository {
        private val pages = mutableListOf<Page>()

        fun addPage(page: Page) { pages.add(page) }

        override fun getAllPages(): Flow<Result<List<Page>>> = flowOf(Result.success(pages.toList()))

        override fun getJournalPages(limit: Int, offset: Int): Flow<Result<List<Page>>> {
            val journals = pages.filter { it.isJournal }
                .sortedByDescending { it.journalDate }
                .drop(offset)
                .take(limit)
            return flowOf(Result.success(journals))
        }

        override fun getPagesInNamespace(namespace: String): Flow<Result<List<Page>>> = 
            flowOf(Result.success(pages.filter { it.namespace == namespace }))

        override fun getPages(limit: Int, offset: Int): Flow<Result<List<Page>>> {
            val result = pages.sortedBy { it.name }.drop(offset).take(limit)
            return flowOf(Result.success(result))
        }

        override fun searchPages(query: String, limit: Int, offset: Int): Flow<Result<List<Page>>> {
            val result = pages
                .filter { it.name.contains(query, ignoreCase = true) }
                .sortedBy { it.name }
                .drop(offset)
                .take(limit)
            return flowOf(Result.success(result))
        }

        override fun getPageByUuid(uuid: String): Flow<Result<Page?>> =
            flowOf(Result.success(pages.find { it.uuid == uuid }))

        override fun getPageByName(name: String): Flow<Result<Page?>> =
            flowOf(Result.success(pages.find { it.name == name }))

        override fun getRecentPages(limit: Int): Flow<Result<List<Page>>> =
            flowOf(Result.success(pages.sortedByDescending { it.updatedAt }.take(limit)))

        override fun getJournalPageByDate(date: LocalDate): Flow<Result<Page?>> =
            flowOf(Result.success(pages.find { it.journalDate == date }))

        override fun getUnloadedPages(): Flow<Result<List<Page>>> =
            flowOf(Result.success(pages.filter { !it.isContentLoaded }))

        override suspend fun savePage(page: Page): Result<Unit> {
            pages.removeAll { it.uuid == page.uuid }
            pages.add(page)
            return Result.success(Unit)
        }

        override suspend fun savePages(pageList: List<Page>): Result<Unit> {
            pageList.forEach { page ->
                pages.removeAll { it.uuid == page.uuid }
                pages.add(page)
            }
            return Result.success(Unit)
        }

        override suspend fun deletePage(pageUuid: String): Result<Unit> {
            pages.removeAll { it.uuid == pageUuid }
            return Result.success(Unit)
        }

        override suspend fun renamePage(pageUuid: String, newName: String): Result<Unit> = Result.success(Unit)
        override suspend fun toggleFavorite(pageUuid: String): Result<Unit> = Result.success(Unit)
        override fun countPages(): Flow<Result<Long>> = flowOf(Result.success(pages.size.toLong()))
        override suspend fun clear() { pages.clear() }
    }

    private val now = Clock.System.now()

    private fun createPage(uuid: String, name: String = "TestPage"): Page {
        return Page(
            uuid = uuid,
            name = name,
            createdAt = now,
            updatedAt = now,
            isJournal = true,
            journalDate = LocalDate(2026, 1, 29)
        )
    }

    private fun createBlock(
        uuid: String,
        pageUuid: String = "page-1",
        parentUuid: String? = null,
        position: Int,
        content: String = "Block $uuid",
        level: Int = 0
    ): Block {
        return Block(
            uuid = uuid,
            pageUuid = pageUuid,
            parentUuid = parentUuid,
            content = content,
            position = position,
            level = level,
            leftUuid = null,
            createdAt = now,
            updatedAt = now
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.createViewModel(
        pageRepo: FakePageRepository,
        blockRepo: InMemoryBlockRepository
    ): JournalsViewModel {
        val fileSystem = FakeFileSystem()
        val journalService = JournalService(pageRepo, blockRepo)
        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo)
        // Use UnconfinedTestDispatcher so coroutines run eagerly (no ordering issues),
        // combined with backgroundScope's Job so StateFlow collectors are auto-cancelled
        // when the test ends (prevents runTest from hanging).
        val viewModelScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val blockStateManager = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = viewModelScope
        )
        return JournalsViewModel(journalService, blockStateManager, viewModelScope)
    }

    // ============================================================
    // MERGE BLOCK TESTS
    // ============================================================

    @Test
    fun `mergeBlock should combine content with previous block`() = runTest {
        val pageRepo = FakePageRepository()
        val blockRepo = InMemoryBlockRepository()

        val page = createPage("page-1")
        pageRepo.addPage(page)

        val block1 = createBlock("block-1", pageUuid = "page-1", position = 0, content = "Hello ")
        val block2 = createBlock("block-2", pageUuid = "page-1", position = 1, content = "World")
        blockRepo.addBlock(block1)
        blockRepo.addBlock(block2)

        val viewModel = createViewModel(pageRepo, blockRepo)

        // Load the page blocks into the ViewModel state
        viewModel.loadPageContent("page-1")

        // Merge block2 into block1
        viewModel.mergeBlock(block2.uuid)

        // Wait for coroutine to complete
        testScheduler.advanceUntilIdle()

        // Verify block1 has combined content
        val mergedBlock = blockRepo.getBlock(block1.uuid)
        assertNotNull(mergedBlock)
        assertEquals("Hello World", mergedBlock.content)

        // Verify block2 was deleted
        val deletedBlock = blockRepo.getBlock(block2.uuid)
        assertNull(deletedBlock)
    }

    @Test
    fun `mergeBlock at first position should do nothing`() = runTest {
        val pageRepo = FakePageRepository()
        val blockRepo = InMemoryBlockRepository()

        val page = createPage("page-1")
        pageRepo.addPage(page)

        val block1 = createBlock("block-1", pageUuid = "page-1", position = 0, content = "First block")
        blockRepo.addBlock(block1)

        val viewModel = createViewModel(pageRepo, blockRepo)
        viewModel.loadPageContent("page-1")

        // Try to merge the first block (should do nothing - no previous block)
        viewModel.mergeBlock(block1.uuid)

        testScheduler.advanceUntilIdle()

        // Block should still exist unchanged
        val block = blockRepo.getBlock(block1.uuid)
        assertNotNull(block)
        assertEquals("First block", block.content)
    }

    @Test
    fun `mergeBlock should update positions of subsequent siblings`() = runTest {
        val pageRepo = FakePageRepository()
        val blockRepo = InMemoryBlockRepository()

        val page = createPage("page-1")
        pageRepo.addPage(page)

        val block1 = createBlock("block-1", pageUuid = "page-1", position = 0, content = "A")
        val block2 = createBlock("block-2", pageUuid = "page-1", position = 1, content = "B")
        val block3 = createBlock("block-3", pageUuid = "page-1", position = 2, content = "C")
        blockRepo.addBlock(block1)
        blockRepo.addBlock(block2)
        blockRepo.addBlock(block3)

        val viewModel = createViewModel(pageRepo, blockRepo)
        viewModel.loadPageContent("page-1")

        // Merge block2 into block1
        viewModel.mergeBlock(block2.uuid)

        testScheduler.advanceUntilIdle()

        // Verify remaining blocks have correct positions
        val remainingBlocks = blockRepo.getBlocksForPage("page-1").first().getOrNull()
            ?.sortedBy { it.position }
            ?: emptyList()

        assertEquals(2, remainingBlocks.size)
        assertEquals(block1.uuid, remainingBlocks[0].uuid)
        assertEquals(block3.uuid, remainingBlocks[1].uuid)
    }

    // ============================================================
    // HANDLE BACKSPACE TESTS
    // ============================================================

    @Test
    fun `handleBackspace should delete empty block and focus previous`() = runTest {
        val pageRepo = FakePageRepository()
        val blockRepo = InMemoryBlockRepository()

        val page = createPage("page-1")
        pageRepo.addPage(page)

        val block1 = createBlock("block-1", pageUuid = "page-1", position = 0, content = "Previous block")
        val block2 = createBlock("block-2", pageUuid = "page-1", position = 1, content = "")  // Empty block
        blockRepo.addBlock(block1)
        blockRepo.addBlock(block2)

        val viewModel = createViewModel(pageRepo, blockRepo)
        viewModel.loadPageContent("page-1")

        // Handle backspace on empty block2
        viewModel.handleBackspace(block2.uuid)

        testScheduler.advanceUntilIdle()

        // Verify block2 was deleted
        val deletedBlock = blockRepo.getBlock(block2.uuid)
        assertNull(deletedBlock)

        // Verify block1 still exists
        val remainingBlock = blockRepo.getBlock(block1.uuid)
        assertNotNull(remainingBlock)
    }

    @Test
    fun `handleBackspace on first root block with siblings should delete and focus next`() = runTest {
        val pageRepo = FakePageRepository()
        val blockRepo = InMemoryBlockRepository()

        val page = createPage("page-1")
        pageRepo.addPage(page)

        val block1 = createBlock("block-1", pageUuid = "page-1", position = 0, content = "")  // Empty first block
        val block2 = createBlock("block-2", pageUuid = "page-1", position = 1, content = "Second block")
        blockRepo.addBlock(block1)
        blockRepo.addBlock(block2)

        val viewModel = createViewModel(pageRepo, blockRepo)
        viewModel.loadPageContent("page-1")

        // Handle backspace on first empty root block
        viewModel.handleBackspace(block1.uuid)

        testScheduler.advanceUntilIdle()

        // Verify block1 was deleted
        val deletedBlock = blockRepo.getBlock(block1.uuid)
        assertNull(deletedBlock)

        // Verify block2 still exists
        val remainingBlock = blockRepo.getBlock(block2.uuid)
        assertNotNull(remainingBlock)
    }

    @Test
    fun `handleBackspace on only root block should not delete`() = runTest {
        val pageRepo = FakePageRepository()
        val blockRepo = InMemoryBlockRepository()

        val page = createPage("page-1")
        pageRepo.addPage(page)

        val block1 = createBlock("block-1", pageUuid = "page-1", position = 0, content = "")  // Only block
        blockRepo.addBlock(block1)

        val viewModel = createViewModel(pageRepo, blockRepo)
        viewModel.loadPageContent("page-1")

        // Handle backspace on the only root block
        viewModel.handleBackspace(block1.uuid)

        testScheduler.advanceUntilIdle()

        // Block should still exist (can't delete the only block)
        val block = blockRepo.getBlock(block1.uuid)
        assertNotNull(block)
    }

    @Test
    fun `handleBackspace on child block should navigate to parent`() = runTest {
        val pageRepo = FakePageRepository()
        val blockRepo = InMemoryBlockRepository()

        val page = createPage("page-1")
        pageRepo.addPage(page)

        val parent = createBlock("parent", pageUuid = "page-1", position = 0, content = "Parent", level = 0)
        val child = createBlock("child", pageUuid = "page-1", parentUuid = "parent", position = 0, content = "", level = 1)  // Empty child
        blockRepo.addBlock(parent)
        blockRepo.addBlock(child)

        val viewModel = createViewModel(pageRepo, blockRepo)
        viewModel.loadPageContent("page-1")

        // Handle backspace on empty child
        viewModel.handleBackspace(child.uuid)

        testScheduler.advanceUntilIdle()

        // Child should be deleted
        val deletedChild = blockRepo.getBlock(child.uuid)
        assertNull(deletedChild)

        // Parent should still exist
        val parentBlock = blockRepo.getBlock(parent.uuid)
        assertNotNull(parentBlock)
    }

    // ============================================================
    // SPLIT BLOCK TESTS
    // ============================================================

    @Test
    fun `splitBlock at middle should create two blocks`() = runTest {
        val pageRepo = FakePageRepository()
        val blockRepo = InMemoryBlockRepository()

        val page = createPage("page-1")
        pageRepo.addPage(page)

        val block = createBlock("block-1", pageUuid = "page-1", position = 0, content = "HelloWorld")
        blockRepo.addBlock(block)

        val viewModel = createViewModel(pageRepo, blockRepo)
        viewModel.loadPageContent("page-1")

        // Split at position 5 (after "Hello")
        viewModel.splitBlock(block.uuid, 5)

        testScheduler.advanceUntilIdle()

        // Verify the split
        val blocks = blockRepo.getBlocksForPage("page-1").first().getOrNull()
            ?.sortedBy { it.position }
            ?: emptyList()

        assertEquals(2, blocks.size)
        assertEquals("Hello", blocks[0].content)
        assertEquals("World", blocks[1].content)
    }

    @Test
    fun `splitBlock at start should create empty block before`() = runTest {
        val pageRepo = FakePageRepository()
        val blockRepo = InMemoryBlockRepository()

        val page = createPage("page-1")
        pageRepo.addPage(page)

        val block = createBlock("block-1", pageUuid = "page-1", position = 0, content = "Full content")
        blockRepo.addBlock(block)

        val viewModel = createViewModel(pageRepo, blockRepo)
        viewModel.loadPageContent("page-1")

        // Split at position 0 (start)
        viewModel.splitBlock(block.uuid, 0)

        testScheduler.advanceUntilIdle()

        val blocks = blockRepo.getBlocksForPage("page-1").first().getOrNull()
            ?.sortedBy { it.position }
            ?: emptyList()

        assertEquals(2, blocks.size)
        assertEquals("", blocks[0].content)
        assertEquals("Full content", blocks[1].content)
    }

    @Test
    fun `splitBlock at end should create empty block after`() = runTest {
        val pageRepo = FakePageRepository()
        val blockRepo = InMemoryBlockRepository()

        val page = createPage("page-1")
        pageRepo.addPage(page)

        val block = createBlock("block-1", pageUuid = "page-1", position = 0, content = "Full content")
        blockRepo.addBlock(block)

        val viewModel = createViewModel(pageRepo, blockRepo)
        viewModel.loadPageContent("page-1")

        // Split at end (position = content length)
        viewModel.splitBlock(block.uuid, 12)  // "Full content".length = 12

        testScheduler.advanceUntilIdle()

        val blocks = blockRepo.getBlocksForPage("page-1").first().getOrNull()
            ?.sortedBy { it.position }
            ?: emptyList()

        assertEquals(2, blocks.size)
        assertEquals("Full content", blocks[0].content)
        assertEquals("", blocks[1].content)
    }

    @Test
    fun `splitBlock should maintain correct positions for subsequent blocks`() = runTest {
        val pageRepo = FakePageRepository()
        val blockRepo = InMemoryBlockRepository()

        val page = createPage("page-1")
        pageRepo.addPage(page)

        val block1 = createBlock("block-1", pageUuid = "page-1", position = 0, content = "First")
        val block2 = createBlock("block-2", pageUuid = "page-1", position = 1, content = "HelloWorld")
        val block3 = createBlock("block-3", pageUuid = "page-1", position = 2, content = "Third")
        blockRepo.addBlock(block1)
        blockRepo.addBlock(block2)
        blockRepo.addBlock(block3)

        val viewModel = createViewModel(pageRepo, blockRepo)
        viewModel.loadPageContent("page-1")

        // Split block2 at position 5
        viewModel.splitBlock(block2.uuid, 5)

        testScheduler.advanceUntilIdle()

        val blocks = blockRepo.getBlocksForPage("page-1").first().getOrNull()
            ?.sortedBy { it.position }
            ?: emptyList()

        assertEquals(4, blocks.size)
        assertEquals("First", blocks[0].content)
        assertEquals("Hello", blocks[1].content)
        assertEquals("World", blocks[2].content)
        assertEquals("Third", blocks[3].content)

        // Verify positions are sequential
        blocks.forEachIndexed { index, block ->
            assertEquals(index, block.position, "Block at index $index should have position $index")
        }
    }

    // ============================================================
    // ADD NEW BLOCK TESTS
    // ============================================================

    @Test
    fun `addNewBlock should create block after current`() = runTest {
        val pageRepo = FakePageRepository()
        val blockRepo = InMemoryBlockRepository()

        val page = createPage("page-1")
        pageRepo.addPage(page)

        val block1 = createBlock("block-1", pageUuid = "page-1", position = 0, content = "First")
        blockRepo.addBlock(block1)

        val viewModel = createViewModel(pageRepo, blockRepo)
        viewModel.loadPageContent("page-1")

        // Add new block after block1
        viewModel.addNewBlock(block1.uuid)

        testScheduler.advanceUntilIdle()

        val blocks = blockRepo.getBlocksForPage("page-1").first().getOrNull()
            ?.sortedBy { it.position }
            ?: emptyList()

        assertEquals(2, blocks.size)
        assertEquals("First", blocks[0].content)
        assertEquals("", blocks[1].content)  // New empty block
    }

    @Test
    fun `addNewBlock should shift subsequent blocks`() = runTest {
        val pageRepo = FakePageRepository()
        val blockRepo = InMemoryBlockRepository()

        val page = createPage("page-1")
        pageRepo.addPage(page)

        val block1 = createBlock("block-1", pageUuid = "page-1", position = 0, content = "A")
        val block2 = createBlock("block-2", pageUuid = "page-1", position = 1, content = "B")
        val block3 = createBlock("block-3", pageUuid = "page-1", position = 2, content = "C")
        blockRepo.addBlock(block1)
        blockRepo.addBlock(block2)
        blockRepo.addBlock(block3)

        val viewModel = createViewModel(pageRepo, blockRepo)
        viewModel.loadPageContent("page-1")

        // Add new block after block1
        viewModel.addNewBlock(block1.uuid)

        testScheduler.advanceUntilIdle()

        val blocks = blockRepo.getBlocksForPage("page-1").first().getOrNull()
            ?.sortedBy { it.position }
            ?: emptyList()

        assertEquals(4, blocks.size)
        assertEquals("A", blocks[0].content)
        assertEquals("", blocks[1].content)  // New block
        assertEquals("B", blocks[2].content)
        assertEquals("C", blocks[3].content)
    }

    // ============================================================
    // CROSS-PAGE ISOLATION TESTS
    // ============================================================

    @Test
    fun `operations should not affect blocks on other pages`() = runTest {
        val pageRepo = FakePageRepository()
        val blockRepo = InMemoryBlockRepository()

        val page1 = createPage("page-1", "Page1")
        val page2 = createPage("page-2", "Page2")
        pageRepo.addPage(page1)
        pageRepo.addPage(page2)

        // Page 1 blocks
        val p1Block1 = createBlock("p1-b1", pageUuid = "page-1", position = 0, content = "Page1-A")
        val p1Block2 = createBlock("p1-b2", pageUuid = "page-1", position = 1, content = "Page1-B")
        blockRepo.addBlock(p1Block1)
        blockRepo.addBlock(p1Block2)

        // Page 2 blocks
        val p2Block1 = createBlock("p2-b1", pageUuid = "page-2", position = 0, content = "Page2-A")
        val p2Block2 = createBlock("p2-b2", pageUuid = "page-2", position = 1, content = "Page2-B")
        blockRepo.addBlock(p2Block1)
        blockRepo.addBlock(p2Block2)

        val viewModel = createViewModel(pageRepo, blockRepo)
        viewModel.loadPageContent("page-1")
        viewModel.loadPageContent("page-2")

        // Merge blocks on page 1
        viewModel.mergeBlock(p1Block2.uuid)

        testScheduler.advanceUntilIdle()

        // Page 2 blocks should be unchanged
        val page2Blocks = blockRepo.getBlocksForPage("page-2").first().getOrNull() ?: emptyList()
        assertEquals(2, page2Blocks.size)
        assertEquals("Page2-A", page2Blocks[0].content)
        assertEquals("Page2-B", page2Blocks[1].content)
    }
}
