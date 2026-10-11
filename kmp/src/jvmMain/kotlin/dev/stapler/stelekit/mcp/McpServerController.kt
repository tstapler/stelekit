// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.ui.StelekitViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val logger = Logger("McpServerController")

/**
 * Starts/stops an [McpSocketServer] in lockstep with [StelekitViewModel.uiState]'s
 * `mcpServerEnabled` toggle (graph-mcp-server Story 4.1.3) — never on construction, never from a
 * raw [LlmSuggestionInbox][dev.stapler.stelekit.llm.LlmSuggestionInbox] reference (routes through
 * [ViewModelSuggestionSink] instead, the architecture-review fix Epic 2.3 already applied to
 * [McpAdapter]). Owns its own [CoroutineExceptionHandler]-guarded scope per CLAUDE.md's
 * "Uncaught coroutine Throwables kill the process" rule — this class must never be constructed
 * from a `rememberCoroutineScope()`-derived scope.
 */
class McpServerController(
    private val viewModel: StelekitViewModel,
    private val graphManager: GraphManager,
    private val scope: CoroutineScope,
) {
    private val mcpClientIdentity = McpClientIdentity()
    private var socketServer: McpSocketServer? = null

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.error("Uncaught error in McpServerController", throwable)
    }

    /** Begins observing the toggle. Call once; the toggle collector runs for the controller's lifetime. */
    fun start() {
        scope.launch(exceptionHandler) {
            viewModel.uiState.map { it.mcpServerEnabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) attemptStart() else stopServer()
            }
        }
        // The bind-failure banner's Retry action.
        scope.launch(exceptionHandler) {
            viewModel.mcpRetryRequests.collect {
                if (viewModel.uiState.value.mcpServerEnabled) attemptStart()
            }
        }
    }


    private suspend fun attemptStart() {
        if (socketServer != null) return // already running — distinctUntilChanged already guards re-entry, this is belt-and-suspenders
        val graphId = graphManager.getActiveGraphId() ?: return // Story 4.1.3's AC: no graph open, never starts

        val suggestionSink = ViewModelSuggestionSink(viewModel)
        val adapter = McpAdapter(graphManager, suggestionSink, mcpClientIdentity::resolve)
        val server = McpSocketServer(graphId, adapter.buildServer(), mcpClientIdentity)
        try {
            server.start()
            socketServer = server
            viewModel.setMcpBindFailureMessage(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("MCP socket bind failed for graph ${graphId.value}", e)
            viewModel.setMcpBindFailureMessage(e.message ?: "MCP server failed to start")
        }
    }

    /** Stops the server and the toggle collector; the controller can't be restarted afterwards. */
    fun shutdown() {
        stopServer()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun stopServer() {
        socketServer?.stop()
        socketServer = null
        viewModel.setMcpBindFailureMessage(null)
    }
}
