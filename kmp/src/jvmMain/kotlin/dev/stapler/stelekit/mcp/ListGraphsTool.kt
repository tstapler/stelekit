// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Builds one `{id, name, path, isActive}` entry for a single graph. */
// The tool lists registry paths.
@OptIn(dev.stapler.stelekit.model.RegistryRootPath::class)
private fun GraphInfo.toJson(activeGraphId: GraphId?) = buildJsonObject {
    put("id", JsonPrimitive(id.value))
    put("name", JsonPrimitive(displayName))
    put("path", JsonPrimitive(path))
    put("isActive", JsonPrimitive(id == activeGraphId))
}

private fun GraphRegistry.toToolResult(): CallToolResult {
    val json = buildJsonArray { graphs.forEach { add(it.toJson(activeGraphId)) } }
    return CallToolResult(content = listOf(TextContent(json.toString())))
}

/**
 * Registers `list_graphs`: every graph SteleKit currently knows about, with a flag marking which
 * one is active, so a multi-graph MCP client can disambiguate before calling other tools. No
 * arguments, no DB access — the registry is in-memory state. See plan.md Story 2.1.2.
 */
fun Server.registerListGraphsTool(graphManager: GraphManager) {
    addTimedTool(
        name = "list_graphs",
        description = "List every graph SteleKit knows about and which one is currently active. Read-only.",
        inputSchema = ToolSchema(),
        annotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false),
    ) { _ ->
        graphManager.graphRegistry.value.toToolResult()
    }
}
