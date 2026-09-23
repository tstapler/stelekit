package dev.stapler.stelekit.llm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingLlmSuggestionTest {

    private val blockEdit = PendingLlmSuggestion.BlockEdit(
        id = "01234567-0000-7000-8000-000000000001",
        graphId = "graph-a",
        sourceProviderId = "anthropic",
        proposedAtEpochMs = 1L,
        rationale = "tidy wording",
        pageUuid = "page-uuid-1",
        blockUuid = "block-uuid-1",
        currentContentSnapshot = "old content",
        proposedContent = "new content",
    )

    private val tagChange = PendingLlmSuggestion.TagChange(
        id = "01234567-0000-7000-8000-000000000002",
        graphId = "graph-b",
        sourceProviderId = "openai",
        proposedAtEpochMs = 2L,
        rationale = null,
        pageUuid = "page-uuid-2",
        blockUuid = "block-uuid-2",
        currentContentSnapshot = "some text",
        addedTerms = listOf("[[Foo]]"),
        removedTerms = emptyList(),
    )

    private val newPage = PendingLlmSuggestion.NewPage(
        id = "01234567-0000-7000-8000-000000000003",
        graphId = "graph-c",
        sourceProviderId = "gemini",
        proposedAtEpochMs = 3L,
        rationale = "synthesized summary",
        proposedTitle = "Synthesized Note",
        proposedBlocks = listOf(ProposedBlock("hello", depth = 0, order = 0)),
    )

    private val unlinkedRef = PendingLlmSuggestion.UnlinkedReference(
        id = "unlinked::block-uuid-4::Kotlin::5",
        graphId = "graph-d",
        sourceProviderId = "aho-corasick-matcher",
        proposedAtEpochMs = 4L,
        rationale = null,
        pageUuid = "page-uuid-4",
        blockUuid = "block-uuid-4",
        targetPageName = "Kotlin",
        matchStart = 5,
        matchEnd = 11,
        currentContentSnapshot = "Using Kotlin coroutines",
    )

    /**
     * Exhaustiveness test: a `when` over all three sealed variants asserts each has a
     * non-blank `id`/`graphId` — guarantees no future variant can be added to the sealed
     * interface without carrying these two mandatory fields.
     */
    @Test
    fun allVariants_should_HaveNonBlankIdAndGraphId_ViaExhaustiveWhen() {
        val variants: List<PendingLlmSuggestion> = listOf(blockEdit, tagChange, newPage, unlinkedRef)
        for (variant in variants) {
            @Suppress("UNUSED_EXPRESSION")
            when (variant) {
                is PendingLlmSuggestion.BlockEdit -> Unit
                is PendingLlmSuggestion.TagChange -> Unit
                is PendingLlmSuggestion.NewPage -> Unit
                is PendingLlmSuggestion.UnlinkedReference -> Unit
            }
            assertFalse(variant.id.isBlank(), "id must not be blank for ${variant::class.simpleName}")
            assertFalse(variant.graphId.isBlank(), "graphId must not be blank for ${variant::class.simpleName}")
        }
    }

    @Test
    fun blockEdit_should_CarrySnapshotAndProposedContentSeparately() {
        assertTrue(blockEdit.currentContentSnapshot != blockEdit.proposedContent)
    }

    @Test
    fun equality_should_HoldForIdenticalDataClassInstances() {
        val copy = blockEdit.copy()
        assertTrue(copy == blockEdit)
    }
}
