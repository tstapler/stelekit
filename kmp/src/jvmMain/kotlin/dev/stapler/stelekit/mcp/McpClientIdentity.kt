// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import java.util.concurrent.atomic.AtomicReference

internal const val UNKNOWN_MCP_CLIENT = "unknown-mcp-client"

/**
 * Holds the connected MCP client's name for provenance strings. The negotiated client
 * `Implementation` is reachable only on [ServerSession] (returned by `Server.createSession`),
 * never from inside a tool handler, so the socket accept loop captures it once per connection.
 */
class McpClientIdentity {
    private val name = AtomicReference(UNKNOWN_MCP_CLIENT)

    fun resolve(): String = name.get()

    internal fun onSessionInitialized(session: ServerSession) {
        name.set(session.clientVersion?.name ?: UNKNOWN_MCP_CLIENT)
    }
}
