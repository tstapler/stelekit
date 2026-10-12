// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpToolTimeoutTest {

    private fun newServer() = McpAdapter(unopenedGraphManager(), RecordingSuggestionSink(), { "test-client" }).buildServer()

    @Test
    fun `addTimedTool returns the handler result unchanged when it completes before the timeout`() = runBlocking {
        val server = newServer()
        server.addTimedTool("fast_tool", "returns immediately", timeoutMs = 5_000L) {
            CallToolResult(content = listOf(TextContent("pong")))
        }
        val client = McpTestClient.connect(server)
        try {
            client.initialize()

            val result = client.callTool("fast_tool")

            assertNotEquals(true, result.isError)
            assertEquals("pong", result.firstText())
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `addTimedTool maps a TimeoutCancellationException to McpToolError_Timeout within the configured timeoutMs`() = runBlocking {
        val server = newServer()
        server.addTimedTool("slow_tool", "never finishes in time", timeoutMs = 100L) {
            delay(5_000L)
            CallToolResult(content = listOf(TextContent("too late")))
        }
        val client = McpTestClient.connect(server)
        try {
            client.initialize()

            lateinit var result: CallToolResult
            val elapsedMs = measureTimeMillis { result = client.callTool("slow_tool") }

            assertTrue(elapsedMs < 3_000L, "call should return near the 100ms timeout, took ${elapsedMs}ms")
            assertEquals(true, result.isError)
            assertEquals(McpToolError.Timeout("slow_tool", 100L).toCallToolResult().firstText(), result.firstText())
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `addTimedTool maps an unhandled IllegalStateException to McpToolError_UnexpectedFailure without leaking the raw message`() = runBlocking {
        val server = newServer()
        server.addTimedTool("buggy_tool", "always throws") {
            throw IllegalStateException("boom")
        }
        val client = McpTestClient.connect(server)
        try {
            client.initialize()

            val result = client.callTool("buggy_tool")

            assertEquals(true, result.isError)
            val text = result.firstText()
            assertTrue("buggy_tool" in text)
            assertTrue("IllegalStateException" in text)
            assertFalse("boom" in text, "raw exception message must not reach the MCP client: $text")
        } finally {
            client.close()
            server.close()
        }
    }
}
