// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.error.DomainError
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * MCP-adapter-level failure reasons that are not [DomainError]s. Every variant converts to a
 * `CallToolResult(isError = true)` so the calling LLM gets an actionable message instead of a
 * JSON-RPC protocol error.
 */
sealed class McpToolError {
    data object GraphNotOpen : McpToolError()

    data class AmbiguousPageName(val name: String, val matchCount: Int) : McpToolError()

    data class InboxFull(val cap: Int) : McpToolError()

    data class Timeout(val toolName: String, val timeoutMs: Long) : McpToolError()

    data class Internal(val cause: DomainError) : McpToolError()

    /** A tool handler threw something unmapped; the raw cause is deliberately kept out of the message. */
    data class UnexpectedFailure(val toolName: String, val cause: Throwable) : McpToolError()
}

fun McpToolError.toCallToolResult(): CallToolResult =
    CallToolResult(content = listOf(TextContent(userMessage())), isError = true)

private fun McpToolError.userMessage(): String = when (this) {
    McpToolError.GraphNotOpen ->
        "No graph is currently open in SteleKit."
    is McpToolError.AmbiguousPageName ->
        "The page name '$name' matches $matchCount pages. Use read_page with a page uuid to pick one."
    is McpToolError.InboxFull ->
        "SteleKit already has $cap pending MCP suggestions awaiting review. " +
            "Ask the user to accept or reject some in SteleKit before proposing more."
    is McpToolError.Timeout ->
        "Tool '$toolName' did not finish within ${timeoutMs}ms. Retry with a smaller request."
    is McpToolError.Internal ->
        "SteleKit could not complete the request: ${cause.message}"
    is McpToolError.UnexpectedFailure ->
        "Tool '$toolName' failed unexpectedly (${cause::class.simpleName}). " +
            "This is a SteleKit bug, not a retryable condition."
}
