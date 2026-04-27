@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.screens

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

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
        override fun getBlocksForPage(pageUuid: String): Flow<Either<DomainError, List<Block>>> = flowOf(emptyList<Block>().right())
        override fun getBlockByUuid(uuid: String): Flow<Either<DomainError, Block?>> = flowOf(null.right())
        override fun getBlockChildren(blockUuid: String): Flow<Either<DomainError, List<Block>>> = flowOf(emptyList<Block>().right())
        override fun getBlockHierarchy(rootUuid: String): Flow<Either<DomainError, List<BlockWithDepth>>> = flowOf(emptyList<BlockWithDepth>().right())
        override fun getBlockAncestors(blockUuid: String): Flow<Either<DomainError, List<Block>>> = flowOf(emptyList<Block>().right())
        override fun getBlockParent(blockUuid: String): Flow<Either<DomainError, Block?>> = flowOf(null.right())
        override fun getBlockSiblings(blockUuid: String): Flow<Either<DomainError, List<Block>>> = flowOf(emptyList<Block>().right())
        override fun getLinkedReferences(pageName: String): Flow<Either<DomainError, List<Block>>> = flowOf(emptyList<Block>().right())
        override fun getLinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> = flowOf(emptyList<Block>().right())
        override fun countLinkedReferences(pageName: String): Flow<Either<DomainError, Long>> = flowOf(0L.right())
        override fun getUnlinkedReferences(pageName: String): Flow<Either<DomainError, List<Block>>> = flowOf(emptyList<Block>().right())
        override fun getUnlinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> = flowOf(emptyList<Block>().right())
        override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> = flowOf(emptyList<Block>().right())
        
        override suspend fun saveBlock(block: Block): Either<DomainError, Unit> = Unit.right()
        override suspend fun saveBlocks(blocks: List<Block>): Either<DomainError, Unit> = Unit.right()
        override suspend fun deleteBlock(blockUuid: String, deleteChildren: Boolean): Either<DomainError, Unit> = Unit.right()
        override suspend fun deleteBulk(blockUuids: List<String>, deleteChildren: Boolean): Either<DomainError, Unit> = Unit.right()
        override suspend fun deleteBlocksForPage(pageUuid: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun deleteBlocksForPages(pageUuids: List<String>): Either<DomainError, Unit> = Unit.right()
        override suspend fun moveBlock(blockUuid: String, newParentUuid: String?, newPosition: Int): Either<DomainError, Unit> = Unit.right()
        override suspend fun indentBlock(blockUuid: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun outdentBlock(blockUuid: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun moveBlockUp(blockUuid: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun moveBlockDown(blockUuid: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun mergeBlocks(blockUuid: String, nextBlockUuid: String, separator: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun splitBlock(blockUuid: String, cursorPosition: Int): Either<DomainError, Block> = DomainError.DatabaseError.WriteFailed("not implemented").left()
        override fun findDuplicateBlocks(limit: Int): Flow<Either<DomainError, List<DuplicateGroup>>> = flowOf(emptyList<DuplicateGroup>().right())
        override suspend fun clear() {}
    }

    class FakePageRepository : PageRepository {
        val pages = mutableListOf<Page>()

        override fun getAllPages(): Flow<Either<DomainError, List<Page>>> = flowOf(pages.right())

        override fun getJournalPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
            val journals = pages
                .filter { it.isJournal }
                .sortedByDescending { it.journalDate }
                .drop(offset)
                .take(limit)
            return flowOf(journals.right())
        }

        override fun getPagesInNamespace(namespace: String): Flow<Either<DomainError, List<Page>>> = flowOf(emptyList<Page>().right())
        
        override fun getPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
            val result = pages.sortedBy { it.name }.drop(offset).take(limit)
            return flowOf(result.right())
        }

        override fun searchPages(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
            val result = pages
                .filter { it.name.contains(query, ignoreCase = true) }
                .sortedBy { it.name }
                .drop(offset)
                .take(limit)
            return flowOf(result.right())
        }

        override fun getPageByUuid(uuid: String): Flow<Either<DomainError, Page?>> = flowOf(pages.find { it.uuid == uuid }.right())
        override fun getPageByName(name: String): Flow<Either<DomainError, Page?>> = flowOf(pages.find { it.name == name }.right())
        override fun getRecentPages(limit: Int): Flow<Either<DomainError, List<Page>>> = flowOf(pages.sortedByDescending { it.updatedAt }.take(limit).right())
        override fun getJournalPageByDate(date: LocalDate): Flow<Either<DomainError, Page?>> = flowOf(pages.find { it.journalDate == date }.right())
        override fun getUnloadedPages(): Flow<Either<DomainError, List<Page>>> = flowOf(pages.filter { !it.isContentLoaded }.right())
        override suspend fun savePage(page: Page): Either<DomainError, Unit> {
            val existingIdx = pages.indexOfFirst { it.uuid == page.uuid }
            if (existingIdx >= 0) pages[existingIdx] = page else pages.add(page)
            return Unit.right()
        }
        override suspend fun savePages(pageList: List<Page>): Either<DomainError, Unit> {
            pageList.forEach { page ->
                val existingIdx = pages.indexOfFirst { it.uuid == page.uuid }
                if (existingIdx >= 0) pages[existingIdx] = page else pages.add(page)
            }
            return Unit.right()
        }
        override suspend fun deletePage(pageUuid: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun renamePage(pageUuid: String, newName: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun toggleFavorite(pageUuid: String): Either<DomainError, Unit> = Unit.right()
        override fun countPages(): Flow<Either<DomainError, Long>> = flowOf(pages.size.toLong().right())
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
