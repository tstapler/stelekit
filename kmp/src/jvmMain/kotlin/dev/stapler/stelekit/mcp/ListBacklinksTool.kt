// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import arrow.core.Either
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.model.Block
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Default/ceiling for [registerListBacklinksTool]'s `limit` — per plan.md Task 2.2.2a, clamped to <=50. */
private const val DEFAULT_BACKLINKS_LIMIT = 50
private const val MAX_BACKLINKS_LIMIT = 50

private const val KEY_UUID = "uuid"
private const val KEY_NAME = "name"
private const val KEY_LIMIT = "limit"
private const val KEY_OFFSET = "offset"

private val listBacklinksInputSchema = ToolSchema(
    properties = buildJsonObject {
        putJsonObject(KEY_UUID) {
            put("type", "string")
            put("description", "UUID of the page to find backlinks for. Mutually exclusive with name.")
        }
        putJsonObject(KEY_NAME) {
            put("type", "string")
            put(
                "description",
                "Name of the page to find backlinks for, matched as the literal [[wiki-link]] target " +
                    "text. Mutually exclusive with uuid.",
            )
        }
        putJsonObject(KEY_LIMIT) {
            put("type", "integer")
            put("description", "Maximum number of backlinks to return (default $DEFAULT_BACKLINKS_LIMIT, max $MAX_BACKLINKS_LIMIT).")
        }
        putJsonObject(KEY_OFFSET) {
            put("type", "integer")
            put("description", "Number of backlinks to skip before the returned page (default 0).")
        }
    },
)

/**
 * `list_backlinks` — list blocks that reference a page via `[[wiki-link]]` references.
 *
 * These are semantic wiki-link references, not child blocks or outline structure — a block that is
 * merely nested under the target page in the outline is not a backlink. Registered with
 * [Server.addTimedTool] (never the SDK's raw `addTool`).
 */
fun Server.registerListBacklinksTool(graphManager: GraphManager) {
    addTimedTool(
        name = "list_backlinks",
        description = "List blocks that reference a page via [[wiki-link]] references — not child " +
            "blocks or outline structure. Takes a page uuid or name. Read-only.",
        inputSchema = listBacklinksInputSchema,
        annotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false),
    ) { request ->
        val args = request.arguments
        val uuid = args.stringOrNull(KEY_UUID)
        val name = args.stringOrNull(KEY_NAME)

        val providedCount = listOf(uuid, name).count { it != null }
        if (providedCount != 1) {
            return@addTimedTool CallToolResult(
                content = listOf(TextContent("list_backlinks requires exactly one of uuid or name; received $providedCount.")),
                isError = true,
            )
        }

        val limit = (args.intOrNull(KEY_LIMIT) ?: DEFAULT_BACKLINKS_LIMIT).coerceIn(1, MAX_BACKLINKS_LIMIT)
        val offset = (args.intOrNull(KEY_OFFSET) ?: 0).coerceAtLeast(0)

        buildListBacklinksResult(graphManager, uuid, name, limit, offset)
    }
}

private suspend fun buildListBacklinksResult(
    graphManager: GraphManager,
    uuid: String?,
    name: String?,
    limit: Int,
    offset: Int,
): CallToolResult {
    val repositorySet = graphManager.getActiveRepositorySet()
        ?: return McpToolError.GraphNotOpen.toCallToolResult()

    // list_backlinks searches by literal page-name text (wiki-links reference names, not uuids), so
    // a `name` lookup is forwarded as-is — only a `uuid` lookup needs resolvePage (shared with
    // read_page/Epic 2.3) to turn the uuid into the name getLinkedReferences expects.
    val pageName = name ?: when (val resolved = resolvePage(graphManager, uuid = uuid)) {
        is Either.Left -> return resolved.value.toCallToolResult()
        is Either.Right -> resolved.value?.name
            ?: return CallToolResult(content = listOf(TextContent("No page found for uuid '$uuid'.")), isError = true)
    }

    return when (val result = repositorySet.blockRepository.getLinkedReferences(pageName, limit, offset).first()) {
        is Either.Left -> McpToolError.Internal(result.value).toCallToolResult()
        is Either.Right -> CallToolResult(
            content = listOf(TextContent(renderBacklinksResult(pageName, result.value, limit, offset).toString())),
        )
    }
}

private fun renderBacklinksResult(pageName: String, blocks: List<Block>, limit: Int, offset: Int): JsonObject =
    buildJsonObject {
        put("pageName", pageName)
        putJsonArray("backlinks") {
            blocks.forEach { block ->
                addJsonObject {
                    put("blockUuid", block.uuid.value)
                    put("pageUuid", block.pageUuid.value)
                    put("content", block.content)
                }
            }
        }
        put("limit", limit)
        put("offset", offset)
        put("count", blocks.size)
    }
