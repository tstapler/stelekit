// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Per-MCP-source pending-suggestion ceiling (ADR-002) — bounds how much one noisy MCP client can pile up. */
const val MCP_PENDING_CAP = 20

/** Kept `private` — the only safe way to read this count is inside [McpPendingCapGuard]'s lock. */
private fun McpSuggestionSink.mcpPendingCount(): Int =
    pending.value.values.count { it.sourceProviderId.startsWith("mcp:") }

/**
 * The only place an MCP propose-tool may add a suggestion. Counting [McpSuggestionSink]'s current
 * `mcp:`-sourced entries and calling [McpSuggestionSink.propose] happen inside one [Mutex] critical
 * section, so pipelined/concurrent `tools/call` requests (legal under JSON-RPC — a client need not
 * await one response before sending the next) can never push the count past [MCP_PENDING_CAP].
 *
 * One instance is shared by both propose tools (constructed once per [McpAdapter]) — a per-tool
 * `Mutex` would not close the race between `propose_block_edit` and `propose_new_page` racing each
 * other for the same slot.
 */
class McpPendingCapGuard(private val sink: McpSuggestionSink) {
    private val mutex = Mutex()

    suspend fun proposeIfUnderCap(build: () -> PendingLlmSuggestion): Either<McpToolError.InboxFull, Unit> =
        mutex.withLock {
            if (sink.mcpPendingCount() >= MCP_PENDING_CAP) {
                McpToolError.InboxFull(MCP_PENDING_CAP).left()
            } else {
                sink.propose(build())
                Unit.right()
            }
        }
}
