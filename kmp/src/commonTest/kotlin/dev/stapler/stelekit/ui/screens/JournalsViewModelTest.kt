@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.screens

import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.repository.BlockReferences
import dev.stapler.stelekit.repository.BlockWithDepth
import dev.stapler.stelekit.repository.BlockWithReferenceCount
import dev.stapler.stelekit.repository.DuplicateGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class JournalsViewModelTest {

    class FakeFileSystem : FileSystem {
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

    class FakeBlockRepository : BlockRepository {
        override fun getBlocksForPage(pageUuid: String): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
        override fun getBlockByUuid(uuid: String): Flow<Result<Block?>> = flowOf(Result.success(null))
        override fun getBlockChildren(blockUuid: String): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
        override fun getBlockHierarchy(rootUuid: String): Flow<Result<List<BlockWithDepth>>> = flowOf(Result.success(emptyList()))
        override fun getBlockAncestors(blockUuid: String): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
        override fun getBlockParent(blockUuid: String): Flow<Result<Block?>> = flowOf(Result.success(null))
        override fun getBlockSiblings(blockUuid: String): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
        override fun getLinkedReferences(pageName: String): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
        override fun getLinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
        override fun countLinkedReferences(pageName: String): Flow<Result<Long>> = flowOf(Result.success(0L))
        override fun getUnlinkedReferences(pageName: String): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
        override fun getUnlinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
        override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Result<List<Block>>> = flowOf(Result.success(emptyList()))
        
        override suspend fun saveBlock(block: Block): Result<Unit> = Result.success(Unit)
        override suspend fun saveBlocks(blocks: List<Block>): Result<Unit> = Result.success(Unit)
        override suspend fun deleteBlock(blockUuid: String, deleteChildren: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun deleteBulk(blockUuids: List<String>, deleteChildren: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun deleteBlocksForPage(pageUuid: String): Result<Unit> = Result.success(Unit)
        override suspend fun deleteBlocksForPages(pageUuids: List<String>): Result<Unit> = Result.success(Unit)
        override suspend fun moveBlock(blockUuid: String, newParentUuid: String?, newPosition: Int): Result<Unit> = Result.success(Unit)
        override suspend fun indentBlock(blockUuid: String): Result<Unit> = Result.success(Unit)
        override suspend fun outdentBlock(blockUuid: String): Result<Unit> = Result.success(Unit)
        override suspend fun moveBlockUp(blockUuid: String): Result<Unit> = Result.success(Unit)
        override suspend fun moveBlockDown(blockUuid: String): Result<Unit> = Result.success(Unit)
        override suspend fun mergeBlocks(blockUuid: String, nextBlockUuid: String, separator: String): Result<Unit> = Result.success(Unit)
        override suspend fun splitBlock(blockUuid: String, cursorPosition: Int): Result<Block> = Result.failure(NotImplementedError())
        override fun findDuplicateBlocks(limit: Int): Flow<Result<List<DuplicateGroup>>> = flowOf(Result.success(emptyList()))
        override suspend fun clear() {}
    }

    class FakePageRepository : PageRepository {
        val pages = mutableListOf<Page>()

        override fun getAllPages(): Flow<Result<List<Page>>> = flowOf(Result.success(pages))

        override fun getJournalPages(limit: Int, offset: Int): Flow<Result<List<Page>>> {
            val journals = pages
                .filter { it.isJournal }
                .sortedByDescending { it.journalDate }
                .drop(offset)
                .take(limit)
            return flowOf(Result.success(journals))
        }

        override fun getPagesInNamespace(namespace: String): Flow<Result<List<Page>>> = flowOf(Result.success(emptyList()))
        
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

        override fun getPageByUuid(uuid: String): Flow<Result<Page?>> = flowOf(Result.success(pages.find { it.uuid == uuid }))
        override fun getPageByName(name: String): Flow<Result<Page?>> = flowOf(Result.success(pages.find { it.name == name }))
        override fun getRecentPages(limit: Int): Flow<Result<List<Page>>> = flowOf(Result.success(pages.sortedByDescending { it.updatedAt }.take(limit)))
        override fun getJournalPageByDate(date: LocalDate): Flow<Result<Page?>> = flowOf(Result.success(pages.find { it.journalDate == date }))
        override fun getUnloadedPages(): Flow<Result<List<Page>>> = flowOf(Result.success(pages.filter { !it.isContentLoaded }))
        override suspend fun savePage(page: Page): Result<Unit> {
            val existingIdx = pages.indexOfFirst { it.uuid == page.uuid }
            if (existingIdx >= 0) pages[existingIdx] = page else pages.add(page)
            return Result.success(Unit)
        }
        override suspend fun savePages(pageList: List<Page>): Result<Unit> {
            pageList.forEach { page ->
                val existingIdx = pages.indexOfFirst { it.uuid == page.uuid }
                if (existingIdx >= 0) pages[existingIdx] = page else pages.add(page)
            }
            return Result.success(Unit)
        }
        override suspend fun deletePage(pageUuid: String): Result<Unit> = Result.success(Unit)
        override suspend fun renamePage(pageUuid: String, newName: String): Result<Unit> = Result.success(Unit)
        override suspend fun toggleFavorite(pageUuid: String): Result<Unit> = Result.success(Unit)
        override fun countPages(): Flow<Result<Long>> = flowOf(Result.success(pages.size.toLong()))
        override suspend fun clear() { pages.clear() }
    }

    private fun generateFakeUuid(index: Int): String {
        val hex = index.toString(16).padStart(12, '0')
        return "00000000-0000-0000-0000-$hex"
    }

    @Test
    fun testLoadMore() = runTest {
        val repo = FakePageRepository()
        
        // 1. Pre-create today's journal to prevent generateTodayJournal() from adding an extra one
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        repo.savePage(
            Page(
                uuid = "today-uuid",
                name = today.toString(),
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
                isJournal = true,
                journalDate = today
            )
        )
        
        // 2. Create 14 more journal pages (total 15)
        for (i in 1..14) {
            val date = LocalDate(2025, 1, i) // Use last year to avoid collision with today
            repo.savePage(
                Page(
                    uuid = generateFakeUuid(i),
                    name = "2025-01-${i.toString().padStart(2, '0')}",
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now(),
                    isJournal = true,
                    journalDate = date
                )
            )
        }

        val blockRepo = FakeBlockRepository()
        val fileSystem = FakeFileSystem()
        val journalService = JournalService(repo, blockRepo)
        val graphLoader = GraphLoader(fileSystem, repo, blockRepo)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val blockStateManager = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = scope
        )
        val viewModel = JournalsViewModel(journalService, blockStateManager, scope)
        
        // Initial load (pageSize is 10)
        assertEquals(10, viewModel.uiState.value.pages.size)
        
        // Load more (remaining 5 pages)
        viewModel.loadMore()
        assertEquals(15, viewModel.uiState.value.pages.size)
        
        // Load more again (no more pages)
        viewModel.loadMore()
        assertEquals(15, viewModel.uiState.value.pages.size)
    }
}
