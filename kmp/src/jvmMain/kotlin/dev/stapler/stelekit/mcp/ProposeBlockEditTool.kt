// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.util.UuidGenerator
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Registers the `propose_block_edit` MCP tool. Reads the block's live content via the normal
 * repository read path and hands a [PendingLlmSuggestion.BlockEdit] to [pendingCapGuard] —
 * never writes to the DB/disk directly, and never calls [McpSuggestionSink.propose] directly
 * (the guard's `Mutex` is what keeps the per-source cap race-free, Epic 3.2).
 */
fun Server.registerProposeBlockEditTool(
    graphManager: GraphManager,
    pendingCapGuard: McpPendingCapGuard,
    mcpClientName: () -> String,
) {
    addTimedTool(
        name = "propose_block_edit",
        description = "Queues a suggested edit for human review in SteleKit. Does not modify the graph.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("pageUuid") {
                    put("type", "string")
                    put("description", "UUID of the page that contains the block.")
                }
                putJsonObject("blockUuid") {
                    put("type", "string")
                    put("description", "UUID of the block to propose an edit for.")
                }
                putJsonObject("newContent") {
                    put("type", "string")
                    put("description", "The proposed replacement content for the block.")
                }
                putJsonObject("rationale") {
                    put("type", "string")
                    put("description", "Optional explanation of why this edit is proposed.")
                }
            },
            required = listOf("pageUuid", "blockUuid", "newContent"),
        ),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false),
    ) { request ->
        val repositorySet = graphManager.getActiveRepositorySet()
        val activeGraphId = graphManager.getActiveGraphId()
        if (repositorySet == null || activeGraphId == null) {
            return@addTimedTool McpToolError.GraphNotOpen.toCallToolResult()
        }

        val args = request.arguments ?: JsonObject(emptyMap())
        val pageUuid = args.requireString("pageUuid")
        val blockUuid = args.requireString("blockUuid")
        val newContent = args.requireString("newContent")
        val rationale = args["rationale"]?.jsonPrimitive?.contentOrNull

        val blockResult = repositorySet.blockRepository.getBlockByUuid(BlockUuid(blockUuid)).first()
        val liveBlock = blockResult.fold(
            { error -> return@addTimedTool McpToolError.Internal(error).toCallToolResult() },
            { block ->
                block ?: return@addTimedTool McpToolError.Internal(
                    DomainError.DatabaseError.NotFound("Block", blockUuid),
                ).toCallToolResult()
            },
        )

        if (liveBlock.pageUuid.value != pageUuid) {
            return@addTimedTool CallToolResult(
                content = listOf(TextContent("Block $blockUuid does not belong to page $pageUuid.")),
                isError = true,
            )
        }

        val outcome = pendingCapGuard.proposeIfUnderCap {
            PendingLlmSuggestion.BlockEdit(
                id = UuidGenerator.generateV7(),
                graphId = activeGraphId.value,
                sourceProviderId = "mcp:${mcpClientName()}:${UuidGenerator.generateV7()}",
                proposedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                rationale = rationale,
                pageUuid = pageUuid,
                blockUuid = blockUuid,
                currentContentSnapshot = liveBlock.content,
                proposedContent = newContent,
            )
        }
        outcome.fold(
            { cap -> return@addTimedTool cap.toCallToolResult() },
            { successResult("Queued a proposed edit for block $blockUuid. The user will review it in SteleKit.") },
        )
    }
}

/** Shared by both propose tools — same package, so no third file is needed for two call sites. */
internal fun JsonObject.requireString(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull
        ?: throw McpInvalidArgumentException("Missing required argument '$key'")

/** Non-error counterpart to [McpToolError.toCallToolResult] — one text block, `isError = false`. */
internal fun successResult(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = false)
