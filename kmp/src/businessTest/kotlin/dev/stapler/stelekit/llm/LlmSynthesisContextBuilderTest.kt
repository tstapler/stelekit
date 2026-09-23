package dev.stapler.stelekit.llm

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.PageRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Story 7.6 / adversarial-review M1 fix — proves [LlmSynthesisContextBuilder] never scans
 * the graph: query counts stay fixed regardless of graph size, content is fetched for at
 * most [LlmSynthesisContextBuilder.MAX_CANDIDATE_PAGES] pages, and the selected candidate
 * set is EXACTLY the backlinks-plus-outbound-links union (not merely "query count is low" —
 * a scan-based relevance heuristic that still called bounded methods would fail these tests).
 */
class LlmSynthesisContextBuilderTest {

    /** Counts invocations of the bounded query methods this builder is allowed to use. */
    private class QueryCountingBlockRepository(private val delegate: BlockRepository) : BlockRepository by delegate {
        var getLinkedReferencesCalls = 0
        var getBlocksForPageCalls = 0

        override fun getLinkedReferences(pageName: String, limit: Int, offset: Int) =
            delegate.getLinkedReferences(pageName, limit, offset).also { getLinkedReferencesCalls++ }

        override fun getBlocksForPage(pageUuid: PageUuid): Flow<Either<DomainError, List<Block>>> {
            getBlocksForPageCalls++
            return delegate.getBlocksForPage(pageUuid)
        }
    }

    private class QueryCountingPageRepository(private val delegate: PageRepository) : PageRepository by delegate {
        var getPageByUuidCalls = 0
        var getPagesByNamesCalls = 0

        override fun getPageByUuid(uuid: PageUuid) =
            delegate.getPageByUuid(uuid).also { getPageByUuidCalls++ }

        override suspend fun getPagesByNames(names: Collection<String>): Either<DomainError, List<Page>> {
            getPagesByNamesCalls++
            return delegate.getPagesByNames(names)
        }
    }

    private val now = Clock.System.now()

    private fun page(name: String, uuid: String = "uuid-$name") = Page(
        uuid = PageUuid(uuid), name = name, createdAt = now, updatedAt = now,
    )

    private fun block(pageUuid: String, content: String, uuid: String = "block-${pageUuid}-${content.hashCode()}") = Block(
        uuid = BlockUuid(uuid), pageUuid = PageUuid(pageUuid), content = content, position = "a0",
        createdAt = now, updatedAt = now,
    )

    /** Builds a graph of [pageCount] unrelated filler pages plus the Hub/backlink/outbound fixture. */
    private fun buildFixture(
        fillerPageCount: Int,
        backlinkCount: Int,
        outboundCount: Int,
    ): Triple<QueryCountingPageRepository, QueryCountingBlockRepository, Page> = runBlocking {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        // Filler pages — never referenced, prove the builder doesn't scan them.
        repeat(fillerPageCount) { i ->
            pageRepo.savePage(page("Filler$i"))
        }

        val hub = page("Hub")
        pageRepo.savePage(hub)
        blockRepo.saveBlock(block(hub.uuid.value, "Hub content"))

        // Backlink pages: each has one block linking to [[Hub]].
        repeat(backlinkCount) { i ->
            val name = "Backlink$i"
            pageRepo.savePage(page(name))
            blockRepo.saveBlock(block("uuid-$name", "See [[Hub]] for details"))
        }

        // Outbound pages: Hub's own content links to each of these.
        val outboundNames = (0 until outboundCount).map { "Outbound$it" }
        outboundNames.forEach { name -> pageRepo.savePage(page(name)) }
        val hubContent = "Hub content " + outboundNames.joinToString(" ") { "[[$it]]" }
        blockRepo.saveBlock(block(hub.uuid.value, hubContent, uuid = "hub-root-block"))

        // Give every referenced page (backlink + outbound) some content to fetch.
        repeat(backlinkCount) { i -> blockRepo.saveBlock(block("uuid-Backlink$i", "Backlink$i own content", uuid = "content-Backlink$i")) }
        outboundNames.forEach { name -> blockRepo.saveBlock(block("uuid-$name", "$name own content", uuid = "content-$name")) }

        Triple(QueryCountingPageRepository(pageRepo), QueryCountingBlockRepository(blockRepo), hub)
    }

    private suspend fun currentPageBlocks(blockRepo: BlockRepository, hub: Page): List<Block> {
        val result = blockRepo.getBlocksForPage(hub.uuid).first()
        return (result as Either.Right).value
    }

    @Test
    fun build_should_IssueAtMostNBoundedQueries_When_RunAgainstLargeGraphFixture() = runBlocking {
        val (pageRepo, blockRepo, hub) = buildFixture(fillerPageCount = 5000, backlinkCount = 5, outboundCount = 5)
        val hubBlocks = currentPageBlocks(blockRepo, hub)

        val builder = LlmSynthesisContextBuilder(pageRepo, blockRepo)
        val result = builder.build(hub, hubBlocks)

        assertTrue(result.isRight())
        // Fixed, small bound: 1 getLinkedReferences + <=20 getPageByUuid + 1 getPagesByNames
        // + <=20 getBlocksForPage — none of these scale with the 5000 filler pages.
        assertTrue(blockRepo.getLinkedReferencesCalls <= 2, "getLinkedReferences calls: ${blockRepo.getLinkedReferencesCalls}")
        assertTrue(pageRepo.getPageByUuidCalls <= 20, "getPageByUuid calls: ${pageRepo.getPageByUuidCalls}")
        assertTrue(pageRepo.getPagesByNamesCalls <= 2, "getPagesByNames calls: ${pageRepo.getPagesByNamesCalls}")
        assertTrue(blockRepo.getBlocksForPageCalls <= 21, "getBlocksForPage calls: ${blockRepo.getBlocksForPageCalls}")
    }

