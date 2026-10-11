// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.GraphManager
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents

private const val PAGE_RESOURCE_URI_TEMPLATE = "note://{uuid}"

/**
 * Registers the `note://{uuid}` resource template so an MCP client can fetch a page by URI
 * instead of a `read_page` tool call. Reuses [buildReadPageResult] verbatim — the same
 * uuid-resolution/rendering path `read_page` uses — so there is never a second, divergent read
 * path (`requirements.md`'s in-scope "Resources" item, Story 2.3.1).
 *
 * Only a template is registered, never [io.modelcontextprotocol.kotlin.sdk.server.Server.addResource]/`addResources` —
 * the SDK's `resources/list` handler enumerates only directly-added resources, so leaving that
 * empty keeps `resources/list` a zero-repository-call, `resources: []` response (CLAUDE.md's
 * bounded-reads rule: never an unbounded `getAllPages()`-style walk). `resources/templates/list`
 * still advertises this one template so clients discover the `note://{uuid}` scheme.
 */
fun Server.registerPageResource(graphManager: GraphManager) {
    addResourceTemplate(
        uriTemplate = PAGE_RESOURCE_URI_TEMPLATE,
        name = "SteleKit page",
        description = "A single SteleKit page by UUID. Use read_page to search by name or journal date.",
        mimeType = "text/markdown",
    ) { request, variables ->
        val uuid = variables["uuid"]
            ?: return@addResourceTemplate emptyResourceResult(request.uri, "Resource URI '${request.uri}' has no uuid segment.")

        val lookup = PageLookup(uuid = uuid, name = null, date = null)
        val toolResult = buildReadPageResult(graphManager, lookup, DEFAULT_BLOCK_LIMIT, blockOffset = 0)
        val text = (toolResult.content.firstOrNull() as? TextContent)?.text
            ?: "No page found for uuid '$uuid'."
        ReadResourceResult(
            contents = listOf(
                TextResourceContents(text = text, uri = request.uri, mimeType = "text/markdown"),
            ),
        )
    }
}

private fun emptyResourceResult(uri: String, message: String): ReadResourceResult =
    ReadResourceResult(contents = listOf(TextResourceContents(text = message, uri = uri, mimeType = "text/plain")))
