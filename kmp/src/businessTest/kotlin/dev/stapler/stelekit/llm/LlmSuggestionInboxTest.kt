package dev.stapler.stelekit.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmSuggestionInboxTest {

    private fun blockEdit(id: String, graphId: String, content: String = "proposed") =
        PendingLlmSuggestion.BlockEdit(
            id = id,
            graphId = graphId,
            sourceProviderId = "anthropic",
            proposedAtEpochMs = 1L,
            rationale = null,
            pageUuid = "page-1",
            blockUuid = "block-1",
            currentContentSnapshot = "original",
            proposedContent = content,
        )

    @Test
    fun propose_should_AddToPending() {
        val inbox = LlmSuggestionInbox()
        val suggestion = blockEdit("id-1", "graph-1")

        inbox.propose(suggestion)

        assertEquals(mapOf("id-1" to suggestion), inbox.pending.value)
    }

    @Test
    fun remove_should_RemoveFromPending() {
        val inbox = LlmSuggestionInbox()
        val suggestion = blockEdit("id-1", "graph-1")
        inbox.propose(suggestion)

        inbox.remove("id-1")

        assertTrue(inbox.pending.value.isEmpty())
    }

    @Test
    fun pendingForGraph_should_FilterCorrectly_AcrossTwoDifferentGraphIds() {
        val inbox = LlmSuggestionInbox()
        val suggestionA = blockEdit("id-a", "graph-a")
        val suggestionB = blockEdit("id-b", "graph-b")
        inbox.propose(suggestionA)
        inbox.propose(suggestionB)

        assertEquals(listOf(suggestionA), inbox.pendingForGraph("graph-a"))
        assertEquals(listOf(suggestionB), inbox.pendingForGraph("graph-b"))
        assertTrue(inbox.pendingForGraph("graph-c").isEmpty())
    }

    @Test
    fun propose_should_Overwrite_When_DuplicateIdProposed() {
        val inbox = LlmSuggestionInbox()
        inbox.propose(blockEdit("id-1", "graph-1", content = "first"))
        inbox.propose(blockEdit("id-1", "graph-1", content = "second"))

        assertEquals(1, inbox.pending.value.size)
        val stored = inbox.pending.value.getValue("id-1") as PendingLlmSuggestion.BlockEdit
        assertEquals("second", stored.proposedContent)
    }
}