    @Test
    fun build_should_NeverFetchFullPageContent_ForMoreThanCappedPageCount() = runBlocking {
        val (pageRepo, blockRepo, hub) = buildFixture(fillerPageCount = 200, backlinkCount = 15, outboundCount = 15)
        val hubBlocks = currentPageBlocks(blockRepo, hub)

        val builder = LlmSynthesisContextBuilder(pageRepo, blockRepo)
        val result = builder.build(hub, hubBlocks)

        assertTrue(result.isRight())
        val context = (result as Either.Right).value
        assertTrue(context.candidatePages.size <= LlmSynthesisContextBuilder.MAX_CANDIDATE_PAGES)
        // getBlocksForPage is called once per current-page load (hub) + once per candidate page.
        assertTrue(
            blockRepo.getBlocksForPageCalls <= LlmSynthesisContextBuilder.MAX_CANDIDATE_PAGES + 1,
            "getBlocksForPage calls: ${blockRepo.getBlocksForPageCalls}",
        )
    }

    @Test
    fun build_should_SelectExactlyTheBacklinksPlusOutboundLinksUnion_When_FixtureHasKnownLinkStructure() = runBlocking {
        val (pageRepo, blockRepo, hub) = buildFixture(fillerPageCount = 10, backlinkCount = 3, outboundCount = 2)
        val hubBlocks = currentPageBlocks(blockRepo, hub)

        val builder = LlmSynthesisContextBuilder(pageRepo, blockRepo)
        val result = builder.build(hub, hubBlocks)

        assertTrue(result.isRight())
        val context = (result as Either.Right).value
        val expectedNames = setOf("Backlink0", "Backlink1", "Backlink2", "Outbound0", "Outbound1")
        assertEquals(expectedNames, context.candidatePages.map { it.name }.toSet())

        val backlinkNames = context.candidatePages
            .filter { it.source == LlmSynthesisContextBuilder.CandidateSource.BACKLINK }
            .map { it.name }.toSet()
        val outboundNamesSelected = context.candidatePages
            .filter { it.source == LlmSynthesisContextBuilder.CandidateSource.OUTBOUND_LINK }
            .map { it.name }.toSet()
        assertEquals(setOf("Backlink0", "Backlink1", "Backlink2"), backlinkNames)
        assertEquals(setOf("Outbound0", "Outbound1"), outboundNamesSelected)
    }

    @Test
    fun build_should_PreferBacklinksOverOutboundLinks_When_UnionExceedsCapOf20() = runBlocking {
        val (pageRepo, blockRepo, hub) = buildFixture(fillerPageCount = 10, backlinkCount = 15, outboundCount = 10)
        val hubBlocks = currentPageBlocks(blockRepo, hub)

        val builder = LlmSynthesisContextBuilder(pageRepo, blockRepo)
        val result = builder.build(hub, hubBlocks)

        assertTrue(result.isRight())
        val context = (result as Either.Right).value
        assertEquals(LlmSynthesisContextBuilder.MAX_CANDIDATE_PAGES, context.candidatePages.size)

        val backlinks = context.candidatePages.filter { it.source == LlmSynthesisContextBuilder.CandidateSource.BACKLINK }
        val outbound = context.candidatePages.filter { it.source == LlmSynthesisContextBuilder.CandidateSource.OUTBOUND_LINK }
        // All 15 backlinks kept in full; only 5 of the 10 outbound links fit in the remaining cap.
        assertEquals(15, backlinks.size)
        assertEquals(5, outbound.size)
    }

    @Test
    fun build_should_CapTotalPromptCharacters_And_PerPageContentChars() = runBlocking {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val hub = page("Hub")
        pageRepo.savePage(hub)

        val longContent = "x".repeat(5000)
        blockRepo.saveBlock(block(hub.uuid.value, "Hub links [[Big0]] [[Big1]] [[Big2]]", uuid = "hub-root"))

        val bigNames = listOf("Big0", "Big1", "Big2")
        bigNames.forEach { name ->
            pageRepo.savePage(page(name))
            blockRepo.saveBlock(block("uuid-$name", longContent, uuid = "content-$name"))
        }

        val countingPageRepo = QueryCountingPageRepository(pageRepo)
        val countingBlockRepo = QueryCountingBlockRepository(blockRepo)
        val hubBlocks = currentPageBlocks(countingBlockRepo, hub)

        val builder = LlmSynthesisContextBuilder(countingPageRepo, countingBlockRepo)
        val result = builder.build(hub, hubBlocks)

        assertTrue(result.isRight())
        val context = (result as Either.Right).value
        context.candidatePages.forEach { candidate ->
            assertTrue(
                candidate.contentPreview.length <= LlmSynthesisContextBuilder.MAX_CONTENT_CHARS_PER_PAGE,
                "candidate '${candidate.name}' content length ${candidate.contentPreview.length}",
            )
        }
        assertTrue(context.promptText.length <= LlmSynthesisContextBuilder.MAX_TOTAL_PROMPT_CHARS)
    }
}
