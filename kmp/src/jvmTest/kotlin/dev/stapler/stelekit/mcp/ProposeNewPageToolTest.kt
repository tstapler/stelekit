// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProposeNewPageToolTest {

    private fun buildTestServer(
        graphManager: GraphManager,
        suggestionSink: McpSuggestionSink,
        mcpClientName: () -> String = { "claude-desktop" },
    ): Server {
        val server = Server(
            serverInfo = Implementation(name = "test", version = "0.0.1"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        server.registerProposeNewPageTool(graphManager, suggestionSink, mcpClientName)
        return server
    }

    @Test
    fun `propose_new_page stores a NewPage suggestion with the proposed title and every proposed block`() = runBlocking {
        val graphManager = unopenedGraphManager()
        graphManager.openGraph("/test/graph")
        val sink = RecordingSuggestionSink()
        val server = buildTestServer(graphManager, sink)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.callTool(
                "propose_new_page",
                buildJsonObject {
                    put("title", "Research: X")
                    putJsonArray("blocks") {
                        addJsonObject { put("content", "Summary"); put("depth", 0); put("order", 0) }
                        addJsonObject { put("content", "Detail"); put("depth", 1); put("order", 1) }
                    }
                    put("rationale", "captured from conversation")
                },
            )

            assertNotEquals(true, result.isError)
            assertEquals(1, sink.proposed.size)
            val suggestion = sink.proposed.single() as PendingLlmSuggestion.NewPage
            assertEquals("Research: X", suggestion.proposedTitle)
            assertEquals(2, suggestion.proposedBlocks.size)
            assertEquals("Summary", suggestion.proposedBlocks[0].content)
            assertEquals(0, suggestion.proposedBlocks[0].depth)
            assertEquals(0, suggestion.proposedBlocks[0].order)
            assertEquals("Detail", suggestion.proposedBlocks[1].content)
            assertEquals(1, suggestion.proposedBlocks[1].depth)
            assertEquals(1, suggestion.proposedBlocks[1].order)
            assertEquals("captured from conversation", suggestion.rationale)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `propose_new_page returns GraphNotOpen and leaves the pending map unchanged when no graph is active`() = runBlocking {
        val graphManager = unopenedGraphManager()
        val sink = RecordingSuggestionSink()
        val server = buildTestServer(graphManager, sink)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.callTool(
                "propose_new_page",
                buildJsonObject {
                    put("title", "Orphan page")
                    putJsonArray("blocks") {
                        addJsonObject { put("content", "x"); put("depth", 0); put("order", 0) }
                    }
                },
            )

            assertEquals(true, result.isError)
            assertEquals(McpToolError.GraphNotOpen.toCallToolResult().firstText(), result.firstText())
            assertEquals(0, sink.pending.value.size)
        } finally {
            client.close()
            server.close()
        }
    }
}
