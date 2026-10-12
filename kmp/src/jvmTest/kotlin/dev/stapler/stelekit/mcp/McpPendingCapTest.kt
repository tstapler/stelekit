// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import arrow.core.Either
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class McpPendingCapTest {

    private fun blockEdit(sourceProviderId: String): PendingLlmSuggestion.BlockEdit =
        PendingLlmSuggestion.BlockEdit(
            id = "id-${sourceProviderId.hashCode()}-${Clock.System.now().toEpochMilliseconds()}-${(0..999_999).random()}",
            graphId = "g1",
            sourceProviderId = sourceProviderId,
            proposedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            rationale = null,
            pageUuid = "p1",
            blockUuid = "b1",
            currentContentSnapshot = "before",
            proposedContent = "after",
        )

    @Test
    fun `proposeIfUnderCap refuses a 21st MCP-sourced suggestion while leaving matcher-sourced entries alone`() = runBlocking {
        val sink = RecordingSuggestionSink()
        repeat(20) { i -> sink.propose(blockEdit("mcp:claude-desktop:seed-$i")) }
        repeat(5) { i -> sink.propose(blockEdit("aho-corasick-matcher").copy(id = "matcher-$i")) }
        assertEquals(25, sink.pending.value.size)

        val guard = McpPendingCapGuard(sink)
        val outcome = guard.proposeIfUnderCap { blockEdit("mcp:claude-desktop:21st") }

        assertIs<Either.Left<McpToolError.InboxFull>>(outcome)
        assertEquals(MCP_PENDING_CAP, outcome.value.cap)
        assertEquals(25, sink.pending.value.size, "neither the refused MCP suggestion nor the matcher's 5 should change")
    }

    @Test
    fun `proposeIfUnderCap is race-free under two concurrent calls at the cap boundary`() = runBlocking {
        val sink = RecordingSuggestionSink()
        repeat(MCP_PENDING_CAP - 1) { i -> sink.propose(blockEdit("mcp:claude-desktop:seed-$i")) }
        assertEquals(MCP_PENDING_CAP - 1, sink.pending.value.size)

        val guard = McpPendingCapGuard(sink)
        val (first, second) = coroutineScope {
            val a = async { guard.proposeIfUnderCap { blockEdit("mcp:claude-desktop:race-a") } }
            val b = async { guard.proposeIfUnderCap { blockEdit("mcp:claude-desktop:race-b") } }
            a.await() to b.await()
        }

        val results = listOf(first, second)
        assertEquals(1, results.count { it.isRight() }, "exactly one of the two concurrent calls must succeed")
        assertEquals(1, results.count { it.isLeft() }, "exactly one of the two concurrent calls must be refused")
        assertEquals(MCP_PENDING_CAP, sink.pending.value.size, "the count must land exactly at the cap, never over it")
    }
}
