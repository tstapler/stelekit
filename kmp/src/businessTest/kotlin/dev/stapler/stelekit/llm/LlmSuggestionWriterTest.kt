package dev.stapler.stelekit.llm

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphWriterPort
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.vault.CryptoLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking

class LlmSuggestionWriterTest {

    private class SpyGraphWriterPort : GraphWriterPort {
        var savedPage: Page? = null
        var savedBlocks: List<Block>? = null
        var savedGraphPath: String? = null
        var saveCallCount: Int = 0

        override fun setCryptoLayer(layer: CryptoLayer?) {}
        override fun closeAndClearCryptoLayer() {}
        override fun startAutoSave(debounceMs: Long) {}
        override fun stopAutoSave() {}
        override suspend fun flush() {}
        override suspend fun renamePage(page: Page, newName: String, graphPath: String) = true
        override suspend fun savePage(page: Page, blocks: List<Block>, graphPath: String) {
            saveCallCount++
            savedPage = page
            savedBlocks = blocks
            savedGraphPath = graphPath
        }
        override suspend fun deletePage(page: Page) = true
        override suspend fun movePageToSection(
            page: Page,
            newSectionId: String,
            newPathPrefix: String,
        ): Either<DomainError, Page> = page.copy(sectionId = newSectionId).right()
    }

    private val now = Clock.System.now()
    private val graphPath = "/tmp/graph"

    private fun page(uuid: String, name: String = "Test Page") = Page(
        uuid = PageUuid(uuid),
        name = name,
        createdAt = now,
        updatedAt = now,
    )

