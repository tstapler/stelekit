// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import dev.stapler.stelekit.llm.ProposedBlock
import dev.stapler.stelekit.util.UuidGenerator
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlin.time.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Registers the `propose_new_page` MCP tool. Mirrors [registerProposeBlockEditTool]'s shape:
 * builds a [PendingLlmSuggestion.NewPage] from the request and hands it to [suggestionSink] —
 * never touches the DB/disk directly. [McpPendingCapGuard] (Epic 3.2) lands in a later pass.
 */
fun Server.registerProposeNewPageTool(
    graphManager: GraphManager,
    suggestionSink: McpSuggestionSink,
    mcpClientName: () -> String,
) {
    addTimedTool(
        name = "propose_new_page",
        description = "Queues a suggested new page for human review in SteleKit. Does not modify the graph.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "Proposed title of the new page.")
                }
                putJsonObject("blocks") {
                    put("type", "array")
                    put("description", "The page's initial block tree, in document order.")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("content") { put("type", "string") }
                            putJsonObject("depth") { put("type", "integer") }
                            putJsonObject("order") { put("type", "integer") }
                        }
                        putJsonArray("required") {
                            add("content")
                            add("depth")
                            add("order")
                        }
                    }
                }
                putJsonObject("rationale") {
                    put("type", "string")
                    put("description", "Optional explanation of why this page is proposed.")
                }
            },
            required = listOf("title", "blocks"),
        ),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false),
    ) { request ->
        val activeGraphId = graphManager.getActiveGraphId()
        if (activeGraphId == null || graphManager.getActiveRepositorySet() == null) {
            return@addTimedTool McpToolError.GraphNotOpen.toCallToolResult()
        }

        val args = request.arguments ?: JsonObject(emptyMap())
        val title = args.requireString("title")
        val rationale = args["rationale"]?.jsonPrimitive?.contentOrNull
        val blocksArg = args["blocks"]?.jsonArray
            ?: throw IllegalArgumentException("Missing required argument 'blocks'")
        val proposedBlocks = blocksArg.map { element ->
            val block = element.jsonObject
            ProposedBlock(
                content = block.requireString("content"),
                depth = block["depth"]?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("Missing required argument 'depth'"),
                order = block["order"]?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("Missing required argument 'order'"),
            )
        }

        suggestionSink.propose(
            PendingLlmSuggestion.NewPage(
                id = UuidGenerator.generateV7(),
                graphId = activeGraphId.value,
                sourceProviderId = "mcp:${mcpClientName()}:${UuidGenerator.generateV7()}",
                proposedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                rationale = rationale,
                proposedTitle = title,
                proposedBlocks = proposedBlocks,
            ),
        )

        successResult("Queued a proposed new page '$title'. The user will review it in SteleKit.")
    }
}
