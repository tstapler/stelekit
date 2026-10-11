// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.mcp

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.RankedSearchHit
import dev.stapler.stelekit.repository.SearchRepository
import dev.stapler.stelekit.repository.SearchRequest
import dev.stapler.stelekit.repository.SearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Fake [SearchRepository] that always returns a canned [SearchResult], regardless of the request. */
private class FakeSearchRepository(private val result: SearchResult) : SearchRepository {
    var lastRequest: SearchRequest? = null

    override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> =
        flowOf(emptyList<Block>().right())

    override fun searchPagesByTitle(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        flowOf(emptyList<Page>().right())

    override fun findBlocksReferencing(blockUuid: BlockUuid): Flow<Either<DomainError, List<Block>>> =
        flowOf(emptyList<Block>().right())

    override fun searchWithFilters(searchRequest: SearchRequest): Flow<Either<DomainError, SearchResult>> {
        lastRequest = searchRequest
        return flowOf(result.right())
    }

    override suspend fun recordPageVisit(pageUuid: PageUuid): Either<DomainError, Unit> = Unit.right()
    override suspend fun rebuildFts(): Either<DomainError, Unit> = Unit.right()
    override suspend fun integrityCheckFts(): Either<DomainError, Unit> = Unit.right()
}

class SearchGraphToolTest {

    private val now = Clock.System.now()

    private fun zettelkastenPage() = Page(
        uuid = PageUuid("00000000-0000-0000-0000-000000000001"),
        name = "Zettelkasten Method",
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `search_graph returns a PageHit for a matching page over ChannelTransport`() = runBlocking {
        val hit = RankedSearchHit.PageHit(page = zettelkastenPage(), snippet = "...atomic notes...", score = 1.5)
        val fakeRepository = FakeSearchRepository(
            SearchResult(blocks = emptyList(), pages = listOf(hit.page), ranked = listOf(hit), totalCount = 1, hasMore = false),
        )

        val result = executeSearchGraph(fakeRepository, query = "atomic notes", scope = null, limit = null, offset = null)

        assertFalse(result.isError ?: false)
        val text = result.firstText()
        assertTrue("Zettelkasten Method" in text, "expected a hit for 'Zettelkasten Method' in: $text")
        assertTrue("\"hasMore\":false" in text, "expected hasMore:false in: $text")
    }

    @Test
    fun `search_graph clamps a limit above 50 to 50 before calling SearchRepository_searchWithFilters`() = runTest {
        val fakeRepository = FakeSearchRepository(SearchResult(emptyList(), emptyList(), totalCount = 0, hasMore = false))

        executeSearchGraph(fakeRepository, query = "x", scope = null, limit = 500, offset = null)

        assertEquals(
            SEARCH_GRAPH_MAX_LIMIT,
            fakeRepository.lastRequest?.limit,
            "limit above $SEARCH_GRAPH_MAX_LIMIT must be clamped before reaching the repository",
        )
    }

    @Test
    fun `clampSearchGraphLimit never exceeds SEARCH_GRAPH_MAX_LIMIT and defaults to 20`() {
        assertEquals(SEARCH_GRAPH_MAX_LIMIT, clampSearchGraphLimit(500))
        assertEquals(SEARCH_GRAPH_MAX_LIMIT, clampSearchGraphLimit(SEARCH_GRAPH_MAX_LIMIT))
        assertEquals(1, clampSearchGraphLimit(1))
        assertEquals(20, clampSearchGraphLimit(null))
    }

    @Test
    fun `search_graph returns GraphNotOpen without hanging when no repository set is active`() = runBlocking {
        val graphManager = unopenedGraphManager()
        val server = McpAdapter(graphManager, RecordingSuggestionSink(), { "test-client" }).buildServer()
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.callTool(
                "search_graph",
                buildJsonObject { put("query", JsonPrimitive("x")) },
            )

            assertTrue(result.isError == true, "expected an error result when no graph is open")
            assertTrue("No graph is currently open" in result.firstText(), "unexpected message: ${result.firstText()}")
        } finally {
            client.close()
            server.close()
        }
    }
}
