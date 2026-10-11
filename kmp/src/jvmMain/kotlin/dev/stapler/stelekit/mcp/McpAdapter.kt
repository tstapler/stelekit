// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.performance.BuildInfo
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/**
 * Wires [GraphManager] and [McpSuggestionSink] into an MCP SDK [Server]. Tools and resources are
 * registered into [buildServer] by later stories; [mcpClientName] is read-only here and is
 * resolved per connection by `McpClientIdentity` (see its doc for why it can't come from a handler).
 */
class McpAdapter(
    private val graphManager: GraphManager,
    private val suggestionSink: McpSuggestionSink,
    private val mcpClientName: () -> String,
) {
    fun buildServer(): Server = Server(
        serverInfo = Implementation(name = "stelekit", version = BuildInfo.appVersion),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
            ),
        ),
    )
}
