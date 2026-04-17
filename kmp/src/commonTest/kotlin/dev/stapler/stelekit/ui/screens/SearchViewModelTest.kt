package dev.stapler.stelekit.ui.screens

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.SearchRepository
import dev.stapler.stelekit.repository.SearchRequest
import dev.stapler.stelekit.repository.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchViewModelTest {

    class FakeSearchRepository : SearchRepository {
        override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Result<List<Block>>> {
            return flowOf(Result.success(emptyList()))
        }

        override fun searchPagesByTitle(query: String, limit: Int): Flow<Result<List<Page>>> {
            return flowOf(Result.success(emptyList()))
        }

        override fun findBlocksReferencing(blockUuid: String): Flow<Result<List<Block>>> {
            return flowOf(Result.success(emptyList()))
        }

        override fun searchWithFilters(searchRequest: SearchRequest): Flow<Result<SearchResult>> {
            if (searchRequest.query == "test") {
                val page = Page(
                    uuid = "uuid-1",
                    name = "Test Page",
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now()
                )
                val block = Block(
                    uuid = "uuid-2",
                    pageUuid = "uuid-1",
                    content = "This is a test block",
                    position = 0,
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now()
                )
                return flowOf(Result.success(SearchResult(listOf(block), listOf(page), totalCount = 2, hasMore = false)))
            }
            return flowOf(Result.success(SearchResult(emptyList(), emptyList(), totalCount = 0, hasMore = false)))
        }
    }

    @Test
    fun testSearch() = runTest {
        val repo = FakeSearchRepository()
        val viewModel = SearchViewModel(repo, CoroutineScope(Dispatchers.Unconfined))

        // Initial state
        assertEquals("", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.results.isEmpty())

        // Perform search
        viewModel.onQueryChange("test")
        
        // Check if query updated
        assertEquals("test", viewModel.uiState.value.query)
    }
}
