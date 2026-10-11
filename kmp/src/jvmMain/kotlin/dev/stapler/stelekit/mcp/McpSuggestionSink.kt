// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.llm.PendingLlmSuggestion
import dev.stapler.stelekit.ui.StelekitViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * The only seam MCP tools use to reach the approval inbox. `LlmSuggestionInbox` is private to
 * [StelekitViewModel], so tools go through the ViewModel's gated API like every other producer.
 */
interface McpSuggestionSink {
    val pending: StateFlow<Map<String, PendingLlmSuggestion>>

    fun propose(suggestion: PendingLlmSuggestion)
}

class ViewModelSuggestionSink(private val viewModel: StelekitViewModel) : McpSuggestionSink {
    override val pending: StateFlow<Map<String, PendingLlmSuggestion>> get() = viewModel.llmSuggestions

    override fun propose(suggestion: PendingLlmSuggestion) = viewModel.proposeLlmSuggestion(suggestion)
}
