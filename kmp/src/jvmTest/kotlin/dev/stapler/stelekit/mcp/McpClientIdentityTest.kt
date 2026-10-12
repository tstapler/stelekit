// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class McpClientIdentityTest {

    @Test
    fun `McpClientIdentity resolve returns unknown-mcp-client before any session initializes`() {
        assertEquals("unknown-mcp-client", McpClientIdentity().resolve())
    }

    @Test
    fun `McpClientIdentity resolve returns the negotiated client name after onSessionInitialized fires`() = runBlocking {
        val server = McpAdapter(unopenedGraphManager(), RecordingSuggestionSink()) { "unused" }.buildServer()
        val client = McpTestClient.connect(server)
        try {
            client.initialize(clientName = "claude-desktop", clientVersion = "1.0")
            val identity = McpClientIdentity()

            identity.onSessionInitialized(client.session)

            assertEquals("claude-desktop", identity.resolve())
        } finally {
            client.close()
            server.close()
        }
    }
}
