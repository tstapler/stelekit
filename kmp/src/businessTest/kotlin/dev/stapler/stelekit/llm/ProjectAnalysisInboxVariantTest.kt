// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.llm

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.GraphWriterPort
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.SectionId
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.vault.CryptoLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Story 5.6b-2: accepting a project answer is "dismiss": no graph write of any kind. */
class ProjectAnalysisInboxVariantTest {
    private class CountingWriter : GraphWriterPort {
        var calls = 0
        override fun setCryptoLayer(layer: CryptoLayer?) { calls++ }
        override fun closeAndClearCryptoLayer() { calls++ }
        override fun startAutoSave(debounceMs: Long) { calls++ }
        override fun stopAutoSave() { calls++ }
        override suspend fun flush() { calls++ }
        override suspend fun renamePage(page: Page, newName: String, graphPath: EffectiveNotesPath): Boolean { calls++; return true }
        override suspend fun savePage(page: Page, blocks: List<Block>, graphPath: EffectiveNotesPath): Either<DomainError, Unit> {
            calls++
            return DomainError.DatabaseError.WriteFailed("must not write").left()
        }
        override suspend fun deletePage(page: Page): Boolean { calls++; return true }
        override suspend fun movePageToSection(page: Page, newSectionId: SectionId, newPathPrefix: String): Either<DomainError, Page> {
            calls++
            return page.right()
        }
    }

    private val answer = PendingLlmSuggestion.ProjectAnalysisAnswer(
        id = "a1", graphId = "g1", sourceProviderId = "anthropic", proposedAtEpochMs = 1L,
        rationale = null, projectSlug = "proj-abc123", text = "Check the shelf zone.",
    )

    @Test
    fun acceptingTheAnswerWritesNothingAndLeavesTheRepositoriesUntouched() = runBlocking {
        val pages = InMemoryPageRepository()
        val blocks = InMemoryBlockRepository()
        val writer = CountingWriter()

        val result = LlmSuggestionWriter(pages, blocks, writer).materializeAndWrite(answer, EffectiveNotesPath("/tmp/graph"))

        assertIs<Either.Right<Unit>>(result)
        assertEquals(0, writer.calls, "no GraphWriter call of any kind")
        assertTrue(pages.getPages(100, 0).first().getOrNull().orEmpty().isEmpty())
    }

    @Test
    fun theInboxHoldsTheAnswerUntilItIsRemoved() {
        val inbox = LlmSuggestionInbox()
        inbox.propose(answer)
        assertEquals(listOf<PendingLlmSuggestion>(answer), inbox.pendingForGraph("g1"))
        inbox.remove("a1")
        assertTrue(inbox.pendingForGraph("g1").isEmpty())
    }
}