    private fun block(uuid: String, pageUuid: String, content: String, position: String = "a0") = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = content,
        position = position,
        createdAt = now,
        updatedAt = now,
    )

    private fun setup(): Triple<InMemoryPageRepository, InMemoryBlockRepository, SpyGraphWriterPort> =
        Triple(InMemoryPageRepository(), InMemoryBlockRepository(), SpyGraphWriterPort())

    @Test
    fun materializeAndWrite_should_CallWrite_When_BlockEditTargetMatchesSnapshot() = runBlocking {
        val (pageRepo, blockRepo, writer) = setup()
        val pageUuid = "page-1"
        val blockUuid = "block-1"
        pageRepo.savePage(page(pageUuid))
        blockRepo.saveBlock(block(blockUuid, pageUuid, "original content"))

        val suggestion = PendingLlmSuggestion.BlockEdit(
            id = "id-1", graphId = "graph-1", sourceProviderId = "anthropic",
            proposedAtEpochMs = 1L, rationale = null,
            pageUuid = pageUuid, blockUuid = blockUuid,
            currentContentSnapshot = "original content",
            proposedContent = "updated content",
        )

        val sut = LlmSuggestionWriter(pageRepo, blockRepo, writer)
        val result = sut.materializeAndWrite(suggestion, graphPath)

        assertTrue(result.isRight())
        assertEquals(1, writer.saveCallCount)
        val savedBlock = writer.savedBlocks?.first { it.uuid.value == blockUuid }
        assertEquals("updated content", savedBlock?.content)
        assertEquals(graphPath, writer.savedGraphPath)
    }

    @Test
    fun materializeAndWrite_should_NotWrite_SurfaceConcurrentWrite_When_BlockEditTargetContentChanged() = runBlocking {
        val (pageRepo, blockRepo, writer) = setup()
        val pageUuid = "page-1"
        val blockUuid = "block-1"
        pageRepo.savePage(page(pageUuid))
        blockRepo.saveBlock(block(blockUuid, pageUuid, "content changed since proposal"))

        val suggestion = PendingLlmSuggestion.BlockEdit(
            id = "id-1", graphId = "graph-1", sourceProviderId = "anthropic",
            proposedAtEpochMs = 1L, rationale = null,
            pageUuid = pageUuid, blockUuid = blockUuid,
            currentContentSnapshot = "original content",
            proposedContent = "updated content",
        )

        val sut = LlmSuggestionWriter(pageRepo, blockRepo, writer)
        val result = sut.materializeAndWrite(suggestion, graphPath)

        assertEquals(0, writer.saveCallCount)
        assertTrue(result.isLeft())
        assertIs<DomainError.ConflictError.ConcurrentWrite>(result.leftOrNull())
        Unit
    }

    @Test
    fun materializeAndWrite_should_NotWrite_SurfaceNotFound_When_BlockEditTargetDeleted() = runBlocking {
        val (pageRepo, blockRepo, writer) = setup()
        val pageUuid = "page-1"
        val blockUuid = "block-1"
        pageRepo.savePage(page(pageUuid))
        // Block never saved -> deleted/never-existed target

        val suggestion = PendingLlmSuggestion.BlockEdit(
            id = "id-1", graphId = "graph-1", sourceProviderId = "anthropic",
            proposedAtEpochMs = 1L, rationale = null,
            pageUuid = pageUuid, blockUuid = blockUuid,
            currentContentSnapshot = "original content",
            proposedContent = "updated content",
        )

        val sut = LlmSuggestionWriter(pageRepo, blockRepo, writer)
        val result = sut.materializeAndWrite(suggestion, graphPath)

        assertEquals(0, writer.saveCallCount)
        assertTrue(result.isLeft())
        assertIs<DomainError.DatabaseError.NotFound>(result.leftOrNull())
        Unit
    }

    @Test
    fun materializeAndWrite_should_ConstructFreshPageAndBlocks_When_NewPage() = runBlocking {
        val (pageRepo, blockRepo, writer) = setup()
        val suggestion = PendingLlmSuggestion.NewPage(
            id = "id-1", graphId = "graph-1", sourceProviderId = "anthropic",
            proposedAtEpochMs = 1L, rationale = "synthesized",
            proposedTitle = "New Synthesized Page",
            proposedBlocks = listOf(
                ProposedBlock("root block", depth = 0, order = 0),
                ProposedBlock("child block", depth = 1, order = 1),
                ProposedBlock("second root", depth = 0, order = 2),
            ),
        )

        val sut = LlmSuggestionWriter(pageRepo, blockRepo, writer)
        val result = sut.materializeAndWrite(suggestion, graphPath)

        assertTrue(result.isRight())
        assertEquals(1, writer.saveCallCount)
        assertEquals("New Synthesized Page", writer.savedPage?.name)
        val savedBlocks = writer.savedBlocks
        assertEquals(3, savedBlocks?.size)
        val root = savedBlocks?.first { it.content == "root block" }
        val child = savedBlocks?.first { it.content == "child block" }
        val secondRoot = savedBlocks?.first { it.content == "second root" }
        assertNull(root?.parentUuid)
        assertEquals(root?.uuid?.value, child?.parentUuid)
        assertNull(secondRoot?.parentUuid)
        assertEquals(0, root?.level)
        assertEquals(1, child?.level)
    }

    @Test
    fun materializeAndWrite_should_ResolveToSameSavePageShape_When_TagChange() = runBlocking {
        val (pageRepo, blockRepo, writer) = setup()
        val pageUuid = "page-1"
        val blockUuid = "block-1"
        pageRepo.savePage(page(pageUuid))
        blockRepo.saveBlock(block(blockUuid, pageUuid, "a note about cooking"))

        val suggestion = PendingLlmSuggestion.TagChange(
            id = "id-1", graphId = "graph-1", sourceProviderId = "anthropic",
            proposedAtEpochMs = 1L, rationale = null,
            pageUuid = pageUuid, blockUuid = blockUuid,
            currentContentSnapshot = "a note about cooking",
            addedTerms = listOf("Recipes"),
            removedTerms = emptyList(),
        )

        val sut = LlmSuggestionWriter(pageRepo, blockRepo, writer)
        val result = sut.materializeAndWrite(suggestion, graphPath)

        assertTrue(result.isRight())
        assertEquals(1, writer.saveCallCount)
        val savedBlock = writer.savedBlocks?.first { it.uuid.value == blockUuid }
        assertTrue(savedBlock?.content?.contains("[[Recipes]]") == true)
    }
}
