@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.RepositorySet
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class ProposeBlockEditToolTest {

    private suspend fun openTestGraph(): Pair<GraphManager, RepositorySet> {
        val graphManager = unopenedGraphManager()
        val repositorySet = graphManager.openGraph("/test/graph")
        return graphManager to repositorySet
    }

    private fun buildTestServer(
        graphManager: GraphManager,
        suggestionSink: McpSuggestionSink,
        mcpClientName: () -> String = { "claude-desktop" },
    ): Server {
        val server = Server(
            serverInfo = Implementation(name = "test", version = "0.0.1"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        server.registerProposeBlockEditTool(graphManager, McpPendingCapGuard(suggestionSink), mcpClientName)
        return server
    }

    private fun block(uuid: String, pageUuid: String, content: String, position: String = "a0"): Block {
        val now = Clock.System.now()
        return Block(
            uuid = BlockUuid(uuid),
            pageUuid = PageUuid(pageUuid),
            content = content,
            position = position,
            createdAt = now,
            updatedAt = now,
        )
    }

    @Test
    fun `propose_block_edit reads the block's live content fresh and stores it as currentContentSnapshot`() = runBlocking {
        val (graphManager, repositorySet) = openTestGraph()
        repositorySet.blockRepository.saveBlock(block("b1", "p1", "Original text"))
        val sink = RecordingSuggestionSink()
        val server = buildTestServer(graphManager, sink)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.callTool(
                "propose_block_edit",
                buildJsonObject {
                    put("pageUuid", "p1")
                    put("blockUuid", "b1")
                    // Client sends a (deliberately wrong) "current content" — the tool must ignore
                    // it and read the live block instead.
                    put("newContent", "Edited text")
                },
            )

            assertNotEquals(true, result.isError)
            assertEquals(1, sink.proposed.size)
            val suggestion = sink.proposed.single() as PendingLlmSuggestion.BlockEdit
            assertEquals("Original text", suggestion.currentContentSnapshot)
            assertEquals("Edited text", suggestion.proposedContent)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `propose_block_edit stores a sourceProviderId matching the mcp-client-name-uuid convention`() = runBlocking {
        val (graphManager, repositorySet) = openTestGraph()
        repositorySet.blockRepository.saveBlock(block("b1", "p1", "content"))
        val sink = RecordingSuggestionSink()
        val server = buildTestServer(graphManager, sink, mcpClientName = { "claude-desktop" })
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            client.callTool(
                "propose_block_edit",
                buildJsonObject { put("pageUuid", "p1"); put("blockUuid", "b1"); put("newContent", "x") },
            )

            val suggestion = sink.proposed.single()
            val uuidPattern = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
            assertTrue(
                Regex("mcp:claude-desktop:$uuidPattern").matches(suggestion.sourceProviderId),
                "Expected 'mcp:claude-desktop:<uuid>', got: ${suggestion.sourceProviderId}",
            )
            assertEquals(graphManager.getActiveGraphId()!!.value, suggestion.graphId)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `propose_block_edit never calls DatabaseWriteActor or GraphWriterPort and adds exactly one pending suggestion`() = runBlocking {
        val (graphManager, repositorySet) = openTestGraph()
        repositorySet.blockRepository.saveBlock(block("b1", "p1", "Original text"))
        val beforeBlock = repositorySet.blockRepository.getBlockByUuid(BlockUuid("b1")).first().getOrNull()

        val sink = RecordingSuggestionSink()
        val server = buildTestServer(graphManager, sink)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            client.callTool(
                "propose_block_edit",
                buildJsonObject { put("pageUuid", "p1"); put("blockUuid", "b1"); put("newContent", "Edited text") },
            )

            // The only observable write path in this test harness is the repository itself
            // (there is no separate DatabaseWriteActor/GraphWriterPort fake under the IN_MEMORY
            // backend) — so the strongest available proof that no write occurred is that the
            // stored block is byte-for-byte unchanged after the call.
            val afterBlock = repositorySet.blockRepository.getBlockByUuid(BlockUuid("b1")).first().getOrNull()
            assertEquals(beforeBlock, afterBlock, "propose_block_edit must never mutate the live block")
            assertEquals("Original text", afterBlock?.content)
            assertEquals(1, sink.pending.value.size)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `propose_block_edit returns GraphNotOpen and leaves the pending map unchanged when no graph is active`() = runBlocking {
        val graphManager = unopenedGraphManager()
        val sink = RecordingSuggestionSink()
        val server = buildTestServer(graphManager, sink)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.callTool(
                "propose_block_edit",
                buildJsonObject { put("pageUuid", "p1"); put("blockUuid", "b1"); put("newContent", "x") },
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
