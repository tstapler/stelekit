// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.repository.RankedSearchHit
import dev.stapler.stelekit.repository.SearchRepository
import dev.stapler.stelekit.repository.SearchRequest
import dev.stapler.stelekit.repository.SearchResult
import dev.stapler.stelekit.repository.SearchScope
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.last
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Hard ceiling enforced server-side regardless of what the client asks for (CLAUDE.md bounded-read rule). */
internal const val SEARCH_GRAPH_MAX_LIMIT = 50
private const val SEARCH_GRAPH_DEFAULT_LIMIT = 20

/** Clamps a client-requested limit to [SEARCH_GRAPH_MAX_LIMIT]; an unclamped limit must never reach the repository. */
internal fun clampSearchGraphLimit(requested: Int?): Int = (requested ?: SEARCH_GRAPH_DEFAULT_LIMIT).coerceIn(1, SEARCH_GRAPH_MAX_LIMIT)

/**
 * Core `search_graph` logic, independent of how the [SearchRepository] was obtained — lets unit
 * tests exercise it against a fake repository without needing a real [GraphManager] graph.
 */
internal suspend fun executeSearchGraph(
    searchRepository: SearchRepository,
    query: String,
    scope: String?,
    limit: Int?,
    offset: Int?,
): CallToolResult {
    val request = SearchRequest(
        query = query,
        scope = runCatching { SearchScope.valueOf(scope ?: SearchScope.ALL.name) }.getOrDefault(SearchScope.ALL),
        limit = clampSearchGraphLimit(limit),
        offset = (offset ?: 0).coerceAtLeast(0),
    )
    // .last(): searchWithFilters may emit an interim page-only result before the final ranked
    // one (see SqlDelightSearchRepository) — the final emission is always the complete answer.
    return searchRepository.searchWithFilters(request).last().fold(
        { error -> McpToolError.Internal(error).toCallToolResult() },
        { result -> result.toToolResult() },
    )
}

private fun SearchResult.toToolResult(): CallToolResult {
    // Fall back to the flat pages/blocks lists for repositories (e.g. the IN_MEMORY test backend)
    // that don't populate the cross-type `ranked` projection.
    val hits = ranked.ifEmpty {
        pages.map { RankedSearchHit.PageHit(it, null, 0.0) } + blocks.map { RankedSearchHit.BlockHit(it, null, 0.0) }
    }
    val json = buildJsonObject {
        put("ranked", buildJsonArray { hits.forEach { add(it.toJson()) } })
        put("totalCount", JsonPrimitive(totalCount))
        put("hasMore", JsonPrimitive(hasMore))
    }
    return CallToolResult(content = listOf(TextContent(json.toString())))
}

private const val JSON_SCHEMA_TYPE_KEY = "type"
private const val JSON_SCHEMA_DESCRIPTION_KEY = "description"
private const val ARG_QUERY = "query"
private const val ARG_SCOPE = "scope"
private const val ARG_LIMIT = "limit"
private const val ARG_OFFSET = "offset"

private fun RankedSearchHit.toJson() = when (this) {
    is RankedSearchHit.PageHit -> buildJsonObject {
        put(JSON_SCHEMA_TYPE_KEY, JsonPrimitive("page"))
        put("uuid", JsonPrimitive(page.uuid.value))
        put("name", JsonPrimitive(page.name))
        snippet?.let { put("snippet", JsonPrimitive(it)) }
        put("score", JsonPrimitive(score))
    }
    is RankedSearchHit.BlockHit -> buildJsonObject {
        put(JSON_SCHEMA_TYPE_KEY, JsonPrimitive("block"))
        put("uuid", JsonPrimitive(block.uuid.value))
        put("pageUuid", JsonPrimitive(block.pageUuid.value))
        put("snippet", JsonPrimitive(snippet ?: block.content))
        put("score", JsonPrimitive(score))
    }
}

/** Builds one `{type, description}` JSON Schema property entry. */
private fun schemaProperty(type: String, description: String) = buildJsonObject {
    put(JSON_SCHEMA_TYPE_KEY, JsonPrimitive(type))
    put(JSON_SCHEMA_DESCRIPTION_KEY, JsonPrimitive(description))
}

private fun searchGraphInputSchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        put(ARG_QUERY, schemaProperty("string", "Text to search for across page titles and block content."))
        put(
            ARG_SCOPE,
            schemaProperty(
                "string",
                "One of ALL, PAGES_ONLY, BLOCKS_ONLY, CURRENT_PAGE, JOURNAL, FAVORITES. Defaults to ALL.",
            ),
        )
        put(ARG_LIMIT, schemaProperty("integer", "Maximum number of hits to return. Clamped to $SEARCH_GRAPH_MAX_LIMIT."))
        put(ARG_OFFSET, schemaProperty("integer", "Number of hits to skip, for pagination. Defaults to 0."))
    },
    required = listOf(ARG_QUERY),
)

/**
 * Registers `search_graph`: full-text search over the active graph's pages and blocks. Read-only.
 * See plan.md Story 2.1.1.
 */
fun Server.registerSearchGraphTool(graphManager: GraphManager) {
    addTimedTool(
        name = "search_graph",
        description = "Search pages and blocks in the currently open SteleKit graph by text. Read-only.",
        inputSchema = searchGraphInputSchema(),
        annotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false),
    ) { request ->
        val repositorySet = graphManager.getActiveRepositorySet()
            ?: return@addTimedTool McpToolError.GraphNotOpen.toCallToolResult()

        val arguments = request.arguments
        val query = arguments?.get(ARG_QUERY)?.jsonPrimitive?.contentOrNull
        if (query.isNullOrEmpty()) {
            return@addTimedTool CallToolResult(
                content = listOf(TextContent("Missing required argument 'query'.")),
                isError = true,
            )
        }

        executeSearchGraph(
            searchRepository = repositorySet.searchRepository,
            query = query,
            scope = arguments[ARG_SCOPE]?.jsonPrimitive?.contentOrNull,
            limit = arguments[ARG_LIMIT]?.jsonPrimitive?.intOrNull,
            offset = arguments[ARG_OFFSET]?.jsonPrimitive?.intOrNull,
        )
    }
}
