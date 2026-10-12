// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.logging.Logger
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

const val DEFAULT_TOOL_TIMEOUT_MS = 15_000L

private val logger = Logger("McpToolTimeout")

/**
 * The only place tools are registered with the SDK's raw `addTool`. Bounds every handler with a
 * timeout and converts any thrown [Throwable] (including `Error`s such as OOM) into an
 * `isError` result, so a misbehaving handler cannot escape into the SDK session loop.
 */
fun Server.addTimedTool(
    name: String,
    description: String,
    inputSchema: ToolSchema = ToolSchema(),
    annotations: ToolAnnotations? = null,
    timeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS,
    handler: suspend (CallToolRequest) -> CallToolResult,
) {
    addTool(name = name, description = description, inputSchema = inputSchema, toolAnnotations = annotations) { request ->
        try {
            withTimeout(timeoutMs) { handler(request) }
        } catch (e: TimeoutCancellationException) {
            McpToolError.Timeout(name, timeoutMs).toCallToolResult()
        } catch (e: McpInvalidArgumentException) {
            CallToolResult(content = listOf(TextContent(e.message ?: "Invalid argument")), isError = true)
        } catch (e: CancellationException) {
            throw e // client disconnect / normal cancellation — not a tool bug
        } catch (e: Throwable) {
            logger.error("Unhandled exception in MCP tool '$name'", e)
            McpToolError.UnexpectedFailure(name, e).toCallToolResult()
        }
    }
}

/** A caller mistake (missing/invalid tool argument) — reported to the client as a plain tool error, not a SteleKit bug. */
internal class McpInvalidArgumentException(message: String) : IllegalArgumentException(message)
