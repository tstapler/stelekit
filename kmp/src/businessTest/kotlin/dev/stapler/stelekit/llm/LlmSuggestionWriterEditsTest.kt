// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.llm

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphWriterPort
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.SectionId
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.vault.CryptoLayer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock

/** The text each suggestion kind writes into the block, and the write guards, beyond `LlmSuggestionWriterTest`. */
class LlmSuggestionWriterEditsTest {
    private class SpyWriter : GraphWriterPort {
        var savedBlocks: List<Block>? = null
        var saves = 0
        override fun setCryptoLayer(layer: CryptoLayer?) {}
        override fun closeAndClearCryptoLayer() {}
        override fun startAutoSave(debounceMs: Long) {}
        override fun stopAutoSave() {}
        override suspend fun flush() {}
        override suspend fun renamePage(page: Page, newName: String, graphPath: EffectiveNotesPath) = true
        override suspend fun savePage(page: Page, blocks: List<Block>, graphPath: EffectiveNotesPath): Either<DomainError, Unit> {
            saves++
            savedBlocks = blocks
            return Unit.right()
        }
        override suspend fun deletePage(page: Page) = true
        override suspend fun movePageToSection(page: Page, newSectionId: SectionId, newPathPrefix: String): Either<DomainError, Page> =
            page.copy(sectionId = newSectionId).right()
    }

    private val now = Clock.System.now()
    private val graphPath = EffectiveNotesPath("/tmp/graph")

    private fun block(uuid: String, content: String, position: String = "a0") = Block(
        uuid = BlockUuid(uuid), pageUuid = PageUuid("p"), content = content, position = position, createdAt = now, updatedAt = now,
    )

    private class Outcome(val result: Either<DomainError, Unit>, val writer: SpyWriter) {
        fun content(uuid: String) = writer.savedBlocks?.single { it.uuid.value == uuid }?.content
    }

    private fun write(
        content: String,
        siblings: List<Block> = emptyList(),
        build: (snapshot: String) -> PendingLlmSuggestion,
    ): Outcome = runBlocking {
        val pages = InMemoryPageRepository().also { it.savePage(Page(uuid = PageUuid("p"), name = "P", createdAt = now, updatedAt = now)) }
        val blocks = InMemoryBlockRepository().also { repo ->
            repo.saveBlock(block("b", content))
            siblings.forEach { repo.saveBlock(it) }
        }
        val writer = SpyWriter()
        Outcome(LlmSuggestionWriter(pages, blocks, writer).materializeAndWrite(build(content), graphPath), writer)
    }

    private fun tagChange(snapshot: String, added: List<String>, removed: List<String>) = PendingLlmSuggestion.TagChange(
        id = "i", graphId = "g", sourceProviderId = "s", proposedAtEpochMs = 1L, rationale = null,
        pageUuid = "p", blockUuid = "b", currentContentSnapshot = snapshot, addedTerms = added, removedTerms = removed,
    )

    private fun unlinked(snapshot: String, target: String, start: Int, end: Int) = PendingLlmSuggestion.UnlinkedReference(
        id = "i", graphId = "g", sourceProviderId = "s", proposedAtEpochMs = 1L, rationale = null,
        pageUuid = "p", blockUuid = "b", targetPageName = target, matchStart = start, matchEnd = end, currentContentSnapshot = snapshot,
    )

    @Test
    fun tagChange_removesALinkAndCollapsesTheGapItLeaves() {
        val out = write("cooking [[Old]] tips") { tagChange(it, added = emptyList(), removed = listOf("Old")) }

        assertEquals("cooking tips", out.content("b"))
    }

    @Test
    fun tagChange_doesNotDuplicateAnExistingLink() {
        val out = write("note [[Kotlin]]") { tagChange(it, added = listOf("Kotlin", "Coroutines"), removed = emptyList()) }

        assertEquals("note [[Kotlin]] [[Coroutines]]", out.content("b"))
    }

    @Test
    fun tagChange_onAnEmptiedBlockWritesJustTheNewLinks() {
        val out = write("[[Old]]") { tagChange(it, added = listOf("A", "B"), removed = listOf("Old")) }

        assertEquals("[[A]] [[B]]", out.content("b"))
    }

    @Test
    fun unlinkedReference_wrapsExactlyTheMatchedSpan() {
        val out = write("I like kotlin a lot") { unlinked(it, target = "Kotlin", start = 7, end = 13) }

        assertEquals("I like [[Kotlin]] a lot", out.content("b"))
    }

    @Test
    fun unlinkedReference_clampsAnOutOfRangeMatchInsteadOfThrowing() {
        val out = write("short") { unlinked(it, target = "Short", start = -4, end = 99) }

        assertEquals("[[Short]]", out.content("b"))
    }

    @Test
    fun siblingBlocksOfTheEditedBlockAreWrittenUnchanged() {
        val out = write("old", siblings = listOf(block("other", "untouched", position = "a1"))) {
            PendingLlmSuggestion.BlockEdit(
                id = "i", graphId = "g", sourceProviderId = "s", proposedAtEpochMs = 1L, rationale = null,
                pageUuid = "p", blockUuid = "b", currentContentSnapshot = it, proposedContent = "new",
            )
        }

        assertEquals("new", out.content("b"))
        assertEquals("untouched", out.content("other"))
    }

    @Test
    fun aStaleSnapshotIsRejectedForEveryEditKindAndNothingIsSaved() {
        val staleTag = write("current text") { tagChange("older text", listOf("A"), emptyList()) }
        val staleLink = write("current text") { unlinked("older text", "Text", 0, 4) }

        for (out in listOf(staleTag, staleLink)) {
            assertIs<DomainError.ConflictError.ConcurrentWrite>(out.result.leftOrNull())
            assertEquals(0, out.writer.saves)
        }
    }

    @Test
    fun anEditTargetingAMissingPageIsReportedNotFound() = runBlocking {
        val blocks = InMemoryBlockRepository().also { it.saveBlock(block("b", "text")) }
        val writer = SpyWriter()

        val result = LlmSuggestionWriter(InMemoryPageRepository(), blocks, writer)
            .materializeAndWrite(tagChange("text", listOf("A"), emptyList()), graphPath)

        assertIs<DomainError.DatabaseError.NotFound>(result.leftOrNull())
        assertEquals(0, writer.saves)
    }
}
