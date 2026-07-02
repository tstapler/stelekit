package dev.stapler.stelekit.llm

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking

class LlmSynthesisServiceTest {

    private class FakeLlmProvider(
        override val id: String,
        override val supportsLongFormOutput: Boolean = true,
        private val response: LlmResult = LlmResult.Success(""),
    ) : LlmProvider {
        override val displayName: String = id
        override val kind: LlmProviderKind = LlmProviderKind.REMOTE
        override val formatter: LlmFormatterProvider = LlmFormatterProvider { _, _ -> response }
        override suspend fun checkAvailability(): LlmProviderAvailability = LlmProviderAvailability.Available
    }

    private val now = Clock.System.now()

    private fun page(name: String = "Hub") = Page(uuid = PageUuid("hub-uuid"), name = name, createdAt = now, updatedAt = now)

    private fun blocks(vararg contents: String): List<Block> = contents.mapIndexed { i, content ->
        Block(
            uuid = BlockUuid("block-$i"), pageUuid = PageUuid("hub-uuid"), content = content,
            position = "a$i", createdAt = now, updatedAt = now,
        )
    }

    private fun contextBuilder(): LlmSynthesisContextBuilder =
        LlmSynthesisContextBuilder(InMemoryPageRepository(), InMemoryBlockRepository())

    @Test
    fun parse_should_ProduceExpectedTagChangeProposals_When_WellFormedFreeTextOutput() {
        val service = LlmSynthesisService(
            LlmProviderRegistry(listOf(FakeLlmProvider("anthropic"))),
            contextBuilder(),
            LlmSuggestionInbox(),
        )
        val currentPage = page()
        val currentBlocks = blocks("first block", "second block")
        val responseText = "0|ADD:Recipes;Cooking|REMOVE:\n1|ADD:|REMOVE:OldTag"

        val proposals = service.parseResponse(responseText, currentPage, currentBlocks, "graph-1", "anthropic")

        assertEquals(2, proposals.size)
        assertEquals(listOf("Recipes", "Cooking"), proposals[0].addedTerms)
        assertEquals(emptyList(), proposals[0].removedTerms)
        assertEquals("block-0", proposals[0].blockUuid)
        assertEquals(emptyList(), proposals[1].addedTerms)
        assertEquals(listOf("OldTag"), proposals[1].removedTerms)
        assertEquals("block-1", proposals[1].blockUuid)
    }

    @Test
    fun parse_should_TolerateMalformedOutput_BestEffortSubset_NoCrash() {
        val service = LlmSynthesisService(
            LlmProviderRegistry(listOf(FakeLlmProvider("anthropic"))),
            contextBuilder(),
            LlmSuggestionInbox(),
        )
        val currentPage = page()
        val currentBlocks = blocks("first block", "second block")
        val responseText = """
            garbage line with no pipes
            0|ADD:GoodTag|REMOVE:
            99|ADD:OutOfRange|REMOVE:
            notanumber|ADD:Bad|REMOVE:
            1|ADD:|REMOVE:
        """.trimIndent()

        val proposals = service.parseResponse(responseText, currentPage, currentBlocks, "graph-1", "anthropic")

        // Only the "0|ADD:GoodTag|REMOVE:" line is valid: garbage line has no pipes, index 99
        // is out of range, "notanumber" isn't a valid index, and the last line has no terms.
        assertEquals(1, proposals.size)
        assertEquals(listOf("GoodTag"), proposals[0].addedTerms)
    }

    @Test
    fun proposalCount_should_BeCappedAtConfiguredMax_When_ModelReturnsMore() = runBlocking {
        val manyLines = (0 until 25).joinToString("\n") { i -> "0|ADD:Tag$i|REMOVE:" }
        val provider = FakeLlmProvider("anthropic", response = LlmResult.Success(manyLines))
        val inbox = LlmSuggestionInbox()
        val service = LlmSynthesisService(
            LlmProviderRegistry(listOf(provider)),
            contextBuilder(),
            inbox,
        )
        val currentPage = page()
        val currentBlocks = blocks("only block")

        val result = service.synthesizeForPage("graph-1", currentPage, currentBlocks)

        assertTrue(result.isRight())
        assertEquals(LlmSynthesisService.MAX_PROPOSALS_PER_RUN, (result as arrow.core.Either.Right).value)
        assertEquals(LlmSynthesisService.MAX_PROPOSALS_PER_RUN, inbox.pending.value.size)
    }

    @Test
    fun service_should_RefuseWithClearMessage_When_OnlyShortFormOnlyProviderAvailable() = runBlocking {
        val onDeviceProvider = FakeLlmProvider("android-ondevice", supportsLongFormOutput = false)
        val service = LlmSynthesisService(
            LlmProviderRegistry(listOf(onDeviceProvider)),
            contextBuilder(),
            LlmSuggestionInbox(),
        )
        val currentPage = page()
        val currentBlocks = blocks("only block")

        val result = service.synthesizeForPage("graph-1", currentPage, currentBlocks)

        assertTrue(result.isLeft())
        val error = (result as arrow.core.Either.Left).value
        assertTrue(error.message.contains("on-device", ignoreCase = true) || error.message.contains("remote", ignoreCase = true))
    }
}
